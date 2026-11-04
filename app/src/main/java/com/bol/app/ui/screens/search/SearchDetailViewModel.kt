package com.bol.app.ui.screens.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bol.app.ai.audio.AudioRecorder
import com.bol.app.ai.audio.WavConverter
import com.bol.app.ai.model.ModelManager
import com.bol.app.ai.stt.NonSpeechDetector
import com.bol.app.data.local.entity.CategoryEntity
import com.bol.app.data.local.entity.ItemEntity
import com.bol.app.data.local.entity.SearchQueryEntity
import com.bol.app.data.local.entity.SearchQueryStatus
import com.bol.app.data.preferences.PreferencesManager
import com.bol.app.data.repository.CategoryRepository
import com.bol.app.data.repository.ItemRepository
import com.bol.app.data.repository.SearchQueryRepository
import com.bol.app.service.BolProcessingService
import com.bol.app.service.RecordingSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

data class SearchDetailUiState(
    val loading: Boolean = true,
    val query: String = "",
    val status: String = SearchQueryStatus.PENDING,
    val answer: String? = null,
    val rankedItems: List<ItemEntity> = emptyList(),
    /** How many leading entries of [rankedItems] are LLM-picked (rerank
     *  survivors). Null when rerank was skipped — UI then shows the list
     *  flat with no "+ N more related" disclosure. */
    val llmPickedCount: Int? = null,
    val sources: List<SourceInfo> = emptyList(),
    val errorMessage: String? = null,
    val createdAt: Long = 0L,
    /** Current pipeline stage string (from SearchStage), null once finished. */
    val stage: String? = null,
    /** Optional sub-line for the current stage — e.g. "batch 2 of 4". */
    val stageDetail: String? = null,
    /** Partial synthesized answer while the model is still streaming. */
    val streamingAnswer: String? = null,
    /** True iff the query started with "[audio]" — drives checklist row. */
    val isAudioQuery: Boolean = false,
)

/**
 * Loads a single search row + resolves its ranked item IDs into full
 * [ItemEntity] rows. Observes status live so a freshly submitted query
 * goes PENDING → PROCESSING → COMPLETED without the user having to
 * refresh the screen.
 */
