package com.betterwhisper.core.cleaner

/**
 * Module 4: High-speed (<1.5 ms) text cleaner backed by Rust libbetter_whisper_core.
 * Strips filler words (um, uh, ah), deduplicates stutters, and fixes verbal corrections.
 */
class TranscriptCleaner {

    companion object {
        init {
            System.loadLibrary("better_whisper_core")
            System.loadLibrary("cleaner_jni")
        }

        val default by lazy { TranscriptCleaner() }
    }

    fun clean(
        rawText: String,
        contextBeforeCursor: String? = null,
        removeFillers: Boolean = true,
        deduplicateStutters: Boolean = true,
        handleSelfCorrections: Boolean = true
    ): String {
        if (rawText.isBlank()) return ""
        return nativeClean(
            rawText,
            contextBeforeCursor,
            removeFillers,
            deduplicateStutters,
            handleSelfCorrections
        )
    }

    private external fun nativeClean(
        rawText: String,
        contextBeforeCursor: String?,
        removeFillers: Boolean,
        dedupStutters: Boolean,
        handleCorrections: Boolean
    ): String
}
