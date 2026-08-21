package com.orilum.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReaderSettings] 序列化/解析/回退行为测试：
 *  - 默认套 round-trip 一致；
 *  - 部分缺省/字段非法时容忍回退（新版兼容）；
 *  - 未知字段静默忽略；
 *  - 空/坏 JSON 回退默认。
 */
class ReaderSettingsTest {

    @Test
    fun `default round-trips through fromJson`() {
        val parsed = ReaderSettings.fromJson(ReaderSettings.DEFAULT.toJson())
        assertEquals(ReaderSettings.DEFAULT, parsed)
    }

    @Test
    fun `full custom round-trips`() {
        val custom = ReaderSettings(
            theme = "dark",
            fontSize = 22,
            lineSpacing = 2.0,
            margin = "wide",
            fontBody = "Songti",
            fontTitle = "Heiti",
            fontCode = "Monaco",
            fontBold = "Heiti",
            fontItalic = "Kaiti",
            useOriginalStyle = true,
            useUserScripts = true,
            pageAnim = false,
            autoContinue = false,
            pageNum = true,
        )
        assertEquals(custom, ReaderSettings.fromJson(custom.toJson()))
    }

    @Test
    fun `partial json falls back to defaults for missing fields`() {
        val parsed = ReaderSettings.fromJson("""{"fontSize":20}""")
        assertEquals(20, parsed.fontSize)          // 提供者生效
        assertEquals(ReaderSettings.DEFAULT.theme, parsed.theme)         // 缺省回退
        assertEquals(ReaderSettings.DEFAULT.lineSpacing, parsed.lineSpacing, 0.0)
        assertEquals(ReaderSettings.DEFAULT.useOriginalStyle, parsed.useOriginalStyle)
    }

    @Test
    fun `unknown fields are ignored`() {
        val parsed = ReaderSettings.fromJson("""{"fontSize":19,"someFutureField":"x"}""")
        assertNull(parsed.toJsonObject().opt("someFutureField"))
        assertEquals(19, parsed.fontSize)
    }

    @Test
    fun `blank json returns default`() {
        assertEquals(ReaderSettings.DEFAULT, ReaderSettings.fromJson(null))
        assertEquals(ReaderSettings.DEFAULT, ReaderSettings.fromJson(""))
        assertEquals(ReaderSettings.DEFAULT, ReaderSettings.fromJson("   "))
    }

    @Test
    fun `malformed json returns default`() {
        assertEquals(ReaderSettings.DEFAULT, ReaderSettings.fromJson("not json at all"))
        assertEquals(ReaderSettings.DEFAULT, ReaderSettings.fromJson("{broken"))
    }

    @Test
    fun `json output contains all keys`() {
        val obj = ReaderSettings.DEFAULT.toJsonObject()
        listOf(
            "theme", "fontSize", "lineSpacing", "margin",
            "fontBody", "fontTitle", "fontCode", "fontBold", "fontItalic",
            "useOriginalStyle", "useUserScripts", "pageAnim", "autoContinue", "pageNum",
            "fontScale", "scheme", "bgOverride", "layoutTheme",
        ).forEach { assertTrue("missing key $it", obj.has(it)) }
        assertNotNull(obj.keys())
        assertFalse(ReaderSettings.DEFAULT.useOriginalStyle)
    }

    // ── 样式系统 · 字号相对档位映射 ─────────────────────────
    @Test
    fun `font scale midpoint is identity`() {
        assertEquals(1.0, ReaderSettings.fontScaleToRatio(50.0), 1e-9)
        assertEquals(0.5, ReaderSettings.fontScaleToRatio(0.0), 1e-9)
        assertEquals(2.0, ReaderSettings.fontScaleToRatio(100.0), 1e-9)
        // 反向：1.0 ↔ 50
        assertEquals(50.0, ReaderSettings.ratioToFontScale(1.0), 1e-9)
    }

    @Test
    fun `legacy absolute fontsize migrates to scale`() {
        // 旧套只有 fontSize=18(默认) → 迁移为 50（默认档位）
        val defaultMigrated = ReaderSettings.fromJson("""{"fontSize":18}""")
        assertEquals(50.0, defaultMigrated.fontScale, 1e-9)
        // 旧套 fontSize=36（2 倍）→ 迁移为 100
        val doubleMigrated = ReaderSettings.fromJson("""{"fontSize":36}""")
        assertEquals(100.0, doubleMigrated.fontScale, 1e-9)
    }

    @Test
    fun `legacy theme migrates to scheme and bg override`() {
        // 旧 theme=dark(暗夜) → 整套夜间配色
        val dark = ReaderSettings.fromJson("""{"theme":"dark"}""")
        assertEquals("night", dark.scheme)
        assertEquals("", dark.bgOverride)
        // 旧 theme=sepia(羊皮纸) → 白天配色 + 背景覆盖色
        val sepia = ReaderSettings.fromJson("""{"theme":"sepia"}""")
        assertEquals("day", sepia.scheme)
        assertEquals("#f4f2ec", sepia.bgOverride)
        // 新体系字段优先，不再被旧 theme 覆盖
        val explicit = ReaderSettings.fromJson("""{"theme":"white","scheme":"night","bgOverride":"#112233"}""")
        assertEquals("night", explicit.scheme)
        assertEquals("#112233", explicit.bgOverride)
    }

    @Test
    fun `new style fields round-trip`() {
        val custom = ReaderSettings(
            fontScale = 72.0,
            scheme = "night",
            bgOverride = "#123456",
            layoutTheme = "traditional",
        )
        assertEquals(custom, ReaderSettings.fromJson(custom.toJson()))
    }
}