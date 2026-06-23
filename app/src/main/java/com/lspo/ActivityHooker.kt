package com.lspo

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * LSPosed / libxposed API 102 module entry.
 * Hooks Activity.onCreate in every scoped app (excluding the module itself)
 * and forwards records via ContentProvider IPC to [ActivityProvider].
 */
class ActivityHooker : XposedModule() {

    private var moduleAuthority: String? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        moduleAuthority = "${moduleApplicationInfo.packageName}.provider"
        log(Log.INFO, TAG, "ActivityMonitor loaded into ${param.processName}")
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
                val activity = chain.thisObject
                if (activity is Context) {
                    val actClass = activity.javaClass.name
                    val authority = moduleAuthority ?: return@intercept chain.proceed()
                    val uri = Uri.parse("content://$authority/activities")

                    try {
                        val values = ContentValues().apply {
                            put("package_name", pkg)
                            put("activity_class", actClass)
                            put("timestamp", System.currentTimeMillis())
                        }
                        activity.contentResolver.insert(uri, values)
                    } catch (_: Exception) {
                        // Cross-process IPC can fail silently
                    }
                }
                chain.proceed()
            }

            log(Log.INFO, TAG, "Hooked Activity.onCreate in $pkg")
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "Failed to hook $pkg: ${e.message}")
        }
    }

    companion object {
        const val TAG = "ActivityMonitor"
    }
}
