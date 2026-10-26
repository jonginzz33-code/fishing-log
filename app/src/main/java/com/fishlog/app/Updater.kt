package com.fishlog.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub 릴리스에 올라온 새 버전 확인 → APK 받아서 설치 화면 띄우기
 * (안드로이드 규칙상 마지막 "업데이트" 버튼은 사용자가 눌러야 함)
 */
object Updater {

    private const val BASE = "https://github.com/jonginzz33-code/fishing-log/releases/latest/download/"
    private val main = Handler(Looper.getMainLooper())
    var checkedThisRun = false

    fun currentCode(a: Activity): Long {
        val info = a.packageManager.getPackageInfo(a.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
    }

    private fun get(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 10_000
        c.readTimeout = 30_000
        return c
    }

    /** 새 버전 번호를 알려줌 (없거나 실패하면 null) */
    fun check(a: Activity, done: (Long?) -> Unit) {
        val mine = currentCode(a)
        Thread {
            val latest = try {
                get(BASE + "version.txt").inputStream.bufferedReader().use { it.readText().trim().toLong() }
            } catch (_: Exception) { null }
            main.post { done(if (latest != null && latest > mine) latest else null) }
        }.start()
    }

    fun downloadAndInstall(a: Activity, progress: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            progress("처음 한 번만: '이 출처 허용'을 켜고 돌아와서 다시 눌러주세요")
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
            return
        }
        progress("새 버전 받는 중…")
        Thread {
            try {
                val out = File(a.getExternalFilesDir(null), "update.apk")
                get(BASE + "fishing-log.apk").inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
                main.post {
                    progress("설치 화면에서 '업데이트'를 눌러주세요")
                    val uri = FileProvider.getUriForFile(a, a.packageName + ".files", out)
                    a.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            } catch (e: Exception) {
                main.post { progress("받기 실패: 인터넷을 확인해 주세요") }
            }
        }.start()
    }
}