class SearchDetailViewModel(
    private val searchId: String,
    private val searchRepo: SearchQueryRepository,
    private val itemRepo: ItemRepository,
    private val appContext: Context,
    preferencesManager: PreferencesManager,
    categoryRepository: CategoryRepository,
    // Voice deps for the "Re-record" + "Dictate the edit" affordances.
    // Each flow gets its own AudioRecorder; only one can be active at a
    // time (enforced by the !=null guards on the recorder fields).
    private val recordingSignal: RecordingSignal,
    private val modelManager: ModelManager,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchDetailUiState())
    val state: StateFlow<SearchDetailUiState> = _state.asStateFlow()

    /** User's 12h/24h clock preference — drives timestamps on sources and items. */
    val timeFormat: Flow<String> = preferencesManager.timeFormat

    /** id → category map so RankedItemRow can show the user-facing
     *  category name (resolved from the category table) instead of the
     *  raw slug stored on each item. Built once and cached eagerly so
     *  the first frame already has the resolved names ready. */
    val categoriesById: StateFlow<Map<String, CategoryEntity>> =
        categoryRepository.getAllActive()
            .map { list -> list.associateBy { it.id } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Sorted list of active categories — used by the Move-category
     *  picker that's mounted from the expandable ranked-item row. */
    val allCategories: StateFlow<List<CategoryEntity>> =
        categoryRepository.getAllActive()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Last item the user deleted from the ranked list. Held while the
     *  Undo snackbar is on screen so undoDeleteItem() can re-insert with
     *  the same id (and therefore the same position in the ranked list
     *  after refresh). Cleared when the snackbar dismisses. */
    private val _lastDeletedItem = MutableStateFlow<ItemEntity?>(null)
    val lastDeletedItem: StateFlow<ItemEntity?> = _lastDeletedItem

    // One-shot nav stream — when the user taps Retry we insert a new search
    // row and emit its ID so the screen can swap to its detail view.
    private val _navigateTo = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val navigateTo: SharedFlow<String> = _navigateTo.asSharedFlow()

    init {
        viewModelScope.launch {
            searchRepo.observeById(searchId).collectLatest { row ->
                if (row == null) {
                    _state.value = _state.value.copy(loading = false, errorMessage = "Search not found")
                    return@collectLatest
                }
                _state.value = _state.value.copy(
                    loading = false,
                    query = row.query,
                    status = row.status,
                    answer = row.answer,
                    sources = parseSources(row.sourcesJson),
                    errorMessage = row.errorMessage,
                    createdAt = row.createdAt,
                    stage = row.stage,
                    stageDetail = row.stageDetail,
                    streamingAnswer = row.streamingAnswer,
                    isAudioQuery = row.query == "[audio]",
                )
                // Resolve ranked item IDs → item rows only after COMPLETED
                // (rows in earlier states don't have ranked IDs yet).
                if (row.status == SearchQueryStatus.COMPLETED) {
                    val items = resolveItems(row)
                    _state.value = _state.value.copy(
                        rankedItems = items,
                        llmPickedCount = row.llmPickedCount,
                    )
                }
            }
        }
    }

    private suspend fun resolveItems(row: SearchQueryEntity): List<ItemEntity> =
        withContext(Dispatchers.IO) {
            val ids = parseRankedIds(row.rankedItemIdsJson)
            ids.mapNotNull { id -> runCatching { itemRepo.getById(id) }.getOrNull() }
        }

    /**
     * Flip the row's cancel flag. SearchProcessingUseCase polls it at every
     * stage boundary (and a watcher coroutine during streaming) so Stop is
     * acknowledged within ~200 ms during synthesis, one batch during rerank.
     */
    fun stopSearch() {
        viewModelScope.launch {
            runCatching { searchRepo.requestCancel(searchId) }
        }
    }

    /**
     * Re-run a failed or cancelled search as a new row so the Detail screen
     * can navigate to it fresh. Guards: audio rows with no transcribed text
     * left (query still "[audio]") can't be retried because the WAV was
     * deleted after the first transcription attempt.
     */
    fun retrySearch() {
        viewModelScope.launch {
            val row = runCatching { searchRepo.getById(searchId) }.getOrNull() ?: return@launch
            val query = row.query
            if (query.isBlank() || query == "[audio]") return@launch
            val newRow = SearchQueryEntity(query = query)
            searchRepo.insert(newRow)
            BolProcessingService.startProcessing(appContext)
            _navigateTo.tryEmit(newRow.id)
        }
    }

    /** Re-run with a user-edited version of the query. The escape hatch
     *  for Whisper mis-transcriptions: user taps Stop on a search whose
     *  transcribed text was wrong, edits it via [EditQueryDialog], submits.
     *  Inserts a NEW search row (so the original cancelled row stays in
     *  history) and emits navigation so the detail screen swaps to it. */
    fun retryWithEditedQuery(newQuery: String) {
        val q = newQuery.trim()
        if (q.isBlank()) return
        viewModelScope.launch {
            val newRow = SearchQueryEntity(query = q)
            searchRepo.insert(newRow)
            BolProcessingService.startProcessing(appContext)
            _navigateTo.tryEmit(newRow.id)
        }
    }

    fun canRetry(status: String, query: String): Boolean {
        val stoppedState = status == SearchQueryStatus.FAILED || status == SearchQueryStatus.CANCELLED
        return stoppedState && query.isNotBlank() && query != "[audio]"
    }

    /**
     * Re-load the rankedItems list from the DB after any item-level
     * mutation (move, edit, reminder change, delete). The search row
     * itself doesn't change when items mutate, so we can't piggyback on
     * the searchRepo.observeById() flow that drives the rest of state —
     * we have to call this explicitly. Cheap: just N getById lookups
     * (N capped at 20 by SearchProcessingUseCase).
     */
    private suspend fun refreshRankedItems() {
        val row = searchRepo.getById(searchId) ?: return
        if (row.status != SearchQueryStatus.COMPLETED) return
        val items = resolveItems(row)
        _state.value = _state.value.copy(rankedItems = items)
    }

    /** Move an item to a different category, then refresh the ranked
     *  list so the new category name shows up in the row caption. */
    fun moveItem(itemId: String, newCategoryId: String) {
        viewModelScope.launch {
            itemRepo.moveItem(itemId, newCategoryId)
            refreshRankedItems()
        }
    }

    /** Set or replace an item's reminder time. Reuses the repository's
     *  setReminderTime which also schedules / re-schedules the alarm. */
    fun setReminder(itemId: String, newTime: Long) {
        viewModelScope.launch {
            itemRepo.setReminderTime(itemId, newTime)
            refreshRankedItems()
        }
    }

    /** Strip the reminder from an item (cancels the alarm too). */
    fun clearReminder(itemId: String) {
        viewModelScope.launch {
            itemRepo.clearReminder(itemId)
            refreshRankedItems()
        }
    }

    /** Tick / un-tick the item's done state from the inline checkbox in
     *  the ranked-results row. Persists to the items table so the change
     *  propagates to Home, CategoryDetail, History — anywhere the item
     *  appears. The ranked list is refreshed so the strikethrough +
     *  muted styling shows immediately. */
    fun toggleItemCompleted(itemId: String) {
        viewModelScope.launch {
            itemRepo.toggleCompleted(itemId)
            refreshRankedItems()
        }
    }

    /** Edit the item's text. updatedAt bumps automatically via the
     *  repository contract. */
    fun editItemText(itemId: String, newText: String) {
        viewModelScope.launch {
            val item = itemRepo.getById(itemId) ?: return@launch
            itemRepo.update(
                item.copy(text = newText, updatedAt = System.currentTimeMillis())
            )
            refreshRankedItems()
        }
    }

    /** Delete an item. Captures the full entity into [lastDeletedItem]
     *  first so the screen's Undo snackbar can restore it via
     *  [undoDeleteItem]. The ranked list is refreshed so the row
     *  disappears from the visible search results immediately. */
    fun deleteItem(itemId: String) {
        viewModelScope.launch {
            val item = itemRepo.getById(itemId) ?: return@launch
            _lastDeletedItem.value = item
            itemRepo.deleteById(itemId)
            refreshRankedItems()
        }
    }

    /** Re-insert the most recently deleted item. No-op if the snackbar
     *  was already dismissed. */
    fun undoDeleteItem() {
        val deleted = _lastDeletedItem.value ?: return
        viewModelScope.launch {
            itemRepo.restore(deleted)
            refreshRankedItems()
            _lastDeletedItem.value = null
        }
    }

    fun clearLastDeletedItem() {
        _lastDeletedItem.value = null
    }

    // ─────────────────────────────────────────────────────────────────
    //  Voice escape hatches: Re-record + Dictate-the-edit
    //
    //  Re-record (Feature 2): on a CANCELLED audio search, the user taps
    //  "Re-record" — capture new audio with AudioRecorder, save WAV to
    //  search_audio/, insert a fresh "[audio]" search row, kick the
    //  foreground service.
    //
    //  Dictate (Feature 3): inside the Edit-and-retry dialog, a mic
    //  button lets the user speak the corrected query. We capture audio
    //  with AudioRecorder, then transcribe inline via speech against
    //  the same loaded engine production already uses.
    //
    //  Each flow owns its own AudioRecorder. Only one can be active at a
    //  time, enforced by the != null guards.
    // ─────────────────────────────────────────────────────────────────

    @Volatile private var rerecRecorder: AudioRecorder? = null
    @Volatile private var dictateRecorder: AudioRecorder? = null

    private val _reRecordState = MutableStateFlow<ReRecordState>(ReRecordState.Idle)
    val reRecordState: StateFlow<ReRecordState> = _reRecordState.asStateFlow()

    private val _dictationState = MutableStateFlow<DictationState>(DictationState.Idle)
    val dictationState: StateFlow<DictationState> = _dictationState.asStateFlow()

    fun startReRecord() {
        if (rerecRecorder != null || dictateRecorder != null) return
        val rec = AudioRecorder()
        rerecRecorder = rec
        _reRecordState.value = ReRecordState.Listening
        recordingSignal.set(true)
        viewModelScope.launch {
            rec.state.collectLatest { state ->
                when (state) {
                    is AudioRecorder.State.Captured -> {
                        // Cancel-race guard: cancelReRecord nullifies the
                        // field and calls release(), but AudioRecorder is a
                        // soft tear-down (it flushes the trailing buffer
                        // and still emits Captured). Without this check,
                        // tapping Cancel would still save the WAV and
                        // insert a search row. If the field no longer
                        // points at our recorder, the user cancelled —
                        // skip the save.
                        if (rerecRecorder !== rec) return@collectLatest
                        finishReRec()
                        viewModelScope.launch {
                            withContext(NonCancellable + Dispatchers.IO) {
                                runCatching {
                                    val newRow = SearchQueryEntity(query = "[audio]")
                                    val audioDir = File(appContext.filesDir, "search_audio")
                                    audioDir.mkdirs()
                                    val wavFile = File(audioDir, "${newRow.id}.wav")
                                    WavConverter.pcmToWav(state.pcmBytes, wavFile)
                                    searchRepo.insert(newRow)
                                    _navigateTo.tryEmit(newRow.id)
                                    BolProcessingService.startProcessing(appContext)
                                }
                            }
                        }
                    }
                    is AudioRecorder.State.Error -> {
                        if (rerecRecorder !== rec) return@collectLatest
                        finishReRec()
                    }
                    else -> {}
                }
            }
        }
        rec.start()
    }

    fun stopReRecord() {
        rerecRecorder?.stop()
    }

    fun cancelReRecord() {
        finishReRec()
    }

    fun startDictation() {
        if (rerecRecorder != null || dictateRecorder != null) return
        val rec = AudioRecorder()
        dictateRecorder = rec
        _dictationState.value = DictationState.Listening()
        recordingSignal.set(true)
        viewModelScope.launch {
            rec.state.collectLatest { state ->
                when (state) {
                    is AudioRecorder.State.Captured -> {
                        // Cancel-race guard (see startReRecord for full
                        // explanation): if user cancelled, the field is
                        // null and we skip the inline transcribe.
                        if (dictateRecorder !== rec) return@collectLatest
                        // Capture-only: transcribe inline via speech so
                        // the dialog can populate its text field with the
                        // result. Same engine as production, no swap.
                        _dictationState.value = DictationState.Transcribing
                        val pcm = state.pcmBytes
                        finishDictate(resetState = false)
                        val text = transcribeForDictation(pcm)
                        _dictationState.value = DictationState.Done(text)
                    }
                    is AudioRecorder.State.Error -> {
                        if (dictateRecorder !== rec) return@collectLatest
                        _dictationState.value = DictationState.Idle
                        finishDictate()
                    }
                    else -> {}
                }
            }
        }
        rec.start()
    }

    fun stopDictation() {
        dictateRecorder?.stop()
    }

    fun cancelDictation() {
        _dictationState.value = DictationState.Idle
        finishDictate()
    }

    /** Called by the dialog after it has read the [DictationState.Done]
     *  text into its own text field, so subsequent dictations don't keep
     *  applying the same final value. */
    fun consumeDictationDone() {
        if (_dictationState.value is DictationState.Done) {
            _dictationState.value = DictationState.Idle
        }
    }

    /** Hard-cancel any in-flight voice (re-record OR dictation). Called by
     *  the screen's DisposableEffect on dispose so a tab switch with voice
     *  active doesn't leave the recorder running in the background and
     *  the shared [recordingSignal] stuck on. */
    fun cancelAllVoice() {
        _dictationState.value = DictationState.Idle
        finishReRec()
        finishDictate()
    }

    private fun finishReRec(resetState: Boolean = true) {
        rerecRecorder?.let {
            try { it.release() } catch (_: Exception) {}
            rerecRecorder = null
        }
        if (resetState) _reRecordState.value = ReRecordState.Idle
        recordingSignal.set(false)
    }

    private fun finishDictate(resetState: Boolean = true) {
        dictateRecorder?.let {
            try { it.release() } catch (_: Exception) {}
            dictateRecorder = null
        }
        if (resetState && _dictationState.value !is DictationState.Done) {
            _dictationState.value = DictationState.Idle
        }
        recordingSignal.set(false)
    }

    private suspend fun transcribeForDictation(pcmBytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            val tempWav = File(appContext.cacheDir, "dictation_${System.currentTimeMillis()}.wav")
            try {
                WavConverter.pcmToWav(pcmBytes, tempWav)
                val samples = WavConverter.wavToFloatArray(tempWav)
                val chunks = com.bol.app.ai.audio.AudioChunker.split(samples, sampleRate = 16_000)
                runCatching {
                    com.bol.app.ai.audio.TranscriptJoiner.join(modelManager.transcribe(chunks.map { it.samples }), chunks.map { it.overlapsPrevious })
                }.getOrDefault("")
            } catch (_: Exception) {
                ""
            } finally {
                runCatching { tempWav.delete() }
            }
        }

    override fun onCleared() {
        finishReRec()
        finishDictate()
        super.onCleared()
    }

    private fun parseRankedIds(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            Json.parseToJsonElement(json).jsonArray.map { it.jsonPrimitive.content }
        }.getOrDefault(emptyList())
    }

    private fun parseSources(json: String?): List<SourceInfo> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            Json.parseToJsonElement(json).jsonArray.map { el ->
                val obj = el.jsonObject
                SourceInfo(
                    id = obj["id"]?.jsonPrimitive?.content ?: "",
                    snippet = obj["snippet"]?.jsonPrimitive?.content ?: "",
                    createdAt = obj["createdAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                )
            }
        }.getOrDefault(emptyList())
    }
}

/** Re-record voice state — only Listening + Idle since the result lands
 *  via navigation to a new search detail (no in-place rendering needed). */
sealed class ReRecordState {
    data object Idle : ReRecordState()
    data object Listening : ReRecordState()
}

/** Dictation voice state for the EditQueryDialog. Live partials when the
 *  STT engine emits them (Google STT); else Transcribing while Whisper
 *  runs inline; then Done(text) for the dialog to consume. */
sealed class DictationState {
    data object Idle : DictationState()
    data class Listening(val partial: String = "") : DictationState()
    data object Transcribing : DictationState()
    data class Done(val text: String) : DictationState()
}
