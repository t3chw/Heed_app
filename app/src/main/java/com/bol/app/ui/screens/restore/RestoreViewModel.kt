package com.bol.app.ui.screens.restore

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bol.app.ai.indexing.IndexingCoordinator
import com.bol.app.data.backup.RestoreMode
import com.bol.app.data.backup.RestoreProgress
import com.bol.app.domain.usecase.ImportDataUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Orchestrates the Restore screen: takes a SAF URI, runs preview,
 * then runs the restore Flow when the user confirms. The ViewModel
 * captures the ContentResolver up-front from the application
 * context so SAF reads survive config changes.
 *
 * Failure handling is centralized here — the screen just renders
 * whatever phase is in the StateFlow. SAF stream open failures, codec
 * errors, validator rejections, and Room rollbacks all surface as
 * [RestorePhase.PreviewError] or [RestorePhase.Failed].
 */
class RestoreViewModel(
    context: Context,
    private val importDataUseCase: ImportDataUseCase,
    private val indexingCoordinator: IndexingCoordinator,
) : ViewModel() {

    private val contentResolver: ContentResolver = context.contentResolver

    private val _state = MutableStateFlow(RestoreUiState())
    val state: StateFlow<RestoreUiState> = _state.asStateFlow()

    /**
     * Called from the SAF picker callback. Null URI means the user
     * cancelled — leave state untouched. Otherwise transition to
     * LoadingPreview and read the manifest off the IO dispatcher.
     */
    fun onFilePicked(uri: Uri?) {
        if (uri == null) return
        _state.update {
            it.copy(
                phase = RestorePhase.LoadingPreview,
                sourceUri = uri,
                replaceConfirmText = "", // reset between picks
            )
        }
        viewModelScope.launch {
            val phase = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val preview = importDataUseCase.previewBackup(input)
                    if (preview.canRestore) RestorePhase.PreviewReady(preview)
                    else RestorePhase.PreviewIncompatible(preview)
                } ?: RestorePhase.PreviewError("Couldn't open the selected file.")
            } catch (e: Throwable) {
                RestorePhase.PreviewError(e.message ?: "Couldn't read the backup file.")
            }
            _state.update { it.copy(phase = phase) }
        }
    }

    fun setMode(mode: RestoreMode) {
        _state.update {
            // Always clear the typed-confirmation text when the user
            // flips modes — otherwise an old "REPLACE" from a previous
            // session could ride along into a re-enabled REPLACE.
            it.copy(mode = mode, replaceConfirmText = "")
        }
    }

    fun setReplaceConfirmText(text: String) {
        _state.update { it.copy(replaceConfirmText = text) }
    }

    /**
     * Run the restore using the currently-selected mode. No-op if the
     * screen isn't in a state that can restore (saves the screen from
     * having to re-check before calling).
     */
    fun runRestore() {
        val current = _state.value
        if (!current.canRunRestore) return
        val uri = current.sourceUri ?: return
        val mode = current.mode

        _state.update {
            it.copy(phase = RestorePhase.Restoring(RestoreProgress.Validating))
        }

        viewModelScope.launch {
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    importDataUseCase.restore(input, mode).collect { progress ->
                        when (progress) {
                            is RestoreProgress.Done -> {
                                _state.update { it.copy(phase = RestorePhase.Done(progress.report)) }
                                // Kick off the deferred-indexing pass so
                                // imported items get embeddings (the backup
                                // doesn't carry them by design — they're
                                // model-version-locked and regenerable).
                                // start() is idempotent and self-cancels
                                // when the app moves to background, so a
                                // double-tap or quick navigate-away is
                                // safe.
                                indexingCoordinator.start()
                            }
                            is RestoreProgress.Failed ->
                                _state.update { it.copy(phase = RestorePhase.Failed(progress.message)) }
                            else ->
                                _state.update { it.copy(phase = RestorePhase.Restoring(progress)) }
                        }
                    }
                } ?: _state.update {
                    it.copy(phase = RestorePhase.Failed("Couldn't open the backup file."))
                }
            } catch (e: Throwable) {
                _state.update {
                    it.copy(phase = RestorePhase.Failed(e.message ?: "Restore failed."))
                }
            }
        }
    }

    /** Used by "Try again" / "Pick another file" — drops back to the
     *  initial phase so the user can pick a fresh URI. */
    fun reset() {
        _state.value = RestoreUiState()
    }
}
