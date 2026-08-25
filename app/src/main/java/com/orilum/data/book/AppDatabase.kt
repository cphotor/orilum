package com.orilum.data.book

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.orilum.data.font.FontDao
import com.orilum.data.font.FontFace

/**
 * 应用本地数据库（M0：书架与阅读进度；M2：字体池）。
 *
 * v2：books.sourceUri → filePath（书改存私有目录副本）；旧 content:// 行已失效，清空重导。
 * v3：reading_states 增加 locator 列，用于「重开接着上次位置」的精确定位恢复。
 * v4：新增 font_faces 表（系统 + 导入字体池），family_name 唯一。
 * v5：font_faces 增加 subfamily 列（字重/样式），家族内多字重文件归并候选时优先 Regular。
 * v6：唯一索引并入 subfamily——同家族多字重文件并存，渲染时按字重归档。
 * v7：书籍/字体文件改存**用户指定的公共目录**（SAF 持久授权），`Book.filePath`/`FontFace.path`
 *     从私有绝对路径改为公共目录内的 content:// uri。旧私有路径数据全部失效，按「重置」语义清空重导。
 */
@Database(
    entities = [Book::class, BookReadingState::class, FontFace::class],
    version = 7,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao

    abstract fun fontDao(): FontDao

    companion object {
        private const val DB_NAME = "orilum.db"
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

        /**
         * v6：唯一索引从 `(familyName, source)` 换为 `(familyName, subfamily, source)`，
         * 让同家族多字重文件并存（渲染按字重归档）。同时把 v5 遗留的可空 `subfamily`
         * 重建为 `NOT NULL DEFAULT ''`（实体字段非空），保证 Room schema 校验一致。
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 旧唯一索引依版本命过 `index_font_faces_familyName` 与
                // `index_font_faces_familyName_source` 两种名字，全部剔除。
                db.execSQL("DROP INDEX IF EXISTS `index_font_faces_familyName`")
                db.execSQL("DROP INDEX IF EXISTS `index_font_faces_familyName_source`")
                // 重建使 subfamily NOT NULL（空串 = 无字重名兜底），并顺带重建复合唯一索引。
                db.execSQL("ALTER TABLE `font_faces` RENAME TO `font_faces_old`")
                db.execSQL(
                    "CREATE TABLE `font_faces` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`familyName` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, " +
                        "`subfamily` TEXT NOT NULL DEFAULT '', " +
                        "`source` TEXT NOT NULL, " +
                        "`path` TEXT, " +
                        "`lang` TEXT NOT NULL)",
                )
                db.execSQL(
                    "INSERT INTO `font_faces` (`id`, `familyName`, `displayName`, `subfamily`, `source`, `path`, `lang`) " +
                        "SELECT `id`, `familyName`, `displayName`, IFNULL(`subfamily`, ''), `source`, `path`, `lang` " +
                        "FROM `font_faces_old`",
                )
                db.execSQL("DROP TABLE `font_faces_old`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_font_faces_familyName_subfamily_source` " +
                        "ON `font_faces` (`familyName`, `subfamily`, `source`)",
                )
            }
        }

        /**
         * v7：书籍/字体文件改存公共目录后，旧的私有绝对路径数据全部失效。按「重置」语义：
         * 清空 books / reading_states / font_faces 全部行，让用户从干净开始重新指定目录并导入。
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM books")
                db.execSQL("DELETE FROM reading_states")
                db.execSQL("DELETE FROM font_faces")
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
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build().also { instance = it }
            }
    }
}