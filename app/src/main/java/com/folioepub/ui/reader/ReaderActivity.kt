package com.folioepub.ui.reader

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import com.folioepub.data.book.AppDatabase
import com.folioepub.data.book.BookReadingState
import com.folioepub.data.book.BookRepository
import com.folioepub.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection

/** JS relocate 回调：携带 foliate `lastLocation` 的 JSON 序列化。 */
fun interface LocatorCallback {
    fun onLocator(locatorJson: String)
}

/**
 * 阅读窗口：薄 Kotlin 壳 + 单个 WebView + foliate-js。
 *
 * 结构：
 *  - 经 [WebViewAssetLoader] 用 `https://appassets.androidplatform.net/assets/...`
 *    服务 assets 中的 foliate-js 与 reader.html，满足 ES Module 动态导入与 fetch。
 *  - reader.html 内建 `<foliate-view flow="paginated">`，读取 assets 内示例书
 *    `assets/sample/sample.epub` 渲染第一章。
 *  - 通过 [addJavascriptInterface] 暴露 [EPUBBridge]，接收 relocate 与日志上报。
 */
class ReaderActivity : ComponentActivity() {

    private lateinit var webView: WebView

    /** 当前要打开的书（私有目录内 epub 副本路径；null → 回退内置示例书，便于快速自测）。 */
    private var bookPath: String? = null

    /** 对应书在主键；>=0 才做进度存取。示例书（无 id）只读不存。 */
    private var bookId: Long = -1L

    private lateinit var repository: BookRepository
    private lateinit var scope: kotlinx.coroutines.CoroutineScope

    /** 上次保存的 locator JSON；供 reader.html 在 init 时回传以恢复位置。 */
    @Volatile
    private var savedLocator: String? = null

