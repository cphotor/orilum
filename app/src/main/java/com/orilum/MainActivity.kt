package com.orilum

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.orilum.data.book.AppDatabase
import com.orilum.data.book.Book
import com.orilum.data.book.BookImporter
import com.orilum.data.book.BookRepository
import com.orilum.data.font.FontFace
import com.orilum.data.font.FontRepository
import com.orilum.data.settings.ReaderSettingsStore
import com.orilum.ui.reader.EXTRA_BOOK_ID
import com.orilum.ui.reader.EXTRA_BOOK_PATH
import com.orilum.ui.reader.ReaderActivity
import com.orilum.ui.theme.OrilumTheme
import com.orilum.util.FileLogger
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.roundToInt
import java.io.File
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自定义书架（M0 闭环）：
 * 选书（SAF）→ 解析（自建 [BookImporter]/[BookRepository]）→ 渲染（打开 [ReaderActivity]）。
 */
class MainActivity : ComponentActivity() {

    private val repository by lazy { BookRepository(AppDatabase.get(this).bookDao()) }
    private val importer by lazy { BookImporter(this, repository) }
    private val booksFlow by lazy { repository.books() }

    /** 阅读配置；autoContinue 决定启动书架时是否自动进入最后阅读的书。 */
    private val settingsStore by lazy { ReaderSettingsStore(File(filesDir, "settings")) }

    /** 字体池仓库（导入式，接 FontDao；写私有字体目录、跨重启持久）。 */
    private val fontRepository by lazy { FontRepository(this, AppDatabase.get(this).fontDao()) }

    /** 记录最后打开的书主键，供「打开时续读」冷启动跳转。 */
    private val prefs by lazy { getSharedPreferences("reader_prefs", android.content.Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 「打开时续读」：启动书架时若开关开启且记录过最后阅读的书，则自动进入阅读器。
        // 每次冷启动只跳一次，避免用户返回书架后又弹回阅读页。
        if (savedInstanceState == null) maybeContinueLastBook()

        // 边到边：让状态栏/导航栏与顶部标题栏、底部工具栏融为一色（Material3 Scaffold
        // 会把系统栏 insets 计入对应栏的背景），切换应用/最近任务预览时不再露出独立深色横条，
        // 与阅读页沉浸式「系统栏与内容同色」是同一套思路。
        enableEdgeToEdge()
        // 书架顶栏/底栏改为白底黑字，系统状态栏/导航栏图标需用深色，否则白底白字看不清。
        // enableEdgeToEdge 默认按浅色主题给深色图标，这里在 onCreate 用 decorView 强制改为深色。
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        setContent {
            OrilumTheme {
                var gridMode by rememberSaveable { mutableStateOf(false) }
                var showSettings by rememberSaveable { mutableStateOf(false) }
                val pickEpub = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenMultipleDocuments(),
                ) { uris ->
                    if (uris.isNotEmpty()) onBooksPicked(uris)
                }

                val books by booksFlow.collectAsStateWithLifecycle(initialValue = emptyList())

                // 书架整屏为底层。设置面板以「右侧滑出抽屉」叠加于 Scaffold 的内容区之上——
                // 其高度自动=「顶栏下方 → 底栏上方」的内容区高，绝不写死。
                ShelfScreen(
                    books = books,
                    gridMode = gridMode,
                    onToggleView = { gridMode = !gridMode },
                    onImport = { pickEpub.launch(arrayOf("application/epub+zip")) },
                    onEdit = { toast("编辑（占位）") },
                    onSettings = { showSettings = !showSettings },
                    onOpenBook = { openReader(it) },
                    fontRepository = fontRepository,
                    settingsOpen = showSettings,
                    onDismissSettings = { showSettings = false },
                )
            }
        }
    }

