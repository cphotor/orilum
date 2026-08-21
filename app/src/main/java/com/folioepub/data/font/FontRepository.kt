package com.folioepub.data.font

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.folioepub.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 字体仓库：**直接引用用户指定的字体目录**，不做拷贝私有化、不做入库。
 *
 * 设计取舍（对齐 moon+reader/多看）：用户 pick 一个字体目录后：
 *  - 通过 [com.folioepub.ui.reader.ReaderActivity] 的 `takePersistableUriPermission` 把该目录树的
 *    读权限**持久化**，因此跨重启仍可读源文件（这正是 SAF 本应支持、早期因未持久化而「重启失效」的根因，现修复）。
 *  - 每次 [list] 时实时扫描目录内的 TTF/OTF/TTC 文件并解析分类，**一是**让「删除源文件后字体消失」
 *    自然成立；**二则**无需维护 DB 增量迁移。
 *  - 每个文件为一个独立候选（**不按字重合并**，一字重一文件，与 moon+reader 一致）。
 *  - 字节经 `/fonts/{key}` 虚拟域 url() 加载，[fontBytes] 从持久化 uri 直接 `openInputStream`。
 *
 * 通过 [Document] 缓存扫描结果，避免重复解析；`setDirectory` 时重新扫描。
 *
 * 字体候选只含「有效语言」字体（cjk/latin/generic），symbol/invalid 自动过滤。
 */
class FontRepository(
    private val context: Context,
    parser: FontParser = FontParser(),
) {
    private val parser = FontParser()
    private val tag = "FolioEpub.Font"

    /** 一个字体候选：key 为稳定标识（目录索引 + 文件名），供 `/fonts/{key}` 加载。 */
    data class FontEntry(
        val key: String,
        val name: String,
        val lang: String,
        val uri: Uri,
    )

    /** 当前生效的字体目录树 uri（已持久化权限）；null 表示尚未选择。 */
    @Volatile
    var directoryUri: Uri? = null
        private set

    /** 当前生效目录下的字体候选列表（按扫描顺序）。 */
    @Volatile
    var entries: List<FontEntry> = emptyList()
        private set

    /** 设置字体目录并持久化权限，随后立即扫描出候选列表。null 表示清除。 */
    suspend fun setDirectory(uri: Uri?): List<FontEntry> = withContext(Dispatchers.IO) {
        directoryUri = uri
        if (uri == null) {
            entries = emptyList()
            return@withContext entries
        }
        // 持久化读权限：取目录树 grant，跨重启有效（SAF 标准做法）
        runCatching {
            context.contentResolver
                .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure {
            FileLogger.w(tag, "takePersistableUriPermission 失败 $uri : $it")
        }
        entries = scan(uri)
        FileLogger.i(tag, "setDirectory -> ${entries.size} fonts")
        entries
    }

    /** 返回当前候选（不重复扫描；扫描在 [setDirectory] 已做）。 */
    suspend fun list(): List<FontEntry> = entries

    /** 按 key 读取字体字节；找不到或源文件已被删除则返回 null（「删源即消失」）。 */
    fun fontBytes(key: String): ByteArray? = runCatching {
        if (key.isBlank()) return null
        val entry = entries.firstOrNull { it.key == key } ?: return null
        context.contentResolver.openInputStream(entry.uri)?.use { it.readBytes() }
    }.getOrNull()

    /** 扫描目录内所有字体文件并解析分类。 */
    private fun scan(rootUri: Uri): List<FontEntry> {
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return emptyList()
        val files = mutableListOf<DocumentFile>()
        collectFontFiles(root, files)
        val out = mutableListOf<FontEntry>()
        for (i in files.indices) {
            val doc = files[i]
            val fileName = doc.name ?: continue
            val ext = fileName.substringAfterLast('.', "").lowercase()
            if (ext !in FONT_EXTS) continue
            val res = runCatching {
                context.contentResolver.openInputStream(doc.uri)?.let { parser.parse(it) }
            }.getOrNull() ?: continue
            if (!res.valid) continue
            val lang = FontClassifier.classify(res)
            if (!FontClassifier.isUsable(lang)) continue
            // 展示名用文件名（去扩展名）：第三方面体常以中文命名，比 name 表英文 Family 更贴近用户认知
            out += FontEntry(
                key = "${rootUri.lastPathSegment}_${i}_$fileName",
                name = fileName.removeSuffix(".$ext"),
                lang = lang,
                uri = doc.uri,
            )
        }
        return out
    }

    private fun collectFontFiles(doc: DocumentFile, out: MutableList<DocumentFile>) {
        if (!doc.isDirectory) { out.add(doc); return }
        doc.listFiles().forEach { child ->
            when {
                child.isDirectory -> collectFontFiles(child, out)
                child.isFile -> out.add(child)
            }
        }
    }

    private companion object {
        val FONT_EXTS = setOf("ttf", "otf", "ttc", "otc")
    }
}