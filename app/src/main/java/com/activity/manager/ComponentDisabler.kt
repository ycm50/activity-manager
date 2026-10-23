package com.activity.manager

import android.util.Log

/**
 * Disables and re-enables packages/components through Shizuku/Sui.
 *
 * Every argument is a separate argv entry. The previous implementation built a
 * `sh -c "pm disable --user 0 <value>"` string, so a value coming from the exported
 * provider — possibly planted by a malicious app — would have been interpreted by a root
 * shell. With argv there is no shell involved, so metacharacters are inert.
 *
 * Arg builders are pure and suspend entry points return an [Outcome] instead of showing a
 * toast, so [DisableHistoryStore] can record exactly what succeeded.
 */
class ComponentDisabler(private val shell: ShizukuShell = ShizukuShell()) {

    sealed interface Outcome {
        data class Success(val message: String) : Outcome
        data class Failure(val message: String) : Outcome
        /** Refused locally: no privileged command was executed at all. */
        data class Refused(val message: String) : Outcome
    }

    suspend fun disablePackage(packageName: String): Outcome {
        refuseIfProtected(packageName)?.let { return it }
        return execute(
            disablePackageArgs(packageName),
            "Disabled package:$packageName"
        )
    }

    suspend fun disableActivity(packageName: String, activityClass: String): Outcome {
        // Activity-level and package-level are treated the same: disabling one activity of the
        // installer is as harmful as disabling the package, and the requirement is that these
        // components are never disabled.
        refuseIfProtected(packageName)?.let { return it }
        return execute(
            disableActivityArgs(packageName, activityClass),
            "Disabled activity:$packageName/$activityClass"
        )
    }

    /**
     * Last line of defence against disabling a system-critical package.
     *
     * The UI asks [DangerousComponentPolicy] first (which also sees the dynamic roles), but a
     * request that reaches this class must still never run `pm` for a protected package — the
     * whole point is that the device cannot be broken even if a call path misses the policy.
     *
     * Restores are deliberately **not** guarded: if something did get disabled, putting it
     * back must always be possible.
     */
    private fun refuseIfProtected(packageName: String): Outcome.Refused? {
        val entry = DangerousPackages.entryOf(packageName) ?: return null
        if (entry.level != RiskLevel.BLOCK) return null

        Log.w(TAG, "Refused to disable protected package " + packageName)
        return Outcome.Refused(entry.displayName + "：" + entry.reason)
    }

    /**
     * Restores a previously disabled component.
     *
     * `default-state` is the correct inverse of `disable`: it clears the override and
     * returns the component to whatever it was declared/blessed as, whereas `enable` would
     * force-enable something that may have been disabled on purpose.
     */
    suspend fun restore(entry: DisableHistoryEntry): Outcome = execute(
        restoreArgs(entry.component),
        "Restored:${entry.component}"
    )

    /**
     * Runs one `pm` command through [ShizukuShell].
     *
     * Sharing the runner (rather than spawning here) keeps a single place responsible for
     * draining the child's pipes concurrently — reading after `waitFor()` deadlocks when a
     * command writes more than the pipe buffer holds.
     */
    private suspend fun execute(argv: Array<String>, successMessage: String): Outcome {
        val result = shell.run(argv)
        val detail = result.stdout.trim().ifEmpty { result.stderr.trim() }

        // "already" means the requested state was already in effect, which counts as success.
        val ok = result.isSuccess || detail.contains("already", ignoreCase = true)
        return if (ok) {
            Outcome.Success(successMessage)
        } else {
            Outcome.Failure(detail.ifEmpty { "exit=" + result.exitCode })
        }
    }

    companion object {
        private const val TAG = "ActivityMonitor"

        /** Binary name of the package manager; never routed through a shell. */
        const val PM = "pm"
        const val DISABLE = "disable"
        const val DEFAULT_STATE = "default-state"
        const val USER_FLAG = "--user"
        const val USER_ID = "0"

        fun disablePackageArgs(packageName: String): Array<String> =
            arrayOf(PM, DISABLE, USER_FLAG, USER_ID, packageName)

        fun disableActivityArgs(packageName: String, activityClass: String): Array<String> =
            arrayOf(PM, DISABLE, USER_FLAG, USER_ID, "$packageName/$activityClass")

        /** Inverse of [disablePackageArgs] / [disableActivityArgs]. */
        fun restoreArgs(component: String): Array<String> =
            arrayOf(PM, DEFAULT_STATE, USER_FLAG, USER_ID, component)
    }
}
