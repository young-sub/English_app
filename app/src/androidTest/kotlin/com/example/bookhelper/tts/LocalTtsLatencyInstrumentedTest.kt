package com.example.bookhelper.tts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalTtsLatencyInstrumentedTest {
    @Test
    fun measureBundledModelLatency() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val installer = BundledTtsModelInstaller(context)

        val modelPathOverride = args.getString(ARG_MODEL_PATH)?.trim().orEmpty().ifBlank { null }
        val modelId = args.getString(ARG_MODEL_ID)?.trim().orEmpty().ifBlank { null }
        val speed = args.getString(ARG_SPEED)?.toFloatOrNull()?.coerceIn(0.85f, 1.15f) ?: DEFAULT_SPEED
        val numThreads = args.getString(ARG_NUM_THREADS)?.toIntOrNull()?.coerceIn(1, 4)
        val maxNumSentences = args.getString(ARG_MAX_NUM_SENTENCES)?.toIntOrNull()?.coerceAtLeast(1)
        val repeatCount = args.getString(ARG_REPEAT_COUNT)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val baseText = args.getString(ARG_TEXT)?.trim().orEmpty().ifBlank { DEFAULT_TEXT }
        val text = List(repeatCount) { baseText }.joinToString(" ")

        val modelPath = modelPathOverride ?: installer.ensureInstalled(resolveBundledModel(installer, modelId)).getOrThrow()

        val engine = LocalModelTtsEngine()
        try {
            engine.setModelPath(modelPath)
            engine.setNumThreads(numThreads)
            engine.setMaxNumSentences(maxNumSentences)

            val first = engine.benchmarkSynthesis(text = text, speed = speed).getOrThrow()
            val warm = engine.benchmarkSynthesis(text = text, speed = speed).getOrThrow()

            Log.i(
                TAG,
                "TTS_LATENCY modelPath=$modelPath modelId=${modelId ?: "default"} textLength=${text.length} repeatCount=$repeatCount speed=$speed numThreads=${numThreads ?: -1} maxNumSentences=${maxNumSentences ?: -1} first_gen_ms=${first.generationMillis} first_rtf=${first.realTimeFactor} warm_gen_ms=${warm.generationMillis} warm_rtf=${warm.realTimeFactor}",
            )

            assertTrue("First synthesis should generate audio", first.sampleCount > 0)
            assertTrue("Warm synthesis should generate audio", warm.sampleCount > 0)
        } finally {
            engine.shutdown()
        }
    }

    private fun resolveBundledModel(installer: BundledTtsModelInstaller, requestedModelId: String?): BundledTtsModel {
        if (requestedModelId.isNullOrBlank()) {
            return BundledTtsModels.DefaultEnglish
        }
        return installer.discoverBundledModels().firstOrNull { it.id.equals(requestedModelId, ignoreCase = true) }
            ?: BundledTtsModels.findById(requestedModelId)
            ?: error("Unknown bundled model id: $requestedModelId")
    }

    private companion object {
        const val TAG = "LocalTtsLatencyTest"
        const val ARG_MODEL_ID = "localTtsModelId"
        const val ARG_MODEL_PATH = "localTtsModelPath"
        const val ARG_TEXT = "localTtsText"
        const val ARG_SPEED = "localTtsSpeed"
        const val ARG_NUM_THREADS = "localTtsNumThreads"
        const val ARG_MAX_NUM_SENTENCES = "localTtsMaxNumSentences"
        const val ARG_REPEAT_COUNT = "localTtsRepeatCount"
        const val DEFAULT_SPEED = 1.0f
        const val DEFAULT_TEXT = "The quick brown fox jumps over the lazy dog."
    }
}
