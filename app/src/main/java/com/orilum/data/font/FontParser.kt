package com.orilum.data.font

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 字体二进制解析（纯 Kotlin，无第三方依赖，可单元测试）。
 *
 * 解析 TTF / OTF / TTC 的 sfnt 表目录，提取：
 *  - **家族名**（'name' 表 nameID=1），供 WebView CSS `local()` 引用系统字体；
 *  - **cmap 覆盖权重**：统计 CJK(U+4E00–9FFF) 与 拉丁(U+0000–02FF) 两个区段被
 *    映射到的码点个数，供 [FontClassifier] 判定中文/英文/通用/符号/无效。
 *
 * 只读取字体字节的只读头（表目录 + cmap + name 表），不解析整体字形，轻量可靠。
 * 覆盖统计按 cmap 段（format 4）或组（format 12）做区间交叠计数，非精确字形数，
 * 但对「是否有足够中/英文字形」的分类判定足够。
 */
class FontParser {

    /** 解析结果；任何一步失败都会以 [valid]==false 返回，不影响调用方。 */
    data class Result(
        /** 家族名(优先 nameID=16 Typographic Family，否则 nameID=1)——同一家族不同字重文件取值一致，作分组键。 */
        val familyName: String?,
        /** 字重/样式名(nameID=2 Subfamily，如 Regular/Bold/Italic)，供同家族内区分。可为 null。 */
        val subfamily: String?,
        /** CJK 统一表意文字区(U+4E00–9FFF)被 cmap 映射的码点数。 */
        val cjkCoverage: Int,
        /** 拉丁/基本拉丁区(U+0000–02FF)被 cmap 映射的码点数。 */
        val latinCoverage: Int,
        val valid: Boolean = true,
    ) {
        companion object {
            val INVALID = Result(familyName = null, subfamily = null, cjkCoverage = 0, latinCoverage = 0, valid = false)
        }
    }

    // 正文分类悬取的两个区段（与 CSS unicode-range / 语义对应）
    private val cjkLo = 0x4E00
    private val cjkHi = 0x9FFF
    private val latinLo = 0x0000
    private val latinHi = 0x02FF

    fun parse(file: File): Result = runCatching { file.readBytes().let { parse(it) } }
        .getOrElse { Result.INVALID }

    fun parse(stream: InputStream): Result = runCatching {
        val bytes = stream.use { it.readBytes() }
        parse(bytes)
    }.getOrElse { Result.INVALID }

    fun parse(bytes: ByteArray): Result = runCatching { parseUnsafe(bytes) }
        .getOrElse { Result.INVALID }

    private fun parseUnsafe(bytes: ByteArray): Result {
        require(bytes.size >= 4)
        var base = 0
        // TTC 集合：表目录偏移在 12 + 4*index；解析首个字体即可（同一集合内参靠前）
        if (isTag(bytes, 0, "ttcf")) {
            val buf = bb(bytes)
            val numFonts = buf.getInt(8).toInt() and 0xffff
            require(numFonts >= 1) { "TTC 无字体" }
            base = buf.getInt(12).toInt()
        }
        // 读表目录，定位 cmap 与 name
        val numTables = u16(bytes, base + 4)
        val cmapRec = findTable(bytes, base, numTables, "cmap")
        val nameRec = findTable(bytes, base, numTables, "name")
        if (cmapRec == null) return Result.INVALID // 无 cmap 连字符覆盖都无法判定

        val familyName = nameRec?.let { readName(bytes, it, ID_FAMILY) }
        val subfamily = nameRec?.let { readName(bytes, it, ID_SUBFAMILY) }
        val cjk = CjkCollector("cjk", cjkLo, cjkHi)
        val latin = CjkCollector("latin", latinLo, latinHi)
        var anySubtable = false
        // 遍历 cmap 子表，取能解析的 format4/12 累计覆盖（同码点不去重，区间加法近似）
        val cmapNumTables = u16(bytes, cmapRec.offset + 2)
        for (i in 0 until cmapNumTables) {
            val recOff = cmapRec.offset + 4 + i * 8
            val subOff = i32(bytes, recOff + 4)
            if (subOff < 0) continue
            val abs = cmapRec.offset + subOff
            if (abs + 2 > bytes.size) continue
            val format = u16(bytes, abs)
            when (format) {
                4 -> { countFormat4(bytes, abs, cjk, latin); anySubtable = true }
                12 -> { countFormat12(bytes, abs, cjk, latin); anySubtable = true }
                else -> Unit // 其余格式不影响分类
            }
        }
        if (!anySubtable) return Result.INVALID
        return Result(
            familyName = familyName,
            subfamily = subfamily,
            cjkCoverage = cjk.total,
            latinCoverage = latin.total,
            valid = true,
        )
    }

