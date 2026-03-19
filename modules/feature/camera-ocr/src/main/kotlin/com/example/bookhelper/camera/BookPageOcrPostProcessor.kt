package com.example.bookhelper.camera

import com.example.bookhelper.contracts.BoundingBox
import com.example.bookhelper.contracts.OcrBlock
import com.example.bookhelper.contracts.OcrLine
import com.example.bookhelper.contracts.OcrPage
import com.example.bookhelper.contracts.OcrWord

object BookPageOcrPostProcessor {
    fun refine(page: OcrPage, width: Int, height: Int): OcrPage {
        val roi = BoundingBox(
            left = (width * 0.03f).toInt(),
            top = (height * 0.05f).toInt(),
            right = (width * 0.97f).toInt(),
            bottom = (height * 0.95f).toInt(),
        )

        val filteredBlocks = page.blocks.mapNotNull { block ->
            val filteredLines = block.lines.mapNotNull { line ->
                val filteredWords = line.words
                    .mapNotNull { word -> sanitizeWord(word, roi, width, height) }
                    .sortedBy { it.boundingBox?.left ?: Int.MAX_VALUE }
                if (filteredWords.isEmpty()) {
                    null
                } else {
                    OcrLine(
                        words = filteredWords,
                        text = filteredWords
                            .joinToString(" ") { it.text },
                        boundingBox = mergeBoxes(filteredWords.mapNotNull { it.boundingBox }),
                    )
                }
            }
                .sortedBy { it.boundingBox?.top ?: Int.MAX_VALUE }

            if (filteredLines.isEmpty()) {
                null
            } else {
                OcrBlock(filteredLines, mergeBoxes(filteredLines.mapNotNull { it.boundingBox }))
            }
        }

        val fullText = filteredBlocks
            .flatMap { it.lines }
            .joinToString("\n") { it.text }

        return OcrPage(filteredBlocks, fullText)
    }

    private fun sanitizeWord(
        word: OcrWord,
        roi: BoundingBox,
        width: Int,
        height: Int,
    ): OcrWord? {
        val box = word.boundingBox ?: return null
        if (!roi.contains((box.left + box.right) / 2, (box.top + box.bottom) / 2)) {
            return null
        }

        val boxWidth = (box.right - box.left).coerceAtLeast(0)
        val boxHeight = (box.bottom - box.top).coerceAtLeast(0)
        if (boxWidth < (width * 0.008f) || boxHeight < (height * 0.01f)) {
            return null
        }

        val cleaned = word.text
            .replace(Regex("^[^A-Za-z0-9]+|[^A-Za-z0-9']+$"), "")
            .trim()
        if (cleaned.isBlank()) {
            return null
        }

        return OcrWord(
            text = cleaned,
            boundingBox = box,
        )
    }

    private fun mergeBoxes(boxes: List<BoundingBox>): BoundingBox? {
        if (boxes.isEmpty()) {
            return null
        }
        return BoundingBox(
            left = boxes.minOf { it.left },
            top = boxes.minOf { it.top },
            right = boxes.maxOf { it.right },
            bottom = boxes.maxOf { it.bottom },
        )
    }
}
