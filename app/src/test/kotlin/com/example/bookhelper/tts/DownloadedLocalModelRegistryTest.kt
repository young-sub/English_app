package com.example.bookhelper.tts

import java.io.File
import java.util.Properties
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedLocalModelRegistryTest {
    @Test
    fun discoverInstalledModelsReturnsPiperManifestEntries() {
        val root = createTempDirectory("downloaded-models").toFile()
        val installDir = File(root, "piper-amy")
        installDir.mkdirs()
        File(installDir, "model.onnx").writeText("onnx")
        File(installDir, "tokens.txt").writeText("tokens")
        File(installDir, "espeak-ng-data").mkdirs()

        Properties().apply {
            setProperty("id", "piper-en-us-amy-low")
            setProperty("displayName", "Piper Amy Low")
            setProperty("shortLabel", "Amy")
            setProperty("modelKind", LocalTtsModelKind.PIPER_DERIVED.name)
            setProperty("modelFormat", LocalTtsModelFormat.PIPER_ONNX.name)
        }.store(File(installDir, DownloadedLocalModelRegistry.MANIFEST_FILE_NAME).outputStream(), null)

        val models = DownloadedLocalModelRegistry.discoverInstalledModels(root)

        assertEquals(1, models.size)
        assertEquals("piper-en-us-amy-low", models.first().id)
        assertEquals(LocalTtsModelKind.PIPER_DERIVED, models.first().modelKind)
    }

    @Test
    fun writeManifestCreatesDiscoverableEntry() {
        val root = createTempDirectory("manifest-write").toFile()
        val installDir = File(root, "piper-amy")
        installDir.mkdirs()

        val model = BundledTtsModel(
            id = "piper-en-us-amy-low",
            displayName = "Piper Amy Low",
            shortLabel = "Amy",
            assetDirectory = "",
            speakers = listOf(LocalSpeakerProfile(0, "default", "Default", SpeakerGender.UNKNOWN, "General")),
            modelKind = LocalTtsModelKind.PIPER_DERIVED,
            modelFormat = LocalTtsModelFormat.PIPER_ONNX,
        )

        DownloadedLocalModelRegistry.writeManifest(model, installDir)

        val manifest = File(installDir, DownloadedLocalModelRegistry.MANIFEST_FILE_NAME)
        assertTrue(manifest.exists())
        assertEquals(model.id, DownloadedLocalModelRegistry.discoverInstalledModels(root).single().id)
    }
}
