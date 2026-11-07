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
 *  - v3.9.2 (162): 치는 동안 비슷한 이름 목록 (해수욕장 · 항 · 해변 · 포구 붙여서도 찾기 · 물때 지점) · 친 글자 강조
 *  - v4.2: 폰 주소 찾기는 주소 위주라 호수 · 방파제 · 섬 이름을 잘 못 찾음 → 오픈스트리트맵 지명 검색도 같이 (예: 시화호)
 *          (오픈스트리트맵 규칙: 1초에 1번까지 · 같은 검색어는 폰에 기억)
 */
object MapSearch {
    private val main = Handler(Looper.getMainLooper())

    // ---- 오픈스트리트맵(Nominatim) 지명 검색 ----
    private val osmCache = HashMap<String, List<Pair<Place, String>>>()
    @Volatile private var osmLast = 0L
    private val osmLock = Any()

    /** 백그라운드 스레드에서 호출. wait = true 면 1초 규칙을 기다려서라도 찾음, false 면 너무 빠르면 건너뜀 */
    fun osm(q: String, wait: Boolean): List<Pair<Place, String>>? {
        synchronized(osmCache) { osmCache[q]?.let { return it } }
        synchronized(osmLock) {
            val gap = System.currentTimeMillis() - osmLast
            if (gap < 1100) { if (!wait) return null; Thread.sleep(1100 - gap) }
            osmLast = System.currentTimeMillis()
        }
        return try {
            val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&countrycodes=kr&limit=8&accept-language=ko&q=" +
                java.net.URLEncoder.encode(q, "UTF-8")
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", "FishLog/${BuildConfig.VERSION_NAME} (Android fishing log app)")
            c.setRequestProperty("Accept-Language", "ko")
            if (c.responseCode !in 200..299) return null
            val arr = JSONArray(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val out = (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val lat = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
                val lon = o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
                val parts = o.optString("display_name").split(", ").map { it.trim() }
                    .filter { it.isNotBlank() && it != "대한민국" && !it.all { ch -> ch.isDigit() || ch == '-' } }
                val name = o.optString("name").ifBlank { parts.firstOrNull() ?: q }
                Place(name, lat, lon) to parts.reversed().joinToString(" ")
            }
            synchronized(osmCache) { osmCache[q] = out }
            out
        } catch (_: Exception) { null }
    }
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
        // v3.9.3 (170): 해루질 참고용을 켠 사람은 검색한 곳 주변을 미리 받아 둠 (바다에서 인터넷 약할 때)
        Khoa.autoPrefetch(c, p.lat, p.lon, p.name.removePrefix("🌊 ").substringBefore(" · ").take(20))
    }

