package com.betterwhisper.feature.bubble

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
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
 * Guaranteed invariants:
 *  - Never appears on lock screen (keyguard locked or screen off).
 *  - Hides INSTANTLY as soon as the keyboard exits, even if the text field
 *    remains selected in the underlying app.
 */
class FlowAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FlowAccessibilitySvc"

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected
    }

    private var overlay: FlowBubbleOverlay? = null
    private var lastImeHeightPx = -1
    private val mainHandler = Handler(Looper.getMainLooper())

    private val evaluateRunnable = Runnable { evaluateNow() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(TAG, "Screen off: hiding bubble instantly")
                    overlay?.forceHide()
                }
                Intent.ACTION_USER_PRESENT -> {
                    scheduleEvaluate(0L)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isConnected.value = true
        overlay = FlowBubbleOverlay(this)
        Log.i(TAG, "FlowAccessibilityService connected")

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)

        Thread {
            PhononTranscribeEngine.instance.ensureLoaded(applicationContext)
        }.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> scheduleEvaluate(0L)
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> scheduleEvaluate(0L)
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> scheduleEvaluate(0L)
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
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        mainHandler.removeCallbacks(evaluateRunnable)
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
        if (delayMs <= 0L) {
            evaluateRunnable.run()
        } else {
            mainHandler.postDelayed(evaluateRunnable, delayMs)
        }
    }

    private fun isScreenLocked(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isKeyguardLocked == true
    }

    private fun evaluateNow() {
        val overlay = overlay ?: return

        // 1. Never show on lock screen or while keyguard is active
        if (isScreenLocked()) {
            if (!overlay.isBusy()) {
                overlay.forceHide()
            }
            return
        }

        // 2. A gesture, snap, recording, or decode owns the window right now.
        // Re-evaluate when it finishes via onDictationFinished().
        if (overlay.isBusy()) return

        val keyboardHeight = detectKeyboardHeightPx()
        if (keyboardHeight != lastImeHeightPx) {
            Log.i(TAG, "IME height changed: $lastImeHeightPx -> $keyboardHeight")
            lastImeHeightPx = keyboardHeight
        }

        // 3. Instant response:
        // - If keyboard is open -> show bubble docked above keyboard.
        // - If keyboard is exited -> hide bubble INSTANTLY, even if text field is selected.
        if (keyboardHeight > 0) {
            overlay.show(keyboardHeight)
        } else {
            overlay.hide()
        }
    }

    private fun detectKeyboardHeightPx(): Int {
        return try {
            val metrics = resources.displayMetrics
            val screenHeight = metrics.heightPixels
            val minImeHeight = (100 * metrics.density).toInt()
            val rect = Rect()
            var hasImeWindow = false
            var bestHeight = 0

            for (window in windows) {
                val isTypeIme = window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                val pkg = window.root?.packageName?.toString()?.lowercase() ?: ""
                val isPkgIme = pkg.contains("keyboard") ||
                    pkg.contains("honeyboard") ||
                    pkg.contains("inputmethod") ||
                    pkg.contains("latin") ||
                    pkg.contains("swiftkey")

                if (isTypeIme || isPkgIme) {
                    window.getBoundsInScreen(rect)
                    val heightFromBottom = screenHeight - rect.top
                    if (rect.top in 1 until screenHeight &&
                        heightFromBottom > minImeHeight &&
                        rect.height() < screenHeight * 3 / 4
                    ) {
                        hasImeWindow = true
                        bestHeight = maxOf(bestHeight, heightFromBottom)
                    }
                }
            }

            if (!hasImeWindow) {
                return 0
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val wm = getSystemService(WindowManager::class.java)
                    val imeInset = wm.currentWindowMetrics.windowInsets
                        .getInsets(WindowInsets.Type.ime())
                        .bottom
                    if (imeInset > minImeHeight) return imeInset
                } catch (_: Throwable) {}
            }

            bestHeight
        } catch (t: Throwable) {
            Log.e(TAG, "detectKeyboardHeightPx failed: ${t.message}")
            0
        }
    }
}
