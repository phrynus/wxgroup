pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Xposed API (de.robv.android.xposed:api:82) 所在的仓库
        maven(url = "https://api.xposed.info/")
    }
}

rootProject.name = "wxgroup-min"
include(":app")
