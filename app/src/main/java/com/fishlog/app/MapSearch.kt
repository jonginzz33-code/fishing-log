package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import org.json.JSONArray
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.util.Locale

/**
 * 🔍 지도 장소 검색 (v3.9.1 · 159)
 *  - 모든 지도 맨 위 왼쪽 칩 → 지명 입력 → 결과가 하나면 바로, 여럿이면 골라서 그곳으로 이동
 *  - 최근 검색 8곳 기억 (누르면 바로 이동 · ✕ 로 지우기)
 */
object MapSearch {
    private val main = Handler(Looper.getMainLooper())
    private fun prefs(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)

    class Place(val name: String, val lat: Double, val lon: Double)

    private fun recent(c: Context): MutableList<Place> = try {
        val a = JSONArray(prefs(c).getString("map_recent", "[]"))
        (0 until a.length()).mapNotNull { i -> a.getString(i).split("|").takeIf { it.size == 3 }?.let { Place(it[0], it[1].toDouble(), it[2].toDouble()) } }.toMutableList()
    } catch (_: Exception) { mutableListOf() }

    private fun saveRecent(c: Context, l: List<Place>) {
        val a = JSONArray(); l.take(8).forEach { a.put("${it.name.replace("|", "/")}|${it.lat}|${it.lon}") }
        prefs(c).edit().putString("map_recent", a.toString()).apply()
    }

    private fun go(c: Context, map: MapView, p: Place) {
        val l = recent(c); l.removeAll { it.name == p.name }; l.add(0, p); saveRecent(c, l)
        map.controller.setZoom(14.0)
        map.controller.animateTo(GeoPoint(p.lat, p.lon))
    }

    /** 지도 막대 맨 앞에 넣는 🔍 칩 */
    fun pill(a: Activity, map: MapView): TextView {
        fun dp(v: Int) = Ui.dp(a, v)
        return Ui.text(a, 14f, Ui.TEXT, true, "🔍 장소").apply {
            gravity = Gravity.CENTER; minHeight = dp(40); setPadding(dp(14), 0, dp(14), 0)
            background = Ui.bg(a, Color.parseColor("#1A2A38"), 99, Ui.BLUE)
            setOnClickListener { open(a, map) }
        }
    }

    fun open(a: Activity, map: MapView) {
        fun dp(v: Int) = Ui.dp(a, v)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), dp(4)) }
        val et = EditText(a).apply {
            hint = "지명 · 항구 · 해수욕장 (예: 한하계, 제부도, 오천항)"; setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        box.addView(et)
        val info = Ui.text(a, 12f, Ui.SUB, false, "")
        box.addView(info, Ui.lp(a, MATCH, WRAP, top = 4))
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list, Ui.lp(a, MATCH, WRAP, top = 4))
        var dlg: AlertDialog? = null

        fun row(label: String, sub: String, onClick: () -> Unit, onDel: (() -> Unit)? = null) {
            val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
            val tb = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; isClickable = true; setOnClickListener { onClick() } }
            tb.addView(Ui.text(a, 15f, Ui.TEXT, true, label))
            if (sub.isNotBlank()) tb.addView(Ui.text(a, 11.5f, Ui.SUB, false, sub))
            r.addView(tb, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (onDel != null) r.addView(Ui.text(a, 15f, Color.parseColor("#FF8A92"), true, "✕").apply { setPadding(dp(12), dp(4), dp(4), dp(4)); setOnClickListener { onDel() } })
            list.addView(r)
        }
        fun showRecent() {
            list.removeAllViews()
            val rec = recent(a)
            info.text = if (rec.isEmpty()) "검색하면 그곳으로 지도가 옮겨가요" else "최근 검색 (누르면 이동)"
            rec.forEach { p ->
                row("🕘 ${p.name}", "", { dlg?.dismiss(); go(a, map, p) }) {
                    val l = recent(a); l.removeAll { it.name == p.name }; saveRecent(a, l); showRecent()
                }
            }
        }
        fun search() {
            val q = et.text.toString().trim()
            if (q.isBlank()) return
            if (!Geocoder.isPresent()) { info.text = "이 폰은 장소 찾기를 지원하지 않아요"; return }
            info.text = "찾는 중…"; list.removeAllViews()
            Thread {
                val found = try {
                    @Suppress("DEPRECATION")
                    Geocoder(a, Locale.KOREA).getFromLocationName(q, 8, 32.5, 123.5, 39.5, 132.5) ?: emptyList()
                } catch (_: Exception) { null }
                main.post {
                    if (found == null) { info.text = "인터넷 연결을 확인해 주세요"; return@post }
                    val places = found.map { ad ->
                        val nm = listOfNotNull(ad.featureName, ad.thoroughfare, ad.subLocality, ad.locality, ad.subAdminArea, ad.adminArea)
                            .distinct().filter { it.isNotBlank() && !it.all { ch -> ch.isDigit() } }
                        val full = ad.getAddressLine(0) ?: nm.reversed().joinToString(" ")
                        Place(nm.firstOrNull() ?: q, ad.latitude, ad.longitude) to full.removePrefix("대한민국 ")
                    }
                    if (places.isEmpty()) { info.text = "'$q'을(를) 못 찾았어요 · 시·군 이름을 같이 써 보세요 (예: 보령 오천항)"; return@post }
                    if (places.size == 1) { dlg?.dismiss(); go(a, map, Place(q, places[0].first.lat, places[0].first.lon)); return@post }
                    info.text = "${places.size}곳을 찾았어요 · 골라 주세요"
                    places.forEach { (p, full) -> row("📍 ${p.name}", full, { dlg?.dismiss(); go(a, map, Place("$q · ${p.name}".take(30), p.lat, p.lon)) }) }
                }
            }.start()
        }
        et.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false }
        showRecent()
        dlg = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("🔍 장소 찾기")
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton("찾기", null)
            .setNegativeButton("닫기", null).show()
        // '찾기'를 눌러도 창이 닫히지 않게 (결과를 골라야 닫힘)
        dlg?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { search() }
        et.requestFocus()
    }
}
