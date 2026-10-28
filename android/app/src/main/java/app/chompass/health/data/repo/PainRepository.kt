package app.chompass.health.data.repo

import app.chompass.health.data.db.JointSeed
import app.chompass.health.data.db.dao.PainDao
import app.chompass.health.data.db.dao.RecentSiteRow
import app.chompass.health.data.db.entity.PainEpisodeEntity
import app.chompass.health.data.db.entity.PainJointEntity
import app.chompass.health.data.db.entity.PainUpdateEntity
import app.chompass.health.domain.model.PainSide
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Input for creating / editing one episode. */
data class PainInput(
    val startedAt: Long,
    val endedAt: Long?,
    val siteCode: String,
    val side: PainSide,
    val nrs: Int,
    val natureCodes: List<String>,
    val triggerCodes: List<String>,
    val affectWalk: Boolean,
    val affectSleep: Boolean,
    val note: String?,
)

/** Business-rule failure; the UI maps each one to its own message. */
sealed class PainSaveResult {
    data class Success(val id: Long) : PainSaveResult()
    data object EndBeforeStart : PainSaveResult()

    /** An end in the future is meaningless and usually a mis-tap. */
    data object EndInFuture : PainSaveResult()
    data object SaveFailed : PainSaveResult()
}

interface PainRepository {
    fun observeAll(): Flow<List<PainEpisodeEntity>>
    fun observeById(id: Long): Flow<PainEpisodeEntity?>
    suspend fun byId(id: Long): PainEpisodeEntity?

    /**
     * Create.
     *
     * **No longer limited to one ongoing episode** (2026-10-23): polyarticular
     * gout can hit 2-3 joints at once, and collapsing that into one row forces
     * the user to pick a single site — the other flare is then lost forever.
     *
     * The cost of dropping that rule is that one multi-joint flare counts as
     * several episodes; the statistics screen says so (counts are per **site
     * record**, not per flare event).
     */
    suspend fun create(input: PainInput): PainSaveResult

    /** Edit. Returns [PainSaveResult.SaveFailed] when the id no longer exists. */
    suspend fun update(id: Long, input: PainInput): PainSaveResult

    /** Record the end. It must be later than the start. */
    suspend fun end(id: Long, endedAt: Long): PainSaveResult

    /* ---------------- pain-value marks ---------------- */

    /** All live marks, ascending. The list screen uses them to derive current and peak scores. */
    fun observeAllUpdates(): Flow<List<PainUpdateEntity>>

    /** One episode's marks, ascending, without the starting point (that lives in `pain_episode.nrs`). */
    fun observeUpdates(episodeId: Long): Flow<List<PainUpdateEntity>>

    /**
     * Record one change in pain level.
     *
     * [at] is picked by the user (the "worse / better" buttons open a sheet
     * prefilled with now). **No "score only, timestamp is now" overload**: over a
     * multi-day flare "yesterday afternoon" and "a minute ago" are different
     * facts, and stamping the system clock throws half of that away.
     *
     * The trend is still not persisted; `PainNrsTimeline` derives it from the
     * deltas between adjacent points.
     */
    suspend fun addUpdate(episodeId: Long, nrs: Int, at: Long): PainSaveResult

    /** Undo one mark (soft delete). */
    suspend fun removeUpdate(id: Long)

    suspend fun softDelete(id: Long)

    /* ---------------- joint catalogue ---------------- */

    /** Built-in joint table (39 rows); read by the cascade picker and by label lookups. */
    fun observeJoints(): Flow<List<PainJointEntity>>

    /**
     * "Recently used sites": distinct (site, side) pairs, most recent first.
     *
     * The cascade turns the most common site from 2 taps into 4, and this row is
     * the only thing that gives those taps back. The cap is the caller's choice
     * (the screen shows 3); the repository does not guess.
     */
    suspend fun recentSites(limit: Int): List<RecentSiteRow>
}

/**
 * Room-backed implementation. `internal` because the joint names come from
 * [JointSeed], which is an implementation detail of this module.
 */
