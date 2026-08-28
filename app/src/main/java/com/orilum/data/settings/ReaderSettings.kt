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
 * 字体替换按「元素类型」三档：`fontBody` 正文 / `fontTitle` 标题 / `fontCode` 代码，
 * 取代旧 M2 五分类(fontBody/fontTitle/fontCode/fontBold/fontItalic，其中粗/斜不再设档)。
 * 槽位存字体 alias，全局强制——书内对应类型的文字一律用所选本地字体；
 * 空串 = 该类型不替换、保留原书。所有排版主题统一生效。
 */
data class ReaderSettings(
    /** 阅读背景（内置预设名：sepia / light / dark …）。 */
    val theme: String = "sepia",
    /** 正文字号（px）。 */
    val fontSize: Int = 18,
    /** 行距（number，1.x 倍）。 */
    val lineSpacing: Double = 1.6,
    /** 【样式系统】段间距（em）：正文段落 p（及纯文本 div）底部间距，随字号等比缩放（0..2，0=无，靠首行缩进区分段落）。 */
    val paragraphSpacing: Double = 0.0,
    /** 【样式系统】疏密（% 缩放 0..400，默认 100）：标题/引用/代码/列表等结构块的垂直边距整体缩放，永不归零。 */
    val paragraphGap: Double = 100.0,
    /** 页边距：上下左右四方向独立（px）。 */
    val marginTop: Int = 24,
    val marginBottom: Int = 24,
    val marginLeft: Int = 32,
    val marginRight: Int = 32,
    /** 字体替换·元素类型三档（alias，空串 = 该类型不替换保留原书）：正文/标题/代码。 */
    val fontBody: String = "",
    val fontTitle: String = "",
    val fontCode: String = "",
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
    /** 正文首屏封面等比例缩放开关：开 = 等比缩放（不变形、四周留阅读底色）；关 = 拉伸铺满整屏（可变形）。 */
    val coverProportional: Boolean = true,
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
    /** 阅读器亮度档位 -50..100：100=跟随系统；0..100 写系统亮度物理背光；-50..0 系统最暗再叠遮罩压暗。 */
    val brightness: Int = 100,
    /** 跟随系统亮度开关；开 → 禁用「亮度」档位滑块，改由 [brightnessOffset] 围绕系统亮度微调。 */
    val brightnessFollowSystem: Boolean = true,
    /** 跟随系统时围绕系统亮度的偏移 -20..20（0 = 完全跟随系统不调整）。 */
    val brightnessOffset: Int = 0,
    /** 护眼开关：降低蓝光（叠加暖色遮罩）。 */
    val eyeProtection: Boolean = false,
    /** 蓝光过滤量 0..100（暖色遮罩透明度比例）。 */
    val eyeProtectionLevel: Int = 40,
    /** 亮度手势（可多选，均仅非跟随系统时生效）：
     *  [brightnessGestureLeft] 屏幕左侧单指上下滑 | [brightnessGestureRight] 屏幕右侧单指上下滑 |
     *  [brightnessGestureTwo] 任意区域双指上下滑。 */
    val brightnessGestureLeft: Boolean = false,
    val brightnessGestureRight: Boolean = false,
    val brightnessGestureTwo: Boolean = false,
) {

    /** 序列化为 JSON 字符串（== [org.json.JSONObject.toString]）。 */
    @JvmOverloads
    fun toJson(indentFactor: Int = 0): String = toJsonObject().toString(indentFactor)

    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("theme", theme)
        put("fontSize", fontSize)
        put("lineSpacing", lineSpacing)
        put("paragraphSpacing", paragraphSpacing)
        put("paragraphGap", paragraphGap)
        put("marginTop", marginTop)
        put("marginBottom", marginBottom)
        put("marginLeft", marginLeft)
        put("marginRight", marginRight)
        put("fontBody", fontBody)
        put("fontTitle", fontTitle)
        put("fontCode", fontCode)
        put("useOriginalStyle", useOriginalStyle)
        put("useUserScripts", useUserScripts)
        put("pageAnim", pageAnim)
        put("autoContinue", autoContinue)
        put("pageNum", pageNum)
        put("coverProportional", coverProportional)
        put("fontScale", fontScale)
        put("scheme", scheme)
        put("bgOverride", bgOverride)
        put("fgOverride", fgOverride)
        put("layoutTheme", layoutTheme)
        put("brightness", brightness)
        put("brightnessFollowSystem", brightnessFollowSystem)
        put("brightnessOffset", brightnessOffset)
        put("eyeProtection", eyeProtection)
        put("eyeProtectionLevel", eyeProtectionLevel)
        put("brightnessGestureLeft", brightnessGestureLeft)
        put("brightnessGestureRight", brightnessGestureRight)
        put("brightnessGestureTwo", brightnessGestureTwo)
    }

    /**
     * 叠加某本书的私有覆盖层 → 返回生效值。
     *
     * [overlay] 中非 null 字段覆盖全局记忆，null 字段回退 [this]。
     * 不会修改 [this]。
     */
    fun applyOverlay(overlay: BookSettings): ReaderSettings = copy(
        layoutTheme = overlay.layoutTheme ?: layoutTheme,
        fontSize = overlay.fontSize ?: fontSize,
        fontScale = overlay.fontScale ?: fontScale,
        lineSpacing = overlay.lineSpacing ?: lineSpacing,
        paragraphSpacing = overlay.paragraphSpacing ?: paragraphSpacing,
        paragraphGap = overlay.paragraphGap ?: paragraphGap,
        marginTop = overlay.marginTop ?: marginTop,
        marginBottom = overlay.marginBottom ?: marginBottom,
        marginLeft = overlay.marginLeft ?: marginLeft,
        marginRight = overlay.marginRight ?: marginRight,
        scheme = overlay.scheme ?: scheme,
        bgOverride = overlay.bgOverride ?: bgOverride,
        fgOverride = overlay.fgOverride ?: fgOverride,
        fontBody = overlay.fontBody ?: fontBody,
        fontTitle = overlay.fontTitle ?: fontTitle,
        fontCode = overlay.fontCode ?: fontCode,
        useOriginalStyle = overlay.useOriginalStyle ?: useOriginalStyle,
        pageAnim = overlay.pageAnim ?: pageAnim,
    )

    /**
     * 将某本书的覆盖层**回写**到全局记忆。
     *
     * [overlay] 中非 null 字段更新 [this]；null 字段保持 [this] 不变。
     * 返回新 [ReaderSettings] 实例。
     */
    fun mergeFrom(overlay: BookSettings): ReaderSettings = copy(
        layoutTheme = overlay.layoutTheme ?: layoutTheme,
        fontSize = overlay.fontSize ?: fontSize,
        fontScale = overlay.fontScale ?: fontScale,
        lineSpacing = overlay.lineSpacing ?: lineSpacing,
        paragraphSpacing = overlay.paragraphSpacing ?: paragraphSpacing,
        paragraphGap = overlay.paragraphGap ?: paragraphGap,
        marginTop = overlay.marginTop ?: marginTop,
        marginBottom = overlay.marginBottom ?: marginBottom,
        marginLeft = overlay.marginLeft ?: marginLeft,
        marginRight = overlay.marginRight ?: marginRight,
        scheme = overlay.scheme ?: scheme,
        bgOverride = overlay.bgOverride ?: bgOverride,
        fgOverride = overlay.fgOverride ?: fgOverride,
        fontBody = overlay.fontBody ?: fontBody,
        fontTitle = overlay.fontTitle ?: fontTitle,
        fontCode = overlay.fontCode ?: fontCode,
        useOriginalStyle = overlay.useOriginalStyle ?: useOriginalStyle,
        pageAnim = overlay.pageAnim ?: pageAnim,
    )

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
                // 旧套装迁移：旧 margin(compact/normal/wide) 行宽预设 → 新的上下左右独立 px（紧凑=边距大、宽松=边距小）
                val hasMargin = o.has("marginTop") || o.has("marginBottom") || o.has("marginLeft") || o.has("marginRight")
                val legacyMargin = o.optString("margin", "")
                val marginTop = if (hasMargin) o.optInt("marginTop", d.marginTop)
                else when (legacyMargin) { "compact" -> 40; "wide" -> 16; else -> d.marginTop }
                val marginBottom = if (hasMargin) o.optInt("marginBottom", d.marginBottom)
                else when (legacyMargin) { "compact" -> 40; "wide" -> 16; else -> d.marginBottom }
                val marginLeft = if (hasMargin) o.optInt("marginLeft", d.marginLeft)
                else when (legacyMargin) { "compact" -> 48; "wide" -> 16; else -> d.marginLeft }
                val marginRight = if (hasMargin) o.optInt("marginRight", d.marginRight)
                else when (legacyMargin) { "compact" -> 48; "wide" -> 16; else -> d.marginRight }
                ReaderSettings(
                    theme = o.optString("theme", d.theme),
                    fontSize = o.optInt("fontSize", d.fontSize),
                    lineSpacing = o.optDouble("lineSpacing", d.lineSpacing),
                    paragraphSpacing = o.optDouble("paragraphSpacing", d.paragraphSpacing),
                    paragraphGap = o.optDouble("paragraphGap", d.paragraphGap),
                    marginTop = marginTop,
                    marginBottom = marginBottom,
                    marginLeft = marginLeft,
                    marginRight = marginRight,
                    fontBody = o.optString("fontBody", d.fontBody),
                    fontTitle = o.optString("fontTitle", d.fontTitle),
                    fontCode = o.optString("fontCode", d.fontCode),
                    useOriginalStyle = o.optBoolean("useOriginalStyle", d.useOriginalStyle),
                    useUserScripts = o.optBoolean("useUserScripts", d.useUserScripts),
                    pageAnim = o.optBoolean("pageAnim", d.pageAnim),
                    autoContinue = o.optBoolean("autoContinue", d.autoContinue),
                    pageNum = o.optBoolean("pageNum", d.pageNum),
                    coverProportional = o.optBoolean("coverProportional", d.coverProportional),
                    fontScale = fontScale,
                    scheme = scheme,
                    bgOverride = bgOverride,
                    fgOverride = o.optString("fgOverride", d.fgOverride),
                    layoutTheme = o.optString("layoutTheme", d.layoutTheme),
                    brightness = o.optInt("brightness", d.brightness),
                    brightnessFollowSystem = o.optBoolean("brightnessFollowSystem", d.brightnessFollowSystem),
                    brightnessOffset = o.optInt("brightnessOffset", d.brightnessOffset),
                    eyeProtection = o.optBoolean("eyeProtection", d.eyeProtection),
                    eyeProtectionLevel = o.optInt("eyeProtectionLevel", d.eyeProtectionLevel),
                    brightnessGestureLeft = o.optBoolean("brightnessGestureLeft", d.brightnessGestureLeft),
                    brightnessGestureRight = o.optBoolean("brightnessGestureRight", d.brightnessGestureRight),
                    brightnessGestureTwo = o.optBoolean("brightnessGestureTwo", d.brightnessGestureTwo),
                )
            } catch (_: Exception) {
                DEFAULT
            }
        }
    }
}