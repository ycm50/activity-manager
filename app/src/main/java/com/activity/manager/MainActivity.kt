package com.activity.manager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
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
    private lateinit var stateChecker: ComponentStateChecker

    /**
     * Progress label while 更新 walks the list ("更新中 3/12"), null when idle.
     *
     * Lives on the activity rather than inside `setContent` because [syncHistory] — an
     * ordinary method, not a composable — is what updates it.
     */
    private var syncProgress by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        permissionController = ShizukuPermissionController(this)
        disabler = ComponentDisabler()
        historyStore = DisableHistoryStore(this)
        exporter = ReportExporter(this)
        val shell = ShizukuShell()
        reader = HookEventReader(shell)
        stateChecker = ComponentStateChecker(shell)
        // Check (never prompt) on open: the toolbar button is the only way to request.
        permissionController.refreshStatus()

        setContent {
            var showHistory by remember { mutableStateOf(false) }
            var history by remember { mutableStateOf(historyStore.all()) }
            var busy by remember { mutableStateOf(false) }
            // Set only by 更新. 清除已恢复 refuses to run without it, so the restored flags
            // it deletes are known to match the device.
            var synced by remember { mutableStateOf(historyStore.lastSyncedAt() != null) }
            var records by remember { mutableStateOf<List<ActivityRecord>>(emptyList()) }
            var loading by remember { mutableStateOf(false) }
            var errorMessage by remember { mutableStateOf<String?>(null) }

            LspoTheme {
                if (showHistory) {
                    DisableHistoryScreen(
                        entries = history,
                        busy = busy,
                        synced = synced,
                        syncProgress = this@MainActivity.syncProgress,
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
                        },
                        onSync = { syncHistory { busy = it; history = historyStore.all() } },
                        onClearRestored = {
                            clearRestoredEntries { newSynced ->
                                synced = newSynced
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
    /**
     * 更新：re-checks every entry against the device, one command at a time.
     *
     * An entry counts as restored when the component is no longer disabled — that covers
     * both "we restored it" and "the user re-enabled it by hand", which is what the list
     * should show. Entries whose state cannot be determined keep their previous flag rather
     * than being guessed at.
     */
    private fun syncHistory(onProgress: (Boolean) -> Unit) {
        lifecycleScope.launch {
            if (permissionController.state != ShizukuState.GRANTED) {
                toast("请先授予 Shizuku 权限")
                return@launch
            }

            val entries = historyStore.all()
            if (entries.isEmpty()) {
                toast("没有可更新的记录")
                return@launch
            }
            syncProgress = "更新中 0/" + entries.size

            onProgress(true)

            val updates = mutableMapOf<Long, Boolean>()
            // Name the entries that could not be resolved instead of only counting them: a
            // bare "N 条未知" gives the user nothing to act on.
            val failed = mutableListOf<String>()

            // One cheap call resolves every package-level entry; only activities then need a
            // per-package dumpsys. Without this the loop ran a heavy command per entry and
            // looked like it had hung.
            val disabledPackages = stateChecker.disabledPackages()

            entries.forEachIndexed { index, entry ->
                // Show progress: a silent multi-second loop reads as "stuck".
                syncProgress = "更新中 " + (index + 1) + "/" + entries.size

                val result = stateChecker.check(entry, disabledPackages)
                when (result.state) {
                    ComponentStateChecker.State.DISABLED -> updates[entry.id] = false
                    ComponentStateChecker.State.ENABLED -> updates[entry.id] = true
                    ComponentStateChecker.State.MISSING -> updates[entry.id] = true
                    ComponentStateChecker.State.UNKNOWN -> failed += entry.component
                }

                if (result.state == ComponentStateChecker.State.UNKNOWN && result.reason != null) {
                    Log.w(TAG, "Unresolved state for " + entry.component + ": " + result.reason)
                }
            }

            historyStore.updateStates(updates)
            historyStore.markSynced()
            syncProgress = null
            onProgress(false)

            val checked = entries.size - failed.size
            val message = if (failed.isEmpty()) {
                "已更新 " + checked + " 条"
            } else {
                "已更新 " + checked + " 条，" + failed.size + " 条无法确定（保留原状态）：\n" +
                    failed.joinToString("\n")
            }
            toast(message, Toast.LENGTH_LONG)
        }
    }

    /**
     * 清除已恢复：deletes restored entries, but only right after 更新.
     *
     * The guard exists because the flags could otherwise be stale — deleting on stale data
     * would drop entries the user still needs to restore, and the history is their only
     * record of what was disabled.
     */
    private fun clearRestoredEntries(onDone: (Boolean) -> Unit) {
        if (historyStore.lastSyncedAt() == null) {
            toast("请先点「更新」核对当前状态，再清除已恢复", Toast.LENGTH_LONG)
            return
        }

        lifecycleScope.launch {
            val removed = historyStore.clearRestored()
            if (removed == 0) {
                toast("没有已恢复的条目")
            } else {
                toast("已清除 " + removed + " 条已恢复记录")
            }
            // Consume the sync: the next clear must be preceded by a fresh 更新, so stale
            // flags can never accumulate across sessions.
            historyStore.clearSyncMark()
            onDone(false)
        }
    }

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
    /**
     * Deletes every `activity_monitor` directory.
     *
     * Reports the directories that were actually removed instead of a generic "cleared"
     * message: the count and paths are the only confirmation that the privileged command
     * matched anything, and "0" is a meaningful answer (nothing had been recorded).
     */
    private fun clearRecords(onCleared: () -> Unit) {
        lifecycleScope.launch {
            val result = reader.clearAll()
            onCleared()

            if (result.count == 0) {
                toast("没有可清除的记录（未找到 activity_monitor 目录）")
                return@launch
            }

            Log.i(TAG, "Cleared " + result.count + " directories: " + result.directories)
            // Label as "<package>/activity_monitor": the full path is long and mostly noise,
            // while the remaining tail after "/files/" alone would not say whose directory
            // it was.
            val labels = result.directories.map { HookDirectoryLabel.of(it) }
            toast(
                "已删除 " + result.count + " 个目录：\n" + labels.joinToString("\n"),
                Toast.LENGTH_LONG
            )
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

    private companion object {
        const val TAG = "ActivityMonitor"
    }
}
