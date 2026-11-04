package com.bol.app.devtools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bol.app.ai.audio.AudioChunker
import com.bol.app.ai.audio.TranscriptJoiner
import com.bol.app.ai.audio.WavConverter
import com.bol.app.ai.model.ModelManager
import com.bol.app.ai.parser.IntentParser
import com.bol.app.ai.parser.OnDeviceIntentParser
import com.bol.app.data.repository.CategoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

/**
 * Debug builds only. Runs every WAV in files/check_in through the real speech and task code in the app process,
 * one note at a time, and appends one JSON line per note to files/check_out.jsonl. Nothing is saved as a note.
 * Lines of files/check_notes.jsonl ({"id","note"} or {"id","prompt"}) get the raw task-model answer instead. Keep the app open while it runs, so the process stays alive.
 * Start: adb shell am broadcast -n com.heedapp.android.ondevice/com.bol.app.devtools.PipelineCheckReceiver
 */
class PipelineCheckReceiver : BroadcastReceiver(), KoinComponent {
    private val models: ModelManager by inject()
    private val parser: IntentParser by inject()
    private val categories: CategoryRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val out = File(context.filesDir, "check_out.jsonl")
            try {
                models.checkModelStatus()
                File(context.filesDir, "check_in").listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name }?.forEach { wav ->
                    val t0 = System.currentTimeMillis()
                    val samples = WavConverter.wavToFloatArray(wav)
                    val chunks = AudioChunker.split(samples, sampleRate = 16_000)
                    val text = TranscriptJoiner.join(models.transcribe(chunks.map { it.samples }), chunks.map { it.overlapsPrevious })
                    val t1 = System.currentTimeMillis()
                    val items = parser.parse(text).items
                    val t2 = System.currentTimeMillis()
                    val rss = File("/proc/self/status").readLines().first { it.startsWith("VmHWM") }.filter(Char::isDigit).toLong() / 1024
                    out.appendText(JsonObject(mapOf(
                        "wav" to JsonPrimitive(wav.name), "seconds" to JsonPrimitive(samples.size / 16_000.0),
                        "speech_ms" to JsonPrimitive(t1 - t0), "tasks_ms" to JsonPrimitive(t2 - t1), "peak_rss_mb" to JsonPrimitive(rss),
                        "text" to JsonPrimitive(text),
                        "items" to JsonPrimitive(items.joinToString(" | ") { "${it.text} [${it.categoryId}|${it.reminderTime ?: ""}]" }),
                    )).toString() + "\n")
                }
                val notes = File(context.filesDir, "check_notes.jsonl")
                if (notes.exists()) for (line in notes.readLines()) {
                    val row = Json.parseToJsonElement(line).jsonObject
                    val t0 = System.currentTimeMillis()
                    // A row with "prompt" (the note's own category list, as in training) is sent exactly as given.
                    val prompt = row["prompt"]?.jsonPrimitive?.content
                        ?: OnDeviceIntentParser.prompt(categories.getAllActiveSnapshot(), row.getValue("note").jsonPrimitive.content)
                    val raw = models.generateTasks(prompt)
                    out.appendText(JsonObject(mapOf("id" to row.getValue("id"), "reply" to JsonPrimitive(raw),
                        "ms" to JsonPrimitive(System.currentTimeMillis() - t0))).toString() + "\n")
                }
                out.appendText("{\"done\":true}\n")
            } catch (e: Throwable) {
                out.appendText("{\"fatal\":${JsonPrimitive("${e.javaClass.simpleName}: ${e.message}")}}\n")
            }
        }
    }
}
