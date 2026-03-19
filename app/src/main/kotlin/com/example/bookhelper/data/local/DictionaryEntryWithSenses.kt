package com.example.bookhelper.data.local

import androidx.room.Embedded
import androidx.room.Relation

data class DictionaryEntryWithSenses(
    @Embedded val entry: DictionaryEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "entryId",
    )
    val senses: List<DictionarySenseEntity>,
)
