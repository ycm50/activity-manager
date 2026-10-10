package com.activity.manager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

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
class ComponentDisabler {

    sealed interface Outcome {
        data class Success(val message: String) : Outcome
        data class Failure(val message: String) : Outcome
    }

    suspend fun disablePackage(packageName: String): Outcome = execute(
        disablePackageArgs(packageName),
        "Disabled package:$packageName"
    )

    suspend fun disableActivity(packageName: String, activityClass: String): Outcome = execute(
        disableActivityArgs(packageName, activityClass),
        "Disabled activity:$packageName/$activityClass"
    )

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

    private suspend fun execute(argv: Array<String>, successMessage: String): Outcome =
        withContext(Dispatchers.IO) {
            try {
                val process = Shizuku.newProcess(argv, null, null)
                val exitCode = process.waitFor()
                @Suppress("DEPRECATION")
                val stdout = process.inputStream.bufferedReader().readText().trim()
                val stderr = process.errorStream.bufferedReader().readText().trim()
                val detail = stdout.ifEmpty { stderr }

                // "already" means the requested state was already in effect, which is a success.
                val ok = exitCode == 0 || detail.contains("already", ignoreCase = true)
                if (ok) {
                    Outcome.Success(successMessage)
                } else {
                    Outcome.Failure(detail.ifEmpty { "exit=$exitCode" })
                }
            } catch (e: Exception) {
                Outcome.Failure(e.message ?: e.javaClass.simpleName)
            }
        }

    companion object {
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
