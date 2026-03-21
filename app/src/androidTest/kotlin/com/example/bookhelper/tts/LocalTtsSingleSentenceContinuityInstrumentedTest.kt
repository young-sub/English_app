package com.example.bookhelper.tts

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalTtsSingleSentenceContinuityInstrumentedTest {
    @Test
    fun longSingleSentenceStartsBeforeGenerationFinishesWithoutLargeGaps() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val installer = BundledTtsModelInstaller(context)
        val engine = LocalModelTtsEngine()

        try {
            val model = resolveBundledModel(
                installer = installer,
                requestedModelId = args.getString(ARG_MODEL_ID)?.trim().orEmpty().ifBlank { DEFAULT_MODEL_ID },
            )
            val modelPath = installer.ensureInstalled(model).getOrThrow()
            engine.setModelPath(modelPath)
            engine.setSpeakerId(model.defaultSpeakerId)

            val accepted = engine.speakAsync(
                text = "This local TTS comparison sample uses exactly ten simple words.",
                speed = 1.0f,
            )
            assertTrue("Expected request acceptance", accepted)

            val deadline = SystemClock.elapsedRealtime() + 120_000L
            var telemetry = engine.latestPlaybackTelemetry()
            while (SystemClock.elapsedRealtime() <= deadline) {
                telemetry = engine.latestPlaybackTelemetry()
                if (telemetry.completed || telemetry.timedOut || telemetry.failureReason != null) {
                    break
                }
                SystemClock.sleep(50L)
            }

            assertTrue("Expected callback streaming mode. telemetry=$telemetry", telemetry.streamingMode == LocalStreamingMode.CALLBACK.name)
            assertTrue("Expected playback start before full generation finishes. telemetry=$telemetry", telemetry.playbackStartDelayMs in 1 until telemetry.generationMs.toLong())
            assertTrue("Expected no large callback gaps. telemetry=$telemetry", telemetry.maxStreamingGapMs < 500)
            assertTrue("Expected callback-path write telemetry. telemetry=$telemetry", telemetry.audioWriteFrames > 0)
            assertTrue("Expected callback-path generated sample telemetry. telemetry=$telemetry", telemetry.generatedSampleCount > 0)
        } finally {
            engine.shutdown()
        }
    }

    private fun resolveBundledModel(installer: BundledTtsModelInstaller, requestedModelId: String?): BundledTtsModel {
        if (requestedModelId.isNullOrBlank()) {
            return BundledTtsModels.PiperEnUsLibriTtsRMedium
        }
        return installer.discoverBundledModels().firstOrNull { it.id.equals(requestedModelId, ignoreCase = true) }
            ?: BundledTtsModels.findById(requestedModelId)
            ?: error("Unknown bundled model id: $requestedModelId")
    }

    private companion object {
        const val ARG_MODEL_ID = "localTtsModelId"
        const val DEFAULT_MODEL_ID = "piper-en_us-libritts_r-medium"
    }
}
