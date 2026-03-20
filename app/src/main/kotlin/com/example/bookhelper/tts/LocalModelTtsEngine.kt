package com.example.bookhelper.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

class LocalModelTtsEngine {
    private val synthesisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val playbackExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val playbackTelemetryRef = AtomicReference(LocalPlaybackTelemetry())
    private val lastFailureReasonRef = AtomicReference<String?>(null)
    private val utteranceIdRef = AtomicLong(0L)

    private var configuredModelPath: String? = null
    private var configuredSpeakerId: Int = 0
    private var configuredNumThreads: Int? = null
    private var configuredMaxNumSentences: Int? = null
    private var loadedModelPath: String? = null
    private var loadedModelDescriptor: LocalModelDescriptor? = null
    private var offlineTts: OfflineTts? = null
    private val modelCache = LinkedHashMap<String, OfflineTts>(MODEL_CACHE_CAPACITY, 0.75f, true)
    private var activeTrack: AudioTrack? = null
    private var activePiperSession: PiperStreamingSession? = null
    private val trackLock = Any()
    private val modelLock = Any()

    fun setModelPath(path: String?) {
        val normalizedPath = path?.takeIf { it.isNotBlank() }
        val previousPath = configuredModelPath
        configuredModelPath = normalizedPath
        if (normalizedPath == null || normalizedPath == previousPath) {
            return
        }
        runCatching {
            synthesisExecutor.execute {
                runCatching {
                    synchronized(modelLock) {
                        preloadModel(normalizedPath)
                    }
                }.onFailure {
                    Log.w(TAG, "Failed to preload local model", it)
                }
            }
        }.onFailure {
            Log.w(TAG, "Failed to schedule local model preload", it)
        }
    }

    fun setSpeakerId(speakerId: Int) {
        configuredSpeakerId = speakerId.coerceAtLeast(0)
    }

    fun setNumThreads(numThreads: Int?) {
        configuredNumThreads = numThreads?.coerceIn(1, 4)
    }

    fun setMaxNumSentences(maxNumSentences: Int?) {
        configuredMaxNumSentences = maxNumSentences?.coerceAtLeast(1)
    }

    fun speakAsync(text: String, speed: Float, onFailure: (() -> Unit)? = null): Boolean {
        val modelPath = configuredModelPath ?: return false
        val normalizedText = text.trim()
        if (normalizedText.isBlank()) {
            return false
        }

        val normalizedSpeed = speed.coerceIn(MIN_LOCAL_SPEED, MAX_LOCAL_SPEED)
        val speakerId = configuredSpeakerId
        val segments = synthesisSegments(normalizedText)
        lastFailureReasonRef.set(null)
        val utteranceId = utteranceIdRef.incrementAndGet()
        stop()
        val scheduledAtElapsedMs = SystemClock.elapsedRealtime()
        val accepted = runCatching {
            synthesisExecutor.execute {
                val startedAtElapsedMs = SystemClock.elapsedRealtime()
                beginUtteranceTelemetry(
                    scheduledAtElapsedMs = scheduledAtElapsedMs,
                    startedAtElapsedMs = startedAtElapsedMs,
                    queueWaitMs = startedAtElapsedMs - scheduledAtElapsedMs,
                )
                runCatching {
                    val generatedAudio = synchronized(modelLock) {
                        val ensureLoadedStartNs = System.nanoTime()
                        ensureLoaded(modelPath)
                        val ensureLoadedMs = nanosToMillis(System.nanoTime() - ensureLoadedStartNs)
                        updatePlaybackTelemetry(
                            ensureLoadedMs = ensureLoadedMs,
                        )

                        val tts = offlineTts ?: error("Offline TTS is not initialized")
                        val descriptor = loadedModelDescriptor ?: error("Local model descriptor is not initialized")
                        if (descriptor.modelKind == LocalTtsModelKind.PIPER_DERIVED) {
                            generateAndPlayWithPiperSession(
                                tts = tts,
                                descriptor = descriptor,
                                text = normalizedText,
                                speakerId = speakerId,
                                speed = normalizedSpeed,
                                utteranceId = utteranceId,
                            )
                            null
                        } else if (segments.size > 1) {
                            generateAndPlayProgressively(
                                tts = tts,
                                descriptor = descriptor,
                                segments = segments,
                                speakerId = speakerId,
                                speed = normalizedSpeed,
                                utteranceId = utteranceId,
                                onFailure = onFailure,
                            )
                            null
                        } else {
                            generateSegmentedAudio(
                                tts = tts,
                                descriptor = descriptor,
                                segments = segments,
                                speakerId = speakerId,
                                speed = normalizedSpeed,
                            )
                        }
                    }
                    if (generatedAudio != null) {
                        enqueuePlayback(
                            utteranceId = utteranceId,
                            generatedAudio = generatedAudio,
                            onFailure = onFailure,
                        )
                    }
                }.onFailure {
                    handleSynthesisOrPlaybackFailure(
                        throwable = it,
                        logMessage = "Local TTS synthesis failed",
                        onFailure = onFailure,
                    )
                }.also {
                    if (it.isFailure) {
                        logPlaybackTelemetryOnce()
                    }
                }
            }
        }.isSuccess
        if (!accepted) {
            onFailure?.invoke()
            return false
        }
        return accepted
    }

    fun stop() {
        activePiperSession?.stop()
        activePiperSession = null
        val track = synchronized(trackLock) {
            val current = activeTrack
            activeTrack = null
            current
        }
        track?.stopQuietly()
        track?.releaseQuietly()
    }

    fun shutdown() {
        stop()
        synchronized(modelLock) {
            releaseModel()
        }
        playbackExecutor.shutdownNow()
        synthesisExecutor.shutdownNow()
    }

    fun benchmarkSynthesis(text: String, speed: Float): Result<LocalTtsBenchmarkMetrics> {
        val modelPath = configuredModelPath ?: return Result.failure(IllegalStateException("Model path is not configured"))
        if (text.isBlank()) {
            return Result.failure(IllegalArgumentException("Text is blank"))
        }

        return runCatching {
            synthesisExecutor.submit(
                Callable {
                    synchronized(modelLock) {
                        ensureLoaded(modelPath)
                        val tts = offlineTts ?: error("Offline TTS is not initialized")
                        val descriptor = loadedModelDescriptor ?: error("Local model descriptor is not initialized")
                        val segments = synthesisSegments(text)
                        val segmented = benchmarkSegmentedAudio(
                            tts = tts,
                            descriptor = descriptor,
                            segments = segments,
                            speakerId = configuredSpeakerId,
                            speed = speed.coerceIn(MIN_LOCAL_SPEED, MAX_LOCAL_SPEED),
                        )
                        val audio = segmented.audio
                        val generationMs = segmented.totalGenerationMs
                        val extracted = extractAudioSamples(audio)
                        val durationMs = if (extracted.sampleRate > 0) {
                            extracted.samples.size * 1000.0 / extracted.sampleRate
                        } else {
                            0.0
                        }
                        val rtf = if (durationMs > 0.0) generationMs / durationMs else Double.POSITIVE_INFINITY
                        LocalTtsBenchmarkMetrics(
                            generationMillis = generationMs,
                            firstChunkGenerationMillis = segmented.firstChunkGenerationMs,
                            audioDurationMillis = durationMs,
                            realTimeFactor = rtf,
                            sampleRate = extracted.sampleRate,
                            sampleCount = extracted.samples.size,
                            segmentCount = segments.size,
                        )
                    }
                },
            ).get()
        }
    }

