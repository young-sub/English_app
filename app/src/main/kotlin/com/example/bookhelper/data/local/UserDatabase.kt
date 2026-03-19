package com.example.bookhelper.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [VocabularyEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class UserDatabase : RoomDatabase() {
    abstract fun vocabularyDao(): VocabularyDao

    companion object {
        @Volatile
        private var instance: UserDatabase? = null

        fun get(context: Context): UserDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context,
                    UserDatabase::class.java,
                    "book_helper_user.db",
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { db ->
                        instance = db
                    }
            }
        }
    }
}
