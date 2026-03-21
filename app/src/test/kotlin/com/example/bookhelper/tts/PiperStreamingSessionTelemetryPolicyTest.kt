package com.example.bookhelper.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiperStreamingSessionTelemetryPolicyTest {
    @Test
    fun callbackTelemetryEmissionIsThrottledButIncludesFirstChunk() {
        val decisions = (1..9).map { callbackCount ->
            shouldEmitCallbackTelemetryUpdate(callbackCount)
        }

        assertEquals(
            listOf(true, false, false, false, true, false, false, false, true),
            decisions,
        )
    }

    @Test
    fun callbackTelemetryFinalSnapshotRetainsLatestCounters() {
        val telemetry = finalizePiperStreamingTelemetry(
            generationMs = 42.5,
            generatedSampleCount = 2048,
            generatedSampleRate = 22_050,
            totalFrames = 1024,
            totalWriteMs = 12.75,
            gapCount = 3,
            maxGapMs = 88,
        )

        assertEquals(42.5, telemetry.generationMs!!, 0.0001)
        assertEquals(2048, telemetry.generatedSampleCount)
        assertEquals(22_050, telemetry.generatedSampleRate)
        assertTrue(telemetry.generatedPcmNonEmpty == true)
        assertEquals(1024, telemetry.totalFrames)
        assertEquals(1024, telemetry.maxPlaybackHeadFrames)
        assertEquals(12.75, telemetry.audioWriteMs!!, 0.0001)
        assertEquals(1024, telemetry.audioWriteFrames)
        assertTrue(telemetry.audioWriteSucceeded == true)
        assertEquals(3, telemetry.streamingGapCount)
        assertEquals(88L, telemetry.maxStreamingGapMs)
    }

    @Test
    fun callbackTelemetryFinalSnapshotMarksEmptyGenerationAsEmptyPcm() {
        val telemetry = finalizePiperStreamingTelemetry(
            generationMs = 8.0,
            generatedSampleCount = 0,
            generatedSampleRate = 22_050,
            totalFrames = 0,
            totalWriteMs = 0.0,
            gapCount = 0,
            maxGapMs = 0,
        )

        assertFalse(telemetry.generatedPcmNonEmpty == true)
        assertFalse(telemetry.audioWriteSucceeded == true)
    }
}
