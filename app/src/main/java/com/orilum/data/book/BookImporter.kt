package com.orilum.data.book

import android.content.Context
import android.net.Uri
import com.orilum.data.epub.EpubFormatException
import com.orilum.data.epub.EpubParser
import com.orilum.data.epub.ZipEpubResourceReader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把用户经 SAF 选择的电子书导入书架：
 * 拷贝进私有书库目录 → 自建 [EpubParser] 解析元数据 → 写入 [BookRepository]。
 *
 * **书文件存私有、元数据存私有**：
 *  - SAF 选书后把书文件**复制一份**进私有 `filesDir/books/`，`Book.filePath` 存私有绝对路径。
 *  - 私有目录零授权、稳定可读、卸载即清空；复制避免了依赖 SAF 临时授权（一次会话 uri 重启即失效）。
 *  - 书籍信息/阅读进度/设置等元数据在私有 Room，由 [BookRepository] 管理。
 *  - 解析按「先写临时副本 → zip 读取」进行（[ZipEpubResourceReader] 基于文件路径），解析完清掉临时文件。
 */
class BookImporter(
    private val context: Context,
    private val repository: BookRepository,
) {

    /**
     * 导入所选电子书，返回新记录主键；失败时返回异常。
     *
     * @param uri SAF 选书返回的 content:// uri（仅本次会话内有效，用于一次性读取复制）。
     */
    suspend fun import(uri: Uri): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            // 源 SRC → 缓存临时副本（供 zip 解析），随后把同一串字节写入私有书库目录。
            val tmp = File(context.cacheDir, "book_${System.currentTimeMillis()}.epub")
            try {
                context.contentResolver.openInputStream(uri).use { input ->
                    input ?: throw IllegalStateException("无法打开所选文件")
                    input.copyTo(tmp.outputStream())
                }
                val parsed = ZipEpubResourceReader(tmp.path).use { EpubParser().parse(it) }
                if (parsed.isEmpty) throw EpubFormatException("书中没有可读正文章节")
                val name = "book_${System.currentTimeMillis()}.epub"
                val target = copyToPrivate(name, tmp.readBytes())
                    ?: throw IllegalStateException("无法写入书库目录")
                repository.addBook(parsed.title, parsed.author, target)
            } catch (e: Throwable) {
                throw e
            } finally {
                runCatching { tmp.delete() }
            }
        }
    }

    /** 把书字节写入私有 `filesDir/books/`，返回绝对路径；写入失败返回 null。 */
    private fun copyToPrivate(name: String, bytes: ByteArray): String? {
        return runCatching {
            val dir = File(context.filesDir, "books").apply { mkdirs() }
            val f = File(dir, name)
            f.writeBytes(bytes)
            f.absolutePath
        }.getOrNull()
    }
}