package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * v6.53 (329): 🌬 우리 앱 바람 지도 (윈디 창은 그대로 따로)
 *  - 앱 지도 위에 바람 세기 색 + 바람 따라 흘러가는 선(움직임) + 칸마다 화살표 · m/s
 *  - 예보 고르기: 기상청(kma_seamless) · 유럽(ecmwf_ifs025 · 윈디와 같은 모델) · 미국(gfs_seamless)
 *  - 아래 시간 막대(3일 · 1시간) · ▶ 재생 · 지도 누르면 그 자리 세 예보 비교 카드
 *  - 자료: Open-Meteo 무료 (보이는 곳 둘레를 격자 한 번에 · 폰 메모리 30분 기억 · 서버 안 씀)
 *  - 시안 design/wind_map_mock.png · ⚠ 물때 · 물세기 계산과 상관없음
 */
object WindMap {

    private val MODELS = listOf("kma_seamless" to "기상청", "ecmwf_ifs025" to "유럽 (윈디)", "gfs_seamless" to "미국")
    private const val HOURS = 72
    private const val COLS = 8
    private const val ROWS = 9

    /** 격자 한 장: rows × cols 점 · 시간마다 속도/방향 */
    class Grid(val s: Double, val n: Double, val w: Double, val e: Double, val rows: Int, val cols: Int,
               val times: List<Long>, val spd: Array<Array<FloatArray>>, val dir: Array<Array<FloatArray>>,
               val gust: Array<Array<FloatArray>>, val at: Long) {
        fun lat(r: Int) = s + (n - s) * r / (rows - 1)
        fun lon(c: Int) = w + (e - w) * c / (cols - 1)
        /** 그 시각 · 그 자리 (u, v) m/s — 동쪽 +u · 북쪽 +v (바람이 불어 가는 쪽) */
        fun uv(t: Int, la: Double, lo: Double, out: FloatArray): Boolean {
            if (la < s || la > n || lo < w || lo > e) return false
            val fr = (la - s) / (n - s) * (rows - 1); val fc = (lo - w) / (e - w) * (cols - 1)
            val r0 = floor(fr).toInt().coerceIn(0, rows - 2); val c0 = floor(fc).toInt().coerceIn(0, cols - 2)
            val dr = (fr - r0).toFloat(); val dc = (fc - c0).toFloat()
            var u = 0f; var v = 0f; var wsum = 0f
            for (k in 0 until 4) {
                val rr = r0 + (k shr 1); val cc = c0 + (k and 1)
                val ww = (if (k shr 1 == 0) 1 - dr else dr) * (if (k and 1 == 0) 1 - dc else dc)
                val sp = spd[t][rr][cc]; if (sp.isNaN() || ww <= 0f) continue
                val d = Math.toRadians(dir[t][rr][cc].toDouble())
                u += (-sp * sin(d)).toFloat() * ww; v += (-sp * cos(d)).toFloat() * ww; wsum += ww
            }
            if (wsum < 0.01f) return false
            out[0] = u / wsum; out[1] = v / wsum
            return true
        }
        fun covers(la1: Double, la2: Double, lo1: Double, lo2: Double) = la1 >= s && la2 <= n && lo1 >= w && lo2 <= e
    }

