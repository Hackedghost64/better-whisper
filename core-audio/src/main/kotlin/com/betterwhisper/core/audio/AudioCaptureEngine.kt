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
 * Zero UI components. Stops when the user asks for it, or after
 * [MAX_RECORDING_SECONDS] so a forgotten session cannot exhaust memory.
 * Releases the microphone immediately when stopped.
 */
class AudioCaptureEngine {

    companion object {
        private const val TAG = "AudioCaptureEngine"
        const val SAMPLE_RATE = 16000
        const val BLOCK_SIZE = 800 // 50 ms at 16 kHz
        const val NUM_BARS = 5
        const val MAX_RECORDING_SECONDS = 300
        const val MAX_SAMPLES = SAMPLE_RATE * MAX_RECORDING_SECONDS
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val pcmChunks = mutableListOf<FloatArray>()
    private val pcmLock = Any()
    private var capturedSamples = 0

    private val _visualizerLevels = MutableStateFlow(FloatArray(NUM_BARS) { 0.12f })
    val visualizerLevels: StateFlow<FloatArray> = _visualizerLevels

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    @SuppressLint("MissingPermission")
    fun startCapture(): Boolean {
        if (_isRecording.value) return true

        synchronized(pcmLock) {
            pcmChunks.clear()
            capturedSamples = 0
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
            Log.i(TAG, "Started 16kHz PCM audio capture")

            recordingJob = scope.launch {
                val shortBlock = ShortArray(BLOCK_SIZE)

                while (isActive && _isRecording.value) {
                    val read = record.read(shortBlock, 0, BLOCK_SIZE)
                    if (read < 0) {
                        Log.e(TAG, "AudioRecord.read failed: $read")
                        break
                    }
                    if (read == 0) continue

                    // Hard cap: keep at most five minutes of audio. Longer
                    // buffers are useless for a single utterance and would
                    // otherwise grow without bound.
                    val remaining = MAX_SAMPLES - capturedSamples
                    if (remaining <= 0) {
                        Log.w(TAG, "Reached ${MAX_RECORDING_SECONDS}s recording cap")
                        break
                    }
                    val usable = min(read, remaining)
                    val floatBlock = FloatArray(usable)
                    var sumSq = 0.0f
                    for (i in 0 until usable) {
                        val sample = shortBlock[i] / 32768.0f
                        floatBlock[i] = sample
                        sumSq += sample * sample
                    }

                    synchronized(pcmLock) {
                        pcmChunks.add(floatBlock)
                        capturedSamples += usable
                    }

                    val rms = sqrt(sumSq / usable)
                    updateVisualizer(floatBlock, rms)
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
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null

        _visualizerLevels.value = FloatArray(NUM_BARS) { 0.12f }

        synchronized(pcmLock) {
            val totalSamples = capturedSamples
            capturedSamples = 0
            if (totalSamples == 0) {
                pcmChunks.clear()
                return FloatArray(0)
            }
            val result = FloatArray(totalSamples)
            var offset = 0
            for (chunk in pcmChunks) {
                val n = min(chunk.size, totalSamples - offset)
                System.arraycopy(chunk, 0, result, offset, n)
                offset += n
                if (offset >= totalSamples) break
            }
            pcmChunks.clear()
            Log.i(TAG, "Stopped capture, returning $totalSamples samples (${totalSamples / 16000.0f}s)")
            return result
        }
    }

    /** Stops capture and releases the microphone without keeping the buffer. */
    fun release() {
        _isRecording.value = false
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        synchronized(pcmLock) {
            pcmChunks.clear()
            capturedSamples = 0
        }
        _visualizerLevels.value = FloatArray(NUM_BARS) { 0.12f }
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
