plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fishlog.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fishlog.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    // 고정 서명키: 빌드할 때마다 같은 키로 서명해야 업데이트 설치 시 기록이 지워지지 않음
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/fishlog.keystore")
            storePassword = "fishlog123"
            keyAlias = "fishlog"
            keyPassword = "fishlog123"
        }
    }

    buildTypes {
        getByName("debug") {
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
}
