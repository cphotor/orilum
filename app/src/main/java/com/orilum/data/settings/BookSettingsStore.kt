package com.orilum.data.settings

import java.io.File

/**
 * 按书的私有设置覆盖层读写：`filesDir/settings/books/{bookId}.json`。
 *
 * 职责：
 *  - [load]：读取某本书的覆盖层；无文件 → [BookSettings.EMPTY]（全部回退全局记忆）。
 *  - [save]：保存覆盖层（原子写）。
 *  - [reset]：删除某本书的覆盖层，此后该书完全回退全局记忆。
 *
 * 线程安全：方法供 IO 线程调用，建议在协程 Dispatchers.IO 内执行。
 *
 * @see BookSettings 覆盖层数据类
 * @see ReaderSettingsStore 全局记忆
 */
class BookSettingsStore(private val settingsDir: File) {

    private val booksDir = File(settingsDir, BOOKS_DIR)

    /** 读取某本书的覆盖层；无文件 → [BookSettings.EMPTY]。 */
    fun load(bookId: Long): BookSettings {
        val file = bookFile(bookId)
        return if (file.isFile) BookSettings.fromJson(file.readText()) else BookSettings.EMPTY
    }

    /** 保存某本书的覆盖层（原子写）。 */
    fun save(bookId: Long, overlay: BookSettings) {
        if (overlay.isEmpty) {
            // 空覆盖无需落盘，删除已有文件即可
            reset(bookId)
            return
        }
        booksDir.mkdirs()
        val file = bookFile(bookId)
        val tmp = File(booksDir, "${bookId}.json.tmp")
        tmp.writeText(overlay.toJson(2))
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            file.writeText(overlay.toJson(2))
            tmp.delete()
        }
    }

    /** 删除某本书的覆盖层。 */
    fun reset(bookId: Long) {
        val file = bookFile(bookId)
        if (file.exists()) file.delete()
        val tmp = File(booksDir, "${bookId}.json.tmp")
        if (tmp.exists()) tmp.delete()
    }

    private fun bookFile(bookId: Long) = File(booksDir, "${bookId}.json")

    private companion object {
        const val BOOKS_DIR = "books"
    }
}