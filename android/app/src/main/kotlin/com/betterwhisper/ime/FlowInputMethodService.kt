package com.betterwhisper.ime

import android.inputmethodservice.InputMethodService
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import com.betterwhisper.engine.PhononEngine
import java.util.concurrent.Executors

class FlowInputMethodService : InputMethodService() {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val audioBuffer = ArrayList<Float>()

    private lateinit var micButton: View
    private lateinit var statusText: TextView

    companion object {
        private const val TAG = "FlowIME"
        private const val SAMPLE_RATE = 16000
    }

    override fun onCreateInputView(): View {
        val view = LayoutInflater.from(this).inflate(
            android.R.layout.simple_list_item_1, null
        ) // Placeholder layout root; custom layout bound below
        
        setupKeyboardUI(view)
        return view
    }

    private fun setupKeyboardUI(root: View) {
        // Handle push-to-talk touch events: press down to record, release to transcribe
        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startListening()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopListeningAndInject()
                    true
                }
                else -> false
            }
        }
    }

    private fun startListening() {
        if (isRecording) return
        isRecording = true
        audioBuffer.clear()

        executor.execute {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT
            )

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                    minBufferSize * 2
                )

                audioRecord?.startRecording()
                val chunk = FloatArray(480) // 30ms frames

                while (isRecording) {
                    val read = audioRecord?.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING) ?: 0
                    if (read > 0) {
                        synchronized(audioBuffer) {
                            for (i in 0 until read) {
                                audioBuffer.add(chunk[i])
                            }
                        }
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Audio recording permission denied: ${e.message}")
            } finally {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
            }
        }
    }

    private fun stopListeningAndInject() {
        if (!isRecording) return
        isRecording = false

        executor.execute {
            val samples: FloatArray
            synchronized(audioBuffer) {
                samples = FloatArray(audioBuffer.size)
                for (i in audioBuffer.indices) {
                    samples[i] = audioBuffer[i]
                }
            }

            if (samples.isEmpty()) return@execute

            // Extract context before cursor to intelligently decide capitalization and spacing
            val inputConnection = currentInputConnection
            val contextBefore = inputConnection?.getTextBeforeCursor(100, 0)?.toString()

            // Run ultra-fast Phonon-2 + Wispr Flow post-processing
            val cleanedText = PhononEngine.instance.transcribe(samples, contextBefore)

            if (cleanedText.isNotEmpty()) {
                mainHandler.post {
                    inputConnection?.commitText(cleanedText, 1)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        executor.shutdown()
    }
}
