package com.activity.manager

/**
 * Extra fields carried in the optional 4th pipe-delimited column of a hook line.
 *
 * The original format was `timestamp|package|class`. Appending fields naively would have
 * broken every existing log line, because the parser took *the rest of the line* as the class
 * name (`annotation: substring(second + 1)`). The 4th column is therefore a self-describing
 * `key=value` list, and the parser splits with `limit = 4`, so:
 *
 * - a 3-column line (all lines written before this feature) still parses exactly as before,
 * - a class name containing `|` still cannot leak into the extras, because the writer
 *   sanitises it and the reader only ever looks at parts[3].
 *
 * Every value is percent-escaped for the characters that would make the field ambiguous, so
 * the writer never has to escape twice. [encodeSummary] pre-escapes its own pair separators
 * for the same reason: an already-escaped value is written verbatim.
 *
 * Pure (no Android types) so [HookExtrasTest] pins it on the JVM.
 */
object HookExtras {

    /** Hard cap for the whole 4th column; the hook runs on the host's main thread. */
    const val MAX_FIELD_LENGTH = 512

    private const val PAIR_SEPARATOR = ';'
    private const val ASSIGN = '='
    private const val SUMMARY_PAIR = ','
    private const val SUMMARY_ASSIGN = ':'

    /** Event kind; omitted when it is the default (create). */
    const val KEY_KIND = "et"
    const val KEY_ACTION = "a"
    const val KEY_DATA = "d"
    const val KEY_REFERRER = "r"
    const val KEY_LAUNCHED = "c"
    const val KEY_TASK = "t"
    const val KEY_PROCESS = "p"
    const val KEY_EXTRAS = "x"

    /** Builds the 4th column, or "" when the event carries nothing extra. */
    fun encode(event: HookEvent): String {
        val pairs = ArrayList<String>(8)

        if (event.kind != HookEventKind.CREATED) {
            pairs += KEY_KIND + ASSIGN + escape(event.kind.wire)
        }
        event.intentAction?.let { pairs += KEY_ACTION + ASSIGN + escape(it) }
        event.intentData?.let { pairs += KEY_DATA + ASSIGN + escape(it) }
        event.referrer?.let { pairs += KEY_REFERRER + ASSIGN + escape(it) }
        event.launchedFromPackage?.let { pairs += KEY_LAUNCHED + ASSIGN + escape(it) }
        event.taskId?.let { pairs += KEY_TASK + ASSIGN + it.toString() }
        event.processName?.let { pairs += KEY_PROCESS + ASSIGN + escape(it) }
        // Written verbatim: [encodeSummary] has already escaped its own '%' sequences, and
        // escaping again would double-encode them.
        event.extrasSummary?.takeIf { it.isNotEmpty() }?.let {
            pairs += KEY_EXTRAS + ASSIGN + it
        }

        return joinBounded(pairs)
    }

    /** Applies a 4th column to a freshly parsed event. Unknown keys are ignored. */
    fun apply(event: HookEvent, field: String): HookEvent {
        if (field.isEmpty()) return event

        var kind = event.kind
        var action = event.intentAction
        var data = event.intentData
        var referrer = event.referrer
        var launched = event.launchedFromPackage
        var taskId = event.taskId
        var process = event.processName
        var extras = event.extrasSummary

        field.split(PAIR_SEPARATOR).forEach { pair ->
            val at = pair.indexOf(ASSIGN)
            if (at > 0) {
                val key = pair.substring(0, at)
                val raw = pair.substring(at + 1)
                when (key) {
                    KEY_KIND -> HookEventKind.fromWire(raw)?.let { kind = it }
                    KEY_ACTION -> action = unescape(raw).ifEmpty { null }
                    KEY_DATA -> data = unescape(raw).ifEmpty { null }
                    KEY_REFERRER -> referrer = unescape(raw).ifEmpty { null }
                    KEY_LAUNCHED -> launched = unescape(raw).ifEmpty { null }
                    KEY_TASK -> taskId = raw.toIntOrNull() ?: taskId
                    KEY_PROCESS -> process = unescape(raw).ifEmpty { null }
                    KEY_EXTRAS -> extras = raw.ifEmpty { null }
                }
            }
        }

        return event.copy(
            kind = kind,
            intentAction = action,
            intentData = data,
            referrer = referrer,
            launchedFromPackage = launched,
            taskId = taskId,
            processName = process,
            extrasSummary = extras
        )
    }

    /** Encodes an Intent extras digest as `key:value,key:value`, escaping both sides. */
    fun encodeSummary(entries: List<Pair<String, String>>): String =
        entries.joinToString(SUMMARY_PAIR.toString()) { (key, value) ->
            escape(key) + SUMMARY_ASSIGN + escape(value)
        }

    /** Inverse of [encodeSummary]; malformed entries are skipped, never fatal. */
    fun decodeSummary(summary: String?): List<Pair<String, String>> {
        if (summary.isNullOrEmpty()) return emptyList()
        return summary.split(SUMMARY_PAIR).mapNotNull { pair ->
            val at = pair.indexOf(SUMMARY_ASSIGN)
            if (at <= 0) return@mapNotNull null
            unescape(pair.substring(0, at)) to unescape(pair.substring(at + 1))
        }
    }

    private fun joinBounded(pairs: List<String>): String {
        val sb = StringBuilder()
        for (pair in pairs) {
            val needed = if (sb.isEmpty()) pair.length else sb.length + 1 + pair.length
            if (needed > MAX_FIELD_LENGTH) break
            if (sb.isNotEmpty()) sb.append(PAIR_SEPARATOR)
            sb.append(pair)
        }
        return sb.toString()
    }

    /** Escapes every character that would make the field or the summary ambiguous. */
    fun escape(value: String): String {
        if (value.none { it == '%' || it in AMBIGUOUS }) return value
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '%' -> sb.append("%25")
                '|' -> sb.append("%7C")
                ';' -> sb.append("%3B")
                '=' -> sb.append("%3D")
                '\n' -> sb.append("%0A")
                '\r' -> sb.append("%0D")
                ',' -> sb.append("%2C")
                ':' -> sb.append("%3A")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** Inverse of [escape]; leaves malformed `%` sequences untouched. */
    fun unescape(value: String): String {
        if (!value.contains('%')) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '%' && i + 2 < value.length) {
                val code = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (code != null) {
                    out.append(code.toChar())
                    i += 3
                    continue
                }
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    private val AMBIGUOUS = charArrayOf('|', ';', '=', '\n', '\r', ',', ':')
}
