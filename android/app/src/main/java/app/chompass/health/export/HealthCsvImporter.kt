package app.chompass.health.export

import app.chompass.health.data.db.JointSeed
import app.chompass.health.domain.DictResolver
import app.chompass.health.domain.model.DictType
import app.chompass.health.domain.model.DoseLogKind
import app.chompass.health.domain.model.PainSide
import app.chompass.health.domain.model.UricInputUnit
import app.chompass.health.domain.model.UricMethod
import app.chompass.health.util.TimeUtils

/**
 * Reads the CSV files back into rows. Pure: no Room, no `Context`.
 *
 * Whether a label can be turned back into its code is the whole correctness
 * question of an import, and it has to be assertable on the JVM.
 *
 * ## Three rules
 *
 * 1. **Columns are found by header name, never by position.** The export gains
 *    columns over time; reading by index shifts a whole row on an old file and
 *    still "succeeds" — silently importing garbage.
 * 2. **A bad row is skipped and counted, not fatal.** One broken line in a
 *    200-row backup must not cost the user all the others.
 * 3. **A dict label that cannot be resolved is kept as text** rather than
 *    dropped: better a site name the picker cannot match than a missing
 *    "8 hours of pain in the big toe" record.
 *
 * ## Header aliases
 *
 * Files written by this app carry English headers. Files written by the
 * standalone UricDiary app (the predecessor users migrate from) carry Chinese
 * ones, so both are accepted — that import is a one-way door for them.
 */
object HealthCsvImporter {

    /* ---------------------------------------------------------- 数据结构 */

    data class UricRow(
        val measuredAt: Long,
        val valueUmol: Int,
        val inputValue: Double,
        val inputUnit: UricInputUnit,
        val method: UricMethod,
        val fasting: Boolean,
        val note: String?,
    )

    data class PainRow(
        val startedAt: Long,
        val endedAt: Long?,
        val siteCode: String,
        val side: PainSide,
        val nrs: Int,
        val natures: List<String>?,
        val triggers: List<String>?,
        val affectWalk: Boolean,
        val affectSleep: Boolean,
        val note: String?,
    )

    data class PainUpdateRow(
        val episodeStart: Long,
        val at: Long,
        val nrs: Int,
        val note: String?,
    )

    data class DoseRow(
        val actualAt: Long,
        val drugName: String,
        val strengthValue: Double?,
        val strengthUnit: String?,
        val intakeUnits: Double,
        val unitLabel: String,
        val planAt: Long?,
        val deviationMinutes: Int?,
        val kind: DoseLogKind,
        val note: String?,
    )

    /** A line the reader could not use. `line` is 1-based in the file. */
    data class Issue(val file: String, val line: Int)

    data class Parsed<T>(val rows: List<T>, val skipped: Int, val issues: List<Issue>)

    /* ------------------------------------------------------------ 尿酸 */

    fun parseUric(text: String, file: String = "uric.csv"): Parsed<UricRow> {
        val table = CsvTable.parse(text)
        if (table.isEmpty()) return Parsed(emptyList(), 0, emptyList())
        val head = CsvTable.headerIndex(table)
        val dateCol = head.at("date", "日期")
        val timeCol = head.at("time", "时间")
        val valueCol = head.at("value_umol", "数值(μmol/L)")
        val inputCol = head.at("input_value", "原始值")
        val unitCol = head.at("input_unit", "原始单位")
        val methodCol = head.at("method", "测量方式")
        val fastingCol = head.at("fasting", "空腹")
        val noteCol = head.at("note", "备注")

        val rows = mutableListOf<UricRow>()
        val issues = mutableListOf<Issue>()
        var skipped = 0

        table.drop(1).forEachIndexed { index, cells ->
            val at = parseDateAndTime(cells.getOrNull(dateCol), cells.getOrNull(timeCol))
            val value = cells.getOrNull(valueCol)?.trim()?.toIntOrNull()
            if (at == null || value == null) {
                skipped++
                if (issues.size < MAX_ISSUES) issues += Issue(file, index + 2)
                return@forEachIndexed
            }
            rows += UricRow(
                measuredAt = at,
                valueUmol = value,
                inputValue = cells.getOrNull(inputCol)?.trim()?.toDoubleOrNull() ?: value.toDouble(),
                inputUnit = unitOf(cells.getOrNull(unitCol).orEmpty()),
                method = methodOf(cells.getOrNull(methodCol).orEmpty()),
                fasting = isYes(cells.getOrNull(fastingCol)),
                note = cells.getOrNull(noteCol)?.trim()?.takeIf { it.isNotBlank() },
            )
        }
        return Parsed(rows, skipped, issues)
    }

