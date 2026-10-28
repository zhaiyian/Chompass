package app.chompass.health.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import app.chompass.health.data.db.dao.DictDao
import app.chompass.health.data.db.dao.DoseDao
import app.chompass.health.data.db.dao.MedDao
import app.chompass.health.data.db.dao.PainDao
import app.chompass.health.data.db.dao.SettingDao
import app.chompass.health.data.db.dao.UricDao
import app.chompass.health.data.db.entity.AppSettingEntity
import app.chompass.health.data.db.entity.DictItemEntity
import app.chompass.health.data.db.entity.DoseInstanceEntity
import app.chompass.health.data.db.entity.DoseLogEntity
import app.chompass.health.data.db.entity.MedScheduleEntity
import app.chompass.health.data.db.entity.MedicationEntity
import app.chompass.health.data.db.entity.PainEpisodeEntity
import app.chompass.health.data.db.entity.PainJointEntity
import app.chompass.health.data.db.entity.PainUpdateEntity
import app.chompass.health.data.db.entity.SettingKeys
import app.chompass.health.data.db.entity.UricRecordEntity

/**
 * Health diary database: 8 business tables + `dict_item`.
 *
 * Migrated from the standalone "UricDiary" app, which owned a database file
 * named `uric_diary.db`. Ported as a **separate** Room database (not folded into
 * Chompass' DataStore / JSON buckets) so the health records keep their own
 * schema, its own migration history and its own queries — the diary never has
 * to touch, or be touched by, the nutrition side.
 *
 * `exportSchema = true`: the schema JSON under `app/schemas/` must be committed,
 * because migration review depends on diffing it rather than prose.
 * `version = 4` is the source app's last schema version; a fresh install creates
 * v4 directly and only ported installs would ever replay [Migrations.ALL].
 *
 * **Changing the schema takes all three steps**: edit the entity, bump
 * `version`, add a `Migration` to [Migrations.ALL]. Skipping one makes Room
 * throw on startup for existing installs (not a graceful degrade, not a wipe).
 */
@Database(
    entities = [
        UricRecordEntity::class,
        PainEpisodeEntity::class,
        PainUpdateEntity::class,
        PainJointEntity::class,
        MedicationEntity::class,
        MedScheduleEntity::class,
        DoseInstanceEntity::class,
        DoseLogEntity::class,
        AppSettingEntity::class,
        DictItemEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class HealthDatabase : RoomDatabase() {

    abstract fun uricDao(): UricDao
    abstract fun painDao(): PainDao
    abstract fun medDao(): MedDao
    abstract fun doseDao(): DoseDao
    abstract fun settingDao(): SettingDao
    abstract fun dictDao(): DictDao

    companion object {
        const val NAME = "chompass_health.db"

        @Volatile
        private var instance: HealthDatabase? = null

        fun get(context: Context): HealthDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): HealthDatabase =
            Room.databaseBuilder(context, HealthDatabase::class.java, NAME)
                .addMigrations(*Migrations.ALL)
                .addCallback(SeedCallback(context))
                .build()

        /**
         * Fills in what Room annotations cannot express: the initial dictionary
         * and setting rows (idempotent).
         *
         * **Never create structures here that are missing from the schema JSON**
         * (e.g. a partial index built with `execSQL`). Room's post-migration
         * check compares index sets for equality, so an extra object created in
         * `onCreate` makes *every* version upgrade crash — the trap only fires
         * on upgrade, never on a fresh install, which is why it is easy to ship.
         */
        private class SeedCallback(private val context: Context) : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                val now = System.currentTimeMillis()

                DictSeed.insertAll(db, context, now)
                JointSeed.insertAll(db)
                JointSeed.syncDictItems(db, now)

                SettingKeys.defaults.forEach { (key, value) ->
                    val values = ContentValues().apply {
                        put("key", key)
                        put("value", value)
                        put("updated_at", now)
                    }
                    db.insert("app_setting", SQLiteDatabase.CONFLICT_IGNORE, values)
                }
            }
        }
    }
}
