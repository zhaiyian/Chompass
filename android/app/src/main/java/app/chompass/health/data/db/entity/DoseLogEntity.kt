package app.chompass.health.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.chompass.health.domain.model.DoseLogKind

/**
 * 服药记录（只追加）。
 *
 * 撤销 = 写 [revokedAt]，这让"撤销后重新记录"天然产生两条历史，符合审计直觉。
 *
 * **允许的 UPDATE 只有两项：`actual_at` 与 `intake_units`**（连同 `actual_date` /
 * `deviation_minutes` 一起重算）。这两项都是"用户会记错、改完不影响历史结论"的字段：
 * 几点吃的、吃了几片。改服用量必须同步把库存差额补回去，否则账对不上。
 *
 * 不能改的是**药名与每片含量**：那是另一味药 / 另一种规格，改它等于把这条历史
 * 挪到别的东西头上——要纠正只能删了重记，留得下痕迹。
 */
@Entity(
    tableName = "dose_log",
    foreignKeys = [
        ForeignKey(
            entity = DoseInstanceEntity::class,
            parentColumns = ["id"],
            childColumns = ["instance_id"],
        ),
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medication_id"],
        ),
    ],
    indices = [
        Index(value = ["actual_at"]),
        Index(value = ["medication_id", "actual_at"]),
        Index(value = ["instance_id"]),
    ],
)
data class DoseLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 按需 / 额外记录为空 */
    @ColumnInfo(name = "instance_id")
    val instanceId: Long? = null,
    /**
     * 可空：从备份导入的历史记录很可能对应不到本机任何一味药
     * （换机后药单是空的）。v1→v2 迁移把它从 NOT NULL 改成可空，
     * 否则导入必然撞外键。真机上手动记的记录永远有值。
     */
    @ColumnInfo(name = "medication_id")
    val medicationId: Long? = null,
    @ColumnInfo(name = "actual_at")
    val actualAt: Long,
    /** yyyy-MM-dd，冗余列 */
    @ColumnInfo(name = "actual_date")
    val actualDate: String,
    /** 快照：对应计划的时刻 */
    @ColumnInfo(name = "plan_at")
    val planAt: Long? = null,
    /** actual_at - plan_at 的分钟数；准时/延迟由此判定 */
    @ColumnInfo(name = "deviation_minutes")
    val deviationMinutes: Int? = null,
    @ColumnInfo(name = "drug_name")
    val drugName: String,
    @ColumnInfo(name = "strength_value")
    val strengthValue: Double? = null,
    @ColumnInfo(name = "strength_unit")
    val strengthUnit: String? = null,
    @ColumnInfo(name = "intake_units")
    val intakeUnits: Double,
    /**
     * 服用量单位快照（片 / 粒 / ml）。
     *
     * v1 漏了这个字段，导致 C2 只能硬编码"片"——用 ml 计量的药、
     * 或"粒"的胶囊，记录列表上会显示成"1 片"。v1→v2 迁移补的。
     * 存的是快照而不是 join medication：改药不该改历史。
     */
    @ColumnInfo(name = "unit_label", defaultValue = "片")
    val unitLabel: String = "片",
    val kind: DoseLogKind,
    val note: String? = null,
    /** 非空即视为不存在 */
    @ColumnInfo(name = "revoked_at")
    val revokedAt: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
