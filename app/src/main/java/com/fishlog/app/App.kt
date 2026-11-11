package com.fishlog.app

import android.app.Application
import android.util.Log

/** 화면 · 서버에 보이는 앱 버전 = build.gradle.kts 의 versionName (v4.2.8: 예전엔 "3.6"으로 고정돼 있었음) */
object BuildConfigInfo { val VERSION: String get() = BuildConfig.VERSION_NAME }

/** 앱이 죽으면 원인을 저장해 두고, 다음 실행 때 화면에 보여준다 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Cloud.appContext = applicationContext
        // v6.0 (242-1 · 243): 모든 화면에 여백 · 큰 화면 맞춤을 첫 화면부터
        Ui.install(this)
        // 지도 '내 위치 기록 확인': 앱이 화면에서 사라지면 자동으로 끄기
        TestTrack.install(this)
        // 지난번에 받아 둔 내 닉네임 (인터넷 없을 때도 실명 대신)
        Cloud.nick = getSharedPreferences("fishlog", MODE_PRIVATE).getString("myNick", null)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                getSharedPreferences("crash", MODE_PRIVATE).edit()
                    .putString("trace", Log.getStackTraceString(e)).commit()
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }
}
