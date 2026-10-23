package com.activity.manager

/**
 * Pure interpretation of the outputs produced while clearing hook logs.
 *
 * Clearing used to be a single `find … -print -exec rm -rf {} +` whose stdout was taken at
 * face value: the UI reported "已删除 N 个目录" even when nothing was actually removed, and
 * stderr was discarded entirely. The operation is now collect → delete → verify, and the
 * three outputs are interpreted here so the rules stay unit-tested on the JVM.
 */
object ClearOutcome {

    /**
     * Directory paths listed by `find … -print`.
     *
     * `find` walks `Android/data` and cannot descend into other apps' sandboxes, so it also
     * emits unrelated noise; only lines ending in our directory name are real matches.
     */
    fun paths(stdout: String): List<String> =
        stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.endsWith(HookEventCodec.DIRECTORY_NAME) }
            .distinct()
            .toList()

    /**
     * The meaningful part of stderr, or null when it was only traversal noise.
     *
     * Reuses [ShellErrorText] so `find: … No such file or directory` about *other* apps'
     * directories is never surfaced as a failure of our own deletion.
     */
    fun errorOf(stderr: String): String? =
        ShellErrorText.meaningful(stderr).ifEmpty { null }
}
