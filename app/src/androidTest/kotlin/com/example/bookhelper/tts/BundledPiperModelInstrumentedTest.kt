package com.example.bookhelper.tts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledPiperModelInstrumentedTest {
    @Test
    fun bundledPiperCatalogIncludesFastDefaultAndOptionalMultiSpeakerModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installer = BundledTtsModelInstaller(context)

        val discoveredIds = installer.discoverBundledModels().map { it.id }.toSet()

        assertTrue(discoveredIds.contains(BundledTtsModels.PiperEnUsLessacLow.id))
        assertTrue(discoveredIds.contains(BundledTtsModels.PiperEnUsLibriTtsRMedium.id))
    }

    @Test
    fun bundledPiperModelIsDiscoverableWithSpeakerPresets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installer = BundledTtsModelInstaller(context)

        val model = installer.discoverBundledModels().first { it.id == BundledTtsModels.PiperEnUsLibriTtsRMedium.id }

        assertEquals(LocalTtsModelKind.PIPER_DERIVED, model.modelKind)
        assertEquals(10, model.speakers.size)
        assertEquals(0, model.speakers.first().id)
        assertTrue(model.speakers.last().id > model.speakers.first().id)
    }

    @Test
    fun bundledPiperModelInstallsAsSherpaCompatibleLayout() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installer = BundledTtsModelInstaller(context)

        val modelPath = installer.ensureInstalled(BundledTtsModels.PiperEnUsLibriTtsRMedium).getOrThrow()
        val modelDir = File(modelPath)

        assertTrue(File(modelDir, "model.onnx").exists())
        assertTrue(File(modelDir, "tokens.txt").exists())
        assertTrue(File(modelDir, "espeak-ng-data").isDirectory)
        assertTrue(File(modelDir, BundledSpeakerManifest.FILE_NAME).exists())
    }
}
