package app.chompass.health.domain.dose

/**
 * Adherence rate.
 *
 * Denominator: instances in the window with `plan_at <= now` and
 * `status != SKIPPED`.
 *
 * - a `PENDING` dose that is not due yet is not a debt, so it stays out;
 * - a dose the user actively skipped is a decision, not an omission;
 * - as-needed medications have no instances and therefore never take part.
 */
object AdherenceCalculator {

    fun percent(taken: Int, total: Int): Int? {
        if (total <= 0) return null
        return Math.round(taken * 100f / total)
    }
}