    internal fun latestPlaybackTelemetry(): LocalPlaybackTelemetry = playbackTelemetryRef.get()

    internal fun latestFailureReason(): String? = lastFailureReasonRef.get()

    private fun enqueuePlayback(
        utteranceId: Long,
        generatedAudio: com.k2fsa.sherpa.onnx.GeneratedAudio,
        onFailure: (() -> Unit)?,
    ) {
        runCatching {
            playbackExecutor.execute {
                if (isUtteranceSuperseded(utteranceId)) {
                    return@execute
                }
                runCatching {
                    playGeneratedAudio(audio = generatedAudio, utteranceId = utteranceId)
                }.onFailure {
                    handleSynthesisOrPlaybackFailure(
                        throwable = it,
                        logMessage = "Local TTS playback failed",
                        onFailure = onFailure,
                    )
                }.also {
                    logPlaybackTelemetryOnce()
                }
            }
        }.onFailure {
            handleSynthesisOrPlaybackFailure(
                throwable = it,
                logMessage = "Failed to enqueue local TTS playback",
                onFailure = onFailure,
            )
            logPlaybackTelemetryOnce()
        }
    }

    private fun isUtteranceSuperseded(utteranceId: Long): Boolean = utteranceId != utteranceIdRef.get()

    private fun handleSynthesisOrPlaybackFailure(
        throwable: Throwable,
        logMessage: String,
        onFailure: (() -> Unit)?,
    ) {
        val failureReason = "${throwable::class.java.simpleName}: ${throwable.message ?: "(no message)"}"
        lastFailureReasonRef.set(failureReason)
        updatePlaybackTelemetry(failureReason = failureReason)
        Log.e(TAG, logMessage, throwable)
        synchronized(modelLock) {
            releaseModel()
        }
        onFailure?.invoke()
    }

    private fun ensureLoaded(modelPath: String) {
        if (offlineTts != null && loadedModelPath == modelPath && loadedModelDescriptor != null) {
            return
        }

        val descriptor = LocalModelDescriptorResolver.resolve(modelPath)

        val cached = modelCache[modelPath]
        if (cached != null) {
            offlineTts = cached
            loadedModelPath = modelPath
            loadedModelDescriptor = descriptor
            return
        }

        val created = buildOfflineTts(descriptor)
        cacheModel(modelPath, created)
        offlineTts = created
        loadedModelPath = modelPath
        loadedModelDescriptor = descriptor
    }

    private fun releaseModel() {
        val instances = LinkedHashSet<OfflineTts>()
        offlineTts?.let { instances.add(it) }
        modelCache.values.forEach { instances.add(it) }
        modelCache.clear()
        instances.forEach { instance ->
            runCatching { instance.release() }
        }
        offlineTts = null
        loadedModelPath = null
        loadedModelDescriptor = null
    }

    private fun preloadModel(modelPath: String) {
        if (modelCache.containsKey(modelPath)) {
            return
        }
        val descriptor = LocalModelDescriptorResolver.resolve(modelPath)
        val created = buildOfflineTts(descriptor)
        cacheModel(modelPath, created)
    }

    private fun buildOfflineTts(modelDescriptor: LocalModelDescriptor): OfflineTts {
        val config = createOfflineTtsConfig(modelDescriptor)
        return OfflineTts(null, config)
    }

    private fun cacheModel(modelPath: String, tts: OfflineTts) {
        modelCache[modelPath] = tts
        evictLeastRecentlyUsedModelsIfNeeded()
    }

    private fun evictLeastRecentlyUsedModelsIfNeeded() {
        while (modelCache.size > MODEL_CACHE_CAPACITY) {
            val eldestEntry = modelCache.entries.iterator().next()
            modelCache.remove(eldestEntry.key)
            if (loadedModelPath == eldestEntry.key) {
                loadedModelPath = null
                offlineTts = null
            }
            runCatching { eldestEntry.value.release() }
        }
    }

    private fun createOfflineTtsConfig(descriptor: LocalModelDescriptor): OfflineTtsConfig {
        val availableCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val numThreads = configuredNumThreads ?: availableCores.coerceIn(1, 4)
        val modelConfig = OfflineTtsModelConfig(
            vits = if (descriptor.modelKind == LocalTtsModelKind.PIPER_DERIVED) {
                OfflineTtsVitsModelConfig(
                    model = descriptor.model.absolutePath,
                    lexicon = descriptor.lexicon?.absolutePath.orEmpty(),
                    tokens = descriptor.tokens.absolutePath,
                    dataDir = descriptor.dataDir.absolutePath,
                    dictDir = descriptor.dictDir?.absolutePath.orEmpty(),
                    noiseScale = 0.667f,
                    noiseScaleW = 0.8f,
                    lengthScale = 1.0f,
                )
            } else {
                OfflineTtsVitsModelConfig()
            },
            kokoro = if (descriptor.modelKind == LocalTtsModelKind.KOKORO) {
                OfflineTtsKokoroModelConfig(
                    model = descriptor.model.absolutePath,
                    voices = requireNotNull(descriptor.voices).absolutePath,
                    tokens = descriptor.tokens.absolutePath,
                    dataDir = descriptor.dataDir.absolutePath,
                )
            } else {
                OfflineTtsKokoroModelConfig()
            },
            numThreads = numThreads,
            debug = false,
            provider = "cpu",
        )
        return OfflineTtsConfig(
            model = modelConfig,
            maxNumSentences = configuredMaxNumSentences ?: 1,
            silenceScale = 0.2f,
        )
    }

