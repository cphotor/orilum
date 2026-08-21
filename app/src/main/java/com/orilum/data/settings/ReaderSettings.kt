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
    }

    companion object {
        /** 默认套：即未做任何定制时的出厂值。 */
        val DEFAULT = ReaderSettings()

        /** 从 JSON 解析；字段缺失或非法时回退对应默认值（容忍部分缺省/新版本兼容）。 */
        @JvmStatic
        fun fromJson(json: String?): ReaderSettings {
            if (json.isNullOrBlank()) return DEFAULT
            return try {
                val o = JSONObject(json)
                val d = DEFAULT
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
                )
            } catch (_: Exception) {
                DEFAULT
            }
        }
    }
}