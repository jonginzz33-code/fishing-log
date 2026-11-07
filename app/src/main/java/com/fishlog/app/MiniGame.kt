package com.fishlog.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * 🧪 미니게임 테스트 (v4.2.2 · 관리자만)
 *  - 🎣 찌낚시 손맛 · 🐙 쭈꾸미 손맛 (assets/games/*.html 을 앱 안 화면으로)
 *  - 쭈꾸미: 폰 흔들기 센서(devicemotion)로 고패질 · 챔질 · 감기, 바닥 찍을 때 진동 (앱 진동으로 세기까지)
 *  - 해 보고 재밌으면 모든 사람 메뉴로 옮길 예정
 */
object MiniGame {

    /** 게임 화면(JS)에서 부르는 진동: FL.vibrate("45,90,28", 세기 1~255) */
    private class Bridge(val c: Context, val onClose: () -> Unit) {
        @JavascriptInterface
        fun close() = onClose()

        @Suppress("DEPRECATION")
        private val vib: Vibrator? =
            if (Build.VERSION.SDK_INT >= 31) (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            else c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

        @JavascriptInterface
        fun vibrate(csv: String, amp: Int) {
            val v = vib ?: return
            val ms = csv.split(",").mapNotNull { it.trim().toLongOrNull() }.filter { it >= 0 }
            if (ms.isEmpty() || ms.all { it == 0L }) return
            // JS 모양 [켬, 끔, 켬 …] → 안드로이드 모양 [0(쉼), 켬, 끔, 켬 …]
            val timings = LongArray(ms.size + 1); val amps = IntArray(ms.size + 1)
            val a = amp.coerceIn(1, 255)
            ms.forEachIndexed { i, t -> timings[i + 1] = t; amps[i + 1] = if (i % 2 == 0) a else 0 }
            try {
                if (v.hasAmplitudeControl()) v.vibrate(VibrationEffect.createWaveform(timings, amps, -1))
                else v.vibrate(VibrationEffect.createWaveform(timings, -1))
            } catch (_: Exception) {}
        }
    }

    fun pick(a: Activity) {
        val items = arrayOf("🎣 찌낚시 손맛 (터치)", "🐙 쭈꾸미 손맛 (폰 들었다 내리기 · 진동)")
        AlertDialog.Builder(a, R.style.FlAlert)
            .setTitle("🧪 미니게임 테스트")
            .setItems(items) { _, i -> open(a, if (i == 0) "bobber.html" else "jjukkumi.html") }
            .setNegativeButton("닫기", null).show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun open(a: Activity, file: String) {
        val dlg = Dialog(a, R.style.FlFull)
        val root = FrameLayout(a).apply { setBackgroundColor(Color.parseColor("#061521")) }
        val web = WebView(a).apply {
            setBackgroundColor(Color.parseColor("#061521"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(Bridge(a.applicationContext) { a.runOnUiThread { dlg.dismiss() } }, "FL")
            loadUrl("file:///android_asset/games/$file")
        }
        root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        dlg.setContentView(root)
        dlg.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dlg.setOnDismissListener { try { web.stopLoading(); web.destroy() } catch (_: Exception) {} }
        dlg.show()
    }
}
