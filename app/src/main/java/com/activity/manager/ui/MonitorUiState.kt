package com.activity.manager.ui

import com.activity.manager.ActivityRecord
import com.activity.manager.DisableHistoryEntry
import com.activity.manager.MonitorFilter
import com.activity.manager.RiskLevel
import com.activity.manager.TimeWindow

/**
 * Everything the monitor screen renders.
 *
 * Hoisted out of `setContent`'s `remember` so it survives configuration changes: previously
 * the record list, the loading flag and the search text lived in composable-local state, so
 * rotating the device threw away a freshly read list and the user had to refresh again.
 */
data class MonitorUiState(
    val records: List<ActivityRecord> = emptyList(),
    val loading: Boolean = false,
    val errorMessage: String? = null,
    /** Search text, owned here so it also survives rotation. */
    val query: String = "",
    val timeWindow: TimeWindow = TimeWindow.ALL,
    /** Empty means every package. */
    val packages: Set<String> = emptySet(),
    val activityPrefix: String = "",
    val excludeSystem: Boolean = false,
    val groupByPackage: Boolean = false,
    /** Packages whose group is expanded in the grouped view. */
    val expanded: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
    /** Record keys (`ActivityRecord.key`) currently selected. */
    val selected: Set<String> = emptySet(),
    /** Non-null while the disable confirmation dialog is open. */
    val pending: PendingDisable? = null,
    val history: List<DisableHistoryEntry> = emptyList(),
    /** True while a restore / sync / batch operation runs. */
    val busy: Boolean = false,
    /** True when 更新 has run in this session, i.e. the restored flags are trustworthy. */
    val synced: Boolean = false,
    /** Non-null while 更新 is running, e.g. "更新中 3/12". */
    val syncProgress: String? = null
) {
    /** Snapshot handed to [com.activity.manager.MonitorFilters]. */
    val filter: MonitorFilter
        get() = MonitorFilter(query, timeWindow, packages, activityPrefix, excludeSystem)

    val selectedCount: Int get() = selected.size
}

/** One request that is about to be applied. */
data class PlannedDisable(
    val packageName: String,
    val activityClass: String?,
    val level: RiskLevel,
    val reason: String?
) {
    val isActivity: Boolean get() = activityClass != null

    val label: String
        get() = if (activityClass != null) packageName + "/" + activityClass else packageName
}

/**
 * The confirmation the user must see before anything is disabled.
 *
 * [blocked] is kept in the list rather than silently dropped, so the dialog can tell the user
 * exactly how many requests were refused and why — the whole point of the maintained table is
 * that this decision is visible, not hidden.
 */
data class PendingDisable(val items: List<PlannedDisable>) {
    val allowed: List<PlannedDisable> get() = items.filter { it.level != RiskLevel.BLOCK }
    val blocked: List<PlannedDisable> get() = items.filter { it.level == RiskLevel.BLOCK }
    val warnings: List<PlannedDisable> get() = items.filter { it.level == RiskLevel.WARN }
}

/**
 * A one-shot user-facing message.
 *
 * The ViewModel cannot show a Toast itself (it must not hold an Activity), so messages are
 * emitted as events and shown by the composition that collects them.
 */
data class UiMessage(val text: String, val long: Boolean = false)
