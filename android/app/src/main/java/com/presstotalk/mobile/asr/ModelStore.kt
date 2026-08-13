package com.presstotalk.mobile.asr

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Locates the Whisper and VAD model files, extracting them from assets when the
 * build bundles them.
 *
 * Models are always handed to sherpa-onnx as filesystem paths, never through an
 * AssetManager. The asset path reads the entire model into a heap buffer on top
 * of ONNX Runtime's own copy - a ~2x spike that is ~750 MB for the small model.
 * Loading from a path lets ONNX Runtime map the file instead.
 */
class ModelStore(private val context: Context) {

    data class ModelPaths(
        val name: String,
        val encoder: File,
        val decoder: File,
        val tokens: File,
        val vad: File,
    )

    class ModelMissingException(message: String) : Exception(message)

    private val internalDir: File get() = File(context.filesDir, MODELS_DIR)

    /** `adb push` target - writable without root, unlike filesDir. */
    private val externalDir: File? get() = context.getExternalFilesDir(null)?.let { File(it, MODELS_DIR) }

    /**
     * Returns usable paths for [modelName], extracting from assets on first run
     * if this build bundles them.
     *
     * @throws ModelMissingException with a message naming what to do about it.
     */
    fun prepare(modelName: String): ModelPaths {
        if (bundlesModel(modelName)) {
            extractFromAssets(modelName)
        }

        val searched = mutableListOf<File>()
        for (root in listOfNotNull(internalDir, externalDir)) {
            searched += root
            val paths = pathsIn(root, modelName)
            if (paths != null) {
                Log.i(TAG, "Using model '$modelName' from ${root.absolutePath}")
                return paths
            }
            // Without this, a missing or misnamed file is invisible: the UI just
            // says "not found" and the log says nothing at all.
            Log.w(TAG, "No usable '$modelName' under ${root.absolutePath}: ${describe(root, modelName)}")
        }

        throw ModelMissingException(
            buildString {
                append("Model '$modelName' not found. Looked in:\n")
                searched.forEach { append("  ").append(it.absolutePath).append('\n') }
                append("\nPush one with: scripts/push-model.sh $modelName")
            },
        )
    }

    /** Non-throwing probe, for the UI to show a useful empty state. */
    fun isAvailable(modelName: String): Boolean = runCatching { prepare(modelName) }.isSuccess

    /** Per-file readability, so the log says which file is the problem. */
    private fun describe(root: File, modelName: String): String =
        expectedFiles(root, modelName).joinToString(", ") { file ->
            when {
                !file.exists() -> "${file.name}=absent"
                !file.canRead() -> "${file.name}=unreadable"
                file.length() == 0L -> "${file.name}=empty"
                else -> "${file.name}=ok(${file.length()})"
            }
        }

    private fun expectedFiles(root: File, modelName: String): List<File> = listOf(
        File(root, "$modelName/$modelName-encoder.int8.onnx"),
        File(root, "$modelName/$modelName-decoder.int8.onnx"),
        File(root, "$modelName/$modelName-tokens.txt"),
        File(root, VAD_FILE),
    )

    private fun pathsIn(root: File, modelName: String): ModelPaths? {
        val encoder = File(root, "$modelName/$modelName-encoder.int8.onnx")
        val decoder = File(root, "$modelName/$modelName-decoder.int8.onnx")
        val tokens = File(root, "$modelName/$modelName-tokens.txt")
        val vad = File(root, VAD_FILE)

        val present = listOf(encoder, decoder, tokens, vad).all { it.isFile && it.length() > 0 }
        return if (present) ModelPaths(modelName, encoder, decoder, tokens, vad) else null
    }

    private fun bundlesModel(modelName: String): Boolean =
        runCatching { context.assets.list("$MODELS_DIR/$modelName")?.isNotEmpty() == true }
            .getOrDefault(false)

    /**
     * Copies bundled assets to filesDir once. A marker file records what was
     * extracted so later launches skip the copy, and so swapping the bundled
     * model in a new build forces a re-extract rather than silently using the old one.
     */
    private fun extractFromAssets(modelName: String) {
        val marker = File(internalDir, MARKER_FILE)
        val expected = "$modelName@${assetFingerprint(modelName)}"

        if (marker.isFile && marker.readText() == expected && pathsIn(internalDir, modelName) != null) {
            return
        }

        Log.i(TAG, "Extracting bundled model '$modelName' to ${internalDir.absolutePath}")
        internalDir.mkdirs()

        copyAsset("$MODELS_DIR/$VAD_FILE", File(internalDir, VAD_FILE))
        val modelDir = File(internalDir, modelName).apply { mkdirs() }
        context.assets.list("$MODELS_DIR/$modelName").orEmpty().forEach { entry ->
            copyAsset("$MODELS_DIR/$modelName/$entry", File(modelDir, entry))
        }

        marker.writeText(expected)
        Log.i(TAG, "Extraction complete")
    }

    /**
     * Cheap identity for the bundled files. Assets are stored uncompressed
     * (`noCompress "onnx"`), so available() is the real size rather than a
     * decompression-dependent guess.
     */
    private fun assetFingerprint(modelName: String): String =
        context.assets.list("$MODELS_DIR/$modelName").orEmpty().sorted().joinToString(",") { entry ->
            val size = context.assets.open("$MODELS_DIR/$modelName/$entry").use { it.available() }
            "$entry:$size"
        }

    private fun copyAsset(assetPath: String, destination: File) {
        context.assets.open(assetPath).use { input ->
            destination.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        }
    }

    private companion object {
        const val TAG = "ModelStore"
        const val MODELS_DIR = "models"
        const val VAD_FILE = "silero_vad.onnx"
        const val MARKER_FILE = "extracted.version"
    }
}
