package com.orilum.data.settings

import org.json.JSONObject

/**
 * 某本书的**私有设置覆盖层**（overlay）。
 *
 * 所有字段 nullable = `null` 表示「未设置，走全局记忆」。
 *
 * 读取时由 [ReaderSettings.applyOverlay] 将全局与覆盖层合并为生效值；
 * 写入时由 [ReaderSettings.mergeFrom] 将覆盖层回写全局记忆（逐项传染）。
 *
 * @see ReaderSettings 全局记忆（跨书共享默认值）
 * @see BookSettingsStore 覆盖层持久化
 */
data class BookSettings(
    /** 排版主题：original（原书设置）| modern（现代）| traditional（传统）。 */
    val layoutTheme: String? = null,
    /** 正文字号（px）。 */
    val fontSize: Int? = null,
    /** 字号缩放档位 0..100（50=1.0）。 */
    val fontScale: Double? = null,
    /** 行距（number，1.x 倍）。 */
    val lineSpacing: Double? = null,
    /** 段间距（em，0..2）。 */
    val paragraphSpacing: Double? = null,
    /** 疏密缩放（% 0..400，100=默认）。 */
    val paragraphGap: Double? = null,
    /** 页边距上下左右（px）。 */
    val marginTop: Int? = null,
    val marginBottom: Int? = null,
    val marginLeft: Int? = null,
    val marginRight: Int? = null,
    /** 日夜配色：day | night。 */
    val scheme: String? = null,
    /** 独立覆盖背景色（hex）；空串 = 跟随 scheme 默认。 */
    val bgOverride: String? = null,
    /** 独立覆盖正文字色（hex）；空串 = 跟随 scheme 默认。 */
    val fgOverride: String? = null,
    /** 正文/标题/代码/粗体/斜体字体；空串 = 跟随原书。 */
    val fontBody: String? = null,
    val fontTitle: String? = null,
    val fontCode: String? = null,
    val fontBold: String? = null,
    val fontItalic: String? = null,
    /** 跟随原书样式总开关。 */
    val useOriginalStyle: Boolean? = null,
    /** 翻页动画（平滑滑动）。 */
    val pageAnim: Boolean? = null,
) {
    /** 序列化为 JSON（仅输出非 null 字段）。 */
    @JvmOverloads
    fun toJson(indentFactor: Int = 0): String = toJsonObject().toString(indentFactor)

    /** 输出 JSONObject，跳过 null 字段。 */
    fun toJsonObject(): JSONObject = JSONObject().apply {
        layoutTheme?.let { put("layoutTheme", it) }
        fontSize?.let { put("fontSize", it) }
        fontScale?.let { put("fontScale", it) }
        lineSpacing?.let { put("lineSpacing", it) }
        paragraphSpacing?.let { put("paragraphSpacing", it) }
        paragraphGap?.let { put("paragraphGap", it) }
        marginTop?.let { put("marginTop", it) }
        marginBottom?.let { put("marginBottom", it) }
        marginLeft?.let { put("marginLeft", it) }
        marginRight?.let { put("marginRight", it) }
        scheme?.let { put("scheme", it) }
        bgOverride?.let { put("bgOverride", it) }
        fgOverride?.let { put("fgOverride", it) }
        fontBody?.let { put("fontBody", it) }
        fontTitle?.let { put("fontTitle", it) }
        fontCode?.let { put("fontCode", it) }
        fontBold?.let { put("fontBold", it) }
        fontItalic?.let { put("fontItalic", it) }
        useOriginalStyle?.let { put("useOriginalStyle", it) }
        pageAnim?.let { put("pageAnim", it) }
    }

    /** 是否为空（没有任何覆盖层）。 */
    val isEmpty: Boolean
        get() = !toJsonObject().keys().hasNext()

    companion object {
        /** 空覆盖层（所有字段 = null，完全使用全局记忆）。 */
        val EMPTY = BookSettings()

        /**
         * 从 JSON 解析；字段缺失或非法时回退 null（视为未覆盖）。
         */
        @JvmStatic
        fun fromJson(json: String?): BookSettings {
            if (json.isNullOrBlank()) return EMPTY
            return try {
                val o = JSONObject(json)
                BookSettings(
                    layoutTheme = if (o.has("layoutTheme")) o.optString("layoutTheme") else null,
                    fontSize = if (o.has("fontSize")) o.optInt("fontSize") else null,
                    fontScale = if (o.has("fontScale")) o.optDouble("fontScale") else null,
                    lineSpacing = if (o.has("lineSpacing")) o.optDouble("lineSpacing") else null,
                    paragraphSpacing = if (o.has("paragraphSpacing")) o.optDouble("paragraphSpacing") else null,
                    paragraphGap = if (o.has("paragraphGap")) o.optDouble("paragraphGap") else null,
                    marginTop = if (o.has("marginTop")) o.optInt("marginTop") else null,
                    marginBottom = if (o.has("marginBottom")) o.optInt("marginBottom") else null,
                    marginLeft = if (o.has("marginLeft")) o.optInt("marginLeft") else null,
                    marginRight = if (o.has("marginRight")) o.optInt("marginRight") else null,
                    scheme = if (o.has("scheme")) o.optString("scheme") else null,
                    bgOverride = if (o.has("bgOverride")) o.optString("bgOverride") else null,
                    fgOverride = if (o.has("fgOverride")) o.optString("fgOverride") else null,
                    fontBody = if (o.has("fontBody")) o.optString("fontBody") else null,
                    fontTitle = if (o.has("fontTitle")) o.optString("fontTitle") else null,
                    fontCode = if (o.has("fontCode")) o.optString("fontCode") else null,
                    fontBold = if (o.has("fontBold")) o.optString("fontBold") else null,
                    fontItalic = if (o.has("fontItalic")) o.optString("fontItalic") else null,
                    useOriginalStyle = if (o.has("useOriginalStyle")) o.optBoolean("useOriginalStyle") else null,
                    pageAnim = if (o.has("pageAnim")) o.optBoolean("pageAnim") else null,
                )
            } catch (_: Exception) {
                EMPTY
            }
        }

        /**
         * 从完整的 [ReaderSettings] JSON 中**提取**仅属于覆盖层的字段。
         * 用于 JS 回写：JS 传回整份 JSON，我们只取可覆盖的字段。
         */
        @JvmStatic
        fun fromReaderSettingsJson(json: String?): BookSettings {
            if (json.isNullOrBlank()) return EMPTY
            return try {
                // 先解析为 ReaderSettings，再提取覆盖字段
                val rs = ReaderSettings.fromJson(json)
                fromReaderSettings(rs)
            } catch (_: Exception) {
                EMPTY
            }
        }

        /**
         * 从 [ReaderSettings] 对象提取覆盖层。
         * 用于 JS 回写：取出可覆盖字段，与全局不一致的写入覆盖层。
         */
        @JvmStatic
        fun fromReaderSettings(rs: ReaderSettings): BookSettings = BookSettings(
            layoutTheme = rs.layoutTheme,
            fontSize = rs.fontSize,
            fontScale = rs.fontScale,
            lineSpacing = rs.lineSpacing,
            paragraphSpacing = rs.paragraphSpacing,
            paragraphGap = rs.paragraphGap,
            marginTop = rs.marginTop,
            marginBottom = rs.marginBottom,
            marginLeft = rs.marginLeft,
            marginRight = rs.marginRight,
            scheme = rs.scheme,
            bgOverride = rs.bgOverride,
            fgOverride = rs.fgOverride,
            fontBody = rs.fontBody,
            fontTitle = rs.fontTitle,
            fontCode = rs.fontCode,
            fontBold = rs.fontBold,
            fontItalic = rs.fontItalic,
            useOriginalStyle = rs.useOriginalStyle,
            pageAnim = rs.pageAnim,
        )
    }
}