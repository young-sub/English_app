package com.example.bookhelper.camera

import com.example.bookhelper.contracts.BoundingBox
import com.example.bookhelper.contracts.OcrBlock
import com.example.bookhelper.contracts.OcrLine
import com.example.bookhelper.contracts.OcrPage
import com.example.bookhelper.contracts.OcrWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BookPageOcrPostProcessorTest {
    @Test
    fun refineFiltersOutRoiOutsideTinyAndNoiseWords() {
        val input = OcrPage(
            blocks = listOf(
                OcrBlock(
                    lines = listOf(
                        OcrLine(
                            words = listOf(
                                OcrWord("outside", BoundingBox(0, 0, 20, 20)),
                                OcrWord("tiny", BoundingBox(100, 100, 103, 106)),
                                OcrWord("***", BoundingBox(120, 120, 180, 150)),
                                OcrWord("Hello,", BoundingBox(200, 200, 280, 240)),
                                OcrWord("world!", BoundingBox(290, 200, 380, 240)),
                            ),
                            text = "raw line",
                            boundingBox = null,
                        ),
                    ),
                    boundingBox = null,
                ),
            ),
            fullText = "raw",
        )

        val refined = BookPageOcrPostProcessor.refine(page = input, width = 1000, height = 1000)

        assertEquals(1, refined.blocks.size)
        assertEquals(1, refined.blocks.first().lines.size)
        val words = refined.blocks.first().lines.first().words
        assertEquals(listOf("Hello", "world"), words.map { it.text })
        assertEquals("Hello world", refined.fullText)
    }

    @Test
    fun refineSortsLinesAndWordsAndBuildsMergedLineBoxes() {
        val input = OcrPage(
            blocks = listOf(
                OcrBlock(
                    lines = listOf(
                        OcrLine(
                            words = listOf(
                                OcrWord("third", BoundingBox(320, 340, 390, 380)),
                                OcrWord("first", BoundingBox(120, 340, 210, 380)),
                            ),
                            text = "third first",
                            boundingBox = null,
                        ),
                        OcrLine(
                            words = listOf(
                                OcrWord("early", BoundingBox(140, 180, 240, 220)),
                            ),
                            text = "early",
                            boundingBox = null,
                        ),
                    ),
                    boundingBox = null,
                ),
            ),
            fullText = "unordered",
        )

        val refined = BookPageOcrPostProcessor.refine(page = input, width = 1000, height = 1000)

        val lines = refined.blocks.first().lines
        assertEquals("early", lines[0].text)
        assertEquals("first third", lines[1].text)
        assertEquals("early\nfirst third", refined.fullText)

        val mergedBox = requireNotNull(lines[1].boundingBox)
        assertNotNull(mergedBox)
        assertEquals(120, mergedBox.left)
        assertEquals(390, mergedBox.right)
    }
}
