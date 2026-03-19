package com.example.bookhelper.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VocabularyStoragePolicyTest {
    @Test
    fun mergeVocabularyEntityCreatesNewRecordOnFirstSave() {
        val merged = mergeVocabularyEntity(
            existing = null,
            key = "run",
            word = "running",
            lemma = "run",
            sentence = "I am running every day.",
            now = 10_000L,
        )

        assertEquals("run", merged.key)
        assertEquals("running", merged.word)
        assertEquals("run", merged.lemma)
        assertEquals("I am running every day.", merged.sentence)
        assertEquals(1, merged.saveCount)
        assertEquals(10_000L, merged.createdAt)
        assertEquals(10_000L, merged.updatedAt)
    }

    @Test
    fun mergeVocabularyEntityIncrementsCountAndPreservesOriginalFieldsWhenNeeded() {
        val existing = VocabularyEntity(
            key = "run",
            word = "running",
            lemma = "run",
            sentence = "Old sentence",
            saveCount = 2,
            createdAt = 1_000L,
            updatedAt = 2_000L,
        )

        val merged = mergeVocabularyEntity(
            existing = existing,
            key = "run",
            word = "runs",
            lemma = "",
            sentence = null,
            now = 9_000L,
        )

        assertEquals("run", merged.key)
        assertEquals("runs", merged.word)
        assertEquals("run", merged.lemma)
        assertEquals("Old sentence", merged.sentence)
        assertEquals(3, merged.saveCount)
        assertEquals(1_000L, merged.createdAt)
        assertEquals(9_000L, merged.updatedAt)
    }

    @Test
    fun buildVocabularyStorageKeyPrefersLemmaAndFallsBackWhenNormalizerReturnsBlank() {
        val keyFromLemma = buildVocabularyStorageKey(
            word = "Running",
            lemma = "Run",
            normalize = { value -> value.lowercase() },
        )
        assertEquals("run", keyFromLemma)

        val keyFromWordFallback = buildVocabularyStorageKey(
            word = "HELLO",
            lemma = "",
            normalize = { _ -> "" },
        )
        assertEquals("hello", keyFromWordFallback)
    }

    @Test
    fun buildVocabularyStorageKeyNormalizesEquivalentInputsToSameKey() {
        val keyA = buildVocabularyStorageKey(
            word = "RUN",
            lemma = " Run ",
            normalize = { value -> value.trim().lowercase() },
        )
        val keyB = buildVocabularyStorageKey(
            word = "run",
            lemma = "run",
            normalize = { value -> value.trim().lowercase() },
        )

        assertEquals("run", keyA)
        assertEquals(keyA, keyB)
    }

    @Test
    fun mergeVocabularyEntityReplacesSentenceWhenNewSentenceExists() {
        val existing = VocabularyEntity(
            key = "book",
            word = "book",
            lemma = "book",
            sentence = "Old sentence",
            saveCount = 1,
            createdAt = 11L,
            updatedAt = 12L,
        )

        val merged = mergeVocabularyEntity(
            existing = existing,
            key = "book",
            word = "books",
            lemma = "book",
            sentence = "New sentence",
            now = 99L,
        )

        assertEquals("New sentence", merged.sentence)
        assertEquals(2, merged.saveCount)
        assertEquals(11L, merged.createdAt)
        assertEquals(99L, merged.updatedAt)
    }

    @Test
    fun mergeVocabularyEntityAcceptsNullSentenceOnFirstSave() {
        val merged = mergeVocabularyEntity(
            existing = null,
            key = "book",
            word = "book",
            lemma = "book",
            sentence = null,
            now = 100L,
        )

        assertNull(merged.sentence)
    }
}
