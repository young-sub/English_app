package com.example.bookhelper.data.local

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.os.Build
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File
import java.io.FileOutputStream

@Database(
    entities = [
        DictionaryEntity::class,
        DictionarySenseEntity::class,
        DictionarySenseFtsEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class DictionaryDatabase : RoomDatabase() {
    abstract fun dictionaryDao(): DictionaryDao

    companion object {
        @Volatile
        private var instance: DictionaryDatabase? = null

        fun get(context: Context): DictionaryDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { db ->
                    instance = db
                }
            }
        }

        private fun buildDatabase(context: Context): DictionaryDatabase {
            val dbName = "dictionary.db"
            val assetPath = "databases/dictionary.db"

            ensurePrebuiltDatabaseInstalled(context, dbName, assetPath)

            return Room.databaseBuilder(
                context,
                DictionaryDatabase::class.java,
                dbName,
            )
                .fallbackToDestructiveMigration()
                .build()
        }

        private fun ensurePrebuiltDatabaseInstalled(
            context: Context,
            dbName: String,
            assetPath: String,
        ) {
            val dbFile = context.getDatabasePath(dbName)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val appVersionCode = currentAppVersionCode(context)
            val copiedAppVersionCode = prefs.getLong(KEY_COPIED_APP_VERSION_CODE, -1L)

            val needsCopy = !dbFile.exists() ||
                dbFile.length() < MIN_PREBUILT_DB_BYTES ||
                copiedAppVersionCode != appVersionCode

            if (!needsCopy) {
                return
            }

            dbFile.parentFile?.mkdirs()
            val tempFile = File("${dbFile.absolutePath}.tmp")
            context.assets.open(assetPath).use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }

            if (!tempFile.renameTo(dbFile)) {
                tempFile.delete()
                throw IllegalStateException("Failed to install prebuilt dictionary database")
            }

            File("${dbFile.absolutePath}-wal").delete()
            File("${dbFile.absolutePath}-shm").delete()

            prefs.edit()
                .putLong(KEY_COPIED_APP_VERSION_CODE, appVersionCode)
                .apply()
        }

        private fun currentAppVersionCode(context: Context): Long {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            return packageInfoCompatVersionCode(packageInfo)
        }

        private fun packageInfoCompatVersionCode(packageInfo: PackageInfo): Long {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        }

        private const val PREFS_NAME = "dictionary_db_state"
        private const val KEY_COPIED_APP_VERSION_CODE = "copied_app_version_code"
        private const val MIN_PREBUILT_DB_BYTES = 5L * 1024L * 1024L
    }
}
