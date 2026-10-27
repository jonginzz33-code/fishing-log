plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.fishlog.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fishlog.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 16
        versionName = "1.6"
        // 국립해양조사원 인증키: GitHub 비밀값(KHOA_KEY)에서 빌드할 때만 넣음
        buildConfigField("String", "KHOA_KEY", "\"" + (System.getenv("KHOA_KEY") ?: "") + "\"")
    }

    buildFeatures {
        buildConfig = true
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

dependencies {
    // 서버 (Firebase)
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")

    // 구글 로그인
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // 업데이트 설치용 파일 공유
    implementation("androidx.core:core:1.13.1")

    // 지도 (키 없이 쓰는 오픈 지도)
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
