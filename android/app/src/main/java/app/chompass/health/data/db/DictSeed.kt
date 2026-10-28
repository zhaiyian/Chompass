package app.chompass.health.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.annotation.StringRes
import androidx.sqlite.db.SupportSQLiteDatabase
import app.chompass.R
import app.chompass.health.domain.model.DictType

/** A resolved dictionary label pair ("家用指尖血" / "指尖血"). */
data class DictLabelText(val label: String, val shortLabel: String?)

/**
 * Built-in seeds for `dict_item`.
 *
 * Only ever `INSERT OR IGNORE`, **never UPDATE existing rows**: users may rename
 * "足第一跖趾" to "大脚趾", and a release overwriting it erases their wording.
 * Brand-new dictionary entries are by definition codes the database does not
 * have yet, so `INSERT OR IGNORE` is enough.
 *
 * `sort_order` steps by 10 to leave room for future insertions.
 *
 * Labels live in string resources and are re-resolved at read time (see
 * [localizedLabels]), so switching the app language relabels the seeded chips
 * instead of leaving a snapshot in the language the database was created in.
 */
internal object DictSeed {

    private data class Seed(
        val type: DictType,
        val code: String,
        @StringRes val labelRes: Int,
        @StringRes val shortRes: Int = 0,
        /** `UNKNOWN`-style legacy fallbacks: resolvable but not offered in pickers. */
        val enabled: Boolean = true,
    )

    private val seeds: List<Seed> = listOf(
        // ── SITE ─────────────────────────────────────────────
        // **Not seeded here**: since the v4 schema the only source of truth for
        // body sites is `pain_joint` (the anatomy tree), projected into
        // `dict_item` by `JointSeed.syncDictItems`. Seeding a second copy is how
        // "踝" and "脚踝" drifted apart.

        // ── nature PAIN_NATURE ───────────────────────────────
        Seed(DictType.PAIN_NATURE, "ZHANG_TONG", R.string.health_dict_nature_zhang_tong),
        Seed(DictType.PAIN_NATURE, "CI_TONG", R.string.health_dict_nature_ci_tong),
        Seed(DictType.PAIN_NATURE, "TIAO_TONG", R.string.health_dict_nature_tiao_tong),
        Seed(DictType.PAIN_NATURE, "YA_TONG", R.string.health_dict_nature_ya_tong),
        Seed(DictType.PAIN_NATURE, "ZHUO_RE", R.string.health_dict_nature_zhuo_re),
        Seed(DictType.PAIN_NATURE, "DUN_TONG", R.string.health_dict_nature_dun_tong),

        // ── triggers TRIGGER ─────────────────────────────────
        Seed(DictType.TRIGGER, "BEER", R.string.health_dict_trigger_beer),
        Seed(DictType.TRIGGER, "LIQUOR", R.string.health_dict_trigger_liquor),
        Seed(DictType.TRIGGER, "SEAFOOD", R.string.health_dict_trigger_seafood),
        Seed(DictType.TRIGGER, "ORGAN_MEAT", R.string.health_dict_trigger_organ_meat),
        Seed(DictType.TRIGGER, "BROTH", R.string.health_dict_trigger_broth),
        Seed(DictType.TRIGGER, "SUGARY_DRINK", R.string.health_dict_trigger_sugary_drink),
        Seed(DictType.TRIGGER, "INTENSE_EXERCISE", R.string.health_dict_trigger_intense_exercise),
        Seed(DictType.TRIGGER, "COLD_DAMP", R.string.health_dict_trigger_cold_damp),
        Seed(DictType.TRIGGER, "TRAUMA", R.string.health_dict_trigger_trauma),
        Seed(DictType.TRIGGER, "FATIGUE", R.string.health_dict_trigger_fatigue),
        Seed(DictType.TRIGGER, "DIETING", R.string.health_dict_trigger_dieting),
        Seed(DictType.TRIGGER, "DIURETIC", R.string.health_dict_trigger_diuretic),
        // "No clear trigger" is an explicit user judgement, not "not filled in
        // yet" — it has to stay separately selectable.
        Seed(DictType.TRIGGER, "NONE", R.string.health_dict_trigger_none),

        // ── measurement method URIC_METHOD ───────────────────
        // `code` must match the `UricMethod` enum entry character for character.
        Seed(DictType.URIC_METHOD, "FINGER", R.string.health_method_finger, R.string.health_method_finger_short),
        Seed(DictType.URIC_METHOD, "LAB", R.string.health_method_lab, R.string.health_method_lab_short),
        Seed(DictType.URIC_METHOD, "REPORT", R.string.health_method_report, R.string.health_method_report_short),
        // UNKNOWN only backs CSV imports and legacy rows; kept resolvable but
        // out of the picker.
        Seed(DictType.URIC_METHOD, "UNKNOWN", R.string.health_method_unknown, enabled = false),
    )

    /** Called from `onCreate`. Existing rows are left untouched. */
    fun insertAll(
        db: SupportSQLiteDatabase,
        context: Context,
        now: Long = System.currentTimeMillis(),
    ) {
        // Within one type the sort order is 10, 20, 30…
        val counter = mutableMapOf<DictType, Int>()
        for (seed in seeds) {
            val order = (counter[seed.type] ?: 0) + 10
            counter[seed.type] = order

            val values = ContentValues().apply {
                put("type", seed.type.name)
                put("code", seed.code)
                put("label", context.getString(seed.labelRes))
                put("short_label", seed.shortRes.takeIf { it != 0 }?.let(context::getString))
                put("sort_order", order)
                put("enabled", if (seed.enabled) 1 else 0)
                put("builtin", 1)
                put("created_at", now)
                put("updated_at", now)
            }
            db.insert("dict_item", SQLiteDatabase.CONFLICT_IGNORE, values)
        }
    }

    /**
     * Built-in label overlay, keyed by `(type, code)`.
     *
     * `dict_item.label` is a snapshot of the language that created the database,
     * which is wrong the moment the user switches language. Built-in rows are
     * therefore relabelled from resources on every read; user-defined rows
     * (`builtin = 0`) keep whatever the user typed.
     */
    fun localizedLabels(context: Context): Map<Pair<DictType, String>, DictLabelText> =
        seeds.associate { seed ->
            (seed.type to seed.code) to DictLabelText(
                label = context.getString(seed.labelRes),
                shortLabel = seed.shortRes.takeIf { it != 0 }?.let(context::getString),
            )
        }

    /** Number of seeds; asserted by tests. */
    val count: Int get() = seeds.size
}
