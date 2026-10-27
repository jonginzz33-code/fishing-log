package com.fishlog.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 바람 · 파도 · 수온 (원하는 날짜)
 *  - 바람(풍속·풍향·돌풍): Open-Meteo 예보 (인증키 없음) · 3달 전 ~ 15일 뒤
 *  - 파고 · 수온(예측): Open-Meteo 해양 예보 (인증키 없음) · 3달 전 ~ 7일 뒤
 *  - 수온(실측): 국립해양조사원 조위관측소 최신 관측데이터 (오늘·어제만, 공공데이터포털 활용신청 필요)
 */
object Weather {

    private val KST: TimeZone = TimeZone.getTimeZone("Asia/Seoul")
    private val main = Handler(Looper.getMainLooper())
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = KST }
    private val keyFmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = KST }
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = KST }

    /** 한 시간: 풍속(m/s), 바람이 불어오는 방향(°), 돌풍(m/s), 파고(m), 수온 예측(℃) */
    class Hour(val time: Long, val wind: Double?, val dir: Double?, val gust: Double?, val wave: Double?, val sst: Double?)

    class Measured(val temp: Double, val at: String, val station: String)

    class Day(
        val dayStart: Long, val hours: List<Hour>,
        val windNote: String, val seaNote: String,
        val measured: Measured?, val measuredNote: String
    ) {
        fun at(hour: Int): Hour? = hours.firstOrNull { ((it.time - dayStart) / 3_600_000L).toInt() == hour }
    }

    private val cache = HashMap<String, Pair<Long, Day>>()

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    private fun dayStartOf(time: Long): Long = dayFmt.parse(dayFmt.format(Date(time)))!!.time

    /** dayStart = 그날 0시 (한국 시각) */
    fun fetch(ctx: Context, lat: Double, lon: Double, dayStart: Long, done: (Day) -> Unit) {
        val key = String.format(Locale.US, "%.2f,%.2f,%d", lat, lon, dayStart)
        cache[key]?.let { (at, d) -> if (System.currentTimeMillis() - at < 30 * 60_000L) { done(d); return } }
        val app = ctx.applicationContext
        Thread {
            val d = try { load(app, lat, lon, dayStart) } catch (e: Exception) {
                Day(dayStart, emptyList(), "바람 정보를 못 받았어요 (인터넷 확인)", "", null, "")
            }
            main.post {
                if (d.hours.isNotEmpty()) cache[key] = System.currentTimeMillis() to d
                done(d)
            }
        }.start()
    }

    private fun load(ctx: Context, lat: Double, lon: Double, dayStart: Long): Day {
        val today = dayStartOf(System.currentTimeMillis())
        val diff = Math.round((dayStart - today) / 86_400_000.0).toInt()
        val date = dayFmt.format(Date(dayStart + 12 * 3_600_000L))

        // ---- 바람 ----
        val wind = HashMap<Long, Triple<Double?, Double?, Double?>>()
        var windNote = ""
        if (diff in -90..15) {
            try {
                val body = get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&hourly=wind_speed_10m,wind_direction_10m,wind_gusts_10m&wind_speed_unit=ms" +
                    "&timezone=Asia%2FSeoul&start_date=$date&end_date=$date")
                val h = JSONObject(body).getJSONObject("hourly")
                val t = h.getJSONArray("time")
                val ws = h.optJSONArray("wind_speed_10m"); val wd = h.optJSONArray("wind_direction_10m"); val wg = h.optJSONArray("wind_gusts_10m")
                for (i in 0 until t.length()) {
                    val ms = isoFmt.parse(t.getString(i))?.time ?: continue
                    wind[ms] = Triple(num(ws, i), num(wd, i), num(wg, i))
                }
            } catch (_: Exception) { windNote = "바람 정보를 못 받았어요 (인터넷 확인)" }
        } else windNote = "바람은 3달 전부터 15일 뒤까지만 볼 수 있어요"

        // ---- 파고 · 수온 예측 (바닷가 격자가 비어 있으면 조금씩 바다 쪽으로) ----
        val sea = HashMap<Long, Pair<Double?, Double?>>()
        var seaNote = ""
        if (diff in -90..7) {
            val offsets = listOf(0.0 to 0.0, 0.0 to -0.08, 0.0 to 0.08, -0.08 to 0.0, 0.08 to 0.0, 0.0 to -0.16, -0.08 to -0.08)
            for ((dla, dlo) in offsets) {
                try {
                    val body = get("https://marine-api.open-meteo.com/v1/marine?latitude=${lat + dla}&longitude=${lon + dlo}" +
                        "&hourly=wave_height,sea_surface_temperature&timezone=Asia%2FSeoul&start_date=$date&end_date=$date")
                    val h = JSONObject(body).getJSONObject("hourly")
                    val t = h.getJSONArray("time")
                    val wv = h.optJSONArray("wave_height"); val st = h.optJSONArray("sea_surface_temperature")
                    var ok = 0
                    for (i in 0 until t.length()) {
                        val ms = isoFmt.parse(t.getString(i))?.time ?: continue
                        val w = num(wv, i); val s = num(st, i)
                        if (w != null || s != null) ok++
                        sea[ms] = w to s
                    }
                    if (ok >= 6) break else sea.clear()
                } catch (_: Exception) {}
            }
            if (sea.isEmpty()) seaNote = "이 위치는 파도·수온 예보가 없어요 (육지 안쪽이거나 인터넷 확인)"
        } else seaNote = "파도·수온 예보는 3달 전부터 7일 뒤까지만 볼 수 있어요"

        val times = (wind.keys + sea.keys).toSortedSet()
        val hours = times.map { t ->
            val w = wind[t]; val s = sea[t]
            Hour(t, w?.first, w?.second, w?.third, s?.first, s?.second)
        }

        // ---- 수온 실측 (오늘·어제) ----
        var measured: Measured? = null
        var mNote = ""
        if (diff in -1..0) {
            val r = measuredTemp(ctx, lat, lon, keyFmt.format(Date(dayStart + 12 * 3_600_000L)))
            measured = r.first; mNote = r.second
        } else if (diff > 0) mNote = "실측 수온은 아직 측정 전이에요 (예측만 보여요)"
        else mNote = "실측 수온은 오늘·어제만 볼 수 있어요"
        return Day(dayStart, hours, windNote, seaNote, measured, mNote)
    }

    private fun num(a: JSONArray?, i: Int): Double? = if (a == null || i >= a.length() || a.isNull(i)) null else a.optDouble(i)

    /** 가까운 조위관측소 3곳 중 수온계가 있는 곳의 가장 최근 값 */
    private fun measuredTemp(ctx: Context, lat: Double, lon: Double, ymd: String): Pair<Measured?, String> {
        val key = BuildConfig.KHOA_KEY
        if (key.isBlank()) return null to "실측 수온: 인증키가 앱에 없어요"
        var note = ""
        for (s in Tide.stationsNear(ctx, lat, lon).take(3)) {
            val q = "/1192136/dtRecent/GetDTRecentApiService?serviceKey=${URLEncoder.encode(key, "UTF-8")}" +
                "&obsCode=${s.code}&reqDate=$ymd&min=60&type=json&pageNo=1&numOfRows=300"
            var body: String? = null
            for (scheme in listOf("https", "http")) {
                try { body = get("$scheme://apis.data.go.kr$q"); break } catch (_: Exception) {}
            }
            val b = body?.trim() ?: continue
            if (b.startsWith("<")) {
                // 활용신청 안 된 키는 XML 오류로 옴
                note = if (b.contains("SERVICE_KEY_IS_NOT_REGISTERED") || b.contains("SERVICE ERROR") || b.contains("ACCESS DENIED"))
                    "실측 수온: 공공데이터포털에서 '조위관측소 최신 관측데이터' 활용신청이 필요해요"
                else "실측 수온을 못 받았어요"
                if (note.contains("활용신청")) return null to note
                continue
            }
            try {
                val root = JSONObject(b).let { if (it.has("response")) it.getJSONObject("response") else it }
                val code0 = root.optJSONObject("header")?.optString("resultCode") ?: ""
                if (code0 != "00") {
                    val msg = root.optJSONObject("header")?.optString("resultMsg") ?: ""
                    note = if (code0 == "30" || code0 == "20" || msg.contains("KEY")) "실측 수온: 공공데이터포털에서 '조위관측소 최신 관측데이터' 활용신청이 필요해요"
                        else "실측 수온을 못 받았어요 ($code0)"
                    if (note.contains("활용신청")) return null to note
                    continue
                }
                val itemAny = (root.optJSONObject("body")?.opt("items") as? JSONObject)?.opt("item")
                val arr = when (itemAny) { is JSONArray -> itemAny; is JSONObject -> JSONArray().put(itemAny); else -> JSONArray() }
                var best: Pair<Double, String>? = null
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val t = o.optString("wtem").toDoubleOrNull() ?: continue
                    val salt = o.optString("slntQty").toDoubleOrNull()
                    // 수온계가 없는 곳은 수온·염분이 둘 다 0으로 옴
                    if (kotlin.math.abs(t) < 0.05 && (salt == null || kotlin.math.abs(salt) < 0.05)) continue
                    if (t < -3 || t > 35) continue
                    val at = o.optString("obsrvnDt")
                    if (best == null || at > best.second) best = t to at
                }
                if (best != null) {
                    val hm = Regex("\\d{1,2}:\\d{2}").find(best.second)?.value ?: best.second
                    return Measured(best.first, hm, s.name) to ""
                }
            } catch (_: Exception) {}
        }
        return null to note.ifBlank { "가까운 관측소에 실측 수온이 없어요" }
    }

    /** 풍속 등급: 0 약함, 1 주의, 2 강함 */
    fun windLevel(ms: Double): Int = when { ms < 5 -> 0; ms < 9 -> 1; else -> 2 }
    fun windLevelName(l: Int) = when (l) { 0 -> "약함"; 1 -> "주의"; else -> "강함" }
}
