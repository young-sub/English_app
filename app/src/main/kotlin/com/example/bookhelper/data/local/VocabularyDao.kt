package com.example.bookhelper.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface VocabularyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: VocabularyEntity)

    @Query("SELECT * FROM vocabulary WHERE `key` = :key LIMIT 1")
    suspend fun findByKey(key: String): VocabularyEntity?

    @Query("SELECT * FROM vocabulary ORDER BY updatedAt DESC")
    suspend fun findAll(): List<VocabularyEntity>

    @Query("DELETE FROM vocabulary WHERE `key` = :key")
    suspend fun deleteByKey(key: String)
}
