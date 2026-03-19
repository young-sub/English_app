package com.example.bookhelper.dictionary

interface DictionaryLookupDataSource {
    suspend fun findExactCandidatesForTokens(
        tokens: List<String>,
        lemmas: List<String>,
    ): List<DictionaryLookupCandidate>

    suspend fun searchByPrefix(
        prefix: String,
        prefixEnd: String,
    ): List<DictionaryLookupCandidate>

    suspend fun searchEntryIdsByFts(query: String): List<Long>

    suspend fun findByIds(ids: List<Long>): List<DictionaryLookupCandidate>
}

data class DictionaryLookupCandidate(
    val id: Long,
    val headword: String,
    val lemma: String,
    val pos: String,
    val ipa: String?,
    val frequencyRank: Int?,
    val source: String,
    val license: String,
    val senses: List<DictionaryLookupSense>,
)

data class DictionaryLookupSense(
    val senseIndex: Int,
    val definitionEn: String,
    val definitionKo: String,
    val exampleEn: String?,
    val exampleKo: String?,
)
