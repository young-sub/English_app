package com.example.bookhelper.camera

import com.example.bookhelper.contracts.BoundingBox
import com.example.bookhelper.contracts.OcrBlock
import com.example.bookhelper.contracts.OcrLine
import com.example.bookhelper.contracts.OcrPage
import com.example.bookhelper.contracts.OcrWord

class SimpleOcrEngine {
    fun recognize(lines: List<String>): OcrPage {
        val ocrLines = lines.mapIndexed { lineIndex, lineText ->
            val words = lineText.split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .mapIndexed { wordIndex, word ->
                    val left = wordIndex * 100
                    val top = lineIndex * 40
                    OcrWord(word, BoundingBox(left, top, left + 90, top + 30))
                }

            OcrLine(
                words = words,
                text = lineText,
                boundingBox = BoundingBox(0, lineIndex * 40, 1000, lineIndex * 40 + 30),
            )
        }

        return OcrPage(
            blocks = listOf(
                OcrBlock(
                    lines = ocrLines,
                    boundingBox = BoundingBox(0, 0, 1000, (lines.size * 40).coerceAtLeast(40)),
                ),
            ),
            fullText = lines.joinToString("\n"),
        )
    }
}
