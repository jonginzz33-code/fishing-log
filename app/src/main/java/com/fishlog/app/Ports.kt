package com.fishlog.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.widget.LinearLayout
import org.json.JSONArray
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.util.Locale

/**
 * v6.73 (369): ⚓ 지도 항구 자동 표시 — 앱 안 자료(assets/ports.json · 오픈스트리트맵 + 카카오 장소 검색으로 모음 · tools/ports_*.py)
 *  멀리 = 작은 점 · 확대 = ⚓ + 이름표(겹치면 빼기) · 누르면 카드 [물때 · 날씨] [포인트 꽂기] [길 찾기] [⭐ 즐겨찾기]
 */
object Ports {
    class Port(val name: String, val lat: Double, val lon: Double, val big: Boolean, val addr: String)

    @Volatile private var all: List<Port>? = null
    fun list(c: Context): List<Port> {
        all?.let { return it }
        val l = try {
            val a = JSONArray(c.assets.open("ports.json").bufferedReader().use { it.readText() })
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONArray(i) ?: return@mapNotNull null
                Port(o.optString(0), o.optDouble(1), o.optDouble(2), o.optInt(3) == 1, o.optString(4, ""))
            }
        } catch (_: Exception) { emptyList() }
        all = l; return l
    }

    private fun prefs(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)
    fun on(c: Context) = prefs(c).getBoolean("ports_on", true)
    fun setOn(c: Context, v: Boolean) = prefs(c).edit().putBoolean("ports_on", v).apply()

    class PortOverlay(private val ctx: Context, private val onTap: (Port) -> Unit) : Overlay() {
        private val d = ctx.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; color = Color.parseColor("#14232E") }
        private val anc = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Color.WHITE }
        private val shown = mutableListOf<Pair<Port, android.graphics.PointF>>()
        var selected: Port? = null

        override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
            if (shadow) return
            shown.clear()
            val z = map.zoomLevelDouble
            if (z < 8.5) return
            val bb = map.boundingBox
            val pts = list(ctx).filter { it.lat in bb.latSouth..bb.latNorth && it.lon in bb.lonWest..bb.lonEast }
            val proj = map.projection
            if (z < 11.0) {
                // 멀리 = 작은 점만 (큰 항구는 조금 크게)
                pts.forEach { p ->
                    val q = proj.toPixels(GeoPoint(p.lat, p.lon), null)
                    val r = (if (p.big) 4.2f else 3f) * d
                    fill.color = Color.WHITE; c.drawCircle(q.x.toFloat(), q.y.toFloat(), r + 1.4f * d, fill)
                    fill.color = if (p.big) Color.parseColor("#16334A") else Color.parseColor("#4F6B7E"); c.drawCircle(q.x.toFloat(), q.y.toFloat(), r, fill)
                    shown += p to android.graphics.PointF(q.x.toFloat(), q.y.toFloat())
                }
                return
            }
            // 가까이 = ⚓ + 이름표 · 큰 항구 먼저 · 겹치는 이름표는 빼기 (작은 선착장은 12.5 부터)
            val used = mutableListOf<RectF>()
            val order = pts.sortedByDescending { if (it == selected) 2 else if (it.big) 1 else 0 }
            for (p in order) {
                if (!p.big && z < 12.5 && p != selected) continue
                val q = proj.toPixels(GeoPoint(p.lat, p.lon), null)
                val x = q.x.toFloat(); val y = q.y.toFloat()
                val r = (if (p.big) 13f else 9.5f) * d
                txt.textSize = (if (p.big) 12.5f else 11f) * d
                val w = txt.measureText(p.name) + 12 * d
                val box = RectF(x - r, y - r, x + r + 4 * d + w, y + r)
                if (p != selected && used.any { RectF.intersects(it, box) }) {
                    fill.color = Color.parseColor("#16334A"); c.drawCircle(x, y, 3.5f * d, fill)
                    continue
                }
                used += box
                if (p == selected) { fill.color = Color.argb(110, 255, 211, 77); c.drawCircle(x, y, r + 5 * d, fill) }
                fill.color = if (p.big) Color.parseColor("#16334A") else Color.parseColor("#3D5566"); c.drawCircle(x, y, r, fill)
                stroke.strokeWidth = 2f * d; stroke.color = if (p == selected) Color.parseColor("#FFD34D") else Color.WHITE; c.drawCircle(x, y, r, stroke)
                anc.textSize = r * 1.1f; c.drawText("⚓", x, y + r * 0.38f, anc)
                val lx = x + r + 4 * d
                fill.color = if (p.big) Color.WHITE else Color.argb(225, 255, 255, 255)
                c.drawRoundRect(lx, y - 10 * d, lx + w, y + 10 * d, 8 * d, 8 * d, fill)
                c.drawText(p.name, lx + 6 * d, y + txt.textSize * 0.36f, txt)
                shown += p to android.graphics.PointF(x, y)
            }
        }

        override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
            if (!isEnabled) return false
            val hit = shown.minByOrNull { (_, s) -> (s.x - e.x) * (s.x - e.x) + (s.y - e.y) * (s.y - e.y) } ?: return false
            val dx = hit.second.x - e.x; val dy = hit.second.y - e.y
            if (dx * dx + dy * dy > (24 * d) * (24 * d)) return false
            onTap(hit.first); return true
        }
    }

    /** 항구 누르면 카드 */
    fun card(a: Activity, map: MapView, ov: PortOverlay, p: Port, putPin: (Port) -> Unit) {
        ov.selected = p; map.invalidate()
        val sh = Sheet(a, 0.42f)
        sh.head.addView(Ui.text(a, 18f, Ui.TEXT, true, "⚓ " + p.name), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val me = Tracker.last
        val sub = listOfNotNull(p.addr.ifBlank { null },
            me?.let { String.format(Locale.KOREA, "내 위치에서 %.0fkm", Spot.km(it.latitude, it.longitude, p.lat, p.lon)) })
        if (sub.isNotEmpty()) sh.body.addView(Ui.text(a, 13f, Ui.SUB, false, sub.joinToString("\n")), Ui.lp(a, -1, -2))
        fun btn(t: String, click: () -> Unit) = Ui.text(a, 14f, Ui.TEXT, true, t).apply {
            gravity = Gravity.CENTER; minHeight = Ui.dp(a, Ui.PILL_M)
            background = Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE); setOnClickListener { click() }
        }
        fun row(b1: android.view.View, b2: android.view.View) = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false
            addView(b1, LinearLayout.LayoutParams(0, -2, 1f))
            addView(b2, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = Ui.dp(a, 8) })
        }
        sh.body.addView(row(
            btn("🌊 물때 · 날씨") {
                sh.close()
                val prev = Spot.current; val mine = Spot.Place(p.name, p.lat, p.lon)
                Spot.current = mine
                TideDialog.onNextClose = { Spot.current?.let { if (Spot.same(it, mine)) Spot.current = prev } }
                TideDialog.show(a, p.lat, p.lon, System.currentTimeMillis(), null)
            },
            btn("📌 포인트 꽂기") { sh.close(); putPin(p) }), Ui.lp(a, -1, -2, top = 12))
        val fav = Spot.isFav(Spot.Place(p.name, p.lat, p.lon))
        sh.body.addView(row(
            btn("🧭 길 찾기") {
                val u = "https://map.kakao.com/link/to/" + Uri.encode(p.name) + "," + p.lat + "," + p.lon
                try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (_: Exception) { Ui.toast(a, "지도 앱을 못 열었어요") }
            },
            btn(if (fav) "⭐ 즐겨찾기됨" else "☆ 즐겨찾기") {
                if (Spot.addFav(Spot.Place(p.name, p.lat, p.lon))) Ui.toast(a, "⭐ 물때 창 즐겨찾기 지점에 넣었어요") else Ui.toast(a, "이미 즐겨찾기에 있어요")
                sh.close()
            }), Ui.lp(a, -1, -2, top = 8))
        sh.body.addView(Ui.text(a, 11.5f, Ui.SUB, false, "항구 위치 · 이름은 오픈스트리트맵 · 카카오 지도 자료예요. 작은 선착장은 빠졌을 수 있어요."), Ui.lp(a, -1, -2, top = 10))
        sh.onClose = { ov.selected = null; map.invalidate() }
        sh.show()
    }
}
