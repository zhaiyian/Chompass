package app.chompass.health.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.chompass.health.domain.model.DoseInstanceStatus
import app.chompass.health.domain.model.MealRelation
import app.chompass.health.domain.model.SkipReason

/**
 * 计划实例（今日清单的数据源）。
 *
 * 药名/规格/剂量/餐 relation 全是**写入时快照**：之后改药不影响今天已生成的实例。
 * `(medication_id, plan_at)` 唯一索引是幂等生成的基石——`ensureInstances(today)`
 * 可以每次 onResume 无脑调用，靠 `INSERT OR IGNORE` 保证不重复。
 */
@Entity(
    tableName = "dose_instance",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medication_id"],
        ),
        ForeignKey(
            entity = MedScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["schedule_id"],
        ),
    ],
    indices = [
        Index(value = ["medication_id", "plan_at"], unique = true),
        Index(value = ["plan_date", "status"]),
        Index(value = ["plan_at"]),
        Index(value = ["schedule_id"]),
    ],
)
data class DoseInstanceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "medication_id")
    val medicationId: Long,
    /** 按需药为空 */
    @ColumnInfo(name = "schedule_id")
    val scheduleId: Long? = null,
    /** 计划时刻 epoch millis（由 plan_date + time_of_day 合成） */
    @ColumnInfo(name = "plan_at")
    val planAt: Long,
    /** yyyy-MM-dd，冗余列 */
    @ColumnInfo(name = "plan_date")
    val planDate: String,
    @ColumnInfo(name = "drug_name")
    val drugName: String,
    @ColumnInfo(name = "strength_value")
    val strengthValue: Double? = null,
    @ColumnInfo(name = "strength_unit")
    val strengthUnit: String? = null,
    @ColumnInfo(name = "intake_units")
    val intakeUnits: Double,
    @ColumnInfo(name = "unit_label")
    val unitLabel: String,
    @ColumnInfo(name = "meal_relation")
    val mealRelation: MealRelation,
    val status: DoseInstanceStatus = DoseInstanceStatus.PENDING,
    @ColumnInfo(name = "skip_reason")
    val skipReason: SkipReason? = null,
    @ColumnInfo(name = "generated_at")
    val generatedAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
