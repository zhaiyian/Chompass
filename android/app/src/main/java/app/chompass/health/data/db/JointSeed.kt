package app.chompass.health.data.db

import android.content.ContentValues
import android.content.Context
import androidx.annotation.StringRes
import androidx.sqlite.db.SupportSQLiteDatabase
import app.chompass.R
import app.chompass.health.data.db.entity.PainJointEntity
import app.chompass.health.domain.model.DictType

/**
 * `pain_joint` 的内置种子：6 个大区 / 11 个趾·指 / 39 个关节。
 *
 * 与 `DictSeed` 的差别：这是**解剖枚举**，不是用户词表。用户不能改、不能删，
 * 只有"其他大关节"下允许自己补一个说不清的部位（仍走 `dict_item`）。
 *
 * 三处对原始数据的修正（见 `03 §3.9`）：
 * 1. **「前脚掌 / 足背」从 digits 上提到大区直属**——它只有一个关节，
 *    留在趾层会让级联出现"点了前脚掌、再点一次前脚掌"。
 * 2. **同层级命名统一成 `根部 / 中段 / 指尖侧`**。原数据里拇指用「指间关节」、
 *    食指用「靠近手掌的关节」、脚趾又用「中段关节」，同一层两套粒度。
 * 3. **`sideRequired = false` 给脊柱与"说不清"**，其余 37 个都要选左右。
 *
 * `diseaseTag` 原样保留，但只用于排序与统计分组，界面上一个字都不出现。
 */
internal object JointSeed {

    /** 只做 `INSERT OR IGNORE`：解剖定义不该在升级时覆盖用户库里的任何东西。 */
    fun insertAll(db: SupportSQLiteDatabase) {
        JOINT_ROWS.forEach { j ->
            val values = ContentValues().apply {
                put("joint_id", j.jointId)
                put("region_id", j.regionId)
                put("region_name", j.regionName)
                put("region_order", j.regionOrder)
                put("digit_id", j.digitId)
                put("digit_name", j.digitName)
                put("digit_order", j.digitOrder)
                put("display_name", j.displayName)
                put("anatomy_name", j.anatomyName)
                put("disease_tag", j.diseaseTag)
                put("side_required", if (j.sideRequired) 1 else 0)
                put("sort_order", j.sortOrder)
            }
            db.insert("pain_joint", android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE, values)
        }
    }

    private fun row(
        region: String, regionName: String, regionOrder: Int,
        digit: String?, digitName: String?, digitOrder: Int?,
        jointId: String, display: String, anatomy: String,
        tag: String, sort: Int, side: Boolean = true,
    ) = PainJointEntity(
        jointId = jointId, regionId = region, regionName = regionName, regionOrder = regionOrder,
        digitId = digit, digitName = digitName, digitOrder = digitOrder,
        displayName = display, anatomyName = anatomy, diseaseTag = tag,
        sideRequired = side, sortOrder = sort,
    )

    /** 脚趾 / 前脚掌 —— sort 在趾内递增，趾之间隔 10，给日后插关节留空。 */
    private fun toe(
        digit: String, digitName: String, digitOrder: Int,
        jointId: String, display: String, anatomy: String, tag: String, sort: Int,
    ) = row("TOE", "脚趾 / 前脚掌", 1, digit, digitName, digitOrder, jointId, display, anatomy, tag, sort)

    private fun finger(
        digit: String, digitName: String, digitOrder: Int,
        jointId: String, display: String, anatomy: String, tag: String, sort: Int,
    ) = row("FINGER", "手指", 2, digit, digitName, digitOrder, jointId, display, anatomy, tag, sort)

