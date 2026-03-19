package com.example.bookhelper.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalModelSynthesisSegmentationTest {
    @Test
    fun synthesisSegmentsSplitSentenceBoundariesDeterministically() {
        assertEquals(
            listOf("Hello world.", "How are you?", "I am fine!"),
            synthesisSegments("Hello world. How are you? I am fine!"),
        )
    }

    @Test
    fun synthesisSegmentsFallbackToSingleChunkWhenNoBoundaryExists() {
        assertEquals(
            listOf("hello world without punctuation"),
            synthesisSegments("hello world without punctuation"),
        )
    }
}
