package app.chompass.health.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import app.chompass.R
import app.chompass.health.data.db.dao.DoseDao
import app.chompass.health.data.db.dao.PainDao
import app.chompass.health.data.db.dao.SettingDao
import app.chompass.health.data.db.dao.UricDao
import app.chompass.health.data.db.entity.AppSettingEntity
import app.chompass.health.data.db.entity.DoseLogEntity
import app.chompass.health.data.db.entity.PainEpisodeEntity
import app.chompass.health.data.db.entity.PainUpdateEntity
import app.chompass.health.data.db.entity.SettingKeys
import app.chompass.health.data.db.entity.UricRecordEntity
import app.chompass.health.data.repo.DictRepository
import app.chompass.health.domain.model.DoseInstanceStatus
import app.chompass.health.domain.model.DoseLogKind
import app.chompass.health.domain.model.PainSide
import app.chompass.health.ui.HealthUnits
import app.chompass.health.util.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Number of rows in each table, for the "what am I about to export" line. */
data class HealthBackupCounts(
    val uric: Int = 0,
    val pain: Int = 0,
    val painUpdates: Int = 0,
    val dose: Int = 0,
) {
    /** Score marks belong to a flare, so they are not counted as records again. */
    val records: Int get() = uric + pain + dose
}

/**
 * Writes the health diary as one zip and hands it to the system share sheet.
 *
 * One file, not three: the share sheet behaves differently per target with
 * multiple attachments (some take only the first), and the user concludes "only
 * my uric acid was exported".
 *
 * No storage permission: the zip goes to the app's own `cache/capture` directory
 * and is handed out through the existing [FileProvider] authority.
 */
class HealthExportManager(
    private val context: Context,
    private val uricDao: UricDao,
    private val painDao: PainDao,
    private val doseDao: DoseDao,
    private val settingDao: SettingDao,
    private val dictRepository: DictRepository,
    private val clock: () -> Long,
) {

    /** Where the archive ended up plus what went into it. */
    data class Artifact(val uri: Uri, val fileName: String, val counts: HealthBackupCounts)

    suspend fun counts(): HealthBackupCounts = withContext(Dispatchers.IO) {
        HealthBackupCounts(
            uric = uricDao.allForExport().size,
            pain = painDao.allForExport().size,
            painUpdates = painDao.allUpdatesForExport().size,
            dose = doseDao.allLogsForExport().size,
        )
    }

    suspend fun export(): Artifact = withContext(Dispatchers.IO) {
        val now = clock()
        val dict = dictRepository.resolver.first()
        val copy = csvStrings(context)

        val uric = uricDao.allForExport()
        val pain = painDao.allForExport()
        val updates = painDao.allUpdatesForExport()
        val dose = doseDao.allLogsForExport()

        val counts = HealthBackupCounts(
            uric = uric.size,
            pain = pain.size,
            painUpdates = updates.size,
            dose = dose.size,
        )

        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        // Old archives only pile up and make the newest backup ambiguous.
        dir.listFiles { f -> f.name.startsWith(FILE_PREFIX) }?.forEach { it.delete() }
        val file = File(dir, "$FILE_PREFIX${fileStamp(now)}.zip")

        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.put(ENTRY_URIC, HealthCsvExporter.uric(uric, copy, dict))
            zip.put(ENTRY_PAIN, HealthCsvExporter.pain(pain, copy, dict))
            zip.put(
                ENTRY_PAIN_UPDATE,
                HealthCsvExporter.painUpdate(
                    rows = updates,
                    episodeStart = pain.associate { it.id to it.startedAt },
                    s = copy,
                ),
            )
            zip.put(ENTRY_DOSE, HealthCsvExporter.doseLog(dose, copy))
            zip.put("readme.txt", readme(context, now))
        }

        settingDao.put(
            AppSettingEntity(
                key = SettingKeys.EXPORT_LAST_AT,
                value = now.toString(),
                updatedAt = now,
            )
        )

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        Artifact(uri = uri, fileName = file.name, counts = counts)
    }

    fun shareIntent(artifact: Artifact): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, artifact.uri)
            putExtra(Intent.EXTRA_SUBJECT, artifact.fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, context.getString(R.string.health_data_export)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun fileStamp(at: Long): String =
        TimeUtils.toLocalDateTime(at)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))

    private companion object {
        const val CACHE_DIR = "capture"
        const val FILE_PREFIX = "health-diary-"
        const val ENTRY_URIC = "uric.csv"
        const val ENTRY_PAIN = "pain.csv"
        const val ENTRY_PAIN_UPDATE = "pain_update.csv"
        const val ENTRY_DOSE = "dose_log.csv"
    }
}

