package com.betterwhisper.feature.bubble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class DictationEvent(
    val rawText: String,
    val cleanedText: String,
    val decodeMs: Long,
    val injected: Boolean = true,
    val targetPackage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

object DictationEventBus {
    private val _latestEvent = MutableStateFlow<DictationEvent?>(null)
    val latestEvent: StateFlow<DictationEvent?> = _latestEvent

    fun emit(event: DictationEvent) {
        _latestEvent.value = event
    }
}
