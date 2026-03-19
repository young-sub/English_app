package com.example.bookhelper.tts

object DeterministicTextChunker {
    private val sentenceBoundary = Regex("(?<=[.!?])\\s+")

    fun chunk(text: String): List<String> {
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            return emptyList()
        }
        return sentenceBoundary
            .split(normalized)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}
