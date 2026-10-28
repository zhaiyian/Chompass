package app.chompass.health.domain.dose

import app.chompass.health.domain.model.MealRelation
import app.chompass.health.util.TimeUtils
import java.time.LocalDate

/** Snapshot of one medication needed to plan a dose (taken from `medication`). */
data class ScheduleSeed(
    val medicationId: Long,
    val scheduleId: Long?,
    val drugName: String,
    val strengthValue: Double?,
    val strengthUnit: String?,
    val intakeUnits: Double,
    val unitLabel: String,
    val mealRelation: MealRelation,
    /** HH:mm */
    val timeOfDay: String?,
)

/** A planned dose before it is written. Pure data — no Room entity here. */
data class PlannedDose(
    val medicationId: Long,
    val scheduleId: Long?,
    val planAt: Long,
    val planDate: String,
    val drugName: String,
    val strengthValue: Double?,
    val strengthUnit: String?,
    val intakeUnits: Double,
    val unitLabel: String,
    val mealRelation: MealRelation,
)

/**
 * Plans today's doses from the medication list.
 *
 * Idempotency is the database's job (`(medication_id, plan_at)` unique index +
 * `INSERT OR IGNORE`), so callers may call this on every resume without first
 * asking "was today already generated".
 *
 * As-needed (`is_prn`) medications have no "should have taken it" moment and are
 * filtered out by the caller, never passed in here.
 */
object InstanceGenerator {

    fun planFor(date: LocalDate, seeds: List<ScheduleSeed>): List<PlannedDose> {
        val dateText = TimeUtils.dateString(date)
        return seeds.mapNotNull { seed ->
            val at = seed.timeOfDay?.let { TimeUtils.at(date, it) } ?: return@mapNotNull null
            PlannedDose(
                medicationId = seed.medicationId,
                scheduleId = seed.scheduleId,
                planAt = at,
                planDate = dateText,
                drugName = seed.drugName,
                strengthValue = seed.strengthValue,
                strengthUnit = seed.strengthUnit,
                intakeUnits = seed.intakeUnits,
                unitLabel = seed.unitLabel,
                mealRelation = seed.mealRelation,
            )
        }
    }
}
