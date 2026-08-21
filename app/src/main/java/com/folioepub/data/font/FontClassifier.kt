package com.folioepub.data.font

/**
 * 字体语言/用途分类：依据 [FontParser.Result] 的 cmap 覆盖权重，把字体归为
 * 中文 / 拉丁 / 通用 / 符号 / 无效 五类。
 *
 * 设计意图：移动端系统字体动辄几十款（含大量覆盖不全、符号/图标、语言填充字体），
 * 若全列给用户会掩盖真正有用的字体。这里用「覆盖阈值」而非「是否有」判定：
 * 只有足量覆盖 CJK 的才当中文候选、足量覆盖拉丁的才当拉丁候选，其余归为符号/无效，
 * 从而在字体选择页自动过滤掉杂字体。
 *
 * 阈值依据区段规模：CJK 统一表意区约 2 万字、拉丁/基本拉丁区约 700 字，
 * 用「覆盖比例」而非绝对数更稳；此处按比例折算为绝对阈值。
 */
object FontClassifier {

    const val LANG_CJK = "cjk"
    const val LANG_LATIN = "latin"
    const val LANG_GENERIC = "generic"
    const val LANG_SYMBOL = "symbol"
    const val LANG_INVALID = "invalid"

    /** CJK 判定的最低覆盖码点数（区段约 20k 字，≥12% 视为真正的中文字体）。 */
    private const val CJK_THRESHOLD = 2400
    /** 拉丁判定的最低覆盖码点数（区段约 700 字，≥40% 视为真正的拉丁字体）。 */
    private const val LATIN_THRESHOLD = 26

    /**
     * 由解析结果分类。
     * @param result [FontParser.parse] 的结果；[valid]==false 直接归为 [LANG_INVALID]。
     */
    fun classify(result: FontParser.Result): String {
        if (!result.valid) return LANG_INVALID
        val cjk = result.cjkCoverage >= CJK_THRESHOLD
        val latin = result.latinCoverage >= LATIN_THRESHOLD
        return when {
            cjk && latin -> LANG_GENERIC
            cjk -> LANG_CJK
            latin -> LANG_LATIN
            else -> LANG_SYMBOL
        }
    }

    /** 是否是能作为正文风格候选的有效语言类（中文/拉丁/通用）。 */
    fun isUsable(lang: String): Boolean =
        lang == LANG_CJK || lang == LANG_LATIN || lang == LANG_GENERIC
}