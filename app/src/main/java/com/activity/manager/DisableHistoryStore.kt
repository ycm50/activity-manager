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
        const val MAX_ENTRIES = 500
    }
}
