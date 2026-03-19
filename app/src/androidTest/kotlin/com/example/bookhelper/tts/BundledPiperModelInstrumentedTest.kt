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
    fun bundledPiperModelIsDiscoverableWithSpeakerPresets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installer = BundledTtsModelInstaller(context)

        val model = installer.discoverBundledModels().first { it.id == BundledTtsModels.PiperEnUsLibriTtsRMedium.id }

        assertEquals(LocalTtsModelKind.PIPER_DERIVED, model.modelKind)
        assertTrue(model.speakers.size > 1)
        assertEquals(0, model.speakers.first().id)
        assertEquals(10, model.speakers[10].id)
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
