package com.example.bookhelper.tts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
        val modelIds = parseRequestedModelIds(
            modelId = modelId,
            modelIdsArg = args.getString(ARG_MODEL_IDS),
        )
        val speed = args.getString(ARG_SPEED)?.toFloatOrNull()?.coerceIn(0.85f, 1.15f) ?: DEFAULT_SPEED
        val numThreads = args.getString(ARG_NUM_THREADS)?.toIntOrNull()?.coerceIn(1, 4)
        val maxNumSentences = args.getString(ARG_MAX_NUM_SENTENCES)?.toIntOrNull()?.coerceAtLeast(1)
        val repeatCount = args.getString(ARG_REPEAT_COUNT)?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_REPEAT_COUNT
        val timeoutMs = args.getString(ARG_TIMEOUT_MS)?.toLongOrNull()?.coerceAtLeast(1_000L) ?: DEFAULT_TIMEOUT_MS
        val baseText = args.getString(ARG_TEXT)?.trim().orEmpty().ifBlank { DEFAULT_TEXT }
        val text = List(TEXT_REPEAT_COUNT) { baseText }.joinToString(" ")

        val models = modelIds.map { requestedId ->
            resolveBundledModel(installer, requestedId)
        }

        val engine = LocalModelTtsEngine()
        try {
            engine.setNumThreads(numThreads)
            engine.setMaxNumSentences(maxNumSentences)
            models.forEach { model ->
                val modelPath = modelPathOverride ?: installer.ensureInstalled(model).getOrThrow()
                engine.setModelPath(modelPath)
                engine.setSpeakerId(model.defaultSpeakerId)

                val runMetrics = mutableListOf<Phase1MetricsSample>()
                repeat(repeatCount) { runIndex ->
                    val synthesis = engine.benchmarkSynthesis(text = text, speed = speed).getOrThrow()
                    assertTrue("Synthesis should generate audio for model=${model.id}", synthesis.sampleCount > 0)

                    val failureCalled = AtomicBoolean(false)
                    val failureLatch = CountDownLatch(1)
                    val accepted = engine.speakAsync(
                        text = text,
                        speed = speed,
                        onFailure = {
                            failureCalled.set(true)
                            failureLatch.countDown()
                        },
                    )
                    assertTrue("Playback request should be accepted for model=${model.id}", accepted)

                    val playback = waitForPlayback(
                        engine = engine,
                        failureLatch = failureLatch,
                        failureCalled = failureCalled,
                        timeoutMs = timeoutMs,
                    )
                    assertTrue(
                        "Playback should finish for model=${model.id}, run=${runIndex + 1}, telemetry=${playback.telemetry}, failureReason=${engine.latestFailureReason()}",
                        playback.success,
                    )

                    val sample = Phase1MetricsSample(
                        queueWaitMs = playback.telemetry.queueWaitMs,
                        ensureLoadedMs = playback.telemetry.ensureLoadedMs,
                        firstChunkGenerationMs = playback.telemetry.firstChunkGenerationMs,
                        playbackStartDelayMs = playback.telemetry.playbackStartDelayMs,
                        streamingMode = playback.telemetry.streamingMode,
                        synthesisGenerationTotalMs = synthesis.generationMillis,
                        playbackGenerationTotalMs = playback.telemetry.generationMs,
                    )
                    runMetrics += sample
                    Log.i(
                        TAG,
                        "TTS_PHASE1_METRICS modelId=${model.id} run=${runIndex + 1}/$repeatCount textLength=${text.length} speed=$speed queueWaitMs=${sample.queueWaitMs} ensureLoadedMs=${sample.ensureLoadedMs} firstChunkGenerationMs=${sample.firstChunkGenerationMs} playbackStartDelayMs=${sample.playbackStartDelayMs} streamingMode=${sample.streamingMode} synthesisGenerationTotalMs=${sample.synthesisGenerationTotalMs} playbackGenerationTotalMs=${sample.playbackGenerationTotalMs}",
                    )
                }

                val median = medianSample(runMetrics)
                Log.i(
                    TAG,
                    "TTS_PHASE1_METRICS_MEDIAN modelId=${model.id} runs=${runMetrics.size} queueWaitMs=${median.queueWaitMs} ensureLoadedMs=${median.ensureLoadedMs} firstChunkGenerationMs=${median.firstChunkGenerationMs} playbackStartDelayMs=${median.playbackStartDelayMs} streamingMode=${median.streamingMode} synthesisGenerationTotalMs=${median.synthesisGenerationTotalMs} playbackGenerationTotalMs=${median.playbackGenerationTotalMs}",
                )
            }
        } finally {
            engine.shutdown()
        }
    }

    private fun waitForPlayback(
        engine: LocalModelTtsEngine,
        failureLatch: CountDownLatch,
        failureCalled: AtomicBoolean,
        timeoutMs: Long,
    ): PlaybackOutcome {
        val deadlineMs = android.os.SystemClock.elapsedRealtime() + timeoutMs
        var telemetry = engine.latestPlaybackTelemetry()
        var playbackProgressObserved = false
        while (android.os.SystemClock.elapsedRealtime() <= deadlineMs) {
            if (failureLatch.await(100L, TimeUnit.MILLISECONDS) || failureCalled.get()) {
                return PlaybackOutcome(success = false, telemetry = engine.latestPlaybackTelemetry())
            }
            telemetry = engine.latestPlaybackTelemetry()
            playbackProgressObserved = playbackProgressObserved || telemetry.maxPlaybackHeadFrames > 0
            if (telemetry.completed && !telemetry.timedOut && playbackProgressObserved) {
                return PlaybackOutcome(success = true, telemetry = telemetry)
            }
            android.os.SystemClock.sleep(40L)
        }
        return PlaybackOutcome(success = false, telemetry = telemetry)
    }

    private fun parseRequestedModelIds(modelId: String?, modelIdsArg: String?): List<String> {
        val fromListArg = modelIdsArg
            ?.split(',')
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (modelId != null) {
            return listOf(modelId)
        }
        if (fromListArg.isNotEmpty()) {
            return fromListArg
        }
        return DEFAULT_MODEL_IDS
    }

    private fun resolveBundledModel(installer: BundledTtsModelInstaller, requestedModelId: String?): BundledTtsModel {
        if (requestedModelId.isNullOrBlank()) {
            return BundledTtsModels.PiperEnUsLibriTtsRMedium
        }
        return installer.discoverBundledModels().firstOrNull { it.id.equals(requestedModelId, ignoreCase = true) }
            ?: BundledTtsModels.findById(requestedModelId)
            ?: error("Unknown bundled model id: $requestedModelId")
    }

    private fun medianSample(samples: List<Phase1MetricsSample>): Phase1MetricsSample {
        require(samples.isNotEmpty()) { "No metrics samples provided" }
        fun medianLong(values: List<Long>): Long {
            val sorted = values.sorted()
            return sorted[sorted.lastIndex / 2]
        }
        fun medianDouble(values: List<Double>): Double {
            val sorted = values.sorted()
            return sorted[sorted.lastIndex / 2]
        }
        fun mode(values: List<String>): String {
            return values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: LocalStreamingMode.BATCH.name
        }

        return Phase1MetricsSample(
            queueWaitMs = medianLong(samples.map { it.queueWaitMs }),
            ensureLoadedMs = medianDouble(samples.map { it.ensureLoadedMs }),
            firstChunkGenerationMs = medianDouble(samples.map { it.firstChunkGenerationMs }),
            playbackStartDelayMs = medianLong(samples.map { it.playbackStartDelayMs }),
            streamingMode = mode(samples.map { it.streamingMode }),
            synthesisGenerationTotalMs = medianDouble(samples.map { it.synthesisGenerationTotalMs }),
            playbackGenerationTotalMs = medianDouble(samples.map { it.playbackGenerationTotalMs }),
        )
    }

    private data class Phase1MetricsSample(
        val queueWaitMs: Long,
        val ensureLoadedMs: Double,
        val firstChunkGenerationMs: Double,
        val playbackStartDelayMs: Long,
        val streamingMode: String,
        val synthesisGenerationTotalMs: Double,
        val playbackGenerationTotalMs: Double,
    )

    private data class PlaybackOutcome(
        val success: Boolean,
        val telemetry: LocalPlaybackTelemetry,
    )

    private companion object {
        const val TAG = "LocalTtsLatencyTest"
        const val ARG_MODEL_ID = "localTtsModelId"
        const val ARG_MODEL_IDS = "localTtsModelIds"
        const val ARG_MODEL_PATH = "localTtsModelPath"
        const val ARG_TEXT = "localTtsText"
        const val ARG_SPEED = "localTtsSpeed"
        const val ARG_NUM_THREADS = "localTtsNumThreads"
        const val ARG_MAX_NUM_SENTENCES = "localTtsMaxNumSentences"
        const val ARG_REPEAT_COUNT = "localTtsRepeatCount"
        const val ARG_TIMEOUT_MS = "localTtsTimeoutMs"
        const val DEFAULT_SPEED = 1.0f
        const val DEFAULT_REPEAT_COUNT = 3
        const val TEXT_REPEAT_COUNT = 3
        const val DEFAULT_TIMEOUT_MS = 120_000L
        const val DEFAULT_TEXT = "The quick brown fox jumps over the lazy dog."
        val DEFAULT_MODEL_IDS = listOf(
            BundledTtsModels.PiperEnUsLessacLow.id,
            BundledTtsModels.PiperEnUsLibriTtsRMedium.id,
        )
    }
}
