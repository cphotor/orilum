package com.orilum.data.book

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 书架上的书。
 *
 * 元数据 +【本地书文件位置】——`filePath` 是公共书库目录内 EPUB 的 `content://` 文档 uri
 * （经 SAF 持久授权，重启后仍可直接读取）。书本体存公共目录、用户可见可共享；
 * 书名/作者/阅读进度等元数据存私有 Room。
 *
 * @property filePath 公共书库目录内 EPUB 的 content:// uri。
 */
@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String? = null,
    val filePath: String,
    val addedAt: Long = System.currentTimeMillis(),
)