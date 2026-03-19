package com.example.bookhelper.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

@Fts4
@Entity(tableName = "dictionary_senses_fts")
data class DictionarySenseFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,
    val entryId: Long,
    val headword: String,
    val lemma: String,
    val pos: String,
    val definitionEn: String,
    val definitionKo: String,
)