    /** 短提示（占位按钮/操作的统一反馈）。 */
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /**
     * 批量导入所选电子书：逐个在 I/O 协程导入，结束后用一条 Toast 汇总成功/失败情况。
     * （多选导入支持，避免一次只能一本。）
     */
    private fun onBooksPicked(uris: List<android.net.Uri>) {
        lifecycleScope.launch {
            var ok = 0
            var fail = 0
            // 逐个导入期间临时离线提醒：导入是串行 I/O，避免 UI 卡顿。
            uris.forEach { uri ->
                val result = importer.import(uri)
                if (result.isSuccess) ok++ else fail++
            }
            val msg = when {
                ok == 0 -> "导入失败：$fail 本未导入"
                fail == 0 -> "已加入书架 $ok 本"
                else -> "导入成功 $ok 本，失败 $fail 本"
            }
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openReader(book: Book) {
        // 记录最后打开的书，供「打开时续读」下次冷启动自动进入
        prefs.edit().putLong(KEY_LAST_BOOK_ID, book.id).apply()
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(EXTRA_BOOK_PATH, book.filePath)
                .putExtra(EXTRA_BOOK_ID, book.id),
        )
    }

    /** 「打开时续读」：开关开启且记录过最后阅读的书 → 应用启动时自动进入该书阅读器。 */
    private fun maybeContinueLastBook() {
        if (!settingsStore.load().autoContinue) return
        val lastId = prefs.getLong(KEY_LAST_BOOK_ID, -1L)
        if (lastId <= 0) return
        lifecycleScope.launch {
            val book = repository.getBook(lastId)
            if (book != null) {
                FileLogger.i(TAG, "continue last book id=$lastId title=${book.title}")
                openReader(book)
            } else {
                // 最后阅读的书已被移除：清理记录，回到书架
                FileLogger.i(TAG, "last book gone, clear key bookId=$lastId")
                prefs.edit().remove(KEY_LAST_BOOK_ID).apply()
            }
        }
    }

    companion object {
        private const val TAG = "Orilum.Main"
        private const val KEY_LAST_BOOK_ID = "last_book_id"
    }
}

/** 书架顶栏与底栏配色：普通白底 + 深黑前景（比阅读页工具栏的深灰更轻，契合书架清爽观感）。 */
private val BarGray = Color.White
private val BarGrayFg = Color(0xFF1F1F1F)

/**
 * 底部工具栏按钮：图标在上、文字在下，平分一行；「图标+文字」整体作为圆角按钮，按压时整块高亮。
 * 与阅读页 reader.html 的 .tool（icon 上、label 下、:active 高亮）视觉与交互一致。
 * 复用 MutableInteractionSource 监听按压态驱动背景，而非 Material ripple 胶囊，保持两界面同一套观感。
 */
@androidx.compose.runtime.Composable
private fun RowScope.ToolItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 外层占 1/4 平分一行并垂直居中，负责布局但不接管按压，保证整项仍是平分条。
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 按钮本体：圆角块，内衬合理留白包裹「图标+文字」；按压高亮覆盖整个块（对齐阅读页 .tool 的 :active）。
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (pressed) Color(0x12000000) else Color.Transparent)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = BarGrayFg,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = label, fontSize = 12.sp, color = BarGrayFg, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** 书架卡片：书名 + 作者。 */
@androidx.compose.runtime.Composable
private fun BookCard(book: Book, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = book.title, style = MaterialTheme.typography.titleMedium)
            book.author?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 书架整屏：顶部标题栏（承接状态栏）+ 内容区 + 底部工具栏（承接导航栏）。
 * Material3 Scaffold 自动把系统栏 insets 计入 topBar/bottomBar 背景，状态栏/导航栏
 * 区域与栏同色，应用切换/最近任务预览不露深色横条。
 */
