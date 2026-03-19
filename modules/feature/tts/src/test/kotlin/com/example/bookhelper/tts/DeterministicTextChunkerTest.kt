package com.example.bookhelper.tts

import kotlin.test.Test
import kotlin.test.assertEquals

class DeterministicTextChunkerTest {
    @Test
    fun splitsShortTextIntoSentenceChunksInOrder() {
        val chunks = DeterministicTextChunker.chunk("Hello world. How are you? I am fine!")

        assertEquals(listOf("Hello world.", "How are you?", "I am fine!"), chunks)
    }

    @Test
    fun keepsSingleChunkWhenTextHasNoSentenceDelimiter() {
        val chunks = DeterministicTextChunker.chunk("This text has no sentence delimiter")

        assertEquals(listOf("This text has no sentence delimiter"), chunks)
    }
}
