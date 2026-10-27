package com.fishlog.app

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor

/**
 * 물때
 *  - 만조·간조: 국립해양조사원 조석예보(고·저조). 가장 가까운 조위관측소, 폰에 저장해서 바다에서도 보기
 *  - 국립해양조사원이 안 되면 Open-Meteo 해양 예측으로 대신
 *  - 물때 단계(몇 물): 달 나이로 계산
 */
object Tide {

    private val KST: TimeZone = TimeZone.getTimeZone("Asia/Seoul")
    private const val SYNODIC = 29.530588853
    private const val NEW_MOON_2000 = 947182440000.0 // 2000-01-06 18:14 UTC

    fun lunarDay(time: Long): Int {
        val cal = Calendar.getInstance(KST).apply { timeInMillis = time; set(Calendar.HOUR_OF_DAY, 12) }
        val days = (cal.timeInMillis - NEW_MOON_2000) / 86_400_000.0
        val age = ((days % SYNODIC) + SYNODIC) % SYNODIC
        return floor(age).toInt() + 1
    }

    fun mulName(time: Long): String {
        val d = lunarDay(time)
        val idx = (((d - 9) % 15) + 15) % 15
        val name = when (idx) {
            0 -> "무시"
            14 -> "조금"
            else -> "${idx}물"
        }
        val kind = when (idx) {
            in 5..9 -> "사리"
            0, 12, 13, 14 -> "조금 무렵"
            else -> "중간"
        }
        return "$name · $kind (음력 ${d}일 무렵)"
    }

    /** 만조/간조. value 단위는 m */
    class Extreme(val time: Long, val high: Boolean, val value: Double)

    /**
     * 하루 조위. values[i] = 0시 + i*stepMin 분의 높이(m)
     * exact = 국립해양조사원 만조·간조 (있으면 그대로 표시)
     */
    class Day(val dateMillis: Long, val values: List<Double?>, val stepMin: Int,
              val exact: List<Extreme>?, val source: String)

