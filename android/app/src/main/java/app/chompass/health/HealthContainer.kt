package app.chompass.health

import android.content.Context
import app.chompass.health.data.db.DictSeed
import app.chompass.health.data.db.HealthDatabase
import app.chompass.health.data.db.JointSeed
import app.chompass.health.data.db.entity.SettingKeys
import app.chompass.health.data.repo.DictRepository
import app.chompass.health.data.repo.MedRepository
import app.chompass.health.data.repo.MedRepositoryImpl
import app.chompass.health.data.repo.PainRepository
import app.chompass.health.data.repo.PainRepositoryImpl
import app.chompass.health.data.repo.UricRepository
import app.chompass.health.data.repo.UricRepositoryImpl
import app.chompass.health.export.HealthExportManager
import app.chompass.health.export.HealthImportManager
import app.chompass.health.reminder.HealthReminderCenter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Health-diary graph (uric acid / pain / medication), owned by [app.chompass.AppContainer].
 *
 * Deliberately small and manual, matching how the rest of Chompass wires its
 * dependencies: one Room database, one repository per module, no DI framework.
 * The clock is injected so tests can pin "now" without freezing the JVM clock.
 */
class HealthContainer(
    context: Context,
    /** Exposed so the screens can derive "days since" without reading the system clock. */
    val clock: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext
    private val db = HealthDatabase.get(appContext)

    val uricRepository: UricRepository =
        UricRepositoryImpl(db.uricDao(), db.settingDao(), clock)

    /**
     * Joint names are resolved here, not in the screens: `pain_joint` stores the
     * names of the language the database was created in, and every consumer of
     * the catalogue (cascade picker, site label, insights) must see the current
     * language without each one re-applying the override.
     */
    val painRepository: PainRepository = PainRepositoryImpl(
        dao = db.painDao(),
        clock = clock,
        jointNames = JointSeed.localizedNames(appContext),
    )

    /**
     * Built-in dictionary labels follow the current UI language: the rows in
     * `dict_item` are only a snapshot from the language the database was
     * created in.
     *
     * The joint catalogue projects itself into `dict_item(SITE)` so site labels
     * resolve through one path; those projected labels need the same override.
     */
    val dictRepository = DictRepository(
        dao = db.dictDao(),
        builtinLabels = DictSeed.localizedLabels(appContext) +
            JointSeed.localizedSiteLabels(appContext),
    )

    /** Flow of [app.chompass.health.domain.DictResolver] rebuilt on every dictionary write. */
    val dictResolver = dictRepository.resolver

    /** Dosage planning and logging; needs the database itself because saving a medication spans tables. */
    val medRepository: MedRepository = MedRepositoryImpl(
        database = db,
        medDao = db.medDao(),
        doseDao = db.doseDao(),
        clock = clock,
    )

    /**
     * Medication reminders: the bridge between pending doses and system alarms.
     *
     * Lazy because arming alarms is not needed to draw a screen, and because the
     * broadcast receivers are the only other consumers. Both the switches and the
     * re-arm pass through this one object so they cannot disagree.
     */
    val reminders: HealthReminderCenter by lazy(LazyThreadSafetyMode.NONE) {
        HealthReminderCenter(
            context = appContext,
            meds = medRepository,
            settings = db.settingDao(),
            clock = clock,
        )
    }

    /** CSV archive of the whole diary, handed to the system share sheet. */
    val backupExport: HealthExportManager by lazy(LazyThreadSafetyMode.NONE) {
        HealthExportManager(
            context = appContext,
            uricDao = db.uricDao(),
            painDao = db.painDao(),
            doseDao = db.doseDao(),
            settingDao = db.settingDao(),
            dictRepository = dictRepository,
            clock = clock,
        )
    }

    /** Merges an archive back in; never deletes anything that is already here. */
    val backupImport: HealthImportManager by lazy(LazyThreadSafetyMode.NONE) {
        HealthImportManager(
            context = appContext,
            uricDao = db.uricDao(),
            painDao = db.painDao(),
            doseDao = db.doseDao(),
            dictRepository = dictRepository,
            clock = clock,
        )
    }

    /**
     * When the diary was last exported, null if never.
     *
     * `0` is the seeded sentinel for "never"; passing it through would render as
     * "last export 1970-01-01" and the user would believe they had a backup.
     */
    val lastExportAt: Flow<Long?> = db.settingDao().observeAll().map { rows ->
        rows.firstOrNull { it.key == SettingKeys.EXPORT_LAST_AT }
            ?.value?.toLongOrNull()?.takeIf { it > 0 }
    }
}
