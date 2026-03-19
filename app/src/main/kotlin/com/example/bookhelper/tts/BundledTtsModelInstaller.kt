package com.example.bookhelper.tts

import android.content.Context
import java.io.File

class BundledTtsModelInstaller(context: Context) {
    private val appContext = context.applicationContext

    fun discoverBundledModels(): List<BundledTtsModel> {
        val knownByDirName = BundledTtsModels.All.associateBy { it.assetDirectory.substringAfter("${BundledTtsModels.AssetRoot}/") }
        val assetDirs = runCatching { appContext.assets.list(BundledTtsModels.AssetRoot).orEmpty().toList() }
            .getOrDefault(emptyList())
            .sorted()

        if (assetDirs.isEmpty()) {
            return BundledTtsModels.All
        }

        return assetDirs.map { dirName ->
            val model = knownByDirName[dirName] ?: createGenericModel(dirName)
            val speakers = BundledSpeakerManifest.loadFromAssets(appContext.assets, model.assetDirectory)
            if (speakers.isEmpty()) model else model.copy(speakers = speakers)
        }
    }

    fun ensureInstalled(model: BundledTtsModel): Result<String> {
        return runCatching {
            val installRoot = installRootFor(model)
            if (isModelReady(installRoot, model)) {
                return@runCatching installRoot.absolutePath
            }

            if (!hasAssets(model.assetDirectory)) {
                error("Bundled model asset is missing: ${model.assetDirectory}")
            }

            if (installRoot.exists()) {
                installRoot.deleteRecursively()
            }
            installRoot.mkdirs()
            copyAssetTree(model.assetDirectory, installRoot)

            if (!isModelReady(installRoot, model)) {
                error(
                    "Bundled local model is incomplete (required: ${model.requiredInstallFiles.joinToString(", ")})",
                )
            }

            installRoot.absolutePath
        }
    }

    fun resolveInstalledModelPath(model: BundledTtsModel): String? {
        val installRoot = installRootFor(model)
        return if (isModelReady(installRoot, model)) installRoot.absolutePath else null
    }

    fun installRootFor(model: BundledTtsModel): File {
        return File(appContext.filesDir, "tts-models/${model.id}")
    }

    private fun hasAssets(assetPath: String): Boolean {
        val children = runCatching { appContext.assets.list(assetPath) }.getOrNull() ?: return false
        return children.isNotEmpty()
    }

    private fun createGenericModel(directoryName: String): BundledTtsModel {
        val id = directoryName.lowercase().replace('_', '-')
        val words = directoryName
            .replace('_', ' ')
            .replace('-', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .map { token -> token.replaceFirstChar { char -> char.uppercase() } }
        val displayName = if (words.isEmpty()) directoryName else words.joinToString(" ")

        return BundledTtsModel(
            id = id,
            displayName = displayName,
            shortLabel = words.firstOrNull() ?: "Model",
            assetDirectory = "${BundledTtsModels.AssetRoot}/$directoryName",
            speakers = listOf(
                LocalSpeakerProfile(
                    id = 0,
                    code = "default",
                    displayLabel = "Default",
                    gender = SpeakerGender.UNKNOWN,
                    accentLabel = "General",
                ),
            ),
        )
    }

    private fun isModelReady(modelDir: File, model: BundledTtsModel): Boolean {
        if (!modelDir.exists() || !modelDir.isDirectory) {
            return false
        }
        val hasModel = File(modelDir, "model.onnx").exists()
        val hasTokens = File(modelDir, "tokens.txt").exists()
        val hasEspeakData = File(modelDir, "espeak-ng-data").isDirectory
        val hasVoices = File(modelDir, "voices.bin").exists()
        return when (model.modelKind) {
            LocalTtsModelKind.KOKORO -> hasModel && hasVoices && hasTokens && hasEspeakData
            LocalTtsModelKind.PIPER_DERIVED -> hasModel && hasTokens && hasEspeakData
        }
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val children = appContext.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            appContext.assets.open(assetPath).use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return
        }

        target.mkdirs()
        children.forEach { child ->
            val sourceChild = "$assetPath/$child"
            val targetChild = File(target, child)
            copyAssetTree(sourceChild, targetChild)
        }
    }
}