    private val JOINT_ROWS: List<PainJointEntity> = listOf(
        // ── 脚趾 / 前脚掌 ─────────────────────────────────────
        toe("TOE1", "大脚趾", 1, "MTP1", "根部", "第一跖趾关节", "GOUT_HIGH", 10),
        toe("TOE1", "大脚趾", 1, "TOE1_IP", "指尖侧", "拇趾趾间关节", "GOUT_HIGH", 20),

        toe("TOE2", "第二趾", 2, "TOE2_MTP", "根部", "第二跖趾关节", "BOTH", 10),
        toe("TOE2", "第二趾", 2, "TOE2_PIP", "中段", "第二趾近端趾间关节", "BOTH", 20),
        toe("TOE2", "第二趾", 2, "TOE2_DIP", "指尖侧", "第二趾远端趾间关节", "BOTH", 30),

        toe("TOE3", "第三趾", 3, "TOE3_MTP", "根部", "第三跖趾关节", "BOTH", 10),
        toe("TOE3", "第三趾", 3, "TOE3_PIP", "中段", "第三趾近端趾间关节", "BOTH", 20),
        toe("TOE3", "第三趾", 3, "TOE3_DIP", "指尖侧", "第三趾远端趾间关节", "BOTH", 30),

        toe("TOE4", "第四趾", 4, "TOE4_MTP", "根部", "第四跖趾关节", "BOTH", 10),
        toe("TOE4", "第四趾", 4, "TOE4_PIP", "中段", "第四趾近端趾间关节", "BOTH", 20),
        toe("TOE4", "第四趾", 4, "TOE4_DIP", "指尖侧", "第四趾远端趾间关节", "BOTH", 30),

        toe("TOE5", "小趾", 5, "TOE5_MTP", "根部", "第五跖趾关节", "BOTH", 10),
        toe("TOE5", "小趾", 5, "TOE5_PIP", "中段", "第五趾近端趾间关节", "BOTH", 20),
        toe("TOE5", "小趾", 5, "TOE5_DIP", "指尖侧", "第五趾远端趾间关节", "BOTH", 30),

        // 大区直属：原数据把它塞在 digits 里，但只有一个关节，
        // 留在趾层会让用户"点了前脚掌还要再点一次前脚掌"。
        row("TOE", "脚趾 / 前脚掌", 1, null, null, null,
            "FOREFOOT", "前脚掌 / 足背", "跖跗关节区", "GOUT_HIGH", 90),

        // ── 手指 ─────────────────────────────────────────────
        finger("THUMB", "大拇指", 1, "THUMB_MCP", "根部", "拇指掌指关节", "RA_HIGH", 10),
        finger("THUMB", "大拇指", 1, "THUMB_IP", "指尖侧", "拇指指间关节", "BOTH", 20),

        finger("INDEX", "食指", 2, "INDEX_MCP", "根部", "食指掌指关节", "RA_HIGH", 10),
        finger("INDEX", "食指", 2, "INDEX_PIP", "中段", "食指近端指间关节", "RA_HIGH", 20),
        finger("INDEX", "食指", 2, "INDEX_DIP", "指尖侧", "食指远端指间关节", "RA_HIGH", 30),

        finger("MIDDLE", "中指", 3, "MIDDLE_MCP", "根部", "中指掌指关节", "RA_HIGH", 10),
        finger("MIDDLE", "中指", 3, "MIDDLE_PIP", "中段", "中指近端指间关节", "RA_HIGH", 20),
        finger("MIDDLE", "中指", 3, "MIDDLE_DIP", "指尖侧", "中指远端指间关节", "RA_HIGH", 30),

        finger("RING", "无名指", 4, "RING_MCP", "根部", "无名指掌指关节", "RA_HIGH", 10),
        finger("RING", "无名指", 4, "RING_PIP", "中段", "无名指近端指间关节", "RA_HIGH", 20),
        finger("RING", "无名指", 4, "RING_DIP", "指尖侧", "无名指远端指间关节", "RA_HIGH", 30),

        finger("LITTLE", "小指", 5, "LITTLE_MCP", "根部", "小指掌指关节", "RA_HIGH", 10),
        finger("LITTLE", "小指", 5, "LITTLE_PIP", "中段", "小指近端指间关节", "RA_HIGH", 20),
        finger("LITTLE", "小指", 5, "LITTLE_DIP", "指尖侧", "小指远端指间关节", "RA_HIGH", 30),

        // ── 手腕（无趾 / 指层）────────────────────────────────
        row("WRIST", "手腕", 3, null, null, null, "WRIST", "手腕", "腕关节", "RA_HIGH", 10),
        row("WRIST", "手腕", 3, null, null, null, "WRIST_CMC", "拇指侧根部", "拇指腕掌关节", "BOTH", 20),

        // ── 脚踝 / 足跟 ──────────────────────────────────────
        row("ANKLE", "脚踝 / 足跟", 4, null, null, null, "ANKLE", "脚踝", "踝关节", "BOTH", 10),
        row("ANKLE", "脚踝 / 足跟", 4, null, null, null, "HEEL", "足跟", "跟骨区", "GOUT_HIGH", 20),

        // ── 膝盖 ────────────────────────────────────────────
        row("KNEE", "膝盖", 5, null, null, null, "KNEE", "膝盖", "膝关节", "BOTH", 10),

        // ── 其他大关节 ──────────────────────────────────────
        row("OTHER", "其他大关节", 6, null, null, null, "ELBOW", "肘关节", "肘关节", "BOTH", 10),
        row("OTHER", "其他大关节", 6, null, null, null, "SHOULDER", "肩关节", "肩关节", "GENERAL", 20),
        row("OTHER", "其他大关节", 6, null, null, null, "HIP", "髋关节 / 胯部", "髋关节", "GENERAL", 30),
        row("OTHER", "其他大关节", 6, null, null, null, "SPINE", "腰背 / 脊柱", "脊柱", "GENERAL", 40, side = false),
        // 老数据里的 site_code = 'OTHER' 要有地方落，否则升级后那条记录查不到部位
        row("OTHER", "其他大关节", 6, null, null, null, "OTHER", "其他 / 说不清", "—", "GENERAL", 90, side = false),
    )