    private fun generateAndPlayProgressively(
        tts: OfflineTts,
        descriptor: LocalModelDescriptor,
        segments: List<String>,
        speakerId: Int,
        speed: Float,
        utteranceId: Long,
        onFailure: (() -> Unit)?,
    ) {
        require(segments.isNotEmpty()) { "Segments must not be empty" }

        val firstGenerateStartNs = System.nanoTime()
        val firstAudio = generateAudio(
            tts = tts,
            descriptor = descriptor,
            text = segments.first(),
            speakerId = speakerId,
            speed = speed,
        )
        val firstChunkGenerationMs = nanosToMillis(System.nanoTime() - firstGenerateStartNs)
        val progressiveStream = ProgressiveAudioStream()
        updatePlaybackTelemetry(
            firstChunkGenerationMs = firstChunkGenerationMs,
            segmentCount = segments.size,
            generatedSampleRate = firstAudio.sampleRate,
            generatedPcmNonEmpty = firstAudio.samples.isNotEmpty() && firstAudio.sampleRate > 0,
        )
        enqueueProgressivePlayback(
            utteranceId = utteranceId,
            firstAudio = firstAudio,
            progressiveStream = progressiveStream,
            onFailure = onFailure,
        )

        var totalGenerationMs = firstChunkGenerationMs
        var totalSampleCount = firstAudio.samples.size
        try {
            for (segment in segments.drop(1)) {
                if (isUtteranceSuperseded(utteranceId)) {
                    progressiveStream.finish()
                    return
                }
                val generateStartNs = System.nanoTime()
                val audio = generateAudio(
                    tts = tts,
                    descriptor = descriptor,
                    text = segment,
                    speakerId = speakerId,
                    speed = speed,
                )
                totalGenerationMs += nanosToMillis(System.nanoTime() - generateStartNs)
                totalSampleCount += audio.samples.size
                progressiveStream.offer(audio)
            }
            progressiveStream.finish()
            updatePlaybackTelemetry(
                generationMs = totalGenerationMs,
                generatedSampleCount = totalSampleCount,
                generatedSampleRate = firstAudio.sampleRate,
                generatedPcmNonEmpty = totalSampleCount > 0 && firstAudio.sampleRate > 0,
            )
        } catch (throwable: Throwable) {
            progressiveStream.fail(throwable)
            throw throwable
        }
    }

    private fun generateAndPlayWithPiperSession(
        tts: OfflineTts,
        descriptor: LocalModelDescriptor,
        text: String,
        speakerId: Int,
        speed: Float,
        utteranceId: Long,
    ) {
        stop()
        if (isUtteranceSuperseded(utteranceId)) {
            return
        }

        val session = PiperStreamingSession(
            tts = tts,
            utteranceId = utteranceId,
            isUtteranceSuperseded = ::isUtteranceSuperseded,
            updateTelemetry = { telemetry ->
                updatePlaybackTelemetry(
                    firstChunkGenerationMs = telemetry.firstChunkGenerationMs,
                    generationMs = telemetry.generationMs,
                    segmentCount = 1,
                    totalFrames = telemetry.totalFrames,
                    streamingMode = LocalStreamingMode.CALLBACK.name,
                    streamingGapCount = telemetry.streamingGapCount,
                    maxStreamingGapMs = telemetry.maxStreamingGapMs,
                    generatedSampleCount = telemetry.generatedSampleCount,
                    generatedSampleRate = telemetry.generatedSampleRate,
                    generatedPcmNonEmpty = telemetry.generatedPcmNonEmpty,
                    audioTrackMode = telemetry.audioTrackMode,
                    audioTrackInitialized = telemetry.audioTrackInitialized,
                    audioTrackCreateMs = telemetry.audioTrackCreateMs,
                    audioWriteMs = telemetry.audioWriteMs,
                    audioWriteFrames = telemetry.audioWriteFrames,
                    audioWriteSucceeded = telemetry.audioWriteSucceeded,
                )
            },
        )
        activePiperSession = session
        try {
            val result = session.play(
                text = text,
                speakerId = normalizeSpeakerIdForGeneration(descriptor, speakerId),
                speed = speed,
            )
            beginPlaybackTelemetry(totalFrames = result.totalFrames, sampleRate = result.sampleRate)
        } finally {
            if (activePiperSession === session) {
                activePiperSession = null
            }
        }
    }

    private fun generateSegmentedAudio(
        tts: OfflineTts,
        descriptor: LocalModelDescriptor,
        segments: List<String>,
        speakerId: Int,
        speed: Float,
    ): com.k2fsa.sherpa.onnx.GeneratedAudio {
        val generated = mutableListOf<com.k2fsa.sherpa.onnx.GeneratedAudio>()
        var totalGenerationMs = 0.0
        var firstChunkGenerationMs = 0.0
        segments.forEachIndexed { index, segment ->
            val generateStartNs = System.nanoTime()
            val audio = generateAudio(
                tts = tts,
                descriptor = descriptor,
                text = segment,
                speakerId = speakerId,
                speed = speed,
            )
            val generationMs = nanosToMillis(System.nanoTime() - generateStartNs)
            totalGenerationMs += generationMs
            if (index == 0) {
                firstChunkGenerationMs = generationMs
            }
            generated += audio
        }

        val merged = mergeGeneratedAudio(generated)
        updatePlaybackTelemetry(
            generationMs = totalGenerationMs,
            firstChunkGenerationMs = firstChunkGenerationMs,
            segmentCount = segments.size,
            generatedSampleCount = merged.samples.size,
            generatedSampleRate = merged.sampleRate,
            generatedPcmNonEmpty = merged.samples.isNotEmpty() && merged.sampleRate > 0,
        )
        return merged
    }

    private fun benchmarkSegmentedAudio(
        tts: OfflineTts,
        descriptor: LocalModelDescriptor,
        segments: List<String>,
        speakerId: Int,
        speed: Float,
    ): SegmentedAudioResult {
        val generated = mutableListOf<com.k2fsa.sherpa.onnx.GeneratedAudio>()
        var totalGenerationMs = 0.0
        var firstChunkGenerationMs = 0.0
        segments.forEachIndexed { index, segment ->
            val startNs = System.nanoTime()
            val audio = generateAudio(
                tts = tts,
                descriptor = descriptor,
                text = segment,
                speakerId = speakerId,
                speed = speed,
            )
            val generationMs = nanosToMillis(System.nanoTime() - startNs)
            totalGenerationMs += generationMs
            if (index == 0) {
                firstChunkGenerationMs = generationMs
            }
            generated += audio
        }
        return SegmentedAudioResult(
            audio = mergeGeneratedAudio(generated),
            firstChunkGenerationMs = firstChunkGenerationMs,
            totalGenerationMs = totalGenerationMs,
        )
    }

    private fun mergeGeneratedAudio(
        generated: List<com.k2fsa.sherpa.onnx.GeneratedAudio>,
    ): com.k2fsa.sherpa.onnx.GeneratedAudio {
        if (generated.size == 1) {
            return generated.single()
        }
        val sampleRate = generated.firstOrNull()?.sampleRate ?: 0
        require(generated.all { it.sampleRate == sampleRate }) { "Generated segments use different sample rates" }
        val totalSamples = generated.sumOf { it.samples.size }
        val merged = FloatArray(totalSamples)
        var offset = 0
        generated.forEach { audio ->
            audio.samples.copyInto(merged, destinationOffset = offset)
            offset += audio.samples.size
        }
        return com.k2fsa.sherpa.onnx.GeneratedAudio(merged, sampleRate)
    }

    private fun generateAudio(
        tts: OfflineTts,
        descriptor: LocalModelDescriptor,
        text: String,
        speakerId: Int,
        speed: Float,
    ) = tts.generate(
        text = text,
        sid = normalizeSpeakerIdForGeneration(descriptor, speakerId),
        speed = speed,
    )

