package com.betterwhisper.feature.bubble

import android.accessibilityservice.AccessibilityService
import android.content.ComponentCallbacks2
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.betterwhisper.core.transcribe.PhononTranscribeEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * System-wide accessibility service.
 *
 * Responsibilities are intentionally narrow:
 *  - decide whether an editable field / keyboard is present,
 *  - keep the floating bubble attached and docked,
 *  - delegate text insertion to [TextInjector].
 *
 * Accessibility events are debounced. The old implementation traversed the
 * whole node tree on every event (including every keystroke), which is both a
 * battery problem and a source of races with the IME window while the user
 * types quickly.
 */
class FlowAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FlowAccessibilitySvc"
        private const val EVALUATE_DEBOUNCE_MS = 140L
        private const val HIDE_DELAY_MS = 280L

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected
    }

    private var overlay: FlowBubbleOverlay? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val evaluateRunnable = Runnable { evaluateNow() }
    private val hideRunnable = Runnable { hideIfIdle() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isConnected.value = true
        overlay = FlowBubbleOverlay(this)
        Log.i(TAG, "FlowAccessibilityService connected")

        Thread {
            PhononTranscribeEngine.instance.ensureLoaded(applicationContext)
        }.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> scheduleEvaluate(0L)
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> scheduleEvaluate(EVALUATE_DEBOUNCE_MS)

            // Do not chase every character. Wait until typing pauses before
            // re-checking the IME/focus state.
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> scheduleEvaluate(HIDE_DELAY_MS)
        }
    }

    override fun onInterrupt() {
        overlay?.forceHide()
    }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && overlay?.isBusy() != true) {
            PhononTranscribeEngine.instance.release()
        }
        super.onTrimMemory(level)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(evaluateRunnable)
        mainHandler.removeCallbacks(hideRunnable)
        overlay?.onDestroy()
        overlay = null
        _isConnected.value = false
        super.onDestroy()
    }

    fun injectTextIntoFocusedField(rawText: String, decodeMs: Long = 0L, expectedPackage: String? = null) {
        val result = TextInjector.inject(this, rawText, decodeMs, expectedPackage)
        Log.i(TAG, "Injection finished: $result")
        scheduleEvaluate(0L)
    }

    fun onDictationFinished() {
        scheduleEvaluate(0L)
    }

    fun currentFocusPackage(): String? =
        try {
            rootInActiveWindow?.packageName?.toString()
        } catch (_: Throwable) {
            null
        }

    private fun scheduleEvaluate(delayMs: Long) {
        mainHandler.removeCallbacks(evaluateRunnable)
        mainHandler.postDelayed(evaluateRunnable, delayMs)
    }

    private fun evaluateNow() {
        val overlay = overlay ?: return

        // A gesture, snap, recording, or decode owns the window right now.
        // Re-evaluate when it finishes via onDictationFinished().
        if (overlay.isBusy()) return

        val keyboardHeight = detectKeyboardHeightPx()
        val hasFocus = hasAnyEditableOrInputFocus()

        if (keyboardHeight > 0 || hasFocus) {
            mainHandler.removeCallbacks(hideRunnable)
            overlay.show(keyboardHeight)
        } else {
            // Wait a beat before removing the window. Fast app switches and
            // IME close animations often emit a transient "no focus" event.
            mainHandler.removeCallbacks(hideRunnable)
            mainHandler.postDelayed(hideRunnable, HIDE_DELAY_MS)
        }
    }

    private fun hideIfIdle() {
        val overlay = overlay ?: return
        if (overlay.isBusy()) return
        if (detectKeyboardHeightPx() > 0) return
        if (hasAnyEditableOrInputFocus()) return
        overlay.hide()
    }

    private fun detectKeyboardHeightPx(): Int {
        return try {
            val metrics = resources.displayMetrics
            val screenHeight = metrics.heightPixels
            val minImeHeight = (120 * metrics.density).toInt()
            val rect = Rect()
            var best = 0

            for (window in windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                window.getBoundsInScreen(rect)
                val heightFromBottom = screenHeight - rect.top
                if (rect.top in 1 until screenHeight && heightFromBottom > minImeHeight) {
                    best = maxOf(best, heightFromBottom)
                }
            }

            if (best == 0) {
                // A few OEM keyboards are not exposed as TYPE_INPUT_METHOD.
                // Fall back to package heuristics, but still require a
                // bottom-anchored window tall enough to be a keyboard.
                for (window in windows) {
                    val pkg = window.root?.packageName?.toString()?.lowercase() ?: continue
                    val looksLikeIme = pkg.contains("keyboard") ||
                        pkg.contains("honeyboard") ||
                        pkg.contains("inputmethod") ||
                        pkg.contains("latin") ||
                        pkg.contains("swiftkey")
                    if (!looksLikeIme) continue

                    window.getBoundsInScreen(rect)
                    val heightFromBottom = screenHeight - rect.top
                    if (rect.top in 1 until screenHeight && heightFromBottom > minImeHeight) {
                        best = maxOf(best, heightFromBottom)
                    }
                }
            }
            best
        } catch (t: Throwable) {
            Log.e(TAG, "detectKeyboardHeightPx failed: ${t.message}")
            0
        }
    }

    private fun hasAnyEditableOrInputFocus(): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && isUsableField(focused)) return true
            searchForInputFocus(root, depth = 0)
        } catch (t: Throwable) {
            Log.e(TAG, "hasAnyEditableOrInputFocus failed: ${t.message}")
            false
        }
    }

    /**
     * Password fields are deliberately excluded: the bubble should not appear
     * over a PIN/password screen, and [TextInjector] refuses them as well.
     */
    private fun isUsableField(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return false
        if (node.isEditable) return true
        val cls = node.className?.toString() ?: return false
        return cls.contains("EditText", ignoreCase = true) ||
            cls.contains("AutoCompleteTextView", ignoreCase = true) ||
            cls.contains("ExtractEditText", ignoreCase = true) ||
            cls.contains("RichContent", ignoreCase = true) ||
            cls.contains("Editor", ignoreCase = true) ||
            cls.contains("ComposerView", ignoreCase = true)
    }

    private fun searchForInputFocus(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 6) return false
        if (node.isFocused && isUsableField(node)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (searchForInputFocus(child, depth + 1)) return true
        }
        return false
    }
}