@androidx.compose.runtime.Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ShelfScreen(
    books: List<Book>,
    gridMode: Boolean,
    onToggleView: () -> Unit,
    onImport: () -> Unit,
    onEdit: () -> Unit,
    onSettings: () -> Unit,
    onOpenBook: (Book) -> Unit,
    fontRepository: FontRepository,
    settingsOpen: Boolean,
    onDismissSettings: () -> Unit,
) {
    // 系统栏图标深色已在 MainActivity.onCreate 统一设置（见 enableEdgeToEdge 后），此处无需重复。

    Scaffold(
        containerColor = Color.White,
        topBar = {
            // 标题栏：白色、高度 48dp（较此前 32dp 增加 50%），「书架」靠左垂直居中、字号 19sp。
            // 状态栏区域单独以同色白条承接，与标题栏融合；系统栏图标为深色（见 onCreate 设置）。
            Column(modifier = Modifier.fillMaxWidth().background(BarGray)) {
                Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars).background(BarGray))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    Text(
                        text = "书架",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BarGrayFg,
                    )
                }
            }
        },
        bottomBar = {
            // 底部工具栏：白色背景铺满到屏幕底（含系统栏区域），按钮行高 56dp 置于其上。
            Column(modifier = Modifier.fillMaxWidth().background(BarGray)) {
                // 按钮行高 56dp 足够容纳「图标+文字」整块并垂直居中；行内上下留白保持紧凑，避免按钮下方出现过多空白。
                Row(modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    // 面板开着时，点底部任一功能按钮只先关面板（呈模态：书架除关闭外暂不可操作）；
                    // 「设置」按钮本身为开关切换，交由 onSettings 处理，不在此拦截。
                    ToolItem(icon = if (gridMode) Icons.Default.List else ImageVector.vectorResource(R.drawable.ic_grid), label = "封面/列表", onClick = { if (settingsOpen) onDismissSettings() else onToggleView() })
                    ToolItem(icon = Icons.Default.Add, label = "导入", onClick = { if (settingsOpen) onDismissSettings() else onImport() })
                    ToolItem(icon = Icons.Default.Edit, label = "编辑", onClick = { if (settingsOpen) onDismissSettings() else onEdit() })
                    ToolItem(icon = Icons.Default.Settings, label = "设置", onClick = onSettings)
                }
                // 底部安全间距：该设备系统导航栏 inset 极小，取一半即可让按钮行避开系统栏，且几乎不留空白。
                val navBottom = WindowInsets.navigationBars.getBottom(LocalDensity.current)
                Spacer(modifier = Modifier.height((navBottom / 2).dp))
            }
        },
    ) { padding ->
        // 内容区：以 Box 承载书单列表，并在其上叠加设置抽屉（高度=padding 已裁掉标题栏/工具栏后的区域，不写死）。
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (books.isEmpty()) {
                // 空书架提示：目录已非必需（默认私有即用），仅提示用户导入。
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "书架上还没有书", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(books, key = { it.id }) { book ->
                        // 面板打开时书不可点（与底部功能按钮同呈模态），点书仅关闭面板。
                        BookCard(book = book, onClick = {
                            if (settingsOpen) onDismissSettings() else onOpenBook(book)
                        })
                    }
                }
            }
            // 设置抽屉浮于内容区顶部（右侧停靠）。
            SettingsDrawer(
                show = settingsOpen,
                fontRepository = fontRepository,
                onDismiss = onDismissSettings,
            )
        }
    }
}

/** SAF 可选中的字体文件 MIME 类型（不同系统上报格式兼容）。 */
private val FONT_MIMES = arrayOf(
    "font/ttf", "font/otf", "font/woff", "font/woff2",
    "application/x-font-ttf", "application/vnd.ms-opentype",
    "application/octet-stream",
)

/**
 * 书架设置面板的导航路由：多级下沉，一级「设置」主页列出可下钻项，点某项再进子页。
 * 与阅读页设置抽屉相同的布局：窄屏近全屏、平板收窄为侧栏，后续新增设置项在此扩展路由。
 */
private sealed interface SettingsRoute {
    val title: String
    data object Home : SettingsRoute { override val title = "设置" }
    data object Fonts : SettingsRoute { override val title = "字体管理" }
}

/** 抽屉面板配色，取自阅读页 reader.html 浅色主题 `--ui-bg` 族，保证两侧观感一致。 */
private val PanelBg = Color.White
private val PanelText = Color(0xFF2B2B2B)
private val PanelMuted = Color(0xFF888888)
private val PanelChevron = Color(0xFFBBBBBB)
private val PanelDivider = Color(0xFFF0EDE6)
private val PanelSlab = Color(0xFFEFECE4)

/**
 * 书架全局设置抽屉（「⚙ 设置」进入）：右侧停靠、滑入，风格对齐阅读页设置面板。
 *
 * 结构与阅读页一致：宽度写死（宽屏固定 360dp，窄屏 `min(360, 85vw)`）；高度由外层
 * Scaffold 内容区约束决定，即「屏幕高 − 系统状态栏 − 标题栏 − 底部工具栏」，不写死。
 * 无遮罩、无标题栏（子页时才在顶部显示一枚细返回箭头）。
 *
 * 面板内为多级下沉栈：一级「设置」主页 + 可下钻子页（当前「字体」），系统返回键逐级退栈、栈底关闭。
 * 配色：浅色米白底 + 细分割线 + 行悬浮高亮，取自阅读页 `--ui-bg` 等变量，两侧观感统一。
 */
