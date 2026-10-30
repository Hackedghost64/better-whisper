package com.betterwhisper.feature.bubble

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import com.betterwhisper.core.audio.AudioCaptureEngine
import com.betterwhisper.core.transcribe.PhononTranscribeEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

class FlowBubbleOverlay(private val service: FlowAccessibilityService) {

    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var bubbleView: View? = null
    private var isExpanded = false

    private val audioEngine = AudioCaptureEngine()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var visualizerJob: Job? = null
    private var audioStreamJob: Job? = null

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

    // Default keyboard height on S23 Ultra / modern phones is ~300dp (~1100px)
    private val density = service.resources.displayMetrics.density
    private var currentImeHeight = (320 * density).toInt()

    private val layoutParams = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        x = 0
        y = currentImeHeight + (16 * density).toInt() // Position cleanly above the keyboard
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
    }

    fun show() {
        if (bubbleView != null) return

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

        // Make the collapsed bubble draggable and clickable
        setupDraggable(containerCollapsed)

        btnClose.setOnClickListener {
            cancelAndCollapse()
        }

        btnDone.setOnClickListener {
            finishAndInject()
        }

        // Listen for keyboard insets dynamically to keep bubble above the keyboard
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.setOnApplyWindowInsetsListener { _, insets ->
                val ime = insets.getInsets(WindowInsets.Type.ime())
                if (ime.bottom > 0 && ime.bottom != currentImeHeight) {
                    currentImeHeight = ime.bottom
                    layoutParams.y = currentImeHeight + (16 * density).toInt()
                    try {
                        windowManager.updateViewLayout(view, layoutParams)
                    } catch (_: Exception) {}
                }
                insets
            }
        }

        try {
            windowManager.addView(view, layoutParams)
        } catch (_: Exception) {}
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
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (initialTouchY - event.rawY).toInt() // invert for Gravity.BOTTOM

                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isDragging = true
                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy
                        try {
                            windowManager.updateViewLayout(bubbleView, layoutParams)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Regular tap -> expand to 3-component dictation pill
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
        if (isExpanded) return
        isExpanded = true

        containerCollapsed.visibility = View.GONE
        containerExpanded.visibility = View.VISIBLE

        containerExpanded.scaleX = 0.7f
        containerExpanded.scaleY = 0.7f
        containerExpanded.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(200)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()

        // 1. Start Audio Capture (Module 2) - Battery optimized: only captures while active
        audioEngine.startCapture()

        // 2. Open Stream in Transcription Engine (Module 3)
        PhononTranscribeEngine.instance.startStream()

        // 3. Connect real-time visualizer
        visualizerJob = scope.launch {
            audioEngine.visualizerLevels.collectLatest { levels ->
                updateVisualizerBars(levels)
            }
        }

        // 4. Stream audio blocks into Phonon and auto-detect sentence completion
        audioStreamJob = scope.launch(Dispatchers.IO) {
            audioEngine.audioFlow.collect { pcmBlock ->
                val event = PhononTranscribeEngine.instance.feedAudio(pcmBlock)
                if (event != null && event.isFinal) {
                    withContext(Dispatchers.Main) {
                        finishAndInject()
                    }
                }
            }
        }
    }

    private fun updateVisualizerBars(levels: FloatArray) {
        val minHeightPx = (4 * density).toInt()
        val maxHeightPx = (24 * density).toInt()

        for (i in 0 until visualizerBars.size) {
            val bar = visualizerBars[i]
            val level = if (i < levels.size) levels[i] else 0.1f
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
        audioStreamJob?.cancel()
        audioEngine.stopCapture()
        PhononTranscribeEngine.instance.stopStream()

        containerExpanded.visibility = View.GONE
        containerCollapsed.visibility = View.VISIBLE
    }

    private fun finishAndInject() {
        if (!isExpanded) return
        isExpanded = false

        visualizerJob?.cancel()
        audioStreamJob?.cancel()
        audioEngine.stopCapture()

        scope.launch(Dispatchers.IO) {
            val transcript = PhononTranscribeEngine.instance.stopStream()
            withContext(Dispatchers.Main) {
                containerExpanded.visibility = View.GONE
                containerCollapsed.visibility = View.VISIBLE

                if (transcript.isNotBlank()) {
                    service.injectTextIntoFocusedField(transcript)
                }
            }
        }
    }

    fun onDestroy() {
        hide()
        scope.cancel()
    }
}
