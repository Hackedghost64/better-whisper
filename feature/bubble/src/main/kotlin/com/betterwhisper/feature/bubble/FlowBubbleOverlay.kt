package com.betterwhisper.feature.bubble

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import com.betterwhisper.core.audio.AudioCaptureEngine
import com.betterwhisper.core.transcribe.PhononTranscribeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The floating Flow bubble.
 *
 * State is an explicit [Mode] instead of a set of booleans, and placement math
 * lives in [BubblePlacement]. The service treats [isBusy] as a hard guard: it
 * never removes the window while the user is touching it, snapping it, or
 * recording/transcribing. That removes the "bubble disappears mid-gesture and
 * the next tap goes nowhere" failure mode.
 */
class FlowBubbleOverlay(private val service: FlowAccessibilityService) {

    private enum class Mode { COLLAPSED, RECORDING, TRANSCRIBING }

    companion object {
        private const val TAG = "FlowBubbleOverlay"
        private const val SNAP_DURATION_MS = 200L
        private const val KEYBOARD_SNAP_DURATION_MS = 160L
    }

    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = service.resources.displayMetrics.density

    private val sideMarginPx = (14 * density).toInt()
    private val collapsedWidthPx = (50 * density).toInt()
    private val expandedWidthPx = (196 * density).toInt()
    private val bubbleHeightPx = (52 * density).toInt()
    private val topGuardPx = (8 * density).toInt()
    private val bottomGuardPx = (96 * density).toInt()

    /**
     * The IME is measured every accessibility event, and its window bounds
     * animate while the keyboard opens. Ignoring changes smaller than this
     * keeps the bubble from vibrating with each animation frame.
     */
    private val imeJitterPx = (10 * density).toInt()

    private var viewport = BubbleViewport(0, 0, sideMarginPx, topGuardPx, bottomGuardPx)

    private var mode = Mode.COLLAPSED
    private var isUserInteracting = false
    private var isSnapping = false
    private var isDockedOnRight = true
    private var currentImeHeightPx = 0
    private var preferredUserY = (340 * density).toInt()
    private var recordingPackage: String? = null

    private var dragStartX = 0
    private var dragStartY = 0

    private var bubbleView: View? = null
    private var attached = false
    private var containerCollapsed: View? = null
    private var containerExpanded: View? = null
    private var btnClose: ImageButton? = null
    private var btnDone: ImageButton? = null
    private var visualizerBars: List<View> = emptyList()
    private var visualizerJob: Job? = null
    private var snapAnimator: AnimatorSet? = null