@androidx.compose.runtime.Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SettingsDrawer(
    show: Boolean,
    fontRepository: FontRepository,
    onDismiss: () -> Unit,
) {
    // 面板宽度写死，与阅读页设置面板一致：宽屏固定 360dp，窄屏 min(360, 85vw)。
    val drawerWidth = with(androidx.compose.ui.platform.LocalConfiguration.current) {
        if (screenWidthDp < 600) (screenWidthDp * 0.85f).dp else 360.dp
    }

    // 下沉导航栈：栈底 Home，push 下钻、pop 返回；抽屉关闭时重置回 Home。
    val stack = remember { mutableStateListOf<SettingsRoute>(SettingsRoute.Home) }
    val current = stack.last()
    val scope = rememberCoroutineScope()
    // 字体池共享状态：主页显示已导入数量，字体子页导入/删除后刷新。
    var fonts by remember { mutableStateOf<List<FontFace>>(emptyList()) }
    LaunchedEffect(Unit) { fonts = fontRepository.list() }
    val refreshFonts: suspend () -> Unit = { fonts = fontRepository.list() }
    // SAF 多选字体文件 → 逐个导入，随后刷新列表
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            uris.forEach { runCatching { fontRepository.import(it) } }
            refreshFonts()
        }
    }
    // WIFI 导入对话框开关。
    var showWifiDialog by remember { mutableStateOf(false) }

    // 系统返回：先逐级退栈，栈底则关闭整个抽屉。
    BackHandler(enabled = show) {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else onDismiss()
    }

    AnimatedVisibility(
        visible = show,
        modifier = Modifier.fillMaxSize(),
        enter = slideInHorizontally { it },
        exit = slideOutHorizontally { it },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 面板外空白区：不画遮罩、只消费点击 → 防止穿透到底层书架，点击即关闭整个抽屉。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
            // 右侧停靠、填满内容区高度（顶栏下方 → 底栏上方）。
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .width(drawerWidth)
                    .fillMaxHeight()
                    .background(PanelBg),
            ) {
            // 顶行即标题行：中间「设置」粗体标题；左侧按层级显示返回「‹」，右侧常驻关闭「✕」。
            // ✕ 样式与阅读页目录/设置面板的关闭按钮一致：transparent、继色、四周留白。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 8.dp),
            ) {
                if (stack.size > 1) {
                    Text(
                        text = "‹",
                        color = PanelText,
                        fontSize = 24.sp,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(horizontal = 4.dp)
                            .clickable { stack.removeAt(stack.lastIndex) },
                    )
                }
                Text(
                    text = current.title,
                    color = PanelText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center),
                )
                Text(
                    text = "✕",
                    color = PanelText,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(8.dp)
                        .clickable(onClick = onDismiss),
                )
            }
            when (current) {
                SettingsRoute.Home -> SettingsHomePage(
                    fontsCount = fonts.size,
                    onOpenFonts = { stack.add(SettingsRoute.Fonts) },
                    modifier = Modifier.weight(1f),
                )
                // 字体导入：私有目录即用，直接拉起选择器。
                SettingsRoute.Fonts -> ManageFontsPage(
                    fonts = fonts,
                    onImport = { importLauncher.launch(FONT_MIMES) },
                    onImportWifi = { showWifiDialog = true },
                    onDelete = { family -> scope.launch { fontRepository.deleteFamily(family); refreshFonts() } },
                    modifier = Modifier.weight(1f),
                )
            }
            }
        }
    }
    // WIFI 导入对话框：全屏独立窗口，不嵌在被裁切的面板动画内。
    if (showWifiDialog) {
        WifiImportDialog(
            fontRepository = fontRepository,
            onDismiss = { showWifiDialog = false },
            onImported = { scope.launch { refreshFonts() } },
        )
    }
}

/** 书架设置一级页：列出可下钻设置项（当前为字体管理）。 */
@androidx.compose.runtime.Composable
private fun SettingsHomePage(
    fontsCount: Int,
    onOpenFonts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            SettingsEntry(
                label = "字体管理",
                value = if (fontsCount > 0) "已导入 $fontsCount 个" else null,
                onClick = onOpenFonts,
                hasDivider = false,
            )
        }
    }
}

/** 一行可下钻设置项：label 左、当前值(若有)右中、› 最右，下方细分割线（对齐阅读页 .set-row）。 */
@androidx.compose.runtime.Composable
private fun SettingsEntry(
    label: String,
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hasDivider: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = PanelText,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f),
            )
            if (value != null) {
                Text(
                    text = value,
                    fontSize = 13.sp,
                    color = PanelMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            Text(text = "›", color = PanelChevron, fontSize = 14.sp)
        }
        if (hasDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                thickness = 1.dp,
                color = PanelDivider,
            )
        }
    }
}

/** 字体行左滑露出的删除区底色。 */
private val DeleteRed = Color(0xFFD9534F)

/**
 * 书架设置二级页「字体管理」：SAF 多选导入（私有拷贝 + 解析分类入库，跨重启持久），
 * 列出已导入字体、每项左滑露出删除按钮。这里编辑的是全局字体池，供阅读页按家族选用替换原书字体。
 */
