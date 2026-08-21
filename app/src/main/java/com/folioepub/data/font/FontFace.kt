package com.folioepub.data.font

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 字体池表：只存**用户导入字体**，作为设置面板字体选择的候选。
 *
 * 设计取舍：系统字体不作为候选（移动端几十款系统字体全列会掩盖真正有用的自定义字体），
 * 样式表无字体指定时渲染走系统默认字体，无需显式选择。
 *
 * @param source 目前固定为 "imported"。字节在 [path]（`filesDir/fonts/` 下私有副本），
 *  经 `/fonts/{id}` 虚拟域 url() 加载。
 * @param lang [FontClassifier] 分类结果（cjk/latin/generic），["symbol"/"invalid"] 不作为候选。
 */
@Entity(
    tableName = "font_faces",
    indices = [Index(value = ["familyName", "source"], unique = true)],
)
data class FontFace(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val familyName: String,
    val displayName: String,
    /** 字重/样式(nameID=2，如 Regular/Bold)；同家族多字重文件并存时用于挑选保留的代表文件。 */
    val subfamily: String? = null,
    val source: String = SOURCE_IMPORTED,
    val path: String?,
    val lang: String,
) {
    companion object {
        const val SOURCE_IMPORTED = "imported"
    }
}