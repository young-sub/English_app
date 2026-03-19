package com.example.bookhelper.dictionary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DictionaryCoreTest {
    @Test
    fun lemmatizerGeneratesExpectedCandidates() {
        val lemmatizer = Lemmatizer()
        val candidates = lemmatizer.candidates("studies")
        assertEquals("studies", candidates.first())
        assertTrue(candidates.contains("study"))
    }

    @Test
    fun lemmatizerHandlesContractionsAndPastForms() {
        val lemmatizer = Lemmatizer()

        val contraction = lemmatizer.candidates("didn't")
        assertTrue(contraction.contains("did"))
        assertTrue(contraction.contains("do"))

        val plainContraction = lemmatizer.candidates("doesnt")
        assertTrue(plainContraction.contains("does"))
        assertTrue(plainContraction.contains("do"))

        val pastForm = lemmatizer.candidates("stopped")
        assertTrue(pastForm.contains("stop"))

        val progressiveForm = lemmatizer.candidates("including")
        assertTrue(progressiveForm.contains("include"))
    }

    @Test
    fun inMemoryRepositorySupportsLemmaAndPrefix() {
        val repository = InMemoryDictionaryRepository(
            entries = listOf(
                DictionaryEntry("running", "run", "move quickly", "달리다"),
                DictionaryEntry("runner", "run", "a person who runs", "주자"),
                DictionaryEntry("study", "study", "learn", "공부하다"),
            ),
        )

        val byLemma = repository.lookupByLemma("run")
        assertEquals(2, byLemma.size)

        val byPrefix = repository.searchByPrefix("stu")
        assertEquals(1, byPrefix.size)
        assertEquals("study", byPrefix.first().headword)
    }
}
