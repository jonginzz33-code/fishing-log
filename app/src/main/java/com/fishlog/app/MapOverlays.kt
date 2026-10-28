package com.fishlog.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Typeface
import android.view.MotionEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.min

/** 지도 위 점 하나. kind = 묶음 요약에 쓰는 이름 (예: "주꾸미", "철수 주꾸미") */
class MapDot(val point: GeoPoint, val color: Int, val title: String, val kind: String,
             val time: Long = 0L, val who: String = "", val species: String = "",
             /** 해루질 유료 자리 → 점 옆에 작게 "유료" */
             val paid: Boolean = false,
             /** 0 = 내 기록, 1 = 그룹 친구, 2 = 전체 게시판 */
             val src: Int = 0,
             /** 게시판 기록: 대략 위치 범위(m), 0 = 정확한 점 */
             val radiusM: Double = 0.0)

/**
 * 조과 점 그리기. 화면에서 가까이 겹치는 점은 하나로 묶어서
 * 크게 그리고 가운데에 마릿수 숫자를 씀. 확대하면 자연히 풀림.
 */
class DotsOverlay(
    ctx: Context,
    var dots: List<MapDot>,
    private val faded: Boolean,
    private val onTap: ((List<MapDot>) -> Unit)?
) : Overlay() {

    /** 화면에서 이 거리(dp) 안의 점은 하나로 묶음 */
    var mergeDp = 18f
    /** 지난 기록: 색 점 대신 어종 그림 + 마릿수 + 이름표 */
    var icons = false
    private val art = SpeciesArtView(ctx)
    private val PINK = Color.parseColor("#F07AA0")
    private val PURPLE = Color.parseColor("#B9A6FF")
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0B1A26") }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val area = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tagTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }

    /** 어종 그림 마커 하나 */
    private fun drawIcon(c: Canvas, map: MapView, cl: Cluster) {
        val n = cl.items.size
        val top = cl.items.groupingBy { it.species.ifBlank { it.kind } }.eachCount().entries.sortedByDescending { it.value }
        val src = cl.items.groupingBy { it.src }.eachCount().maxByOrNull { it.value }!!.key
        // 게시판 대략 위치: 옅은 보라 원 (범위)
        cl.items.firstOrNull { it.src == 2 && it.radiusM > 0 }?.let { p ->
            val rr = map.projection.metersToPixels(p.radiusM.toFloat())
            if (rr > 14 * d) {
                area.color = Color.argb(45, 185, 166, 255); c.drawCircle(cl.x, cl.y, rr, area)
                edge.color = Color.argb(140, 185, 166, 255); edge.strokeWidth = 1 * d; c.drawCircle(cl.x, cl.y, rr, edge)
            }
        }
        val rad = (17 + min(n, 60) * 0.12f) * d
        val bg = cl.items.groupingBy { it.color }.eachCount().maxByOrNull { it.value }!!.key
        fill.color = bg; fill.alpha = 235
        c.drawCircle(cl.x, cl.y, rad, fill)
        edge.color = when (src) { 1 -> PINK; 2 -> PURPLE; else -> Color.WHITE }
        edge.strokeWidth = (if (src == 0) 2f else 3f) * d
        c.drawCircle(cl.x, cl.y, rad, edge)
        val ink = Color.parseColor(if (Color.blue(bg) > 180) "#04222A" else "#1B0E02")
        if (top.size >= 2 && top[1].value * 4 >= top[0].value) {
            art.drawArt(c, top[0].key, cl.x - rad * 0.33f, cl.y, rad * 1.15f, ink)
            art.drawArt(c, top[1].key, cl.x + rad * 0.38f, cl.y + rad * 0.05f, rad * 1.0f, ink)
        } else art.drawArt(c, top[0].key, cl.x, cl.y, rad * 1.5f, ink)
        // 마릿수 (오른쪽 위 검은 동그라미)
        val br = 9 * d + (if (n >= 100) 3 * d else if (n >= 10) 1.5f * d else 0f)
        val bx = cl.x + rad * 0.75f; val by = cl.y - rad * 0.75f
        c.drawCircle(bx, by, br, badge)
        num.textSize = 11 * d
        c.drawText(n.toString(), bx, by - (num.descent() + num.ascent()) / 2, num)
        num.textSize = 13 * d
        // 친구·게시판: 이름표
        if (src != 0) {
            val names = cl.items.filter { it.src == src }.map { it.who }.distinct()
            val label = if (names.size == 1) names[0] else "${names[0]} 외 ${names.size - 1}"
            tagTxt.textSize = 10 * d
            val w = tagTxt.measureText(label) + 10 * d
            area.color = if (src == 1) PINK else PURPLE
            c.drawRoundRect(cl.x - w / 2, cl.y + rad + 2 * d, cl.x + w / 2, cl.y + rad + 16 * d, 7 * d, 7 * d, area)
            tagTxt.color = Color.parseColor(if (src == 1) "#4B1528" else "#26215C")
            c.drawText(label, cl.x, cl.y + rad + 12.5f * d, tagTxt)
        }
    }

    private val d = ctx.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * d; color = Color.parseColor("#0B1A26")
    }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = 13 * d
        setShadowLayer(2 * d, 0f, 0f, Color.parseColor("#0B1A26"))
    }

    private val paidBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F2C14E") }
    private val paidTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0B1A26"); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = 9 * d
    }

    private class Cluster(val x: Float, val y: Float, val items: MutableList<MapDot>)
    private var clusters: List<Cluster> = emptyList()
    private val pt = Point()

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = map.projection
        val r = mergeDp * d
        val list = ArrayList<Cluster>()
        for (dot in dots) {
            proj.toPixels(dot.point, pt)
            val x = pt.x.toFloat(); val y = pt.y.toFloat()
            var hit: Cluster? = null
            for (cl in list) {
                val dx = cl.x - x; val dy = cl.y - y
                if (dx * dx + dy * dy < r * r) { hit = cl; break }
            }
            if (hit != null) hit.items.add(dot) else list.add(Cluster(x, y, mutableListOf(dot)))
        }
        clusters = list
        if (icons) { for (cl in list) drawIcon(c, map, cl); return }
        val alpha = if (faded) 105 else 255
        for (cl in list) {
            val n = cl.items.size
            val color = cl.items.groupingBy { it.color }.eachCount().maxByOrNull { it.value }!!.key
            val rad = if (n == 1) 7 * d else (12 + min(n, 40) * 0.2f) * d
            fill.color = color; fill.alpha = alpha
            ring.alpha = alpha
            c.drawCircle(cl.x, cl.y, rad, fill)
            c.drawCircle(cl.x, cl.y, rad, ring)
            if (n > 1) {
                num.alpha = if (faded) 190 else 255
                c.drawText(n.toString(), cl.x, cl.y - (num.descent() + num.ascent()) / 2, num)
            }
            // 해루질 유료 자리: 오른쪽 위에 작은 "유료"
            if (cl.items.any { it.paid }) {
                val w = paidTxt.measureText("유료") + 6 * d
                val left = cl.x + rad * 0.5f; val top = cl.y - rad - 9 * d
                paidBg.alpha = alpha; paidTxt.alpha = alpha
                c.drawRoundRect(left, top, left + w, top + 13 * d, 6 * d, 6 * d, paidBg)
                c.drawText("유료", left + w / 2, top + 10 * d, paidTxt)
            }
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
        val cb = onTap ?: return false
        val r = 24 * d
        var best: Cluster? = null
        var bestD = Float.MAX_VALUE
        for (cl in clusters) {
            val dx = cl.x - e.x; val dy = cl.y - e.y
            val dd = dx * dx + dy * dy
            if (dd < r * r && dd < bestD) { best = cl; bestD = dd }
        }
        val b = best ?: return false
        cb(b.items)
        return true
    }
}

