package com.betterwhisper.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Module 2: Pure audio processing & real-time amplitude extraction.
 * No UI components. Streams 16 kHz mono float PCM blocks (50 ms / 800 samples)
 * and computes visualizer bar amplitudes.
 */
class AudioCaptureEngine {

    companion object {
        const val SAMPLE_RATE = 16000
        const val BLOCK_SIZE = 800 // 50 ms cadence required by Phonon stream
        const val NUM_BARS = 5
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _audioFlow = MutableSharedFlow<FloatArray>(extraBufferCapacity = 64)
    val audioFlow: SharedFlow<FloatArray> = _audioFlow

    private val _visualizerLevels = MutableStateFlow(FloatArray(NUM_BARS) { 0.1f })
    val visualizerLevels: StateFlow<FloatArray> = _visualizerLevels

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    @SuppressLint("MissingPermission")
    fun startCapture() {
        if (_isRecording.value) return

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        val bufferSize = max(minBufferSize, BLOCK_SIZE * 4)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord?.startRecording()
            _isRecording.value = true

            recordingJob = scope.launch {
                val block = FloatArray(BLOCK_SIZE)
                while (isActive && _isRecording.value) {
                    val read = audioRecord?.read(block, 0, BLOCK_SIZE, AudioRecord.READ_BLOCKING) ?: 0
                    if (read > 0) {
                        val pcmCopy = block.copyOf(read)
                        _audioFlow.emit(pcmCopy)
                        updateVisualizer(pcmCopy)
                    }
                }
            }
        } catch (e: Exception) {
            stopCapture()
        }
    }

    fun stopCapture() {
        _isRecording.value = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        // Reset visualizer bars
        _visualizerLevels.value = FloatArray(NUM_BARS) { 0.1f }
    }

    private fun updateVisualizer(samples: FloatArray) {
        if (samples.isEmpty()) return

        var sumSq = 0.0f
        for (sample in samples) {
            sumSq += sample * sample
        }
        val rms = sqrt(sumSq / samples.size)
        // Normalize RMS between 0.08 and 1.0
        val normalized = min(1.0f, max(0.08f, rms * 8.0f))

        val bars = FloatArray(NUM_BARS)
        for (i in 0 until NUM_BARS) {
            // Give slightly varied dynamic response across bars
            val factor = 0.7f + (0.3f * ((i % 3) / 2.0f))
            bars[i] = min(1.0f, normalized * factor)
        }
        _visualizerLevels.value = bars
    }
}
