package com.orilum

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orilum.data.book.AppDatabase
import com.orilum.data.book.Book
import com.orilum.data.book.BookImporter
import com.orilum.data.book.BookRepository
import com.orilum.data.settings.ReaderSettingsStore
import com.orilum.ui.reader.EXTRA_BOOK_ID
import com.orilum.ui.reader.EXTRA_BOOK_PATH
import com.orilum.ui.reader.ReaderActivity
import com.orilum.ui.theme.OrilumTheme
import com.orilum.util.FileLogger
import kotlinx.coroutines.launch
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
        // 书架顶栏/底栏为深灰 #303030，系统状态栏/导航栏图标必须用浅色，否则深底深字看不清。
        // enableEdgeToEdge 默认按浅色主题给浅色图标，这里在 onCreate 用 decorView 强制改为浅色。
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContent {
            OrilumTheme {
                var gridMode by rememberSaveable { mutableStateOf(false) }
                val pickEpub = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    uri?.let { onBookPicked(it) }
                }

                val books by booksFlow.collectAsStateWithLifecycle(initialValue = emptyList())

                ShelfScreen(
                    books = books,
                    gridMode = gridMode,
                    onToggleView = { gridMode = !gridMode },
                    onImport = { pickEpub.launch(arrayOf("application/epub+zip")) },
                    onEdit = { toast("编辑（占位）") },
                    onSettings = { toast("设置（占位）") },
                    onOpenBook = { openReader(it) },
                    onOpenSample = { openSampleBook() },
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

/** 书架顶栏与底栏配色，与阅读页工具栏一致（深灰底 + 浅灰前景）。 */
private val BarGray = Color(0xFF303030)
private val BarGrayFg = Color(0xFFF5F5F5)

/**
 * 底部工具栏按钮：图标在上、文字在下，占满平分一行；整项可点（含文字），按压时整块变白高亮。
 * 与阅读页 reader.html 的 .tool（icon 上、label 下、:active 高亮）视觉与交互一致。
 * 复用 MutableInteractionSource 监听按压态驱动背景，而非 Material ripple 胶囊，保持两界面同一套观感。
 */
@androidx.compose.runtime.Composable
private fun RowScope.ToolItem(icon: String, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 外层占 1/4 平分整行并承接点击：保证「图标+文字整项都可点」。
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 按钮本体：紧凑圆角块，四周留白；按压高亮只出现在此块内（与阅读页 .tool 的圆角 :active 一致），不铺满整项。
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (pressed) Color(0x1FFFFFFF) else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = icon, fontSize = 18.sp, color = BarGrayFg)
                Text(text = label, fontSize = 10.sp, color = BarGrayFg)
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
) {
    // 系统栏图标浅色已在 MainActivity.onCreate 统一设置（见 enableEdgeToEdge 后），此处无需重复。

    Scaffold(
        topBar = {
            // 标题栏深灰 #303030，与阅读页工具栏同色；状态栏区域由 Material3 自动并入同色。
            TopAppBar(
                title = { Text(text = "书架") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BarGray,
                    titleContentColor = BarGrayFg,
                ),
            )
        },
        bottomBar = {
            // 底部工具栏：与阅读页 #bar-bottom 同语义——深灰背景铺满到屏幕底（含系统手势条区域），
            // 按钮行固定 56dp 置于其上，底部用「系统导航条安全区」高度撑开，按钮不与上滑提示条相碰。
            Column(modifier = Modifier.fillMaxWidth().background(BarGray)) {
                Row(modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    ToolItem(icon = if (gridMode) "▦" else "☰", label = "封面/列表", onClick = onToggleView)
                    ToolItem(icon = "＋", label = "导入", onClick = onImport)
                    ToolItem(icon = "✎", label = "编辑", onClick = onEdit)
                    ToolItem(icon = "⚙", label = "设置", onClick = onSettings)
                }
                // 底部安全间距：该设备为手势导航，WindowInsets.navigationBars 常返回 0/极小，无法靠动态值撑开；
                // 用固定安全高度让按钮行与系统上滑手势条保持清晰间距（观感对齐阅读页底栏）。
                Spacer(modifier = Modifier.height(10.dp))
            }
        },
    ) { padding ->
        if (books.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
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
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(books, key = { it.id }) { book ->
                    BookCard(book = book, onClick = { onOpenBook(book) })
                }
            }
        }
    }
}