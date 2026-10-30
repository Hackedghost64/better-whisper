package com.betterwhisper.feature.bubble

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.*
import android.view.animation.DecelerateInterpolator
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

    private val density = service.resources.displayMetrics.density
    private val sideMarginPx = (14 * density).toInt()

    // Screen dimensions
    private val screenWidthPx = service.resources.displayMetrics.widthPixels
    private val screenHeightPx = service.resources.displayMetrics.heightPixels

    // Layout dimension estimates
    private val collapsedWidthPx = (50 * density).toInt()
    private val expandedWidthPx = (196 * density).toInt()

    // Keyboard state
    private var currentImeHeightPx = 0
    private var isDockedOnRight = true

    // Resting vertical offset above keyboard or navigation bar
    private var preferredUserY = (340 * density).toInt()

    private var xAnimator: ValueAnimator? = null
    private var yAnimator: ValueAnimator? = null

    private val layoutParams = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        gravity = Gravity.BOTTOM or Gravity.START
        x = screenWidthPx - collapsedWidthPx - sideMarginPx
        y = (340 * density).toInt()
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
    }

    fun isExpanded(): Boolean = isExpanded || isTranscribing

    fun show(keyboardTopFromBottomPx: Int? = null) {
        if (keyboardTopFromBottomPx != null && keyboardTopFromBottomPx > 0) {
            currentImeHeightPx = keyboardTopFromBottomPx
        }

        val minSafeY = getMinSafeY()
        val targetY = preferredUserY.coerceAtLeast(minSafeY)
            .coerceAtMost(screenHeightPx - (120 * density).toInt())
        layoutParams.y = targetY

        val currentW = if (isExpanded) expandedWidthPx else collapsedWidthPx
        layoutParams.x = if (isDockedOnRight) {
            screenWidthPx - currentW - sideMarginPx
        } else {
            sideMarginPx
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

        setupSideDockingDraggable(containerCollapsed)

        btnClose.setOnClickListener {
            cancelAndCollapse()
        }

        btnDone.setOnClickListener {
            finishAndInject()
        }

        try {
            windowManager.addView(view, layoutParams)
            Log.i(TAG, "Flow bubble overlay shown at x=${layoutParams.x}, y=${layoutParams.y}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add Flow bubble view: ${e.message}", e)
        }
    }

    private fun getMinSafeY(): Int {
        return if (currentImeHeightPx > (100 * density).toInt()) {
            currentImeHeightPx + (16 * density).toInt()
        } else {
            (48 * density).toInt()
        }
    }

    /**
     * Responds immediately when a keyboard opens or closes:
     * - If keyboard opens: pushes bubble up above the keyboard.
     * - If keyboard closes: lowers bubble back down while respecting side-docking.
     */
    fun updateKeyboardHeight(imeHeightPx: Int) {
        currentImeHeightPx = imeHeightPx
        val minSafeY = getMinSafeY()
        val targetY = preferredUserY.coerceAtLeast(minSafeY)
            .coerceAtMost(screenHeightPx - (120 * density).toInt())

        if (layoutParams.y != targetY) {
            animateY(targetY)
        }
    }

    private fun animateY(targetY: Int) {
        yAnimator?.cancel()
        val startY = layoutParams.y
        if (bubbleView == null || startY == targetY) {
            layoutParams.y = targetY
            return
        }

        yAnimator = ValueAnimator.ofInt(startY, targetY).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                layoutParams.y = anim.animatedValue as Int
                try {
                    if (bubbleView != null) {
                        windowManager.updateViewLayout(bubbleView, layoutParams)
                    }
                } catch (_: Exception) {}
            }
            start()
        }
    }

    private fun animateX(targetX: Int) {
        xAnimator?.cancel()
        val startX = layoutParams.x
        if (bubbleView == null || startX == targetX) {
            layoutParams.x = targetX
            return
        }

        xAnimator = ValueAnimator.ofInt(startX, targetX).apply {
            duration = 220
            interpolator = OvershootInterpolator(1.15f)
            addUpdateListener { anim ->
                layoutParams.x = anim.animatedValue as Int
                try {
                    if (bubbleView != null) {
                        windowManager.updateViewLayout(bubbleView, layoutParams)
                    }
                } catch (_: Exception) {}
            }
            start()
        }
    }

    /**
     * Side-docking gesture handler:
     * - User can freely drag UP, DOWN, LEFT, RIGHT.
     * - Bubble NEVER stays in the middle: upon touch release, it cleanly snaps
     *   to the nearest edge (Left or Right side).
     */
    private fun setupSideDockingDraggable(view: View) {
        var startTouchRawX = 0f
        var startTouchRawY = 0f
        var initialBubbleX = 0
        var initialBubbleY = 0
        var isDragging = false

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    xAnimator?.cancel()
                    yAnimator?.cancel()
                    startTouchRawX = event.rawX
                    startTouchRawY = event.rawY
                    initialBubbleX = layoutParams.x
                    initialBubbleY = layoutParams.y
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startTouchRawX).toInt()
                    val dy = (startTouchRawY - event.rawY).toInt()

                    if (abs(dx) > (8 * density) || abs(dy) > (8 * density)) {
                        isDragging = true
                        val minSafeY = getMinSafeY()
                        val maxSafeY = screenHeightPx - (120 * density).toInt()

                        val newY = (initialBubbleY + dy).coerceIn(minSafeY, maxSafeY)
                        val newX = (initialBubbleX + dx).coerceIn(
                            sideMarginPx,
                            screenWidthPx - collapsedWidthPx - sideMarginPx
                        )

                        layoutParams.x = newX
                        layoutParams.y = newY
                        preferredUserY = newY

                        try {
                            if (bubbleView != null) {
                                windowManager.updateViewLayout(bubbleView, layoutParams)
                            }
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isDragging) {
                        v.performClick()
                        expandAndStartDictation()
                    } else {
                        // Snap strictly to closest side (Left or Right) - Never stays in the middle
                        val bubbleCenterX = layoutParams.x + (collapsedWidthPx / 2)
                        val isRightSide = bubbleCenterX >= (screenWidthPx / 2f)
                        isDockedOnRight = isRightSide

                        val targetX = if (isRightSide) {
                            screenWidthPx - collapsedWidthPx - sideMarginPx
                        } else {
                            sideMarginPx
                        }
                        animateX(targetX)

                        val minSafeY = getMinSafeY()
                        val maxSafeY = screenHeightPx - (120 * density).toInt()
                        val targetY = layoutParams.y.coerceIn(minSafeY, maxSafeY)
                        if (targetY != layoutParams.y) {
                            animateY(targetY)
                        }
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

        val targetX = if (isDockedOnRight) {
            screenWidthPx - expandedWidthPx - sideMarginPx
        } else {
            sideMarginPx
        }
        animateX(targetX)

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

        val targetX = if (isDockedOnRight) {
            screenWidthPx - collapsedWidthPx - sideMarginPx
        } else {
            sideMarginPx
        }
        animateX(targetX)
    }

    private fun finishAndInject() {
        if (!isExpanded || isTranscribing) return
        isExpanded = false
        isTranscribing = true

        visualizerJob?.cancel()
        val pcmSamples = audioEngine.stopAndGetPcm()

        containerExpanded.visibility = View.GONE
        containerCollapsed.visibility = View.VISIBLE

        val targetX = if (isDockedOnRight) {
            screenWidthPx - collapsedWidthPx - sideMarginPx
        } else {
            sideMarginPx
        }
        animateX(targetX)

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
