package com.activity.manager

import android.util.Log

/**
 * Reads hooked apps' event logs back into the module process.
 *
 * The provider route is unavailable in a hooked process: Android 11+ package visibility
 * blocks the authority lookup (observed as `Failed to find provider info`). Files bypass
 * package visibility entirely, and the module already holds Shizuku/Sui privilege, whose
 * `ext_data_rw` group is what makes other apps' external directories readable.
 *
 * Scanning is limited to [ModuleScope]: one `find` over explicit log paths instead of a
 * traversal of every app under `Android/data`.
 */
class HookEventReader(private val shell: ShizukuShell) {

    /**
     * Outcome of a read.
     *
     * [error] is surfaced by the UI instead of being swallowed: a missing Shizuku grant used
     * to look identical to "no Activity was ever recorded".
     */
    data class ReadResult(val parsed: List<ParsedHookLine>, val error: String?) {
        val isEmpty: Boolean get() = parsed.isEmpty()
    }

    suspend fun readScoped(): ReadResult {
        shell.unavailableReason()?.let { reason ->
            Log.i(TAG, "readScoped blocked: " + reason)
            return ReadResult(emptyList(), reason)
        }

        val scoped = ModuleScope.packages()
        val argv = if (scoped.isEmpty()) fullTraversalArgs() else scopedArgs(scoped)
        val result = shell.run(argv)
        Log.i(
            TAG,
            "readScoped scope=" + scoped + " rc=" + result.exitCode +
                " bytes=" + result.stdout.length +
                " err=" + ShellErrorText.meaningful(result.stderr).take(200)
        )

        // `find` exits non-zero and prints "No such file or directory" / "Permission denied"
        // for paths it cannot enter — that is routine while walking Android/data and says
        // nothing about the logs we did read. Those lines are filtered out, so a successful
        // read is never reported to the user as an error.
        val error = ShellErrorText.meaningful(result.stderr)
        if (!result.hasOutput && error.isNotEmpty()) {
            return ReadResult(emptyList(), error)
        }
        return ReadResult(dedupe(HookEventCodec.parse(result.stdout)), null)
    }

    /** Scoped read: one `find` with an explicit path per package. */
    fun scopedArgs(packages: Collection<String>): Array<String> = buildList {
        add(ShizukuShell.FIND)
        packages.forEach { add(ModuleScope.logPathFor(it)) }
        add("-maxdepth"); add("1")
        add("-type"); add("f"); add("-name"); add(HookEventCodec.FILE_NAME)
        add("-exec"); add("cat"); add("{}"); add("+")
    }.toTypedArray()

    /** Fallback: traverse the whole external data root when the scope is unknown. */
    fun fullTraversalArgs(): Array<String> = arrayOf(
        ShizukuShell.FIND,
        ShizukuShell.EXTERNAL_DATA_ROOT,
        "-maxdepth", "5",
        "-type", "f",
        "-path", "*/" + HookEventCodec.DIRECTORY_NAME + "/" + HookEventCodec.FILE_NAME,
        "-exec", "cat", "{}", "+"
    )

    /**
     * Deletes every `activity_monitor` **directory** (not just the log inside it).
     *
     * Removing the directory keeps the device clean — an emptied folder would otherwise be
     * left behind for every app the hook has ever touched.
     *
     * Deliberately unscoped: a package dropped from the scope would otherwise leave an
     * orphaned log behind forever, and the user asking to clear expects the data gone.
     *
     * Three phases, because the previous single `find … -print -exec rm -rf {} +` reported
     * "已删除 N 个目录" from whatever `-print` printed, without ever checking that a deletion
     * happened (stderr was dropped entirely):
     *   1. collect the directories that exist,
     *   2. delete them,
     *   3. list again — whatever is still present was not removed.
     */
    suspend fun clearAll(): ClearResult {
        val collect = shell.run(findDirectoriesArgs())
        val found = ClearOutcome.paths(collect.stdout)
        if (found.isEmpty()) {
            return ClearResult(emptyList(), emptyList(), ClearOutcome.errorOf(collect.stderr))
        }

        val remove = shell.run(removeArgs(found))
        val verify = shell.run(findDirectoriesArgs())
        val remaining = ClearOutcome.paths(verify.stdout)
        val removed = found.filterNot { it in remaining.toSet() }

        val error = when {
            remaining.isNotEmpty() -> "仍有 " + remaining.size + " 个目录未能删除"
            else -> ClearOutcome.errorOf(remove.stderr)
        }
        return ClearResult(removed, remaining, error)
    }

    /**
     * `find` listing every hook directory.
     *
     * The same argv is used to collect and to verify, so "cleared" always means "this
     * command no longer lists it".
     */
    fun findDirectoriesArgs(): Array<String> = arrayOf(
        ShizukuShell.FIND,
        ShizukuShell.EXTERNAL_DATA_ROOT,
        "-maxdepth", "5",
        "-type", "d",
        "-name", HookEventCodec.DIRECTORY_NAME,
        "-print"
    )

    /** `rm -rf` over the collected paths; one invocation rather than one per directory. */
    fun removeArgs(paths: Collection<String>): Array<String> = buildList {
        add(ShizukuShell.RM)
        add("-rf")
        addAll(paths)
    }.toTypedArray()

    /** What a clear removed, what survived, and why. */
    data class ClearResult(
        val directories: List<String>,
        val remaining: List<String> = emptyList(),
        val error: String? = null
    ) {
        val count: Int get() = directories.size
        val isComplete: Boolean get() = remaining.isEmpty() && error == null
    }

    companion object {
        private const val TAG = "ActivityMonitor"

        /** Collapses duplicate payloads; the same launch can be reported by several processes. */
        fun dedupe(parsed: List<ParsedHookLine>): List<ParsedHookLine> =
            parsed.distinctBy { it.raw }.sortedByDescending { it.event.timestamp }

        /**
         * Maps parsed lines onto the UI model.
         *
         * The key is the raw line, **not** the list index: the reader returns newest-first,
         * so an index-based id renumbered everything whenever a new event arrived.
         */
        fun toRecords(parsed: List<ParsedHookLine>): List<ActivityRecord> =
            parsed.map { line ->
                val event = line.event
                ActivityRecord(
                    key = line.raw,
                    packageName = event.packageName,
                    activityClass = event.activityClass,
                    timestamp = event.timestamp,
                    kind = event.kind,
                    intentAction = event.intentAction,
                    intentData = event.intentData,
                    referrer = event.referrer,
                    launchedFromPackage = event.launchedFromPackage,
                    taskId = event.taskId,
                    processName = event.processName,
                    extrasSummary = event.extrasSummary
                )
            }
    }
}
