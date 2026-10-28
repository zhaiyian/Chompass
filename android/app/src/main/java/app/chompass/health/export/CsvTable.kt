package app.chompass.health.export

/**
 * RFC 4180 table reader/writer shared by the health CSV files.
 *
 * Pure on purpose: "does a note with a comma in it shift the whole row" is the
 * kind of bug that only shows up on a doctor's laptop, months later. Keeping the
 * escaping here makes it a JVM assertion instead of a device experiment.
 *
 * Three rules carried over from the standalone diary app, all derived from
 * "the user hands this to a doctor":
 *
 * 1. **Multi-values never use a comma.** The database stores label lists
 *    comma-separated; exported verbatim they collide with the delimiter and
 *    Excel silently shifts the row one column right.
 * 2. **Every enum cell carries its code.** `[MTP1]` after the label keeps the
 *    file readable *and* re-importable after the phone language changes — a
 *    label-only export has to be reverse-translated to be restored.
 * 3. **UTF-8 with BOM.** Without the BOM Excel renders CJK as mojibake, and the
 *    user will almost certainly open this in Excel.
 */
internal object CsvTable {

    const val BOM = "\uFEFF"
    const val EOL = "\r\n"

    /** `"Big toe · base [MTP1]"` -> `MTP1`. Trailing whitespace tolerated. */
    private val CODE_SUFFIX = Regex("""\[([^\[\]]+)]\s*$""")

    fun row(cells: List<String>): String =
        cells.joinToString(",") { escape(it) } + EOL

    /**
     * Quote when the cell contains a quote, a comma or a line break; double the
     * quotes inside. Newlines are flattened first: a multi-line note would
     * otherwise be legal CSV that every spreadsheet shows as two rows.
     */
    fun escape(raw: String): String {
        val v = raw.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
        val needsQuote = v.contains('"') || v.contains(',')
        return if (needsQuote) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

    /**
     * [`label`] with its stable code appended. When the label is missing or is
     * just the code, the code alone is written — `MTP1 [MTP1]` is noise.
     */
    fun labelWithCode(label: String, code: String): String =
        if (label.isBlank() || label == code) code else "$label [$code]"

    /** The bracketed code, or null when the cell has none (legacy export, hand edit). */
    fun codeOf(cell: String): String? = CODE_SUFFIX.find(cell.trim())?.groupValues?.get(1)

    /**
     * Cells of one table. Handles quoted commas — a note reading
     * "had BBQ, then beer" is normal, and `split(",")` turns one row into two
     * while still parsing "successfully", which is the hardest kind to notice.
     */
    fun parse(text: String): List<List<String>> {
        val src = text.removePrefix(BOM)
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0

        fun flushCell() {
            row.add(cell.toString())
            cell.setLength(0)
        }

        fun flushRow() {
            flushCell()
            // Drop blank lines: trailing newlines and whatever Excel adds on save.
            if (row.any { it.isNotBlank() }) rows.add(row)
            row = mutableListOf()
        }

        while (i < src.length) {
            val c = src[i]
            when {
                c == '"' && inQuotes && i + 1 < src.length && src[i + 1] == '"' -> {
                    cell.append('"')
                    i++
                }

                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> flushCell()
                (c == '\r' || c == '\n') && !inQuotes -> {
                    flushRow()
                    if (c == '\r' && i + 1 < src.length && src[i + 1] == '\n') i++
                }

                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) flushRow()
        return rows
    }

    /** Header name -> column index, trimmed. Read by name, never by position. */
    fun headerIndex(table: List<List<String>>): Map<String, Int> =
        table.firstOrNull()?.mapIndexed { index, name -> name.trim() to index }?.toMap().orEmpty()
}
