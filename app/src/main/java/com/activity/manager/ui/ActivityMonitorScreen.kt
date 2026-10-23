package com.activity.manager.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.activity.manager.ActivityRecord
import com.activity.manager.AppInfoResolver
import com.activity.manager.MonitorFilters
import com.activity.manager.PackageGroup
import com.activity.manager.RiskLevel
import com.activity.manager.ShizukuState
import com.activity.manager.TimeWindow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ACTIVITY_ITEM_CONTENT_TYPE = 0
private const val GROUP_HEADER_CONTENT_TYPE = 1

/**
 * Live list of recorded activity launches, with filtering, grouping and multi-select.
 *
 * All state arrives in [ui]; this composable only renders it and forwards intents, which is
 * what makes rotation-safe behaviour possible (the ViewModel survives, the composition does
 * not).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityMonitorScreen(
    ui: MonitorUiState,
    permissionState: ShizukuState,
    permissionText: String,
    statusHint: String?,
    /** Icon + label resolver; cached inside the ViewModel. */
    appInfo: (String) -> AppInfoResolver.AppInfo,
    /** Predicate backing the "排除系统应用" filter. */
    isSystemPackage: (String) -> Boolean,
    onRequestShizuku: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onOpenHistory: () -> Unit,
    onQueryChange: (String) -> Unit,
    onTimeWindowChange: (TimeWindow) -> Unit,
    onToggleExcludeSystem: () -> Unit,
    onToggleGrouping: () -> Unit,
    onToggleExpanded: (String) -> Unit,
    onCopyActivity: (String) -> Unit,
    onDisablePackage: (String) -> Unit,
    onDisableActivity: (String, String) -> Unit,
    onBeginSelection: (String) -> Unit,
    onToggleSelected: (String) -> Unit,
    onExitSelection: () -> Unit,
    onSelectAll: (List<String>) -> Unit,
    /** 按包全选：the group header's long-press adds every activity of that package. */
    onSelectPackage: (List<String>) -> Unit,
    onDisableSelected: () -> Unit,
    onConfirmDisable: () -> Unit,
    onDismissDisable: () -> Unit
) {
    // Keyed on the inputs: `records` is a plain parameter (not a State), so an unkeyed
    // remember{} would capture the initial empty list and never recompute — a bug this screen
    // already had once.
    val now = remember(ui.records, ui.timeWindow) { System.currentTimeMillis() }
    val visible = remember(ui.records, ui.filter, now) {
        MonitorFilters.apply(ui.records, ui.filter, now, isSystemPackage)
    }
    val groups = remember(visible, ui.groupByPackage) {
        if (ui.groupByPackage) MonitorFilters.groupByPackage(visible) else emptyList()
    }
    val notice = ui.errorMessage ?: statusHint

    Scaffold(
        topBar = {
            if (ui.selectionMode) {
                TopAppBar(
                    title = { Text("已选 " + ui.selectedCount) },
                    navigationIcon = {
                        IconButton(onClick = onExitSelection) {
                            Icon(Icons.Filled.Clear, contentDescription = "取消选择")
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = { onSelectAll(visible.map { it.key }) },
                            enabled = visible.isNotEmpty()
                        ) {
                            Text("全选", color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        TextButton(
                            onClick = onDisableSelected,
                            enabled = ui.selectedCount > 0
                        ) {
                            Text("禁用", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            } else {
                TopAppBar(
                    title = { Text("Activity Monitor") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    actions = {
                        if (ui.loading) {
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
            }
        },
        floatingActionButton = {
            // Manual refresh only: nothing subscribes to the log files, and reading them
            // costs a privileged shell round trip, so the user decides when to re-read.
            if (!ui.selectionMode) {
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
                value = ui.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search package or activity...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true
            )

            FilterBar(
                timeWindow = ui.timeWindow,
                excludeSystem = ui.excludeSystem,
                groupByPackage = ui.groupByPackage,
                onTimeWindowChange = onTimeWindowChange,
                onToggleExcludeSystem = onToggleExcludeSystem,
                onToggleGrouping = onToggleGrouping
            )

            // One banner for whichever condition blocks reading, so the cause is never hidden.
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

            Text(
                text = "${visible.size} activities" +
                    if (visible.size != ui.records.size) " (filtered from ${ui.records.size})" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )

            if (visible.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = when {
                            ui.query.isNotBlank() -> "No matches"
                            ui.loading -> "读取中..."
                            notice != null -> "暂无可显示内容，见上方提示"
                            else -> "暂无记录：请确认已在 LSPosed 勾选作用域并重启目标应用"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (ui.groupByPackage) {
                GroupedList(
                    modifier = Modifier.weight(1f),
                    groups = groups,
                    expanded = ui.expanded,
                    selectionMode = ui.selectionMode,
                    selected = ui.selected,
                    appInfo = appInfo,
                    onToggleExpanded = onToggleExpanded,
                    onSelectPackage = onSelectPackage,
                    onItemClick = { record ->
                        if (ui.selectionMode) onToggleSelected(record.key)
                        else onCopyActivity(record.activityClass)
                    },
                    onItemLongClick = { record -> onBeginSelection(record.key) },
                    onDisableActivity = onDisableActivity,
                    onDisablePackage = onDisablePackage
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(
                        items = visible,
                        // Keyed on the raw log line, not a list index: the list is
                        // newest-first, so an index key renumbered every item whenever a new
                        // event arrived and the whole list was treated as changed.
                        key = { it.key },
                        contentType = { ACTIVITY_ITEM_CONTENT_TYPE }
                    ) { record ->
                        ActivityItem(
                            record = record,
                            info = appInfo(record.packageName),
                            selectionMode = ui.selectionMode,
                            selected = record.key in ui.selected,
                            onClick = {
                                if (ui.selectionMode) onToggleSelected(record.key)
                                else onCopyActivity(record.activityClass)
                            },
                            onLongClick = { onBeginSelection(record.key) },
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

    ui.pending?.let { pending ->
        DisableConfirmationDialog(
            pending = pending,
            onConfirm = onConfirmDisable,
            onDismiss = onDismissDisable
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(
    timeWindow: TimeWindow,
    excludeSystem: Boolean,
    groupByPackage: Boolean,
    onTimeWindowChange: (TimeWindow) -> Unit,
    onToggleExcludeSystem: () -> Unit,
    onToggleGrouping: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Only the compact window set lives inline; anything longer would push the list away.
        listOf(TimeWindow.ALL, TimeWindow.ONE_HOUR, TimeWindow.TODAY).forEach { window ->
            FilterChip(
                selected = timeWindow == window,
                onClick = { onTimeWindowChange(window) },
                label = { Text(window.label, fontSize = 11.sp) }
            )
        }
        FilterChip(
            selected = excludeSystem,
            onClick = onToggleExcludeSystem,
            label = { Text("非系统", fontSize = 11.sp) }
        )
        FilterChip(
            selected = groupByPackage,
            onClick = onToggleGrouping,
            label = { Text("分组", fontSize = 11.sp) }
        )
    }
}

@Composable
private fun GroupedList(
    modifier: Modifier,
    groups: List<PackageGroup>,
    expanded: Set<String>,
    selectionMode: Boolean,
    selected: Set<String>,
    appInfo: (String) -> AppInfoResolver.AppInfo,
    onToggleExpanded: (String) -> Unit,
    onSelectPackage: (List<String>) -> Unit,
    onItemClick: (ActivityRecord) -> Unit,
    onItemLongClick: (ActivityRecord) -> Unit,
    onDisableActivity: (String, String) -> Unit,
    onDisablePackage: (String) -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        groups.forEach { group ->
            // `item()` takes a plain contentType value; only `items()` takes a lambda.
            item(key = "group:" + group.packageName, contentType = GROUP_HEADER_CONTENT_TYPE) {
                GroupHeader(
                    group = group,
                    info = appInfo(group.packageName),
                    expanded = group.packageName in expanded,
                    onClick = { onToggleExpanded(group.packageName) },
                    onLongClick = { onSelectPackage(group.records.map { it.key }) }
                )
            }
            if (group.packageName in expanded) {
                items(
                    items = group.records,
                    key = { it.key },
                    contentType = { ACTIVITY_ITEM_CONTENT_TYPE }
                ) { record ->
                    ActivityItem(
                        record = record,
                        info = appInfo(record.packageName),
                        selectionMode = selectionMode,
                        selected = record.key in selected,
                        onClick = { onItemClick(record) },
                        onLongClick = { onItemLongClick(record) },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupHeader(
    group: PackageGroup,
    info: AppInfoResolver.AppInfo,
    expanded: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppAvatar(info)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = info.label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = group.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = if (expanded) "收起 " + group.size else group.size.toString() + " 条",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActivityItem(
    record: ActivityRecord,
    info: AppInfoResolver.AppInfo,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
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
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        } else {
            AppAvatar(info)
            Spacer(Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = info.label,
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
            // Second line only when the hook actually captured something, so ordinary rows
            // stay one line tall.
            val subtitle = listOfNotNull(
                if (record.isNewIntent) "新 Intent" else null,
                record.caller?.let { "← " + it },
                record.intentAction?.let { it.substringAfterLast('.') }
            ).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Hidden while selecting: a stray tap must not disable anything mid-selection.
        if (!selectionMode) {
            Text(
                text = "Act",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .combinedClickableCompat(onClick = onDisableActivity)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
            Text(
                text = "Pkg",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .combinedClickableCompat(onClick = onDisablePackage)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun AppAvatar(info: AppInfoResolver.AppInfo) {
    val icon = info.icon
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = Modifier.size(28.dp)
        )
    } else {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = info.initial,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun DisableConfirmationDialog(
    pending: PendingDisable,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("确认禁用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = {
            Text(if (pending.allowed.size == 1) "确认禁用" else "确认禁用 ${pending.allowed.size} 项")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                pending.allowed.take(MAX_DIALOG_ITEMS).forEach { item ->
                    Column {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.reason != null) {
                            Text(
                                text = "⚠ " + item.reason,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (item.level == RiskLevel.WARN) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
                if (pending.allowed.size > MAX_DIALOG_ITEMS) {
                    Text(
                        text = "…还有 ${pending.allowed.size - MAX_DIALOG_ITEMS} 项",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (pending.blocked.isNotEmpty()) {
                    Text(
                        text = "已自动跳过 ${pending.blocked.size} 项系统关键组件（不会执行）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    )
}

/** [combinedClickable] without the long-press, for cases that only need an onClick. */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick)

private const val MAX_DIALOG_ITEMS = 6
