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
4. 安装本 APK，在 LSPosed 的作用域中勾选目标应用（本模块为**动态作用域**，必须手动勾选）
5. 打开应用，授予 Sui 权限后即可使用

---

## 构建要求

| 项目 | 值 |
|------|-----|
| **compileSdk** | Android 15（API 37） |
| **minSdk** | Android 14（API 34） |
| **targetSdk** | Android 15（API 36） |
| **Java** | 17 |
| **LibXposed API** | 102 |
| **Shizuku API** | 12.2.0 |
| **Gradle** | 8.x+（使用 Gradle Wrapper） |
| **Android Gradle Plugin** | 9.2.1 |

> **LSPosed API 版本**：`module.prop` 中声明 `minApiVersion=102` / `targetApiVersion=102`，对应 LSPosed 1.9+ / LSPosed v2.0+ 或任何兼容 LibXposed API 102 的框架。

### 本地构建

```bash
./gradlew assembleDebug
```

APK 输出在 `app/build/outputs/apk/debug/`。

---

---

## 签名与发布

### 签名密钥

固定密钥库位于 **`cert/my.jks`**，随仓库一起入库。

| 项 | 值 |
|----|-----|
| 别名 | `key0` |
| 口令 | `123456` |
| 格式 | PKCS12（**store 与 key 共用同一口令**） |

> ✅ **该密钥库是公开的**，随仓库提交是有意为之：本项目为开源项目，任何人都可以
> 自行构建；它是自签名密钥、不用于任何分发凭证，公开不构成安全风险。
> CI 不需要任何签名相关的 Secret。
>
> ⚠️ 但它是**固定**的——Android 只允许**签名相同**的 APK 覆盖安装。
> 若更换密钥，老用户必须先卸载（会丢数据）才能装新版。**不要重新生成它。**
> `.gitignore` 中 `*.jks` 之后紧跟 `!cert/my.jks` 取反规则，请勿调整顺序。

### 本地构建

```bash
./gradlew assembleRelease
```

### GitHub Actions 发布

工作流 `.github/workflows/release.yml`（手动触发 `workflow_dispatch`）。

**无需配置任何 Secret**（仅用内置的 `GITHUB_TOKEN`）：

1. 拉取代码（密钥库随代码一起检出）
2. 计算版本号（上一 tag + 0.0.1，十进制满 10 进 1，起始 1.0.0）
3. 校验 `cert/my.jks` 可读
4. 构建 → `zipalign` → `apksigner` 签名 → `verify` → 发布 Release

> SDK 步骤直接校验并使用 runner 自带的 Android SDK，不使用
> `android-actions/setup-android`（该 action 在 runner 上曾导致构建在第 5 步直接失败）。

## 修改包名

如需修改应用包名，需同步更新以下文件中的 `com.lspo`：

| 文件 | 位置 |
|------|------|
| `app/build.gradle.kts` | `namespace = "com.activity.manager"` 和 `applicationId = "com.activity.manager"` |
| `app/src/main/AndroidManifest.xml` | 无需修改，`${applicationId}` 会自动跟随 |
| `app/src/main/resources/META-INF/xposed/module.prop` | 无需修改，此处不声明包名 |
| 源代码目录 | `app/src/main/java/com/activity/manager/` 整个目录需重命名 |

> **注意**：`module.prop` 不包含包名字段；LSPosed 通过 `XposedProvider` 的 `authorities`（`${applicationId}.XposedService`）自动识别模块，因此改 `applicationId` 后模块会自动适配。

---

## 功能

- 实时监控 Activity 启动（通过 LibXposed Hook `Activity.onCreate`）
- 跨进程记录：Hook 侧把事件追加写入**目标应用自己的外部目录**
  （`Android/data/<包名>/files/activity_monitor/events.log`），模块侧通过 Shizuku/Sui 读回
- 扫描只针对**当前作用域**内的包（`XposedService.getScope()`），避免遍历全部应用
- 搜索过滤（包名 / Activity 类名）
- 一键复制 Activity 类名
- 禁用 Activity 组件 / 整个 Package（通过 Shizuku/Sui，参数化调用 `pm disable --user 0`，不经过 shell）
- **禁用历史页面**：记录每一次禁用操作，可单条或一键全部恢复（`pm default-state --user 0`，即 `disable` 的逆操作），历史持久化在 SharedPreferences
- 导出报告到 Downloads（右上角分享按钮，MediaStore 写入）

> **关于恢复**：恢复使用 `default-state` 而非 `enable`。`enable` 会强制启用，可能把系统原本有意禁用的组件打开；`default-state` 只是清除覆盖、回到默认状态，是 `disable` 的正确逆操作。

> **为什么不用 ContentProvider 回传**：Hook 代码运行在被 Hook 应用自己的进程里，Android 11+ 的包可见性会让它解析不到本模块的 authority，真机表现为
> `Failed to find provider info for <applicationId>.provider` —— 即便加上 `android:forceQueryable="true"` 并在部分 ROM 上重启后**仍然失败**。
> 文件不受包可见性限制，且 Shizuku/Sui 的 `ext_data_rw` 组使其能读取其它应用的外部目录，因此改用文件通道。
>
> **为什么 Hook 写「自己」的目录**：Android 禁止应用写入**其它**应用的 `Android/data/<别的包名>/`；写自己的外部私有目录始终允许。
>
> **清除是全量的**：清空记录会删除 `Android/data` 下**所有** `activity_monitor/events.log`，
> 而不只是当前作用域内的，避免包被移出作用域后残留日志。

---

## 技术栈

- **Hook 框架**: LibXposed（LSPosed 后继框架）
- **权限提升**: Sui（通过 Shizuku API 通信）
- **UI**: Jetpack Compose + Material3
- **跨进程通信**: 外部存储日志文件（Hook 写、Shizuku/Sui 读）

---

> 开发过程中踩过的坑与真机验证结论，见 [开发经验总结.md](./开发经验总结.md)。
