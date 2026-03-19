package com.example.bookhelper.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineTts
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class LocalTtsPlaybackInstrumentedTest {
    @Test
    fun localTtsPublishesActivePlaybackConfiguration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installer = BundledTtsModelInstaller(context)
        val modelPath = installer.ensureInstalled(BundledTtsModels.DefaultEnglish).getOrThrow()
        val engine = LocalModelTtsEngine()

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val callbackLatch = CountDownLatch(1)
        val playbackFailed = AtomicBoolean(false)
        val baselineMatches = countSpeechLikePlaybackConfigs(audioManager)
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>) {
                val matched = configs.count { isSpeechLikePlayback(it.audioAttributes) }
                if (matched > baselineMatches) {
                    callbackLatch.countDown()
                }
            }
        }

        audioManager.registerAudioPlaybackCallback(callback, Handler(Looper.getMainLooper()))

        try {
            engine.setModelPath(modelPath)
            engine.setSpeakerId(BundledTtsModels.DefaultEnglish.defaultSpeakerId)
            val warmup = engine.benchmarkSynthesis(
                text = "Ready.",
                speed = 1.0f,
            ).getOrThrow()
            Log.i(
                TAG,
                "LOCAL_TTS_WARMUP generationMs=${warmup.generationMillis} durationMs=${warmup.audioDurationMillis} sampleRate=${warmup.sampleRate} sampleCount=${warmup.sampleCount}",
            )
            val startMs = SystemClock.elapsedRealtime()

            val accepted = engine.speakAsync(
                text = "Hi. Hello there.",
                speed = 1.0f,
                onFailure = { playbackFailed.set(true) },
            )
            assertTrue("Local TTS playback job should be accepted", accepted)

            val signal = waitForPlaybackSignal(
                engine = engine,
                audioManager = audioManager,
                callbackLatch = callbackLatch,
                baselineMatches = baselineMatches,
                playbackFailed = playbackFailed,
                timeoutMs = 300_000L,
            )
            val telemetry = signal.telemetry
            val failureReason = engine.latestFailureReason()
            val elapsedMs = SystemClock.elapsedRealtime() - startMs
            val finalMatches = countSpeechLikePlaybackConfigs(audioManager)
            Log.i(
                TAG,
                "LOCAL_TTS_PLAYBACK callbackDetected=${signal.callbackDetected} polledDetected=${signal.polledDetected} telemetryDetected=${signal.telemetryDetected} playbackFailed=${playbackFailed.get()} failureReason=$failureReason baselineMatches=$baselineMatches finalMatches=$finalMatches queueWaitMs=${telemetry.queueWaitMs} ensureLoadedMs=${telemetry.ensureLoadedMs} generationMs=${telemetry.generationMs} firstChunkGenerationMs=${telemetry.firstChunkGenerationMs} playbackStartDelayMs=${telemetry.playbackStartDelayMs} segmentCount=${telemetry.segmentCount} generatedPcmNonEmpty=${telemetry.generatedPcmNonEmpty} generatedSampleCount=${telemetry.generatedSampleCount} generatedSampleRate=${telemetry.generatedSampleRate} audioTrackMode=${telemetry.audioTrackMode} audioTrackInitialized=${telemetry.audioTrackInitialized} audioTrackCreateMs=${telemetry.audioTrackCreateMs} audioWriteMs=${telemetry.audioWriteMs} audioWriteFrames=${telemetry.audioWriteFrames} audioWriteSucceeded=${telemetry.audioWriteSucceeded} telemetryStartedAt=${telemetry.startedAtElapsedMs} telemetryMaxFrames=${telemetry.maxPlaybackHeadFrames} telemetryTotalFrames=${telemetry.totalFrames} telemetrySampleRate=${telemetry.sampleRate} telemetryCompleted=${telemetry.completed} telemetryTimedOut=${telemetry.timedOut} telemetryTimeoutCause=${telemetry.timeoutCause} elapsedMs=$elapsedMs",
            )

            assertTrue(
                "Expected active local playback signal via AudioManager or AudioTrack telemetry. callbackDetected=${signal.callbackDetected}, polledDetected=${signal.polledDetected}, telemetryDetected=${signal.telemetryDetected}, playbackFailed=${playbackFailed.get()}, failureReason=$failureReason, queueWaitMs=${telemetry.queueWaitMs}, ensureLoadedMs=${telemetry.ensureLoadedMs}, generationMs=${telemetry.generationMs}, generatedPcmNonEmpty=${telemetry.generatedPcmNonEmpty}, generatedSampleCount=${telemetry.generatedSampleCount}, generatedSampleRate=${telemetry.generatedSampleRate}, audioTrackMode=${telemetry.audioTrackMode}, audioTrackInitialized=${telemetry.audioTrackInitialized}, audioTrackCreateMs=${telemetry.audioTrackCreateMs}, audioWriteMs=${telemetry.audioWriteMs}, audioWriteFrames=${telemetry.audioWriteFrames}, audioWriteSucceeded=${telemetry.audioWriteSucceeded}, telemetryStartedAt=${telemetry.startedAtElapsedMs}, telemetryMaxFrames=${telemetry.maxPlaybackHeadFrames}, telemetryTotalFrames=${telemetry.totalFrames}, telemetrySampleRate=${telemetry.sampleRate}, telemetryCompleted=${telemetry.completed}, telemetryTimedOut=${telemetry.timedOut}, telemetryTimeoutCause=${telemetry.timeoutCause}, baselineMatches=$baselineMatches, finalMatches=$finalMatches, elapsedMs=$elapsedMs",
                signal.callbackDetected || signal.polledDetected || signal.telemetryDetected,
            )
            assertTrue(
                "Expected audio write success after fallback. audioTrackMode=${telemetry.audioTrackMode}, audioWriteFrames=${telemetry.audioWriteFrames}, telemetryTotalFrames=${telemetry.totalFrames}, failureReason=$failureReason",
                telemetry.audioWriteSucceeded,
            )
            assertTrue(
                "Expected playback head progress after fallback. maxPlaybackHeadFrames=${telemetry.maxPlaybackHeadFrames}, audioTrackMode=${telemetry.audioTrackMode}, failureReason=$failureReason",
                telemetry.maxPlaybackHeadFrames > 0,
            )
            assertTrue("Expected segmented playback path for multi-sentence text", telemetry.segmentCount > 1)
            assertTrue("Expected total generation to exceed first chunk generation", telemetry.generationMs > telemetry.firstChunkGenerationMs)
            assertTrue("Expected playback to start before total generation finished", telemetry.playbackStartDelayMs in 1..telemetry.generationMs.toLong())
            assertFalse("Local TTS playback should not trigger failure callback", playbackFailed.get())
        } finally {
            audioManager.unregisterAudioPlaybackCallback(callback)
            engine.shutdown()
        }
    }

    @Test
    fun localTtsSwitchesModelsWithTwoSlotCacheAndReusesPriorModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installer = BundledTtsModelInstaller(context)
        val modelPathA = installer.ensureInstalled(BundledTtsModels.DefaultEnglish).getOrThrow()
        val modelPathB = copyModelDirectoryToAlternateLocation(modelPathA, context.cacheDir)
        val engine = LocalModelTtsEngine()

        try {
            engine.setModelPath(modelPathA)
            engine.benchmarkSynthesis(text = "Warm up A.", speed = 1.0f).getOrThrow()
            val modelAInstance = getActiveOfflineTts(engine)
            assertNotNull("Expected model A to be loaded", modelAInstance)

            engine.setModelPath(modelPathB)
            waitForCachedModel(engine, modelPathB, timeoutMs = 90_000L)
            assertTrue("Expected model B to preload into cache", isModelCached(engine, modelPathB))

            engine.benchmarkSynthesis(text = "Warm up B.", speed = 1.0f).getOrThrow()
            val modelBInstance = getActiveOfflineTts(engine)
            assertNotNull("Expected model B to be loaded", modelBInstance)
            assertTrue("Expected model B to use a different OfflineTts instance", modelAInstance !== modelBInstance)

            engine.setModelPath(modelPathA)
            engine.benchmarkSynthesis(text = "Back to A.", speed = 1.0f).getOrThrow()
            val modelAReusedInstance = getActiveOfflineTts(engine)
            assertTrue("Expected switching back to model A to reuse cached instance", modelAReusedInstance === modelAInstance)

            assertEquals("Expected 2-slot cache to hold exactly two models", 2, getModelCacheSize(engine))
            assertTrue("Expected model A in cache", isModelCached(engine, modelPathA))
            assertTrue("Expected model B in cache", isModelCached(engine, modelPathB))
        } finally {
            engine.shutdown()
            File(modelPathB).parentFile?.deleteRecursively()
        }
    }

    @Test
    fun systemTtsStillSpeaksWhenLocalModelIsDisabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ttsManager = AndroidTtsManager(context)

        try {
            ttsManager.setLocalModelEnabled(false)
            waitForAndroidTtsReady(ttsManager, timeoutMs = 20_000L)

            val result = ttsManager.speak("Hello")

            assertTrue(
                "Expected system TTS to accept speak() after initialization when local model is disabled. result=$result",
                result.accepted,
            )
            assertEquals(
                "Expected speak() route to remain SYSTEM_TTS when local model is disabled.",
                TtsRoute.SYSTEM_TTS,
                result.route,
            )
        } finally {
            ttsManager.shutdown()
        }
    }

    private fun waitForPlaybackSignal(
        engine: LocalModelTtsEngine,
        audioManager: AudioManager,
        callbackLatch: CountDownLatch,
        baselineMatches: Int,
        playbackFailed: AtomicBoolean,
        timeoutMs: Long,
    ): PlaybackSignal {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var callbackDetected = false
        var polledDetected = false
        var telemetryDetected = false
        var telemetry = engine.latestPlaybackTelemetry()
        while (SystemClock.elapsedRealtime() <= deadline) {
            callbackDetected = callbackDetected || callbackLatch.await(200L, TimeUnit.MILLISECONDS)
            polledDetected = polledDetected || (countSpeechLikePlaybackConfigs(audioManager) > baselineMatches)
            telemetry = engine.latestPlaybackTelemetry()
            val playbackProgressed = telemetry.audioWriteSucceeded && telemetry.maxPlaybackHeadFrames > 0
            telemetryDetected = playbackProgressed

            if (playbackProgressed || playbackFailed.get()) {
                break
            }
            SystemClock.sleep(40L)
        }

        return PlaybackSignal(
            callbackDetected = callbackDetected,
            polledDetected = polledDetected,
            telemetryDetected = telemetryDetected,
            telemetry = telemetry,
        )
    }

    private fun countSpeechLikePlaybackConfigs(audioManager: AudioManager): Int {
        return runCatching {
            audioManager.activePlaybackConfigurations.count { config ->
                isSpeechLikePlayback(config.audioAttributes)
            }
        }.getOrDefault(0)
    }

    private fun isSpeechLikePlayback(audioAttributes: AudioAttributes): Boolean {
        val usageMatches = audioAttributes.usage == AudioAttributes.USAGE_MEDIA ||
            audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        val contentMatches = audioAttributes.contentType == AudioAttributes.CONTENT_TYPE_SPEECH
        return usageMatches && contentMatches
    }

    private fun copyModelDirectoryToAlternateLocation(modelPath: String, rootDir: File): String {
        val sourceModel = File(modelPath)
        val sourceModelDir = if (sourceModel.isDirectory) sourceModel else sourceModel.parentFile
            ?: error("Model path has no parent directory")
        val destinationModelDir = File(rootDir, "tts-model-copy-${SystemClock.elapsedRealtime()}")
        sourceModelDir.copyRecursively(destinationModelDir, overwrite = true)
        return File(destinationModelDir, "model.onnx").absolutePath
    }

    private fun waitForCachedModel(engine: LocalModelTtsEngine, modelPath: String, timeoutMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() <= deadline) {
            if (isModelCached(engine, modelPath)) {
                return
            }
            SystemClock.sleep(50L)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun isModelCached(engine: LocalModelTtsEngine, modelPath: String): Boolean {
        return withModelLock(engine) {
            val cache = getField(engine, "modelCache") as LinkedHashMap<String, OfflineTts>
            cache.containsKey(modelPath)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getModelCacheSize(engine: LocalModelTtsEngine): Int {
        return withModelLock(engine) {
            val cache = getField(engine, "modelCache") as LinkedHashMap<String, OfflineTts>
            cache.size
        }
    }

    private fun getActiveOfflineTts(engine: LocalModelTtsEngine): OfflineTts? {
        return withModelLock(engine) {
            getField(engine, "offlineTts") as OfflineTts?
        }
    }

    private fun waitForAndroidTtsReady(ttsManager: AndroidTtsManager, timeoutMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() <= deadline) {
            val ready = getField(ttsManager, "isReady") as? Boolean ?: false
            if (ready) {
                return
            }
            SystemClock.sleep(100L)
        }
        assertTrue("AndroidTtsManager did not become ready within ${timeoutMs}ms", false)
    }

    private inline fun <T> withModelLock(engine: LocalModelTtsEngine, block: () -> T): T {
        val lock = getField(engine, "modelLock") as Any
        synchronized(lock) {
            return block()
        }
    }

    private fun getField(target: Any, name: String): Any? {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target)
    }

    private data class PlaybackSignal(
        val callbackDetected: Boolean,
        val polledDetected: Boolean,
        val telemetryDetected: Boolean,
        val telemetry: LocalPlaybackTelemetry,
    )

    private companion object {
        const val TAG = "LocalTtsPlaybackTest"
    }
}
