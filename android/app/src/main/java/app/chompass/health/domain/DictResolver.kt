package app.chompass.health.domain

import app.chompass.health.domain.model.DictType

/**
 * 字典项在领域层的轻量投影。
 * 不直接依赖 Room 实体，方便纯 JVM 单测。
 */
data class DictEntry(
    val type: DictType,
    val code: String,
    val label: String,
    val shortLabel: String? = null,
    val sortOrder: Int = 0,
    val enabled: Boolean = true,
    /**
     * 内置项（种子）还是用户自己添加的。
     *
     * 界面上要用不同样式区分：内置项是普通 chip，用户项描边偏蓝且字号小一号，
     * 让用户一眼看出"这个是我自己加的"。
     */
    val builtin: Boolean = true,
)

/** 解析结果。`resolved == false` 表示该 code 在字典里已不存在（被停用或来自旧版本）。 */
data class DictLabel(
    val text: String,
    val enabled: Boolean,
    val resolved: Boolean,
)

/**
 * 字典解析器。
 *
 * 全表只有几十行，启动时一次性读进内存 Map，UI 层查表——
 * 逐条 join 会让"一次发作 3 个标签"变成 3 次 join，收益为零。
 *
 * 解析纪律：解析不到时回落 code 原文并标记「未启用」，
 * 不抛异常、不隐藏记录。否则用户会以为数据坏了。
 */
class DictResolver(entries: List<DictEntry> = emptyList()) {

    private val byKey: Map<Pair<DictType, String>, DictEntry> =
        entries.associateBy { it.type to it.code }

    private val byType: Map<DictType, List<DictEntry>> =
        entries.filter { it.enabled }
            .groupBy { it.type }
            .mapValues { (_, list) -> list.sortedBy { it.sortOrder } }

    fun enabledOf(type: DictType): List<DictEntry> = byType[type].orEmpty()

    /**
     * 含停用项的全部条目。
     *
     * 只给 **CSV 导入**用：老备份里的「足第一跖趾」「踝」在 2026-10-27 之后已经停用，
     * 只查 `enabledOf` 会解析不出来，于是把文案当成 code 落库——
     * 换机一次就多一批查不到部位名的记录。
     */
    fun allOf(type: DictType): List<DictEntry> =
        byKey.values.filter { it.type == type }.sortedBy { it.sortOrder }

    fun label(type: DictType, code: String?, short: Boolean = false): DictLabel {
        if (code.isNullOrBlank()) return DictLabel("", enabled = true, resolved = false)
        val entry = byKey[type to code]
            ?: return DictLabel(code, enabled = false, resolved = false)
        val text = if (short) (entry.shortLabel ?: entry.label) else entry.label
        return DictLabel(text, enabled = entry.enabled, resolved = true)
    }

    /** 部位展示：「右」+「足第一跖趾」。side 为 NA / null 时不拼前缀。 */
    fun siteLabel(siteCode: String?, sidePrefix: String?, short: Boolean = false): String {
        val base = label(DictType.SITE, siteCode, short).text
        if (sidePrefix.isNullOrBlank()) return base
        return sidePrefix + base
    }

    fun withEntries(newEntries: List<DictEntry>): DictResolver = DictResolver(newEntries)
}
