import java.util.Properties

// ============================================================================
//  disAD —— 模块构建脚本
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 仓库里没有放 keystore：clone 下来的人也能直接构建（产出未签名包，
// 用 Android Studio 或自己的 debug key 签名即可）。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val devStore = localProps.getProperty("DEV_STORE_FILE") ?: "disad-dev.keystore"
val devStorePass = localProps.getProperty("DEV_STORE_PASSWORD") ?: "android"
val devKeyAlias = localProps.getProperty("DEV_KEY_ALIAS") ?: "disad"
val devKeyPass = localProps.getProperty("DEV_KEY_PASSWORD") ?: "android"
val hasLocalKey = file(devStore).exists()

android {
    namespace = "com.kalvsupu.disad"
    compileSdk = 34
    // build-tools 35 的 zipalign 支持 -P 16：让未压缩的 .so 按 16KB 页对齐
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.disad"
        minSdk = 26          // Android 8.0
        targetSdk = 34       // Android 14
        // 只打 arm64：x86/x86_64 会额外占 40MB，而真机都是 arm64
        ndk { abiFilters += listOf("arm64-v8a") }
        versionCode = 73
        versionName = "2.6.1"
    }

    signingConfigs {
        // 仅供本地/测试使用：默认口令是 Android debug keystore 的公开约定值。
        if (hasLocalKey) {
            create("localSign") {
                storeFile = file(devStore)
                storePassword = devStorePass
                keyAlias = devKeyAlias
                keyPassword = devKeyPass
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            if (hasLocalKey) signingConfig = signingConfigs.getByName("localSign")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasLocalKey) signingConfig = signingConfigs.getByName("localSign")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = false
        viewBinding = false
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // 纯系统 API 实现，不引入任何广告/统计/网络 SDK
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
    // OCR 兜底：onnxruntime（仅 arm64）+ 10.4MB 中文识别模型（assets/ocr/rec.onnx）
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}
