package com.activity.manager.ui

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.activity.manager.ActivityRecord
import com.activity.manager.AppInfoResolver
import com.activity.manager.ComponentDisabler
import com.activity.manager.ComponentStateChecker
import com.activity.manager.DangerousComponentPolicy
import com.activity.manager.DisableHistoryEntry
import com.activity.manager.DisableHistoryStore
import com.activity.manager.DisableTarget
import com.activity.manager.HookDirectoryLabel
import com.activity.manager.HookEventReader
import com.activity.manager.RiskLevel
import com.activity.manager.RecordsRepository
import com.activity.manager.ReportExporter
import com.activity.manager.RoleResolver
import com.activity.manager.ShizukuShell
import com.activity.manager.SystemPackages
import com.activity.manager.TimeWindow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Owns the monitor screen's state and every privileged operation behind it.
 *
 * This replaced ~200 lines of orchestration that lived directly in `MainActivity`, where the
 * record list and search text sat in `remember {}` inside `setContent` and were therefore
 * lost on rotation, and none of the flows could be exercised without a device.
 *
 * Privilege checks are self-contained: [ShizukuShell.unavailableReason] already distinguishes
 * "not running", "not granted" and "binder died", so the ViewModel does not need to observe
 * the activity's permission controller.
 */
class ActivityMonitorViewModel(application: Application) : AndroidViewModel(application) {

    private val shell = ShizukuShell()
    private val reader = HookEventReader(shell)
    private val disabler = ComponentDisabler(shell)
    private val stateChecker = ComponentStateChecker(shell)
    private val roleResolver = RoleResolver(shell)
    private val historyStore = DisableHistoryStore(application)
    private val exporter = ReportExporter(application)
    private val appInfoResolver = AppInfoResolver(application)

    /**
     * Role holders, resolved at most once per process.
     *
     * Three privileged round trips; they only change when the user changes a default app, and
     * re-running them for every tapped row would make the guard feel sluggish.
     */
    @Volatile
    private var roleCache: RoleResolver.Roles? = null

    /** Single source of truth for the monitor + history screens. */
    var state by mutableStateOf(
        MonitorUiState(
            history = historyStore.all(),
            synced = historyStore.lastSyncedAt() != null
        )
    )
        private set

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages

    init {
        // Read once on creation. Nothing subscribes to the log files, and reading them costs a
        // privileged shell round trip, so the rest is manual (the refresh FAB).
        refresh()
    }

    // ── monitor ────────────────────────────────────────────────────────────────

    fun setQuery(value: String) {
        state = state.copy(query = value)
    }

    fun setTimeWindow(value: TimeWindow) {
        state = state.copy(timeWindow = value)
    }

    fun toggleExcludeSystem() {
        state = state.copy(excludeSystem = !state.excludeSystem)
    }

    fun toggleGrouping() {
        state = state.copy(groupByPackage = !state.groupByPackage)
    }

    fun toggleExpanded(packageName: String) {
        val expanded = state.expanded
        state = state.copy(
            expanded = if (packageName in expanded) expanded - packageName else expanded + packageName
        )
    }

    fun togglePackageFilter(packageName: String) {
        val packages = state.packages
        state = state.copy(
            packages = if (packageName in packages) packages - packageName else packages + packageName
        )
    }

    fun clearFilters() {
        state = state.copy(
            query = "",
            timeWindow = TimeWindow.ALL,
            packages = emptySet(),
            activityPrefix = "",
            excludeSystem = false
        )
    }

    /** Icon + label for a package; cached inside [AppInfoResolver]. */
    fun appInfo(packageName: String) = appInfoResolver.resolve(packageName)

    /** True when the package looks like part of the system; used by the "exclude system" filter. */
    fun isSystemPackage(packageName: String): Boolean =
        SystemPackages.isLikelySystem(packageName)

    fun refresh() {
        viewModelScope.launch {
            state = state.copy(loading = true)
            val result = reader.readScoped()
            state = state.copy(
                records = HookEventReader.toRecords(result.parsed),
                errorMessage = result.error,
                loading = false
            )
        }
    }

    fun clearRecords() {
        viewModelScope.launch {
            val result = reader.clearAll()
            state = state.copy(records = emptyList())

            if (result.count == 0 && result.isComplete) {
                emit("没有可清除的记录（未找到 activity_monitor 目录）")
                return@launch
            }

            val message = buildString {
                if (result.count > 0) {
                    Log.i(TAG, "Cleared " + result.count + " directories: " + result.directories)
                    // Label as "<package>/activity_monitor": the full path is long and mostly
                    // noise, while the tail after "/files/" alone would not say whose it was.
                    val labels = result.directories.map { HookDirectoryLabel.of(it) }
                    append("已删除 ").append(result.count).append(" 个目录：\n")
                    append(labels.joinToString("\n"))
                }
                if (result.error != null) {
                    if (isNotEmpty()) append("\n")
                    append("清除未完全成功：").append(result.error)
                }
            }
            emit(message, long = true)
        }
    }

