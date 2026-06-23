# Activity Monitor (LSPO)

一个基于 LibXposed 的 Android Xposed 模块，实时监控并记录所有应用的 Activity 启动信息，支持搜索、过滤、禁用组件和导出报告。

---

## 环境要求

### 运行环境

| 组件 | 版本要求 | 说明 |
|------|----------|------|
| **Root** | — | 需要 root 权限，推荐使用 [KernelSU](https://kernelsu.org/) 或 [Magisk](https://github.com/topjohnwu/Magisk) |
| **LSPosed** | v2.0+ | 基于 LibXposed 的 Xposed 框架，需在 KernelSU/Magisk 中刷入 [LSPosed](https://github.com/LSPosed/LSPosed) 模块 |
| **Sui** | v12+ | 提供 root shell 权限的 Magisk/KernelSU 模块，项目通过 Shizuku API 与 Sui 通信，[下载](https://github.com/RikkaApps/Sui) |

### 安装步骤

1. 确保设备已解锁 Bootloader 并刷入 KernelSU 或 Magisk
2. 刷入 LSPosed 框架（Zygisk 版本）
3. 刷入 Sui 模块
4. 安装本 APK，在 LSPosed 的作用域中勾选目标应用
5. 打开应用，授予 Sui 权限后即可使用

---

## 构建要求

| 项目 | 值 |
|------|-----|
| **compileSdk** | Android 15（API 37） |
| **minSdk** | Android 14（API 34） |
| **targetSdk** | Android 15（API 36） |
| **Java** | 11 |
| **LibXposed API** | 102 |
| **Shizuku API** | 12.2.0 |
| **Gradle** | 8.x+（使用 Gradle Wrapper） |
| **Android Gradle Plugin** | 9.2.1 |

### 本地构建

```bash
./gradlew assembleDebug
```

APK 输出在 `app/build/outputs/apk/debug/`。

---

## 功能

- 实时监控 Activity 启动（通过 LibXposed Hook `Activity.onCreate`）
- 跨进程记录（ContentProvider）
- 搜索过滤（包名 / Activity 类名）
- 一键复制 Activity 类名
- 禁用 Activity 组件（通过 Sui → `pm disable --user 0`）
- 禁用整个 Package
- 导出报告到 Downloads

---

## 技术栈

- **Hook 框架**: LibXposed（LSPosed 后继框架）
- **权限提升**: Sui（通过 Shizuku API 通信）
- **UI**: Jetpack Compose + Material3
- **跨进程通信**: ContentProvider
