package com.example.bookhelper.tts

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelDirectoryImporterTest {
    @Test
    fun importModelCopiesPiperFilesAndWritesManifest() {
        val sourceRoot = createTempDirectory("piper-source").toFile()
        File(sourceRoot, "model.onnx").writeText("onnx")
        File(sourceRoot, "tokens.txt").writeText("tokens")
        File(sourceRoot, "espeak-ng-data").mkdirs()

        val installRoot = createTempDirectory("piper-install").toFile()
        val importer = LocalModelDirectoryImporter(installRoot)
        val model = BundledTtsModel(
            id = "piper-en-us-libritts-r-medium",
            displayName = "Piper LibriTTS-R Medium",
            shortLabel = "LibriTTS-R",
            assetDirectory = "",
            speakers = listOf(LocalSpeakerProfile(0, "default", "Default", SpeakerGender.UNKNOWN, "General")),
            modelKind = LocalTtsModelKind.PIPER_DERIVED,
            modelFormat = LocalTtsModelFormat.PIPER_ONNX,
        )

        val installedPath = importer.importModel(model, sourceRoot).getOrThrow()
        val installedDir = File(installedPath)

        assertTrue(File(installedDir, "model.onnx").exists())
        assertTrue(File(installedDir, "tokens.txt").exists())
        assertTrue(File(installedDir, "espeak-ng-data").isDirectory)
        assertTrue(File(installedDir, DownloadedLocalModelRegistry.MANIFEST_FILE_NAME).exists())
        assertEquals(model.id, DownloadedLocalModelRegistry.discoverInstalledModels(installRoot).single().id)
    }
}
