package com.orilum.data.epub

/**
 * 解析后的电子书模型，是阅读器的核心数据结构。
 *
 * @property spine 阅读顺序的章节列表（书芯）。
 * @property toc 目录树，节点通过 [TocItem.index] 关联到 spine 章节。
 */
data class EpubBook(
    val title: String,
    val author: String?,
    val spine: List<SpineItem>,
    val toc: List<TocItem>,
    /** 封面图资源的完整路径（相对 OPF 目录）；无封面为 null。 */
    val cover: String? = null,
) {
    /** 书芯为空视为无效电子书。 */
    val isEmpty: Boolean get() = spine.isEmpty()
}

/**
 * spine 中的一个章节（即一份正文 xhtml）。
 *
 * @property href 相对 OPF 目录的原样路径（大小写保留），用于读取正文。
 * @property fragment 章节内定位锚（如 `#sec1`），无则 null。
 */
data class SpineItem(
    val index: Int,
    val href: String,
    val fragment: String?,
    val mediaType: String?,
)

/**
 * 目录节点（可嵌套）。
 *
 * @property index 命中 spine 的章节索引；无对应章节（如仅链接到资源）时为 null。
 * @property fragment 目录目标锚点。
 */
data class TocItem(
    val label: String,
    val index: Int?,
    val fragment: String?,
    val children: List<TocItem> = emptyList(),
)

/** EPUB 格式不合法时抛出。 */
class EpubFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)