package app.chompass.health.data.repo

import app.chompass.health.data.db.DictLabelText
import app.chompass.health.data.db.dao.DictDao
import app.chompass.health.domain.DictEntry
import app.chompass.health.domain.DictResolver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import app.chompass.health.domain.model.DictType

/**
 * 字典仓储。
 *
 * 全表只有几十行，这里直接把整表映射成内存 [DictResolver] 往下游发；
 * UI 层查表，不做 join。
 *
 * [builtinLabels] 是内置项的当前语言文案：`dict_item.label` 只是建库那一刻语言
 * 的快照，用户换语言后必须按 code 重新取文案，否则「家用指尖血」会一直停在中
 * 文。用户自建项（builtin = 0）不出现在这张表里，保留用户输入的原文。
 */
class DictRepository(
    private val dao: DictDao,
    private val builtinLabels: Map<Pair<DictType, String>, DictLabelText> = emptyMap(),
) {

    val resolver: Flow<DictResolver> = dao.observeAll().map { rows ->
        DictResolver(
            rows.map { row ->
                val localized = builtinLabels[row.type to row.code].takeIf { row.builtin }
                DictEntry(
                    type = row.type,
                    code = row.code,
                    label = localized?.label ?: row.label,
                    shortLabel = localized?.shortLabel ?: row.shortLabel,
                    sortOrder = row.sortOrder,
                    enabled = row.enabled,
                    builtin = row.builtin,
                )
            }
        )
    }

    /**
     * 新增一个用户自定义字典项，返回它的 code。
     *
     * 落库到 `dict_item`（builtin = 0），所以它和内置项走同一套解析、
     * 导出与统计路径——不需要另开一张表，也就不会出现"导出的 CSV 里没有这个词"。
     *
     * - 同名去重：用户第二次输入同一个词，直接复用已有项，不会堆一串重复项；
     * - code 形如 `U_SITE_1a2b3c`：带 `U_` 前缀，肉眼可辨是用户项，
     *   且不会与内置 code（FOOT_MTP1 这种大写下划线）撞；
     * - 限制 12 字：选项 chip 一行放不下太长的词，太长的词在界面上也是负担。
     *
     * @return code；输入为空或超长时返回 null
     */
    suspend fun addUserDefined(type: DictType, label: String, now: Long): String? {
        val text = label.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty() || text.length > 12) return null

        val existing = dao.allOf(type).firstOrNull { !it.builtin && it.label == text }
        if (existing != null) return existing.code

        val code = "U_${type.name}_${text.hashCode().toUInt().toString(16)}"
        dao.insertIgnore(
            listOf(
                app.chompass.health.data.db.entity.DictItemEntity(
                    type = type,
                    code = code,
                    label = text,
                    shortLabel = null,
                    // 排在所有内置项之后（内置项用 10 的间隔，最多到几百）
                    sortOrder = 900,
                    enabled = true,
                    builtin = false,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        )
        return code
    }
}