@androidx.compose.runtime.Composable
private fun ManageFontsPage(
    fonts: List<FontFace>,
    onImport: () -> Unit,
    onImportWifi: () -> Unit,
    onDelete: (String) -> Unit, // familyName
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(PanelBg)) {
        // 导入区：与底部工具栏一致「图标在上、文字在下」的按钮风格。
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ManageImportAction(icon = Icons.Default.Add, label = "本地导入", onClick = onImport)
            ManageImportAction(icon = ImageVector.vectorResource(R.drawable.ic_wifi), label = "WIFI 导入", onClick = onImportWifi)
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            thickness = 1.dp,
            color = PanelDivider,
        )
        // 显示层按家族合并：同家族的字重归并成一行，副行罗列字重；左滑删除以家族为单位。
        // 家族条目按字体名排序，中文用 Collator(zh) 做拼音友好排序，避免乱序与纯字节序混乱。
        val collator = remember { java.text.Collator.getInstance(java.util.Locale.CHINA) }
        val groups = remember(fonts) {
            fonts.groupBy { it.familyName }.toSortedMap(
                Comparator<String> { a, b ->
                    // 空名垫底，其余按拼音/首字母比较；相等再按原串保证稳定。
                    if (a.isEmpty()) return@Comparator if (b.isEmpty()) 0 else 1
                    if (b.isEmpty()) return@Comparator -1
                    val c = collator.compare(a, b)
                    if (c != 0) c else a.compareTo(b)
                }
            )
        }
        if (groups.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                Text(text = "尚未导入字体，点上方「导入字体…」添加", color = PanelMuted, fontSize = 14.sp)
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items(groups.entries.toList(), key = { it.key }) { (family, list) ->
                    val subs = list.mapNotNull { it.subfamily.trim().ifBlank { null } }.joinToString(" / ")
                    val lang = list.firstOrNull()?.lang
                    val subtitle = listOfNotNull(lang, subs).joinToString(" · ").ifEmpty { "无字重名" }
                    FontSwipeRow(title = family, subtitle = subtitle, onDelete = { onDelete(family) })
                }
            }
        }
    }
}

/**
 * 单个字体行（iOS 左滑路线）：把「文字 + 删除钮」看作一个整体一起左右滑动。
 *  - 整体宽度 = 文字行 x + 删除钮 y；删除钮平时藏在面板右缘外（被 clipToBounds 裁掉，不可见）。
 *  - 向左滑出的位移上限 = y：文字最多向左滑出 y（y 宽被裁剪），删除钮正好被拉进面板并贴住右缘。
 *  - 松手带惯性：按挥手速度衰减缓动一段，衰减停止后吸附到 0（关闭）或 -yPx（删除钮贴右）。
 */