    private val audioEngine = AudioCaptureEngine()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val layoutParams = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        gravity = Gravity.BOTTOM or Gravity.START
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
    }

    init {
        refreshViewport()
        layoutParams.x = viewport.dockedX(isDockedOnRight, collapsedWidthPx)
        layoutParams.y = viewport.clampY(preferredUserY, currentImeHeightPx, bubbleHeightPx)
        preferredUserY = layoutParams.y
    }

    fun isExpanded(): Boolean = mode != Mode.COLLAPSED

    /** True while a gesture, snap animation, recording, or decode is active. */
    fun isBusy(): Boolean = isUserInteracting || isSnapping || mode != Mode.COLLAPSED

    fun show(imeHeightPx: Int = 0) {
        val newImeHeight = imeHeightPx.coerceAtLeast(0)
        if (abs(newImeHeight - currentImeHeightPx) > imeJitterPx) {
            currentImeHeightPx = newImeHeight
        }
        refreshViewport()

        if (mode != Mode.COLLAPSED) {
            ensureAttached()
            updateKeyboardHeight(currentImeHeightPx)
            return
        }
        if (isUserInteracting || isSnapping) return

        val targetX = viewport.dockedX(isDockedOnRight, collapsedWidthPx)
        val targetY = viewport.clampY(preferredUserY, currentImeHeightPx, bubbleHeightPx)
        preferredUserY = targetY

        // Do not touch the window unless it actually has to move or appear.
        // Calling updateViewLayout on every accessibility event is what made
        // the bubble flicker while typing.
        val moved = layoutParams.x != targetX || layoutParams.y != targetY
        layoutParams.x = targetX
        layoutParams.y = targetY

        ensureAttached()
        if (moved) safeUpdateLayout()
    }

    fun updateKeyboardHeight(imeHeightPx: Int) {
        val newHeight = imeHeightPx.coerceAtLeast(0)
        if (abs(newHeight - currentImeHeightPx) <= imeJitterPx) return
        currentImeHeightPx = newHeight
        if (bubbleView == null) return

        val targetY = viewport.clampY(preferredUserY, currentImeHeightPx, bubbleHeightPx)
        preferredUserY = targetY
        if (isUserInteracting || isSnapping) return

        val width = if (mode == Mode.COLLAPSED) collapsedWidthPx else expandedWidthPx
        val targetX = viewport.dockedX(isDockedOnRight, width)
        if (targetX != layoutParams.x || targetY != layoutParams.y) {
            animateSnap(targetX, targetY, KEYBOARD_SNAP_DURATION_MS)
        }
    }

    /** Returns false when the bubble is busy and must not be removed yet. */
    fun hide(): Boolean {
        if (isBusy()) return false
        cancelAnimations()
        mode = Mode.COLLAPSED
        Log.i(TAG, "Bubble hidden (no editable field or keyboard)")
        detachView()
        return true
    }

    fun forceHide() {
        cancelAnimations()
        isUserInteracting = false
        recordingPackage = null
        mode = Mode.COLLAPSED
        detachView()
    }

    fun onDestroy() {
        visualizerJob?.cancel()
        visualizerJob = null
        scope.cancel()
        audioEngine.release()
        forceHide()
    }

    private fun refreshViewport() {
        val width: Int
        val height: Int
        val navigationInset: Int
        val statusInset: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets
            width = metrics.bounds.width()
            height = metrics.bounds.height()
            navigationInset = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom
            statusInset = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
        } else {
            val metrics = service.resources.displayMetrics
            width = metrics.widthPixels
            height = metrics.heightPixels
            navigationInset = 0
            statusInset = statusBarHeightFallback()
        }

        viewport = BubbleViewport(
            screenWidthPx = width,
            screenHeightPx = height,
            sideMarginPx = sideMarginPx,
            topInsetPx = statusInset + topGuardPx,
            bottomInsetPx = bottomGuardPx + navigationInset
        )
    }

    private fun statusBarHeightFallback(): Int {
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) service.resources.getDimensionPixelSize(id) else (24 * density).toInt()
    }

    private fun ensureAttached() {
        // `View.isAttachedToWindow` is false between addView() and the first
        // traversal. Polling it here used to tear down and re-add a brand-new
        // window on every accessibility event, which is exactly what made the
        // bubble flicker. Track attach state with a listener instead.
        if (bubbleView != null && attached) return
        detachView()

        val view = LayoutInflater.from(service).inflate(R.layout.layout_flow_bubble, null)
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

        val dragController = BubbleDragController(
            touchSlopPx = ViewConfiguration.get(service).scaledTouchSlop,
            onGestureStart = {
                cancelAnimations()
                isUserInteracting = true
                dragStartX = layoutParams.x
                dragStartY = layoutParams.y
            },
            onDrag = ::onDrag,
            onGestureEnd = ::onGestureEnd
        )
        containerCollapsed?.setOnTouchListener(dragController)
        btnClose?.setOnClickListener { cancelAndCollapse() }
        btnDone?.setOnClickListener { finishAndInject() }

        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (bubbleView === v) attached = true
            }

            override fun onViewDetachedFromWindow(v: View) {
                // The system removed the window (or we did). Drop all view
                // references so the next show() rebuilds instead of animating
                // a dead token.
                if (bubbleView === v) {
                    attached = false
                    bubbleView = null
                    containerCollapsed = null
                    containerExpanded = null
                    btnClose = null
                    btnDone = null
                    visualizerBars = emptyList()
                }
            }
        })

        bubbleView = view
        try {
            windowManager.addView(view, layoutParams)
            attached = true
            Log.i(TAG, "Bubble attached at x=${layoutParams.x}, y=${layoutParams.y}")
        } catch (t: RuntimeException) {
            Log.e(TAG, "Unable to attach Flow bubble: ${t.message}", t)
            bubbleView = null
            attached = false
            containerCollapsed = null
            containerExpanded = null
        }
    }

    private fun onDrag(dx: Int, dy: Int) {
        if (bubbleView == null) return
        val newY = viewport.clampY(dragStartY - dy, currentImeHeightPx, bubbleHeightPx)
        layoutParams.y = newY
        preferredUserY = newY

        // Bubble stays strictly on the sides (can be moved up or down, but never parks in the middle).
        // Only flip to the other side if the drag crosses the center threshold.
        val currentTouchX = dragStartX + dx
        val shouldDockRight = (currentTouchX + collapsedWidthPx / 2) >= (viewport.screenWidthPx / 2)
        if (shouldDockRight != isDockedOnRight) {
            isDockedOnRight = shouldDockRight
            layoutParams.x = viewport.dockedX(isDockedOnRight, collapsedWidthPx)
        }
        safeUpdateLayout()
    }

    private fun onGestureEnd(dragged: Boolean, cancelled: Boolean) {
        isUserInteracting = false
        if (bubbleView == null) return

        if (!dragged && !cancelled) {
            expandAndStartDictation()
            return
        }

        snapToEdge(SNAP_DURATION_MS)
    }

    private fun snapToEdge(durationMs: Long = SNAP_DURATION_MS, widthPx: Int = -1) {
        if (bubbleView == null) return
        val width = when {
            widthPx > 0 -> widthPx
            mode == Mode.COLLAPSED -> collapsedWidthPx
            else -> expandedWidthPx
        }
        isDockedOnRight = viewport.shouldDockRight(layoutParams.x, width)
        val targetX = viewport.dockedX(isDockedOnRight, width)
        val targetY = viewport.clampY(preferredUserY, currentImeHeightPx, bubbleHeightPx)
        animateSnap(targetX, targetY, durationMs)
    }

    private fun animateSnap(targetX: Int, targetY: Int, durationMs: Long) {
        cancelAnimations()
        if (bubbleView == null) {
            layoutParams.x = targetX
            layoutParams.y = targetY
            return
        }

        val startX = layoutParams.x
        val startY = layoutParams.y
        if (startX == targetX && startY == targetY) {
            safeUpdateLayout()
            return
        }

        val xAnimator = ValueAnimator.ofInt(startX, targetX).apply {
            addUpdateListener {
                layoutParams.x = it.animatedValue as Int
                safeUpdateLayout()
            }
        }
        val yAnimator = ValueAnimator.ofInt(startY, targetY).apply {
            addUpdateListener {
                layoutParams.y = it.animatedValue as Int
                safeUpdateLayout()
            }
        }

        val set = AnimatorSet().apply {
            playTogether(xAnimator, yAnimator)
            this.duration = durationMs
            interpolator = DecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (snapAnimator !== animation) return
                    layoutParams.x = targetX
                    layoutParams.y = targetY
                    safeUpdateLayout()
                    isSnapping = false
                    snapAnimator = null
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (snapAnimator === animation) {
                        isSnapping = false
                        snapAnimator = null
                    }
                }
            })
        }
        snapAnimator = set
        isSnapping = true
        set.start()
    }

    private fun cancelAnimations() {
        val animator = snapAnimator
        snapAnimator = null
        isSnapping = false
        animator?.cancel()
    }

    private fun safeUpdateLayout() {
        val view = bubbleView ?: return
        // Do not use isAttachedToWindow here: it is false until the first
        // traversal, and treating that as "detached" caused re-add churn.
        if (!attached) return
        try {
            windowManager.updateViewLayout(view, layoutParams)
        } catch (t: RuntimeException) {
            // A detached window or an IME transition can invalidate the token.
            // Do not swallow this silently: mark the view as detached so the
            // next show() re-attaches it instead of animating a ghost.
            Log.e(TAG, "updateViewLayout failed: ${t.message}", t)
            attached = false
        }
    }

    private fun detachView() {
        val view = bubbleView ?: return
        try {
            // Always attempt the removal. `isAttachedToWindow` can still be
            // false for a window that addView() has already registered, and
            // skipping the removal would leak that window.
            windowManager.removeView(view)
        } catch (t: RuntimeException) {
            Log.w(TAG, "removeView failed: ${t.message}")
        }
        bubbleView = null
        attached = false
        containerCollapsed = null
        containerExpanded = null
        btnClose = null
        btnDone = null
        visualizerBars = emptyList()
    }

    private fun expandAndStartDictation() {
        if (mode != Mode.COLLAPSED || bubbleView == null) return
        cancelAnimations()
        mode = Mode.RECORDING
        recordingPackage = service.currentFocusPackage()

        val targetX = viewport.dockedX(isDockedOnRight, expandedWidthPx)
        val targetY = viewport.clampY(preferredUserY, currentImeHeightPx, bubbleHeightPx)
        layoutParams.x = targetX
        layoutParams.y = targetY
        safeUpdateLayout()
        showExpandedUi()

        if (!audioEngine.startCapture()) {
            Log.e(TAG, "Failed to start audio capture")
            cancelAndCollapse()
            return
        }

        visualizerJob?.cancel()
        visualizerJob = scope.launch {
            audioEngine.visualizerLevels.collectLatest { levels ->
                updateVisualizerBars(levels)
            }
        }
    }

    private fun cancelAndCollapse() {
        if (mode == Mode.COLLAPSED) return
        cancelAnimations()
        mode = Mode.COLLAPSED
        recordingPackage = null
        visualizerJob?.cancel()
        visualizerJob = null
        audioEngine.stopAndGetPcm()

        // Dock strictly to side edge before showing collapsed UI (never jumps to middle)
        val targetX = viewport.dockedX(isDockedOnRight, collapsedWidthPx)
        val targetY = viewport.clampY(preferredUserY, currentImeHeightPx, bubbleHeightPx)
        layoutParams.x = targetX
        layoutParams.y = targetY
        safeUpdateLayout()

        showCollapsedUi()
        service.onDictationFinished()
    }

    private fun finishAndInject() {
        if (mode != Mode.RECORDING) return
        cancelAnimations()
        mode = Mode.TRANSCRIBING
        visualizerJob?.cancel()
        visualizerJob = null

        val targetPackage = recordingPackage
        recordingPackage = null

        val pcmSamples = audioEngine.stopAndGetPcm()

        // Dock strictly to side edge before showing collapsed UI (never jumps to middle)
        val targetX = viewport.dockedX(isDockedOnRight, collapsedWidthPx)
        val targetY = viewport.clampY(preferredUserY, currentImeHeightPx, bubbleHeightPx)
        layoutParams.x = targetX
        layoutParams.y = targetY
        safeUpdateLayout()

        showCollapsedUi()

        if (pcmSamples.isEmpty()) {
            finishTranscription()
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                PhononTranscribeEngine.instance.ensureLoaded(service.applicationContext)
                val startedAt = System.currentTimeMillis()
                val rawTranscript = PhononTranscribeEngine.instance.transcribeBuffer(pcmSamples)
                val decodeMs = System.currentTimeMillis() - startedAt
                Log.i(TAG, "Phonon-2 decoded in ${decodeMs}ms: \"$rawTranscript\"")

                if (rawTranscript.isNotBlank()) {
                    withContext(Dispatchers.Main) {
                        service.injectTextIntoFocusedField(rawTranscript, decodeMs, targetPackage)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Transcription error: ${t.message}", t)
            } finally {
                withContext(Dispatchers.Main) {
                    finishTranscription()
                }
            }
        }
    }

    private fun finishTranscription() {
        mode = Mode.COLLAPSED
        service.onDictationFinished()
    }

    private fun showExpandedUi() {
        containerCollapsed?.visibility = View.GONE
        containerExpanded?.visibility = View.VISIBLE
        containerExpanded?.let { view ->
            view.animate().cancel()
            view.scaleX = 0.75f
            view.scaleY = 0.75f
            view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200)
                .setInterpolator(OvershootInterpolator(1.1f))
                .start()
        }
    }

    private fun showCollapsedUi() {
        containerExpanded?.let { it.animate().cancel() }
        containerExpanded?.visibility = View.GONE
        containerCollapsed?.visibility = View.VISIBLE
    }

    private fun updateVisualizerBars(levels: FloatArray) {
        if (mode != Mode.RECORDING) return
        val minHeightPx = (4 * density).toInt()
        val maxHeightPx = (24 * density).toInt()

        visualizerBars.forEachIndexed { index, bar ->
            val level = if (index < levels.size) levels[index] else 0.12f
            val targetHeight = (minHeightPx + (maxHeightPx - minHeightPx) * level).toInt()
            val params = bar.layoutParams
            params.height = targetHeight
            bar.layoutParams = params
        }
    }
}
