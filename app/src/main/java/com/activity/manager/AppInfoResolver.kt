package com.activity.manager

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/**
 * Resolves an application's display name and icon, with a graceful degradation path.
 *
 * Because `QUERY_ALL_PACKAGES` is not declared, most packages are invisible to this process:
 * `getApplicationInfo` throws `NameNotFoundException`. That is expected, not an error. The
 * three tiers are:
 *
 * 1. the maintained table in [DangerousPackages] (installation-critical system names),
 * 2. `PackageManager`, for the packages declared in the `<queries>` whitelist,
 * 3. the package name itself plus a letter avatar rendered by the UI.
 *
 * Results are cached in a small LRU: the list re-renders on every keystroke and each miss
 * costs a resource load.
 */
class AppInfoResolver(private val context: Context) {

    data class AppInfo(
        val packageName: String,
        val label: String,
        /** Null when the package is not visible; the UI draws a letter avatar instead. */
        val icon: ImageBitmap?,
        val isSystem: Boolean
    ) {
        /** First character for the placeholder avatar. */
        val initial: String get() = label.take(1).uppercase()
    }

    private val cache = LruCache<String, AppInfo>(CACHE_SIZE)

    fun resolve(packageName: String): AppInfo {
        cache.get(packageName)?.let { return it }
        return load(packageName).also { cache.put(packageName, it) }
    }

    private fun load(packageName: String): AppInfo {
        val known = DangerousPackages.displayNameOf(packageName)
        val pm = context.packageManager

        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val label = known ?: runCatching { pm.getApplicationLabel(appInfo).toString() }
                .getOrDefault(packageName)
            val icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull()?.toImageBitmap()
            AppInfo(
                packageName = packageName,
                label = label,
                icon = icon,
                isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        } catch (_: PackageManager.NameNotFoundException) {
            // Not visible: the normal case for everything outside the <queries> whitelist.
            AppInfo(
                packageName = packageName,
                label = known ?: packageName,
                icon = null,
                isSystem = SystemPackages.isLikelySystem(packageName)
            )
        } catch (_: Throwable) {
            AppInfo(
                packageName = packageName,
                label = known ?: packageName,
                icon = null,
                isSystem = SystemPackages.isLikelySystem(packageName)
            )
        }
    }

    private fun Drawable.toImageBitmap(): ImageBitmap? =
        runCatching { toBitmap(ICON_PX, ICON_PX).asImageBitmap() }.getOrNull()

    private companion object {
        const val CACHE_SIZE = 256

        /** Decoding at the rendered size keeps the LRU small (48dp @ 3x ≈ 144 px). */
        const val ICON_PX = 144
    }
}
