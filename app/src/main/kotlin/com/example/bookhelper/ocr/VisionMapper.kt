package com.example.bookhelper.ocr

import com.example.bookhelper.contracts.BoundingBox
import com.example.bookhelper.contracts.OcrBlock
import com.example.bookhelper.contracts.OcrLine
import com.example.bookhelper.contracts.OcrPage
import com.example.bookhelper.contracts.OcrWord
import com.google.mlkit.vision.text.Text

object VisionMapper {
    fun toOcrPage(visionText: Text): OcrPage {
        val blocks = visionText.textBlocks.map { block ->
            OcrBlock(
                lines = block.lines.map { line ->
                    OcrLine(
                        words = line.elements.map { element ->
                            OcrWord(
                                text = element.text,
                                boundingBox = element.boundingBox?.let {
                                    BoundingBox(it.left, it.top, it.right, it.bottom)
                                },
                            )
                        },
                        text = line.text,
                        boundingBox = line.boundingBox?.let {
                            BoundingBox(it.left, it.top, it.right, it.bottom)
                        },
                    )
                },
                boundingBox = block.boundingBox?.let {
                    BoundingBox(it.left, it.top, it.right, it.bottom)
                },
            )
        }

        return OcrPage(blocks = blocks, fullText = visionText.text)
    }
}
