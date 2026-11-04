pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Mirrors first: they avoid intermittent TLS handshake failures with
        // dl.google.com in some networks. Google/Maven Central remain fallbacks.
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
        // Legacy Xposed API (de.robv.android.xposed:api:82)
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "FnCalls"
include(":app")
