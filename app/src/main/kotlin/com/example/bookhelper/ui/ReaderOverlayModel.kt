package com.example.bookhelper.ui

import com.example.bookhelper.contracts.BoundingBox
import com.example.bookhelper.contracts.OcrLine
import com.example.bookhelper.contracts.OcrPage
import com.example.bookhelper.contracts.OcrWord

internal data class ReaderOverlayWordBox(
    val box: BoundingBox,
    val isSelected: Boolean,
)

internal data class ReaderOverlayModel(
    val highlightedLines: List<BoundingBox>,
    val words: List<ReaderOverlayWordBox>,
)

internal fun buildReaderOverlayModel(
    page: OcrPage,
    selectedSentence: String?,
    selectedWord: OcrWord?,
    selectedWords: List<OcrWord>,
    renderAllDetected: Boolean,
): ReaderOverlayModel {
    val highlightedLines = mutableListOf<BoundingBox>()
    val words = mutableListOf<ReaderOverlayWordBox>()

    page.blocks.forEach { block ->
        block.lines.forEach { line ->
            val lineBox = line.boundingBox
            if (lineBox != null && shouldHighlightLine(line, selectedSentence, selectedWord, selectedWords)) {
                highlightedLines += lineBox
            }

            if (renderAllDetected) {
                line.words.forEach { word ->
                    word.boundingBox?.let { box ->
                        words += ReaderOverlayWordBox(
                            box = box,
                            isSelected = isSelectedWord(
                                candidate = word,
                                selectedWord = selectedWord,
                                selectedWords = selectedWords,
                            ),
                        )
                    }
                }
            }
        }
    }

    if (!renderAllDetected) {
        selectedWords.forEach { selected ->
            selected.boundingBox?.let { box ->
                words += ReaderOverlayWordBox(box = box, isSelected = true)
            }
        }
        if (words.isEmpty()) {
            selectedWord?.boundingBox?.let { box ->
                words += ReaderOverlayWordBox(box = box, isSelected = true)
            }
        }
    }

    return ReaderOverlayModel(
        highlightedLines = highlightedLines,
        words = words,
    )
}

private fun shouldHighlightLine(
    line: OcrLine,
    selectedSentence: String?,
    selectedWord: OcrWord?,
    selectedWords: List<OcrWord>,
): Boolean {
    val lineBox = line.boundingBox
    if (lineBox != null && selectedWords.isNotEmpty()) {
        if (selectedWords.any { selected -> centerInLine(selected, lineBox) }) {
            return true
        }
    }

    val selectedWordBox = selectedWord?.boundingBox
    if (selectedWordBox != null) {
        val lineBounds = line.boundingBox ?: return false
        val cx = (selectedWordBox.left + selectedWordBox.right) / 2
        val cy = (selectedWordBox.top + selectedWordBox.bottom) / 2
        if (lineBounds.contains(cx, cy)) {
            return true
        }
    }
    return selectedSentence != null && line.text.contains(selectedSentence)
}

private fun centerInLine(word: OcrWord, lineBox: BoundingBox): Boolean {
    val box = word.boundingBox ?: return false
    val cx = (box.left + box.right) / 2
    val cy = (box.top + box.bottom) / 2
    return lineBox.contains(cx, cy)
}

private fun isSelectedWord(
    candidate: OcrWord,
    selectedWord: OcrWord?,
    selectedWords: List<OcrWord>,
): Boolean {
    if (isSameWord(selectedWord, candidate)) {
        return true
    }
    return selectedWords.any { isSameWord(it, candidate) }
}

private fun isSameWord(selected: OcrWord?, candidate: OcrWord): Boolean {
    if (selected == null) {
        return false
    }
    if (selected.text != candidate.text) {
        return false
    }
    val a = selected.boundingBox
    val b = candidate.boundingBox
    if (a == null || b == null) {
        return a == b
    }
    return a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom
}
