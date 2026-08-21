package com.folioepub.data.book

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.folioepub.data.font.FontDao
import com.folioepub.data.font.FontFace

/**
 * 应用本地数据库（M0：书架与阅读进度；M2：字体池）。
 *
 * v2：books.sourceUri → filePath（书改存私有目录副本）；旧 content:// 行已失效，清空重导。
 * v3：reading_states 增加 locator 列，用于「重开接着上次位置」的精确定位恢复。
 * v4：新增 font_faces 表（系统 + 导入字体池），family_name 唯一。
 * v5：font_faces 增加 subfamily 列（字重/样式），家族内多字重文件归并候选时优先 Regular。
 */
@Database(
    entities = [Book::class, BookReadingState::class, FontFace::class],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao

    abstract fun fontDao(): FontDao

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
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reading_states ADD COLUMN locator TEXT")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `font_faces` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`familyName` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "`path` TEXT, " +
                        "`lang` TEXT NOT NULL)",
                )
                // 同款字体重导覆盖：以家族名为唯一依据（导入覆盖同名系统字体）
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_font_faces_familyName` " +
                        "ON `font_faces` (`familyName`, `source`)",
                )
            }
        }

        /** v5：font_faces 增加 subfamily 列（字重/样式，nullable）。 */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `font_faces` ADD COLUMN `subfamily` TEXT")
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
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { instance = it }
            }
    }
}