package com.orilum.ui.reader

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import com.orilum.data.book.AppDatabase
import com.orilum.data.book.BookReadingState
import com.orilum.data.book.BookRepository
import com.orilum.data.font.FontFace
import com.orilum.data.font.FontRepository
import com.orilum.data.settings.BookSettings
import com.orilum.data.settings.BookSettingsStore
import com.orilum.data.settings.ReaderSettings
import com.orilum.data.settings.ReaderSettingsStore
import com.orilum.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 *  - reader.html 内建 `<foliate-view flow="paginated">`，通过虚拟域 `current.epub` 加载当前书。
 *  - 通过 [addJavascriptInterface] 暴露 [EPUBBridge]，接收 relocate 与日志上报。
 */
class ReaderActivity : ComponentActivity() {

    private lateinit var webView: WebView

    /** 当前要打开的书（公共书库目录内 epub 的 content:// uri）；null 表示无书源。 */
    private var bookPath: String? = null

    /** 对应书在主键；>=0 才做进度存取。 */
    private var bookId: Long = -1L

    private lateinit var repository: BookRepository
    private lateinit var settingsStore: ReaderSettingsStore
    private lateinit var bookSettingsStore: BookSettingsStore
    private lateinit var fontRepository: FontRepository
    private lateinit var scope: kotlinx.coroutines.CoroutineScope

