package com.example.bookhelper.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "dictionary_entries",
    indices = [
        Index("headword"),
        Index("lemma"),
    ],
)
data class DictionaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val headword: String,
    val lemma: String,
    val pos: String,
    val ipa: String?,
    val frequencyRank: Int?,
    val source: String,
    val license: String,
)
