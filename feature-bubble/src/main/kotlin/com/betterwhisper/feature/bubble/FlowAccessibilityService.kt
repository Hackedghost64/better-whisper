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
 * Detects keyboard visibility and text field interactions across all Android apps
 * (Samsung Notes, Claude, WhatsApp, Chrome, Keep, Telegram, Slack, etc.).
 * Docks the Flow bubble safely above the keyboard and guarantees seamless text injection.
 */
class FlowAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FlowAccessibilitySvc"
        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected
    }

    private var overlay: FlowBubbleOverlay? = null
    private var lastTargetNode: AccessibilityNodeInfo? = null

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

        // 1. Track the last focused or clicked editable view across apps
        event.source?.let { node ->
            if (node.isEditable || node.isFocused || isLikelyInputField(node)) {
                lastTargetNode = node
            }
        }

        // 2. Dynamically evaluate whether keyboard or text field is active
        evaluateBubbleVisibility()
    }

    override fun onInterrupt() {
        overlay?.hide()
    }

    private fun evaluateBubbleVisibility() {
        val keyboardHeight = detectKeyboardHeightPx()
        val isKeyboardOpen = keyboardHeight != null && keyboardHeight > 0
        val hasTextFocus = hasAnyEditableOrInputFocus()

        if (isKeyboardOpen || hasTextFocus) {
            overlay?.show(keyboardHeight)
        } else {
            // Keep bubble visible if user is currently recording/dictating
            if (overlay?.isExpanded() != true) {
                overlay?.hide()
            }
        }
    }

    private fun detectKeyboardHeightPx(): Int? {
        return try {
            val displayMetrics = resources.displayMetrics
            val screenHeight = displayMetrics.heightPixels
            val density = displayMetrics.density
            val minImeHeight = (120 * density).toInt()
            val rect = Rect()

            for (window in windows) {
                val isImeType = window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                val pkg = window.root?.packageName?.toString()?.lowercase() ?: ""
                val isImePkg = pkg.contains("keyboard") ||
                        pkg.contains("honeyboard") ||
                        pkg.contains("inputmethod") ||
                        pkg.contains("latin") ||
                        pkg.contains("swiftkey")

                if (isImeType || isImePkg) {
                    window.getBoundsInScreen(rect)
                    val heightFromBottom = screenHeight - rect.top
                    if (heightFromBottom > minImeHeight && rect.top in 1 until screenHeight) {
                        return heightFromBottom
                    }
                    if (rect.height() > minImeHeight) {
                        return rect.height()
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "detectKeyboardHeightPx error: ${e.message}")
            null
        }
    }

    private fun hasAnyEditableOrInputFocus(): Boolean {
        try {
            val root = rootInActiveWindow ?: return false
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && (focused.isEditable || isLikelyInputField(focused))) {
                lastTargetNode = focused
                return true
            }

            if (searchForInputFocus(root)) {
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    private fun isLikelyInputField(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val cls = node.className?.toString() ?: ""
        return cls.contains("EditText", ignoreCase = true) ||
                cls.contains("AutoCompleteTextView", ignoreCase = true) ||
                cls.contains("ExtractEditText", ignoreCase = true) ||
                cls.contains("RichContent", ignoreCase = true) ||
                cls.contains("Editor", ignoreCase = true) ||
                cls.contains("ComposerView", ignoreCase = true)
    }

    private fun searchForInputFocus(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 6) return false
        if (node.isFocused && isLikelyInputField(node)) {
            lastTargetNode = node
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (searchForInputFocus(child, depth + 1)) {
                return true
            }
        }
        return false
    }

    fun injectTextIntoFocusedField(rawText: String, decodeMs: Long = 0L) {
        val root = rootInActiveWindow

        // 1. Resolve best target node
        var targetNode: AccessibilityNodeInfo? = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (targetNode == null) {
            targetNode = lastTargetNode
        }
        if (targetNode == null) {
            for (window in windows) {
                val wRoot = window.root ?: continue
                val found = wRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (found != null) {
                    targetNode = found
                    break
                }
            }
        }
        if (targetNode == null && root != null) {
            targetNode = findFirstEditableOrFocused(root)
        }

        var existingText = ""
        if (targetNode != null) {
            val textStr = targetNode.text?.toString()?.trim() ?: ""
            val hintStr = targetNode.hintText?.toString()?.trim() ?: ""

            // Strict check to ensure we NEVER prepend placeholder/hint words
            val isPlaceholder = targetNode.isShowingHintText ||
                    (hintStr.isNotBlank() && textStr.equals(hintStr, ignoreCase = true)) ||
                    textStr.equals("Message", ignoreCase = true) ||
                    textStr.equals("Type a message", ignoreCase = true) ||
                    textStr.equals("Send a message", ignoreCase = true) ||
                    textStr.equals("Search", ignoreCase = true) ||
                    textStr.equals("Search...", ignoreCase = true) ||
                    textStr.equals("Add a comment...", ignoreCase = true) ||
                    textStr.equals("Note", ignoreCase = true) ||
                    textStr.equals("Title", ignoreCase = true)

            if (!isPlaceholder) {
                existingText = textStr
            }
        }

        // Clean transcript (<1.5 ms) with cursor context
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

        // Step 1: Prime system clipboard as universal fallback
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("BetterWhisper", cleanedText))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy to clipboard: ${e.message}")
        }

        // Step 2: Try ACTION_SET_TEXT if node is standard editable
        var injected = false
        if (targetNode != null && (targetNode.isEditable || targetNode.className?.contains("EditText") == true)) {
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
            injected = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            Log.i(TAG, "ACTION_SET_TEXT result: $injected")
        }

        // Step 3: Try ACTION_PASTE on targetNode
        if (!injected && targetNode != null) {
            injected = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            Log.i(TAG, "targetNode ACTION_PASTE result: $injected")
        }

        // Step 4: Try ACTION_PASTE on active window root
        if (!injected && root != null) {
            injected = root.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            Log.i(TAG, "root ACTION_PASTE result: $injected")
        }

        Log.i(TAG, "Injection finished. success=$injected (Clipboard always primed with: \"$cleanedText\")")
    }

    private fun findFirstEditableOrFocused(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 5) return null
        if (node.isEditable || (node.isFocused && isLikelyInputField(node))) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditableOrFocused(child, depth + 1)
            if (found != null) return found
        }
        return null
    }

    override fun onDestroy() {
        _isConnected.value = false
        super.onDestroy()
        overlay?.onDestroy()
        overlay = null
    }
}
