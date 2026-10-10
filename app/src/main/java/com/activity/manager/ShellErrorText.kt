package com.activity.manager

/**
 * Filters the routine noise that `find` writes to stderr.
 *
 * `find` walking `Android/data` always prints lines like
 * `find: /path: No such file or directory` or `Permission denied` for directories it cannot
 * enter — other apps' sandboxes, and scoped packages that have no directory because they
 * never wrote anything. Those lines describe *paths we do not care about*, so repeating them
 * to the user is misleading: a fully successful operation would look like a failure.
 *
 * Genuine errors (a bad command, a missing binary) have a different shape and do survive.
 *
 * Pure, so [ShellErrorTextTest] pins the behaviour on the JVM.
 */
object ShellErrorText {

    /** Substrings that mark a line as find's per-path traversal noise. */
    private val NOISE_MARKERS = listOf(
        "No such file or directory",
        "Permission denied"
    )

    /** True when the line is find complaining about one path it could not read. */
    fun isTraversalNoise(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        // find prefixes its per-path complaints with "find: ", which is what distinguishes
        // them from a real failure message.
        if (!trimmed.startsWith("find:")) return false
        return NOISE_MARKERS.any { trimmed.contains(it, ignoreCase = true) }
    }

    /**
     * Drops traversal noise and returns the remaining lines.
     *
     * @return the meaningful lines, empty when everything was noise.
     */
    fun meaningful(stderr: String): String =
        stderr.lineSequence()
            .filter { it.isNotBlank() && !isTraversalNoise(it) }
            .joinToString("\n")
            .trim()
}
