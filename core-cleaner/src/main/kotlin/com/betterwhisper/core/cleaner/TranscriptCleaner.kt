package com.betterwhisper.core.cleaner

import android.util.Log

/**
 * Module 4: High-speed (<1.5 ms) text cleaner backed by Rust libbetter_whisper_core,
 * with OpenWhispr smartSpacing and pure-Kotlin fallback so it can never crash.
 */
class TranscriptCleaner {

    companion object {
        private const val TAG = "TranscriptCleaner"
        private var nativeAvailable = false

        init {
            try {
                System.loadLibrary("better_whisper_core")
                System.loadLibrary("cleaner_jni")
                nativeAvailable = true
                Log.i(TAG, "Loaded native libbetter_whisper_core.so + libcleaner_jni.so")
            } catch (t: Throwable) {
                nativeAvailable = false
                Log.w(TAG, "Native cleaner unavailable, using Kotlin fallback: ${t.message}")
            }
        }

        val default by lazy { TranscriptCleaner() }

        private val FILLER_REGEX = Regex(
            "(?i)(?:,\\s*)?\\b(um+|uh+|er+|ah+|hmm+|hm+|you know|i mean|sort of|kind of)\\b(?:\\s*,)?"
        )
        private val SCRATCH_THAT_REGEX = Regex("(?i).*\\b(scratch that|never mind|delete that)\\b\\s*")
        private val NO_WAIT_REGEX = Regex("(?i)\\b(no wait|sorry i meant|or rather)\\s+")
    }

    fun clean(
        rawText: String,
        contextBeforeCursor: String? = null,
        removeFillers: Boolean = true,
        deduplicateStutters: Boolean = true,
        handleSelfCorrections: Boolean = true
    ): String {
        if (rawText.isBlank()) return ""

        val cleaned = if (nativeAvailable) {
            try {
                nativeClean(
                    rawText,
                    contextBeforeCursor,
                    removeFillers,
                    deduplicateStutters,
                    handleSelfCorrections
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Native clean call failed, falling back to Kotlin: ${t.message}")
                cleanKotlinFallback(rawText, contextBeforeCursor, removeFillers, deduplicateStutters, handleSelfCorrections)
            }
        } else {
            cleanKotlinFallback(rawText, contextBeforeCursor, removeFillers, deduplicateStutters, handleSelfCorrections)
        }

        return cleaned
    }

    private fun cleanKotlinFallback(
        rawText: String,
        contextBeforeCursor: String?,
        removeFillers: Boolean,
        deduplicateStutters: Boolean,
        handleSelfCorrections: Boolean
    ): String {
        var text = rawText.trim()
        if (text.isEmpty()) return ""

        if (handleSelfCorrections) {
            text = text.replace(SCRATCH_THAT_REGEX, "")
            val match = NO_WAIT_REGEX.find(text)
            if (match != null) {
                val beforeWords = text.substring(0, match.range.first).trim().split(Regex("\\s+")).toMutableList()
                val after = text.substring(match.range.last + 1).trim()
                val afterWords = after.split(Regex("\\s+"))
                val popCount = afterWords.size.coerceAtMost(beforeWords.size).coerceAtLeast(1)
                repeat(popCount) { if (beforeWords.isNotEmpty()) beforeWords.removeAt(beforeWords.lastIndex) }
                text = if (beforeWords.isEmpty()) after else "${beforeWords.joinToString(" ")} $after"
            }
        }

        if (removeFillers) {
            text = text.replace(FILLER_REGEX, " ")
        }

        if (deduplicateStutters) {
            val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val out = mutableListOf<String>()
            var i = 0
            while (i < words.size) {
                val curr = words[i].lowercase().trim { !it.isLetterOrDigit() }
                val next = if (i + 1 < words.size) words[i + 1].lowercase().trim { !it.isLetterOrDigit() } else ""
                if (curr.isNotEmpty() && curr == next) {
                    i++
                    continue
                }
                out.add(words[i])
                i++
            }
            text = out.joinToString(" ")
        }

        text = text.replace(Regex("\\s+"), " ")
            .replace(" ,", ",")
            .replace(" .", ".")
            .trimStart(',', ';', ' ', '.')
            .trim()

        if (text.isEmpty()) return ""

        val ctx = contextBeforeCursor ?: return text.replaceFirstChar { it.uppercase() }
        val trimmedCtx = ctx.trimEnd()
        val shouldCapitalize = trimmedCtx.isEmpty() ||
                trimmedCtx.endsWith(".") ||
                trimmedCtx.endsWith("!") ||
                trimmedCtx.endsWith("?") ||
                trimmedCtx.endsWith("\n")

        var result = if (shouldCapitalize) {
            text.replaceFirstChar { it.uppercase() }
        } else {
            text.replaceFirstChar { it.lowercase() }
        }

        // OpenWhispr smartSpacing rule: ensure space between existing text and new dictation
        if (ctx.isNotEmpty() && !ctx.endsWith(" ") && !ctx.endsWith("\n")) {
            result = " $result"
        }

        return result
    }

    private external fun nativeClean(
        rawText: String,
        contextBeforeCursor: String?,
        removeFillers: Boolean,
        dedupStutters: Boolean,
        handleCorrections: Boolean
    ): String
}