    fun export() {
        viewModelScope.launch {
            val result = exporter.export(RecordsRepository.newestFirst(state.records))
            emit(
                when (result) {
                    is ReportExporter.Result.Success -> "Saved to Downloads/" + result.fileName
                    is ReportExporter.Result.Failure -> "Export failed: " + result.message
                    ReportExporter.Result.Empty -> "No records to export"
                },
                long = true
            )
        }
    }

    // ── selection ──────────────────────────────────────────────────────────────

    /** Long-press entry point: starts multi-select with one row selected. */
    fun beginSelection(key: String) {
        state = state.copy(selectionMode = true, selected = state.selected + key)
    }

    fun toggleSelected(key: String) {
        val selected = state.selected
        state = state.copy(
            selected = if (key in selected) selected - key else selected + key
        )
    }

    fun exitSelection() {
        state = state.copy(selectionMode = false, selected = emptySet())
    }

    fun selectAll(keys: Collection<String>) {
        state = state.copy(selectionMode = true, selected = keys.toSet())
    }

    /**
     * 按包全选：adds every record of one package to the current selection.
     *
     * This is the "禁用它所有的 Act" path: a package's activities are separate components, so
     * disabling one leaves the others able to launch whatever they do.
     */
    fun selectPackage(keys: Collection<String>) {
        if (keys.isEmpty()) return
        state = state.copy(selectionMode = true, selected = state.selected + keys)
    }

    // ── disable (guarded) ──────────────────────────────────────────────────────

    /**
     * Asks for [packageName] to be disabled.
     *
     * A BLOCK verdict never reaches a dialog: it is refused immediately, with the maintained
     * reason, and no `pm` command is ever issued.
     */
    fun disablePackage(packageName: String) {
        request(listOf(packageName to null))
    }

    fun disableActivity(packageName: String, activityClass: String) {
        request(listOf(packageName to activityClass))
    }

    /** Batch entry point: plans every selected record. */
    fun disableSelected() {
        val keys = state.selected
        if (keys.isEmpty()) {
            emit("请先选择要禁用的条目")
            return
        }
        val targets = state.records
            .filter { it.key in keys }
            .map { it.packageName to it.activityClass }

        if (targets.isEmpty()) {
            emit("所选条目已不在列表中，请刷新后再试")
            return
        }
        request(targets)
    }

    private fun request(targets: List<Pair<String, String?>>) {
        viewModelScope.launch {
            val roles = roles()
            val plans = targets
                .distinct()
                .map { (pkg, cls) -> plan(pkg, cls, roles) }

            val blocked = plans.filter { it.level == RiskLevel.BLOCK }
            val allowed = plans.filter { it.level != RiskLevel.BLOCK }

            if (allowed.isEmpty()) {
                // Everything was refused: report the reasons instead of opening a dead dialog.
                emit(
                    "已拒绝：" + blocked.joinToString("\n") { it.reason ?: it.label },
                    long = true
                )
                return@launch
            }

            if (blocked.isNotEmpty()) {
                Log.w(TAG, "Batch skipped protected targets: " + blocked.map { it.packageName })
            }
            state = state.copy(pending = PendingDisable(plans))
        }
    }

    /** Runs the confirmed batch, one command at a time, recording each success. */
    fun confirmPending() {
        val pending = state.pending ?: return
        state = state.copy(pending = null)

        viewModelScope.launch {
            state = state.copy(busy = true)
            val succeeded = mutableListOf<String>()
            var firstFailure: String? = null

            for (item in pending.allowed) {
                val outcome = if (item.activityClass != null) {
                    disabler.disableActivity(item.packageName, item.activityClass)
                } else {
                    disabler.disablePackage(item.packageName)
                }

                when (outcome) {
                    is ComponentDisabler.Outcome.Success -> {
                        historyStore.record(
                            if (item.activityClass != null) DisableTarget.ACTIVITY else DisableTarget.PACKAGE,
                            item.packageName,
                            item.activityClass
                        )
                        succeeded += item.label
                    }

                    is ComponentDisabler.Outcome.Failure ->
                        if (firstFailure == null) firstFailure = outcome.message

                    is ComponentDisabler.Outcome.Refused ->
                        if (firstFailure == null) firstFailure = outcome.message
                }
            }

            state = state.copy(
                busy = false,
                history = historyStore.all(),
                selectionMode = false,
                selected = emptySet()
            )

            val message = buildString {
                if (succeeded.isNotEmpty()) append("已禁用 ").append(succeeded.size).append(" 项")
                if (pending.blocked.isNotEmpty()) {
                    if (isNotEmpty()) append("，")
                    append("已拒绝 ").append(pending.blocked.size).append(" 项系统关键组件")
                }
                if (firstFailure != null) {
                    if (isNotEmpty()) append("；")
                    append("失败：").append(firstFailure)
                }
            }
            emit(message, long = true)
        }
    }

