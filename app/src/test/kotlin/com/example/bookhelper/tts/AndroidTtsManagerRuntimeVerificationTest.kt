package com.example.bookhelper.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTtsManagerRuntimeVerificationTest {
    @Test
    fun runtimeReadinessUsesSmokeCheckInsteadOfBenchmark() {
        val verifier = RecordingRuntimeVerifier(
            readinessResult = Result.success(Unit),
            benchmarkResult = Result.failure(IllegalStateException("not-used")),
        )

        val outcome = verifyLocalRuntimeReadiness(verifier = verifier, speed = 1.0f)

        assertTrue(outcome.ready)
        assertEquals(null, outcome.failureReason)
        assertEquals(1, verifier.readinessCalls)
        assertEquals(0, verifier.benchmarkCalls)
    }

    @Test
    fun runtimeReadinessFailureRetainsErrorWithoutBenchmarkFallback() {
        val readinessFailure = IllegalStateException("model load failed")
        val verifier = RecordingRuntimeVerifier(
            readinessResult = Result.failure(readinessFailure),
            benchmarkResult = Result.success(
                LocalTtsBenchmarkMetrics(
                    generationMillis = 1.0,
                    firstChunkGenerationMillis = 1.0,
                    audioDurationMillis = 1.0,
                    realTimeFactor = 1.0,
                    sampleRate = 24_000,
                    sampleCount = 100,
                    segmentCount = 1,
                ),
            ),
        )
        var callbackThrowable: Throwable? = null

        val outcome = verifyLocalRuntimeReadiness(
            verifier = verifier,
            speed = 1.0f,
            onVerificationFailed = { callbackThrowable = it },
        )

        assertFalse(outcome.ready)
        assertEquals("IllegalStateException: model load failed", outcome.failureReason)
        assertEquals(readinessFailure, callbackThrowable)
        assertEquals(1, verifier.readinessCalls)
        assertEquals(0, verifier.benchmarkCalls)
    }
}

private class RecordingRuntimeVerifier(
    private val readinessResult: Result<Unit>,
    private val benchmarkResult: Result<LocalTtsBenchmarkMetrics>,
) : LocalRuntimeVerifier {
    var readinessCalls: Int = 0
        private set
    var benchmarkCalls: Int = 0
        private set

    override fun verifyRuntimeReady(speed: Float): Result<Unit> {
        readinessCalls += 1
        return readinessResult
    }

    override fun benchmarkSynthesis(text: String, speed: Float): Result<LocalTtsBenchmarkMetrics> {
        benchmarkCalls += 1
        return benchmarkResult
    }
}