@androidx.compose.runtime.Composable
private fun FontSwipeRow(title: String, subtitle: String, onDelete: () -> Unit) {
    // 删除钮宽度 y：既是行内元素宽，也是左滑-右缘贴边的位移量。
    val yDp = 88.dp
    val yPx = with(LocalDensity.current) { yDp.toPx() }
    // 向右（正向）橡皮筋上限：正常右拉到 0 后，最多额外拉到 rightPx，越远阻力越大，松手回 0。
    val rightPx = with(LocalDensity.current) { 72.dp.toPx() }
    // 向左（负向）越界上限：红块右缘贴屏后，字体+红块主体随手指继续左移，红块右半被拉长（右空增大）最多 leftPx，松手回弹。
    val leftPx = with(LocalDensity.current) { 56.dp.toPx() }
    val scope = rememberCoroutineScope()
    // 整体横向偏移（px，负=向左）。常态值域 [-yPx, 0]：0 关闭，-yPx 删除钮右缘贴屏。
    // 拖动可短暂越界：向右最多到 rightPx（饱和压缩）；向左越过 -yPx 后联动 overBest，主体继续左移且右空被拉长。
    val offsetX = remember { Animatable(0f) }
    // 越界左拉的饱和量（px）：红块自身右移补偿 offsetX 之外、再加宽自身，使红块右缘保持贴屏、右空被拉长 overBest。松手回 0。
    val overBest = remember { Animatable(0f) }
    // 本次拖动「停止前滑动方向」（1=右滑，-1=左滑，0=未动）：每帧按 dragAmount 符号更新，无阈值。
    val dragDir = remember { mutableFloatStateOf(0f) }
    // 本次拖动累计手指位移（负=左，正=右）：常态域 1:1 跟手，越界域经饱和函数压缩产生「越远阻力越大」的橡皮筋。
    val dragAccum = remember { mutableFloatStateOf(0f) }
    // 外层按行内容定高，出界部分用 clipToBounds 裁剪隐藏（删除钮平时在面板右缘外即被裁掉）。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clipToBounds(),
    ) {
        // 文字行：整行随 offsetX 平移。文字列宽度恒定（删除钮不再参与其宽度分配），
        // 因此滑动时字重列表的折行行数不改变、行高稳定，不会忽高忽低。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(yPx, rightPx, leftPx) {
                    // detectHorizontalDragGestures：只在横向越过 slop 后才接手，纵向交给父 LazyColumn 滚动。
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragDir.value = 0f
                            dragAccum.value = offsetX.value // 从当前偏移继续累计手指位移
                        },
                        onDragEnd = {
                            scope.launch {
                                // 左滑到删除区 → 吸附删除态；右滑/未左移 → 回 0；越界右空 → 弹回 0。
                                val target = if (offsetX.value < 0f) {
                                    if (dragDir.value < 0f) -yPx else 0f
                                } else 0f
                                offsetX.animateTo(
                                    target,
                                    spring(
                                        stiffness = Spring.StiffnessMediumLow,
                                        dampingRatio = 0.5f,
                                    ),
                                )
                                overBest.animateTo(0f, spring())
                            }
                        },
                        onDragCancel = {
                            // 纵向胜出（父列表滚动）：本行不消费，无需吸附；把越界部分归位即可。
                            scope.launch { offsetX.snapTo(0f); overBest.snapTo(0f) }
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        if (dragAmount != 0f) {
                            dragDir.value = if (dragAmount > 0f) 1f else -1f
                        }
                        dragAccum.value += dragAmount
                        val d = dragAccum.value
                        if (d < -yPx) {
                            val over = -d - yPx
                            val best = leftPx * (1f - exp(-over / leftPx))
                            scope.launch {
                                overBest.snapTo(best)
                                offsetX.snapTo(-yPx - overBest.value)
                            }
                        } else {
                            val shown = if (d <= 0f) d else rightPx * (1f - exp(-d / rightPx))
                            scope.launch {
                                offsetX.snapTo(shown)
                                overBest.snapTo(0f)
                            }
                        }
                    }
                }
                .padding(start = 16.dp)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 文字列：铺满整行（无删除钮占位），始终以恒定宽度排布。
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(text = title, color = PanelText, fontSize = 15.sp)
                Text(text = subtitle, color = PanelMuted, fontSize = 12.sp)
            }
        }
        // 删除钮：浮在文字行上方的覆盖层，不参与文字行宽度 → 文字折行行数不随滑动改变。
        // 自身偏移 = 文字行位移 offsetX + 出屏隐藏 yPx + 越界补偿 overBest（等价原「Row 平移 + 红块内移」合成），
        // 红块右缘行为与原版一致：平时出屏被裁、左滑贴右、越界仍贴屏且右空拉长。
        val overDp = with(LocalDensity.current) { overBest.value.toDp() }
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset { IntOffset((offsetX.value + yPx + overBest.value).roundToInt(), 0) },
        ) {
            // 红块本体：贴父容器右缘，上下顶满；自身再右移 yPx 藏在面板右缘外（被 clipToBounds 裁掉）。
            // 无圆角、无内边距，滑到极限时红色右缘恰好对齐屏幕边界；越界时加宽只作用于右半（右空拉长）。
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(yDp + overDp)
                    .fillMaxHeight()
                    .background(DeleteRed)
                    .clickable { onDelete() },
                contentAlignment = Alignment.CenterStart,
            ) {
                // 删字靠左定位、固定左空（约 28dp，接近未拉长时的居中视觉）。
                Text(
                    text = "删除",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 28.dp, end = 0.dp),
                )
            }
        }
    }
}

/**
 * 字体管理导入动作按钮：与底部工具栏 ToolItem 同款「图标在上、文字在下」，圆角块按压高亮。
 */
@androidx.compose.runtime.Composable
private fun ManageImportAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (pressed) Color(0x12000000) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = icon, contentDescription = label, tint = PanelText, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = label, fontSize = 12.sp, color = PanelText, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * WIFI 字体导入对话框：打开时在本机起一个临时 HTTP 服务器（局域网地址），
 * 在电脑浏览器打开该地址即可选字体文件上传；上传后私有拷贝 + 解析入库，完成后本面板提示。
 * 无遮罩、带常驻 ✕ 关闭，风格与书架面板一致。
 */
