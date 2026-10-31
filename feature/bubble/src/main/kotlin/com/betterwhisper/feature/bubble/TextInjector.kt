package com.betterwhisper.feature.bubble

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.betterwhisper.core.cleaner.TranscriptCleaner
import com.betterwhisper.core.intent.SpeechIntentResolver

/**
 * Resolves the focused editable field and inserts the cleaned transcript.
 *
 * Extracted from [FlowAccessibilityService] so the service is only responsible
 * for lifecycle + keyboard/focus tracking. The injector never writes into
 * password fields and only touches the clipboard when direct insertion fails.
 */
object TextInjector {

    private const val TAG = "TextInjector"

    private val PLACEHOLDER_VALUES = setOf(
        "message", "type a message", "send a message", "search", "search...",
        "add a comment...", "note", "title", "reply", "write a message"
    )

    enum class Result {
        INJECTED,
        PASTED,
        COPIED_ONLY,
        NO_TARGET,
        PASSWORD_FIELD,
        APP_CHANGED,
        FAILED
    }

    /**
     * @param expectedPackage package that owned the focused field when the
     *   recording started. When it is non-null and the user has switched apps
     *   in the meantime the transcript is only copied to the clipboard, never
     *   typed into the new app.
     */
    fun inject(
        service: AccessibilityService,
        rawText: String,
        decodeMs: Long,
        expectedPackage: String? = null
    ): Result {
        if (rawText.isBlank()) return Result.FAILED

        val node = resolveTarget(service)
        if (node == null) {
            val cleaned = SpeechIntentResolver.resolve(rawText)
            copyToClipboard(service, cleaned)
            emit(rawText, cleaned, decodeMs, injected = false, targetPackage = null)
            return Result.COPIED_ONLY
        }

        val targetPackage = node.packageName?.toString()
        if (expectedPackage != null && targetPackage != expectedPackage) {
            // Fast app switching used to paste a dictation into whatever app
            // happened to be focused. Keep the text, but do not write it.
            val cleaned = SpeechIntentResolver.resolve(rawText)
            copyToClipboard(service, cleaned)
            Log.w(TAG, "App changed during dictation ($expectedPackage -> $targetPackage); copied instead")
            emit(rawText, cleaned, decodeMs, injected = false, targetPackage = targetPackage)
            return Result.APP_CHANGED
        }

        // Never dictate into password / PIN fields.
        if (node.isPassword) {
            Log.w(TAG, "Refusing to inject into a password field in $targetPackage")
            return Result.PASSWORD_FIELD
        }

        val snapshot = snapshotOf(node)
        val cleaned = SpeechIntentResolver.resolve(
            rawAsrText = rawText,
            contextBeforeCursor = snapshot.beforeCursor.ifBlank { null }
        )
        if (cleaned.isBlank()) return Result.FAILED

        val composed = compose(snapshot, cleaned)
        if (setText(node, composed.text)) {
            setSelection(node, composed.cursor)
            emit(rawText, cleaned, decodeMs, injected = true, targetPackage = targetPackage)
            return Result.INJECTED
        }

        // Direct insertion is blocked by some apps (Compose, WebView, custom
        // editors). Paste is the portable fallback; only then do we touch the
        // clipboard.
        copyToClipboard(service, cleaned)
        if (paste(node) || paste(service.rootInActiveWindow)) {
            emit(rawText, cleaned, decodeMs, injected = true, targetPackage = targetPackage)
            return Result.PASTED
        }

        Log.w(TAG, "Could not inject into $targetPackage; transcript left on clipboard")
        emit(rawText, cleaned, decodeMs, injected = false, targetPackage = targetPackage)
        return Result.COPIED_ONLY
    }

    private fun resolveTarget(service: AccessibilityService): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && isEditableLike(focused)) return focused
        return findFirstEditable(root, depth = 0)
    }

    private fun isEditableLike(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val cls = node.className?.toString() ?: return false
        return cls.contains("EditText", ignoreCase = true) ||
            cls.contains("AutoCompleteTextView", ignoreCase = true) ||
            cls.contains("ExtractEditText", ignoreCase = true) ||
            cls.contains("RichContent", ignoreCase = true) ||
            cls.contains("Editor", ignoreCase = true) ||
            cls.contains("ComposerView", ignoreCase = true)
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 6) return null
        if (isEditableLike(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditable(child, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private data class FieldSnapshot(
        val node: AccessibilityNodeInfo,
        val text: String,
        val beforeCursor: String,
        val afterCursor: String,
        val hasSelection: Boolean
    )

    private data class ComposedText(val text: String, val cursor: Int)

    private fun snapshotOf(node: AccessibilityNodeInfo): FieldSnapshot {
        val rawText = node.text?.toString().orEmpty()
        val hint = node.hintText?.toString()?.trim().orEmpty()
        val isPlaceholder = node.isShowingHintText ||
            (hint.isNotBlank() && rawText.trim().equals(hint, ignoreCase = true)) ||
            PLACEHOLDER_VALUES.contains(rawText.trim().lowercase())

        val text = if (isPlaceholder) "" else rawText
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        val valid = start in 0..text.length && end in start..text.length

        return if (valid) {
            FieldSnapshot(
                node = node,
                text = text,
                beforeCursor = text.substring(0, start),
                afterCursor = text.substring(end),
                hasSelection = true
            )
        } else {
            // Some WebViews/custom editors do not expose a selection. Fall
            // back to appending at the end of the field rather than guessing.
            FieldSnapshot(
                node = node,
                text = text,
                beforeCursor = text,
                afterCursor = "",
                hasSelection = false
            )
        }
    }

    private fun compose(snapshot: FieldSnapshot, cleaned: String): ComposedText {
        val insertion = if (snapshot.beforeCursor.isEmpty()) cleaned.trimStart() else cleaned
        val needsSpaceAfter = snapshot.afterCursor.isNotEmpty() &&
            !snapshot.afterCursor.first().isWhitespace() &&
            !insertion.last().isWhitespace()

        val suffix = if (needsSpaceAfter) " " else ""
        return ComposedText(
            text = snapshot.beforeCursor + insertion + suffix + snapshot.afterCursor,
            cursor = snapshot.beforeCursor.length + insertion.length + suffix.length
        )
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (t: Throwable) {
            Log.w(TAG, "ACTION_SET_TEXT failed: ${t.message}")
            false
        }
    }

    private fun setSelection(node: AccessibilityNodeInfo, cursor: Int) {
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        }
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        } catch (_: Throwable) {
            // Cursor placement is best-effort; text insertion already worked.
        }
    }

    private fun paste(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (t: Throwable) {
            Log.w(TAG, "ACTION_PASTE failed: ${t.message}")
            false
        }
    }

    private fun copyToClipboard(service: AccessibilityService, text: String) {
        try {
            val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("BetterWhisper", text))
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to copy to clipboard: ${t.message}")
        }
    }

    private fun emit(rawText: String, cleaned: String, decodeMs: Long, injected: Boolean, targetPackage: String?) {
        DictationEventBus.emit(
            DictationEvent(
                rawText = rawText,
                cleanedText = cleaned,
                decodeMs = decodeMs,
                injected = injected,
                targetPackage = targetPackage
            )
        )
    }
}
