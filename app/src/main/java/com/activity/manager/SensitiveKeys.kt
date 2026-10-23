package com.activity.manager

/**
 * Masks the keys whose values must never reach the log.
 *
 * The hook records a digest of Intent extras so the list can show *why* an activity was
 * launched. Those extras occasionally carry credentials (`token`, `session`, `password`, …),
 * and the log file is readable by shell/Shizuku and survives until the user clears it, so the
 * value of a sensitive key is replaced before it is ever written.
 *
 * Pure (no Android types) so [SensitiveKeysTest] pins it on the JVM.
 */
object SensitiveKeys {

    /** What a masked value becomes. */
    const val MASK = "***"

    /** Longest value kept for a non-sensitive key, in characters. */
    const val MAX_VALUE_LENGTH = 64

    private val MARKERS = arrayOf(
        "password",
        "passwd",
        "pwd",
        "token",
        "secret",
        "cookie",
        "session",
        "auth",
        "credential",
        "apikey",
        "api_key",
        "access_key",
        "signature",
        "private"
    )

    /** True when the key's value must not be written as-is. */
    fun isSensitive(key: String): Boolean =
        MARKERS.any { key.contains(it, ignoreCase = true) }

    /**
     * What to store for one extra: the mask for a sensitive key, otherwise a clamped value.
     *
     * Clamping happens here rather than at the call site so the hook's main-thread work stays
     * bounded even when an app passes a multi-megabyte string extra.
     */
    fun display(key: String, value: String): String = if (isSensitive(key)) {
        MASK
    } else if (value.length <= MAX_VALUE_LENGTH) {
        value
    } else {
        value.substring(0, MAX_VALUE_LENGTH) + "…"
    }
}
