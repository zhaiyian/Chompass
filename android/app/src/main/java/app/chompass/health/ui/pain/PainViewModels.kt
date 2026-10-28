package app.chompass.health.ui.pain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.chompass.health.HealthContainer
import app.chompass.health.data.db.entity.PainEpisodeEntity
import app.chompass.health.data.db.entity.PainJointEntity
import app.chompass.health.data.db.entity.PainUpdateEntity
import app.chompass.health.data.repo.DictRepository
import app.chompass.health.data.repo.PainInput
import app.chompass.health.data.repo.PainRepository
import app.chompass.health.data.repo.PainSaveResult
import app.chompass.health.domain.DictEntry
import app.chompass.health.domain.DictResolver
import app.chompass.health.domain.model.DictType
import app.chompass.health.domain.model.PainSide
import app.chompass.health.domain.pain.JointRow
import app.chompass.health.domain.pain.PainInsightCalculator
import app.chompass.health.domain.pain.PainNrsTimeline
import app.chompass.health.domain.pain.PainSiteCatalog
import app.chompass.health.domain.pain.RegionGroup
import app.chompass.health.model.PainEpisode
import app.chompass.health.model.PainNrsPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/* ============================================================
 * Entity -> display model
 * ============================================================ */

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** Room entity -> domain-facing episode. Room stays out of the domain, so this is the one seam. */
fun PainEpisodeEntity.toPainEpisode(updates: List<PainUpdateEntity> = emptyList()): PainEpisode {
    // The start point lives in `pain_episode.nrs`; the marks come from `pain_update`.
    // Building the timeline is a domain job (pure function, unit-testable) and the
    // only place a delta is ever computed.
    val points = PainNrsTimeline.build(
        startAt = startedAt,
        startNrs = nrs,
        updates = updates.map { Triple(it.at, it.nrs, it.id) },
    )
    return PainEpisode(
        id = id,
        startedAt = startedAt,
        endedAt = endedAt,
        siteCode = siteCode,
        side = side,
        startNrs = nrs,
        currentNrs = PainNrsTimeline.currentNrs(points),
        peakNrs = PainNrsTimeline.peakNrs(points),
        timeline = points.map { PainNrsPoint(it.at, it.nrs, it.delta, it.updateId) },
        natureCodes = natures.orEmpty(),
        triggerCodes = triggers.orEmpty(),
        affectWalk = affectWalk,
        affectSleep = affectSleep,
        note = note.orEmpty(),
    )
}

/** Room entity -> the tree-building shape. Names arrive already localized from the repository. */
private fun PainJointEntity.toDomainRow() = JointRow(
    jointId = jointId,
    regionId = regionId,
    regionName = regionName,
    regionOrder = regionOrder,
    digitId = digitId,
    digitName = digitName,
    digitOrder = digitOrder,
    displayName = displayName,
    anatomyName = anatomyName,
    sideRequired = sideRequired,
    sortOrder = sortOrder,
)

/** Why a save failed, without a message: the screen owns the language. */
sealed interface PainError {
    data object EndBeforeStart : PainError

    /** An end in the future is meaningless and usually a mis-tap. */
    data object EndInFuture : PainError
    data object SaveFailed : PainError
}

internal fun PainSaveResult.errorOrNull(): PainError? = when (this) {
    is PainSaveResult.Success -> null
    PainSaveResult.EndBeforeStart -> PainError.EndBeforeStart
    PainSaveResult.EndInFuture -> PainError.EndInFuture
    PainSaveResult.SaveFailed -> PainError.SaveFailed
}

/* ============================================================
 * B1 · list
 * ============================================================ */

/**
 * The undo receipt for one just-recorded pain mark.
 *
 * It exists because the mark is a form submit but mis-taps still happen — a
 * flare is exactly when hands are least reliable. Without it the only way back
 * is opening the detail screen and deleting the row.
 *
 * The label is not stored: `at` and `nrs` are, and the screen formats them, so
 * switching the app language does not leave a Chinese receipt on screen.
 */
data class PainUndo(
    val updateId: Long,
    val episodeId: Long,
    val at: Long,
    val nrs: Int,
)

/** One site in the "where does it hit" mini chart. */
data class SiteStat(val siteCode: String, val count: Int)