@androidx.compose.runtime.Composable
private fun WifiImportDialog(
    fontRepository: FontRepository,
    onDismiss: () -> Unit,
    onImported: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var addr by remember { mutableStateOf<String?>(null) }
    var startFailed by remember { mutableStateOf(false) }
    var imported by remember { mutableStateOf(false) }

    // 服务器随对话框生命周期启停；每次上传成功即走导入入库并回调刷新。
    val server = remember {
        WifiFontServer(context) { file ->
            scope.launch {
                fontRepository.importFile(file)
                imported = true
                onImported()
            }
        }
    }
    DisposableEffect(Unit) {
        val a = server.start()
        if (a == null) startFailed = true else addr = a
        onDispose { server.stop() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = Modifier
                .width(340.dp)
                .background(PanelBg),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = PanelBg),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // 标题行：左标题居中权重、右侧常驻 ✕ 关闭（对齐书架面板头部）。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "WIFI 导入字体",
                        color = PanelText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "✕",
                        color = PanelText,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .padding(4.dp)
                            .clickable(onClick = onDismiss),
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                when {
                    startFailed -> Text(
                        text = "启动服务失败：请确认平板已连接到 Wi-Fi，然后重试。",
                        color = DeleteRed,
                        fontSize = 14.sp,
                    )
                    addr == null -> Text("正在启动服务器…", color = PanelMuted, fontSize = 14.sp)
                    else -> {
                        Text("平板与电脑需在同一个 Wi-Fi 下，在电脑浏览器打开下面地址：", color = PanelText, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = PanelSlab),
                        ) {
                            SelectionContainer {
                                Text(
                                    text = "http://${addr!!}",
                                    color = PanelText,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "长按地址可复制。打开后点选 .ttf / .otf / .ttc 文件即可上传。",
                            color = PanelMuted,
                            fontSize = 12.sp,
                        )
                        if (imported) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("已收到上传并导入（可继续上传或关闭）", color = Color(0xFF1A7F37), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 极简 HTTP 服务器：局域网内跑一个临时站点，页面提供字体上传。
 *  - GET /            → 返回中文上传页（含多选文件 + fetch 逐个 POST 上传）。
 *  - POST /upload?name=xx → 按 Content-Length 读请求体字节，写进 cacheDir 临时文件，回调 [onUpload]。
 * 仅上传字节，不做 multipart 解析；服务器线程为 daemon，随对话框 dispose 关闭。
 */
private class WifiFontServer(
    private val context: android.content.Context,
    private val onUpload: (java.io.File) -> Unit,
) {
    private val tag = "Orilum.Wifi"
    private val active = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    val uploadDir: java.io.File
        get() = java.io.File(context.cacheDir, "wifi_fonts").apply { mkdirs() }

    /** 启动服务器，返回「ip:port」；无局域网 IPv4 或端口被占用绑定失败返回 null。 */
    fun start(): String? {
        val ip = localIpv4()
        if (ip == null) {
            FileLogger.w(tag, "no lan ipv4")
            return null
        }
        val ss = runCatching { ServerSocket(PORT).apply { reuseAddress = true } }.getOrNull()
        if (ss == null) {
            FileLogger.w(tag, "bind port $PORT failed")
            return null
        }
        serverSocket = ss
        active.set(true)
        acceptThread = Thread {
            try {
                while (active.get()) {
                    val sock = runCatching { ss.accept() }.getOrNull() ?: break
                    Thread { runCatching { handle(sock) } }.start()
                }
            } catch (_: Exception) {
                // accept 抛异常（关闭场景）直接退出循环
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
        return "$ip:$PORT"
    }

    fun stop() {
        active.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { uploadDir.listFiles()?.forEach { it.delete() } }
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            // 手动解析，避免 BufferedInputStream 预读把 POST body 头吃掉：
            // 若用 bufferedReader() 读行，其内部缓冲会提前缓存 body 内容，导致后续再
            // 从输入流顺序读 body 时错位/阻塞。这里统一走同一个 input 读行+读 body。
            val input = BufferedInputStream(s.getInputStream())
            val requestLine = input.readLineIso() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: ""
            val pathRaw = parts.getOrNull(1) ?: "/"
            var contentLength = 0
            while (true) {
                val line = input.readLineIso() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            when {
                method == "GET" && pathRaw.startsWith("/") ->
                    respond(s, 200, "text/html; charset=utf-8", UPLOAD_PAGE.toByteArray(Charsets.UTF_8))
                method == "POST" && pathRaw.startsWith("/upload") ->
                    handleUpload(input, s, pathRaw, contentLength)
                else ->
                    respond(s, 404, "text/plain; charset=utf-8", "not found".toByteArray(Charsets.UTF_8))
            }
        }
    }

    /** 手动读一行（\r\n / \n 结尾），返回去掉行尾分界符的内容；流结束返回 null。 */
    private fun InputStream.readLineIso(): String? {
        val sb = StringBuilder()
        while (true) {
            val c = read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(c.toChar())
        }
    }

    private fun handleUpload(input: InputStream, s: Socket, pathRaw: String, contentLength: Int) {
        var name = pathRaw.substringAfter("name=", "")
            .let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault("") }
        // 白名单过滤，防止路径穿越/超长文件名。
        name = name.filter { it.isLetterOrDigit() || it in "-_." }.take(64)
        val dest = java.io.File(uploadDir, name.ifBlank { "font_${System.currentTimeMillis()}.ttf" })
        var ok = false
        try {
            dest.outputStream().use { out ->
                val buf = ByteArray(8192)
                var remaining = contentLength
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(buf.size, remaining))
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
            ok = dest.exists() && dest.length() > 0
        } catch (e: Exception) {
            FileLogger.e(tag, "upload ${dest.name} err ${e.message}")
            ok = false
        }
        if (ok) {
            FileLogger.i(tag, "uploaded ${dest.name} (${dest.length()}B)")
            runCatching { onUpload(dest) }
            respond(s, 200, "text/html; charset=utf-8", okPage(name).toByteArray(Charsets.UTF_8))
        } else {
            runCatching { dest.delete() }
            respond(s, 400, "text/html; charset=utf-8", "上传失败，请重试".toByteArray(Charsets.UTF_8))
        }
    }

    private fun respond(s: Socket, status: Int, contentType: String, body: ByteArray) {
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; else -> "Error" }
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        runCatching {
            s.getOutputStream().use { it.write(head.toByteArray()); it.write(body); it.flush() }
        }
    }

    private fun localIpv4(): String? {
        runCatching {
            java.util.Collections.list(NetworkInterface.getNetworkInterfaces()).forEach { ni ->
                if (ni.isUp && !ni.isLoopback) {
                    java.util.Collections.list(ni.inetAddresses).forEach { a ->
                        if (a is Inet4Address && !a.isLoopbackAddress) {
                            val h = a.hostAddress
                            if (h != null && !h.startsWith("127.")) return h
                        }
                    }
                }
            }
        }
        return null
    }

    private fun okPage(name: String): String {
        val safe = name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return "<!DOCTYPE html><html lang=\"zh\"><meta charset=\"utf-8\"><body>" +
            "<p style='color:#1a7f37'>$safe 已上传到平板并导入</p>" +
            "<p><a href='/'>继续上传</a></p></body></html>"
    }

    private companion object {
        /** 固定上传端口：同一局域网内每次打开地址不变，电脑端无需重输。 */
        const val PORT = 8080

        val UPLOAD_PAGE = """
            <!DOCTYPE html><html lang="zh"><head><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>向平板导入字体</title><style>
            body{font-family:system-ui,sans-serif;max-width:560px;margin:40px auto;padding:0 20px;color:#222}
            h1{font-size:20px}.drop{border:2px dashed #999;border-radius:12px;padding:40px 20px;text-align:center;color:#666}
            .list{margin-top:20px}.item{display:flex;justify-content:space-between;padding:8px 0;border-bottom:1px solid #eee}
            .ok{color:#1a7f37}.err{color:#c0392b}
            </style></head><body>
            <h1>向平板导入字体</h1>
            <p>选择字体文件（.ttf / .otf / .ttc / .otc），可一次选多个，逐个上传。</p>
            <div class="drop" id="drop">点击选择文件</div>
            <input type="file" id="file" multiple accept=".ttf,.otf,.ttc,.otc" style="display:none">
            <div class="list" id="list"></div>
            <script>
            const drop=document.getElementById('drop'),file=document.getElementById('file'),list=document.getElementById('list');
            drop.onclick=()=>file.click();
            file.onchange=async()=>{
              const files=[...file.files];
              for(const f of files){
                const row=document.createElement('div');row.className='item';
                row.innerHTML='<span>'+f.name+'</span><span class="ok">上传中…</span>';list.appendChild(row);
                const label=row.lastChild;
                try{
                  const r=await fetch('/upload?name='+encodeURIComponent(f.name),{method:'POST',body:f});
                  if(!r.ok) throw new Error('HTTP '+r.status);
                  label.className='ok';label.textContent='成功';
                }catch(e){label.className='err';label.textContent=('失败');}
              }
              file.value='';
            };
            </script></body></html>
        """.trimIndent()
    }
}