    /* ------------------------------------------------------------ 疼痛 */

    fun parsePain(text: String, dict: DictResolver, file: String = "pain.csv"): Parsed<PainRow> {
        val table = CsvTable.parse(text)
        if (table.isEmpty()) return Parsed(emptyList(), 0, emptyList())
        val head = CsvTable.headerIndex(table)
        val startCol = head.at("start", "开始")
        val endCol = head.at("end", "结束")
        val siteCol = head.at("site", "部位")
        val sideCol = head.at("side", "侧别")
        val nrsCol = head.at("nrs", "NRS")
        val naturesCol = head.at("natures", "性质")
        val triggersCol = head.at("triggers", "诱因")
        val walkCol = head.at("affect_walk", "影响行走")
        val sleepCol = head.at("affect_sleep", "影响睡眠")
        val noteCol = head.at("note", "备注")

        val rows = mutableListOf<PainRow>()
        val issues = mutableListOf<Issue>()
        var skipped = 0

        table.drop(1).forEachIndexed { index, cells ->
            val start = parseDateTime(cells.getOrNull(startCol))
            val nrs = cells.getOrNull(nrsCol)?.trim()?.toIntOrNull()
            if (start == null || nrs == null) {
                skipped++
                if (issues.size < MAX_ISSUES) issues += Issue(file, index + 2)
                return@forEachIndexed
            }
            rows += PainRow(
                startedAt = start,
                endedAt = parseDateTime(cells.getOrNull(endCol)),
                siteCode = siteCodeOf(cells.getOrNull(siteCol).orEmpty(), dict),
                side = sideOf(cells.getOrNull(sideCol).orEmpty()),
                nrs = nrs.coerceIn(0, 10),
                natures = codesOf(DictType.PAIN_NATURE, cells.getOrNull(naturesCol).orEmpty(), dict),
                triggers = codesOf(DictType.TRIGGER, cells.getOrNull(triggersCol).orEmpty(), dict),
                affectWalk = isYes(cells.getOrNull(walkCol)),
                affectSleep = isYes(cells.getOrNull(sleepCol)),
                note = cells.getOrNull(noteCol)?.trim()?.takeIf { it.isNotBlank() },
            )
        }
        return Parsed(rows, skipped, issues)
    }

    fun parsePainUpdate(text: String, file: String = "pain_update.csv"): Parsed<PainUpdateRow> {
        val table = CsvTable.parse(text)
        if (table.isEmpty()) return Parsed(emptyList(), 0, emptyList())
        val head = CsvTable.headerIndex(table)
        val startCol = head.at("episode_start", "发作开始")
        val atCol = head.at("at", "时刻")
        val nrsCol = head.at("nrs", "NRS")
        val noteCol = head.at("note", "备注")

        val rows = mutableListOf<PainUpdateRow>()
        val issues = mutableListOf<Issue>()
        var skipped = 0

        table.drop(1).forEachIndexed { index, cells ->
            val start = parseDateTime(cells.getOrNull(startCol))
            val at = parseDateTime(cells.getOrNull(atCol))
            val nrs = cells.getOrNull(nrsCol)?.trim()?.toIntOrNull()
            if (start == null || at == null || nrs == null) {
                skipped++
                if (issues.size < MAX_ISSUES) issues += Issue(file, index + 2)
                return@forEachIndexed
            }
            rows += PainUpdateRow(
                episodeStart = start,
                at = at,
                nrs = nrs.coerceIn(0, 10),
                note = cells.getOrNull(noteCol)?.trim()?.takeIf { it.isNotBlank() },
            )
        }
        return Parsed(rows, skipped, issues)
    }

