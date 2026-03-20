package com.example.bookhelper.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import java.util.concurrent.atomic.AtomicBoolean

internal class PiperStreamingSession(
    private val tts: OfflineTts,
    private val utteranceId: Long,
    private val isUtteranceSuperseded: (Long) -> Boolean,
    private val updateTelemetry: (PiperStreamingTelemetry) -> Unit,
    private val onPlaybackStarted: (totalFrames: Int, sampleRate: Int) -> Unit,
    private val onPlaybackFinished: (completed: Boolean, timedOut: Boolean, timeoutCause: String?) -> Unit,
) {
    private val stopped = AtomicBoolean(false)
    private var activeTrack: AudioTrack? = null

    fun play(text: String, speakerId: Int, speed: Float): PiperStreamingResult {
        val sampleRate = tts.sampleRate()
        Log.i(TAG, "play(): sampleRate=$sampleRate utteranceId=$utteranceId")
        val track = createStreamTrack(sampleRate)
            ?: throw IllegalStateException("Failed to initialize callback AudioTrack")
        activeTrack = track

        val generationStartNs = System.nanoTime()
        var firstCallback = true
        var totalFrames = 0
        var totalWriteMs = 0.0
        var gapCount = 0
        var maxGapMs = 0L
        var lastWriteFinishedAt = 0L

        try {
            Log.i(TAG, "play(): track initialized, calling play()")
            track.play()
            updateTelemetry(
                PiperStreamingTelemetry(
                    audioTrackMode = "pcm_float_stream_callback",
                    audioTrackInitialized = true,
                    audioTrackCreateMs = 0.0,
                ),
            )

            val generatedAudio = tts.generateWithCallback(
                text = text,
                sid = speakerId,
                speed = speed,
            ) { samples ->
                if (firstCallback) {
                    Log.i(TAG, "callback(): first invocation size=${samples.size}")
                }
                if (samples.isEmpty()) {
                    return@generateWithCallback 1
                }
                if (stopped.get() || isUtteranceSuperseded(utteranceId)) {
                    return@generateWithCallback 0
                }

                val callbackNow = SystemClock.elapsedRealtime()
                if (firstCallback) {
                    firstCallback = false
                    onPlaybackStarted(0, sampleRate)
                    updateTelemetry(
                        PiperStreamingTelemetry(
                            firstChunkGenerationMs = nanosToMillis(System.nanoTime() - generationStartNs),
                        ),
                    )
                } else {
                    val gapMs = (callbackNow - lastWriteFinishedAt).coerceAtLeast(0L)
                    if (gapMs > CALLBACK_GAP_THRESHOLD_MS) {
                        gapCount += 1
                        maxGapMs = maxOf(maxGapMs, gapMs)
                    }
                }

                val writeStartNs = System.nanoTime()
                val written = writeFloatSamplesStreaming(track, samples)
                if (firstCallback) {
                    Log.i(TAG, "callback(): first write result=$written requested=${samples.size}")
                }
                totalWriteMs += nanosToMillis(System.nanoTime() - writeStartNs)
                lastWriteFinishedAt = SystemClock.elapsedRealtime()
                if (written != samples.size) {
                    return@generateWithCallback 0
                }

                totalFrames += written
                updateTelemetry(
                    PiperStreamingTelemetry(
                        totalFrames = totalFrames,
                        maxPlaybackHeadFrames = totalFrames,
                        audioWriteMs = totalWriteMs,
                        audioWriteFrames = totalFrames,
                        audioWriteSucceeded = true,
                        streamingGapCount = gapCount,
                        maxStreamingGapMs = maxGapMs,
                    ),
                )
                1
            }

            updateTelemetry(
                PiperStreamingTelemetry(
                    generationMs = nanosToMillis(System.nanoTime() - generationStartNs),
                    generatedSampleCount = generatedAudio.samples.size,
                    generatedSampleRate = generatedAudio.sampleRate,
                    generatedPcmNonEmpty = generatedAudio.samples.isNotEmpty() && generatedAudio.sampleRate > 0,
                    streamingGapCount = gapCount,
                    maxStreamingGapMs = maxGapMs,
                ),
            )
            Log.i(TAG, "play(): generation completed totalFrames=$totalFrames sampleRate=$sampleRate")
            onPlaybackFinished(true, false, null)
            return PiperStreamingResult(
                totalFrames = totalFrames,
                sampleRate = sampleRate,
            )
        } finally {
            Log.i(TAG, "play(): cleanupTrack()")
            cleanupTrack()
        }
    }

    fun stop() {
        stopped.set(true)
        cleanupTrack()
    }

    private fun cleanupTrack() {
        activeTrack?.let { track ->
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        activeTrack = null
    }

    private fun createStreamTrack(sampleRate: Int): AudioTrack? {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
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
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build(),
                minBuffer * 2,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
        }.getOrNull() ?: return null

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            return null
        }
        return track
    }

    private fun writeFloatSamplesStreaming(track: AudioTrack, samples: FloatArray): Int {
        var offset = 0
        while (offset < samples.size) {
            if (stopped.get() || isUtteranceSuperseded(utteranceId)) {
                return AudioTrack.ERROR
            }
            val written = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) {
                return AudioTrack.ERROR
            }
            offset += written
        }
        return offset
    }

    private fun nanosToMillis(durationNs: Long): Double = durationNs / 1_000_000.0

    private companion object {
        private const val TAG = "PiperStreamingSession"
        private const val CALLBACK_GAP_THRESHOLD_MS = 50L
    }
}

internal data class PiperStreamingTelemetry(
    val firstChunkGenerationMs: Double? = null,
    val generationMs: Double? = null,
    val streamingGapCount: Int? = null,
    val maxStreamingGapMs: Long? = null,
    val generatedSampleCount: Int? = null,
    val generatedSampleRate: Int? = null,
    val generatedPcmNonEmpty: Boolean? = null,
    val audioTrackMode: String? = null,
    val audioTrackInitialized: Boolean? = null,
    val audioTrackCreateMs: Double? = null,
    val audioWriteMs: Double? = null,
    val audioWriteFrames: Int? = null,
    val audioWriteSucceeded: Boolean? = null,
    val totalFrames: Int? = null,
    val maxPlaybackHeadFrames: Int? = null,
)

internal data class PiperStreamingResult(
    val totalFrames: Int,
    val sampleRate: Int,
)
