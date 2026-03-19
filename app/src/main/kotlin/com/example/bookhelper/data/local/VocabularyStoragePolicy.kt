package com.example.bookhelper.data.local

fun mergeVocabularyEntity(
    existing: VocabularyEntity?,
    key: String,
    word: String,
    lemma: String,
    sentence: String?,
    now: Long,
): VocabularyEntity {
    return if (existing == null) {
        VocabularyEntity(
            key = key,
            word = word,
            lemma = lemma,
            sentence = sentence,
            saveCount = 1,
            createdAt = now,
            updatedAt = now,
        )
    } else {
        VocabularyEntity(
            key = existing.key,
            word = word,
            lemma = if (lemma.isNotBlank()) lemma else existing.lemma,
            sentence = sentence ?: existing.sentence,
            saveCount = existing.saveCount + 1,
            createdAt = existing.createdAt,
            updatedAt = now,
        )
    }
}

fun buildVocabularyStorageKey(
    word: String,
    lemma: String,
    normalize: (String) -> String,
): String {
    val base = if (lemma.isNotBlank()) lemma else word
    val normalized = normalize(base)
    return if (normalized.isBlank()) base.lowercase() else normalized
}
