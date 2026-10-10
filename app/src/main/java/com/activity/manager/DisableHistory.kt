package com.activity.manager

/** What was disabled: a whole package, or a single activity component. */
enum class DisableTarget(val wireName: String) {
    PACKAGE("p"),
    ACTIVITY("a");

    companion object {
        fun fromWire(value: String): DisableTarget? = entries.firstOrNull { it.wireName == value }
    }
}

/**
 * One recorded `pm disable` operation, kept so the user can undo it later.
 *
 * [restored] marks that a restore was issued; the entry is kept (it is a history, not a
 * to-do list) but rendered as already handled.
 */
data class DisableHistoryEntry(
    val id: Long,
    val target: DisableTarget,
    val packageName: String,
    /** Null for [DisableTarget.PACKAGE]. */
    val activityClass: String?,
    val disabledAt: Long,
    val restored: Boolean = false
) {
    /** The `package/class` form accepted by `pm`, or just the package name. */
    val component: String
        get() = if (target == DisableTarget.ACTIVITY && !activityClass.isNullOrBlank()) {
            "$packageName/$activityClass"
        } else {
            packageName
        }

    val isActivity: Boolean get() = target == DisableTarget.ACTIVITY

    /** Short label for the list; the full component stays visible on its own line. */
    val shortLabel: String
        get() = if (isActivity) {
            activityClass?.substringAfterLast('.') ?: packageName
        } else {
            packageName.substringAfterLast('.')
        }
}

/**
 * Tab-separated line codec for [DisableHistoryEntry].
 *
 * Kept pure (no Android types) so it is covered by JVM unit tests; the SharedPreferences
 * plumbing lives in [DisableHistoryStore].
 *
 * Fields: id, target, packageName, activityClass (empty when absent), disabledAt, restored
 * Package and class names cannot contain tab or newline, so no escaping is required, and
 * malformed lines are skipped rather than failing the whole load.
 */
object DisableHistoryCodec {

    private const val FIELD_SEPARATOR = '\t'
    private const val RECORD_SEPARATOR = '\n'
    private const val FIELD_COUNT = 6

    fun encode(entries: List<DisableHistoryEntry>): String =
        entries.joinToString(RECORD_SEPARATOR.toString()) { e ->
            listOf(
                e.id.toString(),
                e.target.wireName,
                e.packageName,
                e.activityClass.orEmpty(),
                e.disabledAt.toString(),
                if (e.restored) "1" else "0"
            ).joinToString(FIELD_SEPARATOR.toString())
        }

    fun decode(raw: String?): List<DisableHistoryEntry> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(RECORD_SEPARATOR).mapNotNull { decodeLine(it) }
    }

    private fun decodeLine(line: String): DisableHistoryEntry? {
        if (line.isBlank()) return null
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size != FIELD_COUNT) return null

        val id = parts[0].toLongOrNull() ?: return null
        val target = DisableTarget.fromWire(parts[1]) ?: return null
        val packageName = parts[2]
        if (packageName.isBlank()) return null
        val activityClass = parts[3].ifBlank { null }
        val disabledAt = parts[4].toLongOrNull() ?: return null
        if (target == DisableTarget.ACTIVITY && activityClass == null) return null

        return DisableHistoryEntry(
            id = id,
            target = target,
            packageName = packageName,
            activityClass = activityClass,
            disabledAt = disabledAt,
            restored = parts[5] == "1"
        )
    }
}
