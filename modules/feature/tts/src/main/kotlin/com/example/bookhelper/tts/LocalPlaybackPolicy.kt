package com.example.bookhelper.tts

fun playbackTimeoutMillis(sampleCount: Int, sampleRate: Int): Long {
    if (sampleCount <= 0 || sampleRate <= 0) {
        return 600L
    }
    val durationMs = sampleCount * 1000L / sampleRate
    return maxOf(600L, durationMs * 2L)
}