/** 지금 내 위치: 작은 배. 뱃머리가 진행 방향 */
class BoatOverlay(ctx: Context) : Overlay() {
    var position: GeoPoint? = null
    var bearing = 0f
    var accuracy = 0f

    private val d = ctx.resources.displayMetrics.density
    private val hull = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * d; color = Color.parseColor("#0B1A26"); strokeJoin = Paint.Join.ROUND
    }
    private val cabin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F28C38") }
    private val wake = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * d; color = Color.argb(150, 255, 255, 255); strokeCap = Paint.Cap.ROUND
    }
    private val acc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(45, 91, 200, 223) }
    private val pt = Point()

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val p = position ?: return
        map.projection.toPixels(p, pt)
        val x = pt.x.toFloat(); val y = pt.y.toFloat()
        if (accuracy > 0) {
            val ar = map.projection.metersToPixels(accuracy)
            if (ar > 16 * d) c.drawCircle(x, y, ar, acc)
        }
        val s = 15 * d
        c.save()
        c.rotate(bearing, x, y)
        c.drawLine(x - s * 0.25f, y + s * 1.0f, x - s * 0.45f, y + s * 1.5f, wake)
        c.drawLine(x + s * 0.25f, y + s * 1.0f, x + s * 0.45f, y + s * 1.5f, wake)
        val path = Path().apply {
            moveTo(x, y - s)
            quadTo(x + s * 0.6f, y - s * 0.2f, x + s * 0.45f, y + s * 0.85f)
            lineTo(x - s * 0.45f, y + s * 0.85f)
            quadTo(x - s * 0.6f, y - s * 0.2f, x, y - s)
            close()
        }
        c.drawPath(path, hull)
        c.drawPath(path, edge)
        c.drawRoundRect(x - s * 0.25f, y - s * 0.05f, x + s * 0.25f, y + s * 0.5f, 3 * d, 3 * d, cabin)
        c.restore()
    }
}

