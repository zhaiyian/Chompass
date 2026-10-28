package app.chompass.health.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.chompass.health.HealthContainer
import app.chompass.health.domain.uric.LevelResolver
import app.chompass.health.export.HealthBackupCounts
import app.chompass.health.export.HealthCsvImporter
import app.chompass.health.export.HealthImportManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why a target value was refused; the screen owns the wording. */
enum class UricTargetError { OUT_OF_RANGE, SAVE_FAILED }

/**
 * One-shot result the screen shows in a dialog. Codes and numbers only: the
 * string resources are resolved in the screen, like every other health screen.
 */
sealed interface HealthBackupNotice {
    data class Imported(
        val uric: Int,
        val pain: Int,
        val painUpdates: Int,
        val dose: Int,
        val duplicates: Int,
        val skipped: Int,
        val issues: List<HealthCsvImporter.Issue>,
    ) : HealthBackupNotice

    data class ImportedNothing(val skipped: Int) : HealthBackupNotice
    data object ExportEmpty : HealthBackupNotice
    data object ExportFailed : HealthBackupNotice
    data object ImportUnreadable : HealthBackupNotice
    data object ImportNoTables : HealthBackupNotice
    data object ImportFailed : HealthBackupNotice
}

data class HealthDataUiState(
    val loading: Boolean = true,
    val uricTarget: Int = LevelResolver.DEFAULT_TARGET,
    val counts: HealthBackupCounts = HealthBackupCounts(),
    val lastExportAt: Long? = null,
    /** Null when nothing was ever exported. */
    val daysSinceExport: Int? = null,
    val exporting: Boolean = false,
    val importing: Boolean = false,
    val targetError: UricTargetError? = null,
    val notice: HealthBackupNotice? = null,
) {
    /**
     * Nudge only when there is something to lose: an empty diary that has never
     * been exported is not a backup emergency, it is a new install.
     */
    val exportOverdue: Boolean
        get() = counts.records > 0 && (daysSinceExport == null || daysSinceExport >= OVERDUE_DAYS)

    private companion object {
        /** Roughly one follow-up appointment, and one phone-replacement cycle. */
        const val OVERDUE_DAYS = 30
    }
}

/**
 * Settings and backup for the health diary.
 *
 * Two things live here because they share one page and one audience: the uric
 * acid target (the only setting the diary has) and the CSV archive, which is the
 * only escape hatch if the phone is lost.
 */
class HealthDataViewModel(private val container: HealthContainer) : ViewModel() {

    private val _state = MutableStateFlow(HealthDataUiState())
    val state: StateFlow<HealthDataUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.uricRepository.observeTarget().collect { target ->
                _state.update { it.copy(uricTarget = target) }
            }
        }
        viewModelScope.launch {
            container.lastExportAt.collect { at ->
                val days = at?.let {
                    ((container.clock() - it) / DAY_MS).toInt().coerceAtLeast(0)
                }
                _state.update { it.copy(lastExportAt = at, daysSinceExport = days, loading = false) }
            }
        }
        refreshCounts()
    }

    private fun refreshCounts() {
        viewModelScope.launch {
            val counts = runCatching { container.backupExport.counts() }
                .getOrDefault(HealthBackupCounts())
            _state.update { it.copy(counts = counts, loading = false) }
        }
    }

    /**
     * The repository validates as well; this is the second check that lets the
     * screen explain *why* nothing was saved instead of silently keeping the old
     * number in the field.
     */
    fun setTarget(value: Int) {
        if (!LevelResolver.isValidTarget(value)) {
            _state.update { it.copy(targetError = UricTargetError.OUT_OF_RANGE) }
            return
        }
        viewModelScope.launch {
            val ok = runCatching { container.uricRepository.setTarget(value) }
            _state.update {
                it.copy(
                    targetError = if (ok.getOrDefault(false)) {
                        null
                    } else {
                        UricTargetError.SAVE_FAILED
                    },
                )
            }
        }
    }

    fun clearTargetError() {
        _state.update { it.copy(targetError = null) }
    }

    /**
     * Build the archive and hand the chooser back to the screen.
     *
     * The share intent cannot be launched from here: starting an activity needs
     * the screen's context, and doing it in a ViewModel leaks it.
     */
    fun export(onShare: (Intent) -> Unit) {
        if (_state.value.exporting) return
        _state.update { it.copy(exporting = true, notice = null) }
        viewModelScope.launch {
            runCatching { container.backupExport.export() }
                .onSuccess { artifact ->
                    _state.update { it.copy(exporting = false, counts = artifact.counts) }
                    onShare(container.backupExport.shareIntent(artifact))
                    // Share the file, then say the file is empty — the user would
                    // otherwise assume the export failed.
                    if (artifact.counts.records == 0) {
                        _state.update { it.copy(notice = HealthBackupNotice.ExportEmpty) }
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(exporting = false, notice = HealthBackupNotice.ExportFailed)
                    }
                }
        }
    }

    /** Merge what the picker returned. Nothing on this device is ever replaced. */
    fun import(uri: Uri) {
        if (_state.value.importing) return
        _state.update { it.copy(importing = true, notice = null) }
        viewModelScope.launch {
            val result = runCatching { container.backupImport.import(uri) }.getOrNull()
            _state.update { it.copy(importing = false) }

            when {
                result == null -> setNotice(HealthBackupNotice.ImportFailed)
                result.failure == HealthImportManager.Failure.UNREADABLE ->
                    setNotice(HealthBackupNotice.ImportUnreadable)

                result.failure == HealthImportManager.Failure.NO_TABLES ->
                    setNotice(HealthBackupNotice.ImportNoTables)

                result.imported == 0 && result.duplicates == 0 ->
                    setNotice(HealthBackupNotice.ImportedNothing(result.skipped))

                else -> {
                    setNotice(
                        HealthBackupNotice.Imported(
                            uric = result.uric,
                            pain = result.pain,
                            painUpdates = result.painUpdates,
                            dose = result.dose,
                            duplicates = result.duplicates,
                            skipped = result.skipped,
                            issues = result.issues,
                        )
                    )
                    // Imported logs can mark today's instances taken; the alarm for
                    // a dose already recorded must not stay armed.
                    container.reminders.syncAsync()
                    refreshCounts()
                }
            }
        }
    }

    private fun setNotice(notice: HealthBackupNotice) {
        _state.update { it.copy(notice = notice) }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    class Factory(private val container: HealthContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HealthDataViewModel(container) as T
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
