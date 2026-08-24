package com.orilum.data.font

import android.content.Context
import android.net.Uri
import com.orilum.util.FileLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 字体仓库：**主动导入 + 私有副本 + Room 持久化**。
 *
 * 与书店一致：用户通过「字体导入」选字体文件（SAF），本仓库把字节**拷贝进私有目录**
 * `filesDir/fonts/`，解析分类后写入 `font_faces` 表（以 `(familyName, subfamily)` 去重，
 * 同款同字重重导覆盖）。字体跨重启持久存在，删除源文件不影响（已有私有副本）；用户显式删除才会清掉。
 *
 * 设计取舍：
 *  - 每张导入的字体文件 = 一条 `FontFace`（`path` 指向私有副本，`source=imported`），
 *    字节经 `/fonts/{id}` 虚拟域 url() 加载（[fontBytes] 按 id 从私有路径直读）。
 *  - 同一家族的不同字重文件（如思源黑体 Regular / Bold）以不同 `id` 并存；渲染时
 *    reader 端按家族归档成多份 `@font-face`（同家族名 + 各自 font-weight/style 描述符）。
 *  - 私有文件名 = `家族-字重.ext`，避免同家族多字重互相覆盖。
 *  - 只保留「有效语言」字体（cjk/latin/generic），symbol/invalid 导入时自动拒绝。
 */
class FontRepository(
    private val context: Context,
    private val fontDao: FontDao,
) {
    private val parser = FontParser()
    private val tag = "Orilum.Font"

    /** 私有字体目录（不存在则创建）。 */
    private val fontsDir: File
        get() = File(context.filesDir, "fonts").apply { mkdirs() }

    /** 全部已导入字体（持久）。 */
    suspend fun list(): List<FontFace> = fontDao.all()

    /** 按 id 加载字体字节（key = `FontFace.id`）；文件被删/记录缺失返回 null。 */
    fun fontBytes(id: Long): ByteArray? = runCatching {
        val face = fontDao.byIdBlocking(id) ?: return null
        val f = face.path?.let { File(it) } ?: return null
        if (!f.exists()) {
            // 私有副本意外丢失：清理失效记录，避免残留占位
            fontDao.delete(face.id)
            FileLogger.w(tag, "font file missing, cleaned id=$id family=${face.familyName}")
            return null
        }
        f.readBytes()
    }.getOrNull()

    /** 导入一个字体文件（SAF uri）→ 拷贝私有副本 + 解析分类 + 入库。返回更新后的全量列表。 */
    suspend fun import(uri: Uri): List<FontFace> = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            FileLogger.w(tag, "import failed: cannot read $uri")
            return@withContext fontDao.all()
        }
        val res = runCatching { bytes.inputStream().use { parser.parse(it) } }.getOrNull()
        if (res == null || !res.valid) {
            FileLogger.w(tag, "import rejected: not a parseable font $uri")
            return@withContext fontDao.all()
        }
        val lang = FontClassifier.classify(res)
        if (!FontClassifier.isUsable(lang)) {
            FileLogger.w(tag, "import rejected: unusable lang=$lang $uri")
            return@withContext fontDao.all()
        }
        val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
            ?.takeIf { it in FONT_EXTS } ?: "ttf"
        val family = res.familyName?.takeIf { it.isNotBlank() } ?: "font"
        // 字重归一为空串（无字重名）或原值；私有文件名含 family-subfamily 避免同家族多字重互相覆盖。
        val sub = res.subfamily?.trim()?.takeIf { it.isNotBlank() } ?: ""
        val safe = if (sub.isEmpty()) family else "$family-$sub"
        val dest = File(fontsDir, "$safe.$ext")
        dest.writeBytes(bytes)
        fontDao.upsert(
            listOf(
                FontFace(
                    familyName = family,
                    displayName = dest.name.removeSuffix(".$ext"),
                    subfamily = sub,
                    source = FontFace.SOURCE_IMPORTED,
                    path = dest.absolutePath,
                    lang = lang,
                ),
            ),
        )
        FileLogger.i(tag, "imported family=$family subfamily=${if (sub.isEmpty()) "(none)" else sub} lang=$lang -> ${dest.name}")
        fontDao.all()
    }

    /** 删除一个已导入字体：清 DB 行 + 删私有文件副本。 */
    suspend fun delete(face: FontFace) = withContext(Dispatchers.IO) {
        runCatching { face.path?.let { File(it).delete() } }
        fontDao.delete(face.id)
        FileLogger.i(tag, "deleted font id=${face.id} family=${face.familyName}")
    }

    private companion object {
        val FONT_EXTS = setOf("ttf", "otf", "ttc", "otc")
    }
}