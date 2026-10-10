package com.activity.manager

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * LSPosed / libxposed API 102 module entry.
 *
 * Hooks `Activity.onCreate` in every scoped app and records the launch.
 *
 * Transport is a per-app **append-only log file**, not a ContentProvider. The provider
 * approach cannot work from a hooked process: Android 11+ package visibility prevents it
 * from resolving the module's authority (observed on-device as
 * `Failed to find provider info for <applicationId>.provider`, even with
 * `android:forceQueryable="true"`). A file is not subject to package visibility.
 *
 * Each app writes to **its own** external files directory — an app may always write there;
 * writing into the module's directory is what Android forbids. The module reads the logs
 * back through Shizuku/Sui, which holds `ext_data_rw`.
 */
class ActivityHooker : XposedModule() {

    /** Created lazily on first Activity, once the host Application is available. */
    @Volatile
    private var eventLog: HookEventLog? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "ActivityMonitor loaded into " + param.processName)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val pkg = param.packageName

        // Skip our own module package and system virtual packages
        val myPkg = moduleApplicationInfo.packageName
        if (pkg == myPkg || pkg == "system" || pkg == "android") return

        try {
            val activityClass = Class.forName("android.app.Activity")
            val bundleClass = Class.forName("android.os.Bundle")
            val onCreateMethod = activityClass.getDeclaredMethod("onCreate", bundleClass)

            hook(onCreateMethod).intercept { chain ->
                val activity = chain.thisObject as? Context
                if (activity != null) {
                    record(activity)
                }
                chain.proceed()
            }

            log(Log.INFO, TAG, "Hooked Activity.onCreate in " + pkg)
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "Failed to hook " + pkg + ": " + e.message)
        }
    }

    /**
     * Best-effort write. Runs on the host app's main thread during Activity startup, so it
     * must never throw or block noticeably.
     */
    private fun record(activity: Context) {
        try {
            val app = activity.applicationContext ?: return
            val log = eventLog ?: synchronized(this) {
                eventLog ?: HookEventLog(app).also { eventLog = it }
            }
            log.append(
                HookEvent(
                    timestamp = System.currentTimeMillis(),
                    packageName = app.packageName,
                    activityClass = activity.javaClass.name
                )
            )
        } catch (_: Throwable) {
            // Never break the host app because of monitoring.
        }
    }

    companion object {
        const val TAG = "ActivityMonitor"
    }
}
