package app.chompass.health.domain.pain

import app.chompass.health.domain.model.PainSide

/**
 * Pain-pattern statistics.
 *
 * Only **descriptive** numbers live here: how many episodes, how long they last,
 * which tags were ticked. Relative risk is deliberately absent — that would need
 * days *without* an episode to also record exposure, and all we have is the
 * episode log. Computing it from episodes alone turns "3 flares after hotpot"
 * into "hotpot triples your risk" with no denominator. For a gout diary that
 * number is worse than no number.
 */
object PainInsightCalculator {

    /** Sample floor: below this many episodes, report counts and draw no conclusions. */
    const val MIN_SAMPLE = 5

    /** Rolling window the screen reports on; the list's "where does it hit" card uses it too. */
    const val WINDOW_DAYS = 90

    /** Minimal input, without dictionaries or UI, so this stays unit-testable. */
    data class Sample(
        val startedAt: Long,
        val endedAt: Long?,
        val siteCode: String,
        val side: PainSide,
        /** Score when the episode started. */
        val startNrs: Int,
        /**
         * Peak. Equals [startNrs] when there are no change marks.
         *
         * **Statistics use this, not [startNrs]**: for an episode that climbed
         * from 5 to 9, the starting score reports a median of 5 while the user
         * remembers "the day it was a 9". Keeping both forces call sites to say
         * which one they mean — never reuse a single `nrs` for two meanings.
         */
        val peakNrs: Int = startNrs,
        val triggerCodes: List<String> = emptyList(),
        val natureCodes: List<String> = emptyList(),
        val affectWalk: Boolean = false,
        val affectSleep: Boolean = false,
    )

    /** Occurrences and share of `count / total`. */
    data class CodeCount(val code: String, val count: Int, val share: Float)

    data class SiteCount(
        val siteCode: String,
        val side: PainSide,
        val count: Int,
        val share: Float,
    )

    data class Insight(
        val windowDays: Int,
        /** Episodes inside the window, including ongoing ones. */
        val total: Int,
        val ongoing: Int,
        /** Finished episodes only; null = nothing to average. */
        val avgDurationMinutes: Int?,
        /** Median of the per-episode **peak**. */
        val medianNrs: Int?,
        /** Highest peak inside the window. */
        val maxNrs: Int?,
        val triggers: List<CodeCount>,
        val natures: List<CodeCount>,
        val sites: List<SiteCount>,
        /** Times "no clear trigger" was ticked on purpose; it must stay out of the ranking. */
        val noTriggerCount: Int,
        val walkAffected: Int,
        val sleepAffected: Int,
    ) {
        val sampleEnough: Boolean get() = total >= MIN_SAMPLE

        /** How many more episodes until the floor. */
        val missingForSample: Int get() = (MIN_SAMPLE - total).coerceAtLeast(0)

        val hasAnything: Boolean get() = total > 0
    }

    /** Dictionary code for "no clear trigger"; excluded from the trigger ranking. */
    private const val TRIGGER_NONE = "NONE"

    fun compute(
        samples: List<Sample>,
        since: Long,
        now: Long,
        windowDays: Int = WINDOW_DAYS,
    ): Insight {
        val inWindow = samples.filter { it.startedAt >= since && it.startedAt <= now }
        val total = inWindow.size
        if (total == 0) {
            return Insight(
                windowDays = windowDays,
                total = 0,
                ongoing = 0,
                avgDurationMinutes = null,
                medianNrs = null,
                maxNrs = null,
                triggers = emptyList(),
                natures = emptyList(),
                sites = emptyList(),
                noTriggerCount = 0,
                walkAffected = 0,
                sleepAffected = 0,
            )
        }

        // Average duration only over finished episodes: folding in the ongoing
        // one makes the average climb on every refresh, which reads as "getting
        // worse" rather than "still counting".
        val durations = inWindow.mapNotNull { s ->
            s.endedAt?.let { end -> ((end - s.startedAt) / 60_000L).toInt() }
        }
        // Ranked on peaks: that is the answer to "how bad was it". Starting
        // scores report the value at the moment of onset, and most people log
        // only once it becomes unbearable — the start value is the low one.
        val nrsSorted = inWindow.map { it.peakNrs }.sorted()

        fun rank(
            codesOf: (Sample) -> List<String>,
            exclude: Set<String> = emptySet(),
        ): List<CodeCount> = inWindow
            .flatMap { codesOf(it) }
            .filter { it.isNotBlank() && it !in exclude }
            .groupingBy { it }
            .eachCount()
            .map { (code, count) -> CodeCount(code, count, count.toFloat() / total) }
            .sortedWith(compareByDescending<CodeCount> { it.count }.thenBy { it.code })

        val sites = inWindow
            .groupBy { it.siteCode to it.side }
            .map { (key, list) ->
                SiteCount(
                    siteCode = key.first,
                    side = key.second,
                    count = list.size,
                    share = list.size.toFloat() / total,
                )
            }
            .sortedWith(
                compareByDescending<SiteCount> { it.count }
                    .thenBy { it.siteCode }
                    .thenBy { it.side.ordinal }
            )

        return Insight(
            windowDays = windowDays,
            total = total,
            ongoing = inWindow.count { it.endedAt == null },
            avgDurationMinutes = durations.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            // Median resists outliers: one three-day flare drags a mean off a cliff.
            medianNrs = nrsSorted[nrsSorted.size / 2],
            maxNrs = nrsSorted.last(),
            triggers = rank({ it.triggerCodes }, exclude = setOf(TRIGGER_NONE)),
            natures = rank(codesOf = { it.natureCodes }),
            sites = sites,
            noTriggerCount = inWindow.count { TRIGGER_NONE in it.triggerCodes },
            walkAffected = inWindow.count { it.affectWalk },
            sleepAffected = inWindow.count { it.affectSleep },
        )
    }
}
