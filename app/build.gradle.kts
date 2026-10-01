// ============================================================================
//  百度杀手 · 免 Root 版 —— 模块构建脚本
// ============================================================================
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.baidukiller.lite"
    compileSdk = 34

    defaultConfig {
        // ★ 换成一个干净的、没有历史包袱的包名。
        //
        //   之前用 com.baidukiller.lite 是想让老用户能覆盖升级，但踩了个大坑：
        //   旧版「百度杀手」用的**无障碍组件名和新版一模一样**
        //   （com.baidukiller.lite/.SkipAdAccessibilityService），
        //   覆盖安装之后系统里那次绑定是坏的 —— 实测现象就是第一次启用报错、
        //   启用后也不工作、统计一次都不涨。这类系统级绑定问题本 App 修不了，
        //   换个组件名（= 换包名）就绕开了。
        applicationId = "com.disad"
        minSdk = 26          // Android 8.0
        targetSdk = 34       // Android 14
        versionCode = 45
        versionName = "1.18.1"
    }

    // 仓库里没有放 keystore：clone 下来的人也能直接构建（产出未签名包，
    // 用 Android Studio 或自己的 debug key 签名即可）。本地保留
    // app/lite-debug.keystore 时，才会启用下面这套固定签名。
    val hasLocalKey = file("lite-debug.keystore").exists()

    signingConfigs {
        // ------------------------------------------------------------------
        //  ⚠ 仅供本地/测试使用：口令是公开的，任何人都能用它签出"看起来一样"的包。
        //  正式对外分发请换成自己的 release keystore，并把口令放到
        //  gradle.properties（不进仓库）或环境变量里。
        // ------------------------------------------------------------------
        if (hasLocalKey) {
            create("localSign") {
                storeFile = file("lite-debug.keystore")
                storePassword = "baidukiller"
                keyAlias = "baidukiller"
                keyPassword = "baidukiller"
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
    // 纯系统 API 实现，不引入任何第三方库（无广告 SDK、无统计 SDK、无网络库）
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
}
