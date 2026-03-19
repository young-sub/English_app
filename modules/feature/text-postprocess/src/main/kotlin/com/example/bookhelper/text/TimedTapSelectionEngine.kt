package com.example.bookhelper.text

import com.example.bookhelper.contracts.OcrWord
import kotlin.math.abs

data class TimedTapSelectionResult(
    val textToRead: String,
    val words: List<OcrWord>,
    val startHit: WordHit,
    val endHit: WordHit,
    val shouldReset: Boolean,
)

class TimedTapSelectionEngine(
    maxIntervalMs: Long = 1200L,
) {
    private var maxIntervalMs: Long = maxIntervalMs
    private var last: TapToken? = null

    fun onTap(
        hit: WordHit,
        timestampMs: Long,
    ): TimedTapSelectionResult? {
        val previous = last
        last = TapToken(hit = hit, timestampMs = timestampMs)
        if (previous == null) {
            return null
        }

        if (timestampMs - previous.timestampMs > maxIntervalMs) {
            return null
        }

        val inLineRangeWords = selectWordsInLineRange(previous.hit, hit)
        val selectedWords = inLineRangeWords ?: listOf(previous.hit.word, hit.word)
        val orderedHits = orderHits(previous.hit, hit)
        val text = wordsToReadableText(selectedWords)
        if (text.isBlank()) {
            return null
        }

        last = null
        return TimedTapSelectionResult(
            textToRead = text,
            words = selectedWords,
            startHit = orderedHits.first,
            endHit = orderedHits.second,
            shouldReset = true,
        )
    }

    private fun orderHits(first: WordHit, second: WordHit): Pair<WordHit, WordHit> {
        return if (isHitBeforeOrEqual(first, second)) {
            first to second
        } else {
            second to first
        }
    }

    private fun isHitBeforeOrEqual(first: WordHit, second: WordHit): Boolean {
        if (first.lineIndex != second.lineIndex) {
            return first.lineIndex < second.lineIndex
        }
        return first.wordIndex <= second.wordIndex
    }

    private fun selectWordsInLineRange(previous: WordHit, current: WordHit): List<OcrWord>? {
        if (!isSameVisualLine(previous, current)) {
            return null
        }

        val words = current.line.words
        if (words.isEmpty()) {
            return null
        }

        val rangeStart = minOf(previous.wordIndex, current.wordIndex).coerceIn(0, words.lastIndex)
        val rangeEnd = maxOf(previous.wordIndex, current.wordIndex).coerceIn(0, words.lastIndex)
        if (rangeStart > rangeEnd) {
            return null
        }

        return words.subList(rangeStart, rangeEnd + 1)
            .filter { it.text.isNotBlank() }
            .ifEmpty { null }
    }

    private fun isSameVisualLine(previous: WordHit, current: WordHit): Boolean {
        if (previous.lineIndex == current.lineIndex) {
            return true
        }

        val previousBox = previous.line.boundingBox ?: return false
        val currentBox = current.line.boundingBox ?: return false
        val overlapTop = maxOf(previousBox.top, currentBox.top)
        val overlapBottom = minOf(previousBox.bottom, currentBox.bottom)
        val overlapHeight = (overlapBottom - overlapTop).coerceAtLeast(0)
        val previousHeight = (previousBox.bottom - previousBox.top).coerceAtLeast(1)
        val currentHeight = (currentBox.bottom - currentBox.top).coerceAtLeast(1)
        val minHeight = minOf(previousHeight, currentHeight)
        val overlapRatio = overlapHeight.toFloat() / minHeight.toFloat()
        if (overlapRatio >= 0.5f) {
            return true
        }

        val previousCenterY = (previousBox.top + previousBox.bottom) / 2f
        val currentCenterY = (currentBox.top + currentBox.bottom) / 2f
        val centerGap = abs(previousCenterY - currentCenterY)
        val allowedGap = maxOf(previousHeight, currentHeight) * 0.4f
        return centerGap <= allowedGap
    }

    fun reset() {
        last = null
    }

    fun setMaxIntervalMs(value: Long) {
        maxIntervalMs = value.coerceIn(300L, 3000L)
        last = null
    }

    private data class TapToken(
        val hit: WordHit,
        val timestampMs: Long,
    )
}

fun wordsToReadableText(words: List<OcrWord>): String {
    return words
        .map { it.text }
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
