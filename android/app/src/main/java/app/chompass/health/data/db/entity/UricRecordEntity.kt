package app.chompass.health.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.chompass.health.domain.model.UricInputUnit
import app.chompass.health.domain.model.UricMethod

/**
 * 血尿酸测量记录。
 *
 * 只存 μmol/L 的规范值（`value_umol`），`input_*` 两列仅用于回显用户当初怎么输的。
 * 同一指标若允许两种单位落库，阈值判断每次都要先统一，浮点比较还会出边界错。
 */
@Entity(
    tableName = "uric_record",
    indices = [
        Index(value = ["measured_at"]),
        Index(value = ["measured_date"]),
    ],
)
data class UricRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 规范值，μmol/L，四舍五入到整数 */
    @ColumnInfo(name = "value_umol")
    val valueUmol: Int,
    /** 用户原始输入值 */
    @ColumnInfo(name = "input_value")
    val inputValue: Double,
    @ColumnInfo(name = "input_unit")
    val inputUnit: UricInputUnit,
    /** 测量时刻 epoch millis */
    @ColumnInfo(name = "measured_at")
    val measuredAt: Long,
    /** 冗余列 yyyy-MM-dd，由 measuredAt 推导，不允许手工填 */
    @ColumnInfo(name = "measured_date")
    val measuredDate: String,
    /** 测量方式，code 与字典 URIC_METHOD 一致 */
    @ColumnInfo(name = "measure_method")
    val measureMethod: UricMethod = UricMethod.FINGER,
    val fasting: Boolean = false,
    val note: String? = null,
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
