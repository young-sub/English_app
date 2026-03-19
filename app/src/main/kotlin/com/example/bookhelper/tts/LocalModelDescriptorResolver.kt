package com.example.bookhelper.tts

import java.io.File

internal data class LocalModelDescriptor(
    val modelKind: LocalTtsModelKind,
    val modelFormat: LocalTtsModelFormat,
    val modelDir: File,
    val model: File,
    val tokens: File,
    val dataDir: File,
    val voices: File? = null,
    val lexicon: File? = null,
    val dictDir: File? = null,
)

internal object LocalModelDescriptorResolver {
    fun resolve(path: String): LocalModelDescriptor {
        val file = File(path)
        val modelDir = if (file.isDirectory) file else file.parentFile
            ?: throw IllegalArgumentException("Model path is invalid.")

        val model = File(modelDir, "model.onnx")
        val tokens = File(modelDir, "tokens.txt")
        val dataDir = File(modelDir, "espeak-ng-data")
        val voices = File(modelDir, "voices.bin").takeIf { it.exists() }
        val lexicon = File(modelDir, "lexicon.txt").takeIf { it.exists() }
        val dictDir = File(modelDir, "dict").takeIf { it.isDirectory }

        val missing = buildList {
            if (!model.exists()) add("model.onnx")
            if (!tokens.exists()) add("tokens.txt")
            if (!dataDir.exists() || !dataDir.isDirectory) add("espeak-ng-data/")
            if (voices != null) {
                // Kokoro-specific validation already covered by voices.bin existing.
            }
        }
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "Local model is incomplete at ${modelDir.absolutePath}. Missing: ${missing.joinToString(", ")}",
            )
        }

        return if (voices != null) {
            LocalModelDescriptor(
                modelKind = LocalTtsModelKind.KOKORO,
                modelFormat = LocalTtsModelFormat.KOKORO_ONNX,
                modelDir = modelDir,
                model = model,
                tokens = tokens,
                dataDir = dataDir,
                voices = voices,
            )
        } else {
            LocalModelDescriptor(
                modelKind = LocalTtsModelKind.PIPER_DERIVED,
                modelFormat = LocalTtsModelFormat.PIPER_ONNX,
                modelDir = modelDir,
                model = model,
                tokens = tokens,
                dataDir = dataDir,
                lexicon = lexicon,
                dictDir = dictDir,
            )
        }
    }
}
