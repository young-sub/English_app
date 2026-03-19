package com.example.bookhelper.tts

import android.content.Context
import java.io.File

class LocalModelDirectoryImporter internal constructor(
    private val installRoot: File,
) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "tts-models"))

    fun importModel(model: BundledTtsModel, sourceDir: File): Result<String> {
        return runCatching {
            require(model.isDownloadedModel) { "Only downloaded/local directory models can be imported." }
            require(sourceDir.exists() && sourceDir.isDirectory) { "Source model directory does not exist: ${sourceDir.absolutePath}" }

            val destination = File(installRoot, model.id)
            if (destination.exists()) {
                destination.deleteRecursively()
            }
            destination.mkdirs()

            copyRequiredEntries(model, sourceDir, destination)
            DownloadedLocalModelRegistry.writeManifest(model, destination)
            destination.absolutePath
        }
    }

    private fun copyRequiredEntries(model: BundledTtsModel, sourceDir: File, destination: File) {
        model.requiredInstallFiles.forEach { relativePath ->
            val normalized = relativePath.removeSuffix("/")
            val source = File(sourceDir, normalized)
            val target = File(destination, normalized)
            require(source.exists()) { "Required model entry is missing: ${source.absolutePath}" }
            if (source.isDirectory) {
                copyDirectory(source, target)
            } else {
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
            }
        }
    }

    private fun copyDirectory(source: File, target: File) {
        source.walkTopDown().forEach { file ->
            val relative = file.relativeTo(source)
            val output = File(target, relative.path)
            if (file.isDirectory) {
                output.mkdirs()
            } else {
                output.parentFile?.mkdirs()
                file.copyTo(output, overwrite = true)
            }
        }
    }
}