    /** 关节 id 的集合，给自己的单测与"是不是解剖枚举"判断用 */
    val jointIds: Set<String> get() = JOINT_ROWS.map { it.jointId }.toSet()

    /** 只给单测看的全量行：验证"没有孤立趾、没有重 id、侧别开关只落在该落的地方" */
    val rows: List<PainJointEntity> get() = JOINT_ROWS

    /**
     * 把 `pain_joint` 投影成 `dict_item(SITE)` 的文案行，并停用老的 9 个内置部位。
     *
     * 为什么要投影一份到字典：**部位文案只用一条解析路径**。
     * `siteLabel` / CSV 导出 / CSV 导入 / B6 统计全都走 `dict.label(SITE, code)`，
     * 只要 code 能在字典里查到文案，这四处一行都不用改。
     * 如果让它们各自去查 `pain_joint`，就会有四条实现、四个机会不一致。
     *
     * 投影出来的 label 是**路径**（「大脚趾」+「根部」= 大脚趾根部）：
     * 只写「根部」在一屏三个「根部」里认不出是哪儿。
     */
    fun syncDictItems(db: SupportSQLiteDatabase, now: Long) {
        db.execSQL(DICT_SYNC_SQL, arrayOf<Any?>(now, now))
        db.execSQL(DICT_DISABLE_LEGACY_SQL)
    }

    private const val DICT_SYNC_SQL = """
        INSERT OR IGNORE INTO `dict_item`
            (`type`,`code`,`label`,`short_label`,`sort_order`,`enabled`,`builtin`,`created_at`,`updated_at`)
        SELECT 'SITE', `joint_id`,
               COALESCE(`digit_name`, '') || `display_name`,
               NULL,
               `region_order` * 1000 + COALESCE(`digit_order`, 0) * 100 + `sort_order`,
               1, 1, ?, ?
        FROM `pain_joint`
    """

    /** 这 9 个老 code 已被关节 id 取代：不删（历史仍要能解析），只是不再出现在选择器里。 */
    private const val DICT_DISABLE_LEGACY_SQL = """
        UPDATE `dict_item` SET `enabled` = 0
        WHERE `type` = 'SITE' AND `builtin` = 1
          AND `code` IN ('FOOT_MTP1','FOOT_DORSAL','HAND_THUMB','ANKLE','KNEE','HEEL','WRIST','ELBOW','OTHER')
    """

    /** 旧 `dict_item(SITE)` code → 新 jointId。迁移用。 */
    val legacySiteMap: Map<String, String> = mapOf(
        "FOOT_MTP1" to "MTP1",
        "FOOT_DORSAL" to "FOREFOOT",
        "HAND_THUMB" to "THUMB_MCP",
        "ANKLE" to "ANKLE",
        "KNEE" to "KNEE",
        "HEEL" to "HEEL",
        "WRIST" to "WRIST",
        "ELBOW" to "ELBOW",
        "OTHER" to "OTHER",
    )

    // ── 文案：重建时按当前语言解析 ───────────────────────────

