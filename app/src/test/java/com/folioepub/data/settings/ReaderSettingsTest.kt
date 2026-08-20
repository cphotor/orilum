package com.folioepub.data.settings

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
        ).forEach { assertTrue("missing key $it", obj.has(it)) }
        assertNotNull(obj.keys())
        assertFalse(ReaderSettings.DEFAULT.useOriginalStyle)
    }
}