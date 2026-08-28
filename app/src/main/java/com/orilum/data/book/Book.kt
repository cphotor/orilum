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
    /** 封面图本地私有路径（导入时从 EPUB 提取缓存）；无封面为 null。 */
    val coverPath: String? = null,
    /** 最近一次打开阅读的时间戳（ms）；用于「按阅读时间近→远」排序。 */
    val readTime: Long? = null,
)