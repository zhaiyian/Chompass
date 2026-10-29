package app.chompass.services.grounding

import android.content.Context
import app.chompass.models.NutrientBasis
import app.chompass.models.NutrientSourceKind
import app.chompass.services.ai.FoodAnalysis
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Read-only lookup over the compact China Food Composition Table SQLite asset
 * (`assets/cn/cn_foods.sqlite`, 《中国食物成分表标准版（第6版）》 via the
 * Sanotsu/china-food-composition-data mirror). Ships in all build types (main
 * assets). No Room — opens a copied file with
 * [android.database.sqlite.SQLiteDatabase.openDatabase].
 *
 * Rows are per 100 g of edible portion with the book's Chinese name. Search has
 * to cope with a script the other two indexes never see: FTS5's tokenizer treats
 * a whole CJK run as one token, so the asset's `tokens` column indexes every
 * glyph *and* every adjacent pair, and [queryTokens] reads a query the same way.
 * That is what makes "鸡胸" find "鸡胸脯肉" and "牛柳" find
 * "牛肉（里脊肉）［牛柳］".
 */
class ChinaFoodIndex(
    context: Context,
    assetPath: String = ASSET_PATH,
) : OfflineFoodIndex(
    context = context,
    assetPath = assetPath,
    manifestAssetPath = MANIFEST_ASSET_PATH,
    dbFileName = DB_FILE_NAME,
) {
    fun getById(id: Long): ChinaFoodRecord? {
        db.rawQuery(
            "SELECT * FROM foods WHERE id = ? LIMIT 1",
            arrayOf(id.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return null
            return readRecord(c)
        }
    }

    /** Chinese-name search; see [searchScored] for the ranked form. */
    fun search(
        query: String,
        limit: Int = 8,
    ): List<ChinaFoodRecord> = searchScored(query, limit).map { it.first }

    /**
     * Like [search] but keeps the per-row relevance score so callers can rank
     * Chinese hits against other sources on a shared scale (see
     * [FoodDatabaseSearch]).
     */
    internal fun searchScored(
        query: String,
        limit: Int = 8,
    ): List<Pair<ChinaFoodRecord, Double>> {
        if (queryTokens(query).isEmpty()) return emptyList()

        return searchRows(
            query = query,
            idColumn = "id",
            nameColumn = "name",
            tokensColumn = "tokens",
            rowReader = ::readRecord,
            tokenizer = ::queryTokens,
        )
            .asSequence()
            .filter { it.calories != null }
            .map { it to score(query, it.name) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .toList()
    }

    private fun readRecord(c: android.database.Cursor): ChinaFoodRecord {
        fun d(col: String): Double? {
            val idx = c.getColumnIndex(col)
            if (idx < 0 || c.isNull(idx)) return null
            return c.getDouble(idx)
        }
        fun s(col: String): String? {
            val idx = c.getColumnIndex(col)
            if (idx < 0 || c.isNull(idx)) return null
            return c.getString(idx)
        }
        return ChinaFoodRecord(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            foodCode = c.getString(c.getColumnIndexOrThrow("food_code")),
            category = s("category"),
            name = c.getString(c.getColumnIndexOrThrow("name")),
            enName = s("en_name"),
            ediblePct = d("edible_pct"),
            calories = d("calories"),
            protein = d("protein"),
            carbs = d("carbs"),
            fat = d("fat"),
            fiber = d("fiber"),
            cholesterol = d("cholesterol"),
            sodium = d("sodium"),
            potassium = d("potassium"),
            calcium = d("calcium"),
            iron = d("iron"),
            magnesium = d("magnesium"),
            zinc = d("zinc"),
            vitaminA = d("vitamin_a"),
            vitaminC = d("vitamin_c"),
            vitaminE = d("vitamin_e"),
        )
    }

    companion object {
        const val ASSET_PATH = "cn/cn_foods.sqlite"
        const val MANIFEST_ASSET_PATH = "cn/cn_foods.manifest.json"
        private const val DB_FILE_NAME = "cn_foods.sqlite"

        /** True when the APK includes the offline index (main assets — all builds). */
        fun assetAvailable(context: Context, assetPath: String = ASSET_PATH): Boolean =
            OfflineFoodIndex.assetAvailable(context, assetPath)

        private fun isCjk(ch: Char): Boolean =
            ch.code in 0x3400..0x4DBF || ch.code in 0x4E00..0x9FFF || ch.code in 0xF900..0xFAFF

        /**
         * Query-side tokens for the Chinese index: one token per CJK glyph plus
         * whole Latin words. Matches the asset's unigram indexing, so the FTS
         * MATCH stage stays an AND over the characters the user typed ("牛肉" →
         * 牛 ∧ 肉) instead of over one opaque run FTS would never split.
         */
        internal fun queryTokens(text: String): List<String> {
            val out = LinkedHashSet<String>()
            val latin = StringBuilder()

            fun flushLatin() {
                if (latin.isEmpty()) return
                val word = latin.toString().lowercase(Locale.US)
                if (word.length >= 2) out += word
                latin.setLength(0)
            }

            for (ch in text) {
                if (isCjk(ch)) {
                    flushLatin()
                    out += ch.toString()
                } else if (ch.isLetterOrDigit()) {
                    latin.append(ch)
                } else {
                    flushLatin()
                }
            }
            flushLatin()
            return out.toList()
        }

        /** Lowercased letters/digits only — punctuation and brackets are dropped. */
        internal fun flatten(text: String): String = buildString {
            for (ch in text) {
                if (ch.isLetterOrDigit()) append(ch.lowercaseChar())
            }
        }

        /**
         * Ranks a row against the raw query. The book's names carry a lot of
         * parenthetical detail ("牛肉（里脊肉）［牛柳］"), so brackets are
         * flattened away before matching: the alias inside them then works as a
         * plain substring.
         *
         * The whole-string tiers order the obvious wins; coverage and bigram
         * overlap do the rest, so a query carrying typing noise (a quantity, a
         * unit) still ranks the rows it half-matches instead of dropping them.
         */
        internal fun score(query: String, name: String): Double {
            val q = flatten(query)
            if (q.isEmpty()) return 0.0
            val flat = flatten(name)
            val tokens = queryTokens(query)
            val covered = tokens.count { flat.contains(it) }
            if (covered == 0) return 0.0
            var score = when {
                flat == q -> 12.0
                flat.startsWith(q) -> 8.0
                flat.contains(q) -> 5.0
                else -> 0.0
            }
            score += 4.0 * covered / tokens.size
            score += sharedBigrams(q, flat) * 0.8
            // Earlier occurrences are better names for the query: "牛奶" should
            // lead with 纯牛奶, not with 花卷（加牛奶）. `indexOf` is -1 when only
            // the coverage tier matched, which earns no position bonus.
            val at = flat.indexOf(q)
            if (at >= 0) score += max(0.0, 3.0 - at * 0.5)
            score += max(0.0, 2.0 - name.length / 12.0)
            return score
        }

        /** How many adjacent pairs of [query] also occur in [text]. */
        private fun sharedBigrams(query: String, text: String): Int {
            var count = 0
            for (i in 0 until query.length - 1) {
                if (text.contains(query.substring(i, i + 2))) count++
            }
            return count
        }
    }
}

/**
 * One China Food Composition Table row (per 100 g of edible portion, normalized
 * to Chompass units: macros g, cholesterol/minerals/vitamin C and E mg,
 * vitamin A µg RE). The source table has no sugar, saturated-fat, vitamin D/B12/K
 * or folate columns, so those stay null here rather than being invented.
 */
data class ChinaFoodRecord(
    val id: Long,
    val foodCode: String,
    val category: String? = null,
    val name: String,
    val enName: String? = null,
    /** 可食部: the share of the weighed food that is edible, in percent. */
    val ediblePct: Double? = null,
    val calories: Double? = null,
    val protein: Double? = null,
    val carbs: Double? = null,
    val fat: Double? = null,
    val fiber: Double? = null,
    val cholesterol: Double? = null,
    val sodium: Double? = null,
    val potassium: Double? = null,
    val calcium: Double? = null,
    val iron: Double? = null,
    val magnesium: Double? = null,
    val zinc: Double? = null,
    val vitaminA: Double? = null,
    val vitaminC: Double? = null,
    val vitaminE: Double? = null,
) {
    /** Scale per-100g nutrients to [grams] as a reviewable [FoodAnalysis]. */
    fun toFoodAnalysis(grams: Double, datasetVersion: String): FoodAnalysis {
        val per100 = FoodAnalysis(
            name = name,
            calories = (calories ?: 0.0).roundToInt(),
            protein = protein ?: 0.0,
            carbs = carbs ?: 0.0,
            fat = fat ?: 0.0,
            servingSizeGrams = 100.0,
            fiber = fiber,
            cholesterol = cholesterol,
            sodium = sodium,
            potassium = potassium,
            calcium = calcium,
            iron = iron,
            magnesium = magnesium,
            zinc = zinc,
            vitaminA = vitaminA,
            vitaminC = vitaminC,
            vitaminE = vitaminE,
            grounding = app.chompass.models.FoodGroundingProvenance(
                sourceKind = NutrientSourceKind.CHINA,
                sourceId = id.toString(),
                sourceName = name,
                nutrientBasis = NutrientBasis.PER_100G,
                datasetVersion = datasetVersion,
                retrievedAtEpochMs = System.currentTimeMillis(),
                identityEvidence = "china-food-composition-table:$foodCode",
            ),
        )
        return NutrientScaling.scaleAnalysis(per100, grams)
    }
}
