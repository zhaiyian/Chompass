package app.chompass.health.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 每日计划时间点。
 *
 * 不做版本化：历史保护已由 dose_instance 的快照字段承担。改时间点时，
 * 已发生与已记录的实例不动，只重算"今天及以后、plan_at > now、status = PENDING"
 * 的实例（doc/04 §2.3）。这比维护 effective_from/effective_to 的版本表更简单。
 */
@Entity(
    tableName = "med_schedule",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medication_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["medication_id", "time_of_day"], unique = true),
    ],
)
data class MedScheduleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "medication_id")
    val medicationId: Long,
    /** HH:mm 本地时间，如 08:00 */
    @ColumnInfo(name = "time_of_day")
    val timeOfDay: String,
    /** 同一天内的序号，仅用于稳定排序 */
    val ordinal: Int = 0,
)
