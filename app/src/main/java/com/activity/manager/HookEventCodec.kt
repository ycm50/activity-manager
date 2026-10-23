package com.activity.manager

/** Event kinds the hook can report for one activity instance. */
enum class HookEventKind(val wire: String) {
    /** `Activity.onCreate` — the instance was created. */
    CREATED("c"),

    /**
     * `Activity.onNewIntent` — an existing instance received a new intent.
     *
     * This is the case a `onCreate`-only hook silently misses: `singleTop` / `singleTask` /
     * `singleInstance` activities are reused instead of recreated, so launching such an app a
     * second time produced no record at all.
     */
    NEW_INTENT("n");

    companion object {
        fun fromWire(value: String): HookEventKind? = entries.firstOrNull { it.wire == value }
    }
}

/** One Activity launch reported by a hook, before an id is assigned. */
data class HookEvent(
    val timestamp: Long,
    val packageName: String,
    val activityClass: String,
    val kind: HookEventKind = HookEventKind.CREATED,
    /** Intent action, e.g. `android.intent.action.MAIN`. */
    val intentAction: String? = null,
    /** Intent data URI (truncated). */
    val intentData: String? = null,
    /** `Activity.getReferrer()` normalised to a package name or a short URI. */
    val referrer: String? = null,
    /**
     * `Activity.getLaunchedFromPackage()`.
     *
     * Best-effort on purpose: since Android 16 (API 36) the launching app's identity is only
     * shared when the launch is result-based, same-uid, or the caller opted in via
     * `ActivityOptions.setShareIdentityEnabled(true)`. Null is the normal "not shared" answer.
     */
    val launchedFromPackage: String? = null,
    val taskId: Int? = null,
    val processName: String? = null,
    /** Intent extras digest (`key:value,key:value`), sensitive values masked. */
    val extrasSummary: String? = null
)

/** A parsed log line plus the exact source text, used as the de-duplication key. */
data class ParsedHookLine(val raw: String, val event: HookEvent)

/**
 * Line codec for the hook event log.
 *
 * The hook runs inside third-party app processes and cannot reach the module's
 * ContentProvider: Android 11+ package visibility blocks that lookup, so the module
 * process gets `Failed to find provider info`. Instead every hooked app appends to a log
 * in **its own** external files directory, and the module reads all of them back through
 * Shizuku/Sui. Files are not subject to package visibility.
 *
 * Format: `timestamp|packageName|activityClass` with an optional 4th column of
 * `key=value` extras (see [HookExtras]). Package and class names cannot contain '|' or a
 * newline, but values are sanitised anyway so a malformed write can never corrupt the file
 * into unparsable garbage.
 *
 * The optional column is why [parseLine] splits with `limit = 4`: a line written before the
 * extras existed still produces exactly the same event, and the class name is never allowed
 * to swallow the extras.
 *
 * Pure by design (no Android types) so [HookEventCodecTest] can cover it on the JVM.
 */
object HookEventCodec {

    const val SEPARATOR = '|'

    /** Path segment (inside the app's external files dir) holding the logs. */
    const val DIRECTORY_NAME = "activity_monitor"

    /** Log file name, identical in every hooked app. */
    const val FILE_NAME = "events.log"

    /** timestamp, package, class, optional extras. */
    private const val MAX_PARTS = 4

    fun encode(event: HookEvent): String = buildString {
        append(event.timestamp)
        append(SEPARATOR)
        append(sanitize(event.packageName))
        append(SEPARATOR)
        append(sanitize(event.activityClass))

        // Omitted when there is nothing extra, so a create event with no captured data still
        // produces the exact 3-column line older readers understand.
        val extras = HookExtras.encode(event)
        if (extras.isNotEmpty()) {
            append(SEPARATOR)
            append(extras)
        }
    }

    /**
     * Parses a whole file.
     *
     * Unparsable lines are skipped rather than throwing: a partially written trailing line
     * (the writer appends while we read) is expected and must not break the load.
     */
    fun parse(text: String): List<ParsedHookLine> {
        if (text.isEmpty()) return emptyList()
        return text.split('\n').mapNotNull { parseLine(it) }
    }

    private fun parseLine(raw: String): ParsedHookLine? {
        val line = raw.trim()
        if (line.isEmpty()) return null

        val parts = line.split(SEPARATOR, limit = MAX_PARTS)
        if (parts.size < 3) return null

        val timestamp = parts[0].toLongOrNull() ?: return null
        val packageName = parts[1]
        val activityClass = parts[2]
        if (packageName.isEmpty() || activityClass.isEmpty()) return null

        val base = HookEvent(timestamp, packageName, activityClass)
        val event = if (parts.size == MAX_PARTS) HookExtras.apply(base, parts[3]) else base
        return ParsedHookLine(line, event)
    }

    private fun sanitize(value: String): String = value
        .replace(SEPARATOR, '_')
        .replace('\n', '_')
        .replace('\r', '_')
}
