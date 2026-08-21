package com.orilum.data.settings

import org.json.JSONObject

/**
 * 阅读全局设置（单层全量对象）。
 *
 * 属**全局**的用户偏好，跨书一致（字号/行距/字体/主题…）；按书的数据走
 * `BookReadingState`（阅读进度），不在此表。
 *
 * 双套机制：
 *  - **默认套**：[Companion.DEFAULT]（内置常量，不落盘）；
 *  - **用户套**：由 [ReaderSettingsStore] 存成 `filesDir/settings/reader.json`；
 *  - **一键重置**：删除用户文件 → 回退 [Companion.DEFAULT]。
 *
 * M2 字体五分类：`fontBody` 正文 / `fontTitle` 标题 / `fontCode` 代码 /
 * `fontBold` 粗体 / `fontItalic` 斜体。空串表示「跟随原书样式」，由
 * [useOriginalStyle] 总开关统一控制是否注入自定义字体。
 */
data class ReaderSettings(
    /** 阅读背景（内置预设名：sepia / light / dark …）。 */
    val theme: String = "sepia",
    /** 正文字号（px）。 */
    val fontSize: Int = 18,
    /** 行距（number，1.x 倍）。 */
    val lineSpacing: Double = 1.6,
    /** 页边距预设名（compact / normal / wide）。 */
    val margin: String = "normal",
    /** 正文/标题/代码/粗体/斜体字体；空串 = 跟随原书样式。 */
    val fontBody: String = "",
    val fontTitle: String = "",
    val fontCode: String = "",
    val fontBold: String = "",
    val fontItalic: String = "",
    /** 【总开关】跟随原书样式；开则不注入自定义字体/字号（仅排版有效）。 */
    val useOriginalStyle: Boolean = false,
    /** L2 用户导入样式表开关（M2）。 */
    val useUserScripts: Boolean = false,
    /** 翻页动画（平滑滑动）。 */
    val pageAnim: Boolean = true,
    /** 打开时自动续读（回上次位置）。 */
    val autoContinue: Boolean = true,
    /** 显示页码。 */
    val pageNum: Boolean = false,
    /** 【样式系统】字号相对默认缩放：0..100 滑块，50 = 默认 1.0（见 COMPANION 映射）。 */
    val fontScale: Double = DEFAULT_FONT_SCALE,
    /** 【样式系统】日夜整套配色：day（白天）| night（夜间）。 */
    val scheme: String = "day",
    /** 【样式系统】独立覆盖背景色（hex 如 "#f4f2ec"）；空串 = 跟随 [scheme] 默认背景。 */
    val bgOverride: String = "",
    /** 【样式系统】独立覆盖正文字色（hex 如 "#2b2b2b"）；空串 = 跟随 [scheme] 默认文字色。 */
    val fgOverride: String = "",
    /** 【样式系统】排版主题：original（原书设置）| modern（现代）| traditional（传统）。 */
    val layoutTheme: String = "original",
) {

    /** 序列化为 JSON 字符串（== [org.json.JSONObject.toString]）。 */
    @JvmOverloads
    fun toJson(indentFactor: Int = 0): String = toJsonObject().toString(indentFactor)

    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("theme", theme)
        put("fontSize", fontSize)
        put("lineSpacing", lineSpacing)
        put("margin", margin)
        put("fontBody", fontBody)
        put("fontTitle", fontTitle)
        put("fontCode", fontCode)
        put("fontBold", fontBold)
        put("fontItalic", fontItalic)
        put("useOriginalStyle", useOriginalStyle)
        put("useUserScripts", useUserScripts)
        put("pageAnim", pageAnim)
        put("autoContinue", autoContinue)
        put("pageNum", pageNum)
        put("fontScale", fontScale)
        put("scheme", scheme)
        put("bgOverride", bgOverride)
        put("fgOverride", fgOverride)
        put("layoutTheme", layoutTheme)
    }

    companion object {
        /** 默认套：即未做任何定制时的出厂值。 */
        val DEFAULT = ReaderSettings()

        /** 默认正文字号（px），作为 [fontScale] 标定 1.0（=50）的锚点正文。 */
        const val BASE_BODY_PX = 18

        /** 出厂字号档位：0..100 滑块，50 = 默认 1.0。 */
        const val DEFAULT_FONT_SCALE = 50.0

        /**
         * 相对默认分段映射：把 0..100 档位映射为缩放系数。
         *  - 0..50：0.5 → 1.0（`0.5 + v*0.01`）
         *  - 50..100：1.0 → 2.0（`v*0.02`）
         * 50 档 = 元素自身默认（正文字号 = [BASE_BODY_PX]，标题等随其 em 基准等比缩放）。
         */
        fun fontScaleToRatio(v: Double): Double {
            val x = v.coerceIn(0.0, 100.0)
            return if (x <= 50.0) 0.5 + x * 0.01 else x * 0.02
        }

        /** [fontScaleToRatio] 的反解（<1.0 时在 0..50 半段取反；≥1.0 在 50..100）。仅用于旧绝对 px 迁移。 */
        fun ratioToFontScale(ratio: Double): Double {
            val r = ratio.coerceIn(0.5, 2.0)
            val v = if (r <= 1.0) (r - 0.5) / 0.01 else r / 0.02
            return v.coerceIn(0.0, 100.0)
        }

        /** 旧版绝对正文字号（px）→ 相对默认档位。 */
        fun fontSizePxToScale(px: Int): Double = ratioToFontScale(px.toDouble() / BASE_BODY_PX)

        /**
         * 从 JSON 解析；字段缺失或非法时回退对应默认值（容忍部分缺省/新版本兼容）。
         * 旧套（只有绝对 `fontSize` px、无 `fontScale`）自动迁移为相对档位。
         */
        @JvmStatic
        fun fromJson(json: String?): ReaderSettings {
            if (json.isNullOrBlank()) return DEFAULT
            return try {
                val o = JSONObject(json)
                val d = DEFAULT
                // 旧套装迁移：无 fontScale 但带旧绝对 fontSize(px) → 反解档位；否则用新的相对档位
                val fontScale = if (o.has("fontScale")) {
                    o.optDouble("fontScale", d.fontScale)
                } else if (o.has("fontSize")) {
                    fontSizePxToScale(o.optInt("fontSize", d.fontSize))
                } else {
                    d.fontScale
                }
                // 旧套装迁移：旧 theme(sepia/white/dark) 无 scheme/bgOverride → 映射到新配色体系
                //  dark(暗夜)→night 整套；white(亮白)/sepia(羊皮纸)→day 配色 + 背景色覆盖
                val hasScheme = o.has("scheme")
                val hasBg = o.has("bgOverride")
                val legacyTheme = o.optString("theme", "sepia")
                val scheme = if (hasScheme) o.optString("scheme", d.scheme)
                else if (legacyTheme == "dark") "night" else d.scheme
                val bgOverride = when {
                    hasBg -> o.optString("bgOverride", d.bgOverride)
                    legacyTheme == "white" -> "#ffffff"
                    legacyTheme == "sepia" -> "#f4f2ec"
                    else -> d.bgOverride
                }
                ReaderSettings(
                    theme = o.optString("theme", d.theme),
                    fontSize = o.optInt("fontSize", d.fontSize),
                    lineSpacing = o.optDouble("lineSpacing", d.lineSpacing),
                    margin = o.optString("margin", d.margin),
                    fontBody = o.optString("fontBody", d.fontBody),
                    fontTitle = o.optString("fontTitle", d.fontTitle),
                    fontCode = o.optString("fontCode", d.fontCode),
                    fontBold = o.optString("fontBold", d.fontBold),
                    fontItalic = o.optString("fontItalic", d.fontItalic),
                    useOriginalStyle = o.optBoolean("useOriginalStyle", d.useOriginalStyle),
                    useUserScripts = o.optBoolean("useUserScripts", d.useUserScripts),
                    pageAnim = o.optBoolean("pageAnim", d.pageAnim),
                    autoContinue = o.optBoolean("autoContinue", d.autoContinue),
                    pageNum = o.optBoolean("pageNum", d.pageNum),
                    fontScale = fontScale,
                    scheme = scheme,
                    bgOverride = bgOverride,
                    fgOverride = o.optString("fgOverride", d.fgOverride),
                    layoutTheme = o.optString("layoutTheme", d.layoutTheme),
                )
            } catch (_: Exception) {
                DEFAULT
            }
        }
    }
}