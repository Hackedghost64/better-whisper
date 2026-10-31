package com.betterwhisper.core.transcribe

import android.content.Context
import android.os.Environment
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
     * Ensures the Phonon-2 model is loaded from app storage, external storage,
     * or fallback system directories (/data/local/tmp/phonon2, /sdcard/Download/phonon2).
     */
    @Synchronized
    fun ensureLoaded(context: Context): Boolean {
        if (modelHandle != 0L) return true

        val privateDir = File(context.filesDir, "phonon2")

        // Unpack bundled Phonon-2 assets into private storage if needed
        val privateWeights = File(privateDir, "model.fermion")
        if (!privateWeights.exists() || privateWeights.length() < 100_000_000L) {
            try {
                val assetList = context.assets.list("models/phonon2")
                if (assetList != null && assetList.contains("model.fermion")) {
                    privateDir.mkdirs()
                    context.assets.open("models/phonon2/model.fermion").use { input ->
                        File(privateDir, "model.fermion").outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    context.assets.open("models/phonon2/config.json").use { input ->
                        File(privateDir, "config.json").outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.i(TAG, "Successfully extracted bundled Phonon-2 weights to ${privateDir.absolutePath}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not extract Phonon-2 assets: ${e.message}")
            }
        }

        val candidateDirs = listOfNotNull(
            privateDir,
            context.getExternalFilesDir(null)?.let { File(it, "phonon2") },
            File(context.filesDir, "models/phonon2"),
            File("/data/local/tmp/phonon2"),
            File("/sdcard/Download/phonon2"),
            try {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let {
                    File(it, "phonon2")
                }
            } catch (_: Throwable) {
                null
            }
        )

        for (dir in candidateDirs) {
            val cfg = File(dir, "config.json")
            val weights = File(dir, "model.fermion")
            if (cfg.exists() && weights.exists() && weights.length() > 1000) {
                Log.i(TAG, "Found Phonon-2 weights at ${dir.absolutePath} (${weights.length()} bytes)")

                // If found outside private storage and private dir doesn't have it, copy for faster/safer future loads
                if (dir.absolutePath != privateDir.absolutePath && !File(privateDir, "model.fermion").exists()) {
                    try {
                        privateDir.mkdirs()
                        cfg.copyTo(File(privateDir, "config.json"), overwrite = true)
                        weights.copyTo(File(privateDir, "model.fermion"), overwrite = true)
                        Log.i(TAG, "Mirrored Phonon-2 weights to private storage: ${privateDir.absolutePath}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not mirror weights to privateDir: ${e.message}")
                    }
                }

                val loadPath = if (File(privateDir, "model.fermion").exists()) privateDir.absolutePath else dir.absolutePath
                modelHandle = nativeLoad(loadPath)
                if (modelHandle != 0L) {
                    _isLoadedFlow.value = true
                    Log.i(TAG, "Successfully initialized Phonon-2 handle from $loadPath")
                    return true
                }
            }
        }
        Log.e(TAG, "Could not find Phonon-2 weights in any candidate directory: ${candidateDirs.map { it.absolutePath }}")
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
