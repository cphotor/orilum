package com.orilum.data.book

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 某本书的阅读进度。
 *
 * 精确恢复依赖 [locator]（foliate lastLocation 的 JSON）。
 * [chapter] 取 lastLocation.section.current（章节索引），[progress] 为全书进度(0..1)；
 * 二者用于书架展示与轻量换算，恢复以 [locator] 为准。
 */
@Entity(tableName = "reading_states")
data class BookReadingState(
    @PrimaryKey val bookId: Long,
    val chapter: Int,
    val target: String? = null,
    val progress: Double = 0.0,
    /** foliate `view.lastLocation` 的 JSON 序列化；重开时原样回传给 `init({ lastLocation })` 精确定位。 */
    val locator: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)