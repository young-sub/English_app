package com.example.bookhelper.dictionary

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DictionaryLookupServiceTest {
    @Test
    fun lookupReturnsEmptyWhenDictionaryHasNoMatches() = runBlocking {
        val service = DictionaryLookupService(FakeDictionaryDao())

        val entries = service.lookup(
            normalizedToken = "indemnity",
            lemmaCandidates = listOf("indemnity"),
            sentenceContext = "The contract included an indemnity clause.",
        )

        assertTrue(entries.isEmpty())
    }

    @Test
    fun lookupReturnsEmptyForBlankToken() = runBlocking {
        val service = DictionaryLookupService(FakeDictionaryDao())

        val entries = service.lookup(
            normalizedToken = "   ",
            lemmaCandidates = emptyList(),
            sentenceContext = null,
        )

        assertTrue(entries.isEmpty())
    }

    @Test
    fun lookupFindsEntryForPossessiveTokenVariant() = runBlocking {
        val indemnityEntry = entryWithSense(
            id = 1L,
            headword = "indemnity",
            lemma = "indemnity",
        )
        val service = DictionaryLookupService(
            FakeDictionaryDao(
                exactByToken = mapOf("indemnity" to listOf(indemnityEntry)),
            ),
        )

        val entries = service.lookup(
            normalizedToken = "indemnity's",
            lemmaCandidates = listOf("indemnity's"),
            sentenceContext = "The indemnity's scope was broad.",
        )

        assertEquals(1, entries.size)
        assertEquals("indemnity", entries.first().headword)
    }

    @Test
    fun lookupFindsEntryForSmartQuotePossessiveVariant() = runBlocking {
        val indemnityEntry = entryWithSense(
            id = 2L,
            headword = "indemnity",
            lemma = "indemnity",
        )
        val service = DictionaryLookupService(
            FakeDictionaryDao(
                exactByToken = mapOf("indemnity" to listOf(indemnityEntry)),
            ),
        )

        val entries = service.lookup(
            normalizedToken = "indemnity’s",
            lemmaCandidates = listOf("indemnity’s"),
            sentenceContext = "An indemnity’s legal meaning matters.",
        )

        assertEquals(1, entries.size)
        assertEquals("indemnity", entries.first().headword)
    }

    @Test
    fun lookupFindsEntryWhenTokenContainsInternalPunctuationNoise() = runBlocking {
        val entry = entryWithSense(
            id = 3L,
            headword = "apprehensive",
            lemma = "apprehensive",
        )
        val service = DictionaryLookupService(
            FakeDictionaryDao(
                exactByToken = mapOf("apprehensive" to listOf(entry)),
            ),
        )

        val entries = service.lookup(
            normalizedToken = "appre-hen\u00ADsive",
            lemmaCandidates = listOf("appre-hen\u00ADsive"),
            sentenceContext = "He felt appre-hen\u00ADsive before the interview.",
        )

        assertEquals(1, entries.size)
        assertEquals("apprehensive", entries.first().headword)
    }

    @Test
    fun lookupFallsBackToPrefixSearchWhenExactCandidatesMiss() = runBlocking {
        val entry = entryWithSense(
            id = 4L,
            headword = "abandon",
            lemma = "abandon",
        )
        val service = DictionaryLookupService(
            FakeDictionaryDao(
                prefixByToken = mapOf("aban" to listOf(entry)),
            ),
        )

        val entries = service.lookup(
            normalizedToken = "aban",
            lemmaCandidates = emptyList(),
            sentenceContext = "He decided to aban his old plan.",
        )

        assertEquals(1, entries.size)
        assertEquals("abandon", entries.first().headword)
    }

    @Test
    fun lookupFallsBackToFtsSearchWhenExactAndPrefixMiss() = runBlocking {
        val entry = entryWithSense(
            id = 5L,
            headword = "deliberate",
            lemma = "deliberate",
        )
        val service = DictionaryLookupService(
            FakeDictionaryDao(
                ftsIds = listOf(5L),
                entriesById = mapOf(5L to entry),
            ),
        )

        val entries = service.lookup(
            normalizedToken = "deliber",
            lemmaCandidates = emptyList(),
            sentenceContext = "The committee began to deliber before voting.",
        )

        assertEquals(1, entries.size)
        assertEquals("deliberate", entries.first().headword)
    }

    @Test
    fun lookupRanksEntriesUsingPosHintAndContext() = runBlocking {
        val adverbEntry = entryWithSense(
            id = 6L,
            headword = "quickly",
            lemma = "quickly",
            pos = "adverb",
            frequencyRank = 1500,
            definitionEn = "in a quick manner used to move fast",
        )
        val nounEntry = entryWithSense(
            id = 7L,
            headword = "quickly",
            lemma = "quickly",
            pos = "noun",
            frequencyRank = 50,
            definitionEn = "a rare nominal usage",
        )
        val service = DictionaryLookupService(
            FakeDictionaryDao(
                exactByToken = mapOf("quickly" to listOf(nounEntry, adverbEntry)),
            ),
        )

        val entries = service.lookup(
            normalizedToken = "quickly",
            lemmaCandidates = listOf("quickly"),
            sentenceContext = "Move quickly when danger appears.",
        )

        assertEquals(2, entries.size)
        assertEquals("adverb", entries.first().pos)
    }

    private fun entryWithSense(
        id: Long,
        headword: String,
        lemma: String,
        pos: String = "noun",
        frequencyRank: Int = 100,
        definitionEn: String = "test definition",
    ): DictionaryLookupCandidate {
        return DictionaryLookupCandidate(
            id = id,
            headword = headword,
            lemma = lemma,
            pos = pos,
            ipa = null,
            frequencyRank = frequencyRank,
            source = "test",
            license = "internal-dev",
            senses = listOf(
                DictionaryLookupSense(
                    senseIndex = 0,
                    definitionEn = definitionEn,
                    definitionKo = "테스트 뜻",
                    exampleEn = null,
                    exampleKo = null,
                ),
            ),
        )
    }

    private class FakeDictionaryDao(
        private val exactByToken: Map<String, List<DictionaryLookupCandidate>> = emptyMap(),
        private val prefixByToken: Map<String, List<DictionaryLookupCandidate>> = emptyMap(),
        private val ftsIds: List<Long> = emptyList(),
        private val entriesById: Map<Long, DictionaryLookupCandidate> = emptyMap(),
    ) : DictionaryLookupDataSource {
        override suspend fun findExactCandidatesForTokens(
            tokens: List<String>,
            lemmas: List<String>,
        ): List<DictionaryLookupCandidate> {
            return tokens
                .flatMap { token -> exactByToken[token].orEmpty() }
                .distinctBy { it.id }
        }

        override suspend fun searchByPrefix(prefix: String, prefixEnd: String): List<DictionaryLookupCandidate> {
            return prefixByToken[prefix].orEmpty()
        }

        override suspend fun searchEntryIdsByFts(query: String): List<Long> = ftsIds

        override suspend fun findByIds(ids: List<Long>): List<DictionaryLookupCandidate> {
            return ids.mapNotNull { entriesById[it] }
        }
    }
}
