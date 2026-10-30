package com.betterwhisper.core.cleaner

import android.util.Log

/**
 * Module 4: text cleaner backed by the Rust `libbetter_whisper_core` fast path,
 * with a pure-Kotlin fallback ([FlowTextCleaner]) so dictation can never crash
 * because a native library is missing.
 *
 * Both paths run the same rules; [FlowTextCleaner] is the readable reference.
 */
class TranscriptCleaner {

    companion object {
        private const val TAG = "TranscriptCleaner"
        private var nativeAvailable = false

        init {
            nativeAvailable = try {
                System.loadLibrary("better_whisper_core")
                System.loadLibrary("cleaner_jni")
                Log.i(TAG, "Loaded native libbetter_whisper_core.so + libcleaner_jni.so")
                true
            } catch (t: Throwable) {
                Log.w(TAG, "Native cleaner unavailable, using Kotlin fallback: ${t.message}")
                false
            }
        }

        val default by lazy { TranscriptCleaner() }
    }

    fun clean(
        rawText: String,
        contextBeforeCursor: String? = null,
        removeFillers: Boolean = true,
        deduplicateStutters: Boolean = true,
        handleSelfCorrections: Boolean = true,
        aggressiveDiscourse: Boolean = true
    ): String {
        if (rawText.isBlank()) return ""

        if (nativeAvailable) {
            try {
                return nativeClean(
                    rawText,
                    contextBeforeCursor,
                    removeFillers,
                    deduplicateStutters,
                    handleSelfCorrections,
                    aggressiveDiscourse
                )
            } catch (t: Throwable) {
                // A JNI failure must never cost the user their dictation.
                Log.w(TAG, "Native clean failed, using Kotlin fallback: ${t.message}")
            }
        }

        val cleaned = FlowTextCleaner.clean(
            rawText = rawText,
            contextBeforeCursor = contextBeforeCursor,
            removeFillers = removeFillers,
            deduplicateStutters = deduplicateStutters,
            handleSelfCorrections = handleSelfCorrections,
            aggressiveDiscourse = aggressiveDiscourse
        )
        return FlowTextCleaner.applyDictionary(cleaned)
    }

    /** Exposed for tests and for callers that want to force the pure path. */
    fun cleanWithFallback(
        rawText: String,
        contextBeforeCursor: String? = null,
        removeFillers: Boolean = true,
        deduplicateStutters: Boolean = true,
        handleSelfCorrections: Boolean = true,
        aggressiveDiscourse: Boolean = true
    ): String {
        val cleaned = FlowTextCleaner.clean(
            rawText = rawText,
            contextBeforeCursor = contextBeforeCursor,
            removeFillers = removeFillers,
            deduplicateStutters = deduplicateStutters,
            handleSelfCorrections = handleSelfCorrections,
            aggressiveDiscourse = aggressiveDiscourse
        )
        return FlowTextCleaner.applyDictionary(cleaned)
    }

    private external fun nativeClean(
        rawText: String,
        contextBeforeCursor: String?,
        removeFillers: Boolean,
        dedupStutters: Boolean,
        handleCorrections: Boolean,
        aggressiveDiscourse: Boolean
    ): String
}