    private fun countFormat4(bytes: ByteArray, off: Int, cjk: CjkCollector, latin: CjkCollector) {
        val buf = bb(bytes)
        val segCount = (buf.getShort(off + 6).toInt() and 0xffff) / 2
        if (segCount <= 0) return
        val endBase = off + 14
        val startBase = endBase + segCount * 2 + 2
        for (seg in 0 until segCount) {
            val start = buf.getShort(startBase + seg * 2).toInt() and 0xffff
            val end = buf.getShort(endBase + seg * 2).toInt() and 0xffff
            if (start > end) continue
            cjk.addIntersect(start, end)
            latin.addIntersect(start, end)
        }
    }

    private fun countFormat12(bytes: ByteArray, off: Int, cjk: CjkCollector, latin: CjkCollector) {
        val buf = bb(bytes)
        val nGroups = buf.getInt(off + 12).toLong()
        if (nGroups < 0 || nGroups > 0x40000) return
        val groupsBase = off + 16
        for (g in 0 until nGroups.toInt()) {
            val recBase = groupsBase + g * 12
            val start = buf.getInt(recBase).toLong() and 0xffffffffL
            val end = buf.getInt(recBase + 4).toLong() and 0xffffffffL
            if (end < start) continue
            addCoverage(start, end, latinLo.toLong(), latinHi.toLong(), latin)
            addCoverage(start, end, cjkLo.toLong(), cjkHi.toLong(), cjk)
        }
    }

    private inline fun addCoverage(start: Long, end: Long, lo: Long, hi: Long, c: CjkCollector) {
        if (end < lo || start > hi) return
        val s = maxOf(start, lo)
        val e = minOf(end, hi)
        c.addRaw((e - s + 1).toInt())
    }

