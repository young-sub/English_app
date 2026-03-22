package com.example.bookhelper.text

import com.example.bookhelper.contracts.OcrPage
import com.example.bookhelper.contracts.OcrLine
import com.example.bookhelper.contracts.OcrWord
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

data class WordHit(
    val word: OcrWord,
    val line: OcrLine,
    val lineIndex: Int,
    val wordIndex: Int,
)

class SelectionResolver {
    fun resolveWord(x: Float, y: Float, page: OcrPage): OcrWord? {
        val words = page.blocks
            .flatMap { it.lines }
            .flatMap { it.words }

        val containing = words
            .filter { word -> containsWithPadding(word, x, y, 6) }
            .minByOrNull { word ->
                val box = word.boundingBox!!
                (box.right - box.left) * (box.bottom - box.top)
            }
        if (containing != null) {
            return containing
        }

        val nearest = words
            .mapNotNull { word ->
                val box = word.boundingBox ?: return@mapNotNull null
                val width = (box.right - box.left).toFloat().coerceAtLeast(1f)
                val height = (box.bottom - box.top).toFloat().coerceAtLeast(1f)
                val diagonal = sqrt(width * width + height * height).coerceAtLeast(1f)
                val edgeDistance = distanceToBoxEdge(x, y, box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
                val normalizedDistance = edgeDistance / diagonal
                val centerDistance = hypot(((box.left + box.right) / 2f) - x, ((box.top + box.bottom) / 2f) - y)
                val score = normalizedDistance * 1000f + centerDistance
                val adaptiveThreshold = max(24f, minOf(width, height) * 0.8f)
                Triple(word, edgeDistance, score) to adaptiveThreshold
            }
            .filter { (candidate, threshold) ->
                candidate.second <= threshold
            }
            .minByOrNull { it.first.third }

        return nearest?.first?.first
    }

    fun resolveWordHit(x: Float, y: Float, page: OcrPage): WordHit? {
        val lines = page.blocks.flatMap { it.lines }
        lines.forEachIndexed { lineIndex, line ->
            line.words.forEachIndexed { wordIndex, word ->
                if (containsWithPadding(word, x, y, 6)) {
                    return WordHit(
                        word = word,
                        line = line,
                        lineIndex = lineIndex,
                        wordIndex = wordIndex,
                    )
                }
            }
        }

        val fallbackWord = resolveWord(x, y, page) ?: return null
        lines.forEachIndexed { lineIndex, line ->
            line.words.forEachIndexed { wordIndex, word ->
                if (sameWord(word, fallbackWord)) {
                    return WordHit(
                        word = word,
                        line = line,
                        lineIndex = lineIndex,
                        wordIndex = wordIndex,
                    )
                }
            }
        }
        return null
    }

    fun resolveWordsInRegion(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        page: OcrPage,
        minDragDistance: Float = 4f,
    ): List<OcrWord> {
        val left = minOf(startX, endX)
        val right = maxOf(startX, endX)
        val top = minOf(startY, endY)
        val bottom = maxOf(startY, endY)
        if ((right - left) < minDragDistance && (bottom - top) < minDragDistance) {
            return emptyList()
        }

        return page.blocks
            .flatMap { it.lines }
            .flatMap { line -> line.words }
            .filter { word ->
                val box = word.boundingBox ?: return@filter false
                val centerX = (box.left + box.right) / 2f
                val centerY = (box.top + box.bottom) / 2f
                centerX in left..right && centerY in top..bottom
            }
    }

    fun resolveLinesInRegion(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        page: OcrPage,
        minDragDistance: Float = 4f,
    ): List<OcrLine> {
        val left = minOf(startX, endX)
        val right = maxOf(startX, endX)
        val top = minOf(startY, endY)
        val bottom = maxOf(startY, endY)
        if ((right - left) < minDragDistance && (bottom - top) < minDragDistance) {
            return emptyList()
        }

        return page.blocks
            .flatMap { it.lines }
            .filter { line ->
                val lineBox = line.boundingBox
                if (lineBox != null) {
                    boxesIntersect(
                        left = left,
                        right = right,
                        top = top,
                        bottom = bottom,
                        line = lineBox,
                    )
                } else {
                    line.words.any { word ->
                        val box = word.boundingBox ?: return@any false
                        val centerX = (box.left + box.right) / 2f
                        val centerY = (box.top + box.bottom) / 2f
                        centerX in left..right && centerY in top..bottom
                    }
                }
            }
    }

    fun resolveWordsBetweenHits(
        firstHit: WordHit,
        secondHit: WordHit,
        page: OcrPage,
    ): List<OcrWord> {
        val lines = page.blocks.flatMap { it.lines }
        if (lines.isEmpty()) {
            return emptyList()
        }

        val firstCursor = normalizeHitCursor(firstHit, lines)
        val secondCursor = normalizeHitCursor(secondHit, lines)

        val start = if (firstCursor <= secondCursor) firstCursor else secondCursor
        val end = if (firstCursor <= secondCursor) secondCursor else firstCursor

        val selected = mutableListOf<OcrWord>()
        for (lineIndex in start.lineIndex..end.lineIndex) {
            val words = lines.getOrNull(lineIndex)?.words.orEmpty()
            if (words.isEmpty()) {
                continue
            }

            val from = if (lineIndex == start.lineIndex) {
                start.wordIndex.coerceIn(0, words.lastIndex)
            } else {
                0
            }
            val to = if (lineIndex == end.lineIndex) {
                end.wordIndex.coerceIn(0, words.lastIndex)
            } else {
                words.lastIndex
            }
            if (from > to) {
                continue
            }
            selected += words.subList(from, to + 1)
        }

        return selected
    }

    private fun normalizeHitCursor(hit: WordHit, lines: List<OcrLine>): HitCursor {
        val boundedLineIndex = hit.lineIndex.coerceIn(0, lines.lastIndex)
        val words = lines[boundedLineIndex].words
        val boundedWordIndex = if (words.isEmpty()) {
            0
        } else {
            hit.wordIndex.coerceIn(0, words.lastIndex)
        }
        return HitCursor(lineIndex = boundedLineIndex, wordIndex = boundedWordIndex)
    }

    private fun containsWithPadding(word: OcrWord, x: Float, y: Float, padding: Int): Boolean {
        val box = word.boundingBox ?: return false
        val left = box.left - padding
        val top = box.top - padding
        val right = box.right + padding
        val bottom = box.bottom + padding
        return x >= left && x <= right && y >= top && y <= bottom
    }

    private fun distanceToBoxEdge(
        x: Float,
        y: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Float {
        val dx = when {
            x < left -> left - x
            x > right -> x - right
            else -> 0f
        }
        val dy = when {
            y < top -> top - y
            y > bottom -> y - bottom
            else -> 0f
        }
        return hypot(dx, dy)
    }

    private fun boxesIntersect(
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        line: com.example.bookhelper.contracts.BoundingBox,
    ): Boolean {
        return line.left.toFloat() <= right &&
            line.right.toFloat() >= left &&
            line.top.toFloat() <= bottom &&
            line.bottom.toFloat() >= top
    }

    private fun sameWord(a: OcrWord, b: OcrWord): Boolean {
        if (a.text != b.text) {
            return false
        }
        val boxA = a.boundingBox
        val boxB = b.boundingBox
        if (boxA == null || boxB == null) {
            return boxA == boxB
        }
        return boxA.left == boxB.left &&
            boxA.top == boxB.top &&
            boxA.right == boxB.right &&
            boxA.bottom == boxB.bottom
    }

    private data class HitCursor(
        val lineIndex: Int,
        val wordIndex: Int,
    ) : Comparable<HitCursor> {
        override fun compareTo(other: HitCursor): Int {
            val lineCompare = lineIndex.compareTo(other.lineIndex)
            if (lineCompare != 0) {
                return lineCompare
            }
            return wordIndex.compareTo(other.wordIndex)
        }
    }
}
