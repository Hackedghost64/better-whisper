package com.betterwhisper.core.transcribe

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Module 3: Pure transcription engine using official Fermion libphonon.so.
 * Zero UI components. Follows OpenWhispr's LocalParakeetService serialized singleton pattern.
 */
class PhononTranscribeEngine {

    companion object {
        private const val TAG = "PhononTranscribeEngine"

        init {
            try {
                System.loadLibrary("phonon")
                System.loadLibrary("transcribe_jni")
                Log.i(TAG, "Loaded native libphonon.so + libtranscribe_jni.so")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load Phonon JNI libraries: ${t.message}", t)
            }
        }

        val instance by lazy { PhononTranscribeEngine() }
    }

    private var modelHandle: Long = 0

    private val _isLoadedFlow = MutableStateFlow(false)
    val isLoadedFlow: StateFlow<Boolean> = _isLoadedFlow

    val isModelLoaded: Boolean get() = modelHandle != 0L

    /**
     * Ensures the Phonon-2 model is loaded from app-private storage.
     *
     * Only directories owned by the app are searched: `/sdcard/Download` and
     * `/data/local/tmp` are world-writable, so loading weights from there would
     * let another app swap in a malicious model.
     */
    @Synchronized
    fun ensureLoaded(context: Context): Boolean {
        if (modelHandle != 0L) return true

        val candidateDirs = listOfNotNull(
            File(context.filesDir, "phonon2"),
            context.getExternalFilesDir(null)?.let { File(it, "phonon2") }
        )

        for (dir in candidateDirs) {
            val cfg = File(dir, "config.json")
            val weights = File(dir, "model.fermion")
            if (cfg.exists() && weights.exists()) {
                Log.i(TAG, "Found Phonon-2 weights at ${dir.absolutePath}")
                modelHandle = nativeLoad(dir.absolutePath)
                if (modelHandle != 0L) {
                    _isLoadedFlow.value = true
                    return true
                }
            }
        }
        Log.e(TAG, "Could not find Phonon-2 weights in any candidate directory")
        return false
    }

    @Synchronized
    fun loadModel(modelDir: String): Boolean {
        if (modelHandle != 0L) return true
        modelHandle = nativeLoad(modelDir)
        _isLoadedFlow.value = (modelHandle != 0L)
        return modelHandle != 0L
    }

    /**
     * Runs a single fast batch decode over the utterance buffer (~80-200ms).
     */
    @Synchronized
    fun transcribeBuffer(pcm: FloatArray): String {
        if (modelHandle == 0L || pcm.isEmpty()) {
            Log.w(TAG, "transcribeBuffer skipped: modelHandle=$modelHandle, pcmSize=${pcm.size}")
            return ""
        }
        return try {
            nativeTranscribeBuffer(modelHandle, pcm)
        } catch (t: Throwable) {
            Log.e(TAG, "nativeTranscribeBuffer failed: ${t.message}", t)
            ""
        }
    }

    @Synchronized
    fun release() {
        if (modelHandle != 0L) {
            nativeFree(modelHandle)
            modelHandle = 0
            _isLoadedFlow.value = false
        }
    }

    // JNI Declarations
    private external fun nativeLoad(modelDir: String): Long
    private external fun nativeFree(modelHandle: Long)
    private external fun nativeTranscribeBuffer(modelHandle: Long, pcm: FloatArray): String
}
