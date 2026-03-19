package com.example.bookhelper.ui

import com.example.bookhelper.contracts.BoundingBox
import com.example.bookhelper.contracts.OcrBlock
import com.example.bookhelper.contracts.OcrLine
import com.example.bookhelper.contracts.OcrPage
import com.example.bookhelper.contracts.OcrWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderOverlayModelTest {
    @Test
    fun buildReaderOverlayModelFlattensSnapshotWordsAndHighlightsSelectedLine() {
        val selectedWord = OcrWord(text = "book", boundingBox = BoundingBox(12, 12, 24, 24))
        val page = OcrPage(
            blocks = listOf(
                OcrBlock(
                    lines = listOf(
                        OcrLine(
                            words = listOf(
                                OcrWord(text = "read", boundingBox = BoundingBox(0, 12, 10, 24)),
                                selectedWord,
                            ),
                            text = "read book",
                            boundingBox = BoundingBox(0, 10, 24, 26),
                        ),
                    ),
                    boundingBox = BoundingBox(0, 10, 24, 26),
                ),
            ),
            fullText = "read book",
        )

        val overlay = buildReaderOverlayModel(
            page = page,
            selectedSentence = null,
            selectedWord = selectedWord,
            selectedWords = listOf(selectedWord),
            renderAllDetected = true,
        )

        assertEquals(1, overlay.highlightedLines.size)
        assertEquals(2, overlay.words.size)
        assertTrue(overlay.words.any { it.isSelected })
    }

    @Test
    fun buildReaderOverlayModelKeepsOnlySelectedWordOutsideSnapshotMode() {
        val selectedWord = OcrWord(text = "book", boundingBox = BoundingBox(12, 12, 24, 24))
        val page = OcrPage(
            blocks = listOf(
                OcrBlock(
                    lines = listOf(
                        OcrLine(
                            words = listOf(
                                OcrWord(text = "read", boundingBox = BoundingBox(0, 12, 10, 24)),
                                selectedWord,
                            ),
                            text = "read book",
                            boundingBox = BoundingBox(0, 10, 24, 26),
                        ),
                    ),
                    boundingBox = BoundingBox(0, 10, 24, 26),
                ),
            ),
            fullText = "read book",
        )

        val overlay = buildReaderOverlayModel(
            page = page,
            selectedSentence = null,
            selectedWord = selectedWord,
            selectedWords = listOf(selectedWord),
            renderAllDetected = false,
        )

        assertEquals(1, overlay.words.size)
        assertTrue(overlay.words.single().isSelected)
    }

    @Test
    fun buildReaderOverlayModelHighlightsAllLinesForMultiWordSelection() {
        val first = OcrWord(text = "read", boundingBox = BoundingBox(0, 12, 10, 24))
        val second = OcrWord(text = "book", boundingBox = BoundingBox(12, 12, 24, 24))
        val third = OcrWord(text = "today", boundingBox = BoundingBox(0, 42, 20, 54))
        val page = OcrPage(
            blocks = listOf(
                OcrBlock(
                    lines = listOf(
                        OcrLine(
                            words = listOf(first, second),
                            text = "read book",
                            boundingBox = BoundingBox(0, 10, 24, 26),
                        ),
                        OcrLine(
                            words = listOf(third),
                            text = "today",
                            boundingBox = BoundingBox(0, 40, 20, 56),
                        ),
                    ),
                    boundingBox = BoundingBox(0, 10, 24, 56),
                ),
            ),
            fullText = "read book today",
        )

        val overlay = buildReaderOverlayModel(
            page = page,
            selectedSentence = "read book today",
            selectedWord = first,
            selectedWords = listOf(first, second, third),
            renderAllDetected = false,
        )

        assertEquals(2, overlay.highlightedLines.size)
        assertEquals(3, overlay.words.size)
    }
}
