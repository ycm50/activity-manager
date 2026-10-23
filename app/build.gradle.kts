plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 版本号的唯一来源：
//   - CI：release.yml 通过 -PappVersionCode=<int> -PappVersionName=<x.y.z> 注入
//   - 本地 / Android Studio：使用下面的默认值
// 用 providers.gradleProperty 而不是 findProperty，配置缓存才能正确追踪这个输入。
// 之前 versionName 写死 "1.0"，CI 的 tag 已到 1.0.3 而 APK 内仍是 1.0，无法从安装包判断版本。
val appVersionCode: Int = providers.gradleProperty("appVersionCode").orNull?.toIntOrNull() ?: 1
val appVersionName: String = providers.gradleProperty("appVersionName").orNull ?: "1.0"

android {
    namespace = "com.activity.manager"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.activity.manager"
        minSdk = 34
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName


    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        // AGP 9 derives the Kotlin compile task's jvmTarget from targetCompatibility
        // (KotlinCompileCreationAction), so this is the single place to bump both.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}