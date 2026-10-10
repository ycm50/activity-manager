package com.activity.manager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.activity.manager.DisableHistoryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * History of `pm disable` operations, each restorable.
 *
 * Restoring uses `pm default-state`, the inverse of `disable` (see [ComponentDisabler]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisableHistoryScreen(
    entries: List<DisableHistoryEntry>,
    busy: Boolean,
    /** True when 更新 has run in this session, i.e. the restored flags are trustworthy. */
    synced: Boolean,
    /** Non-null while 更新 is running, e.g. "更新中 3/12". */
    syncProgress: String?,
    onBack: () -> Unit,
    onRestore: (DisableHistoryEntry) -> Unit,
    onRestoreAll: () -> Unit,
    onSync: () -> Unit,
    onClearRestored: () -> Unit
) {
    BackHandler(onBack = onBack)

    val pendingCount = entries.count { !it.restored }
    val restoredCount = entries.size - pendingCount

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("禁用历史") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    TextButton(onClick = onSync, enabled = !busy) {
                        Text(
                            text = if (busy) "更新中..." else "更新",
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    if (pendingCount > 0) {
                        TextButton(onClick = onRestoreAll, enabled = !busy) {
                            Text("全部恢复", color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    if (restoredCount > 0) {
                        TextButton(onClick = onClearRestored, enabled = !busy) {
                            Text("清除已恢复", color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (syncProgress != null) {
                    syncProgress
                } else if (entries.isEmpty()) {
                    "还没有禁用记录"
                } else {
                    "共 ${entries.size} 条，待恢复 ${pendingCount} 条，已恢复 ${restoredCount} 条" +
                        if (!synced && restoredCount > 0) "（清除已恢复前请先点「更新」）" else ""
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )

            if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "通过列表里的 Act / Pkg 禁用组件后，可在此恢复",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        HistoryRow(
                            entry = entry,
                            busy = busy,
                            onRestore = { onRestore(entry) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: DisableHistoryEntry,
    busy: Boolean,
    onRestore: () -> Unit
) {
    val df = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }
    val timeText = remember(entry.disabledAt) { df.format(Date(entry.disabledAt)) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (entry.isActivity) "Activity" else "Package",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entry.isActivity) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.packageName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = entry.component,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (entry.restored) {
            Text(
                text = "已恢复",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        } else {
            TextButton(onClick = onRestore, enabled = !busy) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("恢复")
            }
        }
    }
}
