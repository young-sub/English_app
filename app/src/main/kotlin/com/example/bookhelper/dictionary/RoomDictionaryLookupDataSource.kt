package com.example.bookhelper.dictionary

import com.example.bookhelper.data.local.DictionaryDao
import com.example.bookhelper.data.local.DictionaryEntryWithSenses

class RoomDictionaryLookupDataSource(
    private val dao: DictionaryDao,
) : DictionaryLookupDataSource {
    override suspend fun findExactCandidatesForTokens(
        tokens: List<String>,
        lemmas: List<String>,
    ): List<DictionaryLookupCandidate> {
        return dao.findExactCandidatesForTokens(tokens, lemmas).map { it.toLookupCandidate() }
    }

    override suspend fun searchByPrefix(
        prefix: String,
        prefixEnd: String,
    ): List<DictionaryLookupCandidate> {
        return dao.searchByPrefix(prefix, prefixEnd).map { it.toLookupCandidate() }
    }

    override suspend fun searchEntryIdsByFts(query: String): List<Long> {
        return dao.searchEntryIdsByFts(query)
    }

    override suspend fun findByIds(ids: List<Long>): List<DictionaryLookupCandidate> {
        return dao.findByIds(ids).map { it.toLookupCandidate() }
    }
}

private fun DictionaryEntryWithSenses.toLookupCandidate(): DictionaryLookupCandidate {
    return DictionaryLookupCandidate(
        id = entry.id,
        headword = entry.headword,
        lemma = entry.lemma,
        pos = entry.pos,
        ipa = entry.ipa,
        frequencyRank = entry.frequencyRank,
        source = entry.source,
        license = entry.license,
        senses = senses.map {
            DictionaryLookupSense(
                senseIndex = it.senseIndex,
                definitionEn = it.definitionEn,
                definitionKo = it.definitionKo,
                exampleEn = it.exampleEn,
                exampleKo = it.exampleKo,
            )
        },
    )
}
