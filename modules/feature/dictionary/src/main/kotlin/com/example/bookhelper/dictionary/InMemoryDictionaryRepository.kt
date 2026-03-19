package com.example.bookhelper.dictionary

class InMemoryDictionaryRepository(
    entries: List<DictionaryEntry>,
) : DictionaryRepository {
    private val data = entries.toList()

    override fun lookupByLemma(lemma: String): List<DictionaryEntry> {
        val normalized = lemma.trim().lowercase()
        return data.filter { it.lemma.lowercase() == normalized }
    }

    override fun searchByPrefix(prefix: String): List<DictionaryEntry> {
        val normalized = prefix.trim().lowercase()
        return data.filter { it.headword.lowercase().startsWith(normalized) }
    }
}
