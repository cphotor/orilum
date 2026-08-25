package com.orilum.data.font

import android.content.Context
import android.net.Uri
import com.orilum.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 字体仓库：**全部存入私有目录 `filesDir/fonts/`**。
 *
 * 用户经 SAF 选择字体文件或 WIFI 上传后，本仓库把字节**写入私有 `filesDir/fonts/`**，
 * `FontFace.path` 存绝对路径，解析分类后写入 `font_faces` 表（以 `(familyName, subfamily)`
 * 去重，同款同字重重导覆盖）。字体零授权、卸载即清空；显式删除才清掉。
 *
 * 设计取舍：
 *  - 字体体积小，私有目录容量无虞；`FontFace.path` 记录每张字体绝对路径，来源明确无需目录设置。
 *  - 同一家族的不同字重文件（如思源黑体 Regular / Bold）以不同 `id` 并存；渲染时
 *    reader 端按家族归档成多份 `@font-face`（同家族名 + 各自 font-weight/style 描述符）。
 *  - 文件名 = `家族-字重.ext`，避免同家族多字重互相覆盖。
 *  - 字节经 `/fonts/{id}` 虚拟域 url() 加载（[fontBytes] 按 id 读取）。
 *  - 只保留「有效语言」字体（cjk/latin/generic），symbol/invalid 导入时自动拒绝。
 */
class FontRepository(
    private val context: Context,
    private val fontDao: FontDao,
) {
    private val parser = FontParser()
    private val tag = "Orilum.Font"

    /** 全部已导入字体（持久）。 */
    suspend fun list(): List<FontFace> = fontDao.all()

    /** 按 id 加载字体字节（key = `FontFace.id`）；文件被删/记录缺失返回 null。 */
    fun fontBytes(id: Long): ByteArray? = runCatching {
        val face = fontDao.byIdBlocking(id) ?: return null
        val uriStr = face.path ?: return null
        val bytes = readFileBytes(uriStr)
        if (bytes == null || bytes.isEmpty()) {
            // 字体副本意外丢失：清理失效记录，避免残留占位
            fontDao.delete(face.id)
            FileLogger.w(tag, "font file missing, cleaned id=$id family=${face.familyName}")
            return null
        }
        bytes
    }.getOrNull()

    /** 导入一个字体文件（SAF uri）→ 写落点副本 + 解析分类 + 入库。返回更新后的全量列表。 */
    suspend fun import(uri: Uri): List<FontFace> = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        importBytes(bytes, uri.lastPathSegment ?: "font")
    }

    /** 导入一个本地临时文件（WIFI 上传落地后的 cache 文件）→ 同 [import] 的落点副本 + 解析 + 入库。 */
    suspend fun importFile(file: java.io.File): List<FontFace> = withContext(Dispatchers.IO) {
        val bytes = runCatching { file.readBytes() }.getOrNull()
        importBytes(bytes, file.name)
    }

    /** 公共导入核心：读失败返回全量列表；字节为空/不可解析/语言不可用则拒绝。 */
    private suspend fun importBytes(bytes: ByteArray?, name: String): List<FontFace> {
        if (bytes == null || bytes.isEmpty()) {
            FileLogger.w(tag, "import failed: cannot read $name")
            return fontDao.all()
        }
        val res = runCatching { bytes.inputStream().use { parser.parse(it) } }.getOrNull()
        if (res == null || !res.valid) {
            FileLogger.w(tag, "import rejected: not a parseable font $name")
            return fontDao.all()
        }
        val lang = FontClassifier.classify(res)
        if (!FontClassifier.isUsable(lang)) {
            FileLogger.w(tag, "import rejected: unusable lang=$lang $name")
            return fontDao.all()
        }
        val ext = name.substringAfterLast('.', "")
            .lowercase()
            .takeIf { it in FONT_EXTS } ?: "ttf"
        val family = res.familyName?.takeIf { it.isNotBlank() } ?: "font"
        // 字重归一为空串（无字重名）或原值；文件名叫 family-subfamily 避免同家族多字重互相覆盖。
        val sub = res.subfamily?.trim()?.takeIf { it.isNotBlank() } ?: ""
        val safe = if (sub.isEmpty()) family else "$family-$sub"
        val dispName = "$safe.$ext"
        val path = resolveFontTarget(dispName, bytes)
            ?: run { FileLogger.w(tag, "import rejected: cannot write fonts $name"); return fontDao.all() }
        fontDao.upsert(
            listOf(
                FontFace(
                    familyName = family,
                    displayName = safe,
                    subfamily = sub,
                    source = FontFace.SOURCE_IMPORTED,
                    path = path,
                    lang = lang,
                ),
            ),
        )
        FileLogger.i(tag, "imported family=$family subfamily=${if (sub.isEmpty()) "(none)" else sub} lang=$lang -> $dispName")
        return fontDao.all()
    }

    /**
     * 写入私有 `filesDir/fonts/` 并返回绝对路径；写入失败返回 null。
     */
    private fun resolveFontTarget(dispName: String, bytes: ByteArray): String? {
        return runCatching {
            val dir = java.io.File(context.filesDir, "fonts").apply { mkdirs() }
            val f = java.io.File(dir, dispName)
            f.writeBytes(bytes)
            f.absolutePath
        }.getOrNull()
    }

    /** 按绝对路径读取字节；文件缺失返回 null。 */
    private fun readFileBytes(path: String): ByteArray? =
        runCatching { java.io.File(path).readBytes() }.getOrNull()

    /** 删除私有字体副本文件（由 `FontFace.path` 记录的绝对路径）。 */
    private fun deleteFileBytes(path: String?) {
        val s = path ?: return
        runCatching { java.io.File(s).delete() }
    }

    /** 删除一个已导入字体：清 DB 行 + 删落点文件副本。 */
    suspend fun delete(face: FontFace) = withContext(Dispatchers.IO) {
        deleteFileBytes(face.path)
        fontDao.delete(face.id)
        FileLogger.i(tag, "deleted font id=${face.id} family=${face.familyName}")
    }

    /** 删除一个家族的全部字重（显示层按家族合并，删除以家族为单位）：清该家族所有记录 + 删各自副本。 */
    suspend fun deleteFamily(familyName: String) = withContext(Dispatchers.IO) {
        val faces = fontDao.all().filter { it.familyName == familyName }
        faces.forEach { deleteFileBytes(it.path) }
        fontDao.deleteByFamily(familyName)
        FileLogger.i(tag, "deleted family=$familyName (${faces.size} weight(s))")
    }

    private companion object {
        val FONT_EXTS = setOf("ttf", "otf", "ttc", "otc")
    }
}