    // 指定源后的旧路径，改用上面通用逻辑，避免 format4/12 差异
    companion object {
        // OpenType 'name' 表标准 nameID
        private const val ID_FAMILY = 1
        private const val ID_SUBFAMILY = 2
        private const val ID_TYPOGRAPHIC_FAMILY = 16
        private const val ID_TYPOGRAPHIC_SUBFAMILY = 17

        private class CjkCollector(private val name: String, private val lo: Int, private val hi: Int) {
            var total = 0
            fun addIntersect(s: Int, e: Int) {
                if (e < lo || s > hi) return
                addRaw(minOf(e, hi) - maxOf(s, lo) + 1)
            }
            fun addRaw(n: Int) { total += n }
        }

        private fun isTag(bytes: ByteArray, off: Int, tag: String): Boolean {
            if (off + 4 > bytes.size) return false
            for (i in 0 until 4) if (bytes[off + i] != tag[i].code.toByte()) return false
            return true
        }

        private fun bb(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        private fun u16(bytes: ByteArray, off: Int): Int = bb(bytes).getShort(off).toInt() and 0xffff
        private fun i16(bytes: ByteArray, off: Int): Int = bb(bytes).getShort(off).toInt()
        private fun i32(bytes: ByteArray, off: Int): Int = bb(bytes).getInt(off)

        /** 在 sfnt 表目录里找指定 tag 的记录；返回记录名外偏移（记录内 offset/length 相对文件头）。 */
        private fun findTable(bytes: ByteArray, base: Int, numTables: Int, tag: String): TableInfo? {
            for (i in 0 until numTables) {
                val rec = base + 12 + i * 16
                if (rec + 16 > bytes.size) return null
                if (isTag(bytes, rec, tag)) {
                    val offset = i32(bytes, rec + 8)
                    val length = i32(bytes, rec + 12)
                    if (offset < 0 || length < 0) return null
                    return TableInfo(offset, length)
                }
            }
            return null
        }

        /**
         * 读 'name' 表指定 nameID 的名字。family 优先 nameID=16(Typographic，不含字重、同家族一致)，
         * 缺失才回退 nameID=1；subfamily 优先 nameID=17(Typographic Subfamily，含真实字重)，
         * 缺失才回退 nameID=2 —— 因为思源这类 CJK 字体在 nameID=2 里除 Regular/Bold 外常退化成
         * "Regular"，17 才存 Light/Heavy 等真实字重。在全部候选记录里**优先取含中文字形的名字**，
         * 否则退回可读的拉丁名——解决同一字体的中文/英文名并存时（如「霞鹜文楷 / LXGW WenKai」）
         * 误取英文的问题。
         */
        private fun readName(bytes: ByteArray, info: TableInfo, nameId: Int): String? {
            val preferred = when (nameId) {
                ID_FAMILY -> listOf(ID_TYPOGRAPHIC_FAMILY, ID_FAMILY)
                ID_SUBFAMILY -> listOf(ID_TYPOGRAPHIC_SUBFAMILY, ID_SUBFAMILY)
                else -> listOf(nameId)
            }
            for (pref in preferred) {
                collectNames(bytes, info, pref).let { c ->
                    if (c.isNotEmpty()) {
                        return c.maxWithOrNull(compareBy({ it.second }, { -asciiRatio(it.first) }))?.first
                    }
                }
            }
            return null
        }

        /** 收集某 nameID 的全部可解码候选，记录其「中文字数 / ASCII 比例」。 */
        private fun collectNames(bytes: ByteArray, info: TableInfo, nameId: Int): List<Pair<String, Int>> {
            val off = info.offset
            val count = u16(bytes, off + 2)
            val stringOff = u16(bytes, off + 4)
            if (count == 0 || count > 200) return emptyList()
            val out = mutableListOf<Pair<String, Int>>()
            for (i in 0 until count) {
                val rec = off + 6 + i * 12
                if (rec + 12 > bytes.size) continue
                val pf = u16(bytes, rec)
                val enc = u16(bytes, rec + 2)
                val id = u16(bytes, rec + 6)
                val len = u16(bytes, rec + 8)
                val strOff = u16(bytes, rec + 10)
                if (id != nameId || len == 0) continue
                if (pf == 3 && enc != 1 && enc != 10) continue   // Windows: 仅 Unicode
                val start = off + stringOff + strOff
                val end = start + len
                if (start < 0 || end > bytes.size) continue
                // 解码：Windows/Apple 用「中文优先」的 UTF-16 探测，MacRoman 单字节 Latin-1 近似
                val name = when {
                    pf == 1 -> decodeMacRoman(bytes, start, end)
                    else -> decodeUtf16PreferCjk(bytes, start, end)
                }
                if (name != null && name.isNotBlank()) {
                    out.add(name to cjkCount(name))
                }
            }
            return out
        }

        /**
         * UTF-16 家族名解码，字节序自适应，且**优先得到中文记录**（不因无 ASCII 而丢弃）。
         *
         * 字节序判据分两种情境：
         *  - **英文名**：正确字节序解出大量可打印 ASCII（比例→1），错误字节序倒序成 CJK/乱码
         *    （ASCII 比例→0）。故若某一侧的 ASCII 可读比例很高，直接取它即可。
         *  - **中文名**：正确字节序解出 CJK（ASCII 比例低），错误字节序倒序只有约一半落 CJK、另一半
         *    成代理区/非法码点。两侧 ASCII 都低，此时取 CJK 码元占比更高者（信噪比更高）。
         */
        private fun decodeUtf16PreferCjk(bytes: ByteArray, start: Int, end: Int): String? {
            val len = end - start
            if (len < 2) return null
            val bs = bytes.copyOfRange(start, end)
            val be = safeDecode(bs, Charsets.UTF_16BE) ?: return null
            val le = safeDecode(bs, Charsets.UTF_16LE)
            // 任一侧以高 ASCII 比例呈现（几乎必为英文家族名）→ 直接选它
            val asciiBe = asciiRatio(be)
            val asciiLe = le?.let { asciiRatio(it) } ?: 0f
            if (asciiBe >= 0.7f || asciiLe >= 0.7f) {
                return if (asciiLe > asciiBe) (le ?: be) else be
            }
            // 两侧 ASCII 都低（中文名）：取 CJK 码元占比更高的一侧
            return if (cjkRatio(le ?: be) > cjkRatio(be)) (le ?: be) else be
        }

        private fun safeDecode(bs: ByteArray, cs: java.nio.charset.Charset): String? =
            runCatching { String(bs, cs) }.getOrNull()?.replace("\u0000", "")

        /** 字符串里 CJK 统一表意文字(U+4E00–9FFF)的个数；用于判断是否为中文家族名。 */
        private fun cjkCount(s: String): Int {
            var n = 0
            for (c in s) if (c.code in 0x4E00..0x9FFF) n++
            return n
        }

        /** CJK 码元占比；中文名正确字节序下接近 1，错误字节序只有约一半落 CJK。 */
        private fun cjkRatio(s: String): Float {
            if (s.isEmpty()) return 0f
            return cjkCount(s).toFloat() / s.length
        }

        /** 可打印 ASCII/常用拉丁字符占有效码点的比例（越高越像是正确解码的拉丁名）。 */
        private fun asciiRatio(s: String): Float {
            if (s.isEmpty()) return 0f
            var readable = 0
            for (c in s) {
                val v = c.code
                if (v in 0x20..0x7e || v in 0x00a0..0x00ff) readable++
            }
            return readable.toFloat() / s.length
        }

        private fun decodeMacRoman(bytes: ByteArray, start: Int, end: Int): String? {
            if (end <= start) return null
            val out = StringBuilder(end - start)
            for (i in start until end) {
                val b = bytes[i].toInt() and 0xff
                // 家族名通常为纯 ASCII；高位字节(MacRoman 扩展)按不可打印符号丢弃
                if (b in 0x20..0x7e) out.append(b.toChar()) else out.append(' ')
            }
            return out.toString().replace("\\s+".toRegex(), " ").trim().ifEmpty { null }
        }

        private data class TableInfo(val offset: Int, val length: Int)
    }
}