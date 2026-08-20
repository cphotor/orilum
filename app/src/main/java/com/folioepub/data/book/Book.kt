package com.folioepub.data.book

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 书架上的书（M0 简版）。
 *
 * 元数据 +【本地书文件路径】——`filePath` 指向导入时拷贝进 App 私有目录
 * `filesDir/books/` 的 epub 副本。书一经导入即应用持有，不受 SAF content://
 * 授权生命周期影响，重启后仍可直接读取。
 *
 * @property filePath 私有目录内 EPUB 副本的绝对路径。
 */
@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String? = null,
    val filePath: String,
    val addedAt: Long = System.currentTimeMillis(),
)