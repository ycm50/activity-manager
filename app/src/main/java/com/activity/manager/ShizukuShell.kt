package com.activity.manager

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Runs a command through Shizuku/Sui as the privileged user.
 *
 * The command is always passed as an argv array and executed directly
 * (`Shizuku.newProcess` does not go through a shell), so no argument can ever be
 * interpreted as shell syntax.
 *
 * Failures are reported in [Result.stderr] **and logged**: an earlier revision dropped the
 * exception silently, which made "Shizuku permission not granted" look like "no records".
 */
class ShizukuShell {

    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val isSuccess: Boolean get() = exitCode == 0
        val hasOutput: Boolean get() = stdout.isNotBlank()
    }

    suspend fun run(argv: Array<String>): Result = withContext(Dispatchers.IO) {
        try {
            val process = Shizuku.newProcess(argv, null, null)
            val exitCode = process.waitFor()
            @Suppress("DEPRECATION")
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            Result(exitCode, stdout, stderr)
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "Shizuku command failed: " + argv.joinToString(" ") + " -> " + reason)
            Result(exitCode = -1, stdout = "", stderr = reason)
        }
    }

    /**
     * Human-readable reason why privileged commands cannot run yet, or null when ready.
     *
     * Distinguishes "Shizuku/Sui is not running", "permission not granted" and "granted but
     * the sticky service died", which are the three states the user has to act on.
     */
    fun unavailableReason(): String? = try {
        when {
            !Shizuku.pingBinder() -> "未连接 Shizuku/Sui：请先启动 Sui（或 Shizuku）"
            Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED ->
                "尚未授予 Shizuku 权限：请点顶栏按钮授权"
            else -> null
        }
    } catch (e: Throwable) {
        "Shizuku 不可用：" + (e.message ?: e.javaClass.simpleName)
    }

    companion object {
        private const val TAG = "ActivityMonitor"

        /** toybox `find`; absolute path because PATH is not guaranteed. */
        const val FIND = "/system/bin/find"

        /** Explicit device path avoids relying on `/sdcard` symlink resolution. */
        const val EXTERNAL_DATA_ROOT = "/storage/emulated/0/Android/data"
    }
}