    /* -------------------------------------------------------- 服药记录 */

    fun parseDoseLog(text: String, file: String = "dose_log.csv"): Parsed<DoseRow> {
        val table = CsvTable.parse(text)
        if (table.isEmpty()) return Parsed(emptyList(), 0, emptyList())
        val head = CsvTable.headerIndex(table)
        val dateCol = head.at("date", "实际日期")
        val timeCol = head.at("time", "实际时间")
        val drugCol = head.at("drug", "药名")
        val strengthCol = head.at("strength", "规格")
        val amountCol = head.at("amount", "服用量")
        val unitCol = head.at("unit", "单位")
        val planCol = head.at("plan_time", "计划时间")
        val deviationCol = head.at("deviation_min", "偏差(分钟)")
        val kindCol = head.at("kind", "类型")
        val noteCol = head.at("note", "备注")

        val rows = mutableListOf<DoseRow>()
        val issues = mutableListOf<Issue>()
        var skipped = 0

        table.drop(1).forEachIndexed { index, cells ->
            val at = parseDateAndTime(cells.getOrNull(dateCol), cells.getOrNull(timeCol))
            val drug = cells.getOrNull(drugCol)?.trim().orEmpty()
            val amount = cells.getOrNull(amountCol)?.trim()?.toDoubleOrNull()
            if (at == null || drug.isBlank() || amount == null) {
                skipped++
                if (issues.size < MAX_ISSUES) issues += Issue(file, index + 2)
                return@forEachIndexed
            }
            val strength = cells.getOrNull(strengthCol)?.trim().orEmpty()
            rows += DoseRow(
                actualAt = at,
                drugName = drug,
                strengthValue = strength.substringBefore(' ', "").toDoubleOrNull(),
                strengthUnit = strength.substringAfter(' ', "").takeIf { it.isNotBlank() },
                intakeUnits = amount,
                // Files from before v2 had no unit column; "tablet" was the only
                // unit that version could produce, so this cannot be more wrong.
                unitLabel = cells.getOrNull(unitCol)?.trim()?.takeIf { it.isNotBlank() } ?: "tablet",
                planAt = parseDateTime(cells.getOrNull(planCol)),
                deviationMinutes = cells.getOrNull(deviationCol)?.trim()?.toIntOrNull(),
                kind = kindOf(cells.getOrNull(kindCol).orEmpty()),
                note = cells.getOrNull(noteCol)?.trim()?.takeIf { it.isNotBlank() },
            )
        }
        return Parsed(rows, skipped, issues)
    }

    /* ------------------------------------------------------------ 工具 */

    private const val MAX_ISSUES = 3

    /** First alias that exists in the header, or -1. */
    private fun Map<String, Int>.at(vararg names: String): Int =
        names.firstNotNullOfOrNull { this[it] } ?: -1

    private fun parseDateAndTime(date: String?, time: String?): Long? {
        val d = date?.trim().orEmpty()
        val t = time?.trim().orEmpty()
        if (d.isBlank() || t.isBlank()) return null
        return TimeUtils.at(d, t.take(5))
    }

    /** One cell holding `yyyy-MM-dd HH:mm`. */
    private fun parseDateTime(text: String?): Long? {
        val v = text?.trim().orEmpty()
        if (v.isBlank()) return null
        val parts = v.split(' ')
        if (parts.size < 2) return null
        return TimeUtils.at(parts[0], parts[1].take(5))
    }

    private fun isYes(text: String?): Boolean {
        val v = text?.trim().orEmpty()
        // "是" is what the standalone app wrote before this port.
        return v == "是" || v.equals("yes", ignoreCase = true) || v.equals("true", ignoreCase = true)
    }

