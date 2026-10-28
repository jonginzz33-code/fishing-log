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
        val mine = currentCode(a)
        Thread {
            val latest = try {
                get(BASE + "version.txt").inputStream.bufferedReader().use { it.readText().trim().toLong() }
            } catch (_: Exception) { null }
            val notes = if (latest != null && latest > mine) try {
                get(BASE + "notes.txt").inputStream.bufferedReader().use { it.readText().trim() }
            } catch (_: Exception) { "" } else ""
            main.post {
                if (latest != null && latest > mine) { latestCode = latest; latestNotes = notes; done(latest) }
                else done(null)
            }
        }.start()
    }

    fun isShowing() = dialog?.isShowing == true

    /** 큰 업데이트 창. force = 나중에 없음 (뒤로 가기 = 앱 닫기) */
    fun showDialog(a: Activity, force: Boolean) {
        if (isShowing() || a.isFinishing) return
        fun dp(v: Int) = Ui.dp(a, v)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(20), dp(22), dp(16)) }
        box.addView(Ui.text(a, 40f, Ui.TEXT, false, "🎣").apply { gravity = Gravity.CENTER }, Ui.lp(a, MATCH, WRAP))
        box.addView(Ui.text(a, 21f, Ui.TEXT, true, if (force) "업데이트해야 쓸 수 있어요" else "새 버전이 나왔어요!").apply { gravity = Gravity.CENTER },
            Ui.lp(a, MATCH, WRAP, top = 8))
        val newName = Regex("v([0-9.]+)").find(latestNotes.lineSequence().firstOrNull().orEmpty())?.groupValues?.get(1)
        box.addView(Ui.text(a, 14f, Ui.SUB, false, "").apply {
            gravity = Gravity.CENTER
            text = android.text.SpannableStringBuilder("지금 ${BuildConfigInfo.VERSION}  →  ").apply {
                val s0 = length; append("새 " + (newName ?: "버전"))
                setSpan(android.text.style.ForegroundColorSpan(Ui.GREEN), s0, length, 0)
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), s0, length, 0)
            }
        }, Ui.lp(a, MATCH, WRAP, top = 4))
        if (force) box.addView(Ui.text(a, 12f, Ui.ORANGE, false, "예전 버전은 관리자가 막아 두었어요 · 기록은 그대로 남아요").apply { gravity = Gravity.CENTER },
            Ui.lp(a, MATCH, WRAP, top = 6))
        // 이번에 바뀐 것
        val lines = latestNotes.lineSequence().drop(1).map { it.trim().removePrefix("-").trim() }.filter { it.isNotBlank() }.take(6).toList()
        if (lines.isNotEmpty()) {
            val notes = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.bg(a, Ui.CARD, 12)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            notes.addView(Ui.text(a, 11f, Ui.SUB, false, "이번에 바뀐 것"))
            lines.forEach { l -> notes.addView(Ui.text(a, 13f, Ui.TEXT, false, "•  $l").apply { setLineSpacing(0f, 1.2f) }, Ui.lp(a, MATCH, WRAP, top = 4)) }
            box.addView(notes, Ui.lp(a, MATCH, WRAP, top = 12))
        }
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0; visibility = android.view.View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.GREEN)
        }
        box.addView(bar, Ui.lp(a, MATCH, WRAP, top = 14))
        val status = Ui.text(a, 13f, Ui.SUB, false, "").apply { gravity = Gravity.CENTER; visibility = android.view.View.GONE }
        box.addView(status, Ui.lp(a, MATCH, WRAP, top = 6))
        val steps = Ui.text(a, 13f, Ui.TEXT, false,
            "1. 아래 '설치하기'를 눌러요\n2. \"출처를 알 수 없는 앱\" 허용을 물으면 '허용'을 켜고 돌아와요\n3. '업데이트' → '열기'").apply {
            setLineSpacing(0f, 1.35f)
            background = Ui.bg(a, Ui.CARD, 12); setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = android.view.View.GONE
        }
        box.addView(steps, Ui.lp(a, MATCH, WRAP, top = 10))
        val mainBtn = Ui.button(a, "지금 업데이트", true).apply {
            setTextColor(Color.parseColor("#08311F")); background = Ui.bg(a, Ui.GREEN, 14)
        }
        box.addView(mainBtn, Ui.lp(a, MATCH, WRAP, top = 14))
        val later = Ui.text(a, 13f, Ui.SUB, false, "나중에 (다음에 켤 때 다시 알려줘요)").apply {
            gravity = Gravity.CENTER; minHeight = dp(40)
            visibility = if (force) android.view.View.GONE else android.view.View.VISIBLE
        }
        box.addView(later, Ui.lp(a, MATCH, WRAP, top = 4))
        box.addView(Ui.text(a, 11f, Ui.SUB, false, "기록은 그대로 남아요").apply { gravity = Gravity.CENTER }, Ui.lp(a, MATCH, WRAP, top = 2))

        val dlg = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(ScrollView(a).apply { addView(box) })
            .setCancelable(false)
            .create()
        dlg.setCanceledOnTouchOutside(false)
        dlg.setOnKeyListener { d, code, ev ->
            if (code == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP) {
                if (force) a.finish() else d.dismiss()
                true
            } else false
        }
        later.setOnClickListener { dlg.dismiss() }

        var file: File? = null
        fun install() {
            val f = file ?: return
            if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
                status.text = "'이 출처 허용'을 켠 뒤 돌아와서 '설치하기'를 다시 눌러 주세요"
                status.setTextColor(Ui.ORANGE)
                a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
                return
            }
            val uri = FileProvider.getUriForFile(a, a.packageName + ".files", f)
            a.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        fun download() {
            mainBtn.isEnabled = false; mainBtn.alpha = 0.5f; mainBtn.text = "받는 중…"
            later.visibility = android.view.View.GONE
            bar.visibility = android.view.View.VISIBLE; bar.progress = 0
            status.visibility = android.view.View.VISIBLE; status.setTextColor(Ui.SUB)
            status.text = "새 버전 받는 중… 창을 닫지 마세요"
            Thread {
                try {
                    val out = File(a.getExternalFilesDir(null), "update.apk")
                    val c = get(BASE + "fishing-log.apk")
                    val total = c.contentLengthLong
                    c.inputStream.use { input ->
                        out.outputStream().use { os ->
                            val buf = ByteArray(32 * 1024)
                            var got = 0L; var lastPost = 0L
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                os.write(buf, 0, n); got += n
                                val now = System.currentTimeMillis()
                                if (now - lastPost > 150) {
                                    lastPost = now
                                    val g = got
                                    main.post {
                                        if (total > 0) {
                                            bar.progress = (g * 100 / total).toInt()
                                            status.text = String.format(java.util.Locale.KOREA, "%d%%  ·  %.1fMB / %.1fMB", g * 100 / total, g / 1048576.0, total / 1048576.0)
                                        } else status.text = String.format(java.util.Locale.KOREA, "%.1fMB 받는 중…", g / 1048576.0)
                                    }
                                }
                            }
                        }
                    }
                    main.post {
                        file = out
                        bar.progress = 100
                        status.text = "다 받았어요 ✓"; status.setTextColor(Ui.GREEN)
                        steps.visibility = android.view.View.VISIBLE
                        mainBtn.isEnabled = true; mainBtn.alpha = 1f; mainBtn.text = "설치하기"
                        mainBtn.setOnClickListener { install() }
                        install()
                    }
                } catch (_: Exception) {
                    main.post {
                        status.text = "받기 실패: 인터넷을 확인하고 '다시 받기'를 눌러 주세요"; status.setTextColor(Ui.ORANGE)
                        mainBtn.isEnabled = true; mainBtn.alpha = 1f; mainBtn.text = "다시 받기"
                        mainBtn.setOnClickListener { download() }
                        if (!force) later.visibility = android.view.View.VISIBLE
                    }
                }
            }.start()
        }
        mainBtn.setOnClickListener { download() }
        dlg.setOnDismissListener { if (dialog === dlg) dialog = null }
        dialog = dlg
        dlg.show()
        dlg.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