    private val cache = ConcurrentHashMap<String, Grid>()
    private val main = Handler(Looper.getMainLooper())

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000; c.readTimeout = 12_000
        return try { c.inputStream.bufferedReader().use { it.readText() } }
        catch (e: Exception) {
            val err = try { c.errorStream?.bufferedReader()?.use { it.readText() } } catch (_: Exception) { null }
            val reason = err?.let { try { JSONObject(it).optString("reason") } catch (_: Exception) { null } }
            throw Exception(reason?.takeIf { it.isNotBlank() } ?: e.message ?: "받기 실패")
        } finally { c.disconnect() }
    }

    private val tFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.KOREA)
    private fun fmt(v: Double) = String.format(Locale.US, "%.3f", v)

    /** 보이는 곳 둘레(1.4배)를 격자로 한 번에 받기 */
    private fun loadGrid(model: String, s0: Double, n0: Double, w0: Double, e0: Double, done: (Grid?, String?) -> Unit) {
        val padLa = (n0 - s0) * 0.2; val padLo = (e0 - w0) * 0.2
        // 0.05° 단위로 맞춤 (조금 옮겨도 같은 기억 쓰게)
        fun q(v: Double, up: Boolean) = (if (up) Math.ceil(v * 20) else floor(v * 20)) / 20.0
        val s = q(s0 - padLa, false); val n = q(n0 + padLa, true); val w = q(w0 - padLo, false); val e = q(e0 + padLo, true)
        val key = "$model|$s|$n|$w|$e"
        cache[key]?.takeIf { System.currentTimeMillis() - it.at < 30 * 60_000L }?.let { return done(it, null) }
        Thread {
            try {
                val lats = mutableListOf<Double>(); val lons = mutableListOf<Double>()
                for (r in 0 until ROWS) for (c in 0 until COLS) { lats.add(s + (n - s) * r / (ROWS - 1)); lons.add(w + (e - w) * c / (COLS - 1)) }
                val url = "https://api.open-meteo.com/v1/forecast?latitude=" + lats.joinToString(",") { fmt(it) } +
                    "&longitude=" + lons.joinToString(",") { fmt(it) } +
                    "&hourly=wind_speed_10m,wind_direction_10m,wind_gusts_10m&wind_speed_unit=ms&timezone=Asia%2FSeoul&forecast_days=3&models=$model"
                val body = get(url)
                val arr = if (body.trimStart().startsWith("[")) JSONArray(body) else JSONArray().put(JSONObject(body))
                var times: List<Long> = emptyList()
                val spd = Array(HOURS) { Array(ROWS) { FloatArray(COLS) { Float.NaN } } }
                val dir = Array(HOURS) { Array(ROWS) { FloatArray(COLS) { 0f } } }
                val gst = Array(HOURS) { Array(ROWS) { FloatArray(COLS) { Float.NaN } } }
                for (i in 0 until min(arr.length(), ROWS * COLS)) {
                    val h = arr.getJSONObject(i).optJSONObject("hourly") ?: continue
                    if (times.isEmpty()) h.optJSONArray("time")?.let { ta -> times = (0 until min(HOURS, ta.length())).map { tFmt.parse(ta.getString(it))?.time ?: 0L } }
                    val r = i / COLS; val c = i % COLS
                    val sa = h.optJSONArray("wind_speed_10m"); val da = h.optJSONArray("wind_direction_10m"); val ga = h.optJSONArray("wind_gusts_10m")
                    for (t in 0 until HOURS) {
                        if (sa != null && t < sa.length() && !sa.isNull(t)) spd[t][r][c] = sa.getDouble(t).toFloat()
                        if (da != null && t < da.length() && !da.isNull(t)) dir[t][r][c] = da.getDouble(t).toFloat()
                        if (ga != null && t < ga.length() && !ga.isNull(t)) gst[t][r][c] = ga.getDouble(t).toFloat()
                    }
                }
                if (times.isEmpty()) throw Exception("자료가 비었어요")
                val g = Grid(s, n, w, e, ROWS, COLS, times, spd, dir, gst, System.currentTimeMillis())
                if (cache.size > 12) cache.clear()
                cache[key] = g
                main.post { done(g, null) }
            } catch (ex: Exception) { main.post { done(null, ex.message ?: "받기 실패") } }
        }.start()
    }

    class Point3(val spd: Float, val dir: Float, val gust: Float)
    /** 한 자리 세 예보 비교 (시간별) — 모델 이름 → 시간 → 값 */
    private fun loadPoint(la: Double, lo: Double, done: (Map<String, List<Point3>>?, List<Long>, String?) -> Unit) {
        Thread {
            try {
                val body = get("https://api.open-meteo.com/v1/forecast?latitude=${fmt(la)}&longitude=${fmt(lo)}" +
                    "&hourly=wind_speed_10m,wind_direction_10m,wind_gusts_10m&wind_speed_unit=ms&timezone=Asia%2FSeoul&forecast_days=3" +
                    "&models=" + MODELS.joinToString(",") { it.first })
                val h = JSONObject(body).getJSONObject("hourly")
                val ta = h.getJSONArray("time")
                val times = (0 until min(HOURS, ta.length())).map { tFmt.parse(ta.getString(it))?.time ?: 0L }
                val out = HashMap<String, List<Point3>>()
                MODELS.forEach { (m, _) ->
                    val sa = h.optJSONArray("wind_speed_10m_$m") ?: return@forEach
                    val da = h.optJSONArray("wind_direction_10m_$m"); val ga = h.optJSONArray("wind_gusts_10m_$m")
                    out[m] = times.indices.map { t ->
                        Point3(if (t < sa.length() && !sa.isNull(t)) sa.getDouble(t).toFloat() else Float.NaN,
                            if (da != null && t < da.length() && !da.isNull(t)) da.getDouble(t).toFloat() else 0f,
                            if (ga != null && t < ga.length() && !ga.isNull(t)) ga.getDouble(t).toFloat() else Float.NaN)
                    }
                }
                main.post { done(out, times, null) }
            } catch (ex: Exception) { main.post { done(null, emptyList(), ex.message ?: "받기 실패") } }
        }.start()
    }

    // ---------------- 색 ----------------
    private val STOPS = listOf(0f to intArrayOf(59, 111, 216), 4f to intArrayOf(47, 179, 201), 7f to intArrayOf(67, 196, 107),
        10f to intArrayOf(230, 210, 74), 12f to intArrayOf(240, 138, 50), 14f to intArrayOf(216, 54, 74), 17f to intArrayOf(155, 47, 168))
    fun colorOf(s: Float, alpha: Int = 255): Int {
        for (i in 1 until STOPS.size) {
            val (a0, c0) = STOPS[i - 1]; val (a1, c1) = STOPS[i]
            if (s <= a1) {
                val t = ((s - a0) / (a1 - a0)).coerceIn(0f, 1f)
                return Color.argb(alpha, (c0[0] + (c1[0] - c0[0]) * t).toInt(), (c0[1] + (c1[1] - c0[1]) * t).toInt(), (c0[2] + (c1[2] - c0[2]) * t).toInt())
            }
        }
        val c = STOPS.last().second; return Color.argb(alpha, c[0], c[1], c[2])
    }
    fun dirName(d: Float): String {
        val names = arrayOf("북", "북북동", "북동", "동북동", "동", "동남동", "남동", "남남동", "남", "남남서", "남서", "서남서", "서", "서북서", "북서", "북북서")
        return names[(((d % 360) + 360) % 360 / 22.5f).roundToInt() % 16]
    }

    // ---------------- 육지 모양 (v6.55 · 331: 육지 어둡게 · 육지 위엔 숫자 안 놓기) ----------------
    /** assets/korea_map.json (권역 지도와 같은 메르카토르 그림 좌표) — 지도도 메르카토르라 확대 · 이동만 맞추면 겹침 */
    private class Land(val k: Double, val tx: Double, val ty: Double, val path: Path)
    @Volatile private var land: Land? = null
    private fun land(c: android.content.Context): Land? {
        land?.let { return it }
        return try {
            val o = JSONObject(c.assets.open("korea_map.json").bufferedReader().use { it.readText() })
            val p = Path()
            for (key in listOf("K", "O")) {
                val a = o.getJSONArray(key)
                for (i in 0 until a.length()) {
                    val r = a.getJSONArray(i); var j = 0
                    while (j + 1 < r.length()) { val x = r.getInt(j).toFloat(); val y = r.getInt(j + 1).toFloat(); if (j == 0) p.moveTo(x, y) else p.lineTo(x, y); j += 2 }
                    p.close()
                }
            }
            Land(o.getDouble("k"), o.getDouble("tx"), o.getDouble("ty"), p).also { land = it }
        } catch (_: Exception) { null }
    }
    private fun landXY(l: Land, lat: Double, lon: Double): Pair<Float, Float> =
        (l.tx + l.k * Math.toRadians(lon)).toFloat() to (l.ty - l.k * Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2))).toFloat()

    // ---------------- 지도 위 그림 ----------------
    private class WindOverlay(val a: Activity) : Overlay() {
        var grid: Grid? = null
        var t = 0
        var tap: GeoPoint? = null
        var onTap: ((GeoPoint) -> Unit)? = null
        /** 누른 자리 말풍선 (이름, 고른 예보 값) */
        var bubble: Pair<String, String>? = null
        private val landGeo = land(a)
        private val landScr = Path()
        private val landRgn = android.graphics.Region()
        private var landKey = ""
        private val landPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 12, 16, 22) }
        private val coastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(140, 255, 255, 255) }
        private val bubP = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bubT = Paint(Paint.ANTI_ALIAS_FLAG)
        var running = true
        private val d = a.resources.displayMetrics.density
        private var field: Bitmap? = null
        private var fieldKey = ""
        private val fp = Paint(Paint.FILTER_BITMAP_FLAG)
        private val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; color = Color.WHITE }
        private val cp = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        private val N = 520
        private val TRAIL = 14
        private val px = Array(N) { FloatArray(TRAIL) }
        private val py = Array(N) { FloatArray(TRAIL) }
        private val age = IntArray(N)
        private val life = IntArray(N)
        private var lastView = ""
        private val rnd = java.util.Random(7)
        private val uv = FloatArray(2)
        private val pt = Point()

        fun resetParticles(w: Int, h: Int) {
            for (i in 0 until N) seed(i, w, h, true)
        }
        private fun seed(i: Int, w: Int, h: Int, randomAge: Boolean) {
            val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h
            for (k in 0 until TRAIL) { px[i][k] = x; py[i][k] = y }
            life[i] = 40 + rnd.nextInt(50); age[i] = if (randomAge) rnd.nextInt(life[i]) else 0
        }

        override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
            if (shadow) return
            val g = grid ?: return
            val w = c.width; val h = c.height
            if (w <= 0 || h <= 0) return
            val proj = map.projection
            val bb = map.boundingBox
            val viewKey = "${bb.latNorth}|${bb.lonWest}|${map.zoomLevelDouble}|$w|$h"
            // ① 바람 세기 색 (지도 · 시간이 바뀔 때만 새로 그림)
            val fk = "$viewKey|$t|${g.at}"
            if (fk != fieldKey) {
                val cell = (10 * d).toInt().coerceAtLeast(6)
                val fw = w / cell + 1; val fh = h / cell + 1
                val bmp = field?.takeIf { it.width == fw && it.height == fh } ?: Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
                for (yy in 0 until fh) for (xx in 0 until fw) {
                    val gp = proj.fromPixels(xx * cell, yy * cell)
                    bmp.setPixel(xx, yy, if (g.uv(t, gp.latitude, gp.longitude, uv)) colorOf(sqrt(uv[0] * uv[0] + uv[1] * uv[1]), 235) else Color.TRANSPARENT)
                }
                field = bmp; fieldKey = fk
            }
            field?.let { c.drawBitmap(it, null, Rect(0, 0, (it.width * (10 * d).toInt().coerceAtLeast(6)), (it.height * (10 * d).toInt().coerceAtLeast(6))), fp) }
            // ①-2 육지 어둡게 (윈디처럼 · 너무 확대하면 그림이 거칠어서 지도 그대로)
            val useLand = landGeo != null && map.zoomLevelDouble <= 11.5
            if (useLand && viewKey != landKey) {
                val l = landGeo!!
                val a1 = landXY(l, 33.0, 124.0); val a2 = landXY(l, 39.0, 131.0)
                val p1 = Point(); val p2 = Point()
                proj.toPixels(GeoPoint(33.0, 124.0), p1); proj.toPixels(GeoPoint(39.0, 131.0), p2)
                val sxm = (p2.x - p1.x) / (a2.first - a1.first); val sym = (p2.y - p1.y) / (a2.second - a1.second)
                val mtx = android.graphics.Matrix().apply { setScale(sxm, sym); postTranslate(p1.x - a1.first * sxm, p1.y - a1.second * sym) }
                landScr.reset(); l.path.transform(mtx, landScr)
                landRgn.setPath(landScr, android.graphics.Region(-50, -50, w + 50, h + 50))
                landKey = viewKey
            }
            if (useLand) {
                c.drawPath(landScr, landPaint)
                coastPaint.strokeWidth = 1f * d; c.drawPath(landScr, coastPaint)
            }
            // ② 흘러가는 선
            if (viewKey != lastView) { lastView = viewKey; resetParticles(w, h) }
            val step = 0.22f * d
            for (i in 0 until N) {
                val gp = proj.fromPixels(px[i][0].toInt(), py[i][0].toInt())
                val ok = g.uv(t, gp.latitude, gp.longitude, uv)
                age[i]++
                if (!ok || age[i] > life[i] || px[i][0] < 0 || px[i][0] > w || py[i][0] < 0 || py[i][0] > h) { seed(i, w, h, false); continue }
                for (k in TRAIL - 1 downTo 1) { px[i][k] = px[i][k - 1]; py[i][k] = py[i][k - 1] }
                px[i][0] += uv[0] * step; py[i][0] -= uv[1] * step
                val fade = when { age[i] < 8 -> age[i] / 8f; life[i] - age[i] < 8 -> (life[i] - age[i]) / 8f; else -> 1f }
                for (k in 0 until TRAIL - 1) {
                    val f = (1f - k / TRAIL.toFloat()) * fade
                    lp.alpha = (220 * f).toInt().coerceIn(0, 255); lp.strokeWidth = (0.5f + 0.9f * f) * d
                    c.drawLine(px[i][k + 1], py[i][k + 1], px[i][k], py[i][k], lp)
                }
            }
            // ③ 격자 화살표 · m/s (너무 빽빽하면 건너뜀)
            val minGap = 58 * d
            var lastY = -9999f
            tp.textSize = 11.5f * d
            for (r in g.rows - 1 downTo 0) {
                var lastX = -9999f
                proj.toPixels(GeoPoint(g.lat(r), g.lon(0)), pt)
                if (abs(pt.y - lastY) < minGap) continue
                lastY = pt.y.toFloat()
                for (col in 0 until g.cols) {
                    proj.toPixels(GeoPoint(g.lat(r), g.lon(col)), pt)
                    if (pt.x < -20 || pt.x > w + 20 || pt.y < -20 || pt.y > h + 20) continue
                    if (pt.x - lastX < minGap) continue
                    lastX = pt.x.toFloat()
                    val sp = g.spd[t][r][col]; if (sp.isNaN()) continue
                    if (useLand && landRgn.contains(pt.x, pt.y)) continue   // 육지 위엔 숫자 안 놓기
                    val dir = g.dir[t][r][col]
                    val x = pt.x.toFloat(); val y = pt.y.toFloat()
                    // v6.55 (331): 동그라미 안 큰 숫자 + 테두리 = 세기 색 + 밖에 작은 세모 = 바람이 가는 쪽
                    cp.style = Paint.Style.FILL; cp.color = Color.argb(215, 10, 18, 26)
                    c.drawCircle(x, y, 15 * d, cp)
                    cp.style = Paint.Style.STROKE; cp.strokeWidth = 2f * d; cp.color = colorOf(sp)
                    c.drawCircle(x, y, 15 * d, cp)
                    tp.textSize = 14.5f * d; tp.setShadowLayer(0f, 0f, 0f, 0)
                    c.drawText(sp.roundToInt().toString(), x, y + 5.2f * d, tp)
                    val rr = Math.toRadians((dir + 180f).toDouble())
                    val ax = x + (sin(rr) * 21 * d).toFloat(); val ay = y - (cos(rr) * 21 * d).toFloat()
                    c.save(); c.rotate(dir + 180f, ax, ay)
                    cp.style = Paint.Style.FILL; cp.color = Color.WHITE
                    c.drawPath(Path().apply { moveTo(ax, ay - 6 * d); lineTo(ax + 5 * d, ay + 3.5f * d); lineTo(ax - 5 * d, ay + 3.5f * d); close() }, cp)
                    c.restore()
                }
            }
            // ④ 누른 자리
            tap?.let { gp ->
                proj.toPixels(gp, pt)
                cp.style = Paint.Style.STROKE; cp.color = Color.WHITE; cp.strokeWidth = 3 * d
                c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 11 * d, cp)
                cp.style = Paint.Style.FILL; cp.color = Color.parseColor("#FFD166")
                c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 4.5f * d, cp)
                // 말풍선 (흰 바탕 · 이름 + 고른 예보 값)
                bubble?.let { (t1, t2) ->
                    bubT.isFakeBoldText = true; bubT.textSize = 13.5f * d
                    val w1 = bubT.measureText(t1)
                    bubT.isFakeBoldText = false; bubT.textSize = 12.5f * d
                    val w2 = bubT.measureText(t2)
                    val bw = max(w1, w2) + 20 * d; val bh = 46 * d
                    var bx = pt.x - bw / 2; var by = pt.y - 16 * d - bh
                    if (by < 4 * d) by = pt.y + 18 * d
                    bx = bx.coerceIn(4 * d, w - bw - 4 * d)
                    bubP.color = Color.argb(240, 255, 255, 255)
                    c.drawRoundRect(bx, by, bx + bw, by + bh, 10 * d, 10 * d, bubP)
                    bubT.color = Color.parseColor("#14202B"); bubT.isFakeBoldText = true; bubT.textSize = 13.5f * d
                    c.drawText(t1, bx + 10 * d, by + 19 * d, bubT)
                    bubT.isFakeBoldText = false; bubT.textSize = 12.5f * d; bubT.color = Color.parseColor("#33414D")
                    c.drawText(t2, bx + 10 * d, by + 37 * d, bubT)
                }
            }
            if (running) map.postInvalidateDelayed(33)
        }

        override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
            val gp = map.projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
            tap = gp; onTap?.invoke(gp); map.invalidate()
            return true
        }
    }

    // ---------------- 화면 ----------------
    fun show(a: Activity, lat: Double, lon: Double, place: String) {
        fun dp(v: Int) = Ui.dp(a, v)
        val dlg = Dialog(a, R.style.FlFull)
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        val prefs = a.getSharedPreferences("fishlog", android.content.Context.MODE_PRIVATE)
        var model = prefs.getString("wm_model", MODELS[0].first) ?: MODELS[0].first
        if (MODELS.none { it.first == model }) model = MODELS[0].first

        // 제목 줄
        val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(10), dp(6)) }
        bar.addView(Ui.text(a, 14f, Ui.TEXT, true, "← 닫기").apply {
            gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0)
            background = Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE); setOnClickListener { dlg.dismiss() }
        }, LinearLayout.LayoutParams(WRAP, dp(36)))
        val title = Ui.text(a, 17f, Ui.TEXT, true, "🌬 바람 지도").apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        bar.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(10) })
        val shotBtn = android.widget.ImageView(a).apply {
            setImageResource(R.drawable.ic_shot); scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8)); background = Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE)
            contentDescription = "사진 찍어 보내기"
        }
        bar.addView(shotBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        // 예보 고르기
        val mRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; setPadding(dp(12), 0, dp(12), dp(8)) }
        root.addView(mRow, LinearLayout.LayoutParams(MATCH, WRAP))

        // 지도
        val frame = FrameLayout(a)
        val map = MapDialog.newMap(a).apply {
            controller.setZoom(9.0); controller.setCenter(GeoPoint(lat, lon))
            minZoomLevel = 5.0; maxZoomLevel = 13.0
        }
        frame.addView(map, FrameLayout.LayoutParams(MATCH, MATCH))
        val ov = WindOverlay(a)
        map.overlays.add(ov)
        MapDialog.zoomButtons(a, frame, map)
        // 범례
        val legend = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(5), dp(8), dp(5))
            background = Ui.bg(a, Color.argb(200, 10, 20, 30), 10)
        }
        legend.addView(Ui.text(a, 10.5f, Color.WHITE, true, "바람 m/s"))
        legend.addView(View(a).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(colorOf(0f), colorOf(4f), colorOf(7f), colorOf(10f), colorOf(12f), colorOf(14f), colorOf(17f))).apply { cornerRadius = dp(4).toFloat() }
        }, LinearLayout.LayoutParams(dp(140), dp(7)).apply { topMargin = dp(3) })
        val lr = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        listOf("0", "4", "8", "12", "16+").forEachIndexed { i, s ->
            lr.addView(Ui.text(a, 9.5f, Color.parseColor("#B8C7D4"), false, s).apply { gravity = if (i == 4) Gravity.END else Gravity.START },
                LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        legend.addView(lr, LinearLayout.LayoutParams(dp(140), WRAP))
        frame.addView(legend, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { leftMargin = dp(10); topMargin = dp(10) })
        val status = Ui.text(a, 12f, Color.WHITE, true, "바람 받는 중…").apply {
            setPadding(dp(10), dp(5), dp(10), dp(5)); background = Ui.bg(a, Color.argb(210, 10, 20, 30), 99)
        }
        frame.addView(status, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { rightMargin = dp(10); topMargin = dp(10) })
        root.addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // 시간 막대
        val timeBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(4)) }
        val tRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL }
        val tCol = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val nowText = Ui.text(a, 15f, Ui.TEXT, true, "")
        tCol.addView(nowText)
        tCol.addView(Ui.text(a, 11f, Ui.SUB, false, "한 칸 = 1시간 · 밀어서 3일 · 대충 보는 예보예요"))
        tRow.addView(tCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        val play = Ui.text(a, 15f, Ui.BG, true, "▶").apply { gravity = Gravity.CENTER; background = Ui.bg(a, Color.parseColor("#7FD6FF"), 99) }
        tRow.addView(play, LinearLayout.LayoutParams(dp(40), dp(40)))
        timeBox.addView(tRow)
        val dayRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        timeBox.addView(dayRow, Ui.lp(a, MATCH, WRAP, top = 6))
        val seek = SeekBar(a).apply { max = HOURS - 1; setPadding(dp(4), dp(6), dp(4), dp(6)) }
        timeBox.addView(seek, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(timeBox, LinearLayout.LayoutParams(MATCH, WRAP))

        // 누른 자리 비교 카드
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(9), dp(12), dp(10))
            background = Ui.bg(a, Ui.CARD, 14, Ui.LINE)
        }
        root.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { leftMargin = dp(12); rightMargin = dp(12); bottomMargin = dp(10) })

        val dayF = SimpleDateFormat("M월 d일(E) HH:mm", Locale.KOREA)
        val dShort = SimpleDateFormat("E d", Locale.KOREA)
        var times: List<Long> = emptyList()
        var cmp: Map<String, List<Point3>>? = null
        var cmpTimes: List<Long> = emptyList()
        var cmpName = ""
        var cmpErr: String? = null
        var cmpLoading = false

        fun paintCard() {
            card.removeAllViews()
            val tap = ov.tap
            ov.bubble = if (tap != null) ("📍 " + cmpName) to (if (cmpLoading) "받는 중…" else "-") else null
            if (tap == null) {
                card.addView(Ui.text(a, 13f, Ui.SUB, false, "지도를 누르면 그 자리 바람을 기상청 · 유럽(윈디) · 미국 예보로 비교해요"))
                return
            }
            val tt = times.getOrNull(ov.t)
            card.addView(Ui.text(a, 14f, Ui.TEXT, true, "📍 $cmpName" + (tt?.let { " · " + SimpleDateFormat("HH:mm", Locale.KOREA).format(it) } ?: "")))
            when {
                cmpLoading -> card.addView(Ui.text(a, 12.5f, Ui.SUB, false, "세 예보 받는 중…"), Ui.lp(a, MATCH, WRAP, top = 4))
                cmp == null -> card.addView(Ui.text(a, 12.5f, Ui.ORANGE, false, cmpErr ?: "못 받았어요"), Ui.lp(a, MATCH, WRAP, top = 4))
                else -> {
                    val idx = tt?.let { t0 -> cmpTimes.indexOfFirst { it == t0 }.takeIf { it >= 0 } } ?: ov.t
                    cmp?.get(model)?.getOrNull(idx)?.let { p ->
                        ov.bubble = ("📍 " + cmpName) to (if (p.spd.isNaN()) "-" else "${dirName(p.dir)} ${String.format(Locale.US, "%.1f", p.spd)} m/s" + (if (!p.gust.isNaN()) " · 돌풍 ${p.gust.roundToInt()}" else ""))
                    }
                    MODELS.forEach { (m, label) ->
                        val p = cmp?.get(m)?.getOrNull(idx)
                        val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
                        val on = m == model
                        r.addView(Ui.text(a, 13f, if (on) Color.parseColor("#7FD6FF") else Ui.SUB, on, label), LinearLayout.LayoutParams(0, WRAP, 1f))
                        r.addView(Ui.text(a, 13f, Ui.TEXT, on, if (p == null || p.spd.isNaN()) "-" else
                            "${dirName(p.dir)} ${String.format(Locale.US, "%.1f", p.spd)} m/s" + (if (!p.gust.isNaN()) " · 돌풍 ${p.gust.roundToInt()}" else "")))
                        card.addView(r, Ui.lp(a, MATCH, WRAP, top = 4))
                    }
                    card.addView(Ui.text(a, 11.5f, Ui.SUB, false, "누르면 시간별 비교 ›"), Ui.lp(a, MATCH, WRAP, top = 4))
                }
            }
        }
        card.setOnClickListener {
            val c = cmp ?: return@setOnClickListener
            val hf = SimpleDateFormat("d일 HH시", Locale.KOREA)
            val start = (ov.t).coerceAtMost(cmpTimes.size - 1).coerceAtLeast(0)
            val lines = (start until min(cmpTimes.size, start + 24)).joinToString("\n") { i ->
                hf.format(cmpTimes[i]) + "  " + MODELS.joinToString("  ") { (m, label) ->
                    val p = c[m]?.getOrNull(i)
                    label.substringBefore(" ") + " " + (if (p == null || p.spd.isNaN()) "-" else String.format(Locale.US, "%.0f", p.spd) + (if (!p.gust.isNaN()) "(${p.gust.roundToInt()})" else ""))
                }
            }
            AlertDialog.Builder(a, R.style.FlAlert).setTitle("📍 $cmpName · 시간별 바람 m/s (괄호 = 돌풍)")
                .setMessage(lines).setPositiveButton("닫기", null).show()
        }

        fun paintTime() {
            val tt = times.getOrNull(ov.t)
            nowText.text = tt?.let { dayF.format(it) } ?: "-"
            paintCard()
        }
        fun paintDays() {
            dayRow.removeAllViews()
            for (k in 0 until 3) {
                val tt = times.getOrNull(k * 24) ?: continue
                dayRow.addView(Ui.text(a, 11f, Ui.SUB, false, dShort.format(tt)).apply { setPadding(dp(4), 0, 0, 0) },
                    LinearLayout.LayoutParams(0, WRAP, 1f))
            }
        }

        var loadSeq = 0
        fun reload(force: Boolean = false) {
            val bb = map.boundingBox
            val g = ov.grid
            if (!force && g != null && g.covers(bb.latSouth, bb.latNorth, bb.lonWest, bb.lonEast)) return
            val my = ++loadSeq
            status.visibility = View.VISIBLE; status.text = "바람 받는 중…"
            loadGrid(model, bb.latSouth, bb.latNorth, bb.lonWest, bb.lonEast) { ng, err ->
                if (my != loadSeq || !dlg.isShowing) return@loadGrid
                if (ng == null) { status.text = "⚠ 못 받았어요 · ${err ?: ""}".take(60); return@loadGrid }
                status.visibility = View.GONE
                val first = ov.grid == null
                ov.grid = ng; times = ng.times
                if (first) {
                    val now = System.currentTimeMillis()
                    ov.t = times.indexOfLast { it <= now }.coerceAtLeast(0)
                    seek.progress = ov.t
                    paintDays()
                }
                paintTime(); map.invalidate()
            }
        }

        fun paintModels() {
            mRow.removeAllViews()
            MODELS.forEachIndexed { i, (m, label) ->
                val on = m == model
                mRow.addView(Ui.text(a, 13f, if (on) Color.parseColor("#7FD6FF") else Ui.SUB, true, label).apply {
                    gravity = Gravity.CENTER
                    background = if (on) Ui.bg(a, Color.parseColor("#123A52"), 10, Color.parseColor("#7FD6FF")) else Ui.bg(a, Color.TRANSPARENT, 10, Ui.LINE)
                    setOnClickListener {
                        if (model == m) return@setOnClickListener
                        model = m; prefs.edit().putString("wm_model", m).apply()
                        paintModels(); paintCard(); reload(true)
                    }
                }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { if (i > 0) leftMargin = dp(6) })
            }
        }
        paintModels()

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                ov.t = p.coerceIn(0, max(0, (ov.grid?.times?.size ?: HOURS) - 1)); paintTime(); map.invalidate()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        var playing = false
        val tick = object : Runnable {
            override fun run() {
                if (!playing || !dlg.isShowing) return
                seek.progress = if (seek.progress >= seek.max) 0 else seek.progress + 1
                main.postDelayed(this, 700)
            }
        }
        play.setOnClickListener {
            playing = !playing; play.text = if (playing) "❚❚" else "▶"
            if (playing) main.postDelayed(tick, 300)
        }

        ov.onTap = { gp ->
            cmpName = Tide.stationFor(a, gp.latitude, gp.longitude)?.name?.let { "$it 근처" }
                ?: String.format(Locale.US, "%.3f, %.3f", gp.latitude, gp.longitude)
            cmp = null; cmpErr = null; cmpLoading = true; paintCard()
            loadPoint(gp.latitude, gp.longitude) { res, ts, err ->
                if (!dlg.isShowing || ov.tap != gp) return@loadPoint
                cmpLoading = false; cmp = res; cmpTimes = ts; cmpErr = err; paintCard()
            }
        }
        // 지도를 옮기면 (잠깐 뒤) 바깥이 보이면 다시 받기
        val reloadLater = Runnable { if (dlg.isShowing) reload() }
        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean { main.removeCallbacks(reloadLater); main.postDelayed(reloadLater, 700); return false }
            override fun onZoom(event: ZoomEvent?): Boolean { main.removeCallbacks(reloadLater); main.postDelayed(reloadLater, 700); return false }
        })

        shotBtn.setOnClickListener {
            val tt = times.getOrNull(ov.t)
            val head = Ui.text(a, 16f, Ui.TEXT, true, "🌬 바람 지도 · " + (tt?.let { dayF.format(it) } ?: "") + " · " + (MODELS.firstOrNull { it.first == model }?.second ?: "")).apply {
                setBackgroundColor(Ui.BG); setPadding(dp(14), dp(10), dp(14), dp(10))
            }
            val wSpec = View.MeasureSpec.makeMeasureSpec(frame.width, View.MeasureSpec.EXACTLY)
            head.measure(wSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            head.layout(0, 0, frame.width, head.measuredHeight)
            Snap.capture(a, listOf(head), frame, "조과일지 · 바람 지도" + (if (place.isNotBlank()) " · $place" else "") + " · 대충 보는 예보",
                "바람-" + SimpleDateFormat("MMdd", Locale.KOREA).format(tt ?: System.currentTimeMillis()))
        }

        dlg.setContentView(root)
        Ui.edge(dlg.window)
        dlg.setOnDismissListener { ov.running = false; playing = false; main.removeCallbacks(reloadLater); try { map.onDetach() } catch (_: Exception) {} }
        dlg.show()
        map.onResume()
        paintTime()
        if (place.isNotBlank()) title.text = "🌬 바람 지도 · $place"
        map.post { reload(true) }
    }
}
