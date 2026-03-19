package com.example.bookhelper.tts

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalModelPlaybackPolicyTest {
    @Test
    fun playbackTimeoutAddsSafetyMargin() {
        val timeout = playbackTimeoutMillis(
            sampleCount = 24_000,
            sampleRate = 24_000,
        )

        assertEquals(2_000L, timeout)
    }

    @Test
    fun playbackTimeoutUsesMinimumWhenAudioIsVeryShort() {
        val timeout = playbackTimeoutMillis(
            sampleCount = 20,
            sampleRate = 24_000,
        )

        assertEquals(600L, timeout)
    }
}
