package com.fishlog.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import java.util.Locale

/**
 * v5.1: 🌬 윈디(Windy) 바람 지도 — 관리자 테스트
 *  - 윈디 무료 지도 넣기(embed.windy.com) · 유럽 모델(ECMWF) · 단위 m/s
 *  - 기준점에 맞춰 열고, 아래에 그 지점 시간별 예보(윈디 화면 그대로)
 *  - 인터넷 필요 · 우리 서버는 안 씀
 */
object Windy {

    fun url(lat: Double, lon: Double): String = String.format(Locale.US,
        "https://embed.windy.com/embed.html?type=map&location=coordinates&metricRain=mm&metricTemp=%%C2%%B0C&metricWind=m%%2Fs" +
            "&zoom=9&overlay=wind&product=ecmwf&level=surface&lat=%.4f&lon=%.4f&detailLat=%.4f&detailLon=%.4f&detail=true&message=true",
        lat, lon, lat, lon)

    @SuppressLint("SetJavaScriptEnabled")
    fun show(a: Activity, lat: Double, lon: Double, place: String) {
        fun dp(v: Int) = Ui.dp(a, v)
        val dlg = Dialog(a, R.style.FlFull)
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        val bar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(10), dp(8))
        }
        bar.addView(Ui.text(a, 15f, Ui.TEXT, true, "🌬 윈디 바람" + if (place.isBlank()) "" else " · $place").apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(Ui.text(a, 11f, Color.parseColor("#F2C14E"), true, "관리자 테스트").apply {
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = Ui.bg(a, Color.parseColor("#2A2310"), 99, Color.parseColor("#F2C14E"))
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })
        bar.addView(Ui.text(a, 14f, Ui.TEXT, true, "닫기").apply {
            gravity = Gravity.CENTER
            background = Ui.bg(a, Ui.CARD, 99, Ui.LINE)
            setOnClickListener { dlg.dismiss() }
        }, LinearLayout.LayoutParams(dp(64), dp(36)))
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        val note = Ui.text(a, 11f, Ui.SUB, false, "윈디 지도 (유럽 ECMWF 모델 · m/s) · 아래쪽은 이 지점 시간별 예보 · 지도를 눌러 다른 곳도 볼 수 있어요").apply {
            setPadding(dp(14), 0, dp(14), dp(6))
        }
        root.addView(note, LinearLayout.LayoutParams(MATCH, WRAP))
        val web = WebView(a).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            setBackgroundColor(Color.BLACK)
            loadUrl(url(lat, lon))
        }
        root.addView(web, LinearLayout.LayoutParams(MATCH, 0, 1f))
        dlg.setContentView(root)
        dlg.setOnDismissListener { try { web.stopLoading(); web.destroy() } catch (_: Exception) {} }
        dlg.show()
    }
}