data class PainListUiState(
    val loading: Boolean = true,
    /**
     * More than one flare can be running at once (polyarticular gout), each with
     * its own "record end" — which one ends is decided by the row you tapped,
     * never by a global button.
     */
    val ongoing: List<PainEpisode> = emptyList(),
    val history: List<PainEpisode> = emptyList(),
    val siteStats: List<SiteStat> = emptyList(),
    val insight: PainInsightUi = PainInsightUi(),
    val undo: PainUndo? = null,
    val error: PainError? = null,
    /** Clock value the state was built with, so the UI never reads the system time. */
    val now: Long = 0L,
) {
    val isEmpty: Boolean get() = !loading && ongoing.isEmpty() && history.isEmpty()

    val siteStatsMax: Int get() = (siteStats.maxOfOrNull { it.count } ?: 1).coerceAtLeast(1)

    /** Longest-running flare: the status card must not report a short one when two are running. */
    val longestOngoing: PainEpisode? get() = ongoing.maxByOrNull { it.durationMs(now) }

    /** Peak across all running flares — the current worst, not the starting score. */
    val ongoingPeakNrs: Int? get() = ongoing.maxOfOrNull { it.peakNrs }
}

/** One row of the insights page: a code plus a count out of the window total. */
data class InsightRow(val code: String, val side: PainSide, val count: Int, val total: Int) {
    val share: Float get() = if (total == 0) 0f else count.toFloat() / total
}

data class PainInsightUi(
    val windowDays: Int = 90,
    val total: Int = 0,
    val ongoing: Int = 0,
    val avgDurationMinutes: Int? = null,
    val medianNrs: Int? = null,
    val maxNrs: Int? = null,
    val siteRows: List<InsightRow> = emptyList(),
    val triggerRows: List<InsightRow> = emptyList(),
    val natureRows: List<InsightRow> = emptyList(),
    val noTriggerCount: Int = 0,
    val walkAffected: Int = 0,
    val sleepAffected: Int = 0,
) {
    val hasAnything: Boolean get() = total > 0
    val sampleEnough: Boolean get() = total >= PainInsightCalculator.MIN_SAMPLE
    val missingForSample: Int get() = (PainInsightCalculator.MIN_SAMPLE - total).coerceAtLeast(0)
}

private fun PainInsightCalculator.Insight.toUi(): PainInsightUi = PainInsightUi(
    windowDays = windowDays,
    total = total,
    ongoing = ongoing,
    avgDurationMinutes = avgDurationMinutes,
    medianNrs = medianNrs,
    maxNrs = maxNrs,
    // Bars share one denominator (the window total) across sites, triggers and
    // natures: two bars showing the same 4/6 must be the same length, or the
    // first thing the user does is look for the arithmetic error.
    siteRows = sites.map { InsightRow(it.siteCode, it.side, it.count, total) },
    triggerRows = triggers.map { InsightRow(it.code, PainSide.NA, it.count, total) },
    natureRows = natures.map { InsightRow(it.code, PainSide.NA, it.count, total) },
    noTriggerCount = noTriggerCount,
    walkAffected = walkAffected,
    sleepAffected = sleepAffected,
)