    private fun normalizeSpeakerIdForGeneration(descriptor: LocalModelDescriptor, speakerId: Int): Int {
        return when (descriptor.modelKind) {
            LocalTtsModelKind.KOKORO -> speakerId.coerceIn(KOKORO_MIN_SPEAKER_ID, KOKORO_MAX_SPEAKER_ID)
            LocalTtsModelKind.PIPER_DERIVED -> speakerId.coerceAtLeast(0)
        }
    }

    private fun extractAudioSamples(audio: com.k2fsa.sherpa.onnx.GeneratedAudio): ExtractedAudio {
        return ExtractedAudio(samples = audio.samples, sampleRate = audio.sampleRate)
    }

    private fun playGeneratedAudio(audio: com.k2fsa.sherpa.onnx.GeneratedAudio, utteranceId: Long) {
        if (isUtteranceSuperseded(utteranceId)) {
            return
        }

        val extracted = extractAudioSamples(audio)
        val floatSamples = extracted.samples
        val sampleRate = extracted.sampleRate

        if (floatSamples.isEmpty() || sampleRate <= 0) {
            throw IllegalStateException("Generated local audio is empty or sample rate is invalid")
        }

        stop()
        if (isUtteranceSuperseded(utteranceId)) {
            return
        }

        if (tryPlayStaticFloat(floatSamples, sampleRate, utteranceId)) {
            return
        }
        if (tryPlayStreamFloat(floatSamples, sampleRate, utteranceId)) {
            return
        }

        val pcm16Samples = convertFloatToPcm16(floatSamples)
        if (tryPlayStaticPcm16(pcm16Samples, sampleRate, utteranceId)) {
            return
        }
        if (tryPlayStreamPcm16(pcm16Samples, sampleRate, utteranceId)) {
            return
        }

        if (isUtteranceSuperseded(utteranceId)) {
            return
        }

        throw IllegalStateException(
            "Failed local playback: AudioTrack write failed for PCM_FLOAT static/stream and PCM_16BIT static/stream",
        )
    }

    private fun enqueueProgressivePlayback(
        utteranceId: Long,
        firstAudio: com.k2fsa.sherpa.onnx.GeneratedAudio,
        progressiveStream: ProgressiveAudioStream,
        onFailure: (() -> Unit)?,
    ) {
        runCatching {
            playbackExecutor.execute {
                if (isUtteranceSuperseded(utteranceId)) {
                    return@execute
                }
                runCatching {
                    playGeneratedAudioProgressively(
                        firstAudio = firstAudio,
                        progressiveStream = progressiveStream,
                        utteranceId = utteranceId,
                    )
                }.onFailure {
                    handleSynthesisOrPlaybackFailure(
                        throwable = it,
                        logMessage = "Local TTS progressive playback failed",
                        onFailure = onFailure,
                    )
                }.also {
                    logPlaybackTelemetryOnce()
                }
            }
        }.onFailure {
            progressiveStream.fail(it)
            handleSynthesisOrPlaybackFailure(
                throwable = it,
                logMessage = "Failed to enqueue local TTS progressive playback",
                onFailure = onFailure,
            )
            logPlaybackTelemetryOnce()
        }
    }

    private fun playGeneratedAudioProgressively(
        firstAudio: com.k2fsa.sherpa.onnx.GeneratedAudio,
        progressiveStream: ProgressiveAudioStream,
        utteranceId: Long,
    ) {
        if (isUtteranceSuperseded(utteranceId)) {
            return
        }
        stop()
        if (isUtteranceSuperseded(utteranceId)) {
            return
        }

        if (tryPlayProgressiveFloat(firstAudio, progressiveStream, utteranceId)) {
            return
        }
        if (tryPlayProgressivePcm16(firstAudio, progressiveStream, utteranceId)) {
            return
        }

        val fallback = progressiveStream.collectAll(firstAudio)
        playGeneratedAudio(mergeGeneratedAudio(fallback), utteranceId)
    }

    private fun tryPlayProgressiveFloat(
        firstAudio: com.k2fsa.sherpa.onnx.GeneratedAudio,
        progressiveStream: ProgressiveAudioStream,
        utteranceId: Long,
    ): Boolean {
        val sampleRate = firstAudio.sampleRate
        val streamCreateStartNs = System.nanoTime()
        val streamTrack = createStreamTrack(sampleRate = sampleRate, encoding = AudioFormat.ENCODING_PCM_FLOAT)
        val streamCreateMs = nanosToMillis(System.nanoTime() - streamCreateStartNs)
        if (streamTrack == null) {
            updatePlaybackTelemetry(
                audioTrackMode = "pcm_float_stream_progressive",
                audioTrackInitialized = false,
                audioTrackCreateMs = streamCreateMs,
            )
            return false
        }

        synchronized(trackLock) {
            activeTrack = streamTrack
        }
        try {
            var totalFrames = 0
            var totalWriteMs = 0.0
            beginPlaybackTelemetry(totalFrames = 0, sampleRate = sampleRate)
            streamTrack.play()

            fun writeChunk(chunk: FloatArray): Boolean {
                val writeStartNs = System.nanoTime()
                val written = writeFloatSamplesStreaming(streamTrack, chunk, utteranceId)
                totalWriteMs += nanosToMillis(System.nanoTime() - writeStartNs)
                if (written != chunk.size) {
                    updatePlaybackTelemetry(
                        totalFrames = totalFrames,
                        audioTrackMode = "pcm_float_stream_progressive",
                        audioTrackInitialized = true,
                        audioTrackCreateMs = streamCreateMs,
                        audioWriteMs = totalWriteMs,
                        audioWriteFrames = totalFrames,
                        audioWriteSucceeded = false,
                    )
                    return false
                }
                totalFrames += written
                updatePlaybackTelemetry(
                    totalFrames = totalFrames,
                    audioTrackMode = "pcm_float_stream_progressive",
                    audioTrackInitialized = true,
                    audioTrackCreateMs = streamCreateMs,
                    audioWriteMs = totalWriteMs,
                    audioWriteFrames = totalFrames,
                )
                return true
            }

            if (!writeChunk(firstAudio.samples)) {
                finishPlaybackTelemetry(completed = false, timedOut = false, timeoutCause = "float_stream_progressive_write_failed")
                return false
            }

            while (true) {
                when (val item = progressiveStream.pollNext(utteranceId)) {
                    null -> {
                        finishPlaybackTelemetry(completed = false, timedOut = false, timeoutCause = "superseded")
                        return false
                    }
                    is ProgressiveAudioItem.Audio -> if (!writeChunk(item.audio.samples)) {
                        finishPlaybackTelemetry(completed = false, timedOut = false, timeoutCause = "float_stream_progressive_write_failed")
                        return false
                    }
                    is ProgressiveAudioItem.Failure -> throw item.throwable
                    ProgressiveAudioItem.Complete -> {
                        updatePlaybackTelemetry(audioWriteSucceeded = totalFrames > 0, totalFrames = totalFrames)
                        waitForPlaybackCompletion(streamTrack, totalFrames, sampleRate, utteranceId)
                        return true
                    }
                }
            }
        } finally {
            streamTrack.stopQuietly()
            streamTrack.releaseQuietly()
            synchronized(trackLock) {
                if (activeTrack === streamTrack) {
                    activeTrack = null
                }
            }
        }
    }

