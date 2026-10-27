package com.fishlog.app

import android.app.Application
import android.util.Log

object BuildConfigInfo { const val VERSION = "1.7" }

/** 앱이 죽으면 원인을 저장해 두고, 다음 실행 때 화면에 보여준다 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Cloud.appContext = applicationContext
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
