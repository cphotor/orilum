package com.folioepub.data.epub

/**
 * 内存版 [EpubResourceReader]，用 map 模拟 zip 条目，供 JVM 单元测试。
 */
class FakeEpubResourceReader(
    files: Map<String, ByteArray>,
) : EpubResourceReader {
    private val normalized: Map<String, ByteArray> =
        files.entries.associate { normalizePath(it.key) to it.value }

    override fun entries(): Sequence<String> = normalized.keys.asSequence()

    override fun readText(path: String): String? =
        readBytes(path)?.toString(Charsets.UTF_8)

    override fun readBytes(path: String): ByteArray? =
        normalized[normalizePath(path)]
}