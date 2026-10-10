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
                " bytes=" + result.stdout.length + " err=" + result.stderr.take(200)
        )

        // `find` exits non-zero when it cannot enter some sandboxes; that is expected and
        // says nothing about the logs we did read. Only a total absence of output with a
        // failure is reported as an error.
        if (!result.hasOutput && !result.isSuccess && result.stderr.isNotBlank()) {
            return ReadResult(emptyList(), result.stderr)
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
     * Deletes **every** hook log, not just the scoped ones.
     *
     * Deliberately unscoped: a package removed from the scope would otherwise leave an
     * orphaned log behind forever, and the user asking to clear expects the data gone.
     */
    suspend fun clearAll() {
        shell.run(
            arrayOf(
                ShizukuShell.FIND,
                ShizukuShell.EXTERNAL_DATA_ROOT,
                "-maxdepth", "5",
                "-type", "f",
                "-path", "*/" + HookEventCodec.DIRECTORY_NAME + "/" + HookEventCodec.FILE_NAME,
                "-delete"
            )
        )
    }

    companion object {
        private const val TAG = "ActivityMonitor"

        /** Collapses duplicate payloads; the same launch can be reported by several processes. */
        fun dedupe(parsed: List<ParsedHookLine>): List<ParsedHookLine> =
            parsed.distinctBy { it.raw }.sortedByDescending { it.event.timestamp }

        /** Maps parsed lines onto the UI model, assigning stable synthetic ids. */
        fun toRecords(parsed: List<ParsedHookLine>): List<ActivityRecord> =
            parsed.mapIndexed { index, line ->
                ActivityRecord(
                    id = index.toLong() + 1L,
                    packageName = line.event.packageName,
                    activityClass = line.event.activityClass,
                    timestamp = line.event.timestamp
                )
            }
    }
}
