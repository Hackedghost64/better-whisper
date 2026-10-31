package com.betterwhisper.core.intent

import android.content.Context
import android.util.Log
import com.betterwhisper.core.cleaner.TranscriptCleaner
import com.betterwhisper.core.transcribe.AssetModelExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Hybrid speech intent and self-correction dispatcher.
 *
 * Fast Path (<2ms): Fluent utterances bypass the SLM entirely and are cleaned
 * by the native Rust text cleaner.
 *
 * Intent Path (~130ms): Utterances containing self-corrections ("at 5pm no at 6")
 * route through SmolLM2-135M on CPU/NEON to synthesize the intended sentence,
 * followed by tech dictionary casing and cursor-context alignment.
 */
object SpeechIntentResolver {
    private const val TAG = "SpeechIntentResolver"

    private val engine = LlamaIntentEngine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isInitializing = false

    fun init(context: Context) {
        if (engine.isLoaded.value || isInitializing) return
        isInitializing = true

        scope.launch {
            try {
                val extracted = AssetModelExtractor.ensureExtracted(context)
                val modelFile = extracted.intentModelFile
                if (modelFile != null && modelFile.exists()) {
                    Log.i(TAG, "Warming up on-device SLM intent engine from ${modelFile.absolutePath}")
                    engine.loadModel(modelFile, nThreads = 4)
                } else {
                    Log.w(TAG, "Intent model file not found in extracted models")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing intent engine: ${e.message}", e)
            } finally {
                isInitializing = false
            }
        }
    }

    /**
     * Resolves raw ASR transcript into the clean intended text.
     *
     * @param rawAsrText Raw transcript produced by Phonon-2.
     * @param contextBeforeCursor Text immediately preceding the insertion cursor.
     */
    fun resolve(rawAsrText: String, contextBeforeCursor: String? = null): String {
        val trimmed = rawAsrText.trim()
        if (trimmed.isEmpty()) return ""

        // Fast path: if no repair cues detected, clean with Rust text cleaner in <2ms
        val hasRepair = SpeechIntentClassifier.hasRepairCues(trimmed)
        if (!hasRepair || !engine.isLoaded.value) {
            return TranscriptCleaner.default.clean(trimmed, contextBeforeCursor)
        }

        // Intent path: repair through on-device SLM
        val startMs = System.currentTimeMillis()
        val repaired = engine.repair(trimmed)
        val elapsed = System.currentTimeMillis() - startMs
        Log.i(TAG, "SLM Repair applied in ${elapsed}ms: \"$trimmed\" -> \"$repaired\"")

        // Final polish: ensure sentence capitalization, tech vocabulary ("GitHub", "Phonon-2"),
        // and context spacing before cursor.
        return TranscriptCleaner.default.clean(repaired, contextBeforeCursor)
    }

    suspend fun resolveAsync(rawAsrText: String, contextBeforeCursor: String? = null): String {
        return resolve(rawAsrText, contextBeforeCursor)
    }

    fun release() {
        engine.release()
    }
}