    private fun unitOf(cell: String): UricInputUnit {
        CsvTable.codeOf(cell)?.let { code ->
            UricInputUnit.entries.firstOrNull { it.name.equals(code, ignoreCase = true) }
                ?.let { return it }
        }
        // Symbols never pass through the dictionary, so match them directly.
        return if (cell.contains("mg", ignoreCase = true)) UricInputUnit.MG_DL else UricInputUnit.UMOL_L
    }

    private fun methodOf(cell: String): UricMethod {
        CsvTable.codeOf(cell)?.let { code ->
            UricMethod.entries.firstOrNull { it.name.equals(code, ignoreCase = true) }?.let { return it }
        }
        val v = cell.trim()
        return when {
            v.isEmpty() -> UricMethod.UNKNOWN
            v.contains("静脉") || v.contains("lab", ignoreCase = true) ||
                v.contains("blood draw", ignoreCase = true) -> UricMethod.LAB

            v.contains("报告") || v.contains("report", ignoreCase = true) -> UricMethod.REPORT
            v.contains("指尖") || v.contains("家用") || v.contains("finger", ignoreCase = true) ||
                v.contains("meter", ignoreCase = true) -> UricMethod.FINGER

            else -> UricMethod.UNKNOWN
        }
    }

    private fun sideOf(cell: String): PainSide {
        CsvTable.codeOf(cell)?.let { code ->
            PainSide.entries.firstOrNull { it.name.equals(code, ignoreCase = true) }?.let { return it }
        }
        val v = cell.trim()
        return when {
            v.isEmpty() -> PainSide.NA
            v.contains("右") || v.contains("right", ignoreCase = true) -> PainSide.RIGHT
            v.contains("左") || v.contains("left", ignoreCase = true) -> PainSide.LEFT
            v.contains("双") || v.contains("both", ignoreCase = true) -> PainSide.BOTH
            else -> PainSide.NA
        }
    }

    private fun kindOf(cell: String): DoseLogKind {
        CsvTable.codeOf(cell)?.let { code ->
            DoseLogKind.entries.firstOrNull { it.name.equals(code, ignoreCase = true) }?.let { return it }
        }
        val v = cell.trim()
        return when {
            v.contains("补记") || v.contains("backfill", ignoreCase = true) -> DoseLogKind.BACKFILL
            v.contains("按需") || v.contains("as needed", ignoreCase = true) -> DoseLogKind.PRN
            v.contains("额外") || v.contains("extra", ignoreCase = true) -> DoseLogKind.EXTRA
            else -> DoseLogKind.SCHEDULED
        }
    }

    /** Site cell -> joint id, including the codes the pre-joint catalogue used. */
    private fun siteCodeOf(cell: String, dict: DictResolver): String {
        val raw = codeOf(DictType.SITE, cell, dict)
        return JointSeed.legacySiteMap[raw] ?: raw
    }

    private fun codesOf(type: DictType, text: String, dict: DictResolver): List<String>? {
        if (text.isBlank()) return null
        // `;` is what this app writes; `、` is what the standalone app wrote; a
        // comma is accepted because a hand-edited file usually has one.
        val parts = text.split(';', '、', ',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        return parts.map { codeOf(type, it, dict) }
    }

    /**
     * One cell -> dict code. The bracketed code wins; otherwise reverse-resolve
     * the label against the whole dictionary (disabled entries included, so a
     * retired site still comes back as a code) and fall back to the raw text.
     */
    private fun codeOf(type: DictType, cell: String, dict: DictResolver): String {
        val text = cell.trim()
        if (text.isBlank()) return ""
        CsvTable.codeOf(text)?.let { return it }

        val all = dict.allOf(type)
        all.firstOrNull { it.label.equals(text, ignoreCase = true) }?.let { return it.code }
        // Legacy export prefixed the side ("right big toe") onto the site label.
        all.firstOrNull { text.endsWith(it.label, ignoreCase = true) }?.let { return it.code }
        return text
    }
}
