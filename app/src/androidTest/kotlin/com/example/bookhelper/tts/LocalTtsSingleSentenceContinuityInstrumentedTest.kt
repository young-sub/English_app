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
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installer = BundledTtsModelInstaller(context)
        val engine = LocalModelTtsEngine()

        try {
            val modelPath = installer.ensureInstalled(BundledTtsModels.DefaultEnglish).getOrThrow()
            engine.setModelPath(modelPath)
            engine.setSpeakerId(BundledTtsModels.DefaultEnglish.defaultSpeakerId)

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
        } finally {
            engine.shutdown()
        }
    }
}
