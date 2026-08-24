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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
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

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    /** 字体池仓库（导入式，接 FontDao；跨重启持久）。 */
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
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    uri?.let { onBookPicked(it) }
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
                    onOpenSample = { openSampleBook() },
                    fontRepository = fontRepository,
                    settingsOpen = showSettings,
                    onDismissSettings = { showSettings = false },
                )
            }
        }
    }

    /** 短提示（占位按钮/操作的统一反馈）。 */
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** SAF 选书后：导入书架。 */
    private fun onBookPicked(uri: android.net.Uri) {
        lifecycleScope.launch {
            val result = importer.import(uri)
            val msg = if (result.isSuccess) "已加入书架"
            else "导入失败：${result.exceptionOrNull()?.message}"
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

    /** 无 extra → ReaderActivity 回退内置示例书。 */
    private fun openSampleBook() {
        startActivity(Intent(this, ReaderActivity::class.java))
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
    onOpenSample: () -> Unit,
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
                    ToolItem(icon = if (gridMode) Icons.Default.List else ImageVector.vectorResource(R.drawable.ic_grid), label = "封面/列表", onClick = onToggleView)
                    ToolItem(icon = Icons.Default.Add, label = "导入", onClick = onImport)
                    ToolItem(icon = Icons.Default.Edit, label = "编辑", onClick = onEdit)
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
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "书架上还没有书", style = MaterialTheme.typography.bodyLarge)
                        Button(
                            onClick = onOpenSample,
                            modifier = Modifier.padding(top = 20.dp),
                        ) {
                            Text(text = "先看内置示例书（自测渲染）")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(books, key = { it.id }) { book ->
                        BookCard(book = book, onClick = { onOpenBook(book) })
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

    // 系统返回：先逐级退栈，栈底则关闭整个抽屉。
    BackHandler(enabled = show) {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else onDismiss()
    }

    // 字体池共享状态：主页显示已导入数量，字体子页导入/删除后刷新。
    val scope = rememberCoroutineScope()
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

    AnimatedVisibility(
        visible = show,
        modifier = Modifier.fillMaxSize(),
        enter = slideInHorizontally { it },
        exit = slideOutHorizontally { it },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
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
                SettingsRoute.Fonts -> ManageFontsPage(
                    fonts = fonts,
                    onImport = { importLauncher.launch(FONT_MIMES) },
                    onDelete = { face -> scope.launch { fontRepository.delete(face); refreshFonts() } },
                    modifier = Modifier.weight(1f),
                )
            }
            }
        }
    }
}

/** 书架设置一级页：列出可下钻的设置项（当前只有「字体」）。 */
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
    onDelete: (FontFace) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(PanelBg)) {
        // 导入区：扁平「导入字体…」按钮
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "导入字体…",
                color = PanelText,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(PanelSlab)
                    .clickable(onClick = onImport)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            thickness = 1.dp,
            color = PanelDivider,
        )
        if (fonts.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                Text(text = "尚未导入字体，点上方「导入字体…」添加", color = PanelMuted, fontSize = 14.sp)
            }
        } else {
            fonts.forEach { face ->
                key(face.id) { FontSwipeRow(face = face, onDelete = onDelete) }
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
private fun FontSwipeRow(face: FontFace, onDelete: (FontFace) -> Unit) {
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
        // 整条「文字 + 删除钮」作为单层行，统一平移；起点在最右侧（删除钮在面板外）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(yPx, rightPx, leftPx) {
                    detectDragGestures(
                        onDragStart = {
                            dragDir.value = 0f
                            dragAccum.value = offsetX.value // 从当前偏移继续累计手指位移
                        },
                        onDragEnd = {
                            scope.launch {
                                // 左滑到删除区 → 吸附删除态(-yPx)；右滑/未左移 → 回 0；越界右空 → 弹回 0。
                                // offsetX 用低阻尼比 spring：惯性冲到目标位置时适度跑过头，再由弹力拉回（iOS 手感）。
                                val target = if (offsetX.value < 0f) {
                                    if (dragDir.value < 0f) -yPx else 0f
                                } else {
                                    0f
                                }
                                offsetX.animateTo(
                                    target,
                                    spring(
                                        stiffness = Spring.StiffnessMediumLow,
                                        dampingRatio = 0.5f, // <1 → 过冲后回弹
                                    ),
                                )
                                overBest.animateTo(0f, spring())
                            }
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        // 记录最近一帧的滑动方向（符号即可）；touch slop 已确保有真实位移。
                        if (dragAmount.x != 0f) {
                            dragDir.value = if (dragAmount.x > 0f) 1f else -1f
                        }
                        dragAccum.value += dragAmount.x
                        // 显示位移三域：
                        //  - [-yPx, 0] 内 1:1 跟手（红块右缘未贴屏、右空不动）；
                        //  - 越过 -yPx：字体+红块主体继续左移 overBest，红块加宽保持右缘贴屏 → 右空被拉长；
                        //  - >0 右拉：整行饱和压缩到 (0, rightPx)。
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
            // 文字列：占据行内扣除删除钮后的宽度。
            Column(modifier = Modifier.weight(1f)) {
                Text(text = face.displayName, color = PanelText, fontSize = 15.sp)
                Text(
                    text = listOfNotNull(face.subfamily, face.lang).ifEmpty { listOf("无字重名") }.joinToString(" · "),
                    color = PanelMuted,
                    fontSize = 12.sp,
                )
            }
            // 删除钮：占整行末尾；自身再右移 yPx 藏在面板右缘外（被 clipToBounds 裁掉），左滑时随之进入。
            // 贴右缘、上下顶满：无圆角、无内边距，滑到极限时红色右缘恰好对齐屏幕边界。
            // 越界左拉时，红块 offset 额外多加 overBest 补偿 offsetX 的继续左移，且自身加宽 overBest
            //  → 字体+红块主体左移而红块右缘仍贴屏，右空被拉长（橡皮筋），松手弹回。
            val overDp = with(LocalDensity.current) { overBest.value.toDp() }
            Box(
                modifier = Modifier
                    .offset { IntOffset((yPx + overBest.value).roundToInt(), 0) }
                    .width(yDp + overDp)
                    .fillMaxHeight()
                    .background(DeleteRed)
                    .clickable { onDelete(face) },
                contentAlignment = Alignment.CenterStart,
            ) {
                // 删字靠左定位、固定左空（约 28dp，接近未拉长时的居中视觉）。
                // 红块加宽只作用于右半 → 只有右空被拉长，左空不变。
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