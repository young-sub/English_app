package com.example.bookhelper.tts

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalTtsStandaloneProbeInstrumentedTest {
    @Test
    fun runStandaloneProbeFromInstrumentationArgs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val enabled = args.getString(ARG_ENABLED)?.equals("true", ignoreCase = true) ?: false
        assumeTrue(
            "Standalone probe is disabled by default. Set -e $ARG_ENABLED true to run this test.",
            enabled,
        )

        val rawText = args.getString(ARG_TEXT)?.trim()
        val text = if (rawText.isNullOrBlank()) DEFAULT_TEXT else rawText
        val speed = args.getString(ARG_SPEED)?.toFloatOrNull()?.coerceIn(0.85f, 1.15f)
            ?: DEFAULT_SPEED
        val timeoutMs = args.getString(ARG_TIMEOUT_MS)?.toLongOrNull()?.coerceAtLeast(1_000L)
            ?: DEFAULT_TIMEOUT_MS

        val installer = BundledTtsModelInstaller(context)
        val requestedModelId = args.getString(ARG_MODEL_ID)?.trim().orEmpty().ifBlank { null }
        val modelPathOverride = args.getString(ARG_MODEL_PATH)?.trim().orEmpty().ifBlank { null }
        val model = if (modelPathOverride == null) {
            resolveBundledModel(installer, requestedModelId)
        } else {
            BundledTtsModels.findById(requestedModelId) ?: BundledTtsModels.DefaultEnglish
        }
        val modelPath = modelPathOverride ?: installer.ensureInstalled(model).getOrThrow()
        val speakerId = args.getString(ARG_SPEAKER_ID)?.toIntOrNull()
            ?.coerceAtLeast(0)
            ?: model.defaultSpeakerId

        val engine = LocalModelTtsEngine()
        val failureCalled = AtomicBoolean(false)
        val failureLatch = CountDownLatch(1)

        try {
            engine.setModelPath(modelPath)
            engine.setSpeakerId(speakerId)
            val warmup = engine.benchmarkSynthesis(text = "Ready.", speed = speed).getOrThrow()
            Log.i(
                TAG,
                "LOCAL_TTS_PROBE_WARMUP generationMs=${warmup.generationMillis} firstChunkGenerationMs=${warmup.firstChunkGenerationMillis} segmentCount=${warmup.segmentCount} durationMs=${warmup.audioDurationMillis} rtf=${warmup.realTimeFactor}",
            )

            val accepted = engine.speakAsync(
                text = text,
                speed = speed,
                onFailure = {
                    failureCalled.set(true)
                    failureLatch.countDown()
                },
            )
            assertTrue("Local TTS probe request should be accepted", accepted)

            val outcome = waitForPlaybackOutcome(
                engine = engine,
                failureLatch = failureLatch,
                failureCalled = failureCalled,
                timeoutMs = timeoutMs,
            )
            val telemetry = outcome.telemetry
            val failureReason = engine.latestFailureReason()
            Log.i(
                TAG,
                "LOCAL_TTS_PROBE_RESULT success=${outcome.success} timedOut=${outcome.timedOut} failureCalled=${failureCalled.get()} failureReason=$failureReason textLength=${text.length} speed=$speed speakerId=$speakerId telemetryStartedAt=${telemetry.startedAtElapsedMs} telemetryFirstChunkGenerationMs=${telemetry.firstChunkGenerationMs} telemetryPlaybackStartDelayMs=${telemetry.playbackStartDelayMs} telemetrySegmentCount=${telemetry.segmentCount} telemetryStreamingMode=${telemetry.streamingMode} telemetryGapCount=${telemetry.streamingGapCount} telemetryMaxGapMs=${telemetry.maxStreamingGapMs} telemetryMaxFrames=${telemetry.maxPlaybackHeadFrames} telemetryTotalFrames=${telemetry.totalFrames} telemetrySampleRate=${telemetry.sampleRate} telemetryCompleted=${telemetry.completed} telemetryTimedOut=${telemetry.timedOut}",
            )

            assertTrue(
                "Local TTS probe failed. timedOut=${outcome.timedOut}, failureCalled=${failureCalled.get()}, failureReason=$failureReason, telemetryStartedAt=${telemetry.startedAtElapsedMs}, telemetryMaxFrames=${telemetry.maxPlaybackHeadFrames}, telemetryTotalFrames=${telemetry.totalFrames}, telemetryCompleted=${telemetry.completed}, telemetryTimedOut=${telemetry.timedOut}",
                outcome.success,
            )
        } finally {
            engine.shutdown()
        }
    }

    private fun waitForPlaybackOutcome(
        engine: LocalModelTtsEngine,
        failureLatch: CountDownLatch,
        failureCalled: AtomicBoolean,
        timeoutMs: Long,
    ): ProbeOutcome {
        val deadlineMs = SystemClock.elapsedRealtime() + timeoutMs
        var playbackProgressObserved = false
        var telemetry = engine.latestPlaybackTelemetry()
        while (SystemClock.elapsedRealtime() <= deadlineMs) {
            if (failureLatch.await(100L, TimeUnit.MILLISECONDS) || failureCalled.get()) {
                return ProbeOutcome(success = false, timedOut = false, telemetry = engine.latestPlaybackTelemetry())
            }

            telemetry = engine.latestPlaybackTelemetry()
            playbackProgressObserved = playbackProgressObserved || (telemetry.maxPlaybackHeadFrames > 0)
            val completed = telemetry.completed && !telemetry.timedOut
            if (completed && playbackProgressObserved) {
                return ProbeOutcome(success = true, timedOut = false, telemetry = telemetry)
            }
            SystemClock.sleep(40L)
        }

        return ProbeOutcome(success = false, timedOut = true, telemetry = telemetry)
    }

    private data class ProbeOutcome(
        val success: Boolean,
        val timedOut: Boolean,
        val telemetry: LocalPlaybackTelemetry,
    )

    private companion object {
        const val TAG = "LocalTtsStandaloneProbe"
        const val ARG_ENABLED = "localTtsProbeEnabled"
        const val ARG_MODEL_ID = "localTtsModelId"
        const val ARG_MODEL_PATH = "localTtsModelPath"
        const val ARG_TEXT = "localTtsText"
        const val ARG_SPEED = "localTtsSpeed"
        const val ARG_SPEAKER_ID = "localTtsSpeakerId"
        const val ARG_TIMEOUT_MS = "localTtsTimeoutMs"
        const val DEFAULT_TEXT = "This is a standalone local TTS probe."
        const val DEFAULT_SPEED = 1.0f
        const val DEFAULT_TIMEOUT_MS = 120_000L
    }

    private fun resolveBundledModel(installer: BundledTtsModelInstaller, requestedModelId: String?): BundledTtsModel {
        if (requestedModelId.isNullOrBlank()) {
            return BundledTtsModels.DefaultEnglish
        }
        return installer.discoverBundledModels().firstOrNull { it.id.equals(requestedModelId, ignoreCase = true) }
            ?: BundledTtsModels.findById(requestedModelId)
            ?: error("Unknown bundled model id: $requestedModelId")
    }
}
