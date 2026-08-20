package com.folioepub.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * 应用根主题（M0 先用 Material3 默认，后续按需自定义）。
 */
@Composable
fun FolioEpubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        content = content,
    )
}