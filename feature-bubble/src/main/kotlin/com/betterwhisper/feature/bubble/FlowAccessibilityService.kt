package com.betterwhisper.feature.bubble

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.betterwhisper.core.cleaner.TranscriptCleaner
import com.betterwhisper.core.transcribe.PhononTranscribeEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Module 1: System-wide Accessibility Service.
 * Detects when an editable text field is clicked/focused,
 * calculates keyboard bounds to dock the bubble right above the keyboard on the right side,
 * and cleanly injects transcript without prepending placeholder/hint words like "Message".
 */
class FlowAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FlowAccessibilitySvc"
        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected
    }

    private var overlay: FlowBubbleOverlay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isConnected.value = true
        overlay = FlowBubbleOverlay(this)
        Log.i(TAG, "FlowAccessibilityService connected")

        // Warm up Phonon-2 in background
        Thread {
            PhononTranscribeEngine.instance.ensureLoaded(applicationContext)
        }.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                checkFocusAndToggleBubble()
            }
        }
    }

    override fun onInterrupt() {
        overlay?.hide()
    }

    private fun checkFocusAndToggleBubble() {
        val root = rootInActiveWindow ?: run {
            overlay?.hide()
            return
        }

        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            val keyboardHeight = detectKeyboardHeightPx()
            overlay?.show(keyboardHeight)
        } else {
            overlay?.hide()
        }
    }

    private fun detectKeyboardHeightPx(): Int? {
        return try {
            val screenHeight = resources.displayMetrics.heightPixels
            val rect = Rect()
            for (window in windows) {
                if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    window.getBoundsInScreen(rect)
                    val height = screenHeight - rect.top
                    if (height > 100) {
                        return height
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun injectTextIntoFocusedField(rawText: String, decodeMs: Long = 0L) {
        val root = rootInActiveWindow
        val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        var existingText = ""
        if (focused != null && focused.isEditable) {
            val textStr = focused.text?.toString()?.trim() ?: ""
            val hintStr = focused.hintText?.toString()?.trim() ?: ""

            // Strict check to ensure we NEVER prepend placeholder/hint words (e.g. "Message", "Type a message")
            val isPlaceholder = focused.isShowingHintText ||
                    (hintStr.isNotBlank() && textStr.equals(hintStr, ignoreCase = true)) ||
                    textStr.equals("Message", ignoreCase = true) ||
                    textStr.equals("Type a message", ignoreCase = true) ||
                    textStr.equals("Send a message", ignoreCase = true) ||
                    textStr.equals("Search", ignoreCase = true) ||
                    textStr.equals("Search...", ignoreCase = true) ||
                    textStr.equals("Add a comment...", ignoreCase = true)

            if (!isPlaceholder) {
                existingText = textStr
            }
        }

        // Module 4: Clean transcript (<1.5 ms) with cursor context
        val cleanedText = TranscriptCleaner.default.clean(
            rawText = rawText,
            contextBeforeCursor = if (existingText.isNotBlank()) existingText else null
        )

        Log.i(TAG, "Injecting cleaned text: \"$cleanedText\" (existing: \"$existingText\")")

        DictationEventBus.emit(
            DictationEvent(
                rawText = rawText,
                cleanedText = cleanedText,
                decodeMs = decodeMs
            )
        )

        if (focused != null && focused.isEditable) {
            val fullText = if (existingText.isBlank()) {
                cleanedText.trimStart()
            } else if (existingText.endsWith(" ") || cleanedText.startsWith(" ")) {
                existingText + cleanedText
            } else {
                "$existingText $cleanedText"
            }

            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    fullText
                )
            }
            val setOk = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            Log.i(TAG, "ACTION_SET_TEXT result: $setOk")

            if (!setOk) {
                // Clipboard paste fallback
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("BetterWhisper", cleanedText))
                focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }
        }
    }

    override fun onDestroy() {
        _isConnected.value = false
        super.onDestroy()
        overlay?.onDestroy()
        overlay = null
    }
}
