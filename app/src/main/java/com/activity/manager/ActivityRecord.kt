package com.activity.manager

/**
 * One Activity launch as rendered by the UI.
 *
 * [key] is the **raw log line** the record came from, not a position in the list.
 *
 * The previous model assigned `id = index + 1` on every read. Because the reader returns
 * newest-first, every newly appended event shifted every id by one, so `LazyColumn(key = …)`
 * saw an entirely different key set on each refresh: scroll position jumped, item animations
 * fired for records that had merely moved, and the diff was useless. A raw line is unique
 * after de-duplication and never changes once written, so it is a stable key.
 */
data class ActivityRecord(
    val key: String,
    val packageName: String,
    val activityClass: String,
    val timestamp: Long = System.currentTimeMillis(),
    val kind: HookEventKind = HookEventKind.CREATED,
    val intentAction: String? = null,
    val intentData: String? = null,
    val referrer: String? = null,
    val launchedFromPackage: String? = null,
    val taskId: Int? = null,
    val processName: String? = null,
    val extrasSummary: String? = null
) {
    fun toDisplayString(): String = "$packageName / ${activityClass.substringAfterLast('.')}"

    /** True when this record is a reused instance that only received a new intent. */
    val isNewIntent: Boolean get() = kind == HookEventKind.NEW_INTENT

    /**
     * Who started this activity, when the platform told us.
     *
     * `launchedFromPackage` is authoritative but is frequently null on Android 16+ because the
     * identity is only shared for result-based / same-uid launches; `referrer` is what the
     * caller put on the intent and is therefore the more reliable of the two.
     */
    val caller: String?
        get() = launchedFromPackage?.takeIf { it.isNotBlank() }
            ?: referrer?.takeIf { it.isNotBlank() }
}
