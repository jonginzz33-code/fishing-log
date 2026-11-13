plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.fishlog.app"
    // v6.0 (242-1): 안드로이드 16(API 36) 기준 — 플레이 스토어 새 앱 필수 조건
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fishlog.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 84
        versionName = "6.3"
        // 국립해양조사원 인증키: GitHub 비밀값(KHOA_KEY)에서 빌드할 때만 넣음
        buildConfigField("String", "KHOA_KEY", "\"" + (System.getenv("KHOA_KEY") ?: "") + "\"")
        // v5.1 (232): 카카오 장소 검색 REST API 키: GitHub 비밀값(KAKAO_REST_KEY)에서 빌드할 때만 넣음
        buildConfigField("String", "KAKAO_KEY", "\"" + (System.getenv("KAKAO_REST_KEY") ?: "") + "\"")
    }

    // v6.0 (242-2): 같은 코드로 두 가지 판
    //  - github: 지금처럼 GitHub에서 받아 앱 안에서 업데이트 (지금 쓰는 사람용 · 기본 빌드)
    //  - play: 플레이 스토어용 (앱 안 업데이트 · 설치 권한 없음, 스토어가 업데이트)
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            buildConfigField("boolean", "SELF_UPDATE", "true")
        }
        create("play") {
            dimension = "store"
            // com.fishlog.app 은 플레이 스토어에서 이미 다른 사람이 써서 스토어판은 새 이름 (GitHub판과 폰에 같이 깔 수 있음)
            applicationId = "kr.jogwailji.app"
            buildConfigField("boolean", "SELF_UPDATE", "false")
        }
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

    // v6.2 (242-4): 플레이 스토어 업로드 키 — GitHub 빌드가 keystore/play-upload.p12.enc 를
    // 비밀값 PLAY_KEY_PASS 로 풀어서 PLAY_KEYSTORE_FILE 에 놓을 때만 씀 (없으면 스토어용은 안 만듦)
    val playKs = System.getenv("PLAY_KEYSTORE_FILE")?.takeIf { it.isNotBlank() && file(it).exists() }
    if (playKs != null) {
        signingConfigs.create("play") {
            storeFile = file(playKs)
            storeType = "pkcs12"
            storePassword = System.getenv("PLAY_KEY_PASS")
            keyAlias = "upload"
            keyPassword = System.getenv("PLAY_KEY_PASS")
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
            if (playKs != null) signingConfig = signingConfigs.getByName("play")
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
