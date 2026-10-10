package com.activity.manager

import android.content.Context

/**
 * Persists [DisableHistoryEntry] records in SharedPreferences.
 *
 * SharedPreferences rather than a database: the list is small and bounded, and this keeps
 * the module free of an extra dependency. Read-modify-write cycles are serialised on the
 * instance monitor because several coroutines can finish at once (e.g. "restore all").
 */
class DisableHistoryStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whole history, newest first. */
    fun all(): List<DisableHistoryEntry> = synchronized(this) {
        DisableHistoryCodec.decode(prefs.getString(KEY_ENTRIES, null))
            .sortedByDescending { it.disabledAt }
    }

    /** Appends a disable operation, returning the stored entry. */
    fun record(
        target: DisableTarget,
        packageName: String,
        activityClass: String? = null,
        at: Long = System.currentTimeMillis()
    ): DisableHistoryEntry = synchronized(this) {
        val existing = DisableHistoryCodec.decode(prefs.getString(KEY_ENTRIES, null))
        val id = (existing.maxOfOrNull { it.id } ?: 0L) + 1L

        val entry = DisableHistoryEntry(
            id = id,
            target = target,
            packageName = packageName,
            activityClass = activityClass,
            disabledAt = at,
            restored = false
        )

        // A repeat disable of the same target supersedes the older *still-pending* record,
        // otherwise "restore all" would issue redundant commands. Already-restored entries
        // are kept: they are history, not pending work.
        val kept = existing.filterNot {
            !it.restored && it.matches(target, packageName, activityClass)
        }

        // Bound the history so the preference file cannot grow without limit.
        val bounded = (kept + entry).sortedByDescending { it.disabledAt }.take(MAX_ENTRIES)
        persist(bounded)
        entry
    }

    fun markRestored(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        synchronized(this) {
            val existing = DisableHistoryCodec.decode(prefs.getString(KEY_ENTRIES, null))
            val idSet = ids.toSet()
            persist(existing.map { if (it.id in idSet) it.copy(restored = true) else it })
        }
    }

    /**
     * Replaces the stored entries wholesale, used by 更新 after re-checking the device.
     */
    fun replaceAll(entries: List<DisableHistoryEntry>) {
        synchronized(this) { persist(entries.sortedByDescending { it.disabledAt }) }
    }

    /** Applies a per-entry state update produced by 更新. */
    fun updateStates(updates: Map<Long, Boolean>) {
        if (updates.isEmpty()) return
        synchronized(this) {
            val existing = DisableHistoryCodec.decode(prefs.getString(KEY_ENTRIES, null))
            persist(existing.map { entry ->
                updates[entry.id]?.let { entry.copy(restored = it) } ?: entry
            })
        }
    }

    /** Removes entries already marked restored; returns how many were removed. */
    fun clearRestored(): Int = synchronized(this) {
        val existing = DisableHistoryCodec.decode(prefs.getString(KEY_ENTRIES, null))
        val kept = existing.filterNot { it.restored }
        val removed = existing.size - kept.size
        if (removed > 0) persist(kept)
        removed
    }

    /**
     * Records that 更新 just ran.
     *
     * 清除已恢复 refuses to delete anything unless the state was refreshed in the same
     * session, so a stale flag cannot cause the user to lose entries they still need.
     */
    fun markSynced(at: Long = System.currentTimeMillis()) {
        synchronized(this) { prefs.edit().putLong(KEY_LAST_SYNC, at).apply() }
    }

    /** Timestamp of the last 更新, or null when it has never run. */
    fun lastSyncedAt(): Long? = synchronized(this) {
        val value = prefs.getLong(KEY_LAST_SYNC, 0L)
        value.takeIf { it > 0L }
    }

    /**
     * Consumes the sync mark.
     *
     * Called after 清除已恢复 so one 更新 authorises one clear: without this, a single
     * refresh made hours ago would keep permitting deletions against stale state.
     */
    fun clearSyncMark() {
        synchronized(this) { prefs.edit().remove(KEY_LAST_SYNC).apply() }
    }

    /** History entries that still need restoring, oldest first. */
    fun pending(): List<DisableHistoryEntry> = synchronized(this) {
        DisableHistoryCodec.decode(prefs.getString(KEY_ENTRIES, null))
            .filterNot { it.restored }
            .sortedBy { it.disabledAt }
    }

    fun clear() {
        synchronized(this) { prefs.edit().remove(KEY_ENTRIES).apply() }
    }

    private fun persist(entries: List<DisableHistoryEntry>) {
        prefs.edit().putString(KEY_ENTRIES, DisableHistoryCodec.encode(entries)).apply()
    }

    private fun DisableHistoryEntry.matches(
        target: DisableTarget,
        packageName: String,
        activityClass: String?
    ): Boolean = this.target == target &&
        this.packageName == packageName &&
        this.activityClass == activityClass

    private companion object {
        const val PREFS_NAME = "activity_monitor_history"
        const val KEY_ENTRIES = "disable_history"
        const val KEY_LAST_SYNC = "last_sync_at"
        const val MAX_ENTRIES = 500
    }
}
