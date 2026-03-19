package com.example.bookhelper.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimpleOcrEngineTest {
    @Test
    fun recognizeBuildsPageWithWordsAndText() {
        val engine = SimpleOcrEngine()
        val page = engine.recognize(listOf("hello world", "second line"))

        assertEquals(1, page.blocks.size)
        assertEquals(2, page.blocks.first().lines.size)
        assertEquals("hello", page.blocks.first().lines.first().words.first().text)
        assertTrue(page.fullText.contains("second line"))
    }
}
