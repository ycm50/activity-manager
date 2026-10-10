package com.activity.manager

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

enum class ShizukuState { IDLE, REQUESTING, GRANTED }

/**
 * Owns the two-phase privilege acquisition flow:
 *
 * 1. Try the running Shizuku service.
 * 2. If the binder is absent or unresponsive, fall back to Sui (manually initialised,
 *    see [App] which disables Sui's automatic initialisation).
 *
 * State is exposed as Compose state so the UI recomposes without extra plumbing.
 * All coroutines run on the activity's [lifecycleScope], so they are cancelled with it
 * (the previous implementation leaked an un-cancelled `MainScope()` per request).
 */
class ShizukuPermissionController(private val activity: ComponentActivity) {

    var state by mutableStateOf(ShizukuState.IDLE)
        private set

    var label by mutableStateOf(LABEL_IDLE)
        private set

    /** Hint shown when privilege is missing. Null when usable. */
    var hint by mutableStateOf<String?>(null)
        private set

    /**
     * Refreshes the privilege state **without prompting**.
     *
     * Called when the screen opens. Granting stays a deliberate user action via the toolbar
     * button, so simply opening the app never raises a system dialog.
     */
    fun refreshStatus() {
        val binderAlive = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }

        if (!binderAlive) {
            state = ShizukuState.IDLE
            label = LABEL_IDLE
            hint = "未连接 Shizuku/Sui：请确认 Sui 已启动"
            return
        }

        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }

        if (granted) {
            state = ShizukuState.GRANTED
            label = detectPermissionSource()
            hint = null
        } else {
            state = ShizukuState.IDLE
            label = LABEL_IDLE
            hint = "尚未授予 Shizuku 权限：点顶栏按钮授权"
        }
    }

    fun request() {
        // Re-requesting while already granted: reset first so the UI shows progress.
        if (state == ShizukuState.GRANTED) {
            state = ShizukuState.IDLE
            label = LABEL_IDLE
        }

        state = ShizukuState.REQUESTING
        label = "Shizuku..."
        requestViaShizuku()
    }

    private fun requestViaShizuku() {
        val deferred = CompletableDeferred<Int?>()
        val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE) deferred.complete(grantResult)
        }

        try {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (_: Exception) {
            // Shizuku is not running (no binder): go straight to Sui.
            Shizuku.removeRequestPermissionResultListener(listener)
            requestViaSui()
            return
        }

        activity.lifecycleScope.launch {
            try {
                val result = withTimeout(PERMISSION_TIMEOUT_MS) { deferred.await() }
                Shizuku.removeRequestPermissionResultListener(listener)
                handleResult(result)
            } catch (_: TimeoutCancellationException) {
                Shizuku.removeRequestPermissionResultListener(listener)
                toast("Shizuku 无响应，尝试 Sui")
                requestViaSui()
            }
        }
    }

    private fun requestViaSui() {
        val suiAvailable = try {
            rikka.sui.Sui.init(activity.packageName)
        } catch (_: Exception) {
            false
        }

        if (!suiAvailable) {
            reset("Sui 不可用")
            return
        }

        label = "Sui..."

        val deferred = CompletableDeferred<Int?>()
        val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE) deferred.complete(grantResult)
        }

        try {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (_: Exception) {
            Shizuku.removeRequestPermissionResultListener(listener)
            reset("权限服务不可用")
            return
        }

        activity.lifecycleScope.launch {
            try {
                val result = withTimeout(PERMISSION_TIMEOUT_MS) { deferred.await() }
                handleResult(result)
            } catch (_: TimeoutCancellationException) {
                reset("获取权限超时")
            } finally {
                Shizuku.removeRequestPermissionResultListener(listener)
            }
        }
    }

    private fun handleResult(result: Int?) {
        if (result == PackageManager.PERMISSION_GRANTED) {
            state = ShizukuState.GRANTED
            label = detectPermissionSource()
            hint = null
        } else {
            state = ShizukuState.IDLE
            label = LABEL_IDLE
            toast("权限被拒绝")
        }
    }

    private fun reset(message: String) {
        state = ShizukuState.IDLE
        label = LABEL_IDLE
        toast(message)
    }

    private fun detectPermissionSource(): String = try {
        val uid = Shizuku.getUid()
        val level = when (uid) {
            0 -> "root"
            2000 -> "shell"
            else -> "uid=$uid"
        }
        val source = if (rikka.sui.Sui.isSui()) "sui" else "shizuku"
        "$source($level)"
    } catch (_: Exception) {
        "shizuku(?)"
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val REQUEST_CODE = 1001
        const val PERMISSION_TIMEOUT_MS = 5000L
        const val LABEL_IDLE = "Shizuku"
    }
}
