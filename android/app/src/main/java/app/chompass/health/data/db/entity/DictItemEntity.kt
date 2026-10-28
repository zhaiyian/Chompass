package app.chompass.health.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.chompass.health.domain.model.DictType

/**
 * 字典项：只承载「文案、顺序、启停」，不承载行为。
 *
 * 三条硬纪律：
 * 1. 种子只用 INSERT OR IGNORE，**永不 UPDATE 已有项**——用户可能把
 *    「足第一跖趾」改成「大脚趾」，发版覆盖会把它抹掉。
 * 2. 不物理删除，只 `enabled = 0`。历史记录引用的 code 仍要能解析出文案。
 * 3. 解析不到时回落 code 原文 + 「已停用」，不崩溃、不隐藏。
 */
@Entity(
    tableName = "dict_item",
    indices = [
        Index(value = ["type", "code"], unique = true),
        Index(value = ["type", "enabled", "sort_order"]),
    ],
)
data class DictItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: DictType,
    /** 稳定标识，写入业务表的就是它 */
    val code: String,
    /** 展示文案，用户可改写 */
    val label: String,
    /** 窄容器用短标签（B1 条形图 / B3 角标）；null 时回落 label */
    @ColumnInfo(name = "short_label")
    val shortLabel: String? = null,
    /** 同 type 内升序；内置项留 10 的间隔，方便插项不用重排 */
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,
    /** 0 = 停用：不出现在选择器，但历史仍可解析 */
    val enabled: Boolean = true,
    /** 1 = 内置项，不允许物理删除，只能停用或改文案 */
    val builtin: Boolean = true,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
