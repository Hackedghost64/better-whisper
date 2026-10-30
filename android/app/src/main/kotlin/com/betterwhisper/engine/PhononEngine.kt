package com.betterwhisper.engine

import android.util.Log

class PhononEngine private constructor() {

    private var modelHandle: Long = 0
    private var isInitialized: Boolean = false

    companion object {
        private const val TAG = "PhononEngine"

        init {
            try {
                System.loadLibrary("phonon")
                System.loadLibrary("better_whisper_core")
                System.loadLibrary("phonon_jni")
                Log.i(TAG, "Native libraries loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native libraries: ${e.message}")
            }
        }

        val instance: PhononEngine by lazy { PhononEngine() }
    }

    /**
     * Initializes the Phonon-2 model using mmap for near-instant cold loads.
     */
    @Synchronized
    fun initialize(modelPath: String): Boolean {
        if (isInitialized) return true

        modelHandle = nativeInitModel(modelPath)
        isInitialized = (modelHandle != 0L)
        return isInitialized
    }

    /**
     * Transcribes 16 kHz float PCM audio and runs instant Wispr Flow post-processing.
     * @param pcmSamples 16 kHz float PCM audio array
     * @param contextBeforeCursor Text immediately preceding cursor in target app field
     * @return Cleaned, formatted speech text ready for injection
     */
    @Synchronized
    fun transcribe(pcmSamples: FloatArray, contextBeforeCursor: String? = null): String {
        if (!isInitialized || modelHandle == 0L) {
            Log.w(TAG, "Cannot transcribe: PhononEngine is not initialized")
            return ""
        }
        return nativeTranscribe(modelHandle, pcmSamples, pcmSamples.size, contextBeforeCursor)
    }

    @Synchronized
    fun release() {
        if (isInitialized && modelHandle != 0L) {
            nativeDestroyModel(modelHandle)
            modelHandle = 0
            isInitialized = false
        }
    }

    // Native JNI functions
    private external fun nativeInitModel(modelPath: String): Long
    private external fun nativeTranscribe(
        modelHandle: Long,
        pcmSamples: FloatArray,
        numSamples: Int,
        contextBeforeCursor: String?
    ): String
    private external fun nativeDestroyModel(modelHandle: Long)
}