    fun dismissPending() {
        state = state.copy(pending = null)
    }

    private suspend fun roles(): RoleResolver.Roles =
        roleCache ?: roleResolver.resolve().also { roleCache = it }

    private fun plan(
        packageName: String,
        activityClass: String?,
        roles: RoleResolver.Roles
    ): PlannedDisable {
        // Reading the flags needs package visibility; absent that, the heuristic covers it.
        val info = runCatching {
            getApplication<Application>().packageManager.getApplicationInfo(packageName, 0)
        }.getOrNull()

        val isSystem = (info != null && (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0) ||
            SystemPackages.isLikelySystem(packageName)
        val isPersistent = info != null && (info.flags and ApplicationInfo.FLAG_PERSISTENT) != 0

        val verdict = DangerousComponentPolicy.classify(
            DangerousComponentPolicy.Input(
                packageName = packageName,
                activityClass = activityClass,
                isSystemApp = isSystem,
                isPersistent = isPersistent,
                roles = roles
            )
        )
        return PlannedDisable(packageName, activityClass, verdict.level, verdict.reason)
    }

    // ── history ────────────────────────────────────────────────────────────────

    /**
     * 更新：re-checks every entry against the device, one command at a time.
     *
     * An entry counts as restored when the component is no longer disabled — that covers both
     * "we restored it" and "the user re-enabled it by hand". Entries whose state cannot be
     * determined keep their previous flag rather than being guessed at.
     */
    fun syncHistory() {
        viewModelScope.launch {
            shell.unavailableReason()?.let { reason ->
                emit(reason, long = true)
                return@launch
            }

            val entries = state.history
            if (entries.isEmpty()) {
                emit("没有可更新的记录")
                return@launch
            }

            state = state.copy(busy = true, syncProgress = "更新中 0/" + entries.size)

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
                state = state.copy(syncProgress = "更新中 " + (index + 1) + "/" + entries.size)

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
            state = state.copy(
                busy = false,
                syncProgress = null,
                history = historyStore.all(),
                synced = true
            )

            val checked = entries.size - failed.size
            val message = if (failed.isEmpty()) {
                "已更新 $checked 条"
            } else {
                "已更新 $checked 条，${failed.size} 条无法确定（保留原状态）：\n" +
                    failed.joinToString("\n")
            }
            emit(message, long = true)
        }
    }

    fun restore(entry: DisableHistoryEntry) {
        restoreEntries(listOf(entry))
    }

    fun restoreAll() {
        restoreEntries(historyStore.pending())
    }

    /**
     * Restores the given entries, oldest first, marking every entry the shell accepted.
     *
     * Sequential on purpose: `pm` calls are serialised by the system anyway, and one at a
     * time keeps the progress state accurate.
     */
    private fun restoreEntries(entries: List<DisableHistoryEntry>) {
        if (entries.isEmpty()) {
            emit("没有需要恢复的记录")
            return
        }

        viewModelScope.launch {
            state = state.copy(busy = true)
            val succeeded = mutableListOf<Long>()
            var firstFailure: String? = null

            for (entry in entries) {
                when (val outcome = disabler.restore(entry)) {
                    is ComponentDisabler.Outcome.Success -> succeeded += entry.id
                    is ComponentDisabler.Outcome.Failure ->
                        if (firstFailure == null) firstFailure = outcome.message

                    is ComponentDisabler.Outcome.Refused ->
                        if (firstFailure == null) firstFailure = outcome.message
                }
            }

            historyStore.markRestored(succeeded)
            state = state.copy(busy = false, history = historyStore.all())

            val failed = entries.size - succeeded.size
            val message = buildString {
                if (succeeded.isNotEmpty()) append("已恢复 ${succeeded.size} 项")
                if (firstFailure != null) {
                    if (isNotEmpty()) append("，")
                    append("${failed} 项失败: $firstFailure")
                }
            }
            emit(message)
        }
    }

    /**
     * 清除已恢复：deletes restored entries, but only right after 更新.
     *
     * The guard exists because the flags could otherwise be stale — deleting on stale data
     * would drop entries the user still needs to restore, and the history is their only
     * record of what was disabled.
     */
    fun clearRestored() {
        if (historyStore.lastSyncedAt() == null) {
            emit("请先点「更新」核对当前状态，再清除已恢复", long = true)
            return
        }

        viewModelScope.launch {
            val removed = historyStore.clearRestored()
            // Consume the sync: the next clear must be preceded by a fresh 更新, so stale
            // flags can never accumulate across sessions.
            historyStore.clearSyncMark()
            state = state.copy(history = historyStore.all(), synced = false)
            emit(if (removed == 0) "没有已恢复的条目" else "已清除 $removed 条已恢复记录")
        }
    }

    private fun emit(text: String, long: Boolean = false) {
        _messages.tryEmit(UiMessage(text, long))
    }

    private companion object {
        const val TAG = "ActivityMonitor"
    }
}