internal class PainRepositoryImpl(
    private val dao: PainDao,
    private val clock: () -> Long,
    /**
     * Current-language joint names, keyed by joint id. Absent = fall back to the
     * names stored in `pain_joint` (user-added rows, or a language with no
     * translation yet).
     */
    private val jointNames: Map<String, JointSeed.NameText> = emptyMap(),
) : PainRepository {

    override fun observeAll(): Flow<List<PainEpisodeEntity>> = dao.observeAll()

    override fun observeById(id: Long): Flow<PainEpisodeEntity?> = dao.observeById(id)

    override suspend fun byId(id: Long): PainEpisodeEntity? = dao.byId(id)

    override suspend fun create(input: PainInput): PainSaveResult {
        if (input.endedAt != null && input.endedAt <= input.startedAt) {
            return PainSaveResult.EndBeforeStart
        }
        if (input.endedAt != null && input.endedAt > clock()) {
            return PainSaveResult.EndInFuture
        }
        val now = clock()
        return runCatching {
            val id = dao.insert(
                PainEpisodeEntity(
                    startedAt = input.startedAt,
                    endedAt = input.endedAt,
                    siteCode = input.siteCode,
                    side = input.side,
                    nrs = input.nrs.coerceIn(0, 10),
                    natures = input.natureCodes.ifEmpty { null },
                    triggers = input.triggerCodes.ifEmpty { null },
                    affectWalk = input.affectWalk,
                    affectSleep = input.affectSleep,
                    note = input.note?.takeIf { it.isNotBlank() },
                    createdAt = now,
                    updatedAt = now,
                )
            )
            PainSaveResult.Success(id)
        }.getOrElse { PainSaveResult.SaveFailed }
    }

    override suspend fun update(id: Long, input: PainInput): PainSaveResult {
        val existing = dao.byId(id) ?: return PainSaveResult.SaveFailed
        if (input.endedAt != null && input.endedAt <= input.startedAt) {
            return PainSaveResult.EndBeforeStart
        }
        if (input.endedAt != null && input.endedAt > clock()) {
            return PainSaveResult.EndInFuture
        }
        return runCatching {
            dao.update(
                existing.copy(
                    startedAt = input.startedAt,
                    endedAt = input.endedAt,
                    siteCode = input.siteCode,
                    side = input.side,
                    nrs = input.nrs.coerceIn(0, 10),
                    natures = input.natureCodes.ifEmpty { null },
                    triggers = input.triggerCodes.ifEmpty { null },
                    affectWalk = input.affectWalk,
                    affectSleep = input.affectSleep,
                    note = input.note?.takeIf { it.isNotBlank() },
                    updatedAt = clock(),
                )
            )
            PainSaveResult.Success(id)
        }.getOrElse { PainSaveResult.SaveFailed }
    }

    override suspend fun end(id: Long, endedAt: Long): PainSaveResult {
        val existing = dao.byId(id) ?: return PainSaveResult.SaveFailed
        if (endedAt <= existing.startedAt) return PainSaveResult.EndBeforeStart
        if (endedAt > clock()) return PainSaveResult.EndInFuture
        return runCatching {
            dao.end(id, endedAt, clock())
            PainSaveResult.Success(id)
        }.getOrElse { PainSaveResult.SaveFailed }
    }

    override suspend fun softDelete(id: Long) = dao.softDeleteWithUpdates(id, clock())

    override fun observeAllUpdates(): Flow<List<PainUpdateEntity>> = dao.observeAllUpdates()

    override fun observeUpdates(episodeId: Long): Flow<List<PainUpdateEntity>> =
        dao.observeUpdates(episodeId)

    override suspend fun addUpdate(episodeId: Long, nrs: Int, at: Long): PainSaveResult {
        // The episode must still exist: a soft-deleted one must not grow marks.
        val episode = dao.byId(episodeId) ?: return PainSaveResult.SaveFailed
        // The mark has to fall inside the episode: earlier than the start, or
        // later than the end (or than now) is not part of the process.
        if (at < episode.startedAt) return PainSaveResult.EndBeforeStart
        val upper = episode.endedAt ?: clock()
        if (at > upper) return PainSaveResult.EndInFuture
        val value = nrs.coerceIn(0, 10)
        return runCatching {
            // Same-minute marks overwrite each other: two parallel "08:15" rows
            // show no order, and it is almost always the user changing their mind.
            val existing = dao.updateAtMinute(episodeId, at / 60_000L)
            val id = if (existing != null) {
                dao.reviseUpdate(existing.id, value, at)
                existing.id
            } else {
                dao.insertUpdate(
                    PainUpdateEntity(
                        episodeId = episodeId,
                        at = at,
                        nrs = value,
                        createdAt = clock(),
                    )
                )
            }
            // Success carries the **mark** id, not the episode id: callers need it to undo.
            PainSaveResult.Success(id)
        }.getOrElse { PainSaveResult.SaveFailed }
    }

    override suspend fun removeUpdate(id: Long) {
        dao.softDeleteUpdate(id, clock())
    }

    override fun observeJoints(): Flow<List<PainJointEntity>> =
        dao.observeJoints().map { rows -> rows.map(::localized) }

    override suspend fun recentSites(limit: Int): List<RecentSiteRow> =
        dao.recentSites(limit.coerceIn(1, 8))

    /** Swap the four name columns for the current language; keep everything else. */
    private fun localized(row: PainJointEntity): PainJointEntity {
        val name = jointNames[row.jointId] ?: return row
        return row.copy(
            regionName = name.region,
            // A region without a digit level must stay null: an empty string
            // would make the cascade render a "which toe?" step with no chips.
            digitName = name.digit ?: row.digitName,
            displayName = name.display,
            anatomyName = name.anatomy,
        )
    }
}
