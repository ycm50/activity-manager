package com.activity.manager

/**
 * Turns a hook-directory path into a short, readable label.
 *
 * The full path (`/storage/emulated/0/Android/data/<pkg>/files/activity_monitor`) is mostly
 * fixed boilerplate, so only the package and the directory name are worth showing.
 *
 * Pure (no Android types) so [HookDirectoryLabelTest] can pin the behaviour on the JVM.
 */
object HookDirectoryLabel {

    private const val MARKER = "/files/"

    /**
     * `.../Android/data/<pkg>/files/activity_monitor` becomes `<pkg>/activity_monitor`.
     *
     * Falls back to the last two path segments when the shape is unexpected, so an
     * unforeseen path still renders as something readable instead of blank.
     */
    fun of(path: String): String {
        val trimmed = path.trim()
        val at = trimmed.indexOf(MARKER)
        if (at >= 0) {
            val pkg = trimmed.substring(0, at).substringAfterLast('/')
            val tail = trimmed.substring(at + MARKER.length)
            if (pkg.isNotEmpty() && tail.isNotEmpty()) return pkg + "/" + tail
        }
        val parts = trimmed.trimEnd('/').split('/').filter { it.isNotEmpty() }
        return parts.takeLast(2).joinToString("/")
    }
}
