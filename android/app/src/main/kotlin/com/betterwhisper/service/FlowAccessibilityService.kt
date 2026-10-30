package com.betterwhisper.service

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.betterwhisper.engine.PhononEngine

class FlowAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Track focus changes if needed
    }

    override fun onInterrupt() {
        // Handle interruption
    }

    /**
     * Injects cleaned text into whatever input field is currently active on the user's screen.
     */
    fun injectTranscribedText(text: String) {
        val rootNode = rootInActiveWindow ?: return
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return

        if (focusedNode.isEditable) {
            val existingText = focusedNode.text?.toString() ?: ""
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    existingText + text
                )
            }
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }

        focusedNode.recycle()
        rootNode.recycle()
    }
}