    /** 지도 막대 맨 앞에 넣는 🔍 칩 */
    fun pill(a: Activity, map: MapView): TextView {
        fun dp(v: Int) = Ui.dp(a, v)
        return Ui.text(a, 14f, Ui.TEXT, false, "🔍 장소").apply {
            gravity = Gravity.CENTER; minHeight = dp(40); setPadding(dp(14), 0, dp(14), 0)
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.parseColor("#1A2A38")); setStroke(Ui.dp(a, 1), Ui.BLUE) }
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
        // v3.9.2 (162): 치는 동안 비슷한 이름을 커튼처럼 · 'q 해수욕장 · q항 · q 해변 · q 포구' 도 같이 찾기 · 물때 지점 이름
        var seq = 0
        fun hl(label: String, q: String): CharSequence {
            val sp = android.text.SpannableString(label)
            val i = label.indexOf(q)
            if (q.isNotBlank() && i >= 0) {
                sp.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#FFB27A")), i, i + q.length, 0)
                sp.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), i, i + q.length, 0)
            }
            return sp
        }
        fun rowHl(label: String, q: String, sub: String, onClick: () -> Unit) {
            row(label, sub, onClick)
            ((list.getChildAt(list.childCount - 1) as? LinearLayout)?.getChildAt(0) as? LinearLayout)?.let { tb ->
                (tb.getChildAt(0) as? TextView)?.text = hl(label, q)
            }
        }
        fun search(auto: Boolean = false) {
            val q = et.text.toString().trim()
            if (q.isBlank()) { showRecent(); return }
            val my = ++seq
            if (!auto) info.text = "찾는 중…"
            val stations = try { Tide.stations(a).filter { it.name.contains(q) }.take(4) } catch (_: Exception) { emptyList() }
            Thread {
                val qs = linkedSetOf(q)
                if (listOf("해수욕장", "항", "해변", "포구", "도").none { q.endsWith(it) }) { qs.add("$q 해수욕장"); qs.add("${q}항"); qs.add("$q 해변"); qs.add("$q 포구") }
                var anyOk = false
                val found = ArrayList<Pair<Place, String>>()
                if (Geocoder.isPresent()) qs.forEach { qq ->
                    if (my != seq) return@Thread
                    try {
                        @Suppress("DEPRECATION")
                        val r = Geocoder(a, Locale.KOREA).getFromLocationName(qq, if (qq == q) 6 else 3, 32.5, 123.5, 39.5, 132.5) ?: emptyList()
                        anyOk = true
                        r.forEach { ad ->
                            val nm = listOfNotNull(ad.featureName, ad.thoroughfare, ad.subLocality, ad.locality, ad.subAdminArea, ad.adminArea)
                                .distinct().filter { it.isNotBlank() && !it.all { ch -> ch.isDigit() } }
                            val full = (ad.getAddressLine(0) ?: nm.reversed().joinToString(" ")).removePrefix("대한민국 ")
                            val name = (nm.firstOrNull() ?: qq).let { if (it.contains(q)) it else if (qq != q) qq else it }
                            found.add(Place(name, ad.latitude, ad.longitude) to full)
                        }
                    } catch (_: Exception) {}
                }
                // 오픈스트리트맵 지명 (호수 · 섬 · 방파제 등, 예: 시화호) — 항상 같이 (1초 규칙은 기다려서 지킴)
                if (my != seq) return@Thread
                osm(q, wait = true)?.let { r ->
                    anyOk = true
                    found.addAll(0, r)   // 이름이 정확히 맞는 지명을 먼저
                }
                // 가까운 같은 곳(약 300m) 한 번만
                val uniq = ArrayList<Pair<Place, String>>()
                found.forEach { f -> if (uniq.none { Math.abs(it.first.lat - f.first.lat) < 0.003 && Math.abs(it.first.lon - f.first.lon) < 0.003 }) uniq.add(f) }
                main.post {
                    if (my != seq) return@post
                    list.removeAllViews()
                    stations.forEach { st -> rowHl("🌊 ${st.name}", q, "물때 지점", { dlg?.dismiss(); go(a, map, Place(st.name, st.lat, st.lon)) }) }
                    uniq.take(10).forEach { (p, full) -> rowHl("📍 ${p.name}", q, full, { dlg?.dismiss(); go(a, map, Place(p.name.take(30), p.lat, p.lon)) }) }
                    val n = stations.size + uniq.take(10).size
                    info.text = when {
                        n > 0 -> "비슷한 곳 ${n}곳 · 골라 주세요"
                        !anyOk -> "인터넷 연결을 확인해 주세요"
                        else -> "'$q'을(를) 못 찾았어요 · 시·군 이름을 같이 써 보세요 (예: 보령 오천항)"
                    }
                }
            }.start()
        }
        val typing = Runnable { search(true) }
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                main.removeCallbacks(typing)
                val t = s?.toString()?.trim() ?: ""
                if (t.isEmpty()) { seq++; showRecent() } else if (t.length >= 2) { info.text = "찾는 중…"; main.postDelayed(typing, 500) }
            }
        })
        et.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false }
        showRecent()
        dlg = AlertDialog.Builder(a, R.style.FlAlert)
            .setTitle("🔍 장소 찾기")
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton("찾기", null)
            .setNegativeButton("닫기", null).show()
        // '찾기'를 눌러도 창이 닫히지 않게 (결과를 골라야 닫힘)
        dlg?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { search() }
        et.requestFocus()
    }
}
