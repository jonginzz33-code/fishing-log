package com.fishlog.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * v3.9.4 (176): 🌙 달 모양 · 월출 · 월몰 (인터넷 없이 계산 · SunCalc 방식, 오차 몇 분)
 */
object Moon {
    private const val RAD = PI / 180
    private const val E = RAD * 23.4397
    private fun toDays(ms: Long) = ms / 86_400_000.0 - 0.5 + 2440588 - 2451545
    private fun ra(l: Double, b: Double) = atan2(sin(l) * cos(E) - tan(b) * sin(E), cos(l))
    private fun dec(l: Double, b: Double) = asin(sin(b) * cos(E) + cos(b) * sin(E) * sin(l))
    private fun sidereal(d: Double, lw: Double) = RAD * (280.16 + 360.9856235 * d) - lw
    private fun refraction(h0: Double): Double { val h = if (h0 < 0) 0.0 else h0; return 0.0002967 / tan(h + 0.00312536 / (h + 0.08901179)) }

    private class C(val ra: Double, val dec: Double, val dist: Double)
    private fun sun(d: Double): C {
        val m = RAD * (357.5291 + 0.98560028 * d)
        val c = RAD * (1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m))
        val l = m + c + RAD * 102.9372 + PI
        return C(ra(l, 0.0), dec(l, 0.0), 149598000.0)
    }
    private fun moon(d: Double): C {
        val L = RAD * (218.316 + 13.176396 * d)
        val M = RAD * (134.963 + 13.064993 * d)
        val F = RAD * (93.272 + 13.229350 * d)
        val l = L + RAD * 6.289 * sin(M)
        val b = RAD * 5.128 * sin(F)
        return C(ra(l, b), dec(l, b), 385001 - 20905 * cos(M))
    }
    private fun altitude(ms: Long, lat: Double, lon: Double): Double {
        val lw = RAD * -lon; val phi = RAD * lat; val d = toDays(ms)
        val c = moon(d)
        val h = sidereal(d, lw) - c.ra
        val a = asin(sin(phi) * sin(c.dec) + cos(phi) * cos(c.dec) * cos(h))
        return a + refraction(a)
    }

    /** 밝은 부분 비율(0~1) · 위상(0 = 그믐, 0.25 = 상현, 0.5 = 보름, 0.75 = 하현) */
    class Phase(val fraction: Double, val phase: Double) {
        val emoji: String get() = listOf("🌑", "🌒", "🌓", "🌔", "🌕", "🌖", "🌗", "🌘")[((phase * 8 + 0.5).toInt()) % 8]
        val name: String get() = when (((phase * 8 + 0.5).toInt()) % 8) {
            0 -> "그믐(새달)"; 1 -> "초승달"; 2 -> "상현달"; 3 -> "차오르는 달"; 4 -> "보름달"; 5 -> "기우는 달"; 6 -> "하현달"; else -> "그믐달"
        }
    }

    fun phase(ms: Long): Phase {
        val d = toDays(ms)
        val s = sun(d); val m = moon(d)
        val phi = acos(sin(s.dec) * sin(m.dec) + cos(s.dec) * cos(m.dec) * cos(s.ra - m.ra))
        val inc = atan2(s.dist * sin(phi), m.dist - s.dist * cos(phi))
        val angle = atan2(cos(s.dec) * sin(s.ra - m.ra), sin(s.dec) * cos(m.dec) - cos(s.dec) * sin(m.dec) * cos(s.ra - m.ra))
        return Phase((1 + cos(inc)) / 2, 0.5 + 0.5 * inc * (if (angle < 0) -1 else 1) / PI)
    }

    /** 그날(dayStart = 한국 0시) 월출 · 월몰 (없으면 null) */
    fun times(dayStart: Long, lat: Double, lon: Double): Pair<Long?, Long?> {
        val hc = 0.133 * RAD
        var h0 = altitude(dayStart, lat, lon) - hc
        var rise: Double? = null; var set: Double? = null
        var i = 1
        while (i <= 24) {
            val h1 = altitude(dayStart + i * 3_600_000L, lat, lon) - hc
            val h2 = altitude(dayStart + (i + 1) * 3_600_000L, lat, lon) - hc
            val a = (h0 + h2) / 2 - h1; val b = (h2 - h0) / 2
            val xe = -b / (2 * a); val ye = (a * xe + b) * xe + h1
            val d = b * b - 4 * a * h1
            var roots = 0; var x1 = 0.0; var x2 = 0.0
            if (d >= 0) {
                val dx = sqrt(d) / (abs(a) * 2)
                x1 = xe - dx; x2 = xe + dx
                if (abs(x1) <= 1) roots++
                if (abs(x2) <= 1) roots++
                if (x1 < -1) x1 = x2
            }
            if (roots == 1) { if (h0 < 0) rise = i + x1 else set = i + x1 }
            else if (roots == 2) { rise = i + (if (ye < 0) x2 else x1); set = i + (if (ye < 0) x1 else x2) }
            if (rise != null && set != null) break
            h0 = h2; i += 2
        }
        fun ms(h: Double?) = h?.let { dayStart + (it * 3_600_000L).toLong() }?.takeIf { it < dayStart + 86_400_000L }
        return ms(rise) to ms(set)
    }

    private val hm = SimpleDateFormat("HH:mm", Locale.KOREA)
    /** "🌔 차오르는 달 (밝기 78%) · 월출 13:20 · 월몰 01:05" */
    fun line(dayStart: Long, lat: Double, lon: Double): String {
        val p = phase(dayStart + 21 * 3_600_000L)
        val (r, s) = times(dayStart, lat, lon)
        return "${p.emoji} ${p.name} (밝기 ${(p.fraction * 100).toInt()}%) · 월출 ${r?.let { hm.format(Date(it)) } ?: "없음"} · 월몰 ${s?.let { hm.format(Date(it)) } ?: "없음"}"
    }
}

