plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.wxgroup"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.wxgroup"
        minSdk = 28
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            // R8：收缩 + 混淆。混淆会顺带把本模块的类名改掉，
            // 宿主更难从类名认出这是哪个模块。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 签名沿用本机 debug key：签名和已装上的 debug 包一致，
            // 所以能直接覆盖升级，不丢设置、也不用去 LSPosed 里重新勾一次。
            // 要换成自己的正式签名：换成自己的 signingConfigs，
            // 但必须卸载重装（会清掉设置，LSPosed 里的启用状态也要重勾）。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
    }

    packaging {
        resources.excludes += setOf("META-INF/**")
    }
}

dependencies {
    // Xposed API 只用于编译，不打进 APK
    compileOnly("de.robv.android.xposed:api:82")
}
