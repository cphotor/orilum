package com.folioepub.data.book

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 书架 + 阅读进度的访问接口。
 */
@Dao
interface BookDao {

    @Insert
    suspend fun insert(book: Book): Long

    @Query("SELECT * FROM books ORDER BY addedAt DESC")
    fun observeBooks(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun book(id: Long): Book?

    @Delete
    suspend fun delete(book: Book)

    // ---- 阅读进度 ----

    @Upsert
    suspend fun saveReadingState(state: BookReadingState)

    @Query("SELECT * FROM reading_states WHERE bookId = :bookId")
    suspend fun readingState(bookId: Long): BookReadingState?

    @Query("DELETE FROM reading_states WHERE bookId = :bookId")
    suspend fun clearReadingState(bookId: Long)
}