/** 공용: 받기 · 메인 스레드 */
private object Net {
    val main = Handler(Looper.getMainLooper())
    fun get(url: String, timeout: Int = 15_000): String {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299) throw Exception("HTTP $code ${body.take(160)}")
        return body
    }
    fun key(): String = java.net.URLEncoder.encode(BuildConfig.KHOA_KEY, "UTF-8")
}

/**
 * v3.9.4 (178): 🌫 해무 · 안개 — Open-Meteo 시간별 시정(m) · 1km 아래면 주의
 */
object Fog {
    class Day(val minVis: Double?, val badHours: List<Int>)
    private val cache = HashMap<String, Pair<Long, Day>>()
    private val dfmt = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)

    fun fetch(lat: Double, lon: Double, dayStart: Long, done: (Day?) -> Unit) {
        val date = dfmt.format(Date(dayStart))
        val k = "${Math.round(lat * 10)}_${Math.round(lon * 10)}_$date"
        cache[k]?.let { (at, d) -> if (System.currentTimeMillis() - at < 3 * 3_600_000L) return done(d) }
        Thread {
            val r = try {
                val body = Net.get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&hourly=visibility&timezone=Asia%2FSeoul&start_date=$date&end_date=$date")
                val v = JSONObject(body).optJSONObject("hourly")?.optJSONArray("visibility")
                if (v == null) null else {
                    val vals = (0 until v.length()).map { if (v.isNull(it)) null else v.optDouble(it) }
                    Day(vals.filterNotNull().minOrNull(), vals.mapIndexedNotNull { h, x -> if (x != null && x < 1000) h else null })
                }
            } catch (_: Exception) { null }
            Net.main.post { if (r != null) cache[k] = System.currentTimeMillis() to r; done(r) }
        }.start()
    }

    /** "🌫 안개 주의 05~08시 (시정 최저 400m)" · 괜찮으면 "🌫 시정 좋음 (최저 8km)" */
    fun line(d: Day): String {
        val mv = d.minVis ?: return ""
        val v = if (mv >= 1000) String.format(Locale.KOREA, "%.0fkm", mv / 1000) else "${mv.toInt()}m"
        if (d.badHours.isEmpty()) return "🌫 시정 좋음 (최저 $v)"
        val h = d.badHours
        return "🌫 해무 · 안개 주의 ${h.first()}~${h.last() + 1}시 (시정 최저 $v)"
    }
}

/**
 * v3.9.4 (177): ⚠ 기상특보 (기상청_기상특보 조회서비스 · 특보 현황) — 내 위치 바다 구역에 풍랑 · 강풍 등이 있으면
 */
object Warn {
    @Volatile private var at = 0L
    @Volatile private var text: String? = null
    @Volatile var lastErr = ""

    /** 위치 → 바다 예보 구역 이름 (특보 글에 나오는 이름) */
    fun seaArea(lat: Double, lon: Double): String = when {
        lat < 33.9 -> "제주도"
        lon >= 129.0 && lat >= 35.3 -> when { lat < 36.6 -> "동해남부"; lat < 37.9 -> "동해중부"; else -> "동해북부" }
        lat >= 35.9 && lon < 127.0 -> "서해중부"
        lat >= 34.9 && lon < 126.9 -> "서해남부"
        lon < 127.6 -> "남해서부"
        else -> "남해동부"
    }

