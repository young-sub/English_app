package com.example.bookhelper.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface DictionaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<DictionaryEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSenses(senses: List<DictionarySenseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSenseFts(rows: List<DictionarySenseFtsEntity>)

    @Transaction
    @Query(
        """
        SELECT * FROM dictionary_entries
        WHERE lemma IN (:lemmas)
        ORDER BY frequencyRank ASC
        LIMIT 50
        """,
    )
    suspend fun findByLemmas(lemmas: List<String>): List<DictionaryEntryWithSenses>

    @Transaction
    @Query(
        """
        SELECT * FROM (
            SELECT * FROM dictionary_entries
            WHERE headword = :token

            UNION ALL

            SELECT * FROM dictionary_entries
            WHERE lemma = :token AND headword != :token

            UNION ALL

            SELECT * FROM dictionary_entries
            WHERE lemma IN (:lemmas) AND lemma != :token
        )
        ORDER BY frequencyRank ASC
        LIMIT 80
        """,
    )
    suspend fun findExactCandidates(token: String, lemmas: List<String>): List<DictionaryEntryWithSenses>

    @Transaction
    @Query(
        """
        SELECT * FROM (
            SELECT * FROM dictionary_entries
            WHERE headword IN (:tokens)

            UNION ALL

            SELECT * FROM dictionary_entries
            WHERE lemma IN (:tokens)
              AND headword NOT IN (:tokens)

            UNION ALL

            SELECT * FROM dictionary_entries
            WHERE lemma IN (:lemmas)
              AND lemma NOT IN (:tokens)
        )
        ORDER BY frequencyRank ASC
        LIMIT 120
        """,
    )
    suspend fun findExactCandidatesForTokens(tokens: List<String>, lemmas: List<String>): List<DictionaryEntryWithSenses>

    @Transaction
    @Query(
        """
        SELECT * FROM dictionary_entries
        WHERE headword = :headword
        ORDER BY frequencyRank ASC
        LIMIT 20
        """,
    )
    suspend fun findByHeadwordExact(headword: String): List<DictionaryEntryWithSenses>

    @Transaction
    @Query(
        """
        SELECT * FROM dictionary_entries
        WHERE headword >= :prefix
          AND headword < :prefixEnd
        ORDER BY frequencyRank ASC
        LIMIT 50
        """,
    )
    suspend fun searchByPrefix(prefix: String, prefixEnd: String): List<DictionaryEntryWithSenses>

    @Query(
        """
        SELECT DISTINCT entryId FROM dictionary_senses_fts
        WHERE dictionary_senses_fts MATCH :query
        LIMIT 80
        """,
    )
    suspend fun searchEntryIdsByFts(query: String): List<Long>

    @Transaction
    @Query(
        """
        SELECT * FROM dictionary_entries
        WHERE id IN (:ids)
        LIMIT 80
        """,
    )
    suspend fun findByIds(ids: List<Long>): List<DictionaryEntryWithSenses>

    @Query("SELECT COUNT(*) FROM dictionary_entries")
    suspend fun countEntries(): Int

    @Query(
        """
        SELECT COUNT(DISTINCT headword) FROM dictionary_entries
        WHERE headword IN (:headwords)
        """,
    )
    suspend fun countHeadwords(headwords: List<String>): Int

    @Query("DELETE FROM dictionary_senses_fts")
    suspend fun clearSenseFts()

    @Query("DELETE FROM dictionary_senses")
    suspend fun clearSenses()

    @Query("DELETE FROM dictionary_entries")
    suspend fun clearEntries()
}