    private fun tryPlayProgressivePcm16(
        firstAudio: com.k2fsa.sherpa.onnx.GeneratedAudio,
        progressiveStream: ProgressiveAudioStream,
        utteranceId: Long,
    ): Boolean {
        val sampleRate = firstAudio.sampleRate
        val streamCreateStartNs = System.nanoTime()
        val streamTrack = createStreamTrack(sampleRate = sampleRate, encoding = AudioFormat.ENCODING_PCM_16BIT)
        val streamCreateMs = nanosToMillis(System.nanoTime() - streamCreateStartNs)
        if (streamTrack == null) {
            updatePlaybackTelemetry(
                audioTrackMode = "pcm16_stream_progressive",
                audioTrackInitialized = false,
                audioTrackCreateMs = streamCreateMs,
            )
            return false
        }

        synchronized(trackLock) {
            activeTrack = streamTrack
        }
        try {
            var totalFrames = 0
            var totalWriteMs = 0.0
            beginPlaybackTelemetry(totalFrames = 0, sampleRate = sampleRate)
            streamTrack.play()

            fun writeChunk(chunk: FloatArray): Boolean {
                val pcm16 = convertFloatToPcm16(chunk)
                val writeStartNs = System.nanoTime()
                val written = writePcm16SamplesStreaming(streamTrack, pcm16, utteranceId)
                totalWriteMs += nanosToMillis(System.nanoTime() - writeStartNs)
                if (written != pcm16.size) {
                    updatePlaybackTelemetry(
                        totalFrames = totalFrames,
                        audioTrackMode = "pcm16_stream_progressive",
                        audioTrackInitialized = true,
                        audioTrackCreateMs = streamCreateMs,
                        audioWriteMs = totalWriteMs,
                        audioWriteFrames = totalFrames,
                        audioWriteSucceeded = false,
                    )
                    return false
                }
                totalFrames += written
                updatePlaybackTelemetry(
                    totalFrames = totalFrames,
                    audioTrackMode = "pcm16_stream_progressive",
                    audioTrackInitialized = true,
                    audioTrackCreateMs = streamCreateMs,
                    audioWriteMs = totalWriteMs,
                    audioWriteFrames = totalFrames,
                )
                return true
            }

            if (!writeChunk(firstAudio.samples)) {
                finishPlaybackTelemetry(completed = false, timedOut = false, timeoutCause = "pcm16_stream_progressive_write_failed")
                return false
            }

            while (true) {
                when (val item = progressiveStream.pollNext(utteranceId)) {
                    null -> {
                        finishPlaybackTelemetry(completed = false, timedOut = false, timeoutCause = "superseded")
                        return false
                    }
                    is ProgressiveAudioItem.Audio -> if (!writeChunk(item.audio.samples)) {
                        finishPlaybackTelemetry(completed = false, timedOut = false, timeoutCause = "pcm16_stream_progressive_write_failed")
                        return false
                    }
                    is ProgressiveAudioItem.Failure -> throw item.throwable
                    ProgressiveAudioItem.Complete -> {
                        updatePlaybackTelemetry(audioWriteSucceeded = totalFrames > 0, totalFrames = totalFrames)
                        waitForPlaybackCompletion(streamTrack, totalFrames, sampleRate, utteranceId)
                        return true
                    }
                }
            }
        } finally {
            streamTrack.stopQuietly()
            streamTrack.releaseQuietly()
            synchronized(trackLock) {
                if (activeTrack === streamTrack) {
                    activeTrack = null
                }
            }
        }
    }

    private fun tryPlayStaticFloat(samples: FloatArray, sampleRate: Int, utteranceId: Long): Boolean {
        if (isUtteranceSuperseded(utteranceId)) {
            return false
        }
        val staticCreateStartNs = System.nanoTime()
        val floatTrack = createStaticTrack(
            sampleRate = sampleRate,
            encoding = AudioFormat.ENCODING_PCM_FLOAT,
            requestedBufferBytes = samples.size * 4,
        )
        val staticCreateMs = nanosToMillis(System.nanoTime() - staticCreateStartNs)
        if (floatTrack == null) {
            updatePlaybackTelemetry(
                audioTrackMode = "pcm_float_static",
                audioTrackInitialized = false,
                audioTrackCreateMs = staticCreateMs,
                audioWriteMs = 0.0,
                audioWriteFrames = AudioTrack.ERROR,
                audioWriteSucceeded = false,
            )
            return false
        }

        val staticWriteStartNs = System.nanoTime()
        val writtenFloatFrames = writeFloatSamples(floatTrack, samples)
        val staticWriteMs = nanosToMillis(System.nanoTime() - staticWriteStartNs)
        updatePlaybackTelemetry(
            audioTrackMode = "pcm_float_static",
            audioTrackInitialized = true,
            audioTrackCreateMs = staticCreateMs,
            audioWriteMs = staticWriteMs,
            audioWriteFrames = writtenFloatFrames,
            audioWriteSucceeded = writtenFloatFrames == samples.size,
        )
        if (writtenFloatFrames == samples.size) {
            playTrackToEnd(
                track = floatTrack,
                totalFrames = writtenFloatFrames,
                sampleRate = sampleRate,
                utteranceId = utteranceId,
            )
            return true
        }
        floatTrack.releaseQuietly()
        return false
    }

    private fun tryPlayStreamFloat(samples: FloatArray, sampleRate: Int, utteranceId: Long): Boolean {
        if (isUtteranceSuperseded(utteranceId)) {
            return false
        }
        val streamCreateStartNs = System.nanoTime()
        val streamTrack = createStreamTrack(
            sampleRate = sampleRate,
            encoding = AudioFormat.ENCODING_PCM_FLOAT,
        )
        val streamCreateMs = nanosToMillis(System.nanoTime() - streamCreateStartNs)
        if (streamTrack == null) {
            updatePlaybackTelemetry(
                audioTrackMode = "pcm_float_stream",
                audioTrackInitialized = false,
                audioTrackCreateMs = streamCreateMs,
                audioWriteMs = 0.0,
                audioWriteFrames = AudioTrack.ERROR,
                audioWriteSucceeded = false,
            )
            return false
        }

        synchronized(trackLock) {
            activeTrack = streamTrack
        }
        try {
            beginPlaybackTelemetry(totalFrames = samples.size, sampleRate = sampleRate)
            streamTrack.play()
            val streamWriteStartNs = System.nanoTime()
            val totalFrames = writeFloatSamplesStreaming(streamTrack, samples, utteranceId)
            val streamWriteMs = nanosToMillis(System.nanoTime() - streamWriteStartNs)
            updatePlaybackTelemetry(
                audioTrackMode = "pcm_float_stream",
                audioTrackInitialized = true,
                audioTrackCreateMs = streamCreateMs,
                audioWriteMs = streamWriteMs,
                audioWriteFrames = totalFrames,
                audioWriteSucceeded = totalFrames == samples.size,
            )
            if (totalFrames != samples.size) {
                finishPlaybackTelemetry(completed = false, timedOut = false, timeoutCause = "float_stream_write_failed")
                return false
            }
            waitForPlaybackCompletion(streamTrack, totalFrames, sampleRate, utteranceId)
            return true
        } finally {
            streamTrack.stopQuietly()
            streamTrack.releaseQuietly()
            synchronized(trackLock) {
                if (activeTrack === streamTrack) {
                    activeTrack = null
                }
            }
        }
    }