    private fun load(): String? {
        if (System.currentTimeMillis() - at < 30 * 60_000L) return text
        val body = Net.get("https://apis.data.go.kr/1360000/WthrWrnInfoService/getPwnStatus?serviceKey=${Net.key()}&pageNo=1&numOfRows=10&dataType=JSON")
        val items = JSONObject(body).optJSONObject("response")?.optJSONObject("body")?.optJSONObject("items")?.optJSONArray("item")
        val t = (0 until (items?.length() ?: 0)).joinToString("\n") { items!!.optJSONObject(it)?.optString("t6") ?: "" }
        text = t; at = System.currentTimeMillis()
        return t
    }

    /** 내 구역 특보 한 줄 (없으면 "") · 실패하면 null */
    fun forPlace(lat: Double, lon: Double, done: (String?) -> Unit) {
        val area = seaArea(lat, lon)
        Thread {
            val r = try {
                val t = load() ?: ""
                // "o 풍랑주의보 : 서해중부먼바다, …" 처럼 줄마다 특보 이름 : 구역들
                t.split("\n", "\r").map { it.trim().removePrefix("o").trim() }.filter { it.contains(":") }
                    .filter { l -> l.substringAfter(":").replace(" ", "").contains(area) }
                    .map { it.substringBefore(":").trim() }.filter { it.isNotBlank() }.distinct()
                    .joinToString(" · ")
            } catch (e: Exception) { lastErr = e.message ?: ""; null }
            Net.main.post { done(r) }
        }.start()
    }
}

/**
 * v3.9.4 (174 · 175): 국립해양조사원 갯벌체험지수 · 바다낚시지수
 *  - 공공데이터포털 활용신청이 승인되면 바로 나오고, 안 되면 '자료 준비 중'
 *  - 주소가 바뀌면 관리자가 🛠 해루질 관리에서 바꿀 수 있게 (서버 haeruMeta/khoa: fishUrl · mudUrl)
 */
object Idx {
    class Item(val name: String, val lat: Double, val lon: Double, val ymd: String, val index: String, val score: String,
               val extra: String)

    const val FISH_DEF = "https://apis.data.go.kr/1192136/fcstFishingv2/GetFcstFishingApiServicev2"
    const val MUD_DEF = "https://apis.data.go.kr/1192136/fcstMudflatv2/GetFcstMudflatApiServicev2"
    private fun prefs(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)
    fun fishUrl(c: Context) = prefs(c).getString("idx_fish_url", "")?.ifBlank { FISH_DEF } ?: FISH_DEF
    fun mudUrl(c: Context) = prefs(c).getString("idx_mud_url", "")?.ifBlank { MUD_DEF } ?: MUD_DEF
    fun saveUrls(c: Context, fish: String, mud: String) = prefs(c).edit().putString("idx_fish_url", fish.trim()).putString("idx_mud_url", mud.trim()).apply()

    private val cache = HashMap<String, Pair<Long, List<Item>>>()
    @Volatile var lastErr = ""

    /** 응답 안에서 항목 목록 찾기 (모양이 조금 달라도) */
    private fun findItems(o: Any?): JSONArray? {
        when (o) {
            is JSONArray -> { if (o.length() > 0 && o.opt(0) is JSONObject && (o.getJSONObject(0).has("lat") || o.getJSONObject(0).has("la"))) return o
                for (i in 0 until o.length()) { val r = findItems(o.opt(i)); if (r != null) return r } }
            is JSONObject -> { val it0 = o.keys(); while (it0.hasNext()) { val r = findItems(o.opt(it0.next())); if (r != null) return r } }
        }
        return null
    }
    private fun pick(o: JSONObject, vararg keys: String): String {
        for (k in keys) if (o.has(k) && !o.isNull(k)) return o.optString(k)
        return ""
    }
    private fun keyLike(o: JSONObject, part: String): String {
        val ks = o.keys(); while (ks.hasNext()) { val k = ks.next(); if (k.contains(part, true) && !o.isNull(k)) return o.optString(k) }
        return ""
    }

