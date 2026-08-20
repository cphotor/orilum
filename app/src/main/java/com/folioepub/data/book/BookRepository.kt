package com.folioepub.data.book

import kotlinx.coroutines.flow.Flow

/**
 * 书库仓库：封装书架 CRUD 与阅读进度存取。
 *
 * M0 只做数据持久化；「根据 sourceUri 打开并解析 EPUB」由渲染层在阅读时调用（见 M0 闭环）。
 */
class BookRepository(private val dao: BookDao) {

    /** 书架（按加入时间倒序）。 */
    fun books(): Flow<List<Book>> = dao.observeBooks()

    suspend fun getBook(id: Long): Book? = dao.book(id)

    /** 加入书架；返回新记录主键。 */
    suspend fun addBook(title: String, author: String?, filePath: String): Long =
        dao.insert(Book(title = title, author = author, filePath = filePath))

    suspend fun removeBook(book: Book) {
        dao.delete(book)
        dao.clearReadingState(book.id)
    }

    // ---- 阅读进度 ----

    suspend fun saveReadingState(state: BookReadingState) = dao.saveReadingState(state)

    suspend fun readingState(bookId: Long): BookReadingState? = dao.readingState(bookId)
}