package app.chompass.health.domain.dose

import app.chompass.health.domain.model.DoseInstanceStatus
import app.chompass.health.model.DoseState

/**
 * Persisted status -> the eight UI states.
 *
 * Only four states are ever written (`PENDING` / `TAKEN` / `SKIPPED` / `MISSED`);
 * `OVERDUE` and `LATE` are derived here. Writing "overdue" to the database would
 * make the record permanently wrong the moment the user changes the phone clock.
 *
 * No user-visible text lives in this object (see `ui/med/MedFormat`): a
 * "late by 44 minutes" line needs a language, and this file is JVM-testable.
 */
object DoseStateResolver {

    /** Tolerance: a deviation of up to 30 minutes still counts as on time. */
    const val ON_TIME_TOLERANCE_MIN = 30

    private const val MS_PER_MIN = 60_000L

    fun resolve(
        status: DoseInstanceStatus,
        planAt: Long,
        now: Long,
        /** Actual intake time; required when the status is `TAKEN`. */
        actualAt: Long? = null,
        toleranceMin: Int = ON_TIME_TOLERANCE_MIN,
    ): DoseState = when (status) {
        DoseInstanceStatus.SKIPPED -> DoseState.CANCELLED
        DoseInstanceStatus.MISSED -> DoseState.MISSED
        DoseInstanceStatus.TAKEN -> {
            val deviation = ((actualAt ?: planAt) - planAt) / MS_PER_MIN
            // Taking it early counts as on time; only a clear delay is "late".
            if (deviation > toleranceMin) DoseState.LATE else DoseState.TAKEN
        }
        DoseInstanceStatus.PENDING ->
            if (now > planAt + toleranceMin * MS_PER_MIN) DoseState.OVERDUE else DoseState.PENDING
    }

    /** Deviation in minutes; null when there is no plan time or no intake time. */
    fun deviationMinutes(planAt: Long?, actualAt: Long?): Int? {
        if (planAt == null || actualAt == null) return null
        return ((actualAt - planAt) / MS_PER_MIN).toInt()
    }

    /** Minutes a pending dose is past its plan time; 0 when it is not yet due. */
    fun overdueMinutes(planAt: Long, now: Long): Int =
        (((now - planAt) / MS_PER_MIN).toInt()).coerceAtLeast(0)
}
