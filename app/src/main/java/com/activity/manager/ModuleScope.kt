package com.activity.manager

/**
 * The set of packages this module is scoped to, as reported by the Xposed framework.
 *
 * Used to limit log scanning to the apps that can actually produce events: scanning every
 * app's directory costs a full `Android/data` traversal (hundreds of packages, ~300 ms+)
 * and mostly reads directories that will never contain a log.
 */
object ModuleScope {

    @Volatile
    private var scoped: Set<String> = emptySet()

    fun update(packages: List<String>) {
        scoped = packages.filter { it.isNotBlank() }.toSet()
    }

    fun clear() {
        scoped = emptySet()
    }

    /** Scoped package names; empty until the framework service binds. */
    fun packages(): Set<String> = scoped

    fun isKnown(): Boolean = scoped.isNotEmpty()

    /** Absolute log path for a scoped package on the primary external volume. */
    fun logPathFor(packageName: String): String =
        ShizukuShell.EXTERNAL_DATA_ROOT + "/" + packageName + "/files/" +
            HookEventCodec.DIRECTORY_NAME + "/" + HookEventCodec.FILE_NAME
}
