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
 * 🎮 미니게임 (v4.2.2 · 메인 둘째 줄 '🎮 게임' 칸 · 테스트 중이라 관리자만 · v4.2.5 랭킹)
 *  - 🎣 찌낚시 손맛 · 🐙 쭈꾸미 손맛 (assets/games 폴더의 html 파일을 앱 안 화면으로)
 *  - 쭈꾸미: 폰 흔들기 센서(devicemotion)로 고패질 · 챔질 · 감기, 바닥 찍을 때 진동 (앱 진동으로 세기까지)
 *  - 해 보고 재밌으면 모든 사람 메뉴로 옮길 예정
 *  - v4.4: 관리자 메뉴 '🧪 미니게임 테스트판' (assets/games 의 _test.html 파일) — 새 기능을 먼저 시험, 괜찮으면 본 게임에 덮어씀
 *    테스트판은 랭킹에 안 올리고, 오늘 물때 · 바람을 FL.env() 로 넘겨줌
 */
object MiniGame {

    /** 게임 화면(JS)에서 부르는 진동: FL.vibrate("45,90,28", 세기 1~255) */
    /** 테스트판에 넘겨줄 오늘 물때 · 바람 (JSON) · 받기 전엔 "" */
    @Volatile private var envJson: String = ""

    private class Bridge(val c: Context, val onClose: () -> Unit, val onRank: (String) -> Unit) {
        /** 🧪 테스트판: {st 관측소, ext:[{m 0시부터 분, h 1만조/0간조}], wind:[24], dir:[24], wave:[24]} */
        @JavascriptInterface
        fun env(): String = envJson

        @JavascriptInterface
        fun close() = onClose()

        /** 한 마리 잡았을 때: 게임("bobber" · "jj"), 어종, 크기(cm) 또는 무게(g) → 랭킹 */
        @JavascriptInterface
        fun gameCatch(game: String, species: String, value: Int) {
            try { GameRank.report(c, game, species, value) } catch (_: Exception) {}
        }

        /** 게임 화면의 🏆 랭킹 버튼 */
        @JavascriptInterface
        fun openRank(game: String) = onRank(game)

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
        val items = arrayOf("🎣 바다찌낚시 (갯바위 · 방파제)", "🐙 쭈꾸미 선상 (폰 들었다 내리기 · 진동)", "🏆 랭킹 보기")
        AlertDialog.Builder(a, R.style.FlAlert)
            .setTitle("🎮 미니게임")
            .setItems(items) { _, i -> when (i) { 0 -> open(a, "bobber.html"); 1 -> open(a, "jjukkumi.html"); else -> GameRank.show(a) } }
            .setNegativeButton("닫기", null).show()
    }

    /** 🧪 관리자 메뉴 → 미니게임 테스트판 (loc = 물때 · 바람 받을 위치) */
    fun pickTest(a: Activity, loc: Pair<Double, Double>?) {
        loadEnv(a, loc)
        val items = arrayOf("🐙 쭈꾸미 선상 · 테스트 (하루 출조 · 배 흔들림 · 선장 방송 · 바닥 진동 · 도감)",
            "🎣 바다찌낚시 · 테스트 (밑밥 · 수심 · 바람 · 칭호)")
        AlertDialog.Builder(a, R.style.FlAlert)
            .setTitle("🧪 미니게임 테스트판 · 랭킹 X · 기록은 이 폰에만")
            .setItems(items) { _, i -> open(a, if (i == 0) "jjukkumi_test.html" else "bobber_test.html") }
            .setNegativeButton("닫기", null).show()
    }

    /** 오늘 물때(만조 · 간조) + 시간별 바람 · 파고를 받아 JSON 으로 (못 받으면 게임이 달 계산 · 랜덤으로) */
    private fun loadEnv(a: Activity, loc: Pair<Double, Double>?) {
        envJson = ""
        if (loc == null) return
        val app = a.applicationContext
        val j = org.json.JSONObject()
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Seoul")).apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val day0 = cal.timeInMillis
        fun publish() { synchronized(j) { envJson = j.toString() } }
        Tide.fetch(app, loc.first, loc.second, System.currentTimeMillis()) { d, _ ->
            if (d == null) return@fetch
            try {
                val arr = org.json.JSONArray()
                Tide.extremes(d).forEach { e ->
                    arr.put(org.json.JSONObject().put("m", ((e.time - day0) / 60_000L).toInt()).put("h", if (e.high) 1 else 0))
                }
                synchronized(j) {
                    j.put("ext", arr)
                    Tide.stationFor(app, loc.first, loc.second)?.let { j.put("st", it.name) }
                }
                publish()
            } catch (_: Exception) {}
        }
        Weather.fetch(app, loc.first, loc.second, day0) { day ->
            if (day.hours.isEmpty()) return@fetch
            try {
                val w = org.json.JSONArray(); val dr = org.json.JSONArray(); val wv = org.json.JSONArray()
                for (h in 0 until 24) {
                    val hr = day.at(h)
                    w.put(hr?.wind ?: org.json.JSONObject.NULL); dr.put(hr?.dir ?: org.json.JSONObject.NULL); wv.put(hr?.wave ?: org.json.JSONObject.NULL)
                }
                synchronized(j) { j.put("wind", w); j.put("dir", dr); j.put("wave", wv) }
                publish()
            } catch (_: Exception) {}
        }
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
            addJavascriptInterface(Bridge(a.applicationContext, { a.runOnUiThread { dlg.dismiss() } },
                { g -> a.runOnUiThread { GameRank.show(a, g) } }), "FL")
            loadUrl("file:///android_asset/games/$file")
        }
        root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        dlg.setContentView(root)
        dlg.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dlg.setOnDismissListener { try { web.stopLoading(); web.destroy() } catch (_: Exception) {} }
        // 뒤로 가기: 게임이 막으면(테스트판 하루 출조 중 → '그만할까요?') 안 닫음
        dlg.setOnKeyListener { _, code, ev ->
            if (code == android.view.KeyEvent.KEYCODE_BACK && ev.action == android.view.KeyEvent.ACTION_UP) {
                try {
                    web.evaluateJavascript("(window.flBeforeClose&&window.flBeforeClose())?'1':'0'") { r ->
                        if (r?.contains("1") != true) dlg.dismiss()
                    }
                } catch (_: Exception) { dlg.dismiss() }
                true
            } else code == android.view.KeyEvent.KEYCODE_BACK
        }
        dlg.show()
    }
}
