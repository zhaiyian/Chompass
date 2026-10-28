package app.chompass.health.domain.uric

import app.chompass.health.util.TimeUtils
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/**
 * The time view at the top of the uric-acid screen. The index matches the
 * segmented control left to right.
 *
 * Why not "last 7 / 30 / 90 days / all": those windows can only express
 * "how many days back from today", so the user cannot ask "how did last month
 * go". Natural periods plus paging carry the look-back, and look-back is the
 * core need of a chronic-condition log.
 *
 * No display text here: the label is a `when` in the UI layer, because these
 * are resource strings.
 */
enum class UricPeriod {
    WEEK,
    MONTH,
    QUARTER,
    CUSTOM,
    ;

    companion object {
        fun fromIndex(index: Int): UricPeriod = entries.getOrElse(index) { MONTH }
    }
}

/**
 * One X-axis tick: the day it stands for plus its normalized position (0..1)
 * inside the plot. The label is formatted in the UI layer so weekdays and month
 * names follow the device locale.
 */
data class AxisTick(val date: LocalDate, val fraction: Float)

/**
 * 观察区间：一个自然周期（周 / 月 / 季）或用户自选的一段。
 *
 * 不变式：`start <= end` 且首尾**都含**。
 * 构造时就把 CUSTOM 的两个日期排好序——外部传反了不该让区间算成空。
 */
data class UricRange(
    val period: UricPeriod = UricPeriod.MONTH,
    /** 周期锚点：落在目标周期内的任意一天。翻页时整体平移，不用管它具体是几号。 */
    val anchor: LocalDate,
    val customStart: LocalDate? = null,
    val customEnd: LocalDate? = null,
) {

    /** 区间首日（含） */
    val start: LocalDate
    /** 区间末日（含） */
    val end: LocalDate

    init {
        when (period) {
            UricPeriod.WEEK -> {
                // 周一为一周之首。用 previousOrSame 而不是 minusDays(dayOfWeek-1)：
                // 后者在"锚点就是周一"时算出来是上周一。
                start = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                end = start.plusDays(6)
            }
            UricPeriod.MONTH -> {
                start = anchor.withDayOfMonth(1)
                end = start.plusMonths(1).minusDays(1)
            }
            UricPeriod.QUARTER -> {
                // 自然季度：1–3 / 4–6 / 7–9 / 10–12
                val firstMonth = (anchor.monthValue - 1) / 3 * 3 + 1
                start = LocalDate.of(anchor.year, firstMonth, 1)
                end = start.plusMonths(3).minusDays(1)
            }
            UricPeriod.CUSTOM -> {
                val s = customStart ?: anchor
                val e = customEnd ?: s
                start = if (s <= e) s else e
                end = if (s <= e) e else s
            }
        }
    }

    /** 区间总天数（含首尾）。至少 1。 */
    val days: Int get() = (ChronoUnit.DAYS.between(start, end).toInt() + 1).coerceAtLeast(1)

    fun startMillis(): Long = TimeUtils.startOfDay(start)

    fun endMillis(): Long = TimeUtils.endOfDay(end)

    /* ---------- paging ---------- */

    fun previous(): UricRange = when (period) {
        // 用 start 平移而不是 anchor：anchor 可能落在月末 31 号，
        // 减一个月会掉到 2 月 28 号，再减一次就滑出原有节奏。
        UricPeriod.WEEK -> copy(anchor = start.minusWeeks(1))
        UricPeriod.MONTH -> copy(anchor = start.minusMonths(1))
        UricPeriod.QUARTER -> copy(anchor = start.minusMonths(3))
        UricPeriod.CUSTOM -> this
    }

    fun next(): UricRange = when (period) {
        UricPeriod.WEEK -> copy(anchor = start.plusWeeks(1))
        UricPeriod.MONTH -> copy(anchor = start.plusMonths(1))
        UricPeriod.QUARTER -> copy(anchor = start.plusMonths(3))
        UricPeriod.CUSTOM -> this
    }

    /**
     * 能否向后翻：下一段的**起点**必须不晚于今天。
     *
     * 只看 `end < today` 会多放行一格——9 月区间 end=9/30 在今天之前，
     * 点一下会跳到 10 月，而 10 月本来就是当前正在过的月份。
     */
    fun canGoNext(today: LocalDate): Boolean = !end.plusDays(1).isAfter(today)

    /** 自定义区间不能翻页，只能重选 */
    val pageable: Boolean get() = period != UricPeriod.CUSTOM

    /* ---------- X-axis ticks ---------- */

    /**
     * Ticks change shape per view: week = Mon..Sun, quarter = months,
     * month/custom = evenly spaced dates.
     *
     * Labels are always derived from the real dates. Hardcoding
     * "Mon Tue Wed…" happens to be right for a natural week, but a custom range
     * spanning weeks would drift — that failure does not crash, it just makes
     * the user read the time axis wrong.
     */
    fun ticks(): List<AxisTick> = when (period) {
        UricPeriod.WEEK -> (0L..6L).map { i ->
            AxisTick(start.plusDays(i), i / 6f)
        }
        UricPeriod.QUARTER -> (0L..2L).map { i ->
            AxisTick(start.plusMonths(i), i / 2f)
        }
        else -> evenDateTicks()
    }

    /** Month / custom: 6 evenly spaced dates (both ends included), safe for a single day. */
    private fun evenDateTicks(): List<AxisTick> {
        if (days <= 1) return listOf(AxisTick(start, 0f))
        val step = (days - 1) / 5f
        return (0..5).map { i ->
            val d = start.plusDays((step * i).roundToInt().toLong())
            AxisTick(d, i / 5f)
        }
    }

    companion object {
        /** Default range when the app opens: the current month. */
        fun currentMonth(today: LocalDate): UricRange =
            UricRange(UricPeriod.MONTH, today)
    }
}
