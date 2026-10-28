package app.chompass.health.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.chompass.health.domain.model.MealRelation
import app.chompass.health.domain.model.MedStatus
import app.chompass.health.model.MedCategory

/**
 * 药物。
 *
 * 两个正交维度必须分开：
 * - [category] 回答"这个药属于哪一类"，只用于分组筛选与视觉分类；
 * - [isPrn] 回答"它有没有应该吃的时刻"，决定是否生成实例、是否计入依从率、
 *   是否画库存天数进度条。
 *
 * 用 `category == ACUTE` 代替按需判断，会把「秋水仙碱每日 3 次 × 3 天」这类
 * 急性期规律服药整条链路静默跳过——用户看不到待服提醒，且不知道为什么。
 */
@Entity(
    tableName = "medication",
    indices = [
        Index(value = ["status"]),
    ],
)
data class MedicationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val category: MedCategory,
    /** 1 = 按需服用：不生成实例、不进依从率、不画库存天数 */
    @ColumnInfo(name = "is_prn")
    val isPrn: Boolean = false,
    /** 规格数值，如 0.5 */
    @ColumnInfo(name = "strength_value")
    val strengthValue: Double? = null,
    /** 规格单位：mg / g / ml */
    @ColumnInfo(name = "strength_unit")
    val strengthUnit: String? = null,
    /** 每次服用几个最小单位 */
    @ColumnInfo(name = "intake_units")
    val intakeUnits: Double = 1.0,
    /** 最小单位展示名：片 / 粒 / ml */
    @ColumnInfo(name = "unit_label")
    val unitLabel: String = "片",
    /** 每日次数，必须与 med_schedule 行数一致（写入时校验） */
    @ColumnInfo(name = "freq_per_day")
    val freqPerDay: Int = 1,
    @ColumnInfo(name = "meal_relation")
    val mealRelation: MealRelation = MealRelation.NONE,
    /** 剩余最小单位数；null = 不跟踪库存 */
    @ColumnInfo(name = "stock_units")
    val stockUnits: Double? = null,
    /** 续药提醒阈值（天） */
    @ColumnInfo(name = "stock_alert_days")
    val stockAlertDays: Int = 14,
    @ColumnInfo(name = "reminder_enabled")
    val reminderEnabled: Boolean = true,
    val status: MedStatus = MedStatus.ACTIVE,
    @ColumnInfo(name = "started_on")
    val startedOn: String? = null,
    @ColumnInfo(name = "stopped_on")
    val stoppedOn: String? = null,
    val note: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
