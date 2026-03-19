package com.example.bookhelper.integration

import com.example.bookhelper.camera.BookPageOcrPostProcessor
import com.example.bookhelper.contracts.BoundingBox
import com.example.bookhelper.contracts.OcrBlock
import com.example.bookhelper.contracts.OcrLine
import com.example.bookhelper.contracts.OcrPage
import com.example.bookhelper.contracts.OcrWord
import com.example.bookhelper.dictionary.DictionaryLookupCandidate
import com.example.bookhelper.dictionary.DictionaryLookupDataSource
import com.example.bookhelper.dictionary.DictionaryLookupSense
import com.example.bookhelper.dictionary.DictionaryLookupService
import com.example.bookhelper.dictionary.Lemmatizer
import com.example.bookhelper.text.SelectionResolver
import com.example.bookhelper.text.TextPostProcessor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderLookupPipelineTest {
    @Test
    fun ocrRefineTapAndLookupAreConnected() = runBlocking {
        val rawPage = OcrPage(
            blocks = listOf(
                OcrBlock(
                    lines = listOf(
                        OcrLine(
                            words = listOf(
                                OcrWord("###", BoundingBox(140, 210, 190, 240)),
                                OcrWord("Children’s", BoundingBox(220, 210, 360, 248)),
                                OcrWord("books.", BoundingBox(370, 210, 470, 248)),
                            ),
                            text = "### Children’s books.",
                            boundingBox = BoundingBox(140, 210, 470, 248),
                        ),
                    ),
                    boundingBox = BoundingBox(140, 210, 470, 248),
                ),
            ),
            fullText = "### Children’s books.",
        )

        val refinedPage = BookPageOcrPostProcessor.refine(rawPage, width = 1000, height = 1000)
        assertEquals("Children’s books", refinedPage.fullText)

        val tappedWord = SelectionResolver().resolveWord(
            x = 260f,
            y = 225f,
            page = refinedPage,
        )
        assertNotNull(tappedWord)

        val normalized = TextPostProcessor().normalizeToken(tappedWord?.text.orEmpty())
        val lemmas = Lemmatizer().candidates(normalized)
        val dictionaryEntry = entryWithSense(
            id = 1L,
            headword = "children",
            lemma = "child",
        )
        val lookupService = DictionaryLookupService(
            dataSource = FakeDictionaryLookupDataSource(
                exactByToken = mapOf("children" to listOf(dictionaryEntry)),
            ),
        )

        val entries = lookupService.lookup(
            normalizedToken = normalized,
            lemmaCandidates = lemmas,
            sentenceContext = refinedPage.fullText,
        )

        assertTrue(entries.isNotEmpty())
        assertEquals("children", entries.first().headword)
    }

    @Test
    fun seamHandlesBoundaryTapAndPunctuationToken() = runBlocking {
        val rawPage = OcrPage(
            blocks = listOf(
                OcrBlock(
                    lines = listOf(
                        OcrLine(
                            words = listOf(
                                OcrWord("indemnity’s,", BoundingBox(200, 200, 360, 250)),
                                OcrWord("scope", BoundingBox(380, 200, 470, 250)),
                            ),
                            text = "indemnity’s, scope",
                            boundingBox = BoundingBox(200, 200, 470, 250),
                        ),
                    ),
                    boundingBox = BoundingBox(200, 200, 470, 250),
                ),
            ),
            fullText = "indemnity’s, scope",
        )

        val refinedPage = BookPageOcrPostProcessor.refine(rawPage, width = 1000, height = 1000)
        val tappedWord = SelectionResolver().resolveWord(
            x = 364f,
            y = 226f,
            page = refinedPage,
        )
        assertNotNull(tappedWord)

        val normalized = TextPostProcessor().normalizeToken(tappedWord?.text.orEmpty())
        val lemmas = Lemmatizer().candidates(normalized)
        val lookupService = DictionaryLookupService(
            dataSource = FakeDictionaryLookupDataSource(
                exactByToken = mapOf(
                    "indemnity" to listOf(entryWithSense(id = 2L, headword = "indemnity", lemma = "indemnity")),
                ),
            ),
        )

        val entries = lookupService.lookup(
            normalizedToken = normalized,
            lemmaCandidates = lemmas,
            sentenceContext = refinedPage.fullText,
        )

        assertTrue(entries.isNotEmpty())
        assertEquals("indemnity", entries.first().headword)
    }

    private fun entryWithSense(
        id: Long,
        headword: String,
        lemma: String,
    ): DictionaryLookupCandidate {
        return DictionaryLookupCandidate(
            id = id,
            headword = headword,
            lemma = lemma,
            pos = "noun",
            ipa = null,
            frequencyRank = 120,
            source = "test",
            license = "internal-dev",
            senses = listOf(
                DictionaryLookupSense(
                    senseIndex = 0,
                    definitionEn = "young human beings",
                    definitionKo = "아이들",
                    exampleEn = null,
                    exampleKo = null,
                ),
            ),
        )
    }

    private class FakeDictionaryLookupDataSource(
        private val exactByToken: Map<String, List<DictionaryLookupCandidate>> = emptyMap(),
    ) : DictionaryLookupDataSource {
        override suspend fun findExactCandidatesForTokens(
            tokens: List<String>,
            lemmas: List<String>,
        ): List<DictionaryLookupCandidate> {
            return tokens
                .flatMap { token -> exactByToken[token].orEmpty() }
                .distinctBy { it.id }
        }

        override suspend fun searchByPrefix(
            prefix: String,
            prefixEnd: String,
        ): List<DictionaryLookupCandidate> = emptyList()

        override suspend fun searchEntryIdsByFts(query: String): List<Long> = emptyList()

        override suspend fun findByIds(ids: List<Long>): List<DictionaryLookupCandidate> = emptyList()
    }
}
