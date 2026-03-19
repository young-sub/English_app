package com.example.bookhelper.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReaderContractsTest {
    @Test
    fun boundingBoxContainsPoint() {
        val box = BoundingBox(10, 10, 20, 20)
        assertTrue(box.contains(15, 15))
        assertFalse(box.contains(5, 5))
    }

    @Test
    fun emptyPageHasNoBlocks() {
        assertEquals(0, OcrPage.EMPTY.blocks.size)
        assertEquals("", OcrPage.EMPTY.fullText)
    }
}
