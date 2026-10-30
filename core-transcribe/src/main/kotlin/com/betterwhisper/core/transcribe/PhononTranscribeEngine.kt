package com.betterwhisper.core.transcribe

data class StreamEvent(
    val kind: Int, // 1 = PARTIAL, 2 = FINAL (sentence completion)
    val text: String
) {
    val isFinal: Boolean get() = kind == 2
}

/**
 * Module 3: Pure transcription engine using official Fermion libphonon.so.
 * No UI components. Handles streaming audio feed and automatically detects
 * when a sentence is complete (PHONON_EVENT_FINAL).
 */
class PhononTranscribeEngine {

    companion object {
        init {
            System.loadLibrary("phonon")
            System.loadLibrary("transcribe_jni")
        }

        val instance by lazy { PhononTranscribeEngine() }
    }

    private var modelHandle: Long = 0
    private var activeStreamHandle: Long = 0

    val isModelLoaded: Boolean get() = modelHandle != 0L

    @Synchronized
    fun loadModel(modelDir: String): Boolean {
        if (modelHandle != 0L) return true
        modelHandle = nativeLoad(modelDir)
        return modelHandle != 0L
    }

    @Synchronized
    fun startStream(): Boolean {
        if (modelHandle == 0L) return false
        if (activeStreamHandle != 0L) {
            stopStream()
        }
        activeStreamHandle = nativeStreamOpen(modelHandle)
        return activeStreamHandle != 0L
    }

    @Synchronized
    fun feedAudio(pcm: FloatArray): StreamEvent? {
        if (activeStreamHandle == 0L || pcm.isEmpty()) return null
        return nativeStreamFeed(activeStreamHandle, pcm)
    }

    @Synchronized
    fun stopStream(): String {
        if (activeStreamHandle == 0L) return ""
        val text = nativeStreamClose(activeStreamHandle)
        nativeStreamFree(activeStreamHandle)
        activeStreamHandle = 0
        return text
    }

    @Synchronized
    fun transcribeBuffer(pcm: FloatArray): String {
        if (modelHandle == 0L || pcm.isEmpty()) return ""
        return nativeTranscribeBuffer(modelHandle, pcm)
    }

    @Synchronized
    fun release() {
        if (activeStreamHandle != 0L) {
            stopStream()
        }
        if (modelHandle != 0L) {
            nativeFree(modelHandle)
            modelHandle = 0
        }
    }

    // JNI Declarations
    private external fun nativeLoad(modelDir: String): Long
    private external fun nativeFree(modelHandle: Long)
    private external fun nativeStreamOpen(modelHandle: Long): Long
    private external fun nativeStreamFeed(streamHandle: Long, pcm: FloatArray): StreamEvent?
    private external fun nativeStreamClose(streamHandle: Long): String
    private external fun nativeStreamFree(streamHandle: Long)
    private external fun nativeTranscribeBuffer(modelHandle: Long, pcm: FloatArray): String
}
