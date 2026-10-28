package app.chompass.health.domain.dose

import kotlin.math.floor

/**
 * Remaining stock, in days.
 *
 * Stock is only decremented when the user actually records a dose (see
 * `MedRepository`) — never inferred from the calendar. A system that "calculates"
 * consumption lets the count drift away from the box in the drawer.
 */
object StockCalculator {

    /** Daily use = units per dose × doses per day. */
    fun dailyUsage(intakeUnits: Double, freqPerDay: Int): Double =
        intakeUnits * freqPerDay.coerceAtLeast(0)

    /** Days left. Null when stock is untracked or consumption is zero (no bar shown). */
    fun remainingDays(stockUnits: Double?, intakeUnits: Double, freqPerDay: Int): Int? {
        if (stockUnits == null) return null
        val daily = dailyUsage(intakeUnits, freqPerDay)
        if (daily <= 0.0) return null
        return floor(stockUnits / daily).toInt().coerceAtLeast(0)
    }

    fun isLow(remainingDays: Int?, alertDays: Int): Boolean =
        remainingDays != null && remainingDays <= alertDays
}
