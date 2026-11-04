package com.bol.app.ai.model

import android.content.Context
import com.bol.app.ai.parser.AnswerGuard
import com.bol.app.ai.retrieval.GemmaEmbedder
import com.bol.app.ai.speech.GraniteSpeech
import com.bol.app.monitoring.BolLogger
import com.bol.app.monitoring.LogCategory
import com.bol.app.monitoring.LogLevel
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.ResponseCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Session
import com.google.ai.edge.litertlm.SessionConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the bundled on-device models: Granite Speech (voice to text), the fine-tuned Qwen3-0.6B (text to tasks) and
 * EmbeddingGemma-300M (search vectors).
 *
 * The model files ship in install-time AI packs (inside the APK in debug builds; both read as the app's assets) and
 * are copied once to filesDir, because the engines need real file paths.
 * One owner at a time ([lock]), and one model in memory at a time: the pipeline never needs two together (a note is
 * transcribed, then turned into tasks, then indexed), so loading one closes the others. The loaded one is released
 * after [IDLE_RELEASE_MS] without use.
 */
@OptIn(ExperimentalApi::class)
class ModelManager(private val context: Context) {
    private val _state = MutableStateFlow<ModelState>(ModelState.NotDownloaded)
    val state: StateFlow<ModelState> = _state

    private val lock = Mutex()
    private val copyLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tasksEngine: Engine? = null
    private var speech: GraniteSpeech? = null
    private var embedder: GemmaEmbedder? = null
    /** Completes when the last task-model generation has really ended (native callback), even after a cancel. */
    private var tasksGeneration: CompletableDeferred<Unit>? = null
    private var releaseJob: Job? = null

    private val dir = File(context.filesDir, "models").apply { mkdirs() }

    fun isModelOnDisk(): Boolean = _state.value is ModelState.Downloaded

    /** Copies any missing model out of the APK. Safe to call repeatedly; only the first call does work. */
    suspend fun checkModelStatus() = copyLock.withLock {
        if (_state.value is ModelState.Downloaded) return@withLock
        withContext(Dispatchers.IO) {
            try {
                val t0 = System.currentTimeMillis()
                FILES.forEach { copyAsset(it) }
                deleteOldModelFiles()
                _state.value = ModelState.Downloaded
                BolLogger.log(LogLevel.INFO, LogCategory.MODEL, "models_ready", "Bundled models ready in ${System.currentTimeMillis() - t0}ms")
            } catch (e: Exception) {
                _state.value = ModelState.Error("Could not unpack the models: ${e.message?.take(120)}")
                BolLogger.log(LogLevel.ERROR, LogCategory.MODEL, "models_copy_failed", "${e.javaClass.simpleName}: ${e.message?.take(200)}")
            }
        }
    }

    private fun copyAsset(name: String) {
        val out = File(dir, name)
        val assetPath = "models/$name"
        val size = context.assets.openFd(assetPath).use { it.length }
        if (out.exists() && out.length() == size) return
        out.parentFile?.mkdirs()
        val tmp = File(dir, "$name.part")
        context.assets.open(assetPath).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
        check(tmp.renameTo(out)) { "could not move $name into place" }
    }

    /** Gemma's 2.4 GB file is left behind when an older install updates to this build. */
    private fun deleteOldModelFiles() {
        dir.listFiles { f -> f.name.startsWith("gemma-") || (f.name.startsWith("q06_") && !f.name.startsWith(TASKS_FILE)) }?.forEach { it.delete() }
    }

    /** One clip per entry, in order. */
    suspend fun transcribe(clips: List<FloatArray>): List<String> = useModels {
        closeTasks()
        closeEmbedder()
        val model = speech ?: timedLoad("Granite Speech") { GraniteSpeech(File(dir, SPEECH_FILE), File(dir, VOCAB_FILE), dir) }.also { speech = it }
        clips.map { model.transcribe(it) }
    }

    /** Raw model text for a prompt. Greedy, so the phone gives the same kind of answer as evaluation. */
    suspend fun generateTasks(prompt: String): String = useModels {
        closeSpeech()
        closeEmbedder()
        val engine = tasksEngine ?: timedLoad("Qwen task model") {
            Engine(EngineConfig(modelPath = File(dir, TASKS_FILE).absolutePath, backend = Backend.CPU(), maxNumTokens = MAX_TOKENS)).also { it.initialize() }
        }.also { tasksEngine = it }
        val session = engine.createSession(SessionConfig(samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0)))
        try {
            streamAnswer(session, prompt)
        } finally {
            session.close()
        }
    }

    /**
     * EmbeddingGemma search vectors (768-d, unit length), one per text, in order. Runs in batches of [EMBED_BATCH];
     * a cancel takes effect between batches.
     */
    suspend fun embed(texts: List<String>, isQuery: Boolean): List<FloatArray> = useModels {
        closeTasks()
        closeSpeech()
        try {
            val model = embedder ?: run {
                val t0 = System.currentTimeMillis()
                copyLock.withLock { withContext(Dispatchers.IO) { EMBED_FILES.forEach { copyAsset(it) } } }
                val t1 = System.currentTimeMillis()
                GemmaEmbedder(File(dir, GemmaEmbedder.DIR)).also {
                    BolLogger.log(LogLevel.INFO, LogCategory.MODEL, "model_loaded",
                        "EmbeddingGemma ready in ${System.currentTimeMillis() - t0}ms (file check/copy ${t1 - t0}ms, tokenizer ${it.tokenizerLoadMs}ms, ONNX session ${it.sessionLoadMs}ms)",
                        durationMs = System.currentTimeMillis() - t0, extras = mapOf("model" to "embedder"))
                }
            }.also { embedder = it }
            texts.chunked(EMBED_BATCH).flatMap { batch ->
                yield()
                model.embed(batch, isQuery)
            }
        } catch (e: LinkageError) {
            // A missing native library must cost search its vectors, not crash the app (callers catch Exception).
            closeEmbedder()
            throw IllegalStateException("EmbeddingGemma unavailable: ${e.javaClass.simpleName}", e)
        } catch (e: OutOfMemoryError) {
            closeEmbedder()
            throw IllegalStateException("EmbeddingGemma ran out of memory", e)
        }
    }

    /** The native call runs on its own thread; a stop from [AnswerGuard], a timeout or a cancel ends it through cancelProcess. */
    private suspend fun streamAnswer(session: Session, prompt: String): String = coroutineScope {
        val guard = AnswerGuard()
        val finished = CompletableDeferred<Unit>()
        tasksGeneration = finished
        val stop = AtomicReference<AnswerGuard.Stop?>(null)
        val cancelOnce = AtomicBoolean(false)
        fun cancelNative() {
            if (cancelOnce.compareAndSet(false, true)) scope.launch { runCatching { session.cancelProcess() } }
        }
        session.generateContentStream(listOf(InputData.Text(prompt)), object : ResponseCallback {
            override fun onNext(response: String) {
                guard.add(response)?.let { reason -> if (stop.compareAndSet(null, reason)) cancelNative() }
            }
            override fun onDone() { finished.complete(Unit) }
            override fun onError(throwable: Throwable) {
                // Our own stop ends the stream with a cancelled error; the text so far is the answer.
                if (stop.get() != null) finished.complete(Unit) else finished.completeExceptionally(throwable)
            }
        })
        try {
            finished.await()
        } catch (e: CancellationException) {
            cancelNative()
            withContext(NonCancellable) { withTimeoutOrNull(CANCEL_WAIT_MS) { runCatching { finished.await() } } }
            throw e
        }
        stop.get()?.takeIf { it != AnswerGuard.Stop.CLOSED }?.let {
            BolLogger.log(LogLevel.WARN, LogCategory.PARSER, "tasks_answer_stopped", "Stopped the answer early: $it after ${guard.answer.length} chars")
        }
        guard.answer
    }

    private suspend fun <T> useModels(block: suspend () -> T): T {
        checkModelStatus()
        return lock.withLock {
            releaseJob?.cancel()
            try {
                withContext(Dispatchers.Default) { block() }
            } finally {
                scheduleRelease()
            }
        }
    }

    private fun <T> timedLoad(name: String, load: () -> T): T {
        val t0 = System.currentTimeMillis()
        return load().also {
            BolLogger.log(LogLevel.INFO, LogCategory.MODEL, "model_loaded", "$name ready in ${System.currentTimeMillis() - t0}ms",
                durationMs = System.currentTimeMillis() - t0, extras = mapOf("model" to name))
        }
    }

    // Callers hold [lock], so no call is running on the model being closed.
    private suspend fun closeTasks() {
        // A cancelled generation that did not stop within CANCEL_WAIT_MS may still be running natively:
        // wait for it, and keep the engine rather than close it under a running generation.
        val running = tasksGeneration
        if (running != null && !running.isCompleted &&
            withTimeoutOrNull(BUSY_ENGINE_WAIT_MS) { running.join() } == null
        ) {
            BolLogger.log(LogLevel.WARN, LogCategory.MODEL, "tasks_engine_still_busy",
                "Task model still generating ${BUSY_ENGINE_WAIT_MS / 1000}s after a cancel — keeping it loaded instead of closing it")
            return
        }
        runCatching { tasksEngine?.close() }
        tasksEngine = null
        tasksGeneration = null
    }

    private fun closeSpeech() {
        runCatching { speech?.close() }
        speech = null
    }

    private fun closeEmbedder() {
        runCatching { embedder?.close() }
        embedder = null
    }

    private fun scheduleRelease() {
        releaseJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            unloadNow()
        }
    }

    private suspend fun unloadNow() = lock.withLock {
        closeTasks()
        closeSpeech()
        closeEmbedder()
    }

    /** Stop tapped or a generation timed out: free the memory as soon as the current call ends. */
    fun unloadModel() {
        scope.launch { unloadNow() }
    }

    companion object {
        const val TASKS_FILE = "q06_t061s1e3.litertlm"
        const val SPEECH_FILE = "granite_speech_ctc_wi8fc.tflite"
        const val VOCAB_FILE = "vocab_hex.txt"
        private val FILES = listOf(TASKS_FILE, SPEECH_FILE, VOCAB_FILE)
        /** Copied on first use, apart from [FILES], so a failed copy only costs search its vectors, never voice notes. */
        private val EMBED_FILES = listOf(GemmaEmbedder.MODEL_FILE, GemmaEmbedder.DATA_FILE, GemmaEmbedder.TOKENIZER_FILE).map { "${GemmaEmbedder.DIR}/$it" }
        private const val EMBED_BATCH = 16
        private const val MAX_TOKENS = 1024
        private const val CANCEL_WAIT_MS = 5_000L
        private const val BUSY_ENGINE_WAIT_MS = 30_000L
        private const val IDLE_RELEASE_MS = 90_000L
    }
}
