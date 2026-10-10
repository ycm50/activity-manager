package com.activity.manager

/** One Activity launch reported by a hook, before an id is assigned. */
data class HookEvent(
    val timestamp: Long,
    val packageName: String,
    val activityClass: String
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
 * Format: `timestamp|packageName|activityClass`. Package and class names cannot contain
 * '|' or a newline, but values are sanitised anyway so a malformed write can never corrupt
 * the file into unparsable garbage.
 *
 * Pure by design (no Android types) so [HookEventCodecTest] can cover it on the JVM.
 */
object HookEventCodec {

    const val SEPARATOR = '|'

    /** Path segment (inside the app's external files dir) holding the logs. */
    const val DIRECTORY_NAME = "activity_monitor"

    /** Log file name, identical in every hooked app. */
    const val FILE_NAME = "events.log"

    fun encode(event: HookEvent): String = buildString {
        append(event.timestamp)
        append(SEPARATOR)
        append(sanitize(event.packageName))
        append(SEPARATOR)
        append(sanitize(event.activityClass))
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

        val first = line.indexOf(SEPARATOR)
        if (first <= 0) return null
        val second = line.indexOf(SEPARATOR, first + 1)
        if (second <= first + 1) return null

        val timestamp = line.substring(0, first).toLongOrNull() ?: return null
        val packageName = line.substring(first + 1, second)
        val activityClass = line.substring(second + 1)
        if (packageName.isEmpty() || activityClass.isEmpty()) return null

        return ParsedHookLine(
            raw = line,
            event = HookEvent(timestamp, packageName, activityClass)
        )
    }

    private fun sanitize(value: String): String = value
        .replace(SEPARATOR, '_')
        .replace('\n', '_')
        .replace('\r', '_')
}
