package com.betterwhisper.core.intent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device Speech Intent and Self-Correction engine backed by SmolLM2-135M-Instruct
 * and llama.cpp compiled with ARM NEON / fp16.
 */
class LlamaIntentEngine {
    companion object {
        private const val TAG = "LlamaIntentEngine"

        init {
            try {
                System.loadLibrary("intent_jni")
                Log.i(TAG, "Successfully loaded libintent_jni.so")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load libintent_jni.so: ${e.message}")
            }
        }

        private const val PROMPT_PREFIX =
            "<|im_start|>system\n" +
            "You are a speech repair engine. Given spoken text containing self-corrections, slips of the tongue, or false starts, output the intended final sentence. Output ONLY the clean sentence without explanation.<|im_end|>\n" +
            "<|im_start|>user\nhow about at 5pm no at 6<|im_end|>\n" +
            "<|im_start|>assistant\nHow about at 6pm.<|im_end|>\n" +
            "<|im_start|>user\nlet's meet Tuesday wait Wednesday<|im_end|>\n" +
            "<|im_start|>assistant\nLet's meet Wednesday.<|im_end|>\n" +
            "<|im_start|>user\nsend the email to David actually Sarah<|im_end|>\n" +
            "<|im_start|>assistant\nSend the email to Sarah.<|im_end|>\n" +
            "<|im_start|>user\n"

        private const val PROMPT_SUFFIX = "<|im_end|>\n<|im_start|>assistant\n"
    }

    private var handle: Long = 0L

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded

    @Synchronized
    fun loadModel(modelFile: File, nThreads: Int = 4): Boolean {
        if (handle != 0L) return true
        if (!modelFile.exists() || modelFile.length() < 10_000_000L) {
            Log.e(TAG, "Model file does not exist or is too small: ${modelFile.absolutePath}")
            return false
        }

        val start = System.currentTimeMillis()
        handle = nativeInit(modelFile.absolutePath, nThreads)
        val elapsed = System.currentTimeMillis() - start

        if (handle != 0L) {
            _isLoaded.value = true
            Log.i(TAG, "LlamaIntentEngine initialized in ${elapsed}ms from ${modelFile.name}")
            return true
        }

        Log.e(TAG, "nativeInit failed for ${modelFile.absolutePath}")
        return false
    }

    @Synchronized
    fun repair(rawText: String, maxTokens: Int = 48): String {
        if (handle == 0L) {
            Log.w(TAG, "repair called before model loaded, returning rawText")
            return rawText
        }

        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return ""

        val prompt = "$PROMPT_PREFIX$trimmed$PROMPT_SUFFIX"
        val start = System.currentTimeMillis()
        val repaired = nativeRepair(handle, prompt, maxTokens)
        val elapsed = System.currentTimeMillis() - start

        Log.i(TAG, "Repaired: \"$trimmed\" -> \"$repaired\" (${elapsed}ms)")
        return if (repaired.isNotBlank()) repaired else trimmed
    }

    suspend fun repairAsync(rawText: String, maxTokens: Int = 48): String =
        withContext(Dispatchers.Default) {
            repair(rawText, maxTokens)
        }

    @Synchronized
    fun release() {
        if (handle != 0L) {
            nativeRelease(handle)
            handle = 0L
            _isLoaded.value = false
            Log.i(TAG, "LlamaIntentEngine released")
        }
    }

    private external fun nativeInit(modelPath: String, nThreads: Int): Long
    private external fun nativeRepair(handle: Long, prompt: String, maxTokens: Int): String
    private external fun nativeRelease(handle: Long)
}