class PainListViewModel(
    private val repo: PainRepository,
    dictFlow: Flow<DictResolver>,
    private val clock: () -> Long,
) : ViewModel() {

    private val undoFlow = MutableStateFlow<PainUndo?>(null)
    private val errorFlow = MutableStateFlow<PainError?>(null)
    private var undoJob: Job? = null

    val state: StateFlow<PainListUiState> = combine(
        repo.observeAll(),
        repo.observeAllUpdates(),
        dictFlow,
        combine(undoFlow, errorFlow) { undo, error -> undo to error },
    ) { entities, updates, dict, (undo, error) ->
        val now = clock()
        val byEpisode = updates.groupBy { it.episodeId }
        val all = entities.map { it.toPainEpisode(byEpisode[it.id].orEmpty()) }

        // Running flares sort **ascending** by start (history is descending): the
        // earliest one is on top, and it is also the one the status card calls
        // "longest running" — the first row and the summary agree.
        val ongoing = all.filter { it.ongoing }.sortedBy { it.startedAt }
        val history = all.filterNot { it.ongoing }.sortedByDescending { it.startedAt }

        val since = now - PainInsightCalculator.WINDOW_DAYS * DAY_MS
        val stats = entities
            .filter { it.startedAt >= since }
            .groupBy { it.siteCode }
            .map { (code, list) -> SiteStat(code, list.size) }
            .sortedWith(compareByDescending<SiteStat> { it.count }.thenBy { it.siteCode })
            .take(3)

        val insight = PainInsightCalculator.compute(
            // Computed from the assembled episodes, not re-read from entities:
            // the peak only exists after the timeline is built, and building it
            // in two places eventually yields two different peaks.
            samples = all.map { e ->
                PainInsightCalculator.Sample(
                    startedAt = e.startedAt,
                    endedAt = e.endedAt,
                    siteCode = e.siteCode,
                    side = e.side,
                    startNrs = e.startNrs,
                    peakNrs = e.peakNrs,
                    triggerCodes = e.triggerCodes,
                    natureCodes = e.natureCodes,
                    affectWalk = e.affectWalk,
                    affectSleep = e.affectSleep,
                )
            },
            since = since,
            now = now,
        ).toUi()

        PainListUiState(
            loading = false,
            ongoing = ongoing,
            history = history,
            siteStats = stats,
            insight = insight,
            undo = undo,
            error = error,
            now = now,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PainListUiState(),
    )

    /**
     * Record one pain change. [at] and [nrs] come from the sheet, never from here:
     * a flare can run for days, and "yesterday afternoon" and "a minute ago" are
     * different facts.
     */
    fun recordNrs(episodeId: Long, nrs: Int, at: Long) {
        viewModelScope.launch {
            val result = repo.addUpdate(episodeId, nrs, at)
            val failure = result.errorOrNull()
            if (failure != null) {
                errorFlow.value = failure
                return@launch
            }
            val id = (result as PainSaveResult.Success).id
            errorFlow.value = null
            undoJob?.cancel()
            undoFlow.value = PainUndo(
                updateId = id,
                episodeId = episodeId,
                at = at,
                nrs = nrs.coerceIn(0, 10),
            )
            // 5 s: a little longer than "tap, realise, react", short enough to leave.
            undoJob = viewModelScope.launch {
                delay(5_000)
                undoFlow.value = null
            }
        }
    }

    fun undoLastChange() {
        val undo = undoFlow.value ?: return
        undoJob?.cancel()
        undoFlow.value = null
        viewModelScope.launch { repo.removeUpdate(undo.updateId) }
    }

    fun dismissUndo() {
        undoJob?.cancel()
        undoFlow.value = null
    }

    fun dismissError() {
        errorFlow.value = null
    }

    /** After the detail screen deletes a mark, the receipt may still point at it. */
    fun clearUndoFor(updateId: Long) {
        if (undoFlow.value?.updateId == updateId) dismissUndo()
    }

    fun endEpisode(id: Long, endedAt: Long, onDone: (PainError?) -> Unit) {
        viewModelScope.launch {
            onDone(repo.end(id, endedAt).errorOrNull())
        }
    }

    class Factory(private val container: HealthContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PainListViewModel(
            repo = container.painRepository,
            dictFlow = container.dictResolver,
            clock = System::currentTimeMillis,
        ) as T
    }
}

/* ============================================================
 * B2 / B4 · create and edit
 * ============================================================ */

data class PainFormUiState(
    val editingId: Long? = null,
    val startedAt: Long = 0L,
    val endedAt: Long? = null,
    val side: PainSide = PainSide.RIGHT,
    val siteCode: String = "",
    val nrs: Int = 6,
    val natureCodes: Set<String> = emptySet(),
    val triggerCodes: Set<String> = emptySet(),
    val affectWalk: Boolean = false,
    val affectSleep: Boolean = false,
    val note: String = "",
    /** The cascade tree: region -> toe / finger -> joint. */
    val catalog: List<RegionGroup> = emptyList(),
    /** "Recently used" in the site sheet, at most 3. */
    val recentSites: List<RecentSiteUi> = emptyList(),
    val natures: List<DictEntry> = emptyList(),
    val triggers: List<DictEntry> = emptyList(),
    val saving: Boolean = false,
    val error: PainError? = null,
    /** Clock value for "running for X"; injected so the screen never reads system time. */
    val now: Long = 0L,
) {
    val isEditing: Boolean get() = editingId != null
    val canSave: Boolean get() = siteCode.isNotBlank() && !saving

    /** The joint itself, when [siteCode] is one of the 39 built-in ones. */
    val joint: app.chompass.health.domain.pain.JointOption?
        get() = PainSiteCatalog.find(catalog, siteCode)?.second
}

/**
 * "Recently used": one tap straight to a site, compensating for the cascade
 * turning the most common site from 2 taps into 4.
 */
data class RecentSiteUi(val siteCode: String, val side: PainSide)

class PainFormViewModel(
    private val repo: PainRepository,
    private val dictRepository: DictRepository,
    dictFlow: Flow<DictResolver>,
    private val clock: () -> Long,
    private val editingId: Long?,
) : ViewModel() {

    private val _state = MutableStateFlow(PainFormUiState(startedAt = clock(), now = clock()))
    val state: StateFlow<PainFormUiState> = _state.asStateFlow()

    private var dict: DictResolver = DictResolver()
    private var jointRows: List<JointRow> = emptyList()

    init {
        viewModelScope.launch {
            // Keep collecting instead of taking one snapshot: a custom item the
            // user just added must show up in the chips immediately, otherwise it
            // reads as "adding it failed".
            dictFlow.collect { resolver ->
                dict = resolver
                _state.update {
                    it.copy(
                        natures = resolver.enabledOf(DictType.PAIN_NATURE),
                        triggers = resolver.enabledOf(DictType.TRIGGER),
                    )
                }
            }
        }
        viewModelScope.launch {
            repo.observeJoints().collect { rows ->
                jointRows = rows.map { it.toDomainRow() }
                _state.update { it.copy(catalog = PainSiteCatalog.build(jointRows)) }
            }
        }
        if (editingId != null) {
            viewModelScope.launch {
                val entity = repo.byId(editingId)
                if (entity != null) {
                    _state.update {
                        it.copy(
                            editingId = entity.id,
                            startedAt = entity.startedAt,
                            endedAt = entity.endedAt,
                            side = entity.side,
                            siteCode = entity.siteCode,
                            nrs = entity.nrs,
                            natureCodes = entity.natures.orEmpty().toSet(),
                            triggerCodes = entity.triggers.orEmpty().toSet(),
                            affectWalk = entity.affectWalk,
                            affectSleep = entity.affectSleep,
                            note = entity.note.orEmpty(),
                        )
                    }
                }
            }
        } else {
            refreshRecentSites()
        }
    }

    /**
     * No site is preselected.
     *
     * The cascade is a deliberate "region, then joint" pick; choosing one for the
     * user means he can save without ever looking, and a month later every row has
     * the same default site. The cost is one extra tap on a field that is
     * mandatory anyway.
     */
    private fun refreshRecentSites() {
        viewModelScope.launch {
            val rows = runCatching { repo.recentSites(6) }.getOrDefault(emptyList())
            val seen = mutableSetOf<String>()
            val recent = rows.mapNotNull { row ->
                if (!seen.add(row.siteCode + "/" + row.side.name)) return@mapNotNull null
                RecentSiteUi(row.siteCode, row.side)
            }.take(3)
            _state.update { it.copy(recentSites = recent) }
        }
    }

    fun refreshRecentSitesNow() {
        if (editingId != null) return
        refreshRecentSites()
    }

    /** Cascade pick: joint id from the tree, side from the last step of the sheet. */
    fun pickSite(jointId: String, side: PainSide) {
        _state.update {
            it.copy(
                siteCode = jointId,
                // Spine / "can't say" have no side: storing the sheet's leftover
                // "right" would make the record say something the user never chose.
                side = if (siteOf(jointId)?.sideRequired == false) PainSide.NA else side,
                error = null,
            )
        }
    }

    fun setStartedAt(at: Long) {
        _state.update { it.copy(startedAt = at, error = null) }
    }

    fun setEndedAt(at: Long?) {
        _state.update { it.copy(endedAt = at, error = null) }
    }

    fun setNrs(nrs: Int) {
        _state.update { it.copy(nrs = nrs.coerceIn(0, 10)) }
    }

    /** Nature and trigger are capped at 3: beyond that it is a wall of chips nobody reads. */
    fun toggleNature(code: String) {
        _state.update {
            val current = it.natureCodes
            it.copy(
                natureCodes = when {
                    code in current -> current - code
                    current.size >= MAX_CODES -> current
                    else -> current + code
                },
            )
        }
    }

    fun toggleTrigger(code: String) {
        _state.update {
            val current = it.triggerCodes
            it.copy(
                triggerCodes = when {
                    code in current -> current - code
                    current.size >= MAX_CODES -> current
                    else -> current + code
                },
            )
        }
    }

    fun setAffectWalk(value: Boolean) {
        _state.update { it.copy(affectWalk = value) }
    }

    fun setAffectSleep(value: Boolean) {
        _state.update { it.copy(affectSleep = value) }
    }

    fun setNote(value: String) {
        _state.update { it.copy(note = value.take(200)) }
    }

    fun save(onSaved: () -> Unit) {
        val current = _state.value
        if (!current.canSave) return
        _state.update { it.copy(saving = true, error = null) }
        val input = PainInput(
            startedAt = current.startedAt,
            endedAt = current.endedAt,
            siteCode = current.siteCode,
            side = current.side,
            nrs = current.nrs,
            natureCodes = current.natureCodes.toList(),
            triggerCodes = current.triggerCodes.toList(),
            affectWalk = current.affectWalk,
            affectSleep = current.affectSleep,
            note = current.note,
        )
        viewModelScope.launch {
            val result = if (current.editingId != null) {
                repo.update(current.editingId, input)
            } else {
                repo.create(input)
            }
            val failure = result.errorOrNull()
            if (failure == null) {
                _state.update { it.copy(saving = false) }
                onSaved()
            } else {
                _state.update { it.copy(saving = false, error = failure) }
            }
        }
    }

    /**
     * A site the user typed in ("outer left ankle").
     *
     * It lands in `dict_item` (`builtin = 0`), so it appears in **every** later
     * record's options and is exported and counted like any other site. A
     * separate user table would mean the exported CSV cannot resolve the word.
     */
    fun addCustomSite(label: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val code = dictRepository.addUserDefined(DictType.SITE, label, clock())
            if (code != null) {
                _state.update { it.copy(siteCode = code, error = null) }
            }
            onDone(code)
        }
    }

    fun addCustomNature(label: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val code = dictRepository.addUserDefined(DictType.PAIN_NATURE, label, clock())
            if (code == null) {
                onDone(false)
            } else {
                _state.update {
                    it.copy(natureCodes = (it.natureCodes + code).toList().takeLast(MAX_CODES).toSet())
                }
                onDone(true)
            }
        }
    }

    fun addCustomTrigger(label: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val code = dictRepository.addUserDefined(DictType.TRIGGER, label, clock())
            if (code == null) {
                onDone(false)
            } else {
                _state.update {
                    it.copy(triggerCodes = (it.triggerCodes + code).toList().takeLast(MAX_CODES).toSet())
                }
                onDone(true)
            }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = _state.value.editingId ?: return
        viewModelScope.launch {
            repo.softDelete(id)
            onDeleted()
        }
    }

    private fun siteOf(jointId: String) = PainSiteCatalog.find(_state.value.catalog, jointId)?.second

    private companion object {
        const val MAX_CODES = 3
    }

    class Factory(
        private val container: HealthContainer,
        private val editingId: Long?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PainFormViewModel(
            repo = container.painRepository,
            dictRepository = container.dictRepository,
            dictFlow = container.dictResolver,
            clock = System::currentTimeMillis,
            editingId = editingId,
        ) as T
    }
}