    private val assetLoader: WebViewAssetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler(
                "/assets/",
                WebViewAssetLoader.AssetsPathHandler(this),
            )
            .addPathHandler("/book/", bookHandler)
            .build()
    }

    /** 提供当前书字节；无书时回退 assets 里的示例书，供快速验证渲染管线。 */
    private val bookHandler = WebViewAssetLoader.PathHandler { path ->
        // WebViewAssetLoader 传入的 path 无前导斜杠（如 "current.epub"）
        if (path == "current.epub") openBookStream() else null
    }

    private fun openBookStream(): WebResourceResponse? {
        val path = bookPath
        val data = runCatching {
            if (path != null && File(path).isFile) File(path).readBytes()
            else assets.open("sample/sample.epub").use { it.readBytes() }
        }.onFailure {
            Log.e(TAG, "读取书源失败 path=$path", it)
            FileLogger.e(TAG, "读取书源失败 path=$path", it)
        }.getOrNull() ?: return null

        Log.d(TAG, "openBookStream ok path=$path size=${data.size}")
        FileLogger.i(TAG, "openBookStream ok path=$path size=${data.size}")
        return WebResourceResponse(
            "application/epub+zip",
            "utf-8",
            HttpURLConnection.HTTP_OK,
            "OK",
            mapOf(
                "Content-Length" to data.size.toString(),
                "Access-Control-Allow-Origin" to "*",
            ),
            ByteArrayInputStream(data),
        )
    }

    private val bridge = LocatorCallback { locatorJson ->
        parseLocation(locatorJson)?.let { (index, fraction) ->
            Log.d(TAG, "relocate index=$index fraction=$fraction")
            FileLogger.d(TAG, "relocate index=$index fraction=$fraction")
        }
        // 异步落盘（每次翻页写一行，字段用 locator JSON 做精确定位）
        scope.launch(Dispatchers.IO) {
            val (index, fraction) = parseLocation(locatorJson)
                ?: return@launch
            repository.saveReadingState(
                BookReadingState(
                    bookId = bookId,
                    chapter = index,
                    progress = fraction,
                    locator = locatorJson,
                ),
            )
        }
    }

    /** 从 foliate lastLocation 提取 section.current（章节索引）与 fraction（全书进度）。 */
    private fun parseLocation(json: String): Pair<Int, Double>? = runCatching {
        val o = org.json.JSONObject(json)
        val index = o.optJSONObject("section")?.optInt("current", 0) ?: 0
        val fraction = o.optDouble("fraction", 0.0)
        index to fraction
    }.getOrNull()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLogger.init(applicationContext)
        bookPath = intent?.getStringExtra(EXTRA_BOOK_PATH)
        bookId = intent?.getLongExtra(EXTRA_BOOK_ID, -1L) ?: -1L
        scope = lifecycleScope
        repository = BookRepository(AppDatabase.get(this).bookDao())
        if (bookId >= 0) {
            // 启动前同步预载上次定位，保证 reader.html 的 getSavedLocator 稳定返回
            savedLocator = kotlinx.coroutines.runBlocking { repository.readingState(bookId)?.locator }
        }
        Log.w(TAG, "★ ReaderActivity onCreate bookPath=$bookPath bookId=$bookId savedLocator=${savedLocator != null}")
        FileLogger.w(TAG, "★ ReaderActivity onCreate bookPath=$bookPath bookId=$bookId savedLocator=${savedLocator != null}")
        // 沉浸式（隐藏状态栏/导航栏），M1 会做成可收起
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(android.graphics.Color.parseColor("#f4f2ec"))

            val settings = this.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // 阅读器 html/资源打包进 assets，必须禁用 WebView 缓存，否则旧版资源被缓存导致改动不生效
            settings.cacheMode = WebSettings.LOAD_NO_CACHE

            webViewClient = object : WebViewClient() {
                // 新版重载（现代 WebView 优先调用）
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    val res = assetLoader.shouldInterceptRequest(request.url)
                    Log.i(TAG, "req2 $url -> ${res?.statusCode ?: "unserved"}")
                    FileLogger.i(TAG, "req2 $url -> ${res?.statusCode ?: "unserved"}")
                    return res
                }

                // 旧版重载（仅作兜底）
                override fun shouldInterceptRequest(
                    view: WebView,
                    url: String,
                ): WebResourceResponse? {
                    val res = assetLoader.shouldInterceptRequest(Uri.parse(url))
                    Log.i(TAG, "req1 $url -> ${res?.statusCode ?: "unserved"}")
                    FileLogger.i(TAG, "req1 $url -> ${res?.statusCode ?: "unserved"}")
                    return res
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.i(TAG, "onPageFinished $url")
                    FileLogger.i(TAG, "onPageFinished $url")
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?,
                ) {
                    val m = "onReceivedError code=${error?.errorCode} ${request?.url}"
                    Log.e(TAG, m)
                    FileLogger.e(TAG, m)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
                    // vivo 限流下 console 高频输出会丢；合并写文件，不逐条打 Log
                    FileLogger.d(TAG, "[console] ${cm.message()}")
                    return true
                }
            }
        }

        setContentView(
            FrameLayout(this).apply {
                addView(webView)
            },
        )

        webView.addJavascriptInterface(
            EPUBBridge(bridge, { savedLocator }, { finish() }, bottomInset()),
            "EPUBBridge",
        )
        webView.loadUrl(ASSET_BASE + "assets/reader.html")
    }

    /** 底部安全区高度(px)。沉浸模式下取系统实际 inset（一般为 0 或手势条高），
     * 避免把整条平台导航栏高度误加到底部导致留白过大。 */
    private fun bottomInset(): Int {
        return window.decorView.rootWindowInsets?.let { insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
            else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
        } ?: 0
    }

    override fun onBackPressed() {
        // 系统返回位：退出阅读器（翻页用三区点击，避免手势返回被吞成翻页污染进度）
        finish()
    }
}

/** 暴露给 JS 的桥接对象（addJavascriptInterface 名：EPUBBridge）。 */
class EPUBBridge(
    private val cb: LocatorCallback,
    private val savedLocatorProvider: () -> String?,
    private val exit: () -> Unit,
    private val bottomInset: Int,
) {

    /** foliate 每次 relocate 回调：携带 `JSON.stringify(view.lastLocation)`。 */
    @JavascriptInterface
    fun onLocation(locatorJson: String) = cb.onLocator(locatorJson)

    /** 返回上次保存的定位 JSON（无则 null），供 init({ lastLocation }) 恢复章节/页。 */
    @JavascriptInterface
    fun getSavedLocator(): String? = savedLocatorProvider()

    /** 底部导航区/手势条高度(px)，用于把底部工具栏抬到系统导航之上，避免被遮挡。 */
    @JavascriptInterface
    fun getBottomInset(): Int = bottomInset

    /** 顶部「返回书架」：正常退出阅读器（不触发任何翻页）。 */
    @JavascriptInterface
    fun back() = exit()

    @JavascriptInterface
    fun log(msg: String) = FileLogger.d("FolioEpub.js", msg)
}

/** 由书架启动阅读器时传入的书文件绝对路径。 */
const val EXTRA_BOOK_PATH = "book_file_path"
/** 由书架启动阅读器时传入的书主键（>=0 才存取进度）。 */
const val EXTRA_BOOK_ID = "book_id"

private const val ASSET_BASE = "https://appassets.androidplatform.net/"
private const val TAG = "FolioEpub.Reader"