package app.chompass.health.export

import app.chompass.health.data.db.entity.DoseLogEntity
import app.chompass.health.data.db.entity.PainEpisodeEntity
import app.chompass.health.data.db.entity.UricRecordEntity
import app.chompass.health.domain.DictEntry
import app.chompass.health.domain.DictResolver
import app.chompass.health.domain.model.DictType
import app.chompass.health.domain.model.DoseLogKind
import app.chompass.health.domain.model.PainSide
import app.chompass.health.domain.model.UricInputUnit
import app.chompass.health.domain.model.UricMethod
import app.chompass.health.util.TimeUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CSV files are both a doctor-facing record and the only backup the diary
 * has. Every assertion here guards one of those two jobs: a cell that shifts a
 * column, a label that cannot be turned back into a code, a legacy header that
 * stops being understood.
 */
class HealthCsvTest {

    private val strings = HealthCsvStrings(
        yes = "Yes",
        no = "No",
        unitUmol = "μmol/L",
        unitMgdl = "mg/dL",
        side = mapOf(
            PainSide.LEFT to "Left",
            PainSide.RIGHT to "Right",
            PainSide.BOTH to "Both",
        ),
        kind = mapOf(
            DoseLogKind.SCHEDULED to "Scheduled",
            DoseLogKind.BACKFILL to "Backfilled",
            DoseLogKind.PRN to "As needed",
            DoseLogKind.EXTRA to "Extra dose",
        ),
    )

    private val dict = DictResolver(
        listOf(
            DictEntry(type = DictType.SITE, code = "MTP1", label = "Big toe · base"),
            DictEntry(type = DictType.PAIN_NATURE, code = "RED", label = "Redness"),
            DictEntry(type = DictType.PAIN_NATURE, code = "SWELL", label = "Swelling"),
            DictEntry(type = DictType.TRIGGER, code = "WALK", label = "Walking"),
            DictEntry(type = DictType.URIC_METHOD, code = "FINGER", label = "Finger prick"),
        )
    )

    private val at = TimeUtils.at("2026-10-20", "08:30")!!

    /* ------------------------------------------------------- table IO */

