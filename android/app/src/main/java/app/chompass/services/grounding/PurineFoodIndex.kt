package app.chompass.services.grounding

import android.content.Context
import app.chompass.models.PurineLevel

/**
 * Name lookup over the bundled purine table (`assets/purine/purine_foods.sqlite`,
 * built from the maintainer's 嘌呤表.xlsx by `scripts/build_purine_index.py`).
 *
 * This index is not a search source: it carries purine only (no macros), and the
 * app uses it to *annotate* a food the user already sees — a search hit, a saved
 * food, an entry being reviewed. All 240 rows are loaded into memory once, so
 * [findFor] is a plain CPU match that Compose can call while rendering a row.
 *
 * Matching is deliberately conservative, because a wrong badge is worse than no
 * badge: exact name first, then the name with preparation asides removed
 * ("鸡胸肉(熟)" ↔ "鸡胸肉"), then the specific-name-contains-known-food case
 * ("凉拌牛肉" → 牛肉). The reverse case (a shorter typed name inside a longer
 * table name) is accepted only when the table name is barely longer, so "鸡胸"
 * still finds 鸡胸肉 while "鸡蛋" never matches 韭菜鸡蛋饺子.
 */
class PurineFoodIndex(
    context: Context,
    assetPath: String = ASSET_PATH,
) : OfflineFoodIndex(
    context = context,
    assetPath = assetPath,
    manifestAssetPath = MANIFEST_ASSET_PATH,
    dbFileName = DB_FILE_NAME,
) {
    private val rows: List<PurineRecord> = loadRows()

    private val byFlatName: Map<String, PurineRecord> =
        rows.groupBy { flatten(it.name) }.mapValues { (_, group) -> group.strictest() }

    private val byBaseName: Map<String, List<PurineRecord>> = rows.groupBy { normalize(it.name) }

    /**
     * Bounded hit cache: the Add Food list re-asks for the same names on every
     * keystroke and every recomposition, and the table never changes at runtime.
     */
    private val cache = object : LinkedHashMap<String, PurineRecord?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PurineRecord?>): Boolean =
            size > CACHE_SIZE
    }

    /** The row for [name], or null when the table has nothing convincing. */
    fun findFor(name: String): PurineRecord? {
        val key = name.trim()
        if (key.isEmpty()) return null
        if (cache.containsKey(key)) return cache[key]
        val hit = resolve(key)
        cache[key] = hit
        return hit
    }

    private fun resolve(name: String): PurineRecord? {
        val flat = flatten(name)
        if (flat.isEmpty()) return null
        byFlatName[flat]?.let { return it }
        byBaseName[normalize(name)]?.let { return it.strictest() }

        // The typed name is the more specific one ("凉拌牛肉" → 牛肉): the
        // longest known food inside it is the best identity.
        val contained = byBaseName.keys
            .filter { it.length >= MIN_MATCH_CHARS && flat.contains(it) }
            .maxByOrNull { it.length }
        if (contained != null) return byBaseName.getValue(contained).strictest()

        // The typed name is the shorter one ("鸡胸" → 鸡胸肉). Only a name that
        // is barely longer counts, or every short entry would attach to some
        // long dish that happens to contain it.
        val base = normalize(name)
        if (base.length < MIN_MATCH_CHARS) return null
        val wider = byBaseName.keys
            .filter { it.length > base.length && it.length <= base.length + WIDER_NAME_SLACK }
            .filter { it.contains(base) }
            .minByOrNull { it.length }
        return wider?.let { byBaseName.getValue(it).strictest() }
    }

    private fun loadRows(): List<PurineRecord> {
        val out = mutableListOf<PurineRecord>()
        // The sheet's own `level` column is not read: the builder refuses to ship a
        // row whose stated band disagrees with the thresholds, so deriving it here
        // cannot drift and there is one rule for the badge.
        db.rawQuery(
            "SELECT id, category, name, purine_mg FROM foods ORDER BY id",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val mg = c.getDouble(c.getColumnIndexOrThrow("purine_mg"))
                out += PurineRecord(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    category = c.getString(c.getColumnIndexOrThrow("category")),
                    name = c.getString(c.getColumnIndexOrThrow("name")),
                    purineMgPer100g = mg,
                    level = PurineLevel.fromMgPer100g(mg),
                )
            }
        }
        return out
    }

    companion object {
        const val ASSET_PATH = "purine/purine_foods.sqlite"
        const val MANIFEST_ASSET_PATH = "purine/purine_foods.manifest.json"
        private const val DB_FILE_NAME = "purine_foods.sqlite"
        private const val CACHE_SIZE = 256

        /** Shortest name that may anchor a match ("虾" alone is too vague). */
        private const val MIN_MATCH_CHARS = 2

        /** How much longer a table name may be than the typed one. */
        private const val WIDER_NAME_SLACK = 2

        private val ASIDE = Regex("[（(\\[［【].*?[）)\\]］】]")

        /** Lowercase letters/digits only — punctuation and brackets dropped. */
        internal fun flatten(text: String): String = buildString {
            for (ch in text) {
                if (ch.isLetterOrDigit()) append(ch.lowercaseChar())
            }
        }

        /** [flatten] with preparation asides removed: "鸡胸肉(熟)" -> "鸡胸肉". */
        internal fun normalize(text: String): String = flatten(ASIDE.replace(text, ""))

        fun assetAvailable(context: Context, assetPath: String = ASSET_PATH): Boolean =
            OfflineFoodIndex.assetAvailable(context, assetPath)
    }
}

/** One purine-table row: total purine per 100 g of the food as served. */
data class PurineRecord(
    val id: Long,
    val category: String,
    val name: String,
    val purineMgPer100g: Double,
    val level: PurineLevel,
)

/**
 * Cooking almost always lowers measured purine per 100 g, and the table carries
 * both variants for a few foods (羊腿肉(生) / 羊腿肉(熟)). When a name matches
 * either, show the higher value: under-warning a gout user is the harmful
 * direction.
 */
private fun List<PurineRecord>.strictest(): PurineRecord =
    maxBy { it.purineMgPer100g }
