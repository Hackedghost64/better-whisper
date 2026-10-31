package com.betterwhisper.feature.bubble

import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Owns the bubble's touch gesture.
 *
 * The important invariant is that [dragging] is *latched*: once the finger has
 * crossed the touch slop it stays a drag until the gesture ends. The old code
 * re-evaluated the slop on every MOVE (and also treated ACTION_CANCEL as a
 * tap), which is what made the bubble occasionally jump into "mid-screen,
 * frozen, unresponsive" state during fast typing.
 *
 * Only [activePointerId] is followed, so a second finger landing on the bubble
 * cannot teleport it, and a cancelled gesture always reports `cancelled = true`
 * so the caller snaps back to a legal edge instead of starting dictation.
 */
class BubbleDragController(
    private val touchSlopPx: Int,
    private val onGestureStart: () -> Unit,
    private val onDrag: (dxPx: Int, dyPx: Int) -> Unit,
    private val onGestureEnd: (dragged: Boolean, cancelled: Boolean) -> Unit
) : View.OnTouchListener {

    private var downRawX = 0f
    private var downRawY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var gestureActive = false
    private var dragging = false

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                downRawX = event.rawX
                downRawY = event.rawY
                dragging = false
                gestureActive = true
                v.isPressed = true
                onGestureStart()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> return gestureActive

            MotionEvent.ACTION_MOVE -> {
                if (!gestureActive) return true
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return true

                val dx = (event.getRawX(index) - downRawX).toInt()
                val dy = (event.getRawY(index) - downRawY).toInt()

                if (!dragging && (abs(dx) > touchSlopPx || abs(dy) > touchSlopPx)) {
                    dragging = true
                }
                if (dragging) onDrag(dx, dy)
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // The pointer that started the gesture went up. End cleanly
                // instead of letting the follow-up ACTION_UP become a tap.
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    finish(v, cancelled = true)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                finish(v, cancelled = false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                finish(v, cancelled = true)
                return true
            }
        }
        return false
    }

    private fun finish(v: View, cancelled: Boolean) {
        if (!gestureActive) return
        v.isPressed = false
        onGestureEnd(dragging, cancelled)
        gestureActive = false
        dragging = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
    }
}