    @Test
    fun `escape quotes cells that would break the delimiter`() {
        assertEquals("plain", CsvTable.escape("plain"))
        assertEquals("\"a,b\"", CsvTable.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", CsvTable.escape("say \"hi\""))
        // A newline inside a cell would render as two rows in every spreadsheet.
        assertEquals("one line", CsvTable.escape("one\nline"))
    }

    @Test
    fun `parse keeps a quoted comma inside one cell`() {
        val rows = CsvTable.parse("a,b\n\"x,y\",z\n")
        assertEquals(listOf("a", "b"), rows[0])
        assertEquals(listOf("x,y", "z"), rows[1])
    }

    @Test
    fun `parse drops the BOM and blank trailing lines`() {
        val rows = CsvTable.parse("\uFEFFa,b\r\n1,2\r\n\r\n")
        assertEquals(2, rows.size)
        assertEquals(listOf("a", "b"), rows[0])
    }

    @Test
    fun `labelWithCode falls back to the bare code`() {
        assertEquals("Right [RIGHT]", CsvTable.labelWithCode("Right", "RIGHT"))
        assertEquals("MTP1", CsvTable.labelWithCode("MTP1", "MTP1"))
        assertEquals("MTP1", CsvTable.labelWithCode("", "MTP1"))
        assertEquals("MTP1", CsvTable.codeOf("Big toe · base [MTP1]"))
        assertNull(CsvTable.codeOf("Big toe · base"))
    }

    /* ------------------------------------------------------- uric round trip */

    @Test
    fun `uric survives export and import`() {
        val row = UricRecordEntity(
            valueUmol = 412,
            inputValue = 6.9,
            inputUnit = UricInputUnit.MG_DL,
            measuredAt = at,
            measuredDate = TimeUtils.toDateString(at),
            measureMethod = UricMethod.FINGER,
            fasting = true,
            note = "after coffee, before breakfast",
            createdAt = at,
            updatedAt = at,
        )

        val csv = HealthCsvExporter.uric(listOf(row), strings, dict)
        assertTrue("Excel needs the BOM", csv.startsWith("\uFEFF"))
        assertTrue("comma note must be quoted", csv.contains("\"after coffee, before breakfast\""))

        val parsed = HealthCsvImporter.parseUric(csv)
        assertEquals(0, parsed.skipped)
        val back = parsed.rows.single()
        assertEquals(412, back.valueUmol)
        assertEquals(6.9, back.inputValue, 0.0001)
        assertEquals(UricInputUnit.MG_DL, back.inputUnit)
        assertEquals(UricMethod.FINGER, back.method)
        assertEquals(at, back.measuredAt)
        assertTrue(back.fasting)
        assertEquals("after coffee, before breakfast", back.note)
    }

    /* ------------------------------------------------------- pain round trip */

    @Test
    fun `pain keeps its dict codes, side and multi-values`() {
        val row = PainEpisodeEntity(
            startedAt = at,
            endedAt = at + 90 * 60_000L,
            siteCode = "MTP1",
            side = PainSide.RIGHT,
            nrs = 7,
            natures = listOf("RED", "SWELL"),
            triggers = listOf("WALK"),
            affectWalk = true,
            affectSleep = false,
            createdAt = at,
            updatedAt = at,
        )

        val csv = HealthCsvExporter.pain(listOf(row), strings, dict)
        // Two natures must not introduce an extra comma.
        val cells = CsvTable.parse(csv)[1]
        assertEquals(11, cells.size)
        assertEquals(90L, cells[2].toLong())

        val back = HealthCsvImporter.parsePain(csv, dict).rows.single()
        assertEquals("MTP1", back.siteCode)
        assertEquals(PainSide.RIGHT, back.side)
        assertEquals(listOf("RED", "SWELL"), back.natures)
        assertEquals(listOf("WALK"), back.triggers)
        assertTrue(back.affectWalk)
        assertEquals(at, back.startedAt)
        assertEquals(at + 90 * 60_000L, back.endedAt)
    }

    @Test
    fun `unknown site label is kept instead of dropping the flare`() {
        val csv = buildString {
            append("start,end,duration_min,site,side,nrs,natures,triggers,affect_walk,affect_sleep,note\n")
            append("2026-10-20 08:30,,,Retired joint [FOOT_OLD],,5,,,,,\n")
        }
        val row = HealthCsvImporter.parsePain(csv, dict).rows.single()
        assertEquals("FOOT_OLD", row.siteCode)
    }

    @Test
    fun `legacy chinese headers from the standalone app still import`() {
        val csv = buildString {
            append("日期,时间,数值(μmol/L),原始值,原始单位,测量方式,空腹,备注\n")
            append("2026-10-20,08:30,412,6.9,mg/dL,家用指尖血,是,\n")
        }
        val row = HealthCsvImporter.parseUric(csv).rows.single()
        assertEquals(412, row.valueUmol)
        assertEquals(UricInputUnit.MG_DL, row.inputUnit)
        assertEquals(UricMethod.FINGER, row.method)
        assertTrue(row.fasting)
        assertEquals(at, row.measuredAt)
    }

    /* ------------------------------------------------------- dose log */

    @Test
    fun `dose log keeps strength, kind and plan time`() {
        val row = DoseLogEntity(
            actualAt = at + 45 * 60_000L,
            actualDate = TimeUtils.toDateString(at),
            planAt = at,
            deviationMinutes = 45,
            drugName = "Allopurinol",
            strengthValue = 100.0,
            strengthUnit = "mg",
            intakeUnits = 1.0,
            unitLabel = "tablet",
            kind = DoseLogKind.BACKFILL,
            note = null,
            createdAt = at,
        )

        val csv = HealthCsvExporter.doseLog(listOf(row), strings)
        val back = HealthCsvImporter.parseDoseLog(csv).rows.single()
        assertEquals("Allopurinol", back.drugName)
        assertEquals(100.0, back.strengthValue!!, 0.0001)
        assertEquals("mg", back.strengthUnit)
        assertEquals(1.0, back.intakeUnits, 0.0001)
        assertEquals("tablet", back.unitLabel)
        assertEquals(DoseLogKind.BACKFILL, back.kind)
        assertEquals(at, back.planAt)
        assertEquals(45, back.deviationMinutes)
    }

    @Test
    fun `unreadable row is counted, not fatal`() {
        val csv = buildString {
            append("date,time,value_umol,input_value,input_unit,method,fasting,note\n")
            append("not-a-date,,400,400,,,,\n")
            append("2026-10-20,08:30,412,412,μmol/L,,,,,\n")
        }
        val parsed = HealthCsvImporter.parseUric(csv)
        assertEquals(1, parsed.rows.size)
        assertEquals(1, parsed.skipped)
        assertEquals(2, parsed.issues.single().line)
    }
}
