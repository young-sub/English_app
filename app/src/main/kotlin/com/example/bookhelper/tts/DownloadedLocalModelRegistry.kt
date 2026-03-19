package com.example.bookhelper.tts

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties

object DownloadedLocalModelRegistry {
    const val MANIFEST_FILE_NAME = "local-model.properties"

    fun discoverInstalledModels(rootDir: File): List<BundledTtsModel> {
        if (!rootDir.exists() || !rootDir.isDirectory) {
            return emptyList()
        }
        return rootDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { dir ->
                val manifest = File(dir, MANIFEST_FILE_NAME)
                if (!manifest.exists()) {
                    return@mapNotNull null
                }
                readManifest(manifest.inputStream())
            }
    }

    fun writeManifest(model: BundledTtsModel, installDir: File) {
        val manifest = File(installDir, MANIFEST_FILE_NAME)
        manifest.parentFile?.mkdirs()
        manifest.outputStream().use { output ->
            writeManifest(model, output)
        }
    }

    private fun writeManifest(model: BundledTtsModel, outputStream: OutputStream) {
        Properties().apply {
            setProperty("id", model.id)
            setProperty("displayName", model.displayName)
            setProperty("shortLabel", model.shortLabel)
            setProperty("modelKind", model.modelKind.name)
            setProperty("modelFormat", model.modelFormat.name)
        }.store(outputStream, null)
    }

    private fun readManifest(inputStream: InputStream): BundledTtsModel? {
        val properties = Properties().apply {
            inputStream.use(::load)
        }
        val id = properties.getProperty("id")?.takeIf { it.isNotBlank() } ?: return null
        val displayName = properties.getProperty("displayName")?.takeIf { it.isNotBlank() } ?: id
        val shortLabel = properties.getProperty("shortLabel")?.takeIf { it.isNotBlank() } ?: displayName
        val modelKind = runCatching {
            LocalTtsModelKind.valueOf(properties.getProperty("modelKind").orEmpty())
        }.getOrNull() ?: return null
        val modelFormat = runCatching {
            LocalTtsModelFormat.valueOf(properties.getProperty("modelFormat").orEmpty())
        }.getOrNull() ?: return null

        return BundledTtsModel(
            id = id,
            displayName = displayName,
            shortLabel = shortLabel,
            assetDirectory = "",
            speakers = listOf(LocalSpeakerProfile(0, "default", "Default", SpeakerGender.UNKNOWN, "General")),
            modelKind = modelKind,
            modelFormat = modelFormat,
        )
    }
}