    private fun tryPlayStaticPcm16(samples: ShortArray, sampleRate: Int, utteranceId: Long): Boolean {
        if (isUtteranceSuperseded(utteranceId)) {
            return false
        }
        val staticCreateStartNs = System.nanoTime()
        val shortTrack = createStaticTrack(
            sampleRate = sampleRate,
            encoding = AudioFormat.ENCODING_PCM_16BIT,
            requestedBufferBytes = samples.size * 2,
        )
        val staticCreateMs = nanosToMillis(System.nanoTime() - staticCreateStartNs)
        if (shortTrack == null) {
            updatePlaybackTelemetry(
                audioTrackMode = "pcm16_static",
                audioTrackInitialized = false,
                audioTrackCreateMs = staticCreateMs,
                audioWriteMs = 0.0,
                audioWriteFrames = AudioTrack.ERROR,
                audioWriteSucceeded = false,
            )
            return false
        }

        val staticWriteStartNs = System.nanoTime()
        val writtenFrames = writePcm16Samples(shortTrack, samples)
        val staticWriteMs = nanosToMillis(System.nanoTime() - staticWriteStartNs)
        updatePlaybackTelemetry(
            audioTrackMode = "pcm16_static",
            audioTrackInitialized = true,
            audioTrackCreateMs = staticCreateMs,
            audioWriteMs = staticWriteMs,
            audioWriteFrames = writtenFrames,
            audioWriteSucceeded = writtenFrames == samples.size,
        )
        if (writtenFrames == samples.size) {
            playTrackToEnd(
                track = shortTrack,
                totalFrames = writtenFrames,
                sampleRate = sampleRate,
                utteranceId = utteranceId,
            )
            return true
        }
        shortTrack.releaseQuietly()
        return false
    }

    private fun tryPlayStreamPcm16(samples: ShortArray, sampleRate: Int, utteranceId: Long): Boolean {
        if (isUtteranceSuperseded(utteranceId)) {
            return false
        }
        val streamCreateStartNs = System.nanoTime()
        val streamTrack = createStreamTrack(
            sampleRate = sampleRate,
            encoding = AudioFormat.ENCODING_PCM_16BIT,
        )
        val streamCreateMs = nanosToMillis(System.nanoTime() - streamCreateStartNs)
        if (streamTrack == null) {
            updatePlaybackTelemetry(
                audioTrackMode = "pcm16_stream",
                audioTrackInitialized = false,
                audioTrackCreateMs = streamCreateMs,
                audioWriteMs = 0.0,
                audioWriteFrames = AudioTrack.ERROR,
                audioWriteSucceeded = false,
            )
            return false
        }

        synchronized(trackLock) {
            activeTrack = streamTrack
        }
        try {
            beginPlaybackTelemetry(totalFrames = samples.size, sampleRate = sampleRate)
            streamTrack.play()
            val streamWriteStartNs = System.nanoTime()
            val totalFrames = writePcm16SamplesStreaming(streamTrack, samples, utteranceId)
            val streamWriteMs = nanosToMillis(System.nanoTime() - streamWriteStartNs)
            updatePlaybackTelemetry(
                audioTrackMode = "pcm16_stream",
                audioTrackInitialized = true,
                audioTrackCreateMs = streamCreateMs,
                audioWriteMs = streamWriteMs,
                audioWriteFrames = totalFrames,
                audioWriteSucceeded = totalFrames == samples.size,
            )
            if (totalFrames != samples.size) {
                finishPlaybackTelemetry(completed = false, timedOut = false, timeoutCause = "pcm16_stream_write_failed")
                return false
            }
            waitForPlaybackCompletion(streamTrack, totalFrames, sampleRate, utteranceId)
            return true
        } finally {
            streamTrack.stopQuietly()
            streamTrack.releaseQuietly()
            synchronized(trackLock) {
                if (activeTrack === streamTrack) {
                    activeTrack = null
                }
            }
        }
    }

    private fun createStaticTrack(
        sampleRate: Int,
        encoding: Int,
        requestedBufferBytes: Int,
    ): AudioTrack? {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            encoding,
        )
        if (minBuffer <= 0) {
            return null
        }