    /** 状态栏已彻底隐藏后再把其色置透明的延迟任务：保持隐藏过程深灰回缩、不露出阅读底色。 */
    private var hideBarTransparentRunnable: Runnable? = null

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
            .addPathHandler("/fonts/", fontHandler)
            .build()
    }

    /** 提供某个字体候选的源文件字节（按 `FontFace.id` 定位私有副本），供 CSS url() 加载。 */
    private val fontHandler = WebViewAssetLoader.PathHandler { path ->
        val id = decodeUrlKey(path)?.toLongOrNull()
        val data = id?.let { fontRepository.fontBytes(it) } ?: return@PathHandler null
        val mime = path.substringAfterLast('.', "ttf").lowercase().let { ext ->
            if (ext == "otf" || ext == "otc") "font/otf" else "font/ttf"
        }
        WebResourceResponse(
            mime, null, HttpURLConnection.HTTP_OK, "OK",
            mapOf(
                "Content-Length" to data.size.toString(),
                "Access-Control-Allow-Origin" to "*",
            ),
            ByteArrayInputStream(data),
        )
    }

    /** 由 `/fonts/{key}` 还原 key（key 内可能含 %xx，解码；去掉尾部斜杠与扩展名后的原始片段不宜截错，直接整体解码）。 */
    private fun decodeUrlKey(path: String): String? {
        val seg = path.removeSuffix("/").substringAfterLast('/')
        if (seg.isBlank()) return null
        return runCatching { java.net.URLDecoder.decode(seg, "UTF-8") }.getOrNull() ?: seg
    }

    /** 提供当前书字节；无书（本机无可用书源）返回 null。 */
    private val bookHandler = WebViewAssetLoader.PathHandler { path ->
        // WebViewAssetLoader 传入的 path 无前导斜杠（如 "current.epub"）
        if (path == "current.epub") openBookStream() else null
    }

    private fun openBookStream(): WebResourceResponse? {
        val path = bookPath
        val data = runCatching {
            when {
                path?.startsWith("content://") == true ->
                    contentResolver.openInputStream(Uri.parse(path))?.use { it.readBytes() }
                path != null && File(path).isFile ->
                    File(path).readBytes()
                else -> null
            }
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
        settingsStore = ReaderSettingsStore(File(filesDir, "settings"))
        bookSettingsStore = BookSettingsStore(File(filesDir, "settings"))
        fontRepository = FontRepository(this, AppDatabase.get(this).fontDao())
        if (bookId >= 0) {
            // 启动前同步预载上次定位，保证 reader.html 的 getSavedLocator 稳定返回
            savedLocator = kotlinx.coroutines.runBlocking { repository.readingState(bookId)?.locator }
        }
        Log.w(TAG, "★ ReaderActivity onCreate bookPath=$bookPath bookId=$bookId savedLocator=${savedLocator != null}")
        FileLogger.w(TAG, "★ ReaderActivity onCreate bookPath=$bookPath bookId=$bookId savedLocator=${savedLocator != null}")
        // 沉浸阅读：默认隐藏状态栏与导航栏；工具栏弹出时经 JS 联动显示状态栏（见 EPUBBridge.setSystemBarsVisible）
        // 使用 WindowInsets 边到边绘制：内容始终全屏铺满，状态栏/导航栏透明叠加其上，
        // 这样「切换应用/最近任务预览」里系统临时退出沉浸式时，顶部就是阅读页本身，不会露出独立的深色/白色条。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enterImmersive()
        // 系统栏配色跟随工具栏显隐动态设置（见 setSystemBarsVisible）：默认透明 + 浅色窗底，
        // 避免在「切换应用/最近任务预览」时系统强制退出沉浸式、把固定 #303030 状态栏渲染成顶部深色横条
        //（其他应用无此问题，因为它们状态栏与内容同色）。仅在工具栏弹出时置为 #303030 与标题栏融为一色。
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        // 窗底透明：预览/过渡时由 WebView 阅读页本身铺满，不额外透出任何横条色
        window.decorView.setBackgroundColor(Color.TRANSPARENT)

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
                    // vivo 限流下 console 高频输出会丢；文件 + logcat 双写（排查翻页问题时看 logcat 即时态）
                    FileLogger.d(TAG, "[console] ${cm.message()}")
                    Log.d(TAG, "[console] ${cm.message()}")
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
            EPUBBridge(
                bridge,
                { savedLocator },
                settingsStore,
                bookSettingsStore,
                { bookId },
                fontRepository,
                { finish() },
                bottomInset(),
                topInset(),
                { show ->
                    // 工具栏显示/隐藏联动系统状态栏（桥线程 → 主线程）；导航栏始终隐藏
                    runOnUiThread {
                        runCatching {
                            val decor = window.decorView
                            if (show) {
                                // 取消待执行的「置透明」，避免隐藏中被打断
                                hideBarTransparentRunnable?.let { decor.removeCallbacks(it) }
                                // 工具栏弹出：状态栏同色深灰、与标题栏融为一体（仅显示状态栏，导航栏保持隐藏）
                                window.statusBarColor = 0xFF303030.toInt()
                                window.navigationBarColor = 0xFF303030.toInt()
                                showStatusBarOnly()
                            } else {
                                // 收起：先保持深灰回缩，让系统状态栏回缩动画全程是深灰，不瞬间露出阅读底色；
                                // 待状态栏彻底隐藏后再延迟置透明（透明仅用于隐藏态，避免最近任务预览出深色条）。
                                window.statusBarColor = 0xFF303030.toInt()
                                window.navigationBarColor = 0xFF303030.toInt()
                                enterImmersive()
                                val r = Runnable {
                                    runOnUiThread {
                                        runCatching {
                                            window.statusBarColor = Color.TRANSPARENT
                                            window.navigationBarColor = Color.TRANSPARENT
                                        }
                                    }
                                }
                                hideBarTransparentRunnable = r
                                decor.postDelayed(r, 320)
                            }
                        }
                    }
                },
                { applyBrightness(it) },
                { applyOffsetBrightness(it) },
                webView,
            ),
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

    /** 顶部状态栏高度(px)。状态栏常驻显示时，供工具栏把文字/按钮抬到其下，并通过 padding-top 让深灰背景覆盖状态栏区域达到一体化。
     *  优先读系统 `status_bar_height` 资源：无论状态栏当前显隐、是否边到边，都返回真实高度，
     *  避免在 onCreate 时 rootWindowInsets 未就绪/被 LAYOUT_FULLSCREEN 摊平而误返回 0，导致标题栏被状态栏遮盖。 */
    private fun topInset(): Int {
        val res = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (res > 0) return resources.getDimensionPixelSize(res)
        return window.decorView.rootWindowInsets?.let { insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
            else @Suppress("DEPRECATION") insets.systemWindowInsetTop
        } ?: 0
    }

    /** 沉浸阅读：隐藏状态栏与导航栏。由 deprecated 的 `SYSTEM_UI_FLAG_*` 迁移为
     *  兼容的 [WindowInsetsControllerCompat]（minSdk 23 也可用）；底层走边到边
     *  （[WindowCompat.setDecorFitsSystemWindows] 已置 false），边缘擦划可临时唤出系统栏。 */
    private fun enterImmersive() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** 工具栏弹出：仅显示状态栏，导航栏保持隐藏（符合「状态栏随工具栏显隐」的既有交互）。 */
    private fun showStatusBarOnly() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.show(WindowInsetsCompat.Type.statusBars())
        c.hide(WindowInsetsCompat.Type.navigationBars())
    }

    private fun reapplyImmersive() {
        enterImmersive()
    }

    // ---- 系统级亮度（写 Settings.System.SCREEN_BRIGHTNESS，真正控制物理背光，可调亮）----

    /** 进入阅读器时的原系统亮度(0..max)与亮度模式，供退出/跟随时还原。 */
    private var origBrightness: Int = -1

    private var origBrightnessMode: Int = -1

    /** 设备背光合法上限：优先读系统资源配置，否则按官方 0..255 处理。 */
    private val maxBrightness: Int by lazy {
        val id = resources.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
        if (id > 0) resources.getInteger(id) else 255
    }

    /** 进入阅读器首次改动亮度前，缓存原系统亮度与模式，保证退出能还原。 */
    private fun rememberSystemBrightness() {
        if (origBrightness < 0) {
            origBrightness = Settings.System.getInt(
                contentResolver, Settings.System.SCREEN_BRIGHTNESS, 255,
            )
        }
        if (origBrightnessMode < 0) {
            origBrightnessMode = Settings.System.getInt(
                contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1,
            )
        }
    }

    /** 还原进入前的系统亮度与亮度模式（完全跟随系统/退出阅读器时调用）。 */
    private fun restoreSystemBrightness() {
        if (!Settings.System.canWrite(this)) return
        if (origBrightness >= 0) {
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, origBrightness)
        }
        if (origBrightnessMode >= 0) {
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                origBrightnessMode,
            )
        }
    }

    /**
     * 应用阅读器亮度档位（-50..100，与前端「亮度」滑块/手势一致）。
     *  - 0..100 → 写系统亮度 v/100*max（先切手动模式）→ 控制物理背光，可突破系统当前亮度调亮，也能调暗。
     *  - -50..-1 → 系统亮度钉 0，余下压暗交给前端黑色遮罩（突破设备最暗下限）。
     * 若 WRITE_SETTINGS 未授权，回退窗口级亮度保证至少能调暗。
     */
    private fun applyBrightness(value: Int) {
        val v = value.coerceIn(-50, 100)
        rememberSystemBrightness()
        // 未授权改系统设置：回退窗口级亮度（只能调暗，够用且免授权）
        if (!Settings.System.canWrite(this)) {
            setWindowBrightness(if (v < 0) 0f else v / 100f)
            return
        }
        // 先手动模式再写亮度；自动模式下写入会被环境光策略覆盖甚至忽略
        Settings.System.putInt(
            contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        val target = if (v < 0) 0 else
            Math.round(v / 100f * maxBrightness).toInt().coerceIn(0, maxBrightness)
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, target)
    }

    /**
     * 跟随系统时的「亮度偏移」（-20..20）。
     *  - >0 增亮：真正写系统亮度（物理背光）在上限内按每单位 +max/100 提升——白纱只会发白不增亮。
     *  - <=0 还原：负偏移压暗交给前端黑色遮罩；0 为完全跟随系统。回到进入前系统亮度/模式。
     */
    private fun applyOffsetBrightness(offset: Int) {
        val o = offset.coerceIn(-20, 20)
        rememberSystemBrightness()
        // 未授权改系统设置：正偏移无法真正增亮，直接放弃（保持跟跟随/遮罩原样）
        if (!Settings.System.canWrite(this)) return
        if (o <= 0) {
            restoreSystemBrightness()
            return
        }
        // 正偏移：以进入前系统亮度为基准，每 1 单位提升 max/100；先切手动再写亮度
        val base =
            if (origBrightness >= 0) origBrightness
            else Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 0)
        Settings.System.putInt(
            contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        val target = (base + o * maxBrightness.toDouble() / 100.0)
            .toInt().coerceIn(0, maxBrightness)
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, target)
    }

    /** 仅作为「未授予 WRITE_SETTINGS」时的窗口级亮度回退方案。 */
    private fun setWindowBrightness(level: Float) {
        webView.post {
            val attrs = window.attributes
            attrs.screenBrightness = level
            window.attributes = attrs
        }
    }

    /** SAF 目录选择器关闭（确认/取消）后回到阅读器时恢复沉浸式。 */
    override fun onResume() {
        super.onResume()
        reapplyImmersive()
    }

    override fun onDestroy() {
        // 退出阅读器时把系统亮度/模式还原为进入前状态，避免改动残留
        restoreSystemBrightness()
        super.onDestroy()
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
    private val settingsStore: ReaderSettingsStore,
    private val bookSettingsStore: BookSettingsStore,
    private val bookIdProvider: () -> Long,
    private val fontRepository: FontRepository,
    private val exit: () -> Unit,
    private val bottomInset: Int,
    private val topInset: Int,
    private val setSystemBarsVisibility: (Boolean) -> Unit,
    private val applyBrightness: (Int) -> Unit,
    private val applySystemBrightnessOffset: (Int) -> Unit,
    private val webView: WebView,
) {

    /** foliate 每次 relocate 回调：携带 `JSON.stringify(view.lastLocation)`。 */
    @JavascriptInterface
    fun onLocation(locatorJson: String) = cb.onLocator(locatorJson)

    /** 返回上次保存的定位 JSON（无则 null），供 init({ lastLocation }) 恢复章节/页。 */
    @JavascriptInterface
    fun getSavedLocator(): String? = savedLocatorProvider()

    /** 返回当前生效设置 JSON（全局 + 当前书覆盖层合并）。 */
    @JavascriptInterface
    fun getSettings(): String {
        val global = settingsStore.load()
        val bookId = bookIdProvider()
        return if (bookId >= 0) {
            val overlay = bookSettingsStore.load(bookId)
            global.applyOverlay(overlay).toJson(2)
        } else {
            global.toJson(2)
        }
    }

    /** 保存用户设置：拆成「书内覆盖层」「覆盖层回写全局」「纯全局字段」三条路分别落盘。 */
    @JavascriptInterface
    fun saveSettings(json: String?): Boolean {
        if (json.isNullOrBlank()) return false
        val bookId = bookIdProvider()
        // 从 JS 回传的完整 JSON 解析为全量设置
        val full = ReaderSettings.fromJson(json)
        // ① 书内覆盖层：仅排版类字段
        val overlay = BookSettings.fromReaderSettings(full)
        if (bookId >= 0) bookSettingsStore.save(bookId, overlay)
        // ②③ 全局：覆盖层回写（传染）+ 纯全局字段直接持久化（亮度/护眼/手势/续读/页码等）
        val global = settingsStore.load()
            .mergeFrom(overlay)
            .copy(
                useUserScripts = full.useUserScripts,
                autoContinue = full.autoContinue,
                pageNum = full.pageNum,
                brightness = full.brightness,
                brightnessFollowSystem = full.brightnessFollowSystem,
                brightnessOffset = full.brightnessOffset,
                eyeProtection = full.eyeProtection,
                eyeProtectionLevel = full.eyeProtectionLevel,
                brightnessGestureLeft = full.brightnessGestureLeft,
                brightnessGestureRight = full.brightnessGestureRight,
                brightnessGestureTwo = full.brightnessGestureTwo,
            )
        settingsStore.save(global)
        log("saveSettings -> overlay=${overlay.toJson()}")
        return true
    }

    /** 一键重置设置：删除全局用户套 + 当前书覆盖层，之后返回默认套 JSON。 */
    @JavascriptInterface
    fun resetSettings(): String {
        settingsStore.reset()
        val bookId = bookIdProvider()
        if (bookId >= 0) bookSettingsStore.reset(bookId)
        val d = ReaderSettings.DEFAULT
        log("resetSettings -> DEFAULT")
        return d.toJson(2)
    }

    /** 底部导航区/手势条高度(px)，用于把底部工具栏抬到系统导航之上，避免被遮挡。 */
    @JavascriptInterface
    fun getBottomInset(): Int = bottomInset

    /** 顶部状态栏高度(px)，用于把顶部工具栏抬到状态栏之下、深灰背景覆盖状态栏，标题栏与状态栏一体化。 */
    @JavascriptInterface
    fun getTopInset(): Int = topInset

    /** 工具栏弹出/收起时联动显示/隐藏系统状态栏（导航始终隐藏）。由 JS 在 showBars/hideBars 时调用。 */
    @JavascriptInterface
    fun setSystemBarsVisible(show: Boolean) = setSystemBarsVisibility(show)

    /**
     * 返回已导入字体池的**家族结构** JSON 数组：
     * `[{family, name, lang, weights:[{id, subfamily}]}]`
     * 同一家族的多字重文件归并成一个条目（渲染按字重归档成多份 @font-face）。
     * 仅 cjk/latin/generic；`subfamily` 空串代表无字重名。
     */
    @JavascriptInterface
    fun listFonts(): String {
        val fonts = kotlinx.coroutines.runBlocking { fontRepository.list() }
        val byFamily = fonts.groupBy { it.familyName }
        val arr = org.json.JSONArray()
        byFamily.forEach { (family, members) ->
            val weights = org.json.JSONArray()
            members.forEach { m ->
                weights.put(
                    org.json.JSONObject().apply {
                        put("id", m.id)
                        put("subfamily", m.subfamily)
                    },
                )
            }
            arr.put(
                org.json.JSONObject().apply {
                    put("family", family)
                    put("name", members.first().displayName)
                    put("lang", members.first().lang)
                    put("weights", weights)
                },
            )
        }
        return arr.toString()
    }

    /** 顶部「返回书架」：正常退出阅读器（不触发任何翻页）。 */
    @JavascriptInterface
    fun back() {
        webView.post { exit() }
    }

    /** 应用阅读器亮度档位（-50..100）：0..100 可根据系统授权写系统亮度、<0 窗口最暗由前端遮罩叠加。跟随系统走 [setSystemBrightnessOffset]。 */
    @JavascriptInterface
    fun setBrightness(value: Int) = applyBrightness(value)

    /** 「跟随系统亮度 + 偏移」：offset -20..20，围绕系统亮度在窗内微调（0 = 完全跟随）。 */
    @JavascriptInterface
    fun setSystemBrightnessOffset(offset: Int) = applySystemBrightnessOffset(offset)

    @JavascriptInterface
    fun log(msg: String) = FileLogger.d("Orilum.js", msg)
}

/** 由书架启动阅读器时传入的书文件绝对路径。 */
const val EXTRA_BOOK_PATH = "book_file_path"
/** 由书架启动阅读器时传入的书主键（>=0 才存取进度）。 */
const val EXTRA_BOOK_ID = "book_id"

private const val ASSET_BASE = "https://appassets.androidplatform.net/"
private const val TAG = "Orilum.Reader"