package com.folioepub.data.book

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 某本书的阅读进度（M0 简版，与 [com.folioepub.data.read.Location] 对应）。
 *
 * 进度上报（阅读时）与书架「接上次位置」都落在这里；字段含义见 Location：
 * [chapter] 章节索引、[target] 章内锚、[progress] 章内进度。
 */
@Entity(tableName = "reading_states")
data class BookReadingState(
    @PrimaryKey val bookId: Long,
    val chapter: Int,
    val target: String? = null,
    val progress: Double = 0.0,
    val updatedAt: Long = System.currentTimeMillis(),
)