/**
 * Merges a backup back in. **Never** overwrites or clears.
 *
 * The real scenario is "move the records from the old phone", where the new
 * phone already has a few entries typed while setting it up. An overwrite mode
 * would delete data the user cannot even enumerate, so it is not offered.
 *
 * Duplicates are found by business key (minute + value / minute + drug), so
 * importing the same archive twice is safe.
 *
 * ## Import does not touch stock
 *
 * `dose_log` is history: "what I took back then", not "what is in the drawer
 * now". Deducting stock here would leave the user with a mysteriously empty box
 * count after a phone swap.
 */
class HealthImportManager(
    private val context: Context,
    private val uricDao: UricDao,
    private val painDao: PainDao,
    private val doseDao: DoseDao,
    private val dictRepository: DictRepository,
    private val clock: () -> Long,
) {

    enum class Failure { UNREADABLE, NO_TABLES }

    data class Result(
        val uric: Int = 0,
        val pain: Int = 0,
        val painUpdates: Int = 0,
        val dose: Int = 0,
        val duplicates: Int = 0,
        val skipped: Int = 0,
        val issues: List<HealthCsvImporter.Issue> = emptyList(),
        val failure: Failure? = null,
    ) {
        val imported: Int get() = uric + pain + dose
    }

    suspend fun import(uri: Uri): Result = withContext(Dispatchers.IO) {
        val dict = dictRepository.resolver.first()
        val files = runCatching { readZip(uri) }.getOrNull()
            ?: return@withContext Result(failure = Failure.UNREADABLE)
        if (files.isEmpty()) return@withContext Result(failure = Failure.NO_TABLES)

        val uricParsed = files[ENTRY_URIC]?.let { HealthCsvImporter.parseUric(it, ENTRY_URIC) }
        val painParsed = files[ENTRY_PAIN]?.let { HealthCsvImporter.parsePain(it, dict, ENTRY_PAIN) }
        val updateParsed = files[ENTRY_PAIN_UPDATE]
            ?.let { HealthCsvImporter.parsePainUpdate(it, ENTRY_PAIN_UPDATE) }
        val doseParsed = files[ENTRY_DOSE]?.let { HealthCsvImporter.parseDoseLog(it, ENTRY_DOSE) }

        val now = clock()
        var duplicates = 0
        var skipped = (uricParsed?.skipped ?: 0) +
            (painParsed?.skipped ?: 0) +
            (updateParsed?.skipped ?: 0) +
            (doseParsed?.skipped ?: 0)
        val issues = buildList {
            addAll(uricParsed?.issues.orEmpty())
            addAll(painParsed?.issues.orEmpty())
            addAll(updateParsed?.issues.orEmpty())
            addAll(doseParsed?.issues.orEmpty())
        }

        var uric = 0
        uricParsed?.rows?.forEach { row ->
            val duplicate = runCatching {
                uricDao.countAtMinute(row.measuredAt / MINUTE_MS, row.valueUmol) > 0
            }.getOrDefault(false)
            if (duplicate) {
                duplicates++
            } else {
                uricDao.insert(
                    UricRecordEntity(
                        valueUmol = row.valueUmol,
                        inputValue = row.inputValue,
                        inputUnit = row.inputUnit,
                        measuredAt = row.measuredAt,
                        measuredDate = TimeUtils.toDateString(row.measuredAt),
                        measureMethod = row.method,
                        fasting = row.fasting,
                        note = row.note,
                        createdAt = now,
                        updatedAt = now,
                    )
                )
                uric++
            }
        }

        var pain = 0
        painParsed?.rows?.forEach { row ->
            val duplicate = runCatching {
                painDao.countStartedMinute(row.startedAt / MINUTE_MS) > 0
            }.getOrDefault(false)
            if (duplicate) {
                duplicates++
            } else {
                runCatching {
                    painDao.insert(
                        PainEpisodeEntity(
                            startedAt = row.startedAt,
                            endedAt = row.endedAt,
                            siteCode = row.siteCode,
                            side = row.side,
                            nrs = row.nrs,
                            natures = row.natures,
                            triggers = row.triggers,
                            affectWalk = row.affectWalk,
                            affectSleep = row.affectSleep,
                            note = row.note,
                            createdAt = now,
                            updatedAt = now,
                        )
                    )
                }.onSuccess { pain++ }.onFailure { skipped++ }
            }
        }

        var painUpdates = 0
        updateParsed?.rows?.forEach { row ->
            // The episode is found by its start minute: ids are local to an
            // install, so they are useless in a file that crosses devices.
            val episode = runCatching {
                painDao.findByStartMinute(row.episodeStart / MINUTE_MS)
            }.getOrNull()
            if (episode == null) {
                // The parent flare is missing (deleted, or the file was edited).
                // An orphan score would point at nothing.
                skipped++
                return@forEach
            }
            val duplicate = runCatching {
                painDao.updateAtMinute(episode.id, row.at / MINUTE_MS) != null
            }.getOrDefault(false)
            if (duplicate) {
                duplicates++
            } else {
                runCatching {
                    painDao.insertUpdate(
                        PainUpdateEntity(
                            episodeId = episode.id,
                            at = row.at,
                            nrs = row.nrs,
                            note = row.note,
                            createdAt = now,
                        )
                    )
                }.onSuccess { painUpdates++ }.onFailure { skipped++ }
            }
        }

        var dose = 0
        doseParsed?.rows?.forEach { row ->
            val duplicate = runCatching {
                doseDao.countLogAtMinute(row.actualAt / MINUTE_MS, row.drugName) > 0
            }.getOrDefault(false)
            if (duplicate) {
                duplicates++
                return@forEach
            }

            // Bind the log to this device's planned instance when one matches, or
            // the day would show both "overdue" and "extra dose" — as if the user
            // had taken it twice.
            val instance = row.planAt?.let { plan ->
                runCatching {
                    doseDao.findViewByIdByPlan(
                        date = TimeUtils.toDateString(plan),
                        drugName = row.drugName,
                        minute = plan / MINUTE_MS,
                    )
                }.getOrNull()
            }
            // "Already recorded here" is decided by the presence of a log, not by
            // the instance status: TAKEN without a log is an inconsistent state
            // worth repairing, not a duplicate.
            if (instance != null && doseDao.activeLogFor(instance.id) != null) {
                duplicates++
                return@forEach
            }

            doseDao.insertLog(
                DoseLogEntity(
                    instanceId = instance?.id,
                    medicationId = instance?.medicationId,
                    actualAt = row.actualAt,
                    actualDate = TimeUtils.toDateString(row.actualAt),
                    planAt = row.planAt,
                    deviationMinutes = row.deviationMinutes,
                    drugName = row.drugName,
                    strengthValue = row.strengthValue,
                    strengthUnit = row.strengthUnit,
                    intakeUnits = row.intakeUnits,
                    unitLabel = row.unitLabel,
                    kind = row.kind,
                    note = row.note,
                    createdAt = now,
                )
            )
            // Also mark the instance taken so the day reads "taken · 12 min late"
            // rather than "overdue".
            if (instance != null && instance.status == DoseInstanceStatus.PENDING) {
                runCatching {
                    doseDao.transit(
                        id = instance.id,
                        expected = DoseInstanceStatus.PENDING,
                        next = DoseInstanceStatus.TAKEN,
                        reason = null,
                        at = row.actualAt,
                    )
                }
            }
            dose++
        }

        Result(
            uric = uric,
            pain = pain,
            painUpdates = painUpdates,
            dose = dose,
            duplicates = duplicates,
            skipped = skipped,
            issues = issues,
        )
    }

    /**
     * Reads the wanted entries into memory. `ZipInputStream` rather than
     * extracting to disk: a backup is well under a megabyte, and temporary files
     * buy nothing but cleanup code.
     */
    private fun readZip(uri: Uri): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val input = context.contentResolver.openInputStream(uri) ?: error("cannot open")
        input.use { stream -> readEntries(stream).forEach { (name, text) -> out[name] = text } }
        return out
    }

    private fun readEntries(input: InputStream): List<Pair<String, String>> {
        val entries = mutableListOf<Pair<String, String>>()
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.substringAfterLast('/')
                if (name in WANTED && !entry.isDirectory) {
                    entries += name to zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val ENTRY_URIC = "uric.csv"
        const val ENTRY_PAIN = "pain.csv"
        const val ENTRY_PAIN_UPDATE = "pain_update.csv"
        const val ENTRY_DOSE = "dose_log.csv"
        val WANTED = setOf(ENTRY_URIC, ENTRY_PAIN, ENTRY_PAIN_UPDATE, ENTRY_DOSE)
    }
}