        val track = runCatching {
            AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(encoding)
                    .build(),
                minBuffer.coerceAtLeast(requestedBufferBytes),
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
        }.getOrNull() ?: return null

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.releaseQuietly()
            return null
        }
        return track
    }

    private fun writeFloatSamples(track: AudioTrack, samples: FloatArray): Int {
        return runCatching {
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        }.getOrElse {
            Log.e(TAG, "Failed to write float samples to AudioTrack", it)
            AudioTrack.ERROR
        }
    }

    private fun writePcm16Samples(track: AudioTrack, samples: ShortArray): Int {
        return runCatching {
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        }.getOrElse {
            Log.e(TAG, "Failed to write PCM16 samples to AudioTrack", it)
            AudioTrack.ERROR
        }
    }

    private fun writeFloatSamplesStreaming(track: AudioTrack, samples: FloatArray, utteranceId: Long): Int {
        return writeSamplesStreaming(samples.size, utteranceId) { offset, length ->
            runCatching {
                track.write(samples, offset, length, AudioTrack.WRITE_BLOCKING)
            }.getOrElse {
                Log.e(TAG, "Failed while streaming float samples to AudioTrack", it)
                AudioTrack.ERROR
            }
        }
    }

    private fun writePcm16SamplesStreaming(track: AudioTrack, samples: ShortArray, utteranceId: Long): Int {
        return writeSamplesStreaming(samples.size, utteranceId) { offset, length ->
            runCatching {
                track.write(samples, offset, length, AudioTrack.WRITE_BLOCKING)
            }.getOrElse {
                Log.e(TAG, "Failed while streaming PCM16 samples to AudioTrack", it)
                AudioTrack.ERROR
            }
        }
    }

    private inline fun writeSamplesStreaming(
        totalSize: Int,
        utteranceId: Long,
        writeChunk: (offset: Int, length: Int) -> Int,
    ): Int {
        var offset = 0
        val chunkSize = 4096
        var zeroWriteRetries = 0
        while (offset < totalSize) {
            if (isUtteranceSuperseded(utteranceId)) {
                return AudioTrack.ERROR
            }
            val length = minOf(chunkSize, totalSize - offset)
            val written = writeChunk(offset, length)
            if (written < 0) {
                return AudioTrack.ERROR
            }
            if (written == 0) {
                zeroWriteRetries += 1
                if (zeroWriteRetries >= 100) {
                    return AudioTrack.ERROR
                }
                try {
                    Thread.sleep(10L)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return AudioTrack.ERROR
                }
                continue
            }

            zeroWriteRetries = 0
            offset += written
        }
        return offset
    }

    private fun convertFloatToPcm16(samples: FloatArray): ShortArray {
        val pcm16 = ShortArray(samples.size)
        for (index in samples.indices) {
            val clamped = samples[index].coerceIn(-1.0f, 1.0f)
            val scaled = (clamped * 32767.0f).roundToInt()
            pcm16[index] = scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return pcm16
    }

    private fun createStreamTrack(sampleRate: Int, encoding: Int): AudioTrack? {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            encoding,
        )
        if (minBuffer <= 0) {
            return null
        }

        val track = runCatching {
            AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(encoding)
                    .build(),
                minBuffer * 2,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
        }.getOrNull() ?: return null

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.releaseQuietly()
            return null
        }
        return track
    }

    private fun playTrackToEnd(track: AudioTrack, totalFrames: Int, sampleRate: Int, utteranceId: Long) {
        synchronized(trackLock) {
            activeTrack = track
        }
        try {
            beginPlaybackTelemetry(totalFrames = totalFrames, sampleRate = sampleRate)
            track.play()
            waitForPlaybackCompletion(track, totalFrames, sampleRate, utteranceId)
        } finally {
            track.stopQuietly()
            track.releaseQuietly()
            synchronized(trackLock) {
                if (activeTrack === track) {
                    activeTrack = null
                }
            }
        }
    }

    private fun waitForPlaybackCompletion(track: AudioTrack, totalFrames: Int, sampleRate: Int, utteranceId: Long) {
        val timeoutMs = playbackTimeoutMillis(totalFrames, sampleRate)
        val deadlineMs = SystemClock.elapsedRealtime() + timeoutMs
        var maxPlayedFrames = 0
        while (SystemClock.elapsedRealtime() <= deadlineMs) {
            if (isUtteranceSuperseded(utteranceId)) {
                finishPlaybackTelemetry(completed = false, timedOut = false, timeoutCause = "superseded")
                return
            }
            val playedFrames = runCatching { track.playbackHeadPosition }
                .getOrDefault(0)
            if (playedFrames > maxPlayedFrames) {
                maxPlayedFrames = playedFrames
                updatePlaybackTelemetry(maxPlaybackHeadFrames = maxPlayedFrames)
            }
            if (playedFrames >= totalFrames) {
                finishPlaybackTelemetry(completed = true, timedOut = false, timeoutCause = null)
                return
            }
            try {
                Thread.sleep(20L)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                finishPlaybackTelemetry(completed = false, timedOut = false, timeoutCause = "wait_interrupted")
                return
            }
        }
        val timeoutCause = if (maxPlayedFrames <= 0) "playback_head_stalled" else "deadline_elapsed"
        finishPlaybackTelemetry(completed = false, timedOut = true, timeoutCause = timeoutCause)
        Log.w(TAG, "Timed out waiting for local playback completion. sampleRate=$sampleRate frames=$totalFrames")
    }

    private fun beginUtteranceTelemetry(scheduledAtElapsedMs: Long, startedAtElapsedMs: Long, queueWaitMs: Long) {
        playbackTelemetryRef.set(
            LocalPlaybackTelemetry(
                scheduledAtElapsedMs = scheduledAtElapsedMs,
                runnableStartedAtElapsedMs = startedAtElapsedMs,
                queueWaitMs = queueWaitMs,
            ),
        )
    }

    private fun beginPlaybackTelemetry(totalFrames: Int, sampleRate: Int) {
        val startedAtElapsedMs = SystemClock.elapsedRealtime()
        playbackTelemetryRef.updateAndGet { current ->
            current.copy(
                startedAtElapsedMs = startedAtElapsedMs,
                playbackStartDelayMs = startedAtElapsedMs - current.runnableStartedAtElapsedMs,
                totalFrames = totalFrames,
                sampleRate = sampleRate,
                maxPlaybackHeadFrames = 0,
                completed = false,
                timedOut = false,
                timeoutCause = null,
            )
        }
    }

    private fun updatePlaybackTelemetry(
        maxPlaybackHeadFrames: Int? = null,
        ensureLoadedMs: Double? = null,
        generationMs: Double? = null,
        firstChunkGenerationMs: Double? = null,
        segmentCount: Int? = null,
        totalFrames: Int? = null,
        streamingMode: String? = null,
        streamingGapCount: Int? = null,
        maxStreamingGapMs: Long? = null,
        generatedSampleCount: Int? = null,
        generatedSampleRate: Int? = null,
        generatedPcmNonEmpty: Boolean? = null,
        audioTrackMode: String? = null,
        audioTrackInitialized: Boolean? = null,
        audioTrackCreateMs: Double? = null,
        audioWriteMs: Double? = null,
        audioWriteFrames: Int? = null,
        audioWriteSucceeded: Boolean? = null,
        failureReason: String? = null,
    ) {
        playbackTelemetryRef.updateAndGet { current ->
            current.copy(
                maxPlaybackHeadFrames = if (maxPlaybackHeadFrames != null) {
                    maxOf(current.maxPlaybackHeadFrames, maxPlaybackHeadFrames)
                } else {
                    current.maxPlaybackHeadFrames
                },
                ensureLoadedMs = ensureLoadedMs ?: current.ensureLoadedMs,
                generationMs = generationMs ?: current.generationMs,
                firstChunkGenerationMs = firstChunkGenerationMs ?: current.firstChunkGenerationMs,
                segmentCount = segmentCount ?: current.segmentCount,
                totalFrames = totalFrames ?: current.totalFrames,
                streamingMode = streamingMode ?: current.streamingMode,
                streamingGapCount = streamingGapCount ?: current.streamingGapCount,
                maxStreamingGapMs = maxStreamingGapMs ?: current.maxStreamingGapMs,
                generatedSampleCount = generatedSampleCount ?: current.generatedSampleCount,
                generatedSampleRate = generatedSampleRate ?: current.generatedSampleRate,
                generatedPcmNonEmpty = generatedPcmNonEmpty ?: current.generatedPcmNonEmpty,
                audioTrackMode = audioTrackMode ?: current.audioTrackMode,
                audioTrackInitialized = audioTrackInitialized ?: current.audioTrackInitialized,
                audioTrackCreateMs = audioTrackCreateMs ?: current.audioTrackCreateMs,
                audioWriteMs = audioWriteMs ?: current.audioWriteMs,
                audioWriteFrames = audioWriteFrames ?: current.audioWriteFrames,
                audioWriteSucceeded = audioWriteSucceeded ?: current.audioWriteSucceeded,
                failureReason = failureReason ?: current.failureReason,
            )
        }
    }

    private fun finishPlaybackTelemetry(completed: Boolean, timedOut: Boolean, timeoutCause: String?) {
        playbackTelemetryRef.updateAndGet { current ->
            current.copy(
                completed = completed,
                timedOut = timedOut,
                timeoutCause = timeoutCause,
            )
        }
    }

    private fun logPlaybackTelemetryOnce() {
        val telemetry = playbackTelemetryRef.get()
        Log.i(
            TAG,
            "LOCAL_TTS_TELEMETRY queueWaitMs=${telemetry.queueWaitMs} ensureLoadedMs=${telemetry.ensureLoadedMs} generationMs=${telemetry.generationMs} firstChunkGenerationMs=${telemetry.firstChunkGenerationMs} playbackStartDelayMs=${telemetry.playbackStartDelayMs} segmentCount=${telemetry.segmentCount} streamingMode=${telemetry.streamingMode} streamingGapCount=${telemetry.streamingGapCount} maxStreamingGapMs=${telemetry.maxStreamingGapMs} generatedPcmNonEmpty=${telemetry.generatedPcmNonEmpty} generatedSampleCount=${telemetry.generatedSampleCount} generatedSampleRate=${telemetry.generatedSampleRate} audioTrackMode=${telemetry.audioTrackMode} audioTrackInitialized=${telemetry.audioTrackInitialized} audioTrackCreateMs=${telemetry.audioTrackCreateMs} audioWriteMs=${telemetry.audioWriteMs} audioWriteFrames=${telemetry.audioWriteFrames} audioWriteSucceeded=${telemetry.audioWriteSucceeded} maxPlaybackHeadFrames=${telemetry.maxPlaybackHeadFrames} completed=${telemetry.completed} timedOut=${telemetry.timedOut} timeoutCause=${telemetry.timeoutCause} failureReason=${telemetry.failureReason}",
        )
    }

    private fun nanosToMillis(durationNs: Long): Double = durationNs / 1_000_000.0

    companion object {
        const val TAG = "LocalModelTtsEngine"
        const val MIN_LOCAL_SPEED = 0.85f
        const val MAX_LOCAL_SPEED = 1.15f
        private const val KOKORO_MIN_SPEAKER_ID = 0
        private const val KOKORO_MAX_SPEAKER_ID = 10
        private const val MODEL_CACHE_CAPACITY = 2
    }
}

