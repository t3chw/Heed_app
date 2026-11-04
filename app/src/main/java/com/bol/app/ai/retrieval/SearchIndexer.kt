package com.bol.app.ai.retrieval

import android.content.Context
import androidx.room.InvalidationTracker
import com.bol.app.ai.model.ModelManager
import com.bol.app.data.local.BolDatabase
import com.bol.app.data.repository.ItemRepository
import com.bol.app.data.repository.RecordingRepository
import com.bol.app.monitoring.BolLogger
import com.bol.app.monitoring.LogCategory
import com.bol.app.monitoring.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Keeps [index] in step with Room. Any write to `items` or `recordings` (new note, edit, move, delete, upgrade,
 * restore) marks the index dirty; [sync] then applies the difference: changed docs are rewritten (their vector is
 * dropped) and removed rows leave the index. Vectors are added by [embedNewest] right after a note is saved and by
 * [embedBacklog] from the [com.bol.app.ai.indexing.IndexingCoordinator].
 */
class SearchIndexer(
    context: Context,
    database: BolDatabase,
    private val itemRepository: ItemRepository,
    private val recordingRepository: RecordingRepository,
    private val modelManager: ModelManager,
) {
    val index = SearchIndex(File(context.filesDir, SearchIndex.FILE_NAME))

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncLock = Mutex()
    @Volatile private var dirty = true
    /** Room changed: one consumer syncs shortly after; a change during a sync queues exactly one more. */
    private val changes = Channel<Unit>(Channel.CONFLATED)

    private val _pending = MutableStateFlow(0)
    /** Docs that still need a vector. */
    val pending: StateFlow<Int> = _pending.asStateFlow()

    init {
        scope.launch {
            // Off the main thread: Room may run SQL here to set up its change tracking.
            database.invalidationTracker.addObserver(object : InvalidationTracker.Observer(arrayOf("items", "recordings")) {
                override fun onInvalidated(tables: Set<String>) {
                    dirty = true
                    changes.trySend(Unit)
                }
            })
            changes.trySend(Unit)
            // Keeps the pending count (Home banner, search) current.
            for (signal in changes) {
                delay(SYNC_DEBOUNCE_MS)
                runCatching { sync() }.onFailure { e ->
                    BolLogger.log(LogLevel.WARN, LogCategory.SEARCH, "search_index_sync_failed", "${e.javaClass.simpleName}: ${e.message?.take(150)}")
                }
            }
        }
    }

    /**
     * Bring the index up to date with Room. Cheap when nothing changed. [force] reads Room even before the change
     * notice arrives (Room sends it asynchronously after a write).
     */
    suspend fun sync(force: Boolean = false) = syncLock.withLock {
        if (!dirty && !force) return@withLock
        dirty = false
        try {
            withContext(Dispatchers.IO) {
                val (upserts, deletes) = SearchIndex.diff(roomDocs(), index.docs())
                index.apply(upserts, deletes)
                _pending.value = index.countMissingVectors(GemmaEmbedder.VERSION)
                if (upserts.isNotEmpty() || deletes.isNotEmpty()) {
                    BolLogger.log(LogLevel.DEBUG, LogCategory.SEARCH, "search_index_sync",
                        "Search index: ${upserts.size} docs added or changed, ${deletes.size} removed, ${_pending.value} waiting for a vector")
                }
            }
        } catch (e: Exception) {
            dirty = true
            throw e
        }
    }

    /** Vectors for the most recently added docs (a just-saved note), at most [max]. Returns how many were stored. */
    suspend fun embedNewest(max: Int = NEWEST_MAX): Int {
        sync(force = true)
        return embed(index.missingVectors(GemmaEmbedder.VERSION, max, newestFirst = true))
    }

    /**
     * Vectors for the waiting docs now, if they fit in one batch (an edit, a move, a note indexed while the model was
     * busy): the search's own model load does them. A bigger backlog is left to the indexing coordinator.
     */
    suspend fun embedIfFew(): Int {
        sync()
        return if (_pending.value in 1..BATCH) embed(index.missingVectors(GemmaEmbedder.VERSION, BATCH)) else 0
    }

    /** One batch of the backlog. Returns how many vectors were stored. */
    suspend fun embedBacklog(): Int {
        sync()
        return embed(index.missingVectors(GemmaEmbedder.VERSION, BATCH))
    }

    /** Empty the index and refill it from Room; vectors come back through the backlog. */
    suspend fun rebuild() {
        syncLock.withLock {
            withContext(Dispatchers.IO) { index.clear() }
            dirty = true
        }
        sync()
    }

    private suspend fun embed(docs: List<SearchIndex.Doc>): Int {
        if (docs.isEmpty()) return 0
        val t0 = System.currentTimeMillis()
        val vecs = modelManager.embed(docs.map { it.text }, isQuery = false)
        val stored = withContext(Dispatchers.IO) {
            index.putVectors(GemmaEmbedder.VERSION, docs.zip(vecs)).also {
                _pending.value = index.countMissingVectors(GemmaEmbedder.VERSION)
            }
        }
        BolLogger.log(LogLevel.DEBUG, LogCategory.SEARCH, "search_index_embed",
            "Embedded ${docs.size} docs in ${System.currentTimeMillis() - t0}ms; ${_pending.value} still waiting")
        return stored
    }

    /** Every task as "<text> (<category id>)" and every voice note's transcript, dated by the day it was recorded. */
    private suspend fun roomDocs(): List<SearchIndex.Doc> {
        val zone = ZoneId.systemDefault()
        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()
        val recordings = recordingRepository.getAllSnapshot()
        val recordedAt = recordings.associate { it.id to it.createdAt }
        val tasks = itemRepository.getAllSnapshot().map {
            SearchIndex.Doc(TASK_PREFIX + it.id, SearchIndex.KIND_TASK, it.id, day(it.sourceRecordingId?.let(recordedAt::get) ?: it.createdAt), "${it.text} (${it.categoryId})")
        }
        val notes = recordings.filter { !it.cancelled && it.rawText.isNotBlank() && it.rawText != "[audio]" }.map {
            SearchIndex.Doc(NOTE_PREFIX + it.id, SearchIndex.KIND_NOTE, it.id, day(it.createdAt), it.rawText)
        }
        return tasks + notes
    }

    companion object {
        const val TASK_PREFIX = "t:"
        const val NOTE_PREFIX = "n:"
        /** One embedding batch; a search embeds up to this many waiting docs itself ([embedIfFew]). */
        const val BATCH = 16
        private const val NEWEST_MAX = 48
        private const val SYNC_DEBOUNCE_MS = 1_000L
    }
}
