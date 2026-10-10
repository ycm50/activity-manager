plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

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
        versionCode = 1
        versionName = "1.0"


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
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}