package com.example.bookhelper.text

class SentenceSegmenter {
    fun split(text: String): List<String> {
        if (text.isBlank()) {
            return emptyList()
        }

        return text
            .split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}