    /**
     * The four name columns of `pain_joint` are a snapshot of the language the
     * database was created in — same story as `dict_item`. They stay in the table
     * as a fallback and are re-resolved from resources on read, so switching the
     * app language does not leave a Chinese joint name behind.
     */
    data class NameText(
        val region: String,
        val digit: String?,
        val display: String,
        val anatomy: String,
    )

    fun localizedNames(context: Context): Map<String, NameText> =
        NAME_SPECS.associate { spec ->
            spec.jointId to NameText(
                region = context.getString(spec.regionRes),
                digit = spec.digitRes.takeIf { it != 0 }?.let(context::getString),
                display = context.getString(spec.displayRes),
                anatomy = context.getString(spec.anatomyRes),
            )
        }

    /** `big toe · base`: one separator, because `digit + display` has no language-neutral spacing. */
    fun pathOf(context: Context, name: NameText): String =
        name.digit?.let { context.getString(R.string.health_site_path, it, name.display) }
            ?: name.display

    /**
     * Localized labels for the `dict_item(SITE)` rows projected from this table.
     *
     * [syncDictItems] projects the creation-time names; overriding them here means
     * `dict.label(SITE, code)` is correct in one place for lists, detail sheets,
     * statistics and CSV export alike.
     */
    fun localizedSiteLabels(context: Context): Map<Pair<DictType, String>, DictLabelText> =
        localizedNames(context).entries.associate { (jointId, name) ->
            (DictType.SITE to jointId) to DictLabelText(pathOf(context, name), null)
        }

    private data class NameSpec(
        val jointId: String,
        @StringRes val regionRes: Int,
        /** 0 = the region has no toe / finger level. */
        @StringRes val digitRes: Int,
        @StringRes val displayRes: Int,
        @StringRes val anatomyRes: Int,
    )

