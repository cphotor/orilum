package com.orilum.data.book

import kotlinx.coroutines.flow.Flow

/**
 * 书库仓库：封装书架 CRUD 与阅读进度存取。
 *
 * M0 只做数据持久化；「根据 sourceUri 打开并解析 EPUB」由渲染层在阅读时调用（见 M0 闭环）。
 */
class BookRepository(private val dao: BookDao) {

    /** 书架排序枚举：加入时间 / 阅读时间 / 书名。 */
    enum class Sort(val daoFlow: BookDao.() -> Flow<List<Book>>) {
        Added(BookDao::observeBooks),
        Read(BookDao::observeBooksByReadTime),
        Name(BookDao::observeBooksByName),
    }

    /** 书架（按指定排序）。 */
    fun books(sort: Sort = Sort.Added): Flow<List<Book>> = sort.daoFlow(dao)

    suspend fun getBook(id: Long): Book? = dao.book(id)

    /** 一次性返回书库全量（用于导入重复判定等非流式场景）。 */
    suspend fun allBooks(): List<Book> = dao.allBooks()

    /** 加入书架；返回新记录主键。 */
    suspend fun addBook(title: String, author: String?, filePath: String, coverPath: String? = null): Long =
        dao.insert(Book(title = title, author = author, filePath = filePath, coverPath = coverPath))

    /** 更新图书封面本地路径。 */
    suspend fun updateBook(book: Book) = dao.upsert(book)

    suspend fun touchRead(id: Long) = dao.updateReadTime(id, System.currentTimeMillis())

    suspend fun removeBook(book: Book) {
        dao.delete(book)
        dao.clearReadingState(book.id)
    }

    // ---- 阅读进度 ----

    suspend fun saveReadingState(state: BookReadingState) = dao.saveReadingState(state)

    suspend fun readingState(bookId: Long): BookReadingState? = dao.readingState(bookId)
}