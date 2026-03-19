package com.example.bookhelper.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vocabulary")
data class VocabularyEntity(
    @PrimaryKey val key: String,
    val word: String,
    val lemma: String,
    val sentence: String?,
    val saveCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
)
