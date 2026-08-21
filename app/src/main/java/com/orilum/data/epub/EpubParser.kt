package com.orilum.data.epub

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 自建 EPUB 解析器（纯逻辑、可 JVM 单测，不依赖 Readium/Android）。
 *
 * 流程：
 * 1. 读 `META-INF/container.xml` → 定位 OPF 完整路径；
 * 2. 读 OPF → metadata / manifest / spine；
 * 3. 读目录（EPUB3 nav 优先，EPUB2 NCX 兜底）→ 映射回 spine 索引。
 *
 * 统一原则：所有 `href` 保留 OPF 中原样路径（大小写/前缀不变），
 * 因为 [EpubResourceReader] 用原样路径精确读正文；仅做“相对 OPF 目录”的拼接。
 * 目录/章节的匹配比较则用规范化（小写）路径，以容错脏书的大小写漂移。
 */
class EpubParser {

    private val dbFactory by lazy {
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // 尽力开启安全特性；不支持的实现（如 Android 内置解析器）直接忽略，
            // 避免 setFeature 抛异常而无法解析任何书。
            trySetFeature("http://xml.org/sax/features/external-general-entities", false)
            trySetFeature("http://xml.org/sax/features/external-parameter-entities", false)
            trySetFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true)
        }
    }

    /** 设置 XML 安全特性；实现不支持时静默忽略。 */
    private fun DocumentBuilderFactory.trySetFeature(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (_: Exception) {
            // 该特性不受当前实现支持，跳过
        }
    }

    fun parse(reader: EpubResourceReader): EpubBook {
        val opfPath = resolveOpfPath(reader)
            ?: throw EpubFormatException("未找到 META-INF/container.xml 或其中不含 rootfile")
        val opfXml = reader.readText(opfPath)
            ?: throw EpubFormatException("读取 OPF 失败：$opfPath")
        val opfDir = opfPath.substringBeforeLast('/', "")

        val opf = parseXml(opfXml).documentElement
        val (title, author) = parseMetadata(opf)
        val manifest = parseManifest(opf)
        val spineIds = parseSpine(opf)

        if (spineIds.isEmpty()) throw EpubFormatException("OPF 中 spine 为空")

        val spine = spineIds.mapIndexed { index, id ->
            val item = manifest[id] ?: throw EpubFormatException("spine 引用不存在的 manifest id：$id")
            val (href, fragment) = splitFragment(item.href)
            SpineItem(index, joinPath(opfDir, href), fragment, item.mediaType)
        }
        val toc = resolveToc(reader, opfDir, spine, manifest.values)

        return EpubBook(title ?: "未知书名", author, spine, toc)
    }

    // ---- container.xml / OPF 定位 ----

    private fun resolveOpfPath(reader: EpubResourceReader): String? {
        val xml = reader.readText("META-INF/container.xml") ?: return null
        val doc = parseXml(xml)
        val rootfiles = doc.getElementsByTagNameNS("*", "rootfile")
        for (i in 0 until rootfiles.length) {
            val el = rootfiles.item(i) as? Element ?: continue
            if (el.getAttribute("media-type") == "application/oebps-package+xml") {
                return el.getAttribute("full-path").takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    // ---- XML 解析 ----

    private fun parseXml(xml: String): Document {
        // 处理 BOM 与散落空白：保证 `<?xml` 声明位于字符串首位
        val cleaned = xml.trimStart('\uFEFF', ' ', '\t', '\n', '\r')
        val builder = dbFactory.newDocumentBuilder()
        // 拒绝一切外部实体/DTD 拉取：既防 XXE，也避免按 SYSTEM id 发起网络请求
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        return builder.parse(InputSource(StringReader(cleaned)))
    }

    // ---- OPF：metadata / manifest / spine ----

    private fun parseMetadata(opf: Element): Pair<String?, String?> {
        val metadata = elementChildren(opf, "metadata").firstOrNull() ?: return null to null
        val title = firstDescendant(metadata, "title")?.textContent?.trim()?.takeIf { it.isNotEmpty() }
        val author = firstDescendant(metadata, "creator")?.textContent?.trim()?.takeIf { it.isNotEmpty() }
        return title to author
    }

    private fun parseManifest(opf: Element): Map<String, ManifestItem> {
        val manifest = elementChildren(opf, "manifest").firstOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, ManifestItem>()
        for (el in elementChildren(manifest, "item")) {
            val id = el.getAttribute("id")
            if (id.isNotEmpty()) {
                out[id] = ManifestItem(
                    href = el.getAttribute("href"),
                    mediaType = el.getAttribute("media-type").takeIf { it.isNotEmpty() },
                    properties = el.getAttribute("properties").split(' ').filter { it.isNotEmpty() },
                )
            }
        }
        return out
    }

    private fun parseSpine(opf: Element): List<String> {
        val spine = elementChildren(opf, "spine").firstOrNull() ?: return emptyList()
        return elementChildren(spine, "itemref").mapNotNull { itemref ->
            itemref.getAttribute("idref").takeIf { it.isNotEmpty() }
        }
    }

    // ---- 目录：EPUB3 nav 优先，EPUB2 NCX 兜底 ----

    private fun resolveToc(
        reader: EpubResourceReader,
        opfDir: String,
        spine: List<SpineItem>,
        manifestItems: Collection<ManifestItem>,
    ): List<TocItem> {
        val indexByHref = indexSpineByNormalizedHref(spine)

        // 1) EPUB3 nav：manifest 中 properties 含 "nav"
        val navItem = manifestItems.firstOrNull { it.properties.contains("nav") }
        if (navItem != null) {
            val href = joinPath(opfDir, navItem.href)
            val xhtml = reader.readText(href)?.let { runCatching { parseXml(it) }.getOrNull() }
            if (xhtml != null) {
                // nav 通常在 body 内，递归定位首个 <nav>；找不到则退回用文档根
                val nav = firstDescendant(xhtml.documentElement, "nav") ?: xhtml.documentElement
                val toc = parseNavOl(nav, opfDir, indexByHref)
                if (toc.isNotEmpty()) return toc
            }
        }

        // 2) EPUB2 NCX：media-type 为 application/x-dtbncx+xml
        val ncxItem = manifestItems.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        if (ncxItem != null) {
            val href = joinPath(opfDir, ncxItem.href)
            val ncxXml = reader.readText(href)?.let { runCatching { parseXml(it) }.getOrNull() }
            if (ncxXml != null) {
                val toc = parseNcx(ncxXml.documentElement, opfDir, indexByHref)
                if (toc.isNotEmpty()) return toc
            }
        }

        return emptyList()
    }

    /** 解析 EPUB3 `<nav epub:type="toc">` 内的 `<ol>` 树。 */
    private fun parseNavOl(
        scope: Element,
        opfDir: String,
        indexByHref: Map<String, Int>,
    ): List<TocItem> {
        val ol = elementChildren(scope, "ol").firstOrNull() ?: return emptyList()
        return elementChildren(ol, "li").mapNotNull { li ->
            val a = elementChildren(li, "a").firstOrNull() ?: return@mapNotNull null
            val label = a.textContent.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val (path, fragment) = splitFragment(a.getAttribute("href"))
            val tocItem = TocItem(
                label = label,
                index = indexByHref[normalizePath(joinPath(opfDir, path))],
                fragment = fragment, // anchor 通常是 #sec 而非 #p= 标签
                children = parseNavOl(li, opfDir, indexByHref),
            )
            tocItem
        }
    }

    /** 解析 EPUB2 NCX `<navMap>` 树。 */
    private fun parseNcx(
        root: Element,
        opfDir: String,
        indexByHref: Map<String, Int>,
    ): List<TocItem> {
        val navMap = elementChildren(root, "navMap").firstOrNull() ?: return emptyList()
        return parseNavPoints(navMap, opfDir, indexByHref)
    }

    private fun parseNavPoints(
        scope: Element,
        opfDir: String,
        indexByHref: Map<String, Int>,
    ): List<TocItem> {
        return elementChildren(scope, "navPoint").mapNotNull { point ->
            val label = firstDescendant(point, "text")?.textContent?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val src = firstDescendant(point, "content")?.getAttribute("src") ?: return@mapNotNull null
            val (path, fragment) = splitFragment(src)
            TocItem(
                label = label,
                index = indexByHref[normalizePath(joinPath(opfDir, path))],
                fragment = fragment,
                children = parseNavPoints(point, opfDir, indexByHref),
            )
        }
    }

    // ---- 工具 ----

    /** 建 normalized(小写, 去 fragment) spine href → 章节 index 映射。 */
    private fun indexSpineByNormalizedHref(spine: List<SpineItem>): Map<String, Int> {
        val map = HashMap<String, Int>()
        for (item in spine) {
            // item.href 已含 OPF 目录前缀，直接规范化即可
            val (path, _) = splitFragment(item.href)
            map[normalizePath(path)] = item.index
        }
        return map
    }

    /** 拆分 `a#b` → (a, b)，无 `#` 时 fragment 为 null。 */
    private fun splitFragment(href: String): Pair<String, String?> {
        val idx = href.indexOf('#')
        return if (idx >= 0) href.substring(0, idx) to href.substring(idx + 1).ifEmpty { null }
        else href to null
    }

    /** 相对路径拼接：`dir + "/" + href`，dir 为空时直接返回 href。 */
    private fun joinPath(dir: String, href: String): String =
        if (dir.isEmpty() || href.startsWith("/")) href else "$dir/$href"

    private fun normalizePath(p: String): String = p.replace('\\', '/').trim('/').lowercase()

    /** 返回 scope 的直接子元素中 localName 匹配 tag 的。 */
    private fun elementChildren(el: Element, tag: String): List<Element> {
        val out = ArrayList<Element>()
        var child = el.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE && child.nodeName.substringAfter(':') == tag) {
                out.add(child as Element)
            }
            child = child.nextSibling
        }
        return out
    }

    /** 返回 scope 下第一个 localName 匹配 tag 的子孙元素。 */
    private fun firstDescendant(el: Element, tag: String): Element? {
        var current: Node? = el.firstChild
        while (current != null) {
            if (current.nodeType == Node.ELEMENT_NODE) {
                val e = current as Element
                if (e.nodeName.substringAfter(':') == tag) return e
                firstDescendant(e, tag)?.let { return it }
            }
            current = current.nextSibling
        }
        return null
    }

    private data class ManifestItem(
        val href: String,
        val mediaType: String?,
        val properties: List<String> = emptyList(),
    )
}