package app.chompass.health.model

import app.chompass.health.domain.model.PainSide

/* ============================================================
 * 领域模型（第一版只含 尿酸 / 疼痛 / 用药 三个模块）
 * ============================================================ */

enum class AppTab { URIC, PAIN, MED }

/**
 * 尿酸分级。
 *
 * `BELOW_TARGET` 沿用圆点形状、换绿色 + 文字「达标」，**不新增第 4 种形状**：
 * 达标与接近目标共用 ○，无障碍靠「位置（落在目标线下方）+ 颜色（绿/蓝非红绿对立）
 * + 文字」三重冗余成立。图例保持 3 项，第一项文案合并为「达标 / 接近目标」——
 * 拆成两项会画出两个圆点，反而让人以为有区别。
 *
 * 阈值 420 / 480 为临床值，不随目标值变化；只有达标档相对目标判定。
 */
enum class UricLevel { BELOW_TARGET, NEAR, HIGH, VERY_HIGH }

/**
 * 服药八态。
 * 颜色只承载状态语义：橙=时间偏差、红=漏服、绿=已服、蓝=待办/按需。
 */
enum class DoseState {
    /** 待服 ○ 蓝 */
    PENDING,
    /** 已服准时 ● 绿 */
    TAKEN,
    /** 已服延迟 ● 绿 + 橙副行 */
    LATE,
    /** 逾期 ▲ 琥珀（不用红） */
    OVERDUE,
    /** 漏服 ⊠ 红 */
    MISSED,
    /** 按需 ◆ 蓝 */
    PRN,
    /** 已取消 ⊠ 灰绿虚线 */
    CANCELLED,
    /** 额外 ◇ 蓝 */
    EXTRA
}

/**
 * 用药分类：长期 / 急性 / 其它（3 类兜底）。
 * 注：设计稿 C3 上画的是「长期降尿酸 / 急性期抗炎 / 碱化尿液」，
 * 代码里保留了产品侧最终确认的 3 类口径，接数据时按此归类。
 */
enum class MedCategory {
    LONG_TERM, ACUTE, OTHER
}

data class UricRecord(
    val value: Int,
    val supportText: String,
    val level: UricLevel
)

/**
 * One point on an episode's pain timeline: the start, or one later mark.
 *
 * [delta] is **derived** (difference from the previous point), never stored.
 * Positive = worse, negative = easing, 0 = unchanged. The first point is always 0.
 */
data class PainNrsPoint(
    val at: Long,
    val nrs: Int,
    val delta: Int,
    /** Mark id; 0 for the start point, which lives in `pain_episode` and cannot be deleted. */
    val updateId: Long = 0L,
) {
    val isStart: Boolean get() = updateId == 0L
}

/**
 * One flare, in the shape the screens need.
 *
 * **Numbers and codes only.** Every user-visible string is built in the UI layer
 * (`ui/pain/PainFormat`) because the site label needs the dictionary, the
 * duration needs a language, and "today / yesterday" needs a clock. Baking the
 * text in here would freeze the language at mapping time — the exact bug the
 * dictionary snapshot already had to be fixed for.
 */
data class PainEpisode(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val siteCode: String,
    val side: PainSide,
    /** Score when the flare started (`pain_episode.nrs`). */
    val startNrs: Int,
    /** Last timeline point; equals [startNrs] when nothing was marked since. */
    val currentNrs: Int,
    /** Worst moment — a better severity answer than the starting score. */
    val peakNrs: Int,
    /** Ascending; the first point is the start. */
    val timeline: List<PainNrsPoint>,
    val natureCodes: List<String>,
    val triggerCodes: List<String>,
    val affectWalk: Boolean,
    val affectSleep: Boolean,
    val note: String,
) {
    val ongoing: Boolean get() = endedAt == null

    /** Only a flare with marks gets a timeline block; the start point alone is not one. */
    val hasUpdates: Boolean get() = timeline.size > 1

    /** Elapsed millis, counting up to [now] while the flare is still running. */
    fun durationMs(now: Long): Long = ((endedAt ?: now) - startedAt).coerceAtLeast(0L)
}

/**
 * 今日清单的行内快捷操作。
 * 语义分工：SKIP = 这次计划作废（不计入依从率）；UNDO = 撤销已记录的「已服」；
 * BACKFILL = 逾期后补记；MISS = 逾期后确认漏服。BACKFILL 与 MISS 等权，不替用户判定。
 * REOPEN_MISS = 撤销「漏服」这个结论，把它退回逾期（尚未有结论）——漏服是用户手点的，
 * 就得分得清"我确实漏了"和"我点错了"，否则一次误触就是一条改不掉的数据。
 */
enum class DoseAction { RECORD, SKIP, BACKFILL, MISS, UNDO, RESUME, REOPEN_MISS }

data class DoseLogRow(
    val time: String,
    val drug: String,
    val doseText: String,
    val meta: String,
    val state: DoseState
)

data class TrendPoint(val x: Float, val y: Float, val level: UricLevel, val latest: Boolean = false)
