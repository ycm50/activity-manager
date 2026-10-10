package com.activity.manager

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import rikka.shizuku.ShizukuProvider

class App : Application() {

    override fun attachBaseContext(base: Context) {
        // 必须在 ShizukuProvider.onCreate() 之前调用
        // Android 初始化顺序：attachBaseContext → ContentProvider.onCreate → Application.onCreate
        ShizukuProvider.disableAutomaticSuiInitialization()
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()

        // Automatic Sui initialization was disabled above, so establish the binder here.
        // This only *connects*; it never prompts for permission — the toolbar button does that.
        // Without this, Shizuku.checkSelfPermission() has no binder and always reports "denied".
        try {
            val initialized = rikka.sui.Sui.init(packageName)
            Log.i(TAG, "Sui init: " + initialized)
        } catch (t: Throwable) {
            Log.w(TAG, "Sui init failed", t)
        }

        // Learn which apps are in scope, so log scanning can be limited to them.
        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    try {
                        ModuleScope.update(service.scope)
                        Log.i(TAG, "Module scope: " + service.scope)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to read module scope", t)
                    }
                }

                override fun onServiceDied(service: XposedService) {
                    ModuleScope.clear()
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to register Xposed service listener", t)
        }
    }

    private companion object {
        const val TAG = "ActivityMonitor"
    }
}
