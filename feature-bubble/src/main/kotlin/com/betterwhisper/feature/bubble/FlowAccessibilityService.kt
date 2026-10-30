package com.betterwhisper.feature.bubble

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.betterwhisper.core.cleaner.TranscriptCleaner

/**
 * Module 1: System-wide Accessibility Service.
 * Detects when an editable text field is clicked or focused,
 * shows the Wispr Flow bubble, and injects cleaned text into any app.
 */
class FlowAccessibilityService : AccessibilityService() {

    private var overlay: FlowBubbleOverlay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = FlowBubbleOverlay(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
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
            overlay?.show()
        } else {
            overlay?.hide()
        }
    }

    fun injectTextIntoFocusedField(rawText: String) {
        val root = rootInActiveWindow ?: return
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return

        if (focused.isEditable) {
            val existingText = focused.text?.toString() ?: ""
            // Module 4: Run instant transcript cleaner (<1.5 ms) with context-aware casing
            val cleanedText = TranscriptCleaner.default.clean(
                rawText = rawText,
                contextBeforeCursor = existingText
            )

            val fullText = existingText + cleanedText
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    fullText
                )
            }
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        overlay?.onDestroy()
        overlay = null
    }
}