data class LocalTtsBenchmarkMetrics(
    val generationMillis: Double,
    val firstChunkGenerationMillis: Double,
    val audioDurationMillis: Double,
    val realTimeFactor: Double,
    val sampleRate: Int,
    val sampleCount: Int,
    val segmentCount: Int,
)

private data class ExtractedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
)

internal enum class LocalStreamingMode {
    CALLBACK,
    SEGMENT_PROGRESSIVE,
    BATCH,
}

internal data class LocalPlaybackTelemetry(
    val scheduledAtElapsedMs: Long = 0L,
    val runnableStartedAtElapsedMs: Long = 0L,
    val queueWaitMs: Long = 0L,
    val startedAtElapsedMs: Long = 0L,
    val playbackStartDelayMs: Long = 0L,
    val ensureLoadedMs: Double = 0.0,
    val generationMs: Double = 0.0,
    val firstChunkGenerationMs: Double = 0.0,
    val segmentCount: Int = 1,
    val streamingMode: String = LocalStreamingMode.BATCH.name,
    val streamingGapCount: Int = 0,
    val maxStreamingGapMs: Long = 0L,
    val generatedSampleCount: Int = 0,
    val generatedSampleRate: Int = 0,
    val generatedPcmNonEmpty: Boolean = false,
    val audioTrackMode: String = "",
    val audioTrackInitialized: Boolean = false,
    val audioTrackCreateMs: Double = 0.0,
    val audioWriteMs: Double = 0.0,
    val audioWriteFrames: Int = 0,
    val audioWriteSucceeded: Boolean = false,
    val totalFrames: Int = 0,
    val sampleRate: Int = 0,
    val maxPlaybackHeadFrames: Int = 0,
    val completed: Boolean = false,
    val timedOut: Boolean = false,
    val timeoutCause: String? = null,
    val failureReason: String? = null,
)

private data class SegmentedAudioResult(
    val audio: com.k2fsa.sherpa.onnx.GeneratedAudio,
    val firstChunkGenerationMs: Double,
    val totalGenerationMs: Double,
)

private sealed class ProgressiveAudioItem {
    data class Audio(val audio: com.k2fsa.sherpa.onnx.GeneratedAudio) : ProgressiveAudioItem()

    data class Failure(val throwable: Throwable) : ProgressiveAudioItem()

    data object Complete : ProgressiveAudioItem()
}

private class ProgressiveAudioStream {
    private val queue = LinkedBlockingQueue<ProgressiveAudioItem>()

    fun offer(audio: com.k2fsa.sherpa.onnx.GeneratedAudio) {
        queue.offer(ProgressiveAudioItem.Audio(audio))
    }

    fun fail(throwable: Throwable) {
        queue.offer(ProgressiveAudioItem.Failure(throwable))
    }

    fun finish() {
        queue.offer(ProgressiveAudioItem.Complete)
    }

    fun pollNext(utteranceId: Long): ProgressiveAudioItem? {
        while (true) {
            val item = queue.poll(100, TimeUnit.MILLISECONDS)
            if (item != null) {
                return item
            }
            if (Thread.currentThread().isInterrupted()) {
                return null
            }
        }
    }

    fun collectAll(firstAudio: com.k2fsa.sherpa.onnx.GeneratedAudio): List<com.k2fsa.sherpa.onnx.GeneratedAudio> {
        val collected = mutableListOf(firstAudio)
        while (true) {
            when (val item = queue.take()) {
                is ProgressiveAudioItem.Audio -> collected += item.audio
                is ProgressiveAudioItem.Failure -> throw item.throwable
                ProgressiveAudioItem.Complete -> return collected
            }
        }
    }
}

internal fun synthesisSegments(text: String): List<String> {
    val normalized = text.trim()
    if (normalized.isEmpty()) {
        return emptyList()
    }
    return DeterministicTextChunker.chunk(normalized).ifEmpty { listOf(normalized) }
}

internal fun playbackTimeoutMillis(sampleCount: Int, sampleRate: Int): Long {
    if (sampleCount <= 0 || sampleRate <= 0) {
        return 600L
    }
    val durationMs = sampleCount * 1000L / sampleRate
    return maxOf(600L, durationMs * 2L)
}

private fun AudioTrack.stopQuietly() {
    runCatching { this.stop() }
}

private fun AudioTrack.releaseQuietly() {
    runCatching { this.release() }
}
