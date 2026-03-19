package com.example.bookhelper.dictionary

import java.util.LinkedHashMap
import kotlin.math.min

class DictionaryLookupService(
    private val dataSource: DictionaryLookupDataSource,
) {
    private val cache = object : LinkedHashMap<String, List<DictionaryEntry>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<DictionaryEntry>>?): Boolean {
            return size > 400
        }
    }

    suspend fun lookup(
        normalizedToken: String,
        lemmaCandidates: List<String>,
        sentenceContext: String?,
    ): List<DictionaryEntry> {
        val primaryToken = normalizeLexeme(normalizedToken)
        if (primaryToken.isBlank()) {
            return emptyList()
        }

        val tokenCandidates = expandTokenCandidates(primaryToken)

        val normalizedLemmas = lemmaCandidates
            .map { normalizeLexeme(it) }
            .flatMap { expandTokenCandidates(it) }
            .filter { it.isNotBlank() }
            .distinct()
        val exactLemmaCandidates = (normalizedLemmas + tokenCandidates).distinct()
        val cacheKey = buildCacheKey(tokenCandidates, exactLemmaCandidates)

        synchronized(cache) {
            cache[cacheKey]?.let { return it }
        }

        val exactMatches = dataSource.findExactCandidatesForTokens(tokenCandidates, exactLemmaCandidates)
            .distinctBy { it.id }
            .filter { matchesTokenStrictly(it, tokenCandidates, normalizedLemmas) }
        if (exactMatches.isNotEmpty()) {
            val ranked = rank(exactMatches, primaryToken, sentenceContext)
            synchronized(cache) {
                cache[cacheKey] = ranked
            }
            return ranked
        }

        val prefixCandidates = tokenCandidates.filter { it.length >= 3 }
        if (prefixCandidates.isNotEmpty()) {
            val prefixMatches = prefixCandidates
                .flatMap { prefix ->
                    val prefixEnd = "$prefix\uffff"
                    dataSource.searchByPrefix(prefix, prefixEnd)
                }
                .distinctBy { it.id }
                .filter { matchesTokenLoosely(it, tokenCandidates, normalizedLemmas) }
            if (prefixMatches.isNotEmpty()) {
                val ranked = rank(prefixMatches, primaryToken, sentenceContext)
                synchronized(cache) {
                    cache[cacheKey] = ranked
                }
                return ranked
            }
        }

        val ftsQuery = buildFtsQuery(tokenCandidates, exactLemmaCandidates, sentenceContext)
        if (ftsQuery != null) {
            val ids = dataSource.searchEntryIdsByFts(ftsQuery)
            if (ids.isNotEmpty()) {
                val ftsMatches = dataSource.findByIds(ids)
                    .filter { matchesTokenLoosely(it, tokenCandidates, normalizedLemmas) }
                if (ftsMatches.isNotEmpty()) {
                    val ranked = rank(ftsMatches, primaryToken, sentenceContext)
                    synchronized(cache) {
                        cache[cacheKey] = ranked
                    }
                    return ranked
                }
            }
        }

        synchronized(cache) {
            cache[cacheKey] = emptyList()
        }
        return emptyList()
    }

    private fun rank(
        entries: List<DictionaryLookupCandidate>,
        normalizedToken: String,
        sentenceContext: String?,
    ): List<DictionaryEntry> {
        val context = sentenceContext.orEmpty().lowercase()
        val contextTokens = extractContextTokens(context)
        val posHint = inferPosHint(normalizedToken)

        return entries
            .asSequence()
            .map { withSenses ->
                scoreEntry(withSenses, normalizedToken, contextTokens, posHint) to withSenses
            }
            .sortedByDescending { it.first }
            .take(20)
            .map { it.second.toDictionaryEntry() }
            .toList()
    }

    private fun scoreEntry(
        entry: DictionaryLookupCandidate,
        token: String,
        contextTokens: List<String>,
        posHint: String?,
    ): Double {
        val e = entry
        var score = 0.0

        if (e.headword.equals(token, ignoreCase = true)) {
            score += 40.0
        }
        if (e.lemma.equals(token, ignoreCase = true)) {
            score += 35.0
        }

        val rankBoost = (20000 - (e.frequencyRank ?: 20000)).coerceAtLeast(0) / 1000.0
        score += rankBoost

        if (posHint != null && e.pos.equals(posHint, ignoreCase = true)) {
            score += 8.0
        }

        if (contextTokens.isNotEmpty()) {
            val contextHits = entry.senses.sumOf { sense ->
                val blob = listOf(sense.definitionEn, sense.definitionKo, sense.exampleEn, sense.exampleKo)
                    .joinToString(" ")
                    .lowercase()
                contextTokens.count { blob.contains(it) }
            }
            score += min(contextHits, 10).toDouble()
        }

        return score
    }

    private fun inferPosHint(token: String): String? {
        return when {
            token.endsWith("ing") || token.endsWith("ed") -> "verb"
            token.endsWith("ly") -> "adverb"
            token.endsWith("ous") || token.endsWith("ive") || token.endsWith("al") -> "adjective"
            else -> null
        }
    }

    private fun matchesTokenStrictly(
        entry: DictionaryLookupCandidate,
        tokens: List<String>,
        lemmas: List<String>,
    ): Boolean {
        val head = normalizeLexeme(entry.headword)
        val lemma = normalizeLexeme(entry.lemma)
        if (tokens.any { it == head || it == lemma }) {
            return true
        }
        return lemmas.any { it == head || it == lemma }
    }

    private fun matchesTokenLoosely(
        entry: DictionaryLookupCandidate,
        tokens: List<String>,
        lemmas: List<String>,
    ): Boolean {
        if (matchesTokenStrictly(entry, tokens, lemmas)) {
            return true
        }
        val head = normalizeLexeme(entry.headword)
        val lemma = normalizeLexeme(entry.lemma)
        return tokens.any { token -> head.startsWith(token) || lemma.startsWith(token) }
    }

    private fun extractContextTokens(context: String): List<String> {
        return context
            .split(Regex("\\W+"))
            .map { it.trim() }
            .filter { it.length >= 4 }
            .distinct()
            .take(8)
    }

    private fun buildFtsQuery(tokens: List<String>, lemmas: List<String>, context: String?): String? {
        val terms = linkedSetOf<String>()
        val contextTokens = extractContextTokens(context.orEmpty())
        (tokens + lemmas + contextTokens)
            .map { it.replace(Regex("[^a-z0-9]"), "") }
            .filter { it.length >= 3 }
            .forEach { terms.add("$it*") }

        if (terms.isEmpty()) {
            return null
        }
        return terms.take(8).joinToString(" OR ")
    }

    private fun buildCacheKey(tokens: List<String>, lemmas: List<String>): String {
        return buildString {
            append(tokens.sorted().joinToString("|"))
            append("#")
            append(lemmas.sorted().joinToString("|"))
        }
    }

    private fun expandTokenCandidates(token: String): List<String> {
        if (token.isBlank()) {
            return emptyList()
        }

        val candidates = linkedSetOf(token)
        val compact = token.replace(Regex("[^a-z0-9']"), "")
        if (compact.isNotBlank()) {
            candidates.add(compact)
        }
        if (token.contains('.')) {
            candidates.add(token.replace(".", ""))
        }
        if (token.contains("'")) {
            candidates.add(token.replace("'", ""))
        }
        if (token.endsWith("'s") && token.length > 2) {
            candidates.add(token.dropLast(2))
        }
        if (token.endsWith("s'") && token.length > 2) {
            candidates.add(token.dropLast(1))
        }
        if (token.endsWith("'") && token.length > 1) {
            candidates.add(token.dropLast(1))
        }
        return candidates
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun normalizeLexeme(value: String): String {
        return value
            .replace('’', '\'')
            .replace('‘', '\'')
            .replace('＇', '\'')
            .replace("\u00AD", "")
            .lowercase()
            .replace(Regex("^[^a-z0-9]+|[^a-z0-9']+$"), "")
            .trim()
    }
}

private fun DictionaryLookupCandidate.toDictionaryEntry(): DictionaryEntry {
    val orderedSenses = senses.sortedBy { it.senseIndex }
    return DictionaryEntry(
        headword = headword,
        lemma = lemma,
        definitionEn = orderedSenses.firstOrNull()?.definitionEn.orEmpty(),
        definitionKo = orderedSenses.firstOrNull()?.definitionKo.orEmpty(),
        pos = pos,
        ipa = ipa,
        frequencyRank = frequencyRank,
        source = source,
        license = license,
        senses = orderedSenses.map {
            DictionarySense(
                definitionEn = it.definitionEn,
                definitionKo = it.definitionKo,
                exampleEn = it.exampleEn,
                exampleKo = it.exampleKo,
            )
        },
    )
}
