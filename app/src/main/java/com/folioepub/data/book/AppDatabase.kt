package com.folioepub.data.book

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context

/**
 * 应用本地数据库（M0：书架与阅读进度）。
 *
 * v2：books.sourceUri → filePath（书改存私有目录副本）；旧 content:// 行已失效，清空重导。
 */
@Database(
    entities = [Book::class, BookReadingState::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao

    companion object {
        private const val DB_NAME = "folioepub.db"
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 让出错误（报错也符合迁移预期），实现幂等：重命名列 + 清理失效数据
                runCatching {
                    db.execSQL("ALTER TABLE books RENAME COLUMN sourceUri TO filePath")
                }
                db.execSQL("DELETE FROM books")
                db.execSQL("DELETE FROM reading_states")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}