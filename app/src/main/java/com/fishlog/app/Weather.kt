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
               val temp: Double? = null, val sky: Int? = null, val pty: Int? = null, val pop: Int? = null,
               val period: Double? = null, val waveDir: Double? = null, val thunder: Boolean = false, val wcode: Int? = null) {
        /** 하늘 그림: ⚡ 뇌우 > 🌀 돌풍(20노트↑) > 💨 강풍(10노트↑) > 🌧·🌨 > ☀ ⛅ ☁ */
        fun skyIcon(): String = when {
            thunder || (wcode != null && wcode >= 95) -> "⚡"
            wind != null && wind >= KT20 -> "🌀"
            wind != null && wind >= KT10 -> "💨"
            pty != null && pty > 0 -> if (pty == 3 || pty == 7) "🌨" else "🌧"
            sky == 1 -> "☀"; sky == 3 -> "⛅"; sky == 4 -> "☁"
            wcode != null -> codeIcon(wcode)
            else -> ""
        }
    }

    // ---------------- 서버에 그날 날씨 저장 (못 받을 때 대비) ----------------
    private fun wxDoc(lat: Double, lon: Double, ymd: String) = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        .collection("wx").document(String.format(Locale.US, "%.2f_%.2f_%s", lat, lon, ymd))

    /** 시각 → 저장된 한 시간 (없거나 로그인 전이면 빈 값, 3초 넘으면 포기) */
    private fun loadSaved(lat: Double, lon: Double, ymd: String): Map<Long, Hour> {
        if (Cloud.user == null) return emptyMap()
        return try {
            val d = com.google.android.gms.tasks.Tasks.await(wxDoc(lat, lon, ymd).get(), 3, java.util.concurrent.TimeUnit.SECONDS)
            val o = JSONObject(d.getString("h") ?: return emptyMap())
            fun dd(j: JSONObject, k: String) = if (j.has(k) && !j.isNull(k)) j.optDouble(k) else null
            fun ii(j: JSONObject, k: String) = if (j.has(k) && !j.isNull(k)) j.optInt(k) else null
            o.keys().asSequence().mapNotNull { k ->
                val t = k.toLongOrNull() ?: return@mapNotNull null
                val j = o.getJSONObject(k)
                t to Hour(t, dd(j, "w"), dd(j, "d"), dd(j, "g"), dd(j, "wv"), dd(j, "s"), dd(j, "t"), ii(j, "sk"), ii(j, "pt"), ii(j, "p"),
                    dd(j, "pd"), dd(j, "wd"), j.optBoolean("th", false), ii(j, "c"))
            }.toMap()
        } catch (_: Exception) { emptyMap() }
    }

    /** 받은 날씨를 기억해 두는 시간 (3시간 30분) */
    const val KEEP_MS = 210 * 60_000L

    fun hoursToJson(hours: List<Hour>): JSONObject {
        val o = JSONObject()
        hours.forEach { h ->
            val j = JSONObject()
            fun put(k: String, v: Any?) { if (v != null) j.put(k, v) }
            put("w", h.wind); put("d", h.dir); put("g", h.gust); put("wv", h.wave); put("s", h.sst); put("t", h.temp)
            put("sk", h.sky); put("pt", h.pty); put("p", h.pop); put("pd", h.period); put("wd", h.waveDir); put("c", h.wcode)
            if (h.thunder) j.put("th", true)
            if (j.length() > 0) o.put(h.time.toString(), j)
        }
        return o
    }

    fun hoursFromJson(o: JSONObject): List<Hour> {
        fun dd(j: JSONObject, k: String) = if (j.has(k) && !j.isNull(k)) j.optDouble(k) else null
        fun ii(j: JSONObject, k: String) = if (j.has(k) && !j.isNull(k)) j.optInt(k) else null
        return o.keys().asSequence().mapNotNull { k ->
            val t = k.toLongOrNull() ?: return@mapNotNull null
            val j = o.getJSONObject(k)
            Hour(t, dd(j, "w"), dd(j, "d"), dd(j, "g"), dd(j, "wv"), dd(j, "s"), dd(j, "t"), ii(j, "sk"), ii(j, "pt"), ii(j, "p"),
                dd(j, "pd"), dd(j, "wd"), j.optBoolean("th", false), ii(j, "c"))
        }.sortedBy { it.time }.toList()
    }

    /** 출조 기록에 넣어 둔 날씨 (시간별 + 실측 수온) */
    class Snap(val hours: List<Hour>, val measured: Map<Long, Double>, val station: String, val note: String)

    fun snapFromJson(s: String): Snap? = try {
        val o = JSONObject(s)
        val m = HashMap<Long, Double>()
        o.optJSONObject("m")?.let { mj -> mj.keys().forEach { k -> k.toLongOrNull()?.let { m[it] = mj.getDouble(k) } } }
        Snap(hoursFromJson(o.optJSONObject("h") ?: JSONObject()), m, o.optString("st"), o.optString("n"))
    } catch (_: Exception) { null }

    /**
     * 출조 시간대(앞뒤 1시간)의 날씨를 모아 출조 기록에 넣을 글자로 (완료 때 한 번)
     * done(null) = 위치가 없거나 못 받음
     */
    fun snapshot(ctx: Context, t: Store.Trip, done: (String?) -> Unit) {
        val loc = t.anyLocation() ?: return done(null)
        val end = if (t.end > 0) t.end else System.currentTimeMillis()
        val from = t.start - 3_600_000L; val to = end + 3_600_000L
        val days = generateSequence(dayStartOf(from)) { it + 86_400_000L }.takeWhile { it <= to }.toList().take(3)
        val hours = ArrayList<Hour>(); val meas = HashMap<Long, Double>()
        var station = ""; val notes = ArrayList<String>()
        var left = days.size
        days.forEach { ds ->
            fetch(ctx, loc.first, loc.second, ds) { d ->
                hours.addAll(d.hours.filter { it.time in from..to })
                d.measured?.let { m -> station = m.station; m.series.filterKeys { it in from..to }.forEach { (k, v) -> meas[k] = v } }
                if (d.seaNote.isNotBlank()) notes.add(d.seaNote)
                left--
                if (left == 0) {
                    if (hours.isEmpty()) { done(null); return@fetch }
                    val o = JSONObject().put("h", hoursToJson(hours.sortedBy { it.time }))
                    if (meas.isNotEmpty()) o.put("m", JSONObject().apply { meas.forEach { (k, v) -> put(k.toString(), v) } })
                    if (station.isNotBlank()) o.put("st", station)
                    notes.firstOrNull()?.let { o.put("n", it) }
                    o.put("at", System.currentTimeMillis())
                    done(o.toString())
                }
            }
        }
    }

    /** 받은 값을 서버에 합쳐 저장 (지난 시간 값이 사라지지 않게) */
    private fun saveDay(lat: Double, lon: Double, ymd: String, hours: List<Hour>) {
        if (Cloud.user == null || hours.isEmpty()) return
        try {
            val o = JSONObject()
            hours.forEach { h ->
                val j = JSONObject()
                fun put(k: String, v: Any?) { if (v != null) j.put(k, v) }
                put("w", h.wind); put("d", h.dir); put("g", h.gust); put("wv", h.wave); put("s", h.sst); put("t", h.temp)
                put("sk", h.sky); put("pt", h.pty); put("p", h.pop); put("pd", h.period); put("wd", h.waveDir); put("c", h.wcode)
                if (h.thunder) j.put("th", true)
                if (j.length() > 0) o.put(h.time.toString(), j)
            }
            wxDoc(lat, lon, ymd).set(mapOf("h" to o.toString(), "t" to System.currentTimeMillis()))
        } catch (_: Exception) {}
    }

    /** 10노트 ≈ 5.14m/s, 20노트 ≈ 10.3m/s */
    const val KT10 = 5.14
    const val KT20 = 10.29
    fun knots(ms: Double) = ms / 0.514444

    /** Open-Meteo 날씨 코드 → 그림 */
    fun codeIcon(c: Int): String = when (c) {
        0, 1 -> "☀"; 2 -> "⛅"; 3, 45, 48 -> "☁"
        in 71..77, 85, 86 -> "🌨"
        in 95..99 -> "⚡"
        else -> if (c >= 51) "🌧" else "⛅"
    }

    /** 그림 뒤 동그라미 색 */
    fun iconBg(icon: String): Int = when (icon) {
        "☀" -> android.graphics.Color.parseColor("#F2C14E"); "☁" -> android.graphics.Color.parseColor("#3A7FBF")
        "🌧", "🌨" -> android.graphics.Color.parseColor("#1D3F8A")
        "⚡" -> android.graphics.Color.parseColor("#5B2A8C")
        "💨" -> android.graphics.Color.parseColor("#B8661A")
        "🌀" -> android.graphics.Color.parseColor("#B3262F")
        else -> android.graphics.Color.TRANSPARENT
    }

    /** 16방위 (바람이 불어오는 쪽) */
    fun dir16(deg: Double): String {
        val n = arrayOf("북", "북북동", "북동", "동북동", "동", "동남동", "남동", "남남동", "남", "남남서", "남서", "서남서", "서", "서북서", "북서", "북북서")
        return n[((((deg % 360) + 360) % 360 + 11.25) / 22.5).toInt() % 16]
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

    /** 출조 시작 때 오늘 날씨를 미리 받아 두기 (배 위에서 신호가 약해도 보이게) */
    fun prefetch(ctx: Context, lat: Double, lon: Double) = fetch(ctx, lat, lon, dayStartOf(System.currentTimeMillis())) { }

    /** dayStart = 그날 0시 (한국 시각) */
    fun fetch(ctx: Context, lat: Double, lon: Double, dayStart: Long, done: (Day) -> Unit) {
        val key = String.format(Locale.US, "%.2f,%.2f,%d", lat, lon, dayStart)
        // 기억 시간: 오늘은 30분(낙뢰·지금 날씨), 다른 날은 3시간 30분 (기상청 예보는 3시간마다 바뀜)
        val ttl = if (dayStart == dayStartOf(System.currentTimeMillis())) 30 * 60_000L else KEEP_MS
        cache[key]?.let { (at, d) -> if (System.currentTimeMillis() - at < ttl) { done(d); return } }
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
        val wcodes = HashMap<Long, Int>()
        val omTemp = HashMap<Long, Double>(); val omPop = HashMap<Long, Int>()
        var windNote = ""
        if (diff in -90..15) {
            try {
                val body = get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&hourly=wind_speed_10m,wind_direction_10m,wind_gusts_10m,weather_code,temperature_2m,precipitation_probability&wind_speed_unit=ms" +
                    "&timezone=Asia%2FSeoul&start_date=$date&end_date=$date")
                val h = JSONObject(body).getJSONObject("hourly")
                val t = h.getJSONArray("time")
                val ws = h.optJSONArray("wind_speed_10m"); val wd = h.optJSONArray("wind_direction_10m"); val wg = h.optJSONArray("wind_gusts_10m")
                for (i in 0 until t.length()) {
                    val ms = isoFmt.parse(t.getString(i))?.time ?: continue
                    wind[ms] = Triple(num(ws, i), num(wd, i), num(wg, i))
                    num(h.optJSONArray("weather_code"), i)?.let { wcodes[ms] = it.toInt() }
                    num(h.optJSONArray("temperature_2m"), i)?.let { omTemp[ms] = it }
                    num(h.optJSONArray("precipitation_probability"), i)?.let { omPop[ms] = it.toInt() }
                }
            } catch (_: Exception) { windNote = "바람 정보를 못 받았어요 (인터넷 확인)" }
        } else windNote = "바람은 3달 전부터 15일 뒤까지만 볼 수 있어요"

        // ---- 파고 · 수온 예측 (바닷가 격자가 비어 있으면 조금씩 바다 쪽으로) ----
        val sea = HashMap<Long, Pair<Double?, Double?>>()
        val seaPd = HashMap<Long, Pair<Double?, Double?>>() // 주기(s), 파향(°)
        var seaNote = ""
        if (diff in -90..7) {
            // 가까운 칸부터, 그래도 파고가 없으면 서해 쪽 열린 바다까지 (최대 약 60km)
            val offsets = listOf(0.0 to 0.0, 0.0 to -0.08, 0.0 to 0.08, -0.08 to 0.0, 0.08 to 0.0, 0.0 to -0.16, -0.08 to -0.08,
                0.0 to -0.25, -0.1 to -0.3, 0.0 to -0.4, -0.15 to -0.45, -0.25 to -0.5, 0.0 to -0.6, -0.3 to -0.65)
            var firstSst: HashMap<Long, Double?>? = null
            for ((dla, dlo) in offsets) {
                try {
                    val body = get("https://marine-api.open-meteo.com/v1/marine?latitude=${lat + dla}&longitude=${lon + dlo}" +
                        "&hourly=wave_height,sea_surface_temperature,wave_period,wave_direction&timezone=Asia%2FSeoul&start_date=$date&end_date=$date")
                    val h = JSONObject(body).getJSONObject("hourly")
                    val t = h.getJSONArray("time")
                    val wv = h.optJSONArray("wave_height"); val st = h.optJSONArray("sea_surface_temperature")
                    var okW = 0; var okS = 0
                    val tmpSea = HashMap<Long, Pair<Double?, Double?>>(); val tmpPd = HashMap<Long, Pair<Double?, Double?>>()
                    for (i in 0 until t.length()) {
                        val ms = isoFmt.parse(t.getString(i))?.time ?: continue
                        val w = num(wv, i); val s = num(st, i)
                        if (w != null) okW++
                        if (s != null) okS++
                        tmpSea[ms] = w to s
                        tmpPd[ms] = num(h.optJSONArray("wave_period"), i) to num(h.optJSONArray("wave_direction"), i)
                    }
                    // 수온은 가장 가까운 칸 값을 쓰고, 파고만 더 먼 바다에서
                    if (okS >= 6 && firstSst == null) firstSst = HashMap(tmpSea.mapValues { it.value.second })
                    if (okW >= 6) {
                        tmpSea.forEach { (k, v) -> sea[k] = v.first to (firstSst?.get(k) ?: v.second) }
                        seaPd.putAll(tmpPd)
                        val km = Math.round(Math.hypot(dla * 111.0, dlo * 111.0 * Math.cos(Math.toRadians(lat))))
                        if (km >= 10) seaNote = "파고: 가까운 바다 칸이 비어서 약 ${km}km 떨어진 앞바다 예보예요"
                        break
                    }
                } catch (_: Exception) {}
            }
            if (sea.isEmpty()) firstSst?.forEach { (k, v) -> sea[k] = null to v }
            if (sea.isEmpty()) seaNote = "이 위치는 파도·수온 예보가 없어요 (육지 안쪽이거나 인터넷 확인)"
            else if (sea.values.none { it.first != null } && seaNote.isBlank()) seaNote = "근처 바다(약 60km 안)에 파고 예보가 없어요"
        } else seaNote = "파도·수온 예보는 3달 전부터 7일 뒤까지만 볼 수 있어요"

        // ---- 기상청 단기예보 (오늘 ~ 3일 뒤): 기온·하늘·비·바람 ----
        var kmaNote = ""
        val kma: Map<Long, Kma> = if (diff in 0..3) {
            try { kmaForecast(lat, lon, keyFmt.format(Date(dayStart + 12 * 3_600_000L))) }
            catch (e: KmaError) { kmaNote = e.message ?: ""; emptyMap() }
            catch (_: Exception) { kmaNote = "기상청 예보를 못 받았어요 (인터넷 확인)"; emptyMap() }
        } else emptyMap()
        if (diff > 3) kmaNote = "기상청 예보(기온·하늘)는 3일 뒤까지만 있어요"
        // 파고: 내 칸이 육지라 기상청 파고가 없으면 가장 가까운 바다 칸의 기상청 파고
        val ymd = keyFmt.format(Date(dayStart + 12 * 3_600_000L))
        var seaWave: Map<Long, Double> = emptyMap()
        if (diff in 0..3 && kma.isNotEmpty() && kma.values.none { (it.wav ?: 0.0) > 0.0 }) {
            seaWave = try { kmaSeaWave(lat, lon, ymd) } catch (_: Exception) { emptyMap() }
            val km = seaKm(lat, lon)
            if (seaWave.isNotEmpty() && km != null && km >= 10) seaNote = "파고: 내 자리 칸이 육지·갯벌이라 약 ${km}km 떨어진 앞바다 기상청 값이에요"
        }
        // 낙뢰: 기상청 초단기예보 (앞으로 6시간)
        val lightning: Set<Long> = if (diff == 0) try { kmaLightning(lat, lon) } catch (_: Exception) { emptySet() } else emptySet()

        // 서버에 저장해 둔 그날 값 (최신 값을 못 받은 칸은 이걸로 채움)
        val saved = loadSaved(lat, lon, ymd)
        val times = (wind.keys + sea.keys + kma.keys + saved.keys).toSortedSet()
        var fromSaved = 0
        val hours = times.map { t ->
            val w = wind[t]; val s = sea[t]; val k = kma[t]; val pd = seaPd[t]; val sv = saved[t]
            val kw = k?.wav?.takeIf { it > 0.0 } ?: seaWave[t]
            // 바람은 기상청 값이 있으면 기상청 우선 (돌풍은 Open-Meteo), 파고는 기상청 → 바다 칸 기상청 → Open-Meteo
            // 기온·비: 기상청 → Open-Meteo(지난 날 포함) → 서버에 저장해 둔 값
            val h = Hour(t, k?.wsd ?: w?.first ?: sv?.wind, k?.vec ?: w?.second ?: sv?.dir, w?.third ?: sv?.gust,
                kw ?: s?.first ?: sv?.wave, s?.second ?: sv?.sst,
                k?.tmp ?: omTemp[t] ?: sv?.temp, k?.sky ?: sv?.sky, k?.pty ?: sv?.pty, k?.pop ?: omPop[t] ?: sv?.pop,
                pd?.first ?: sv?.period, pd?.second ?: sv?.waveDir,
                lightning.any { it in t until t + 3 * 3_600_000L } || sv?.thunder == true, wcodes[t] ?: sv?.wcode)
            if (sv != null && (w == null || (k == null && omTemp[t] == null))) fromSaved++
            h
        }
        saveDay(lat, lon, ymd, hours)
        if (fromSaved > 0 && kmaNote.isBlank()) kmaNote = "일부 시간은 최신 값을 못 받아 저장해 둔 값으로 보여요"

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
        val latest = kmaItems(lat, lon)
        // 오늘: 이미 지난 시간은 최신 예보에 없어서, 어젯밤 23시 발표 예보로 채움 (0시~지금)
        val items = if (ymd == keyFmt.format(Date())) {
            val early = try {
                val (nx, ny) = grid(lat, lon)
                val y = keyFmt.format(Date(System.currentTimeMillis() - 86_400_000L))
                kmaItemsAt(nx, ny, y to "2300")
            } catch (_: Exception) { emptyList() }
            val have = latest.map { it.optString("category") + it.optString("fcstDate") + it.optString("fcstTime") }.toHashSet()
            latest + early.filter { (it.optString("category") + it.optString("fcstDate") + it.optString("fcstTime")) !in have }
        } else latest
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

    /** 단기예보 원본 (모든 날짜, 1시간 저장) */
    private fun kmaItems(lat: Double, lon: Double): List<JSONObject> { val (nx, ny) = grid(lat, lon); return kmaItemsAt(nx, ny) }

    /** 바다 칸 찾기: 내 칸 둘레를 가까운 순서로 (최대 12칸) 파고가 있는 칸 */
    private val seaGrid = HashMap<String, Pair<Int, Int>?>()
    private val seaGridKm = HashMap<String, Int>()
    /** 내 칸에서 파고를 가져온 바다 칸까지 거리(km) */
    fun seaKm(lat: Double, lon: Double): Int? { val (nx, ny) = grid(lat, lon); return seaGridKm["$nx,$ny"] }
    private fun kmaSeaWave(lat: Double, lon: Double, ymd: String): Map<Long, Double> {
        val (nx, ny) = grid(lat, lon)
        val tf = SimpleDateFormat("yyyyMMddHHmm", Locale.US).apply { timeZone = KST }
        fun waves(items: List<JSONObject>) = items.filter { it.optString("fcstDate") == ymd && it.optString("category") == "WAV" }
            .mapNotNull { o -> val v = o.optString("fcstValue").toDoubleOrNull()?.takeIf { it > 0 && it < 30 } ?: return@mapNotNull null
                (tf.parse(o.optString("fcstDate") + o.optString("fcstTime"))?.time ?: return@mapNotNull null) to v }.toMap()
        val ck = "$nx,$ny"
        seaGrid[ck]?.let { g -> return waves(kmaItemsAt(g.first, g.second)) }
        if (seaGrid.containsKey(ck)) return emptyMap()
        // 가까운 12칸 → 그래도 없으면 서쪽(서해 열린 바다) 쪽으로 약 40km 까지
        val near = (-3..3).flatMap { dx -> (-3..3).map { dy -> dx to dy } }.filter { it != 0 to 0 }
            .sortedBy { it.first * it.first + it.second * it.second }.take(12)
        val west = (4..8).flatMap { d -> listOf(-d to 0, -d to -2, -d to 2, -d to -4) } + listOf(0 to -6, 0 to 6, 2 to -6, -2 to -8)
        for ((dx, dy) in (near + west).distinct()) {
            val w = try { waves(kmaItemsAt(nx + dx, ny + dy)) } catch (_: Exception) { continue }
            if (w.isNotEmpty()) { seaGrid[ck] = (nx + dx) to (ny + dy); seaGridKm[ck] = Math.round(Math.hypot(dx * 5.0, dy * 5.0)).toInt(); return w }
        }
        seaGrid[ck] = null
        return emptyMap()
    }

    /** 기상청 초단기예보 낙뢰(LGT): 앞으로 6시간 중 낙뢰가 있는 시각 */
    private fun kmaLightning(lat: Double, lon: Double): Set<Long> {
        val key = BuildConfig.KHOA_KEY
        if (key.isBlank()) return emptySet()
        val (nx, ny) = grid(lat, lon)
        val c = java.util.Calendar.getInstance(KST).apply { timeInMillis = System.currentTimeMillis() - 45 * 60_000L }
        val bd = keyFmt.format(c.time); val bt = String.format(Locale.US, "%02d30", c.get(java.util.Calendar.HOUR_OF_DAY))
        val q = "/1360000/VilageFcstInfoService_2.0/getUltraSrtFcst?serviceKey=${URLEncoder.encode(key, "UTF-8")}" +
            "&pageNo=1&numOfRows=100&dataType=JSON&base_date=$bd&base_time=$bt&nx=$nx&ny=$ny"
        var body: String? = null
        for (scheme in listOf("https", "http")) { try { body = get("$scheme://apis.data.go.kr$q"); break } catch (_: Exception) {} }
        val b = body?.trim()?.takeIf { it.startsWith("{") } ?: return emptySet()
        val arr = JSONObject(b).optJSONObject("response")?.optJSONObject("body")?.optJSONObject("items")?.optJSONArray("item") ?: return emptySet()
        val tf = SimpleDateFormat("yyyyMMddHHmm", Locale.US).apply { timeZone = KST }
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("category") == "LGT" && (it.optString("fcstValue").toDoubleOrNull() ?: 0.0) > 0 }
            .mapNotNull { tf.parse(it.optString("fcstDate") + it.optString("fcstTime"))?.time }.toSet()
    }

    private fun kmaItemsAt(nx: Int, ny: Int, base: Pair<String, String>? = null): List<JSONObject> {
        val key = BuildConfig.KHOA_KEY
        if (key.isBlank()) throw KmaError("기상청 예보: 인증키가 앱에 없어요")
        val (bd, bt) = base ?: baseTime(System.currentTimeMillis())
        val ck = "$nx,$ny,$bd$bt"
        val items = kmaCache[ck]?.takeIf { System.currentTimeMillis() - it.first < KEEP_MS }?.second ?: run {
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
        return items
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
                else "실측 수온을 못 받았어요 (${s.name} 응답 오류)"
                if (note.contains("활용신청")) return null to note
                continue
            }
            try {
                val root = JSONObject(b).let { if (it.has("response")) it.getJSONObject("response") else it }
                val code0 = root.optJSONObject("header")?.optString("resultCode") ?: ""
                if (code0 != "00") {
                    val msg = root.optJSONObject("header")?.optString("resultMsg") ?: ""
                    note = if (code0 == "30" || code0 == "20" || msg.contains("KEY")) "실측 수온: 공공데이터포털에서 '조위관측소 최신 관측데이터' 활용신청이 필요해요"
                        else if (code0.isBlank()) "실측 수온을 못 받았어요 (${s.name} 응답 없음)"
                        else "실측 수온을 못 받았어요 (${s.name} · 코드 $code0${if (msg.isNotBlank()) " $msg" else ""})"
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
    class WeekDay(val dayStart: Long, var tmin: Double?, var tmax: Double?, var pop: Int?, var wind: Double?,
                  var wave: Double?, val sst: Double?, var code: Int?) {
        /** 하늘·기온·비를 어디서 받았는지: "단기" = 기상청 단기예보, "중기" = 기상청 중기예보, "" = Open-Meteo */
        var src: String = ""
        /** 기상청 중기 해상예보 파고 범위 (예: "1.0~2.0") */
        var waveRange: String? = null
        /** 하늘 그림 (시간별과 같은 규칙) */
        /** 뇌우 > 돌풍(20노트↑) > 강풍(10노트↑) > 하늘 */
        var thunder: Boolean = false
        fun skyIcon(): String {
            val c = code
            val w = wind
            return when {
                thunder || (c != null && c >= 95) -> "⚡"
                w != null && w >= KT20 -> "🌀"
                w != null && w >= KT10 -> "💨"
                c == null -> ""
                else -> codeIcon(c)
            }
        }
    }

    private var weekCache: Pair<String, Pair<Long, List<WeekDay>>>? = null
    /** 주간 화면 아래에 보일 출처·안내 */
    @Volatile var weekNote: String = ""

    fun fetchWeek(lat: Double, lon: Double, done: (List<WeekDay>?) -> Unit) {
        val key = String.format(Locale.US, "%.2f,%.2f", lat, lon)
        weekCache?.let { (k, v) -> if (k == key && System.currentTimeMillis() - v.first < KEEP_MS) { done(v.second); return } }
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
        val offsets = listOf(0.0 to 0.0, 0.0 to -0.08, 0.0 to 0.08, -0.08 to 0.0, 0.08 to 0.0, 0.0 to -0.16,
            0.0 to -0.25, -0.1 to -0.3, 0.0 to -0.4, -0.15 to -0.45, -0.25 to -0.5, 0.0 to -0.6)
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
                    if (sstBy.isEmpty()) sums.forEach { (k, p) -> sstBy[k] = p.first / p.second }
                }
                if (waveBy.isNotEmpty()) break
            } catch (_: Exception) {}
        }
        val list = (0 until days.length()).map { i ->
            val ds = days.getString(i)
            WeekDay(dayFmt.parse(ds)!!.time, num(dd.optJSONArray("temperature_2m_min"), i), num(dd.optJSONArray("temperature_2m_max"), i),
                num(dd.optJSONArray("precipitation_probability_max"), i)?.toInt(), num(dd.optJSONArray("wind_speed_10m_max"), i),
                waveBy[ds], sstBy[ds], num(dd.optJSONArray("weather_code"), i)?.toInt())
        }
        // 기상청으로 덮어쓰기: 오늘~3일 뒤 = 단기예보, 그 뒤 = 중기예보 (못 받으면 Open-Meteo 그대로)
        val notes = mutableListOf<String>()
        val short = try { kmaShortWeek(lat, lon) } catch (e: Exception) { notes.add("단기예보 못 받음"); emptyMap() }
        val (mid, midNote) = try { kmaMidWeek(lat, lon) } catch (e: Exception) { emptyMap<String, Wx>() to (e.message ?: "기상청 중기예보를 못 받았어요") }
        list.forEach { wd ->
            val ymd = keyFmt.format(Date(wd.dayStart))
            val s = short[ymd]
            val m = mid[ymd]
            val x = if (s != null && (s.code != null || s.tmax != null)) s.also { wd.src = "단기" } else m?.also { wd.src = "중기" }
            if (x != null) {
                x.code?.let { wd.code = it }
                x.pop?.let { wd.pop = it }
                x.tmin?.let { wd.tmin = it }
                x.tmax?.let { wd.tmax = it }
                x.wind?.let { wd.wind = it }
                x.wave?.let { wd.wave = it }
                wd.waveRange = x.waveRange
            }
            // 단기예보에 파고가 없으면 중기 해상예보 파고라도
            if (wd.waveRange == null && m?.wave != null && s?.wave == null) { wd.wave = m.wave; wd.waveRange = m.waveRange }
            // 그래도 없고 단기예보 기간이면 가장 가까운 바다 칸의 기상청 파고
            if (s != null && s.wave == null && m?.wave == null) {
                try { kmaSeaWave(lat, lon, ymd).values.maxOrNull()?.let { wd.wave = it } } catch (_: Exception) {}
            }
        }
        val sDays = list.count { it.src == "단기" }; val mDays = list.count { it.src == "중기" }
        weekNote = buildString {
            append("하늘·기온·비: ")
            val parts = mutableListOf<String>()
            if (sDays > 0) parts.add("기상청 단기예보 ${sDays}일")
            if (mDays > 0) parts.add("기상청 중기예보 ${mDays}일")
            if (list.size - sDays - mDays > 0) parts.add("Open-Meteo ${list.size - sDays - mDays}일")
            append(parts.joinToString(" · "))
            append("\n파고: 기상청 (없으면 Open-Meteo) · 바람·수온: ")
            append(if (sDays > 0) "단기예보 기간은 기상청, 그 뒤 Open-Meteo" else "Open-Meteo")
            if (mDays == 0 && midNote.isNotBlank()) append("\n⚠ ").append(midNote)
            if (notes.isNotEmpty()) append("\n⚠ ").append(notes.joinToString(" · "))
        }
        return list
    }

    // ---------------- 기상청 주간: 단기예보(날마다 묶음) + 중기예보 ----------------
    private class Wx { var code: Int? = null; var pop: Int? = null; var tmin: Double? = null; var tmax: Double? = null
        var wind: Double? = null; var wave: Double? = null; var waveRange: String? = null }

    /** 단기예보를 날짜별로: 낮(6~21시) 비·눈 있으면 비/눈, 아니면 정오 하늘 · 최저/최고 · 비 확률 최대 · 바람 최대 · 파고 최대 */
    private fun kmaShortWeek(lat: Double, lon: Double): Map<String, Wx> {
        val out = HashMap<String, Wx>()
        val byDay = kmaItems(lat, lon).groupBy { it.optString("fcstDate") }
        byDay.forEach { (ymd, its) ->
            val w = Wx()
            var sky: Int? = null; var skyDist = 99; var rain = 0
            var tmn: Double? = null; var tmx: Double? = null; var lo: Double? = null; var hi: Double? = null
            its.forEach { o ->
                val h = o.optString("fcstTime").take(2).toIntOrNull() ?: return@forEach
                val v = o.optString("fcstValue")
                when (o.optString("category")) {
                    "SKY" -> v.toIntOrNull()?.let { if (Math.abs(h - 12) < skyDist) { skyDist = Math.abs(h - 12); sky = it } }
                    "PTY" -> v.toIntOrNull()?.let { p -> if (p > 0 && h in 6..21) rain = if (p == 3 || p == 7) maxOf(rain, 2) else maxOf(rain, 1) }
                    "POP" -> v.toIntOrNull()?.let { w.pop = maxOf(w.pop ?: 0, it) }
                    "TMN" -> tmn = v.toDoubleOrNull()
                    "TMX" -> tmx = v.toDoubleOrNull()
                    "TMP" -> v.toDoubleOrNull()?.let { t -> lo = minOf(lo ?: t, t); hi = maxOf(hi ?: t, t) }
                    "WSD" -> v.toDoubleOrNull()?.let { w.wind = maxOf(w.wind ?: 0.0, it) }
                    "WAV" -> v.toDoubleOrNull()?.takeIf { it > 0 && it < 30 }?.let { w.wave = maxOf(w.wave ?: 0.0, it) }
                }
            }
            w.code = when { rain == 2 -> 71; rain == 1 -> 61; sky == 1 -> 0; sky == 3 -> 2; sky == 4 -> 3; else -> null }
            w.tmin = tmn ?: lo; w.tmax = tmx ?: hi
            out[ymd] = w
        }
        return out
    }

    private class Reg(val id: String, val lat: Double, val lon: Double)
    /** 중기 육상예보 구역 */
    private val LAND = listOf(Reg("11B00000", 37.45, 126.9), Reg("11D10000", 37.75, 127.9), Reg("11D20000", 37.75, 128.9),
        Reg("11C20000", 36.5, 126.8), Reg("11C10000", 36.7, 127.7), Reg("11F10000", 35.7, 127.0), Reg("11F20000", 34.9, 126.9),
        Reg("11H10000", 36.3, 128.8), Reg("11H20000", 35.3, 128.4), Reg("11G00000", 33.4, 126.5))
    /** 중기 기온 (도시) */
    private val CITY = listOf(Reg("11B10101", 37.57, 126.98), Reg("11B20201", 37.46, 126.71), Reg("11B20601", 37.26, 127.03),
        Reg("11D10301", 37.88, 127.73), Reg("11D20501", 37.75, 128.88), Reg("11C10301", 36.64, 127.49), Reg("11C20401", 36.35, 127.38),
        Reg("11C20101", 36.78, 126.45), Reg("11F10201", 35.82, 127.15), Reg("11F20501", 35.16, 126.85), Reg("21F20801", 34.81, 126.39),
        Reg("11F20401", 34.76, 127.66), Reg("11H10701", 35.87, 128.6), Reg("11H10501", 36.57, 128.73), Reg("11H20201", 35.18, 129.08),
        Reg("11H20101", 35.54, 129.31), Reg("11H20301", 35.23, 128.68), Reg("11G00201", 33.5, 126.53))
    /** 중기 해상예보 구역 */
    private val SEA = listOf(Reg("12A20000", 36.8, 125.8), Reg("12A30000", 35.2, 125.6), Reg("12B10000", 34.2, 126.6),
        Reg("12B20000", 34.6, 128.6), Reg("12C10000", 36.0, 129.7), Reg("12C20000", 37.6, 129.3), Reg("12C30000", 38.4, 128.8),
        Reg("12B10500", 33.3, 126.5))
    private fun nearest(list: List<Reg>, lat: Double, lon: Double): Reg =
        list.minByOrNull { val dy = it.lat - lat; val dx = (it.lon - lon) * Math.cos(Math.toRadians(lat)); dy * dy + dx * dx }!!

    /** 기상청 중기예보 하나 받기 (item 한 개) */
    private fun midItem(op: String, regId: String, tmFc: String): JSONObject {
        val key = BuildConfig.KHOA_KEY
        if (key.isBlank()) throw KmaError("기상청 중기예보: 인증키가 앱에 없어요")
        val q = "/1360000/MidFcstInfoService/$op?serviceKey=${URLEncoder.encode(key, "UTF-8")}" +
            "&pageNo=1&numOfRows=10&dataType=JSON&regId=$regId&tmFc=$tmFc"
        var body: String? = null
        for (scheme in listOf("https", "http")) {
            try { body = get("$scheme://apis.data.go.kr$q"); break } catch (_: Exception) {}
        }
        val b = body?.trim() ?: throw KmaError("기상청 중기예보를 못 받았어요 (인터넷 확인)")
        if (b.startsWith("<")) {
            if (b.contains("SERVICE_KEY_IS_NOT_REGISTERED") || b.contains("SERVICE ERROR") || b.contains("ACCESS DENIED") || b.contains("Unauthorized"))
                throw KmaError("기상청 중기예보: 공공데이터포털 '기상청_중기예보 조회서비스' 승인을 확인해 주세요 (승인 뒤 1~2시간 걸릴 수 있어요)")
            throw KmaError("기상청 중기예보를 못 받았어요")
        }
        val root = JSONObject(b).let { if (it.has("response")) it.getJSONObject("response") else it }
        val code0 = root.optJSONObject("header")?.optString("resultCode") ?: ""
        if (code0 != "00") throw KmaError("기상청 중기예보를 못 받았어요 ($code0 ${root.optJSONObject("header")?.optString("resultMsg") ?: ""})")
        val items = root.optJSONObject("body")?.optJSONObject("items")
        return items?.optJSONArray("item")?.optJSONObject(0) ?: items?.optJSONObject("item") ?: throw KmaError("기상청 중기예보 내용이 비어 있어요")
    }

    private fun wfCode(s: String?): Int? = when {
        s.isNullOrBlank() -> null
        s.contains("눈") && !s.contains("비") -> 71
        s.contains("비") || s.contains("소나기") -> 61
        s.contains("흐림") -> 3
        s.contains("구름") -> 2
        s.contains("맑음") -> 0
        else -> null
    }
    private fun worse(a: Int?, b: Int?): Int? {
        fun rank(c: Int?) = when (c) { null -> -1; 0 -> 0; 2 -> 1; 3 -> 2; 61 -> 3; 71 -> 4; else -> 1 }
        return if (rank(a) >= rank(b)) a else b
    }
    private fun dbl(o: JSONObject, k: String): Double? = if (o.has(k) && !o.isNull(k)) o.optString(k).toDoubleOrNull() else null

    /** 중기예보: 발표일 + N일 (N = 3~10) → 날짜별. 두 번째 값 = 실패 이유 */
    private fun kmaMidWeek(lat: Double, lon: Double): Pair<Map<String, Wx>, String> {
        // 발표: 06시·18시 (발표 30분 뒤부터)
        val c = java.util.Calendar.getInstance(KST).apply { timeInMillis = System.currentTimeMillis() - 30 * 60_000L }
        val h = c.get(java.util.Calendar.HOUR_OF_DAY)
        val hh = when { h >= 18 -> "1800"; h >= 6 -> "0600"; else -> { c.add(java.util.Calendar.DAY_OF_MONTH, -1); "1800" } }
        val baseDay = keyFmt.format(c.time)
        val tmFc = baseDay + hh
        val base0 = keyFmt.parse(baseDay)!!.time
        val out = HashMap<String, Wx>()
        fun day(n: Int): Wx = out.getOrPut(keyFmt.format(Date(base0 + n * 86_400_000L + 3_600_000L))) { Wx() }
        val errs = mutableListOf<String>()
        try {
            val land = midItem("getMidLandFcst", nearest(LAND, lat, lon).id, tmFc)
            for (n in 3..10) {
                val am = land.optString("wf${n}Am", ""); val pm = land.optString("wf${n}Pm", ""); val one = land.optString("wf$n", "")
                val code = if (one.isNotBlank()) wfCode(one) else worse(wfCode(am), wfCode(pm))
                val pop = listOf("rnSt${n}Am", "rnSt${n}Pm", "rnSt$n").mapNotNull { dbl(land, it)?.toInt() }.maxOrNull()
                if (code != null || pop != null) { val w = day(n); w.code = code; w.pop = pop }
            }
        } catch (e: Exception) { errs.add(e.message ?: "육상예보 실패") }
        try {
            val ta = midItem("getMidTa", nearest(CITY, lat, lon).id, tmFc)
            for (n in 3..10) {
                val lo = dbl(ta, "taMin$n"); val hi = dbl(ta, "taMax$n")
                if (lo != null || hi != null) { val w = day(n); w.tmin = lo; w.tmax = hi }
            }
        } catch (e: Exception) { errs.add(e.message ?: "기온 실패") }
        try {
            val sea = midItem("getMidSeaFcst", nearest(SEA, lat, lon).id, tmFc)
            for (n in 3..10) {
                val lows = listOf("wh${n}AAm", "wh${n}APm", "wh${n}A").mapNotNull { dbl(sea, it) }
                val highs = listOf("wh${n}BAm", "wh${n}BPm", "wh${n}B").mapNotNull { dbl(sea, it) }
                if (highs.isNotEmpty()) {
                    val w = day(n); w.wave = highs.max()
                    w.waveRange = String.format(Locale.KOREA, "%.1f~%.1f", lows.minOrNull() ?: highs.min(), highs.max())
                }
            }
        } catch (e: Exception) { errs.add(e.message ?: "해상예보 실패") }
        // 기온만 있고 하늘이 없는 날은 빼지 않음 (있는 값만 덮어씀)
        return out.filterValues { it.code != null || it.tmax != null || it.wave != null } to errs.distinct().joinToString(" · ")
    }

    /** 풍속 등급: 0 약함, 1 주의, 2 강함 */
    fun windLevel(ms: Double): Int = when { ms < 5 -> 0; ms < 9 -> 1; else -> 2 }
    fun windLevelName(l: Int) = when (l) { 0 -> "약함"; 1 -> "주의"; else -> "강함" }
}
