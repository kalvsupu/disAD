// ============================================================================
//  百度杀手 · 免 Root 版（BaiduKiller Lite）
import org.gradle.api.initialization.resolve.RepositoriesMode

// 国内网络优先走阿里云镜像，镜像没有的坐标会自动回落到官方仓库
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "BaiduKillerLite"
include(":app")