    private fun parse(body: String, mud: Boolean): List<Item> {
        val arr = findItems(try { JSONObject(body) } catch (_: Exception) { JSONArray(body) }) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val lat = pick(o, "lat", "la").toDoubleOrNull() ?: return@mapNotNull null
            val lon = pick(o, "lot", "lon", "lo").toDoubleOrNull() ?: return@mapNotNull null
            val name = pick(o, "seafsPstnNm", "mdftExprnRgnNm", "mdftExpcnRgnNm").ifBlank { keyLike(o, "Nm") }
            val ymd = pick(o, "predcYmd").ifBlank { keyLike(o, "Ymd") }.replace("-", "")
            val index = pick(o, "totalIndex").ifBlank { keyLike(o, "Index") }
            val score = pick(o, "lastScr").ifBlank { keyLike(o, "Scr") }
            val extra = if (mud) {
                val b = pick(o, "mdftExprnBgngTm").ifBlank { keyLike(o, "Bgng") }
                val e = pick(o, "mdftExprnEndTm").ifBlank { keyLike(o, "End") }
                if (b.isNotBlank()) "체험 ${b.take(5)}~${e.take(5)}" else ""
            } else listOf(pick(o, "predcNoonSeCd"), pick(o, "seafsTgfshNm")).filter { it.isNotBlank() }.joinToString(" ")
            Item(name, lat, lon, ymd, index, score, extra)
        }
    }

    private val ymdFmt = SimpleDateFormat("yyyyMMdd", Locale.KOREA)

    /** 그날 그 위치에서 가장 가까운(50km 안) 지수들 · 실패 null */
    fun near(c: Context, mud: Boolean, dayStart: Long, lat: Double, lon: Double, done: (List<Item>?) -> Unit) {
        val ymd = ymdFmt.format(Date(dayStart))
        val base = if (mud) mudUrl(c) else fishUrl(c)
        val k = "${if (mud) "m" else "f"}_$ymd"
        fun pickNear(list: List<Item>): List<Item> {
            val same = list.filter { it.ymd.isBlank() || it.ymd == ymd }
            fun d(it: Item) = (it.lat - lat) * (it.lat - lat) + ((it.lon - lon) * 0.82) * ((it.lon - lon) * 0.82)
            val best = same.minByOrNull { d(it) } ?: return emptyList()
            if (d(best) > 0.45 * 0.45) return emptyList()
            return same.filter { it.name == best.name }.take(6)
        }
        cache[k]?.let { (at, l) -> if (System.currentTimeMillis() - at < 6 * 3_600_000L) return done(pickNear(l)) }
        Thread {
            val r = try {
                val all = ArrayList<Item>()
                val gubuns = if (mud) listOf("") else listOf("갯바위", "선상")
                for (g in gubuns) {
                    val url = "$base?serviceKey=${Net.key()}&type=json&pageNo=1&numOfRows=500&reqDate=$ymd" +
                        (if (g.isNotBlank()) "&gubun=" + java.net.URLEncoder.encode(g, "UTF-8") else "")
                    all.addAll(parse(Net.get(url), mud))
                }
                lastErr = ""
                all
            } catch (e: Exception) { lastErr = e.message ?: ""; null }
            Net.main.post {
                if (r != null) cache[k] = System.currentTimeMillis() to r
                done(r?.let { pickNear(it) })
            }
        }.start()
    }

    /** 관리자: 연결 확인 (응답 앞부분 그대로) */
    fun test(c: Context, done: (String) -> Unit) {
        val ymd = ymdFmt.format(Date())
        Thread {
            val out = listOf(false to fishUrl(c), true to mudUrl(c)).joinToString("\n\n") { (mud, base) ->
                val label = if (mud) "갯벌체험지수" else "바다낚시지수"
                try {
                    val body = Net.get("$base?serviceKey=${Net.key()}&type=json&pageNo=1&numOfRows=5&reqDate=$ymd" + if (!mud) "&gubun=" + java.net.URLEncoder.encode("갯바위", "UTF-8") else "")
                    val n = parse(body, mud).size
                    if (n > 0) "✅ $label: ${n}곳 받음" else "❌ $label: 응답이 이상해요\n${body.take(300)}"
                } catch (e: Exception) { "❌ $label: ${e.message?.take(300)}" }
            }
            Net.main.post { done(out) }
        }.start()
    }

    /** 지수 글 색 */
    fun color(index: String): Int = android.graphics.Color.parseColor(when {
        index.contains("매우좋음") -> "#5FD39A"; index.contains("좋음") -> "#9BE07A"; index.contains("보통") -> "#F2C14E"
        index.contains("매우나쁨") -> "#E8505B"; index.contains("나쁨") -> "#F28C38"; else -> "#9FB4C2"
    })
}
