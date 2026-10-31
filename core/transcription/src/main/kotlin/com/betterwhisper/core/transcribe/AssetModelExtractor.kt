package com.betterwhisper.core.transcribe

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

data class ExtractedModels(
    val phononDir: File,
    val intentModelFile: File?
)

/**
 * Handles unpacking bundled assets (Phonon-2 speech weights and SmolLM2 SLM weights)
 * from APK assets into internal storage (`context.filesDir/models/`).
 *
 * Verifies existence and expected sizes to make repeated calls sub-millisecond.
 */
object AssetModelExtractor {
    private const val TAG = "AssetModelExtractor"

    private const val ASSET_PHONON_WEIGHTS = "models/phonon2/model.fermion"
    private const val ASSET_PHONON_CONFIG = "models/phonon2/config.json"
    private const val ASSET_INTENT_MODEL = "models/intent/smollm2-135m-q4_k_m.gguf"

    // Minimum size gates to detect truncated or placeholder files
    private const val MIN_PHONON_SIZE = 100_000_000L // ~170MB
    private const val MIN_INTENT_SIZE = 50_000_000L  // ~100MB

    @Synchronized
    fun ensureExtracted(context: Context): ExtractedModels {
        val modelsRoot = File(context.filesDir, "models")
        val phononDir = File(modelsRoot, "phonon2")
        val intentDir = File(modelsRoot, "intent")

        phononDir.mkdirs()
        intentDir.mkdirs()

        val phononWeights = File(phononDir, "model.fermion")
        val phononConfig = File(phononDir, "config.json")
        val intentModel = File(intentDir, "smollm2-135m-q4_k_m.gguf")

        // 1. Phonon-2 weights extraction
        if (!phononWeights.exists() || phononWeights.length() < MIN_PHONON_SIZE) {
            val extracted = extractAssetToFile(context, ASSET_PHONON_WEIGHTS, phononWeights)
            if (!extracted) {
                // Fallback to searching candidate dirs if asset not bundled in this build
                findExternalPhononDir(context)?.let { externalDir ->
                    val extWeights = File(externalDir, "model.fermion")
                    if (extWeights.exists() && extWeights.length() >= MIN_PHONON_SIZE) {
                        try {
                            extWeights.copyTo(phononWeights, overwrite = true)
                            Log.i(TAG, "Copied Phonon weights from external: ${externalDir.absolutePath}")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed copying from external: ${e.message}")
                        }
                    }
                }
            }
        }

        // 2. Phonon-2 config extraction
        if (!phononConfig.exists() || phononConfig.length() == 0L) {
            val extracted = extractAssetToFile(context, ASSET_PHONON_CONFIG, phononConfig)
            if (!extracted) {
                findExternalPhononDir(context)?.let { externalDir ->
                    val extCfg = File(externalDir, "config.json")
                    if (extCfg.exists()) {
                        try {
                            extCfg.copyTo(phononConfig, overwrite = true)
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        // 3. Intent SLM weights extraction
        if (!intentModel.exists() || intentModel.length() < MIN_INTENT_SIZE) {
            val extracted = extractAssetToFile(context, ASSET_INTENT_MODEL, intentModel)
            if (!extracted) {
                findExternalIntentFile()?.let { extFile ->
                    try {
                        extFile.copyTo(intentModel, overwrite = true)
                        Log.i(TAG, "Copied Intent SLM model from external: ${extFile.absolutePath}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed copying intent model: ${e.message}")
                    }
                }
            }
        }

        val resolvedPhononDir = if (phononWeights.exists() && phononWeights.length() >= MIN_PHONON_SIZE &&
            phononConfig.exists() && phononConfig.length() > 0) {
            phononDir
        } else {
            findExternalPhononDir(context) ?: phononDir
        }

        val resolvedIntentFile = if (intentModel.exists() && intentModel.length() >= MIN_INTENT_SIZE) {
            intentModel
        } else {
            findExternalIntentFile()
        }

        return ExtractedModels(
            phononDir = resolvedPhononDir,
            intentModelFile = resolvedIntentFile
        )
    }

    private fun extractAssetToFile(context: Context, assetPath: String, destFile: File): Boolean {
        var tempFile: File? = null
        return try {
            val startMs = System.currentTimeMillis()
            context.assets.open(assetPath).use { input ->
                val tmp = File(destFile.parentFile, "${destFile.name}.tmp")
                tempFile = tmp
                FileOutputStream(tmp).use { output ->
                    val buffer = ByteArray(1024 * 1024) // 1MB buffer
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }

                if (destFile.exists()) {
                    destFile.delete()
                }

                val renamed = tmp.renameTo(destFile)
                if (!renamed) {
                    tmp.copyTo(destFile, overwrite = true)
                    tmp.delete()
                }

                val elapsed = System.currentTimeMillis() - startMs
                Log.i(TAG, "Extracted $assetPath (${destFile.length()} bytes) in ${elapsed}ms")
            }
            true
        } catch (e: Exception) {
            tempFile?.let { if (it.exists()) it.delete() }
            Log.d(TAG, "Asset not found or failed to extract ($assetPath): ${e.message}")
            false
        }
    }

    private fun findExternalPhononDir(context: Context): File? {
        val candidates = listOfNotNull(
            File(context.filesDir, "phonon2"),
            context.getExternalFilesDir(null)?.let { File(it, "phonon2") },
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
        for (dir in candidates) {
            val weights = File(dir, "model.fermion")
            val cfg = File(dir, "config.json")
            if (weights.exists() && weights.length() >= MIN_PHONON_SIZE && cfg.exists()) {
                return dir
            }
        }
        return null
    }

    private fun findExternalIntentFile(): File? {
        val candidates = listOf(
            File("/data/local/tmp/intent/smollm2-135m-q4_k_m.gguf"),
            File("/sdcard/Download/smollm2-135m-q4_k_m.gguf"),
            File("/data/local/tmp/smollm2-135m-q4_k_m.gguf")
        )
        for (f in candidates) {
            if (f.exists() && f.length() >= MIN_INTENT_SIZE) {
                return f
            }
        }
        return null
    }
}
