package com.example.bookhelper.text

class TextPostProcessor {
    fun mergeHyphenBreaks(lines: List<String>): List<String> {
        if (lines.isEmpty()) {
            return lines
        }

        val result = mutableListOf<String>()
        var index = 0
        while (index < lines.size) {
            val current = lines[index]
            if (current.endsWith("-") && index + 1 < lines.size) {
                val merged = current.dropLast(1) + lines[index + 1].trimStart()
                result.add(merged)
                index += 2
                continue
            }
            result.add(current)
            index += 1
        }
        return result
    }

    fun normalizeToken(token: String): String {
        return token
            .trim()
            .replace('’', '\'')
            .replace('‘', '\'')
            .replace('＇', '\'')
            .lowercase()
            .replace(Regex("^[^a-z0-9']+|[^a-z0-9']+$"), "")
    }
}
