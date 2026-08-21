package com.orilum.data.read

/**
 * 阅读定位模型（M0 简版：章节索引 + 章内进度）。
 *
 * 设计动机见 ARCHITECTURE-FOLIATE.md §5：MEPUB 定位以「章节 index + 章内偏移/比例」为主，
 * 便于进度上报、存读恢复；后续 M4/M5 阶段再叠加 CFI 兼容串（书签/笔记的稳定溯回）。
 *
 * @property chapter 章节在 spine 中的索引（0 起）。
 * @property target 章节内定位锚（如原书 `#id`）；无则 null。显式锚优先于 [progress] 的换算。
 * @property progress 章节内进度，0.0 表示章首，1.0 表示章尾。
 */
data class Location(
    val chapter: Int,
    val target: String? = null,
    val progress: Double = 0.0,
) {
    init {
        require(chapter >= 0) { "chapter 不能为负：$chapter" }
    }

    /** 全书进度（0.0..1.0），按章节数等权折算；越界自动钳制。 */
    fun bookProgress(chapterCount: Int): Double {
        if (chapterCount <= 0) return 0.0
        val c = chapter.coerceIn(0, chapterCount - 1)
        val p = progress.coerceIn(0.0, 1.0)
        return ((c + p) / chapterCount).coerceIn(0.0, 1.0)
    }
}

/**
 * 全书进度值与 [Location] 的互换算（纯逻辑、可单测）。
 *
 * 约定：全书进度按「章节等权」折算。这对章节长度差异较大的书只是近似，
 * 足够 M0 的进度条显示与「从书架点开接着上次位置」；精确进度待 M1 引入 foliate 的
 * 字符级 fraction 后再增强。
 */
object ProgressConverter {

    /** 由全书进度反推位置；章节数非法时返回 null。 */
    fun locationFromBookProgress(percent: Double, chapterCount: Int): Location? {
        if (chapterCount <= 0) return null
        val clamped = percent.coerceIn(0.0, 1.0)
        val raw = clamped * chapterCount
        val chapter = raw.toInt().coerceIn(0, chapterCount - 1)
        val progress = (raw - chapter).coerceIn(0.0, 1.0)
        return Location(chapter = chapter, progress = progress)
    }

    /** 章节进度 → 全书进度。 */
    fun bookProgressOf(chapter: Int, withinChapter: Double, chapterCount: Int): Double =
        Location(chapter, progress = withinChapter).bookProgress(chapterCount)
}