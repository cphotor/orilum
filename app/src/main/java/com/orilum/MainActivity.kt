package com.orilum

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orilum.data.book.AppDatabase
import com.orilum.data.book.Book
import com.orilum.data.book.BookImporter
import com.orilum.data.book.BookRepository
import com.orilum.ui.reader.EXTRA_BOOK_ID
import com.orilum.ui.reader.EXTRA_BOOK_PATH
import com.orilum.ui.reader.ReaderActivity
import com.orilum.ui.theme.OrilumTheme
import kotlinx.coroutines.launch

/**
 * 自定义书架（M0 闭环）：
 * 选书（SAF）→ 解析（自建 [BookImporter]/[BookRepository]）→ 渲染（打开 [ReaderActivity]）。
 */
class MainActivity : ComponentActivity() {

    private val repository by lazy { BookRepository(AppDatabase.get(this).bookDao()) }
    private val importer by lazy { BookImporter(this, repository) }
    private val booksFlow by lazy { repository.books() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            OrilumTheme {
                val pickEpub = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    uri?.let { onBookPicked(it) }
                }

                val books by booksFlow.collectAsStateWithLifecycle(initialValue = emptyList())

                Scaffold(
                    floatingActionButton = {
                        FloatingActionButton(onClick = {
                            pickEpub.launch(arrayOf("application/epub+zip"))
                        }) {
                            Text(text = "＋", style = MaterialTheme.typography.titleLarge)
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
                                    onClick = { openSampleBook() },
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
                                BookCard(book = book, onClick = { openReader(book) })
                            }
                        }
                    }
                }
            }
        }
    }

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