    private val NAME_SPECS: List<NameSpec> = listOf(
        NameSpec("MTP1", R.string.health_region_toe, R.string.health_digit_toe1,
            R.string.health_joint_root, R.string.health_anatomy_mtp1),
        NameSpec("TOE1_IP", R.string.health_region_toe, R.string.health_digit_toe1,
            R.string.health_joint_tip, R.string.health_anatomy_toe1_ip),
        NameSpec("TOE2_MTP", R.string.health_region_toe, R.string.health_digit_toe2,
            R.string.health_joint_root, R.string.health_anatomy_toe2_mtp),
        NameSpec("TOE2_PIP", R.string.health_region_toe, R.string.health_digit_toe2,
            R.string.health_joint_mid, R.string.health_anatomy_toe2_pip),
        NameSpec("TOE2_DIP", R.string.health_region_toe, R.string.health_digit_toe2,
            R.string.health_joint_tip, R.string.health_anatomy_toe2_dip),
        NameSpec("TOE3_MTP", R.string.health_region_toe, R.string.health_digit_toe3,
            R.string.health_joint_root, R.string.health_anatomy_toe3_mtp),
        NameSpec("TOE3_PIP", R.string.health_region_toe, R.string.health_digit_toe3,
            R.string.health_joint_mid, R.string.health_anatomy_toe3_pip),
        NameSpec("TOE3_DIP", R.string.health_region_toe, R.string.health_digit_toe3,
            R.string.health_joint_tip, R.string.health_anatomy_toe3_dip),
        NameSpec("TOE4_MTP", R.string.health_region_toe, R.string.health_digit_toe4,
            R.string.health_joint_root, R.string.health_anatomy_toe4_mtp),
        NameSpec("TOE4_PIP", R.string.health_region_toe, R.string.health_digit_toe4,
            R.string.health_joint_mid, R.string.health_anatomy_toe4_pip),
        NameSpec("TOE4_DIP", R.string.health_region_toe, R.string.health_digit_toe4,
            R.string.health_joint_tip, R.string.health_anatomy_toe4_dip),
        NameSpec("TOE5_MTP", R.string.health_region_toe, R.string.health_digit_toe5,
            R.string.health_joint_root, R.string.health_anatomy_toe5_mtp),
        NameSpec("TOE5_PIP", R.string.health_region_toe, R.string.health_digit_toe5,
            R.string.health_joint_mid, R.string.health_anatomy_toe5_pip),
        NameSpec("TOE5_DIP", R.string.health_region_toe, R.string.health_digit_toe5,
            R.string.health_joint_tip, R.string.health_anatomy_toe5_dip),
        NameSpec("FOREFOOT", R.string.health_region_toe, 0,
            R.string.health_joint_forefoot, R.string.health_anatomy_forefoot),
        NameSpec("THUMB_MCP", R.string.health_region_finger, R.string.health_digit_thumb,
            R.string.health_joint_root, R.string.health_anatomy_thumb_mcp),
        NameSpec("THUMB_IP", R.string.health_region_finger, R.string.health_digit_thumb,
            R.string.health_joint_tip, R.string.health_anatomy_thumb_ip),
        NameSpec("INDEX_MCP", R.string.health_region_finger, R.string.health_digit_index,
            R.string.health_joint_root, R.string.health_anatomy_index_mcp),
        NameSpec("INDEX_PIP", R.string.health_region_finger, R.string.health_digit_index,
            R.string.health_joint_mid, R.string.health_anatomy_index_pip),
        NameSpec("INDEX_DIP", R.string.health_region_finger, R.string.health_digit_index,
            R.string.health_joint_tip, R.string.health_anatomy_index_dip),
        NameSpec("MIDDLE_MCP", R.string.health_region_finger, R.string.health_digit_middle,
            R.string.health_joint_root, R.string.health_anatomy_middle_mcp),
        NameSpec("MIDDLE_PIP", R.string.health_region_finger, R.string.health_digit_middle,
            R.string.health_joint_mid, R.string.health_anatomy_middle_pip),
        NameSpec("MIDDLE_DIP", R.string.health_region_finger, R.string.health_digit_middle,
            R.string.health_joint_tip, R.string.health_anatomy_middle_dip),
        NameSpec("RING_MCP", R.string.health_region_finger, R.string.health_digit_ring,
            R.string.health_joint_root, R.string.health_anatomy_ring_mcp),
        NameSpec("RING_PIP", R.string.health_region_finger, R.string.health_digit_ring,
            R.string.health_joint_mid, R.string.health_anatomy_ring_pip),
        NameSpec("RING_DIP", R.string.health_region_finger, R.string.health_digit_ring,
            R.string.health_joint_tip, R.string.health_anatomy_ring_dip),
        NameSpec("LITTLE_MCP", R.string.health_region_finger, R.string.health_digit_little,
            R.string.health_joint_root, R.string.health_anatomy_little_mcp),
        NameSpec("LITTLE_PIP", R.string.health_region_finger, R.string.health_digit_little,
            R.string.health_joint_mid, R.string.health_anatomy_little_pip),
        NameSpec("LITTLE_DIP", R.string.health_region_finger, R.string.health_digit_little,
            R.string.health_joint_tip, R.string.health_anatomy_little_dip),
        NameSpec("WRIST", R.string.health_region_wrist, 0,
            R.string.health_joint_wrist, R.string.health_anatomy_wrist),
        NameSpec("WRIST_CMC", R.string.health_region_wrist, 0,
            R.string.health_joint_wrist_cmc, R.string.health_anatomy_wrist_cmc),
        NameSpec("ANKLE", R.string.health_region_ankle, 0,
            R.string.health_joint_ankle, R.string.health_anatomy_ankle),
        NameSpec("HEEL", R.string.health_region_ankle, 0,
            R.string.health_joint_heel, R.string.health_anatomy_heel),
        NameSpec("KNEE", R.string.health_region_knee, 0,
            R.string.health_joint_knee, R.string.health_anatomy_knee),
        NameSpec("ELBOW", R.string.health_region_other, 0,
            R.string.health_joint_elbow, R.string.health_anatomy_elbow),
        NameSpec("SHOULDER", R.string.health_region_other, 0,
            R.string.health_joint_shoulder, R.string.health_anatomy_shoulder),
        NameSpec("HIP", R.string.health_region_other, 0,
            R.string.health_joint_hip, R.string.health_anatomy_hip),
        NameSpec("SPINE", R.string.health_region_other, 0,
            R.string.health_joint_spine, R.string.health_anatomy_spine),
        NameSpec("OTHER", R.string.health_region_other, 0,
            R.string.health_joint_other, R.string.health_anatomy_other),
    )
}
