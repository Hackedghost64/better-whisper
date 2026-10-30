package com.betterwhisper.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Module 2: Pure audio capture & RMS visualizer calculation.
 * Zero UI components. Never stops automatically — user controls when dictation finishes.
 * Releases hardware microphone immediately when stopped for battery optimization.
 */
class AudioCaptureEngine {

    companion object {
        private const val TAG = "AudioCaptureEngine"
        const val SAMPLE_RATE = 16000
        const val BLOCK_SIZE = 800 // 50 ms at 16 kHz
        const val NUM_BARS = 5
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val pcmChunks = mutableListOf<FloatArray>()
    private val pcmLock = Any()

    private val _visualizerLevels = MutableStateFlow(FloatArray(NUM_BARS) { 0.12f })
    val visualizerLevels: StateFlow<FloatArray> = _visualizerLevels

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    @SuppressLint("MissingPermission")
    fun startCapture(): Boolean {
        if (_isRecording.value) return true

        synchronized(pcmLock) {
            pcmChunks.clear()
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) {
            Log.e(TAG, "Invalid minBufferSize: $minBufferSize")
            return false
        }

        val bufferSize = max(minBufferSize * 2, BLOCK_SIZE * 4)

        try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                record.release()
                return false
            }

            audioRecord = record
            record.startRecording()
            _isRecording.value = true
            Log.i(TAG, "Started 16kHz PCM audio capture (manual stop only)")

            recordingJob = scope.launch {
                val shortBlock = ShortArray(BLOCK_SIZE)

                while (isActive && _isRecording.value) {
                    val read = record.read(shortBlock, 0, BLOCK_SIZE)
                    if (read > 0) {
                        val floatBlock = FloatArray(read)
                        var sumSq = 0.0f
                        for (i in 0 until read) {
                            val sample = shortBlock[i] / 32768.0f
                            floatBlock[i] = sample
                            sumSq += sample * sample
                        }

                        synchronized(pcmLock) {
                            pcmChunks.add(floatBlock)
                        }

                        val rms = sqrt(sumSq / read)
                        updateVisualizer(floatBlock, rms)
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio capture: ${e.message}", e)
            stopAndGetPcm()
            return false
        }
    }

    /**
     * Stops microphone capture immediately (saving battery) and returns the recorded 16kHz float PCM buffer.
     */
    fun stopAndGetPcm(): FloatArray {
        _isRecording.value = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        _visualizerLevels.value = FloatArray(NUM_BARS) { 0.12f }

        synchronized(pcmLock) {
            val totalSamples = pcmChunks.sumOf { it.size }
            if (totalSamples == 0) return FloatArray(0)
            val result = FloatArray(totalSamples)
            var offset = 0
            for (chunk in pcmChunks) {
                System.arraycopy(chunk, 0, result, offset, chunk.size)
                offset += chunk.size
            }
            pcmChunks.clear()
            Log.i(TAG, "Stopped capture, returning $totalSamples samples (${totalSamples / 16000.0f}s)")
            return result
        }
    }

    private fun updateVisualizer(samples: FloatArray, rms: Float) {
        if (samples.isEmpty()) return
        val normalized = min(1.0f, max(0.10f, rms * 9.5f))
        val bars = FloatArray(NUM_BARS)
        val chunkLen = max(1, samples.size / NUM_BARS)

        for (b in 0 until NUM_BARS) {
            val start = b * chunkLen
            val end = min(samples.size, start + chunkLen)
            var bandSq = 0.0f
            for (i in start until end) {
                bandSq += samples[i] * samples[i]
            }
            val bandRms = if (end > start) sqrt(bandSq / (end - start)) else rms
            val centerBoost = if (b == 2) 1.25f else if (b == 1 || b == 3) 1.05f else 0.85f
            bars[b] = min(1.0f, max(0.12f, max(normalized * 0.6f, bandRms * 10.0f) * centerBoost))
        }
        _visualizerLevels.value = bars
    }
}
