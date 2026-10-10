package com.activity.manager.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.activity.manager.ActivityRecord
import com.activity.manager.RecordsRepository
import com.activity.manager.ShizukuState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ACTIVITY_ITEM_CONTENT_TYPE = 0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityMonitorScreen(
    records: List<ActivityRecord>,
    loading: Boolean,
    errorMessage: String?,
    permissionState: ShizukuState,
    permissionText: String,
    statusHint: String?,
    onRequestShizuku: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onOpenHistory: () -> Unit,
    onCopyActivity: (String) -> Unit,
    onDisablePackage: (String) -> Unit,
    onDisableActivity: (String, String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    // NOTE: must be keyed on `records`. `records` is a plain parameter (not a State), so an
    // unkeyed remember{} would capture the initial empty list and never recompute, leaving the
    // list permanently at "0 activities" even though the read returned data.
    val visible = remember(records, searchQuery) {
        RecordsRepository.filterRecords(records, searchQuery)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Activity Monitor") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Surface(
                        onClick = onRequestShizuku,
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        modifier = Modifier.padding(horizontal = 4.dp),
                        content = {
                            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                Text(
                                    text = if (permissionState == ShizukuState.REQUESTING) "..."
                                    else permissionText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    )
                    IconButton(onClick = onExport) {
                        Icon(Icons.Filled.Share, contentDescription = "导出报告")
                    }
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "禁用历史")
                    }
                }
            )
        },
        floatingActionButton = {
            // Manual refresh only: nothing subscribes to the log files, and reading them
            // costs a privileged shell round trip, so the user decides when to re-read.
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FloatingActionButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
                FloatingActionButton(onClick = onClear) {
                    Icon(Icons.Filled.Clear, contentDescription = "清空")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search package or activity...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true
            )

            // One banner for whichever condition blocks reading, so the cause is never hidden.
            val notice = errorMessage ?: statusHint
            if (notice != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = "${visible.size} activities" +
                    if (searchQuery.isNotBlank() && visible.size != records.size)
                        " (filtered from ${records.size})" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )

            if (visible.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when {
                            searchQuery.isNotBlank() -> "No matches"
                            loading -> "读取中..."
                            notice != null -> "暂无可显示内容，见上方提示"
                            else -> "暂无记录：请确认已在 LSPosed 勾选作用域并重启目标应用"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(
                        items = visible,
                        key = { it.id },
                        contentType = { ACTIVITY_ITEM_CONTENT_TYPE }
                    ) { record ->
                        ActivityItem(
                            record = record,
                            onCopyActivity = { onCopyActivity(record.activityClass) },
                            onDisablePackage = { onDisablePackage(record.packageName) },
                            onDisableActivity = {
                                onDisableActivity(record.packageName, record.activityClass)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ActivityItem(
    record: ActivityRecord,
    onCopyActivity: () -> Unit,
    onDisablePackage: () -> Unit,
    onDisableActivity: () -> Unit
) {
    val df = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val timeText = remember(record.timestamp) { df.format(Date(record.timestamp)) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { onCopyActivity() }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = record.packageName,
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
                text = record.activityClass,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            text = "Act",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .clickable { onDisableActivity() }
                .padding(horizontal = 6.dp, vertical = 4.dp)
        )
        Text(
            text = "Pkg",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .clickable { onDisablePackage() }
                .padding(horizontal = 6.dp, vertical = 4.dp)
        )
    }
}
