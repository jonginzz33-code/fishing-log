package com.fishlog.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * v6.37 (298): 🌋 지진 — 기상청_지진정보 조회서비스(공공데이터포털 15000420 · 앱의 공공데이터 키)
 *  - 최근 4일 국내(한반도 주변 바다 포함) 지진 · 폰에 3시간 기억
 *  - 지도에 핀(규모 숫자 · 클수록 크고 진한 빨강 · 지난 날 흐리게) · 누르면 자세히
 */
object Quake {
    class Eq(val time: Long, val lat: Double, val lon: Double, val loc: String, val mag: Double?, val dep: Double?, val intensity: String, val rem: String)

    private val KST = TimeZone.getTimeZone("Asia/Seoul")
    private val ymd = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = KST }
    private val full = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = KST }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var mem: Pair<Long, List<Eq>>? = null
    private const val KEEP = 3 * 3600_000L
    const val DAYS = 4

    private fun get(url: String): String {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8_000; c.readTimeout = 8_000
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299) throw Exception("HTTP $code")
        return body
    }

    private fun parse(body: String, out: MutableList<Eq>) {
        val b = body.trim()
        if (b.startsWith("<")) {
            if (b.contains("SERVICE_KEY_IS_NOT_REGISTERED") || b.contains("ACCESS DENIED") || b.contains("SERVICE ERROR"))
                throw Exception("지진 정보: 공공데이터포털 '기상청_지진정보 조회서비스' 활용신청 · 승인을 확인해 주세요")
            throw Exception("지진 정보 응답 오류")
        }
        val root = JSONObject(b).let { if (it.has("response")) it.getJSONObject("response") else it }
        val code = root.optJSONObject("header")?.optString("resultCode") ?: ""
        if (code == "03") return   // 자료 없음 (그 기간 지진 없음)
        if (code != "00" && code.isNotBlank()) throw Exception("지진 정보 응답 코드 $code ${root.optJSONObject("header")?.optString("resultMsg") ?: ""}")
        val itemAny = (root.optJSONObject("body")?.opt("items") as? JSONObject)?.opt("item")
        val arr = when (itemAny) { is JSONArray -> itemAny; is JSONObject -> JSONArray().put(itemAny); else -> JSONArray() }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val lat = o.optString("lat").toDoubleOrNull() ?: continue
            val lon = o.optString("lon").toDoubleOrNull() ?: continue
            // 한반도 주변만 (국외 지진 빼기)
            if (lat !in 31.0..40.5 || lon !in 122.0..133.5) continue
            val t = try { full.parse(o.optString("tmEqk").filter { it.isDigit() }.padEnd(14, '0').take(14))?.time } catch (_: Exception) { null } ?: continue
            out.add(Eq(t, lat, lon, o.optString("loc"), o.optString("mt").toDoubleOrNull(), o.optString("dep").toDoubleOrNull(),
                o.optString("inT"), o.optString("rem")))
        }
    }

    /** 최근 4일 지진 (실패하면 err) */
    fun load(c: Context, done: (List<Eq>?, String?) -> Unit) {
        mem?.let { (at, l) -> if (System.currentTimeMillis() - at < KEEP) return done(l, null) }
        val prefs = c.applicationContext.getSharedPreferences("quake", Context.MODE_PRIVATE)
        prefs.getString("list", null)?.let { s -> if (System.currentTimeMillis() - prefs.getLong("at", 0) < KEEP) fromJson(s)?.let { mem = prefs.getLong("at", 0) to it; return done(it, null) } }
        Thread {
            var err: String? = null
            val out = mutableListOf<Eq>()
            val now = System.currentTimeMillis()
            val key = java.net.URLEncoder.encode(BuildConfig.KHOA_KEY, "UTF-8")
            fun fetch(from: Long, to: Long): Boolean {
                val q = "/1360000/EqkInfoService/getEqkMsg?serviceKey=$key&pageNo=1&numOfRows=200&dataType=JSON" +
                    "&fromTmFc=${ymd.format(Date(from))}&toTmFc=${ymd.format(Date(to))}"
                for (s in listOf("https", "http")) {
                    try { parse(get("$s://apis.data.go.kr$q"), out); return true }
                    catch (e: Exception) { err = e.message; if (err?.contains("활용신청") == true) return false }
                }
                return false
            }
            // 한 번에 4일 · 안 되면 하루씩
            if (!fetch(now - (DAYS - 1) * 86_400_000L, now)) {
                out.clear()
                var ok = false
                for (d in 0 until DAYS) if (fetch(now - d * 86_400_000L, now - d * 86_400_000L)) ok = true
                if (ok) err = null
            } else err = null
            val list = out.filter { now - it.time <= DAYS * 86_400_000L }.distinctBy { it.time / 1000 to Math.round(it.lat * 100) }.sortedByDescending { it.time }
            if (err == null) {
                mem = now to list
                prefs.edit().putString("list", toJson(list)).putLong("at", now).apply()
            }
            main.post { if (err == null) done(list, null) else done(mem?.second, err) }
        }.start()
    }

    private fun toJson(l: List<Eq>) = JSONArray().apply { l.forEach { e ->
        put(JSONObject().put("t", e.time).put("a", e.lat).put("o", e.lon).put("l", e.loc).put("m", e.mag ?: JSONObject.NULL)
            .put("d", e.dep ?: JSONObject.NULL).put("i", e.intensity).put("r", e.rem)) } }.toString()
    private fun fromJson(s: String): List<Eq>? = try {
        val a = JSONArray(s)
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            Eq(o.getLong("t"), o.getDouble("a"), o.getDouble("o"), o.optString("l"), if (o.isNull("m")) null else o.optDouble("m"),
                if (o.isNull("d")) null else o.optDouble("d"), o.optString("i"), o.optString("r")) } }
    } catch (_: Exception) { null }

    fun color(m: Double?): Int = when { m == null || m < 3 -> Color.parseColor("#F28C38"); m < 4 -> Color.parseColor("#F25C3B"); else -> Color.parseColor("#D7263D") }

    /** 핀을 누르면 나오는 글 */
    fun detail(e: Eq, myLat: Double?, myLon: Double?): String {
        val f = SimpleDateFormat("M/d(E) HH:mm", Locale.KOREA).apply { timeZone = KST }
        val km = if (myLat != null && myLon != null) FloatArray(1).also { android.location.Location.distanceBetween(myLat, myLon, e.lat, e.lon, it) }[0] / 1000f else null
        return buildString {
            append("🌋 ").append(f.format(Date(e.time))).append("\n")
            if (e.loc.isNotBlank()) append(e.loc).append("\n")
            append("규모 ").append(e.mag?.let { String.format(Locale.KOREA, "%.1f", it) } ?: "?")
            e.dep?.let { append(" · 깊이 ").append(String.format(Locale.KOREA, "%.0f", it)).append("km") }
            append("\n")
            km?.let { append("📍 내 위치에서 ").append(String.format(Locale.KOREA, "%.0f", it)).append("km\n") }
            if (e.intensity.isNotBlank()) append("진도: ").append(e.intensity).append("\n")
            if (e.rem.isNotBlank()) append(e.rem).append("\n")
            append("기상청 발표")
        }
    }

    /** 지도 핀 (규모 숫자 · 클수록 크고 진한 빨강 · 지난 날은 흐리게) */
    class QuakeOverlay(private val c: Context, private val onTap: (Eq) -> Unit) : Overlay() {
        var list: List<Eq> = emptyList()
        private val d = c.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 2 * d }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        private fun radius(e: Eq) = (9 + ((e.mag ?: 2.0) - 2.0).coerceIn(0.0, 4.0) * 4).toFloat() * d

        override fun draw(cv: Canvas, map: MapView, shadow: Boolean) {
            if (shadow || !isEnabled) return
            val now = System.currentTimeMillis()
            val pt = Point()
            list.sortedBy { it.mag ?: 0.0 }.forEach { e ->
                map.projection.toPixels(GeoPoint(e.lat, e.lon), pt)
                val r = radius(e)
                val ageDays = ((now - e.time) / 86_400_000L).toInt().coerceIn(0, 3)
                val alpha = 255 - ageDays * 45
                val cx = pt.x.toFloat(); val cy = pt.y.toFloat() - r * 1.5f
                // 물방울 모양 핀 (끝이 진앙)
                val path = Path().apply {
                    addCircle(cx, cy, r, Path.Direction.CW)
                    moveTo(cx - r * 0.6f, cy + r * 0.8f); lineTo(cx, pt.y.toFloat()); lineTo(cx + r * 0.6f, cy + r * 0.8f); close()
                }
                fill.color = color(e.mag); fill.alpha = alpha
                cv.drawPath(path, fill)
                edge.alpha = alpha; cv.drawCircle(cx, cy, r, edge)
                txt.textSize = (r * 0.85f).coerceAtLeast(9 * d); txt.alpha = alpha
                cv.drawText(e.mag?.let { String.format(Locale.KOREA, "%.1f", it) } ?: "?", cx, cy + txt.textSize * 0.35f, txt)
            }
        }

        override fun onSingleTapConfirmed(ev: MotionEvent, map: MapView): Boolean {
            if (!isEnabled) return false
            val pt = Point()
            val hit = list.minByOrNull { e ->
                map.projection.toPixels(GeoPoint(e.lat, e.lon), pt)
                val r = radius(e); val dx = ev.x - pt.x; val dy = ev.y - (pt.y - r * 1.5f)
                dx * dx + dy * dy
            } ?: return false
            map.projection.toPixels(GeoPoint(hit.lat, hit.lon), pt)
            val r = radius(hit); val dx = ev.x - pt.x; val dy = ev.y - (pt.y - r * 1.5f)
            if (dx * dx + dy * dy > (r * 1.8f) * (r * 1.8f)) return false
            onTap(hit); return true
        }
    }
}
