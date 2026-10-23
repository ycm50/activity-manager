package com.activity.manager

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * LSPosed / libxposed API 102 module entry.
 *
 * Hooks `Activity.onCreate` **and** `Activity.onNewIntent` in every scoped app and records the
 * launch. The second hook exists because a `singleTop` / `singleTask` / `singleInstance`
 * activity is reused instead of recreated: launching such an app a second time fires
 * `onNewIntent` only, so an `onCreate`-only hook recorded nothing at all for those launches.
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

    /**
     * True once the framework methods have been hooked in this process.
     *
     * The targets are framework classes, so hooking them twice records every launch twice with
     * different timestamps — and line de-duplication only collapses byte-identical lines, so
     * the duplicate would survive as a real duplicate entry. The runtime creates one module
     * instance per process, so a per-instance flag is enough.
     */
    @Volatile
    private var activityHooked = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "ActivityMonitor loaded into " + param.processName)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val pkg = param.packageName

        // Skip our own module package and system virtual packages
        val myPkg = moduleApplicationInfo.packageName
        if (pkg == myPkg || pkg == "system" || pkg == "android") return

        synchronized(this) {
            if (activityHooked) return

            try {
                val activityClass = Class.forName("android.app.Activity")
                val bundleClass = Class.forName("android.os.Bundle")
                val intentClass = Class.forName("android.content.Intent")

                hook(activityClass.getDeclaredMethod("onCreate", bundleClass)).intercept { chain ->
                    val activity = chain.thisObject as? Activity
                    if (activity != null) {
                        record(activity, HookEventKind.CREATED, activity.intent)
                    }
                    chain.proceed()
                }

                // onNewIntent is `protected`, declared on the framework Activity; it fires for
                // a reused instance and is the only signal for those launches.
                hook(activityClass.getDeclaredMethod("onNewIntent", intentClass)).intercept { chain ->
                    val activity = chain.thisObject as? Activity
                    if (activity != null) {
                        val intent = chain.args.firstOrNull() as? Intent
                        record(activity, HookEventKind.NEW_INTENT, intent)
                    }
                    chain.proceed()
                }

                activityHooked = true
                log(Log.INFO, TAG, "Hooked Activity.onCreate/onNewIntent in " + pkg)
            } catch (e: Throwable) {
                log(Log.WARN, TAG, "Failed to hook " + pkg + ": " + e.message)
            }
        }
    }

    /**
     * Best-effort write. Runs on the host app's main thread during Activity startup, so it
     * must never throw or block noticeably — every platform call below is individually
     * guarded, because a single unexpected `SecurityException` would break the host app.
     */
    private fun record(activity: Activity, kind: HookEventKind, intent: Intent?) {
        try {
            val app = activity.applicationContext ?: return
            val log = eventLog ?: synchronized(this) {
                eventLog ?: HookEventLog(app).also { eventLog = it }
            }
            log.append(
                HookEvent(
                    timestamp = System.currentTimeMillis(),
                    packageName = app.packageName,
                    activityClass = activity.javaClass.name,
                    kind = kind,
                    intentAction = intent?.action,
                    intentData = intent?.dataString?.let { clamp(it, MAX_DATA_LENGTH) },
                    referrer = describeReferrer(runCatching { activity.referrer }.getOrNull()),
                    launchedFromPackage = runCatching {
                        activity.launchedFromPackage?.takeIf { it.isNotEmpty() }
                    }.getOrNull(),
                    taskId = runCatching { activity.taskId }.getOrNull(),
                    processName = runCatching { Application.getProcessName() }.getOrNull(),
                    extrasSummary = describeExtras(intent)
                )
            )
        } catch (_: Throwable) {
            // Never break the host app because of monitoring.
        }
    }

    /**
     * Normalises `getReferrer()` to something readable.
     *
     * It is normally `android-app://<package>`, in which case the package alone is the useful
     * part; a web referrer is kept as-is (truncated).
     */
    private fun describeReferrer(uri: Uri?): String? {
        if (uri == null) return null
        return try {
            if (uri.scheme == "android-app") {
                uri.host?.takeIf { it.isNotEmpty() }
            } else {
                clamp(uri.toString(), MAX_DATA_LENGTH)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Builds a small digest of the intent extras: `key:value` pairs, scalars only.
     *
     * Parcelables and serializables are skipped deliberately — reading them can run arbitrary
     * app code, and the values would be meaningless in a log anyway. Sensitive keys are
     * masked and values clamped by [SensitiveKeys].
     */
    private fun describeExtras(intent: Intent?): String? {
        val extras = intent?.extras ?: return null
        return try {
            val keys = extras.keySet()
            if (keys.isEmpty()) return null

            val parts = ArrayList<Pair<String, String>>(minOf(keys.size, MAX_EXTRA_FIELDS))
            for (key in keys) {
                if (parts.size >= MAX_EXTRA_FIELDS) break
                val value = when (val raw = extras.get(key)) {
                    is String -> raw
                    is Int -> raw.toString()
                    is Long -> raw.toString()
                    is Boolean -> raw.toString()
                    is Short -> raw.toString()
                    is Byte -> raw.toString()
                    is Char -> raw.toString()
                    is Float -> raw.toString()
                    is Double -> raw.toString()
                    else -> continue
                }
                parts += key to SensitiveKeys.display(key, value)
            }
            if (parts.isEmpty()) null else HookExtras.encodeSummary(parts)
        } catch (_: Throwable) {
            null
        }
    }

    private fun clamp(value: String, max: Int): String =
        if (value.length <= max) value else value.substring(0, max) + "…"

    companion object {
        const val TAG = "ActivityMonitor"

        /** Kept small: this runs on the host's main thread during startup. */
        private const val MAX_EXTRA_FIELDS = 5

        /** Intent data / web referrer are truncated before the 512-char field cap applies. */
        private const val MAX_DATA_LENGTH = 200
    }
}