/**
 * 같이 출조 중인 그룹원: 색 동그라미 + 이름표 ("홍길동 · 주꾸미 23 · 3분 전")
 * 20분 넘게 갱신이 없으면 흐리게. 누르면 onTap
 */
class MatesOverlay(ctx: android.content.Context, private val onTap: (Live.Mate) -> Unit) : Overlay() {
    var mates: List<Live.Mate> = emptyList()
    private val d = ctx.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = Color.WHITE }
    private val tagBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 11, 26, 38) }
    private val tagTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11.5f * d; color = Color.WHITE; typeface = android.graphics.Typeface.DEFAULT_BOLD }
    private val pts = HashMap<String, android.graphics.Point>()
    private val COLORS = intArrayOf(Color.parseColor("#F07AA0"), Color.parseColor("#B9A6FF"), Color.parseColor("#5FD39A"),
        Color.parseColor("#F2C14E"), Color.parseColor("#5BC8DF"))

    private fun ago(t: Long): String {
        val m = ((System.currentTimeMillis() - t) / 60_000L).coerceAtLeast(0)
        return if (m < 1) "방금" else if (m < 60) "${m}분 전" else "${m / 60}시간 전"
    }

    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        pts.clear()
        mates.forEachIndexed { i, m ->
            val la = m.lat ?: return@forEachIndexed; val lo = m.lon ?: return@forEachIndexed
            val p = mapView.projection.toPixels(GeoPoint(la, lo), null)
            pts[m.uid] = p
            val old = m.ended || System.currentTimeMillis() - m.time > 20 * 60_000L
            val a = if (old) 110 else 255
            fill.color = COLORS[i % COLORS.size]; fill.alpha = a; ring.alpha = a
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), 9 * d, fill)
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), 9 * d, ring)
            val best = if (m.c1 >= m.c2) "${m.names.getOrElse(0) { "1번" }} ${m.c1}" else "${m.names.getOrElse(1) { "2번" }} ${m.c2}"
            val label = if (m.ended) "${m.name} · 종료 · $best" else "${m.name} · $best · ${ago(m.time)}"
            val w = tagTxt.measureText(label) + 12 * d
            val x = p.x + 13 * d; val y = p.y - 11 * d
            tagBg.alpha = if (old) 150 else 225; tagTxt.alpha = a
            c.drawRoundRect(x, y, x + w, y + 22 * d, 11 * d, 11 * d, tagBg)
            c.drawText(label, x + 6 * d, y + 15 * d, tagTxt)
        }
    }

    override fun onSingleTapConfirmed(e: android.view.MotionEvent, mapView: MapView): Boolean {
        val hit = mates.firstOrNull { m ->
            val p = pts[m.uid] ?: return@firstOrNull false
            Math.hypot((e.x - p.x).toDouble(), (e.y - p.y).toDouble()) < 26 * d
        } ?: return false
        onTap(hit); return true
    }
}
