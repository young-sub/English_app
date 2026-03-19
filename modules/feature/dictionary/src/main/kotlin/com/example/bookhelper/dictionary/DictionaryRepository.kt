package com.example.bookhelper.dictionary

interface DictionaryRepository {
    fun lookupByLemma(lemma: String): List<DictionaryEntry>
    fun searchByPrefix(prefix: String): List<DictionaryEntry>
}

data class DictionaryEntry(
    val headword: String,
    val lemma: String,
    val definitionEn: String,
    val definitionKo: String,
    val pos: String? = null,
    val ipa: String? = null,
    val frequencyRank: Int? = null,
    val source: String? = null,
    val license: String? = null,
    val senses: List<DictionarySense> = emptyList(),
)

data class DictionarySense(
    val definitionEn: String,
    val definitionKo: String,
    val exampleEn: String? = null,
    val exampleKo: String? = null,
)