/* ============================================================
 * B3 · detail (read only)
 * ============================================================ */

data class PainDetailUiState(
    val episode: PainEpisode? = null,
    val now: Long = 0L,
    /** Distinguishes "not read yet" from "gone": only the second pops the screen. */
    val loading: Boolean = true,
)

class PainDetailViewModel(
    private val repo: PainRepository,
    dictFlow: Flow<DictResolver>,
    private val clock: () -> Long,
    private val episodeId: Long,
) : ViewModel() {

    /**
     * `dictFlow` is subscribed but intentionally unused for the episode itself:
     * the label is resolved in the screen from the same resolver. Collecting it
     * here keeps the state re-emitting when a custom dictionary item is added or
     * renamed, which is when a site label changes under an open screen.
     */
    val state: StateFlow<PainDetailUiState> = combine(
        repo.observeById(episodeId),
        repo.observeUpdates(episodeId),
        dictFlow,
    ) { entity, updates, _ ->
        PainDetailUiState(
            episode = entity?.toPainEpisode(updates),
            now = clock(),
            loading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PainDetailUiState(),
    )

    /**
     * Delete one mark. This entry point stays on the detail screen because the
     * list's undo lives 5 seconds only — spotting a wrong mark the next day would
     * otherwise leave it on the timeline forever.
     */
    fun removeUpdate(updateId: Long) {
        viewModelScope.launch { repo.removeUpdate(updateId) }
    }

    /** Close the flare from the detail screen, so a running one can be ended without going back. */
    fun endEpisode(endedAt: Long, onDone: (PainError?) -> Unit) {
        viewModelScope.launch {
            onDone(repo.end(episodeId, endedAt).errorOrNull())
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repo.softDelete(episodeId)
            onDeleted()
        }
    }

    class Factory(
        private val container: HealthContainer,
        private val episodeId: Long,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PainDetailViewModel(
            repo = container.painRepository,
            dictFlow = container.dictResolver,
            clock = System::currentTimeMillis,
            episodeId = episodeId,
        ) as T
    }
}

