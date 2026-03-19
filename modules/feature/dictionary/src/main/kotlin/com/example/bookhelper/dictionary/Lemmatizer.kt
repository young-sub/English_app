package com.example.bookhelper.dictionary

class Lemmatizer {
    fun candidates(token: String): List<String> {
        val normalized = normalize(token)
        if (normalized.isBlank()) {
            return emptyList()
        }

        val result = linkedSetOf(normalized)
        addSimpleVariants(normalized, result)
        addContractionVariants(normalized, result)
        addInflectionVariants(normalized, result)
        addIrregularVariants(normalized, result)
        addNumericVariants(normalized, result)

        val expanded = result.toList()
        for (candidate in expanded) {
            irregularLemmas[candidate]?.let { result.add(it) }
        }

        return result
            .map { normalize(it) }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun normalize(value: String): String {
        return value
            .trim()
            .replace('’', '\'')
            .replace('‘', '\'')
            .replace('＇', '\'')
            .lowercase()
    }

    private fun addSimpleVariants(token: String, out: MutableSet<String>) {
        out.add(token.replace("'", ""))
        if (token.contains('.')) {
            out.add(token.replace(".", ""))
        }
        if (token.endsWith("'s") && token.length > 2) {
            out.add(token.dropLast(2))
        }
        if (token.endsWith("s'") && token.length > 2) {
            out.add(token.dropLast(1))
            out.add(token.dropLast(2))
        }
        if (token.endsWith("'") && token.length > 1) {
            out.add(token.dropLast(1))
        }
        if (token.endsWith("wards") && token.length > 6) {
            out.add(token.dropLast(1))
        }
    }

    private fun addContractionVariants(token: String, out: MutableSet<String>) {
        for (suffix in apostropheContractionSuffixes) {
            if (token.endsWith(suffix) && token.length > suffix.length + 1) {
                out.add(token.dropLast(suffix.length))
            }
        }
        for (suffix in plainContractionSuffixes) {
            if (token.endsWith(suffix) && token.length > suffix.length + 1) {
                out.add(token.dropLast(suffix.length))
            }
        }
        if (token.endsWith("n't") && token.length > 4) {
            val stem = token.dropLast(3)
            out.add(stem)
            when (stem) {
                "ca" -> out.add("can")
                "wo" -> out.add("will")
                "sha" -> out.add("shall")
            }
        }
        if (token.endsWith("nt") && token.length > 3) {
            val stem = token.dropLast(2)
            out.add(stem)
            when (token) {
                "cant" -> out.add("can")
                "wont" -> out.add("will")
                "shant" -> out.add("shall")
                "aint" -> out.add("be")
            }
        }
    }

    private fun addInflectionVariants(token: String, out: MutableSet<String>) {
        if (token.endsWith("ies") && token.length > 4) {
            out.add(token.dropLast(3) + "y")
        }
        if (token.endsWith("ied") && token.length > 4) {
            out.add(token.dropLast(3) + "y")
        }
        if (token.endsWith("ing") && token.length > 5) {
            val stem = token.dropLast(3)
            out.add(stem)
            out.add(stem + "e")
            if (stem.length >= 2 && stem.last() == stem[stem.lastIndex - 1]) {
                out.add(stem.dropLast(1))
            }
        }
        if (token.endsWith("ed") && token.length > 4) {
            val stem = token.dropLast(2)
            out.add(stem)
            out.add(stem + "e")
            if (stem.endsWith("i") && stem.length > 1) {
                out.add(stem.dropLast(1) + "y")
            }
            if (stem.length >= 2 && stem.last() == stem[stem.lastIndex - 1]) {
                out.add(stem.dropLast(1))
            }
        }
        if (token.endsWith("es") && token.length > 4) {
            out.add(token.dropLast(2))
        }
        if (token.endsWith("s") && token.length > 3) {
            out.add(token.dropLast(1))
        }
        if (token.endsWith("er") && token.length > 4) {
            val stem = token.dropLast(2)
            out.add(stem)
            if (stem.length >= 2 && stem.last() == stem[stem.lastIndex - 1]) {
                out.add(stem.dropLast(1))
            }
        }
        if (token.endsWith("est") && token.length > 5) {
            val stem = token.dropLast(3)
            out.add(stem)
            if (stem.length >= 2 && stem.last() == stem[stem.lastIndex - 1]) {
                out.add(stem.dropLast(1))
            }
        }
        if (token.endsWith("ly") && token.length > 4) {
            out.add(token.dropLast(2))
            if (token.endsWith("ily") && token.length > 5) {
                out.add(token.dropLast(3) + "y")
            }
        }
    }

    private fun addIrregularVariants(token: String, out: MutableSet<String>) {
        irregularLemmas[token]?.let { out.add(it) }
    }

    private fun addNumericVariants(token: String, out: MutableSet<String>) {
        numericLemmas[token]?.let { out.add(it) }
    }

    private companion object {
        val apostropheContractionSuffixes = listOf("'re", "'ve", "'ll", "'d", "'m", "'s")
        val plainContractionSuffixes = listOf("re", "ve", "ll", "d", "m")

        val irregularLemmas = mapOf(
            "was" to "be",
            "were" to "be",
            "been" to "be",
            "has" to "have",
            "had" to "have",
            "does" to "do",
            "did" to "do",
            "done" to "do",
            "went" to "go",
            "gone" to "go",
            "made" to "make",
            "knew" to "know",
            "known" to "know",
            "thought" to "think",
            "bought" to "buy",
            "brought" to "bring",
            "found" to "find",
            "felt" to "feel",
            "left" to "leave",
            "told" to "tell",
            "kept" to "keep",
            "heard" to "hear",
            "paid" to "pay",
            "taken" to "take",
            "took" to "take",
            "seen" to "see",
            "saw" to "see",
            "given" to "give",
            "gave" to "give",
            "began" to "begin",
            "begun" to "begin",
            "written" to "write",
            "wrote" to "write",
            "better" to "good",
            "best" to "good",
            "worse" to "bad",
            "worst" to "bad",
        )

        val numericLemmas = mapOf(
            "0" to "zero",
            "1" to "one",
            "2" to "two",
            "3" to "three",
            "4" to "four",
            "5" to "five",
            "6" to "six",
            "7" to "seven",
            "8" to "eight",
            "9" to "nine",
            "10" to "ten",
            "1st" to "first",
            "2nd" to "second",
            "3rd" to "third",
            "4th" to "fourth",
            "5th" to "fifth",
            "6th" to "sixth",
            "7th" to "seventh",
            "8th" to "eighth",
            "9th" to "ninth",
            "10th" to "tenth",
        )
    }
}
