package app.chompass.health.export

import app.chompass.health.data.db.entity.DoseLogEntity
import app.chompass.health.data.db.entity.PainEpisodeEntity
import app.chompass.health.data.db.entity.PainUpdateEntity
import app.chompass.health.data.db.entity.UricRecordEntity
import app.chompass.health.domain.DictResolver
import app.chompass.health.domain.model.DictType
import app.chompass.health.domain.model.DoseLogKind
import app.chompass.health.domain.model.PainSide
import app.chompass.health.domain.model.UricInputUnit
import app.chompass.health.util.TimeUtils

/**
 * The only localized copy the CSV files need, resolved once by the caller.
 *
 * Header names deliberately stay **English and stable** (like the app's existing
 * diary export): they are the contract the importer reads, and a translated
 * header would make a backup written before a language switch unreadable. The
 * *values* are where the words go — every enum cell is `label [CODE]`, so the
 * doctor reads "Right" and the importer reads `RIGHT`.
 */
data class HealthCsvStrings(
    val yes: String,
    val no: String,
    val unitUmol: String,
    val unitMgdl: String,
    val side: Map<PainSide, String>,
    val kind: Map<DoseLogKind, String>,
)

/**
 * Builds the four CSV files. Pure: no file system, no `Context`.
 *
 * Column layout (stable, see `HealthCsvImporter` for the reader):
 * - `uric.csv`        date, time, value_umol, input_value, input_unit, method, fasting, note
 * - `pain.csv`        start, end, duration_min, site, side, nrs, natures, triggers,
 *                     affect_walk, affect_sleep, note
 * - `pain_update.csv` episode_start, at, nrs, note
 * - `dose_log.csv`    date, time, drug, strength, amount, unit, plan_time,
 *                     deviation_min, kind, note
 */
object HealthCsvExporter {

    private const val LIST_SEPARATOR = "; "
    private const val MINUTE_MS = 60_000L

    /* ------------------------------------------------------------ uric */

    fun uric(rows: List<UricRecordEntity>, s: HealthCsvStrings, dict: DictResolver): String =
        buildString {
            append(CsvTable.BOM)
            append(
                CsvTable.row(
                    listOf(
                        "date", "time", "value_umol", "input_value", "input_unit",
                        "method", "fasting", "note",
                    )
                )
            )
            rows.forEach { r ->
                append(
                    CsvTable.row(
                        listOf(
                            TimeUtils.toDateString(r.measuredAt),
                            TimeUtils.toTimeString(r.measuredAt),
                            r.valueUmol.toString(),
                            trimNumber(r.inputValue),
                            unitCell(r.inputUnit, s),
                            CsvTable.labelWithCode(
                                dict.label(DictType.URIC_METHOD, r.measureMethod.name).text,
                                r.measureMethod.name,
                            ),
                            if (r.fasting) s.yes else s.no,
                            r.note.orEmpty(),
                        )
                    )
                )
            }
        }

    /* ------------------------------------------------------------ pain */

    fun pain(rows: List<PainEpisodeEntity>, s: HealthCsvStrings, dict: DictResolver): String =
        buildString {
            append(CsvTable.BOM)
            append(
                CsvTable.row(
                    listOf(
                        "start", "end", "duration_min", "site", "side", "nrs",
                        "natures", "triggers", "affect_walk", "affect_sleep", "note",
                    )
                )
            )
            rows.forEach { r ->
                append(
                    CsvTable.row(
                        listOf(
                            dateTime(r.startedAt),
                            r.endedAt?.let(::dateTime).orEmpty(),
                            r.endedAt?.let { ((it - r.startedAt) / MINUTE_MS).toString() }.orEmpty(),
                            CsvTable.labelWithCode(
                                dict.label(DictType.SITE, r.siteCode).text,
                                r.siteCode,
                            ),
                            sideCell(r.side, s),
                            r.nrs.toString(),
                            codesCell(DictType.PAIN_NATURE, r.natures, dict),
                            codesCell(DictType.TRIGGER, r.triggers, dict),
                            if (r.affectWalk) s.yes else s.no,
                            if (r.affectSleep) s.yes else s.no,
                            r.note.orEmpty(),
                        )
                    )
                )
            }
        }

    /**
     * The intra-flare scores.
     *
     * The first score lives on the episode itself, so without this file a
     * restored backup loses the whole "6 → 8 → 4" curve and every flare reads as
     * a single number. `episode_start` is the business key the importer uses to
     * find the episode again — ids are not portable across installs.
     */
    fun painUpdate(
        rows: List<PainUpdateEntity>,
        episodeStart: Map<Long, Long>,
        s: HealthCsvStrings,
    ): String = buildString {
        append(CsvTable.BOM)
        append(CsvTable.row(listOf("episode_start", "at", "nrs", "note")))
        rows.forEach { r ->
            val start = episodeStart[r.episodeId] ?: return@forEach
            append(
                CsvTable.row(
                    listOf(
                        dateTime(start),
                        dateTime(r.at),
                        r.nrs.toString(),
                        r.note.orEmpty(),
                    )
                )
            )
        }
    }

    /* -------------------------------------------------------- dose log */

    fun doseLog(rows: List<DoseLogEntity>, s: HealthCsvStrings): String = buildString {
        append(CsvTable.BOM)
        append(
            CsvTable.row(
                listOf(
                    "date", "time", "drug", "strength", "amount", "unit",
                    "plan_time", "deviation_min", "kind", "note",
                )
            )
        )
        rows.forEach { r ->
            append(
                CsvTable.row(
                    listOf(
                        TimeUtils.toDateString(r.actualAt),
                        TimeUtils.toTimeString(r.actualAt),
                        r.drugName,
                        strengthText(r.strengthValue, r.strengthUnit),
                        trimNumber(r.intakeUnits),
                        r.unitLabel,
                        r.planAt?.let(::dateTime).orEmpty(),
                        r.deviationMinutes?.toString().orEmpty(),
                        CsvTable.labelWithCode(s.kind[r.kind].orEmpty(), r.kind.name),
                        r.note.orEmpty(),
                    )
                )
            )
        }
    }

    /* ------------------------------------------------------------ 工具 */

    fun dateTime(at: Long): String =
        TimeUtils.toDateString(at) + " " + TimeUtils.toTimeString(at)

    private fun unitCell(unit: UricInputUnit, s: HealthCsvStrings): String =
        CsvTable.labelWithCode(
            when (unit) {
                UricInputUnit.UMOL_L -> s.unitUmol
                UricInputUnit.MG_DL -> s.unitMgdl
            },
            unit.name,
        )

    private fun sideCell(side: PainSide, s: HealthCsvStrings): String {
        if (side == PainSide.NA) return ""
        return CsvTable.labelWithCode(s.side[side].orEmpty(), side.name)
    }

    /** Dict codes -> `Label [CODE]; Label [CODE]`, never comma-separated. */
    private fun codesCell(type: DictType, codes: List<String>?, dict: DictResolver): String {
        if (codes.isNullOrEmpty()) return ""
        return codes
            .filter { it.isNotBlank() }
            .joinToString(LIST_SEPARATOR) { code ->
                CsvTable.labelWithCode(dict.label(type, code).text, code)
            }
    }

    private fun strengthText(value: Double?, unit: String?): String =
        if (value == null || unit == null) "" else "${trimNumber(value)} $unit"

    private fun trimNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}
