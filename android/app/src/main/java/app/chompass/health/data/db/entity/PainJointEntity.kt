package app.chompass.health.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 疼痛部位：一条 = 一个**关节**，大区与趾 / 指冗余在行上。
 *
 * 为什么不是 region / digit / joint 三张表：
 * 这张表只有几十行、永不增长（内置解剖枚举），三级查询却要 join 两次。
 * 冗余成一行之后，一次 `SELECT *` 就能在内存里拼出级联树（`PainSiteCatalog`），
 * 而"某大区有没有趾 / 指这一层"恰好就是 `digit_id IS NULL` 与否。
 *
 * `disease_tag` 只作内部排序与统计分组，**不进界面**：
 * 在尿酸 App 里暗示"这处更像类风湿"就是医疗判断，越界了。
 */
@Entity(tableName = "pain_joint")
data class PainJointEntity(
    @PrimaryKey
    @ColumnInfo(name = "joint_id")
    val jointId: String,
    @ColumnInfo(name = "region_id")
    val regionId: String,
    @ColumnInfo(name = "region_name")
    val regionName: String,
    @ColumnInfo(name = "region_order")
    val regionOrder: Int,
    /** null = 这个大区没有"哪一根趾 / 指"这一层（手腕、膝盖、其他） */
    @ColumnInfo(name = "digit_id")
    val digitId: String? = null,
    @ColumnInfo(name = "digit_name")
    val digitName: String? = null,
    @ColumnInfo(name = "digit_order")
    val digitOrder: Int? = null,
    /** 按钮上的字，必须短（≤7 字），如「根部」 */
    @ColumnInfo(name = "display_name")
    val displayName: String,
    /** 解剖名，括号里给就诊时用，如「第一跖趾关节」 */
    @ColumnInfo(name = "anatomy_name")
    val anatomyName: String,
    /** GOUT_HIGH / RA_HIGH / BOTH / GENERAL，**仅内部用** */
    @ColumnInfo(name = "disease_tag")
    val diseaseTag: String = "GENERAL",
    /** false = 不选侧别（脊柱、说不清的部位），直接记 NA */
    @ColumnInfo(name = "side_required")
    val sideRequired: Boolean = true,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,
)