    private val main = Handler(Looper.getMainLooper())
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = KST }
    private val keyFmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = KST }
    private val khoaTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = KST }

    /** 국립해양조사원 정보를 못 받은 이유 (화면에 표시) */
    @Volatile var lastError: String = ""

    private class Station(val code: String, val name: String, val lat: Double, val lon: Double)
    private var stations: List<Station>? = null

    private fun stations(ctx: Context): List<Station> {
        stations?.let { return it }
        val list = try {
            val arr = JSONArray(ctx.assets.open("stations.json").bufferedReader().use { it.readText() })
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Station(o.getString("code"), o.getString("name"), o.getDouble("lat"), o.getDouble("lng"))
            }
        } catch (_: Exception) { emptyList() }
        stations = list
        return list
    }

    private fun dist(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Float {
        val r = FloatArray(1)
        Location.distanceBetween(aLat, aLon, bLat, bLon, r)
        return r[0]
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    // ---------------- 국립해양조사원 ----------------
    /** 한 관측소의 하루 만조·간조. 폰에 저장된 게 있으면 그걸 씀. 실패하면 null */
    private fun khoaDay(ctx: Context, code: String, ymd: String): List<Extreme>? {
        val prefs = ctx.getSharedPreferences("tide", Context.MODE_PRIVATE)
        val cacheKey = "${code}_$ymd"
        prefs.getString(cacheKey, null)?.let { s ->
            try {
                val a = JSONArray(s)
                return (0 until a.length()).map { val e = a.getJSONArray(it); Extreme(e.getLong(0), e.getBoolean(1), e.getDouble(2)) }
            } catch (_: Exception) {}
        }
        val key = BuildConfig.KHOA_KEY
        if (key.isBlank()) { lastError = "인증키가 앱에 없음 (GitHub 비밀값 KHOA_KEY 확인)"; return null }
        val q = "/1192136/tideFcstHghLw/GetTideFcstHghLwApiService" +
            "?serviceKey=${URLEncoder.encode(key, "UTF-8")}&obsCode=$code&reqDate=$ymd&type=json&pageNo=1&numOfRows=20"
        // 보안 연결(https)이 안 되는 폰이 있어서 실패하면 일반 연결(http)로 한 번 더
        var body: String? = null
        var netErr = ""
        for (scheme in listOf("https", "http")) {
            try { body = get("$scheme://apis.data.go.kr$q"); break }
            catch (e: Exception) { netErr = (e.javaClass.simpleName + " " + (e.message ?: "")).take(80) }
        }
        if (body == null) { lastError = "서버 연결 실패: $netErr"; return null }
        return try {
            val trimmed = body.trim()
            if (trimmed.startsWith("<")) {
                val msg = Regex("<returnAuthMsg>(.*?)</returnAuthMsg>|<resultMsg>(.*?)</resultMsg>").find(trimmed)
                    ?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() } ?: "알 수 없는 응답"
                lastError = "공공데이터포털 응답: $msg"
                return null
            }
            val root = JSONObject(trimmed).let { if (it.has("response")) it.getJSONObject("response") else it }
            val header = root.optJSONObject("header")
            val code0 = header?.optString("resultCode") ?: ""
            if (code0 != "00") {
                lastError = "공공데이터포털 오류 $code0: ${header?.optString("resultMsg") ?: ""}"
                return null
            }
            val items = root.optJSONObject("body")?.opt("items")
            val itemAny = (items as? JSONObject)?.opt("item")
            val arr = when (itemAny) {
                is JSONArray -> itemAny
                is JSONObject -> JSONArray().put(itemAny)
                else -> JSONArray()
            }
            val list = (0 until arr.length()).mapNotNull {
                val o = arr.getJSONObject(it)
                val t = khoaTimeFmt.parse(o.getString("predcDt"))?.time ?: return@mapNotNull null
                val high = o.optString("extrSe") == "1" || o.optString("extrSe") == "3"
                Extreme(t, high, o.getDouble("predcTdlvVl") / 100.0)
            }.sortedBy { it.time }
            if (list.isNotEmpty()) {
                val a = JSONArray()
                list.forEach { a.put(JSONArray().put(it.time).put(it.high).put(it.value)) }
                prefs.edit().putString(cacheKey, a.toString()).apply()
            } else {
                lastError = "이 관측소($code)는 예보가 비어 있음"
            }
            list
        } catch (e: Exception) {
            lastError = "응답 읽기 실패: ${e.message?.take(60)}"
            null
        }
    }

    /** 만조·간조 사이를 코사인 곡선으로 이어서 10분 간격 조위 만들기 */
    private fun curve(dayStart: Long, ext: List<Extreme>): List<Double?> {
        val step = 10
        return (0..(24 * 60 / step)).map { i ->
            val t = dayStart + i * step * 60_000L
            val k = ext.indexOfLast { it.time <= t }
            if (k < 0 || k >= ext.size - 1) null
            else {
                val a = ext[k]; val b = ext[k + 1]
                val f = (t - a.time).toDouble() / (b.time - a.time)
                a.value + (b.value - a.value) * (1 - cos(PI * f)) / 2
            }
        }
    }

    private fun tryKhoa(ctx: Context, lat: Double, lon: Double, time: Long): Day? {
        val dayStart = dayFmt.parse(dayFmt.format(Date(time)))!!.time
        val ymd = keyFmt.format(Date(time))
        val prev = keyFmt.format(Date(dayStart - 12 * 3600_000L))
        val next = keyFmt.format(Date(dayStart + 36 * 3600_000L))
        val near = stations(ctx).sortedBy { dist(lat, lon, it.lat, it.lon) }.take(3)
        for (s in near) {
            val today = khoaDay(ctx, s.code, ymd) ?: continue
            if (today.isEmpty()) continue
            val all = (khoaDay(ctx, s.code, prev).orEmpty() + today + khoaDay(ctx, s.code, next).orEmpty())
                .distinctBy { it.time }.sortedBy { it.time }
            val km = dist(lat, lon, s.lat, s.lon) / 1000f
            return Day(dayStart, curve(dayStart, all), 10, today,
                String.format(Locale.KOREA, "국립해양조사원 %s 관측소 기준 (%.0fkm)", s.name, km))
        }
        return null
    }

    // ---------------- Open-Meteo (대체) ----------------
    private fun tryOpenMeteo(lat: Double, lon: Double, time: Long): Day? {
        val date = dayFmt.format(Date(time))
        val offsets = listOf(0.0 to 0.0, 0.0 to -0.08, 0.0 to 0.08, -0.08 to 0.0, 0.08 to 0.0, 0.0 to -0.16, -0.08 to -0.08)
        for ((dla, dlo) in offsets) {
            try {
                val body = get("https://marine-api.open-meteo.com/v1/marine?latitude=${lat + dla}&longitude=${lon + dlo}" +
                    "&hourly=sea_level_height_msl&timezone=Asia%2FSeoul&start_date=$date&end_date=$date")
                val arr = JSONObject(body).getJSONObject("hourly").getJSONArray("sea_level_height_msl")
                val vals = (0 until arr.length()).map { if (arr.isNull(it)) null else arr.getDouble(it) }
                if (vals.count { it != null } >= 12) {
                    return Day(dayFmt.parse(date)!!.time, vals.take(24), 60, null,
                        "Open-Meteo 예측으로 대신 표시 (시각이 어긋날 수 있어요)\n국립해양조사원 실패 이유: " + lastError.ifBlank { "알 수 없음" })
                }
            } catch (_: Exception) {}
        }
        return null
    }

    fun fetch(ctx: Context, lat: Double, lon: Double, time: Long, done: (Day?, String?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val d = tryKhoa(app, lat, lon, time) ?: tryOpenMeteo(lat, lon, time)
            main.post { if (d != null) done(d, null) else done(null, "물때 정보를 불러오지 못했어요 (인터넷 확인)") }
        }.start()
    }

    /** 출조 시작할 때 오늘·내일 물때를 미리 받아 둠 (바다에서 인터넷이 없어도 보이게) */
    fun prefetch(ctx: Context, lat: Double, lon: Double) {
        val app = ctx.applicationContext
        Thread {
            val now = System.currentTimeMillis()
            tryKhoa(app, lat, lon, now)
            tryKhoa(app, lat, lon, now + 24 * 3600_000L)
        }.start()
    }

    // ---------------- 계산 ----------------
    fun extremes(d: Day): List<Extreme> {
        d.exact?.let { return it }
        val v = d.values
        val out = mutableListOf<Extreme>()
        val stepMs = d.stepMin * 60_000L
        for (i in 1 until v.size - 1) {
            val a = v[i - 1] ?: continue; val b = v[i] ?: continue; val c = v[i + 1] ?: continue
            val high = b >= a && b > c
            val low = b <= a && b < c
            if (!high && !low) continue
            val denom = a - 2 * b + c
            val off = if (denom != 0.0) 0.5 * (a - c) / denom else 0.0
            out.add(Extreme(d.dateMillis + ((i + off) * stepMs).toLong(), high, b))
        }
        return out
    }

    fun valueAt(d: Day, time: Long): Double? {
        val h = (time - d.dateMillis) / (d.stepMin * 60_000.0)
        if (h < 0 || h > d.values.size - 1) return null
        val i = floor(h).toInt()
        val a = d.values.getOrNull(i) ?: return null
        val b = d.values.getOrNull(i + 1) ?: return a
        return a + (b - a) * (h - i)
    }

    fun rising(d: Day, time: Long): Boolean? {
        val a = valueAt(d, time - 600_000L) ?: return null
        val b = valueAt(d, time + 600_000L) ?: return null
        return b > a
    }
}
