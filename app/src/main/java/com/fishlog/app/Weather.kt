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
 *  - 기온·하늘·비·바람(우선): 기상청 단기예보 (오늘 ~ 3일 뒤, 공공데이터포털 활용신청 필요)
 */
object Weather {

    private val KST: TimeZone = TimeZone.getTimeZone("Asia/Seoul")
    private val main = Handler(Looper.getMainLooper())
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = KST }
    private val keyFmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = KST }
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = KST }

    /** 한 시간: 풍속(m/s), 바람이 불어오는 방향(°), 돌풍(m/s), 파고(m), 수온 예측(℃) */
    class Hour(val time: Long, val wind: Double?, val dir: Double?, val gust: Double?, val wave: Double?, val sst: Double?,
               val temp: Double? = null, val sky: Int? = null, val pty: Int? = null, val pop: Int? = null) {
        /** 하늘 그림: ☀ 맑음 · ⛅ 구름많음 · ☁ 흐림 · 🌧 비 · 🌨 눈 */
        fun skyIcon(): String = when {
            pty != null && pty > 0 -> if (pty == 3 || pty == 7) "🌨" else "🌧"
            sky == 1 -> "☀"; sky == 3 -> "⛅"; sky == 4 -> "☁"
            else -> ""
        }
    }

    /** 실측 수온: 가장 최근 값 + 그날 시각별 값 (정시 → ℃) */
    class Measured(val temp: Double, val at: String, val station: String, val series: Map<Long, Double> = emptyMap())

    class Day(
        val dayStart: Long, val hours: List<Hour>,
        val windNote: String, val seaNote: String,
        val measured: Measured?, val measuredNote: String,
        val kmaNote: String = "", val fromKma: Boolean = false
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

        // ---- 기상청 단기예보 (오늘 ~ 3일 뒤): 기온·하늘·비·바람 ----
        var kmaNote = ""
        val kma: Map<Long, Kma> = if (diff in 0..3) {
            try { kmaForecast(lat, lon, keyFmt.format(Date(dayStart + 12 * 3_600_000L))) }
            catch (e: KmaError) { kmaNote = e.message ?: ""; emptyMap() }
            catch (_: Exception) { kmaNote = "기상청 예보를 못 받았어요 (인터넷 확인)"; emptyMap() }
        } else emptyMap()
        if (diff > 3) kmaNote = "기상청 예보(기온·하늘)는 3일 뒤까지만 있어요"

        val times = (wind.keys + sea.keys + kma.keys).toSortedSet()
        val hours = times.map { t ->
            val w = wind[t]; val s = sea[t]; val k = kma[t]
            // 바람은 기상청 값이 있으면 기상청 우선 (돌풍은 Open-Meteo)
            Hour(t, k?.wsd ?: w?.first, k?.vec ?: w?.second, w?.third, k?.wav ?: s?.first, s?.second,
                k?.tmp, k?.sky, k?.pty, k?.pop)
        }

        // ---- 수온 실측 (오늘·어제) ----
        var measured: Measured? = null
        var mNote = ""
        if (diff in -1..0) {
            val r = measuredTemp(ctx, lat, lon, keyFmt.format(Date(dayStart + 12 * 3_600_000L)))
            measured = r.first; mNote = r.second
        } else if (diff > 0) mNote = "실측 수온은 아직 측정 전이에요 (예측만 보여요)"
        else mNote = "실측 수온은 오늘·어제만 볼 수 있어요"
        return Day(dayStart, hours, windNote, seaNote, measured, mNote, kmaNote, kma.isNotEmpty())
    }

    // ---------------- 기상청 단기예보 ----------------
    private class Kma { var tmp: Double? = null; var wsd: Double? = null; var vec: Double? = null; var sky: Int? = null
        var pty: Int? = null; var pop: Int? = null; var wav: Double? = null }
    private class KmaError(msg: String) : Exception(msg)

    /** 위도·경도 → 기상청 격자 (Lambert 정각원추) */
    fun grid(lat: Double, lon: Double): Pair<Int, Int> {
        val re = 6371.00877 / 5.0
        val deg = Math.PI / 180.0
        val slat1 = 30.0 * deg; val slat2 = 60.0 * deg; val olon = 126.0 * deg; val olat = 38.0 * deg
        var sn = Math.tan(Math.PI * 0.25 + slat2 * 0.5) / Math.tan(Math.PI * 0.25 + slat1 * 0.5)
        sn = Math.log(Math.cos(slat1) / Math.cos(slat2)) / Math.log(sn)
        var sf = Math.tan(Math.PI * 0.25 + slat1 * 0.5)
        sf = Math.pow(sf, sn) * Math.cos(slat1) / sn
        var ro = Math.tan(Math.PI * 0.25 + olat * 0.5)
        ro = re * sf / Math.pow(ro, sn)
        var ra = Math.tan(Math.PI * 0.25 + lat * deg * 0.5)
        ra = re * sf / Math.pow(ra, sn)
        var theta = lon * deg - olon
        if (theta > Math.PI) theta -= 2.0 * Math.PI
        if (theta < -Math.PI) theta += 2.0 * Math.PI
        theta *= sn
        val x = Math.floor(ra * Math.sin(theta) + 43.0 + 0.5).toInt()
        val y = Math.floor(ro - ra * Math.cos(theta) + 136.0 + 0.5).toInt()
        return x to y
    }

    /** 가장 최근 발표 (02·05·08·11·14·17·20·23시, 발표 10분 뒤부터) */
    private fun baseTime(now: Long): Pair<String, String> {
        val c = java.util.Calendar.getInstance(KST).apply { timeInMillis = now - 15 * 60_000L }
        val bases = listOf(2, 5, 8, 11, 14, 17, 20, 23)
        val h = c.get(java.util.Calendar.HOUR_OF_DAY)
        val b = bases.lastOrNull { it <= h }
        if (b == null) { c.add(java.util.Calendar.DAY_OF_MONTH, -1); return keyFmt.format(c.time) to "2300" }
        return keyFmt.format(c.time) to String.format(Locale.US, "%02d00", b)
    }

    private val kmaCache = HashMap<String, Pair<Long, List<JSONObject>>>()

    private fun kmaForecast(lat: Double, lon: Double, ymd: String): Map<Long, Kma> {
        val key = BuildConfig.KHOA_KEY
        if (key.isBlank()) throw KmaError("기상청 예보: 인증키가 앱에 없어요")
        val (nx, ny) = grid(lat, lon)
        val (bd, bt) = baseTime(System.currentTimeMillis())
        val ck = "$nx,$ny,$bd$bt"
        val items = kmaCache[ck]?.takeIf { System.currentTimeMillis() - it.first < 60 * 60_000L }?.second ?: run {
            val q = "/1360000/VilageFcstInfoService_2.0/getVilageFcst?serviceKey=${URLEncoder.encode(key, "UTF-8")}" +
                "&pageNo=1&numOfRows=1500&dataType=JSON&base_date=$bd&base_time=$bt&nx=$nx&ny=$ny"
            var body: String? = null
            for (scheme in listOf("https", "http")) {
                try { body = get("$scheme://apis.data.go.kr$q"); break } catch (_: Exception) {}
            }
            val b = body?.trim() ?: throw KmaError("기상청 예보를 못 받았어요 (인터넷 확인)")
            if (b.startsWith("<")) {
                if (b.contains("SERVICE_KEY_IS_NOT_REGISTERED") || b.contains("SERVICE ERROR") || b.contains("ACCESS DENIED"))
                    throw KmaError("기상청 예보: 공공데이터포털 '기상청_단기예보' 활용신청을 확인해 주세요 (승인 뒤 1시간쯤 걸릴 수 있어요)")
                throw KmaError("기상청 예보를 못 받았어요")
            }
            val root = JSONObject(b).let { if (it.has("response")) it.getJSONObject("response") else it }
            val code0 = root.optJSONObject("header")?.optString("resultCode") ?: ""
            if (code0 != "00") throw KmaError("기상청 예보를 못 받았어요 ($code0)")
            val arr = root.optJSONObject("body")?.optJSONObject("items")?.optJSONArray("item") ?: JSONArray()
            val l = (0 until arr.length()).map { arr.getJSONObject(it) }
            kmaCache[ck] = System.currentTimeMillis() to l
            l
        }
        val out = HashMap<Long, Kma>()
        val tf = SimpleDateFormat("yyyyMMddHHmm", Locale.US).apply { timeZone = KST }
        items.filter { it.optString("fcstDate") == ymd }.forEach { o ->
            val t = tf.parse(o.optString("fcstDate") + o.optString("fcstTime"))?.time ?: return@forEach
            val k = out.getOrPut(t) { Kma() }
            val v = o.optString("fcstValue")
            when (o.optString("category")) {
                "TMP" -> k.tmp = v.toDoubleOrNull()
                "WSD" -> k.wsd = v.toDoubleOrNull()
                "VEC" -> k.vec = v.toDoubleOrNull()
                "SKY" -> k.sky = v.toIntOrNull()
                "PTY" -> k.pty = v.toIntOrNull()
                "POP" -> k.pop = v.toIntOrNull()
                "WAV" -> k.wav = v.toDoubleOrNull()?.takeIf { it >= 0 && it < 30 }
            }
        }
        return out
    }

    private fun num(a: JSONArray?, i: Int): Double? = if (a == null || i >= a.length() || a.isNull(i)) null else a.optDouble(i)

    /** 가까운 조위관측소 3곳 중 수온계가 있는 곳의 가장 최근 값 */
    private fun measuredTemp(ctx: Context, lat: Double, lon: Double, ymd: String): Pair<Measured?, String> {
        val key = BuildConfig.KHOA_KEY
        if (key.isBlank()) return null to "실측 수온: 인증키가 앱에 없어요"
        var note = ""
        // 반경 60km 안 관측소 6곳까지 (수온계가 있는 곳을 찾을 때까지)
        val near = Tide.stationsNear(ctx, lat, lon).filter { (Tide.stationKm(it, lat, lon) ?: 999f) <= 60f }.take(6)
        for (s in near) {
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
                val series = HashMap<Long, Double>()
                val dtRe = Regex("(\\d{4})\\D?(\\d{2})\\D?(\\d{2})\\D*(\\d{1,2}):(\\d{2})")
                val hourFmt = SimpleDateFormat("yyyyMMddHH", Locale.US).apply { timeZone = KST }
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val t = o.optString("wtem").toDoubleOrNull() ?: continue
                    val salt = o.optString("slntQty").toDoubleOrNull()
                    // 수온계가 없는 곳은 수온·염분이 둘 다 0으로 옴
                    if (kotlin.math.abs(t) < 0.05 && (salt == null || kotlin.math.abs(salt) < 0.05)) continue
                    if (t < -3 || t > 35) continue
                    val at = o.optString("obsrvnDt")
                    if (best == null || at > best.second) best = t to at
                    // 시각별: 정시(분 0~29)는 그 시, 30분 이후는 다음 시로
                    dtRe.find(at)?.groupValues?.let { gv ->
                        val ymd2 = gv[1] + gv[2] + gv[3]
                        if (ymd2 == ymd) {
                            var h = gv[4].toInt(); val mi = gv[5].toInt()
                            if (mi >= 30) h += 1
                            hourFmt.parse(ymd2 + String.format(Locale.US, "%02d", h.coerceAtMost(23)))?.time?.let { k ->
                                if (h <= 23 && !series.containsKey(k)) series[k] = t
                            }
                        }
                    }
                }
                if (best != null) {
                    val hm = Regex("\\d{1,2}:\\d{2}").find(best.second)?.value ?: best.second
                    return Measured(best.first, hm, s.name, series) to ""
                }
            } catch (_: Exception) {}
        }
        return null to note.ifBlank { "근처 관측소(60km 안)에 수온계가 없어 예상 수온만 보여요" }
    }

    // ---------------- 주간 (7일) · Open-Meteo ----------------
    class WeekDay(val dayStart: Long, val tmin: Double?, val tmax: Double?, val pop: Int?, val wind: Double?,
                  val wave: Double?, val sst: Double?, val code: Int?) {
        /** 하늘 그림 (시간별과 같은 규칙) */
        fun skyIcon(): String = when (code) {
            null -> ""
            0, 1 -> "☀"; 2 -> "⛅"; 3, 45, 48 -> "☁"
            in 71..77, 85, 86 -> "🌨"
            else -> if (code >= 51) "🌧" else "⛅"
        }
    }

    private var weekCache: Pair<String, Pair<Long, List<WeekDay>>>? = null

    fun fetchWeek(lat: Double, lon: Double, done: (List<WeekDay>?) -> Unit) {
        val key = String.format(Locale.US, "%.2f,%.2f", lat, lon)
        weekCache?.let { (k, v) -> if (k == key && System.currentTimeMillis() - v.first < 60 * 60_000L) { done(v.second); return } }
        Thread {
            val r = try { loadWeek(lat, lon) } catch (_: Exception) { null }
            main.post {
                if (!r.isNullOrEmpty()) weekCache = key to (System.currentTimeMillis() to r)
                done(r)
            }
        }.start()
    }

    private fun loadWeek(lat: Double, lon: Double): List<WeekDay> {
        val body = get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max" +
            "&wind_speed_unit=ms&timezone=Asia%2FSeoul&forecast_days=7")
        val dd = JSONObject(body).getJSONObject("daily")
        val days = dd.getJSONArray("time")
        // 바다: 최대 파고 + 하루 평균 수온 (바닷가 격자가 비어 있으면 조금씩 바다 쪽으로)
        val waveBy = HashMap<String, Double>(); val sstBy = HashMap<String, Double>()
        val offsets = listOf(0.0 to 0.0, 0.0 to -0.08, 0.0 to 0.08, -0.08 to 0.0, 0.08 to 0.0, 0.0 to -0.16)
        for ((dla, dlo) in offsets) {
            try {
                val mb = get("https://marine-api.open-meteo.com/v1/marine?latitude=${lat + dla}&longitude=${lon + dlo}" +
                    "&daily=wave_height_max&hourly=sea_surface_temperature&timezone=Asia%2FSeoul&forecast_days=7")
                val root = JSONObject(mb)
                root.optJSONObject("daily")?.let { d ->
                    val t = d.getJSONArray("time"); val w = d.optJSONArray("wave_height_max")
                    for (i in 0 until t.length()) num(w, i)?.let { waveBy[t.getString(i)] = it }
                }
                root.optJSONObject("hourly")?.let { h ->
                    val t = h.getJSONArray("time"); val st = h.optJSONArray("sea_surface_temperature")
                    val sums = HashMap<String, Pair<Double, Int>>()
                    for (i in 0 until t.length()) num(st, i)?.let { v ->
                        val day = t.getString(i).take(10)
                        val p = sums[day] ?: (0.0 to 0); sums[day] = (p.first + v) to (p.second + 1)
                    }
                    sums.forEach { (k, p) -> sstBy[k] = p.first / p.second }
                }
                if (waveBy.isNotEmpty() || sstBy.isNotEmpty()) break
            } catch (_: Exception) {}
        }
        return (0 until days.length()).map { i ->
            val ds = days.getString(i)
            WeekDay(dayFmt.parse(ds)!!.time, num(dd.optJSONArray("temperature_2m_min"), i), num(dd.optJSONArray("temperature_2m_max"), i),
                num(dd.optJSONArray("precipitation_probability_max"), i)?.toInt(), num(dd.optJSONArray("wind_speed_10m_max"), i),
                waveBy[ds], sstBy[ds], num(dd.optJSONArray("weather_code"), i)?.toInt())
        }
    }

    /** 풍속 등급: 0 약함, 1 주의, 2 강함 */
    fun windLevel(ms: Double): Int = when { ms < 5 -> 0; ms < 9 -> 1; else -> 2 }
    fun windLevelName(l: Int) = when (l) { 0 -> "약함"; 1 -> "주의"; else -> "강함" }
}
