package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import androidx.core.content.FileProvider
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 새 버전: GitHub 릴리스 확인 → 큰 창으로 알림 → 창 안에서 받기(진행 막대) → 설치 안내
 *  - force = true 면 "나중에" 없이 업데이트해야 쓸 수 있음 (관리자 '최소 버전')
 *  - 안드로이드 규칙상 마지막 "업데이트" 버튼은 사용자가 눌러야 함
 */
object Updater {

    private const val BASE = "https://github.com/jonginzz33-code/fishing-log/releases/latest/download/"
    private val main = Handler(Looper.getMainLooper())
    var checkedThisRun = false
    /** 새 버전이 있으면 그 번호 (메뉴 '새 버전 있음' 표시용) */
    private const val RAW_README = "https://raw.githubusercontent.com/jonginzz33-code/fishing-log/main/README.md"
    @Volatile var latestCode: Long? = null
    @Volatile var latestNotes: String = ""
    private var dialog: AlertDialog? = null

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

    /** 새 버전 번호 + 바뀐 내용 (없거나 실패하면 null) */
    fun check(a: Activity, done: (Long?) -> Unit) {
        // v6.41: 플레이 스토어가 업데이트해요 (GitHub 확인은 없앰)
        main.post { done(null) }; return
        val mine = currentCode(a)
        Thread {
            val latest = try {
                get(BASE + "version.txt").inputStream.bufferedReader().use { it.readText().trim().toLong() }
            } catch (_: Exception) { null }
            // v4.8 (218): '바뀐 것'은 3번까지 다시 시도 · 그래도 안 되면 GitHub README 맨 끝 버전 내용으로
            var notes = ""
            if (latest != null && latest > mine) {
                for (i in 0 until 3) {
                    notes = try { get(BASE + "notes.txt").inputStream.bufferedReader().use { it.readText().trim() } } catch (_: Exception) { "" }
                    if (notes.isNotBlank()) break
                    try { Thread.sleep(1500) } catch (_: Exception) {}
                }
                if (notes.isBlank()) notes = try {
                    val md = get(RAW_README).inputStream.bufferedReader().use { it.readText() }
                    val k = md.lastIndexOf("\n## v")
                    if (k >= 0) md.substring(k + 1).trim() else ""
                } catch (_: Exception) { "" }
            }
            main.post {
                if (latest != null && latest > mine) { latestCode = latest; latestNotes = notes; done(latest) }
                else done(null)
            }
        }.start()
    }

    fun isShowing() = dialog?.isShowing == true

    /** v6.0 (242-2): 플레이 스토어판 — 최소 버전보다 낮으면 스토어에서 업데이트하라고 안내 */
    private fun storeDialog(a: Activity, force: Boolean) {
        fun openStore() {
            val id = a.packageName
            try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id"))) }
            catch (_: Exception) {
                try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id"))) } catch (_: Exception) {}
            }
        }
        val b = AlertDialog.Builder(a, R.style.FlAlert)
            .setTitle("🎣 새 버전이 있어요")
            .setMessage(if (force) "이 버전은 더 이상 쓸 수 없어요. 플레이 스토어에서 업데이트해 주세요." else "플레이 스토어에서 업데이트할 수 있어요.")
            .setPositiveButton("플레이 스토어 열기") { _, _ -> openStore(); if (force) a.finish() }
            .setCancelable(!force)
        if (force) b.setNegativeButton("앱 닫기") { _, _ -> a.finish() } else b.setNegativeButton("나중에", null)
        dialog = b.show()
    }

    /** 업데이트 창 (v6.41: 스토어판만 · force = 이 버전은 더 이상 못 씀) */
    fun showDialog(a: Activity, force: Boolean) {
        if (isShowing() || a.isFinishing) return
        storeDialog(a, force)
    }
}
