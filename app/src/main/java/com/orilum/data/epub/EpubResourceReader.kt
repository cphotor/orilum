package com.orilum.data.epub

/**
 * 抽象 EPUB 资源读取器，隐藏底层存取方式（zip / 目录 / 网络等）。
 *
 * 解析器只依赖本接口，从而成为纯逻辑、可在 JVM 单元测试。
 * 所有路径统一经 [normalizePath] 转小写后再索引/查找，以容错“脏书”中不一致的大小写。
 */
interface EpubResourceReader : AutoCloseable {
    /** 全部条目路径（已 normalize）。 */
    fun entries(): Sequence<String>

    /** 读取文本条目；不存在返回 null。 */
    fun readText(path: String): String?

    /** 读取二进制条目（图片/字体）；不存在返回 null。 */
    fun readBytes(path: String): ByteArray?

    /** 统一路径规范化：反斜杠转正斜杠、去首尾斜杠、转小写。 */
    fun normalizePath(p: String): String = p.replace('\\', '/').trim('/').lowercase()

    override fun close() {}
}

/**
 * 基于 zip 文件（标准 `java.util.zip`）的 [EpubResourceReader] 实现。
 *
 * EPUB 本质是 zip 包；本实现把条目路径统一小写后建索引，再用小写键查找，
 * 从而兼容规格路径（`META-INF/container.xml`）与“原文件大小写漂移”的常见脏书。
 * 注意：不能直接用 `ZipFile.getEntry`（它大小写敏感，直接拿小写名查会漏掉大写条目）。
 */
class ZipEpubResourceReader(path: String) : EpubResourceReader {
    private val zip = java.util.zip.ZipFile(path)

    /** 规范化(小写)路径 → 真实条目。大小写不敏感查找的关键。 */
    private val normalized: Map<String, java.util.zip.ZipEntry> by lazy {
        val m = HashMap<String, java.util.zip.ZipEntry>()
        val e = zip.entries()
        while (e.hasMoreElements()) {
            val entry = e.nextElement()
            m[normalizePath(entry.name)] = entry
        }
        m
    }

    override fun entries(): Sequence<String> = normalized.keys.asSequence()

    override fun readText(path: String): String? =
        readBytes(path)?.toString(Charsets.UTF_8)

    override fun readBytes(path: String): ByteArray? {
        val entry = normalized[normalizePath(path)] ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    override fun close() {
        zip.close()
    }
}