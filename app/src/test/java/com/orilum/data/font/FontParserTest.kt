package com.orilum.data.font

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FontParser] 解析与 [FontClassifier] 分类的单元测试。
 *
 * 用程序构造的最小 sfnt 字节（仅含 cmap + name 表）验证解析逻辑，
 * 避免依赖环境里的真实字体文件（真实设备字体留待真机验证）。
 */
class FontParserTest {

    private val parser = FontParser()

    @Test
    fun `format4 classified as generic when both cjk and latin covered`() {
        val font = sfntFont(
            cmapTable(fmt4(arrayOf(intArrayOf(0x0020, 0x007A), intArrayOf(0x4E00, 0x9FFF))), 3, 1),
            nameTable("Source Han Serif"),
        )
        val res = parser.parse(font)
        assertTrue(res.valid)
        assertEquals("Source Han Serif", res.familyName)
        assertEquals(20992, res.cjkCoverage) // U+4E00..U+9FFF
        assertEquals(91, res.latinCoverage)   // U+0020..U+007A within U+0000-02FF
        assertEquals(FontClassifier.LANG_GENERIC, FontClassifier.classify(res))
    }

    @Test
    fun `cjk only classified as cjk`() {
        val font = sfntFont(
            cmapTable(fmt4(arrayOf(intArrayOf(0x4E00, 0x9FFF))), 3, 10),
            nameTable("ChineseSong"),
        )
        val res = parser.parse(font)
        assertEquals(FontClassifier.LANG_CJK, FontClassifier.classify(res))
    }

    @Test
    fun `latin only classified as latin`() {
        val font = sfntFont(
            cmapTable(fmt4(arrayOf(intArrayOf(0x0041, 0x005A))), 3, 1),
            nameTable("LatinOnly"),
        )
        val res = parser.parse(font)
        assertEquals(26, res.latinCoverage)
        assertEquals(FontClassifier.LANG_LATIN, FontClassifier.classify(res))
    }

    @Test
    fun `tiny coverage not usable, symbol or below threshold`() {
        // 只覆盖几个字符，达不到正文阈值
        val font = sfntFont(cmapTable(fmt4(arrayOf(intArrayOf(0x2000, 0x2003))), 3, 1), nameTable("Symbols"))
        val res = parser.parse(font)
        assertFalse(FontClassifier.isUsable(FontClassifier.classify(res)))
    }

    @Test
    fun `format12 unicode range parsed`() {
        val fmt12 = fmt12(
            listOf(
                Triple(0x4E00, 0x4E10, 0),   // CJK 部分
                Triple(0x0020, 0x007E, 0),   // 拉丁部分
            ),
        )
        val font = sfntFont(cmapTable(fmt12, 3, 10), nameTable("UnicodeFont"))
        val res = parser.parse(font)
        assertTrue(res.valid)
        assertEquals(17, res.cjkCoverage) // 0x4E00..0x4E10
        assertEquals(95, res.latinCoverage) // 0x0020..0x007E
        assertTrue(res.familyName == "UnicodeFont")
    }

    @Test
    fun `garbage is invalid`() {
        val res = parser.parse(byteArrayOf(1, 2, 3, 4, 5, 6))
        assertFalse(res.valid)
        assertEquals(FontClassifier.LANG_INVALID, FontClassifier.classify(res))
    }

    // ── 合成字体构造 ------------------------------------------------------------------

    /** sfnt 头 + 表目录：放一个 cmap 与一个 name 表。 */
    private fun sfntFont(cmap: ByteArray, name: ByteArray): ByteArray {
        val numTables = 2
        val dataStart = (12 + numTables * 16 + 3) and -4
        val cmapOff = dataStart
        val nameOff = cmapOff + cmap.size
        val total = nameOff + name.size
        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(0x00010000.toInt()) // sfnt version
        buf.putShort(numTables.toShort())
        buf.putShort(0) // searchRange
        buf.putShort(0) // entrySelector
        buf.putShort(0) // rangeShift
        putRec(buf, 12, "cmap", cmapOff, cmap.size)
        putRec(buf, 28, "name", nameOff, name.size)
        // putRec 用绝对索引写目录，游标仍在 12；必须在写入表内容前定位到对应偏移，
        // 否则相对 put(cmap)/put(name) 会覆盖目录记录。
        buf.position(cmapOff)
        buf.put(cmap)
        buf.position(nameOff)
        buf.put(name)
        return buf.array()
    }

    private fun putRec(buf: ByteBuffer, at: Int, tag: String, off: Int, len: Int) {
        val intTag = byteArrayOf(tag[0].code.toByte(), tag[1].code.toByte(), tag[2].code.toByte(), tag[3].code.toByte())
            .let { ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).int }
        buf.putInt(at, intTag)
        buf.putInt(at + 4, 0) // checksum（解析器不校验）
        buf.putInt(at + 8, off)
        buf.putInt(at + 12, len)
    }

    /** cmap 表：单个编码子表。 */
    private fun cmapTable(subtable: ByteArray, platform: Int, encoding: Int): ByteArray {
        val out = ByteBuffer.allocate(12 + subtable.size).order(ByteOrder.BIG_ENDIAN)
        out.putShort(0) // version
        out.putShort(1) // numTables
        out.putShort(platform.toShort())
        out.putShort(encoding.toShort())
        out.putInt(12) // 子表相对 cmap 偏移
        out.put(subtable)
        return out.array()
    }

    private fun fmt4(segs: Array<IntArray>): ByteArray {
        val sc = segs.size
        val len = 14 + 2 * sc + 2 + (2 * sc) * 3
        val b = ByteBuffer.allocate(len).order(ByteOrder.BIG_ENDIAN)
        b.putShort(4)
        b.putShort(len.toShort())
        b.putShort(0) // language
        b.putShort((2 * sc).toShort()) // segCountX2
        b.putShort(0); b.putShort(0); b.putShort(0)
        segs.forEach { b.putShort(it[1].toShort()) } // endCode
        b.putShort(0) // reservedPad
        segs.forEach { b.putShort(it[0].toShort()) } // startCode
        segs.forEach { b.putShort(0) } // idDelta
        segs.forEach { b.putShort(0) } // idRangeOffset
        return b.array()
    }

    private fun fmt12(groups: List<Triple<Int, Int, Int>>): ByteArray {
        val len = 16 + groups.size * 12
        val b = ByteBuffer.allocate(len).order(ByteOrder.BIG_ENDIAN)
        b.putShort(12)
        b.putShort(0) // reserved
        b.putInt(len)
        b.putInt(0) // language
        b.putInt(groups.size)
        groups.forEach { (s, e, _) ->
            b.putInt(s); b.putInt(e); b.putInt(0) // start/end/startGlyph
        }
        return b.array()
    }

    @Suppress("unused")
    private fun nameTable(name: String): ByteArray {
        val str = name.toByteArray(Charsets.UTF_16BE)
        val stringOffset = 6 + 12
        val out = ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(0); putShort(1); putShort(stringOffset.toShort())
        }.array())
        out.write(ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(3); putShort(1); putShort(0x0409); putShort(1) // nameID=1
            putShort(str.size.toShort()); putShort(0.toShort())
        }.array())
        out.write(str)
        return out.toByteArray()
    }
}