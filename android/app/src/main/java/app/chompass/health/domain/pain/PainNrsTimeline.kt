package app.chompass.health.domain.pain

/**
 * Derives the pain-value timeline.
 *
 * Input is the starting score plus a series of change marks; output carries a
 * [Point.delta] per point.
 *
 * ## Why the trend is computed here
 *
 * Only `nrs` is persisted (see `PainUpdateEntity`) — the trend is a derived
 * value. Derivation must exist in exactly one place: the repository only stores
 * and loads, the UI only draws, and a wrong number can be traced in one step.
 * Living in `domain` also means it is a pure function, testable without a device.
 *
 * ## Merge-on-write is not this layer's job
 *
 * The "collapse taps inside the same minute" rule lives in
 * `PainRepository.addUpdate`, before the row is written. What arrives here is
 * already the final sequence: nothing is guessed, merged or dropped.
 */
object PainNrsTimeline {

    /**
     * @param startAt when the episode started
     * @param startNrs the starting score (`pain_episode.nrs`)
     * @param updates change marks as `(at, nrs, updateId)`
     */
    fun build(
        startAt: Long,
        startNrs: Int,
        updates: List<Triple<Long, Int, Long>>,
    ): List<Point> {
        val points = ArrayList<Point>(updates.size + 1)
        val start = startNrs.coerceIn(0, 10)
        points += Point(at = startAt, nrs = start, delta = 0, updateId = 0L)

        var prev = start
        updates
            // A mark before the start has no meaning (the write path never makes
            // one, but imports and migrations can), so it is dropped rather than
            // reordered into a negative duration.
            .filter { it.first >= startAt }
            .sortedWith(compareBy({ it.first }, { it.third }))
            .forEach { (at, nrs, id) ->
                val v = nrs.coerceIn(0, 10)
                points += Point(at = at, nrs = v, delta = v - prev, updateId = id)
                prev = v
            }
        return points
    }

    /** Current score = last point. Without marks that is the start score, so callers need no `if`. */
    fun currentNrs(points: List<Point>): Int = points.last().nrs

    /** Peak: the worst moment of an episode, a better severity answer than the starting score. */
    fun peakNrs(points: List<Point>): Int = points.maxOf { it.nrs }

    data class Point(
        val at: Long,
        val nrs: Int,
        /** Difference from the previous point; 0 on the first. Positive = worse, negative = easing. */
        val delta: Int,
        /** Mark id; 0 for the starting point (it does not live in `pain_update`). */
        val updateId: Long,
    ) {
        val isStart: Boolean get() = updateId == 0L
    }
}
