package com.example.bookhelper.contracts

enum class ProvisioningStatus {
    NOT_READY,
    PREPARING,
    READY,
    FAILED_RECOVERABLE,
    FAILED_FATAL,
}

data class SelectionPoint(
    val x: Float,
    val y: Float,
)

data class WordSelection(
    val token: String,
    val lemmaCandidates: List<String>,
)

data class SentenceSelection(
    val id: String,
    val text: String,
)

data class BoundingBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun contains(x: Int, y: Int): Boolean {
        return x in left..right && y in top..bottom
    }
}

data class OcrWord(
    val text: String,
    val boundingBox: BoundingBox?,
)

data class OcrLine(
    val words: List<OcrWord>,
    val text: String,
    val boundingBox: BoundingBox?,
)

data class OcrBlock(
    val lines: List<OcrLine>,
    val boundingBox: BoundingBox?,
)

data class OcrPage(
    val blocks: List<OcrBlock>,
    val fullText: String,
) {
    companion object {
        val EMPTY = OcrPage(emptyList(), "")
    }
}
