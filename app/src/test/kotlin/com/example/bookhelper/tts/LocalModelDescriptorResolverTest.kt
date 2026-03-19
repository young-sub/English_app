package com.example.bookhelper.tts

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalModelDescriptorResolverTest {
    @Test
    fun resolveDetectsKokoroModelDirectory() {
        val modelDir = createModelDir(
            "kokoro-test",
            listOf("model.onnx", "voices.bin", "tokens.txt"),
        )

        val descriptor = LocalModelDescriptorResolver.resolve(modelDir.absolutePath)

        assertEquals(LocalTtsModelKind.KOKORO, descriptor.modelKind)
        assertEquals(LocalTtsModelFormat.KOKORO_ONNX, descriptor.modelFormat)
        assertEquals(File(modelDir, "voices.bin"), descriptor.voices)
    }

    @Test
    fun resolveDetectsPiperDerivedModelDirectoryWithoutVoicesBin() {
        val modelDir = createModelDir(
            "piper-test",
            listOf("model.onnx", "tokens.txt"),
        )

        val descriptor = LocalModelDescriptorResolver.resolve(modelDir.absolutePath)

        assertEquals(LocalTtsModelKind.PIPER_DERIVED, descriptor.modelKind)
        assertEquals(LocalTtsModelFormat.PIPER_ONNX, descriptor.modelFormat)
        assertNull(descriptor.voices)
    }

    @Test(expected = IllegalStateException::class)
    fun resolveFailsWhenRequiredFilesAreMissing() {
        val modelDir = createModelDir(
            "broken-test",
            listOf("model.onnx"),
        )

        LocalModelDescriptorResolver.resolve(modelDir.absolutePath)
    }

    private fun createModelDir(name: String, files: List<String>): File {
        val root = createTempDirectory(name).toFile()
        files.forEach { relativePath ->
            val file = File(root, relativePath)
            file.parentFile?.mkdirs()
            file.writeText("placeholder")
        }
        File(root, "espeak-ng-data").mkdirs()
        return root
    }
}
