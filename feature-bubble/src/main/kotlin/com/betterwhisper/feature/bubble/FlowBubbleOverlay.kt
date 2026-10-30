package com.betterwhisper.feature.bubble

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.*
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import com.betterwhisper.core.audio.AudioCaptureEngine
import com.betterwhisper.core.transcribe.PhononTranscribeEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

class FlowBubbleOverlay(private val service: FlowAccessibilityService) {

    companion object {
        private const val TAG = "FlowBubbleOverlay"
    }

    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var bubbleView: View? = null
    private var isExpanded = false
    private var isTranscribing = false

    private val audioEngine = AudioCaptureEngine()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var visualizerJob: Job? = null

    private lateinit var containerCollapsed: View
    private lateinit var containerExpanded: View
    private lateinit var btnClose: ImageButton
    private lateinit var btnDone: ImageButton
    private lateinit var visualizerBars: List<View>

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    private val density = service.resources.displayMetrics.density
    private var currentImeHeight = (320 * density).toInt()

    // Docked at the bottom-right corner, cleanly avoiding the middle of the screen
    // and resting right above the keyboard's top edge
    private val layoutParams = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        gravity = Gravity.BOTTOM or Gravity.END
        x = (16 * density).toInt() // 16dp from right edge, avoiding the center of the screen
        y = currentImeHeight + (12 * density).toInt() // Above the keyboard
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
    }

    fun show(keyboardTopFromBottomPx: Int? = null) {
        if (keyboardTopFromBottomPx != null && keyboardTopFromBottomPx > (150 * density).toInt()) {
            currentImeHeight = keyboardTopFromBottomPx
            if (!isDragging) {
                layoutParams.y = currentImeHeight + (12 * density).toInt()
            }
        }

        if (bubbleView != null) {
            try {
                windowManager.updateViewLayout(bubbleView, layoutParams)
            } catch (_: Exception) {}
            return
        }

        val inflater = LayoutInflater.from(service)
        val view = inflater.inflate(R.layout.layout_flow_bubble, null)
        bubbleView = view

        containerCollapsed = view.findViewById(R.id.container_collapsed)
        containerExpanded = view.findViewById(R.id.container_expanded)
        btnClose = view.findViewById(R.id.btn_close)
        btnDone = view.findViewById(R.id.btn_done)

        visualizerBars = listOf(
            view.findViewById(R.id.bar_1),
            view.findViewById(R.id.bar_2),
            view.findViewById(R.id.bar_3),
            view.findViewById(R.id.bar_4),
            view.findViewById(R.id.bar_5)
        )

        setupDraggable(containerCollapsed)

        btnClose.setOnClickListener {
            cancelAndCollapse()
        }

        btnDone.setOnClickListener {
            finishAndInject()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.setOnApplyWindowInsetsListener { _, insets ->
                val ime = insets.getInsets(WindowInsets.Type.ime())
                if (ime.bottom > 0 && ime.bottom != currentImeHeight) {
                    currentImeHeight = ime.bottom
                    if (!isDragging) {
                        layoutParams.y = currentImeHeight + (12 * density).toInt()
                        try {
                            windowManager.updateViewLayout(view, layoutParams)
                        } catch (_: Exception) {}
                    }
                }
                insets
            }
        }

        try {
            windowManager.addView(view, layoutParams)
            Log.i(TAG, "Flow bubble overlay added at x=${layoutParams.x}, y=${layoutParams.y} (above keyboard, bottom-right)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add Flow bubble view: ${e.message}", e)
        }
    }

    private fun setupDraggable(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // For Gravity.END: dragging left increases X (moves further from right edge)
                    val dx = (initialTouchX - event.rawX).toInt()
                    val dy = (initialTouchY - event.rawY).toInt()

                    if (abs(dx) > 12 || abs(dy) > 12) {
                        isDragging = true
                        layoutParams.x = (initialX + dx).coerceAtLeast((8 * density).toInt())
                        layoutParams.y = (initialY + dy).coerceAtLeast(currentImeHeight + (8 * density).toInt())
                        try {
                            windowManager.updateViewLayout(bubbleView, layoutParams)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        v.performClick()
                        expandAndStartDictation()
                    }
                    true
                }
                else -> false
            }
        }
    }

    fun hide() {
        if (isExpanded) {
            cancelAndCollapse()
        }
        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            bubbleView = null
        }
    }

    private fun expandAndStartDictation() {
        if (isExpanded || isTranscribing) return
        isExpanded = true

        containerCollapsed.visibility = View.GONE
        containerExpanded.visibility = View.VISIBLE

        containerExpanded.scaleX = 0.75f
        containerExpanded.scaleY = 0.75f
        containerExpanded.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(220)
            .setInterpolator(OvershootInterpolator(1.15f))
            .start()

        // 1. Start audio capture (Module 2) - Manual stop only (never auto-stops on pauses)
        val started = audioEngine.startCapture()

        if (!started) {
            Log.e(TAG, "Failed to start audio capture")
            cancelAndCollapse()
            return
        }

        // 2. Drive the small audio visualizer
        visualizerJob = scope.launch {
            audioEngine.visualizerLevels.collectLatest { levels ->
                updateVisualizerBars(levels)
            }
        }
    }

    private fun updateVisualizerBars(levels: FloatArray) {
        val minHeightPx = (4 * density).toInt()
        val maxHeightPx = (24 * density).toInt()

        for (i in visualizerBars.indices) {
            val bar = visualizerBars[i]
            val level = if (i < levels.size) levels[i] else 0.12f
            val targetHeight = (minHeightPx + (maxHeightPx - minHeightPx) * level).toInt()

            val lp = bar.layoutParams
            lp.height = targetHeight
            bar.layoutParams = lp
        }
    }

    private fun cancelAndCollapse() {
        if (!isExpanded) return
        isExpanded = false

        visualizerJob?.cancel()
        audioEngine.stopAndGetPcm()

        containerExpanded.visibility = View.GONE
        containerCollapsed.visibility = View.VISIBLE
    }

    private fun finishAndInject() {
        if (!isExpanded || isTranscribing) return
        isExpanded = false
        isTranscribing = true

        visualizerJob?.cancel()
        val pcmSamples = audioEngine.stopAndGetPcm()

        containerExpanded.visibility = View.GONE
        containerCollapsed.visibility = View.VISIBLE

        if (pcmSamples.isEmpty()) {
            isTranscribing = false
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                PhononTranscribeEngine.instance.ensureLoaded(service.applicationContext)
                val t0 = System.currentTimeMillis()
                val rawTranscript = PhononTranscribeEngine.instance.transcribeBuffer(pcmSamples)
                val decodeMs = System.currentTimeMillis() - t0
                Log.i(TAG, "Phonon-2 decoded in ${decodeMs}ms: \"$rawTranscript\"")

                if (rawTranscript.isNotBlank()) {
                    withContext(Dispatchers.Main) {
                        service.injectTextIntoFocusedField(rawTranscript, decodeMs)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Transcription error: ${t.message}", t)
            } finally {
                isTranscribing = false
            }
        }
    }

    fun onDestroy() {
        hide()
        scope.cancel()
    }
}
