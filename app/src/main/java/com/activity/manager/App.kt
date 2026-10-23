package com.activity.manager

import android.app.Application
import rikka.shizuku.ShizukuProvider

class App : Application() {

    override fun attachBaseContext(base: android.content.Context) {
        // 必须在 ShizukuProvider.onCreate() 之前调用
        // Android 初始化顺序：attachBaseContext → ContentProvider.onCreate → Application.onCreate
        ShizukuProvider.disableAutomaticSuiInitialization()
        super.attachBaseContext(base)
    }
}
