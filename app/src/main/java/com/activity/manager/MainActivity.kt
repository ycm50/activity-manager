package com.activity.manager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.activity.manager.ui.ActivityMonitorScreen
import com.activity.manager.ui.ActivityMonitorViewModel
import com.activity.manager.ui.DisableHistoryScreen
import com.activity.manager.ui.theme.LspoTheme

/**
 * Single-activity shell.
 *
 * Only three responsibilities are left here, because everything stateful moved into
 * [ActivityMonitorViewModel] when the screen grew a filter panel and multi-select:
 *
 * - navigation between the live monitor and the disable history,
 * - the privileged-permission controller (it is activity-scoped by design), and
 * - one-shot messages turned into Toasts ([ActivityMonitorViewModel.messages]).
 *
 * The ViewModel also survives configuration changes, so a freshly read record list, the
 * search text and any in-progress selection are no longer thrown away on rotation.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: ActivityMonitorViewModel by viewModels()
    private lateinit var permissionController: ShizukuPermissionController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        permissionController = ShizukuPermissionController(this)
        // Check (never prompt) on open: the toolbar button is the only way to request.
        permissionController.refreshStatus()

        setContent {
            var showHistory by remember { mutableStateOf(false) }
            val ui = viewModel.state

            // The ViewModel cannot show a Toast (it must not hold an Activity), so it emits
            // messages and the composition shows them.
            LaunchedEffect(Unit) {
                viewModel.messages.collect { message ->
                    Toast.makeText(
                        this@MainActivity,
                        message.text,
                        if (message.long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                    ).show()
                }
            }

            LspoTheme {
                if (showHistory) {
                    DisableHistoryScreen(
                        entries = ui.history,
                        busy = ui.busy,
                        synced = ui.synced,
                        syncProgress = ui.syncProgress,
                        onBack = { showHistory = false },
                        onRestore = viewModel::restore,
                        onRestoreAll = viewModel::restoreAll,
                        onSync = viewModel::syncHistory,
                        onClearRestored = viewModel::clearRestored
                    )
                } else {
                    ActivityMonitorScreen(
                        ui = ui,
                        permissionState = permissionController.state,
                        permissionText = permissionController.label,
                        statusHint = permissionController.hint,
                        appInfo = viewModel::appInfo,
                        isSystemPackage = viewModel::isSystemPackage,
                        onRequestShizuku = { permissionController.request() },
                        onRefresh = viewModel::refresh,
                        onClear = viewModel::clearRecords,
                        onExport = viewModel::export,
                        onOpenHistory = { showHistory = true },
                        onQueryChange = viewModel::setQuery,
                        onTimeWindowChange = viewModel::setTimeWindow,
                        onToggleExcludeSystem = viewModel::toggleExcludeSystem,
                        onToggleGrouping = viewModel::toggleGrouping,
                        onToggleExpanded = viewModel::toggleExpanded,
                        onCopyActivity = ::copyToClipboard,
                        onDisablePackage = viewModel::disablePackage,
                        onDisableActivity = viewModel::disableActivity,
                        onBeginSelection = viewModel::beginSelection,
                        onToggleSelected = viewModel::toggleSelected,
                        onExitSelection = viewModel::exitSelection,
                        onSelectAll = { keys -> viewModel.selectAll(keys) },
                        onSelectPackage = { keys -> viewModel.selectPackage(keys) },
                        onDisableSelected = viewModel::disableSelected,
                        onConfirmDisable = viewModel::confirmPending,
                        onDismissDisable = viewModel::dismissPending
                    )
                }
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("activity_class", text))
        Toast.makeText(this, "Copied: " + text, Toast.LENGTH_SHORT).show()
    }
}
