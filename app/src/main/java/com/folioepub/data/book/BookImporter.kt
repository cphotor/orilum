package com.folioepub.data.book

import android.content.Context
import android.net.Uri
import com.folioepub.data.epub.EpubFormatException
import com.folioepub.data.epub.EpubParser
import com.folioepub.data.epub.ZipEpubResourceReader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把用户经 SAF 选择的电子书导入书架：
 * 拷贝进私有目录 → 自建 [EpubParser] 解析元数据 → 写入 [BookRepository]。
 *
 * **书文件一经导入即被应用持有**：`Book.filePath` 指向 `filesDir/books/` 下的 epub 副本，
 * 与 SAF 的 content:// 授权生命周期彻底解耦，重启后仍可稳定读取（对齐 Moon+/ReadEra 的做法）。
 */
class BookImporter(
    private val context: Context,
    private val repository: BookRepository,
) {

    /**
     * 导入所选电子书，返回新记录主键；失败时返回异常。
     *
     * @param uri SAF 选书返回的 content:// uri（仅本次会话内有效，用于一次拷贝）。
     */
    suspend fun import(uri: Uri): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val dest = File(context.filesDir, "books/book_${System.currentTimeMillis()}.epub")
            dest.parentFile?.mkdirs()
            try {
                context.contentResolver.openInputStream(uri).use { input ->
                    input ?: throw IllegalStateException("无法打开所选文件")
                    input.copyTo(dest.outputStream())
                }
                val parsed = ZipEpubResourceReader(dest.path).use { EpubParser().parse(it) }
                if (parsed.isEmpty) throw EpubFormatException("书中没有可读正文章节")
                repository.addBook(parsed.title, parsed.author, dest.path)
            } catch (e: Throwable) {
                dest.delete()
                throw e
            }
        }
    }
}