/** The copy the CSV cells need, resolved once from resources. */
internal fun csvStrings(context: Context): HealthCsvStrings = HealthCsvStrings(
    yes = context.getString(R.string.health_csv_yes),
    no = context.getString(R.string.health_csv_no),
    unitUmol = HealthUnits.UMOL,
    unitMgdl = HealthUnits.MGDL,
    side = mapOf(
        PainSide.LEFT to context.getString(R.string.health_side_left),
        PainSide.RIGHT to context.getString(R.string.health_side_right),
        PainSide.BOTH to context.getString(R.string.health_side_both),
    ),
    kind = mapOf(
        DoseLogKind.SCHEDULED to context.getString(R.string.health_dose_kind_scheduled),
        DoseLogKind.BACKFILL to context.getString(R.string.health_dose_kind_backfill),
        DoseLogKind.PRN to context.getString(R.string.health_dose_kind_prn),
        DoseLogKind.EXTRA to context.getString(R.string.health_dose_kind_extra),
    ),
)

/**
 * The archive's own documentation. Written in the app language: the CSV headers
 * stay English because they are the import contract, and this file is where the
 * reader (or the doctor) learns what each column means.
 */
private fun readme(context: Context, exportedAt: Long): String = buildString {
    append(CsvTable.BOM)
    appendLine(context.getString(R.string.health_export_readme_title))
    appendLine(
        context.getString(
            R.string.health_export_readme_exported_at,
            TimeUtils.toLocalDateTime(exportedAt)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
        )
    )
    appendLine()
    appendLine(context.getString(R.string.health_export_readme_files))
    appendLine()
    appendLine(context.getString(R.string.health_export_readme_conventions))
    appendLine(context.getString(R.string.health_export_readme_codes))
    appendLine(context.getString(R.string.health_export_readme_units))
    appendLine(context.getString(R.string.health_export_readme_deleted))
    appendLine()
    appendLine(context.getString(R.string.health_export_readme_privacy))
    appendLine(context.getString(R.string.health_export_readme_disclaimer))
}

private fun ZipOutputStream.put(name: String, content: String) {
    putNextEntry(ZipEntry(name))
    write(content.toByteArray(Charsets.UTF_8))
    closeEntry()
}
