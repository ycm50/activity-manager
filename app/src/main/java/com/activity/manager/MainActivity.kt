package com.activity.manager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.activity.manager.ui.ActivityMonitorScreen
import com.activity.manager.ui.DisableHistoryScreen
import com.activity.manager.ui.theme.LspoTheme
import kotlinx.coroutines.launch

/**
 * Single-activity UI. Owns navigation between the live monitor and the disable history,
 * and wires the collaborators:
 *
 * - [ShizukuPermissionController] — privilege state machine
 * - [ShizukuShell] / [HookEventReader] — read hook logs back through Shizuku
 * - [ComponentDisabler] — parameterised `pm disable` / `pm default-state`
 * - [DisableHistoryStore] — persisted log of what was disabled, so it can be restored
 * - [ReportExporter] — Downloads export
 */
class MainActivity : ComponentActivity() {

    private lateinit var permissionController: ShizukuPermissionController
    private lateinit var disabler: ComponentDisabler
    private lateinit var historyStore: DisableHistoryStore
    private lateinit var exporter: ReportExporter
    private lateinit var reader: HookEventReader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        permissionController = ShizukuPermissionController(this)
        disabler = ComponentDisabler()
        historyStore = DisableHistoryStore(this)
        exporter = ReportExporter(this)
        reader = HookEventReader(ShizukuShell())
        // Check (never prompt) on open: the toolbar button is the only way to request.
        permissionController.refreshStatus()

        setContent {
            var showHistory by remember { mutableStateOf(false) }
            var history by remember { mutableStateOf(historyStore.all()) }
            var busy by remember { mutableStateOf(false) }
            var records by remember { mutableStateOf<List<ActivityRecord>>(emptyList()) }
            var loading by remember { mutableStateOf(false) }
            var errorMessage by remember { mutableStateOf<String?>(null) }

            LspoTheme {
                if (showHistory) {
                    DisableHistoryScreen(
                        entries = history,
                        busy = busy,
                        onBack = { showHistory = false },
                        onRestore = { entry ->
                            restoreEntries(listOf(entry)) {
                                busy = it
                                history = historyStore.all()
                            }
                        },
                        onRestoreAll = {
                            restoreEntries(historyStore.pending()) {
                                busy = it
                                history = historyStore.all()
                            }
                        }
                    )
                } else {
                    ActivityMonitorScreen(
                        records = records,
                        loading = loading,
                        errorMessage = errorMessage,
                        permissionState = permissionController.state,
                        permissionText = permissionController.label,
                        statusHint = permissionController.hint,
                        onRequestShizuku = { permissionController.request() },
                        onRefresh = {
                            lifecycleScope.launch {
                                loading = true
                                val outcome = loadRecords()
                                records = outcome.first
                                errorMessage = outcome.second
                                loading = false
                            }
                        },
                        onClear = { clearRecords { records = emptyList() } },
                        onExport = { exportReport(records) },
                        onOpenHistory = {
                            history = historyStore.all()
                            showHistory = true
                        },
                        onCopyActivity = ::copyToClipboard,
                        onDisablePackage = ::disablePackage,
                        onDisableActivity = ::disableActivity
                    )

                    // Initial load once the screen appears. Authored as its own effect so
                    // it runs after composition rather than during it.
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        loading = true
                        val outcome = loadRecords()
                        records = outcome.first
                        errorMessage = outcome.second
                        loading = false
                    }
                }
            }
        }
    }

    /** @return the records and an error message (null when the read was clean). */
    private suspend fun loadRecords(): Pair<List<ActivityRecord>, String?> {
        val result = reader.readScoped()
        return HookEventReader.toRecords(result.parsed) to result.error
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("activity_class", text))
        toast("Copied: " + text)
    }

    private fun disablePackage(pkg: String) {
        lifecycleScope.launch {
            when (val outcome = disabler.disablePackage(pkg)) {
                is ComponentDisabler.Outcome.Success -> {
                    historyStore.record(DisableTarget.PACKAGE, pkg)
                    toast("Disabled " + pkg + "（可在禁用历史中恢复）")
                }
                is ComponentDisabler.Outcome.Failure -> toast("Failed: " + outcome.message)
            }
        }
    }

    private fun disableActivity(pkg: String, activityClass: String) {
        lifecycleScope.launch {
            when (val outcome = disabler.disableActivity(pkg, activityClass)) {
                is ComponentDisabler.Outcome.Success -> {
                    historyStore.record(DisableTarget.ACTIVITY, pkg, activityClass)
                    toast("Disabled " + activityClass + "（可在禁用历史中恢复）")
                }
                is ComponentDisabler.Outcome.Failure -> toast("Failed: " + outcome.message)
            }
        }
    }

    /**
     * Restores the given entries, oldest first, marking every entry the shell accepted.
     *
     * Sequential on purpose: `pm` calls are serialised by the system anyway, and one at a
     * time keeps the progress callback accurate.
     */
    private fun restoreEntries(
        entries: List<DisableHistoryEntry>,
        onProgress: (Boolean) -> Unit = {}
    ) {
        if (entries.isEmpty()) {
            toast("没有需要恢复的记录")
            return
        }

        lifecycleScope.launch {
            onProgress(true)
            val succeeded = mutableListOf<Long>()
            var firstFailure: String? = null

            for (entry in entries) {
                when (val outcome = disabler.restore(entry)) {
                    is ComponentDisabler.Outcome.Success -> succeeded += entry.id
                    is ComponentDisabler.Outcome.Failure -> if (firstFailure == null) {
                        firstFailure = outcome.message
                    }
                }
            }

            historyStore.markRestored(succeeded)
            onProgress(false)

            val failed = entries.size - succeeded.size
            val message = buildString {
                if (succeeded.isNotEmpty()) append("已恢复 ${succeeded.size} 项")
                if (firstFailure != null) {
                    if (isNotEmpty()) append("，")
                    append("${failed} 项失败: $firstFailure")
                }
            }
            toast(message)
        }
    }

    /**
     * Clears hook logs.
     *
     * Deletes **every** log found under Android/data, not just the scoped ones, so a package
     * dropped from the scope cannot leave an orphaned log behind.
     */
    private fun clearRecords(onCleared: () -> Unit) {
        lifecycleScope.launch {
            reader.clearAll()
            onCleared()
            toast("已清空记录")
        }
    }

    private fun exportReport(records: List<ActivityRecord>) {
        lifecycleScope.launch {
            val result = exporter.export(RecordsRepository.newestFirst(records))
            val message = when (result) {
                is ReportExporter.Result.Success -> "Saved to Downloads/" + result.fileName
                is ReportExporter.Result.Failure -> "Export failed: " + result.message
                ReportExporter.Result.Empty -> "No records to export"
            }
            toast(message, Toast.LENGTH_LONG)
        }
    }

    private fun toast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(this, message, duration).show()
    }
}
