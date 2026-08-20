package com.folioepub.ui.reader

import android.annotation.SuppressLint
import android.net.Uri
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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.webkit.WebViewAssetLoader
import com.folioepub.util.FileLogger
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection

/** JS relocate 回调。 */
fun interface RelocateCallback {
    fun onRelocate(index: Int, fraction: Double)
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

    private val bridge = RelocateCallback { index, fraction ->
        Log.d(TAG, "relocate index=$index fraction=$fraction")
        val pct = (fraction * 100).toInt()
        Toast.makeText(this, "第 ${index + 1} 章 · ${pct}%", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLogger.init(applicationContext)
        bookPath = intent?.getStringExtra(EXTRA_BOOK_PATH)
        Log.w(TAG, "★ ReaderActivity onCreate bookPath=$bookPath")
        FileLogger.w(TAG, "★ ReaderActivity onCreate bookPath=$bookPath")
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

        webView.addJavascriptInterface(EPUBBridge(bridge), "EPUBBridge")
        webView.loadUrl(ASSET_BASE + "assets/reader.html")
    }

    override fun onBackPressed() {
        // 拦下 WebView 内部历史回退，改为 foliate 上一页
        if (webView.canGoBack()) {
            webView.evaluateJavascript(
                "window.folioWebView && window.folioWebView.prev ? window.folioWebView.prev() : null",
                null,
            )
        } else {
            super.onBackPressed()
        }
    }
}

/** 暴露给 JS 的桥接对象（addJavascriptInterface 名：EPUBBridge）。 */
class EPUBBridge(private val cb: RelocateCallback) {

    @JavascriptInterface
    fun onRelocate(index: Int, fraction: Double) = cb.onRelocate(index, fraction)

    @JavascriptInterface
    fun log(msg: String) = FileLogger.d("FolioEpub.js", msg)
}

/** 由书架启动阅读器时传入的书文件绝对路径。 */
const val EXTRA_BOOK_PATH = "book_file_path"

private const val ASSET_BASE = "https://appassets.androidplatform.net/"
private const val TAG = "FolioEpub.Reader"