package com.fishlog.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * v6.46 (317): 🗺 권역 낚시환경 — 권역 16곳 · 오늘 ~ 7일 뒤
 *  - 🌤 출조환경 = 바람 · 돌풍 · 파고 · 비 (Open-Meteo 여러 곳 한 번에) · 오늘 풍랑 특보면 매우나쁨
 *  - 🎣 예상조과 = 물세기(달 나이로 짐작) · 수온 · 이틀 새 수온 변화 · 우리 게시판 최근 7일 조과 · 어종별 기준 (대충 계산)
 *  - 받기는 두 번(바람 · 파도)뿐이라 서버 공유 없이 폰에 3시간 기억
 *  - 지도 = 진짜 해안선 (assets/korea_map.json · Natural Earth 10m → 메르카토르)
 */
object Region {
    class Reg(val id: String, val name: String, val sub: String, val port: String, val lat: Double, val lon: Double,
              /** 지도 이름표 쪽: -1 왼쪽 · 1 오른쪽 · 0 아래 */
              val side: Int)

    val LIST = listOf(
        Reg("ic", "인천경기", "인천 · 시흥 · 화성", "인천 앞바다", 37.35, 126.45, -1),
        Reg("ta", "태안", "태안 · 서산 · 당진", "안흥 앞바다", 36.67, 126.0, -1),
        Reg("br", "보령", "보령 · 홍성 · 서천", "대천 앞바다", 36.3, 126.38, -1),
        Reg("gs", "군산", "군산 · 부안 · 고창", "비응 앞바다", 35.88, 126.38, -1),
        Reg("mp", "목포", "목포 · 무안 · 신안", "목포 앞바다", 34.8, 126.15, -1),
        Reg("ns", "남해서부", "완도 · 진도 · 해남", "완도 앞바다", 34.25, 126.8, -1),
        Reg("ys", "여수", "여수 · 고흥 · 남해", "여수 앞바다", 34.6, 127.75, 0),
        Reg("ty", "통영", "통영 · 고성", "통영 앞바다", 34.75, 128.42, 0),
        Reg("gj", "거제", "거제", "장승포 앞바다", 34.85, 128.78, 1),
        Reg("bs", "부산창원", "부산 · 기장 · 창원", "부산 앞바다", 35.05, 129.1, 1),
        Reg("us", "울산", "울산 · 기장 북부", "방어진 앞바다", 35.48, 129.48, 1),
        Reg("ph", "포항영덕", "포항 · 영덕", "구룡포 앞바다", 36.1, 129.5, 1),
        Reg("uj", "울진후포", "울진 · 후포", "후포 앞바다", 36.7, 129.5, 1),
        Reg("sc", "삼척강릉", "삼척 · 동해 · 강릉", "묵호 앞바다", 37.55, 129.18, 1),
        Reg("yy", "양양속초", "양양 · 속초 · 고성", "속초 앞바다", 38.2, 128.65, 1),
        Reg("jj", "제주", "제주 · 서귀포", "제주 앞바다", 33.58, 126.5, 1))

    val NAMES = listOf("매우나쁨", "나쁨", "보통", "좋음", "매우좋음")
    val COLORS = intArrayOf(Color.parseColor("#E5484D"), Color.parseColor("#F08A4B"), Color.parseColor("#E8C14A"),
        Color.parseColor("#7FD6A0"), Color.parseColor("#2FBF8F"))
    val SPECIES = listOf("전체", "주꾸미", "갑오징어", "우럭", "광어", "참돔", "삼치")
    /** 어종별 좋은 수온(℃) · 좋은 물세기(%) — 대충 기준 */
    private class Pref(val tLo: Double, val tHi: Double, val fLo: Int, val fHi: Int)
    private val PREF = mapOf(
        "전체" to Pref(10.0, 24.0, 40, 85), "주꾸미" to Pref(15.0, 22.0, 30, 70), "갑오징어" to Pref(14.0, 20.0, 30, 70),
        "우럭" to Pref(10.0, 18.0, 40, 90), "광어" to Pref(12.0, 20.0, 40, 85), "참돔" to Pref(15.0, 23.0, 45, 85),
        "삼치" to Pref(14.0, 22.0, 40, 90))

    private val KST = TimeZone.getTimeZone("Asia/Seoul")
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = KST }
    private val main = Handler(Looper.getMainLooper())

    /** 한 권역 · 한 날 */
    class Cell(val wind: Double?, val gust: Double?, val dir: Double?, val wave: Double?, val pop: Int?, val sst: Double?, val sstPrev: Double?,
               val code: Int?)
    class Score(val env: Int, val catch: Int, val envWhy: String, val catchWhy: String, val pct: Int, val trips: Int, val fish: Int)

    /** 받은 자료: 날짜(0시) → 권역 id → Cell */
    @Volatile private var data: Map<Long, Map<String, Cell>>? = null
    @Volatile private var dataAt = 0L
    @Volatile var note = ""
    /** 오늘 풍랑 특보 권역 (id → 특보 이름) */
    @Volatile private var warns: Map<String, String> = emptyMap()

    private fun dayStart(t: Long): Long {
        val c = java.util.Calendar.getInstance(KST).apply { timeInMillis = t
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
        return c.timeInMillis
    }

    private fun get(url: String): String {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8_000; c.readTimeout = 10_000
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    private fun num(a: JSONArray?, i: Int): Double? = if (a == null || i >= a.length() || a.isNull(i)) null else a.optDouble(i).takeIf { !it.isNaN() }

    private fun file(c: Context) = java.io.File(c.filesDir, "region.json")

    /** 바람 · 파도 받기 (3시간 기억 · 폰 파일) */
    fun load(c: Context, force: Boolean, done: (Map<Long, Map<String, Cell>>?) -> Unit) {
        val now = System.currentTimeMillis()
        if (!force) {
            data?.let { if (now - dataAt < 3 * 3_600_000L) { done(it); return } }
            try {
                val o = JSONObject(file(c).readText())
                if (now - o.getLong("at") < 3 * 3_600_000L) { data = parseSaved(o); dataAt = o.getLong("at"); note = o.optString("note"); done(data); return }
            } catch (_: Exception) {}
        }
        val app = c.applicationContext
        Thread {
            val wind = HashMap<String, HashMap<Long, DoubleArray>>()   // id → 시각 → [바람, 돌풍, 방향, 비확률, 코드]
            val sea = HashMap<String, HashMap<Long, DoubleArray>>()    // id → 시각 → [파고, 수온]
            val las = LIST.joinToString(",") { String.format(Locale.US, "%.3f", it.lat) }
            val los = LIST.joinToString(",") { String.format(Locale.US, "%.3f", it.lon) }
            var err = ""
            fun arr(body: String): JSONArray { val b = body.trim(); return if (b.startsWith("[")) JSONArray(b) else JSONArray().put(JSONObject(b)) }
            val t1 = Thread {
                try {
                    val url = "https://api.open-meteo.com/v1/forecast?latitude=$las&longitude=$los" +
                        "&hourly=wind_speed_10m,wind_gusts_10m,wind_direction_10m,precipitation_probability,weather_code&wind_speed_unit=ms" +
                        "&timezone=Asia%2FSeoul&forecast_days=8"
                    val a = arr(try { get(url) } catch (_: Exception) { Thread.sleep(1500L); get(url) })
                    for (k in 0 until minOf(a.length(), LIST.size)) {
                        val h = a.getJSONObject(k).getJSONObject("hourly"); val t = h.getJSONArray("time")
                        val m = HashMap<Long, DoubleArray>()
                        for (i in 0 until t.length()) {
                            val ms = iso.parse(t.getString(i))?.time ?: continue
                            m[ms] = doubleArrayOf(num(h.optJSONArray("wind_speed_10m"), i) ?: Double.NaN, num(h.optJSONArray("wind_gusts_10m"), i) ?: Double.NaN,
                                num(h.optJSONArray("wind_direction_10m"), i) ?: Double.NaN, num(h.optJSONArray("precipitation_probability"), i) ?: Double.NaN,
                                num(h.optJSONArray("weather_code"), i) ?: Double.NaN)
                        }
                        synchronized(wind) { wind[LIST[k].id] = m }
                    }
                } catch (_: Exception) { err = "바람 예보를 못 받았어요 (날씨 서버 응답 없음)" }
            }.apply { start() }
            val t2 = Thread {
                try {
                    val url = "https://marine-api.open-meteo.com/v1/marine?latitude=$las&longitude=$los" +
                        "&hourly=wave_height,sea_surface_temperature&timezone=Asia%2FSeoul&past_days=2&forecast_days=8"
                    val a = arr(try { get(url) } catch (_: Exception) { Thread.sleep(1500L); get(url) })
                    for (k in 0 until minOf(a.length(), LIST.size)) {
                        val h = a.getJSONObject(k).getJSONObject("hourly"); val t = h.getJSONArray("time")
                        val m = HashMap<Long, DoubleArray>()
                        for (i in 0 until t.length()) {
                            val ms = iso.parse(t.getString(i))?.time ?: continue
                            m[ms] = doubleArrayOf(num(h.optJSONArray("wave_height"), i) ?: Double.NaN, num(h.optJSONArray("sea_surface_temperature"), i) ?: Double.NaN)
                        }
                        synchronized(sea) { sea[LIST[k].id] = m }
                    }
                } catch (_: Exception) { if (err.isBlank()) err = "파도 · 수온 예보를 못 받았어요" }
            }.apply { start() }
            t1.join(30_000L); t2.join(30_000L)
            // 오늘 풍랑 특보 (권역 바다 구역)
            val w = HashMap<String, String>()
            val latch = java.util.concurrent.CountDownLatch(1)
            try {
                Warn.forPlace(LIST[0].lat, LIST[0].lon) { latch.countDown() }   // 특보 글을 한 번 받아 두기 (30분 기억)
                latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
                LIST.forEach { r ->
                    val l2 = java.util.concurrent.CountDownLatch(1)
                    Warn.forPlace(r.lat, r.lon) { s -> if (!s.isNullOrBlank() && (s.contains("풍랑") || s.contains("태풍주의보") || s.contains("태풍경보") || s.contains("해일"))) synchronized(w) { w[r.id] = s }; l2.countDown() }
                    l2.await(3, java.util.concurrent.TimeUnit.SECONDS)
                }
            } catch (_: Exception) {}
            warns = w
            if (wind.isEmpty() && sea.isEmpty()) {
                // 못 받았으면 마지막 저장분이라도
                val old = try { JSONObject(file(app).readText()) } catch (_: Exception) { null }
                if (old != null) { data = parseSaved(old); dataAt = old.getLong("at"); note = "📦 예전에 받은 값이에요 (지금은 못 받음 · 인터넷 확인)" }
                else note = err.ifBlank { "날씨를 못 받았어요 (인터넷 확인)" }
                main.post { done(data) }
                return@Thread
            }
            val out = HashMap<Long, HashMap<String, Cell>>()
            val today = dayStart(System.currentTimeMillis())
            for (d in 0..7) {
                val ds = today + d * 86_400_000L
                val m = HashMap<String, Cell>()
                for (r in LIST) {
                    val wm = wind[r.id].orEmpty(); val sm = sea[r.id].orEmpty()
                    // 낮(새벽 4시 ~ 저녁 6시) 기준
                    val hrs = (4..18).map { ds + it * 3_600_000L }
                    fun mx(vals: List<Double>) = vals.filter { !it.isNaN() }.maxOrNull()
                    val ws = hrs.mapNotNull { wm[it] }
                    val wv = mx(ws.map { it[0] }); val gv = mx(ws.map { it[1] })
                    val dir = ws.maxByOrNull { if (it[0].isNaN()) -1.0 else it[0] }?.get(2)?.takeIf { !it.isNaN() }
                    val pop = mx(ws.map { it[3] })?.toInt()
                    val code = mx(ws.map { it[4] })?.toInt()
                    val wave = mx(hrs.mapNotNull { sm[it]?.get(0) })
                    fun avgSst(day: Long) = (0..23).mapNotNull { sm[day + it * 3_600_000L]?.get(1)?.takeIf { v -> !v.isNaN() } }.takeIf { it.isNotEmpty() }?.average()
                    // v6.50 (318): 먼 바다 예측 수온을 근처에서 기억해 둔 실측 보정(±3칸 ≈ 30km · 7일 안)만큼 맞춤
                    val off = Weather.rememberedSst(r.lat, r.lon, 3)?.first ?: 0.0
                    m[r.id] = Cell(wv, gv, dir, wave, pop, avgSst(ds)?.plus(off), avgSst(ds - 2 * 86_400_000L)?.plus(off), code)
                }
                out[ds] = m
            }
            data = out; dataAt = System.currentTimeMillis()
            note = err
            try { file(app).writeText(save(out).put("at", dataAt).put("note", note).toString()) } catch (_: Exception) {}
            main.post { done(out) }
        }.start()
    }

    private fun save(d: Map<Long, Map<String, Cell>>): JSONObject {
        val o = JSONObject()
        d.forEach { (ds, m) ->
            val j = JSONObject()
            m.forEach { (id, c) ->
                j.put(id, JSONArray().put(c.wind ?: JSONObject.NULL).put(c.gust ?: JSONObject.NULL).put(c.dir ?: JSONObject.NULL)
                    .put(c.wave ?: JSONObject.NULL).put(c.pop ?: JSONObject.NULL).put(c.sst ?: JSONObject.NULL).put(c.sstPrev ?: JSONObject.NULL).put(c.code ?: JSONObject.NULL))
            }
            o.put(ds.toString(), j)
        }
        return JSONObject().put("d", o)
    }

    private fun parseSaved(o: JSONObject): Map<Long, Map<String, Cell>> {
        val d = o.getJSONObject("d")
        val out = HashMap<Long, Map<String, Cell>>()
        d.keys().forEach { k ->
            val j = d.getJSONObject(k); val m = HashMap<String, Cell>()
            j.keys().forEach { id ->
                val a = j.getJSONArray(id)
                m[id] = Cell(num(a, 0), num(a, 1), num(a, 2), num(a, 3), num(a, 4)?.toInt(), num(a, 5), num(a, 6), num(a, 7)?.toInt())
            }
            out[k.toLong()] = m
        }
        return out
    }

    // ---------------- 게시판 최근 7일 조과 (권역별) ----------------
    class Recent(val trips: Int, val fish: Int)
    @Volatile private var board: List<PublicBoard.PTrip>? = null

    private fun km(a1: Double, o1: Double, a2: Double, o2: Double): Double {
        val dy = (a2 - a1) * 111.0; val dx = (o2 - o1) * 111.0 * Math.cos(Math.toRadians((a1 + a2) / 2))
        return Math.hypot(dx, dy)
    }
    private fun nearestRegion(lat: Double, lon: Double): Reg? {
        val r = LIST.minByOrNull { km(lat, lon, it.lat, it.lon) } ?: return null
        return if (km(lat, lon, r.lat, r.lon) <= 60.0) r else null
    }

    private fun recent(species: String): Map<String, Recent> {
        val l = board ?: return emptyMap()
        val since = System.currentTimeMillis() - 7 * 86_400_000L
        val trips = HashMap<String, Int>(); val fish = HashMap<String, Int>()
        for (t in l) {
            if (t.start < since || t.isFresh()) continue
            val c = t.catches.firstOrNull { it.hasExact }?.let { it.exactLat!! to it.exactLon!! } ?: t.anyCell()?.let { it.lat to it.lon } ?: continue
            val r = nearestRegion(c.first, c.second) ?: continue
            val n = if (species == "전체") t.catches.size else t.catches.count { t.name(it.slot).contains(species) }
            if (species != "전체" && n == 0 && t.names.none { it.contains(species) }) continue
            trips[r.id] = (trips[r.id] ?: 0) + 1; fish[r.id] = (fish[r.id] ?: 0) + n
        }
        return trips.keys.associateWith { Recent(trips[it]!!, fish[it] ?: 0) }
    }

    // ---------------- 점수 ----------------
    fun score(r: Reg, day: Long, c: Cell?, species: String, rec: Map<String, Recent>, avgFish: Double?): Score {
        val pct = Tide.estimatePct(day + 12 * 3_600_000L)
        if (c == null) return Score(-1, -1, "예보가 없어요", "", pct, 0, 0)
        // 🌤 출조환경
        val envWhy = ArrayList<String>()
        val w = c.wind; val wv = c.wave
        val wP = when { w == null -> 0; w < 4 -> 0; w < 6 -> 1; w < 8 -> 2; w < 10 -> 3; else -> 4 }
        val hP = when { wv == null -> 0; wv < 0.5 -> 0; wv < 1.0 -> 1; wv < 1.5 -> 2; wv < 2.5 -> 3; else -> 4 }
        var bad = maxOf(wP, hP)
        if (w != null) envWhy.add(String.format(Locale.KOREA, "바람 최대 %.0fm/s", w))
        if (wv != null) envWhy.add(String.format(Locale.KOREA, "파고 %.1fm", wv))
        if ((c.gust ?: 0.0) >= 15) { bad = maxOf(bad, 3); envWhy.add(String.format(Locale.KOREA, "돌풍 %.0fm/s", c.gust)) }
        if ((c.pop ?: 0) >= 60) { bad += 1; envWhy.add("비 ${c.pop}%") } else if (c.pop != null) envWhy.add("비 ${c.pop}%")
        val warn = if (day == dayStart(System.currentTimeMillis())) warns[r.id] else null
        if (warn != null) { bad = 4; envWhy.add(0, "⚠ $warn") }
        val env = 4 - bad.coerceIn(0, 4)
        // 🎣 예상조과
        val p = PREF[species] ?: PREF["전체"]!!
        val why = ArrayList<String>()
        var sc = 0.0
        if (pct in p.fLo..p.fHi) { sc += 0.5; why.add("물세기 ${pct}% 알맞음") }
        else if (pct > p.fHi) { sc -= 0.5; why.add("물세기 ${pct}% 셈") }
        else { sc -= 0.5; why.add("물세기 ${pct}% 약함") }
        c.sst?.let { t ->
            when {
                t < p.tLo - 1.5 -> { sc -= 1.0; why.add(String.format(Locale.KOREA, "수온 %.1f℃ 낮음", t)) }
                t > p.tHi + 1.5 -> { sc -= 1.0; why.add(String.format(Locale.KOREA, "수온 %.1f℃ 높음", t)) }
                t in p.tLo..p.tHi -> { sc += 0.5; why.add(String.format(Locale.KOREA, "수온 %.1f℃ 알맞음", t)) }
                else -> why.add(String.format(Locale.KOREA, "수온 %.1f℃", t))
            }
            c.sstPrev?.let { pv ->
                val dt = t - pv
                if (dt <= -1.0) { sc -= 1.0; why.add(String.format(Locale.KOREA, "수온 이틀 새 %.1f℃", dt)) }
                else if (dt >= 0.7 && t < p.tHi) { sc += 0.5; why.add(String.format(Locale.KOREA, "수온 이틀 새 +%.1f℃", dt)) }
            }
        }
        val rc = rec[r.id]
        if (rc != null && rc.trips >= 1) {
            val per = rc.fish.toDouble() / rc.trips
            val avg = avgFish
            if (avg != null && avg > 0 && rc.trips >= 2) {
                if (per >= avg * 1.3) { sc += 1.0; why.add("최근 7일 조황 좋음 (${rc.trips}건 · 평균 ${"%.0f".format(per)}마리)") }
                else if (per <= avg * 0.6) { sc -= 1.0; why.add("최근 7일 조황 저조 (${rc.trips}건 · 평균 ${"%.0f".format(per)}마리)") }
                else why.add("최근 7일 ${rc.trips}건 · 평균 ${"%.0f".format(per)}마리")
            } else why.add("최근 7일 ${rc.trips}건 · ${rc.fish}마리")
        }
        if (env <= 1) { sc -= 0.5; why.add("바다가 거칠어 낚시가 어려움") }
        val catch = Math.round(2 + sc).toInt().coerceIn(0, 4)
        return Score(env, catch, envWhy.joinToString(" · "), why.joinToString(" · "), pct, rc?.trips ?: 0, rc?.fish ?: 0)
    }

    // ---------------- 화면 ----------------
    private const val P_SPECIES = "region_species"
    private const val P_FAV = "region_fav"
    private const val P_VIEW = "region_view"

    @SuppressLint("SetTextI18n")
    fun show(a: Activity) {
        fun dp(v: Int) = Ui.dp(a, v)
        val prefs = a.getSharedPreferences("fishlog", Context.MODE_PRIVATE)
        var dayIdx = 0
        var mode = 0   // 0 출조환경 · 1 예상조과
        var species = prefs.getString(P_SPECIES, "전체") ?: "전체"
        var listView = prefs.getBoolean(P_VIEW, false)
        var sel: Reg? = null
        val favs = (prefs.getString(P_FAV, "") ?: "").split(",").filter { it.isNotBlank() }.toMutableSet()
        val today = dayStart(System.currentTimeMillis())

        val dlg = Dialog(a, R.style.FlFullUp)
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        val headBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(8)); setBackgroundColor(Color.parseColor("#132B40")) }
        root.addView(headBox)
        val top = Ui.row(a)
        top.addView(Ui.text(a, 19f, Ui.TEXT, true, "🗺 권역 낚시환경"), LinearLayout.LayoutParams(0, WRAP, 1f))
        val viewBtn = Ui.text(a, 13f, Ui.TEXT, true, "").apply {
            gravity = Gravity.CENTER; setPadding(dp(12), dp(7), dp(12), dp(7)); background = Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE)
        }
        // v6.50 (319): 📷 지금 보이는 지도(또는 목록) + 열린 카드를 사진 한 장으로 (물때 창과 같은 카메라 그림)
        val shotBtn = android.widget.ImageView(a).apply {
            setImageResource(R.drawable.ic_shot); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(7), dp(7), dp(7), dp(7)); background = Ui.bg(a, Ui.CARD, 12, Ui.LINE)
            contentDescription = "권역 낚시환경 사진 찍기"
        }
        top.addView(shotBtn, LinearLayout.LayoutParams(dp(42), dp(38)).apply { rightMargin = dp(6) })
        top.addView(viewBtn)
        top.addView(Ui.text(a, 13f, Ui.TEXT, true, "닫기").apply {
            gravity = Gravity.CENTER; setPadding(dp(14), dp(7), dp(14), dp(7))
            background = Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE); setOnClickListener { dlg.dismiss() }
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
        headBox.addView(top)
        val noteV = Ui.text(a, 11f, Ui.SUB, false, "대충 계산 · 받는 중…")
        headBox.addView(noteV, Ui.lp(a, MATCH, WRAP, top = 4))
        // 날짜 줄
        val days = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        headBox.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(days) }, Ui.lp(a, MATCH, WRAP, top = 8))
        // 출조환경 | 예상조과
        val tabs = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; background = Ui.bg(a, Color.parseColor("#0C1D2B"), 10); setPadding(dp(3), dp(3), dp(3), dp(3)) }
        val tabEnv = Ui.text(a, 13f, Ui.TEXT, true, "🌤 출조환경").apply { gravity = Gravity.CENTER; setPadding(0, dp(7), 0, dp(7)) }
        val tabCatch = Ui.text(a, 13f, Ui.TEXT, true, "🎣 예상조과").apply { gravity = Gravity.CENTER; setPadding(0, dp(7), 0, dp(7)) }
        tabs.addView(tabEnv, LinearLayout.LayoutParams(0, WRAP, 1f)); tabs.addView(tabCatch, LinearLayout.LayoutParams(0, WRAP, 1f))
        headBox.addView(tabs, Ui.lp(a, MATCH, WRAP, top = 8))
        // 어종 칩
        val spRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        headBox.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(spRow) }, Ui.lp(a, MATCH, WRAP, top = 8))

        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(a).apply { addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val mapView = MapView(a)
        var scores: Map<String, Score> = emptyMap()
        lateinit var render: () -> Unit

        fun lv(i: Int) = if (i < 0) "-" else NAMES[i]
        fun col(i: Int) = if (i < 0) Color.parseColor("#3B5568") else COLORS[i]
        fun pill(i: Int) = Ui.text(a, 12f, if (i < 0) Ui.TEXT else Ui.BG, true, lv(i)).apply {
            gravity = Gravity.CENTER; setPadding(dp(4), dp(3), dp(4), dp(3)); background = Ui.bg(a, col(i), 8)
        }

        fun openTide(r: Reg) {
            // 그 권역 앞바다 기준으로 물때 창 (닫으면 원래 고른 지점으로)
            val prev = Spot.current
            val mine = Spot.Place(r.name + "권 " + r.port, r.lat, r.lon)
            Spot.current = mine
            // 물때 창 안에서 다른 지점 · 관측소를 고르지 않았을 때만 되돌림
            TideDialog.onNextClose = { Spot.current?.let { if (Spot.same(it, mine)) Spot.current = prev } }
            TideDialog.show(a, r.lat, r.lon, today + dayIdx * 86_400_000L + 12 * 3_600_000L, null)
        }

        fun card(r: Reg): View {
            val s = scores[r.id]
            val c = data?.get(today + dayIdx * 86_400_000L)?.get(r.id)
            val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(14))
                background = Ui.bg(a, Color.parseColor("#152F45"), 18, Color.parseColor("#2C5470")) }
            val h = Ui.row(a)
            h.addView(Ui.text(a, 17f, Ui.TEXT, true, r.name + "권"))
            h.addView(Ui.text(a, 11f, Ui.SUB, false, "  " + r.sub + " · " + r.port), LinearLayout.LayoutParams(0, WRAP, 1f))
            val star = Ui.text(a, 18f, Color.parseColor(if (r.id in favs) "#FFD27A" else "#5A7A90"), false, if (r.id in favs) "★" else "☆").apply {
                setPadding(dp(8), 0, dp(4), 0)
                setOnClickListener { if (r.id in favs) favs.remove(r.id) else favs.add(r.id); prefs.edit().putString(P_FAV, favs.joinToString(",")).apply(); render() }
            }
            h.addView(star)
            h.addView(Ui.text(a, 15f, Ui.SUB, false, "✕").apply { setPadding(dp(10), 0, 0, 0); setOnClickListener { sel = null; render() } })
            box.addView(h)
            val g1 = Ui.row(a); val g2 = Ui.row(a)
            fun cell(row: LinearLayout, t: String, v: CharSequence, vc: Int = Ui.TEXT, first: Boolean) {
                val b = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8)); background = Ui.bg(a, Color.parseColor("#0F2436"), 11) }
                b.addView(Ui.text(a, 11.5f, Ui.SUB, false, t))
                b.addView(Ui.text(a, 15f, vc, true, "").apply { text = v })
                row.addView(b, LinearLayout.LayoutParams(0, MATCH, 1f).apply { if (!first) leftMargin = dp(7) })
            }
            cell(g1, "🌤 출조환경", lv(s?.env ?: -1), col(s?.env ?: -1), true)
            cell(g1, "🎣 예상조과" + if (species != "전체") " · $species" else "", lv(s?.catch ?: -1), col(s?.catch ?: -1), false)
            val windS = listOfNotNull(c?.dir?.let { Weather.dir16(it) }, c?.wind?.let { String.format(Locale.KOREA, "%.0fm/s", it) }).joinToString(" ")
            cell(g2, "💨 바람 · 🌊 파도", listOf(windS.ifBlank { "-" }, c?.wave?.let { String.format(Locale.KOREA, "%.1fm", it) } ?: "-").joinToString(" · "), Ui.TEXT, true)
            val mul = Tide.mulName(today + dayIdx * 86_400_000L + 12 * 3_600_000L).substringBefore(" (").substringBefore(" · ")
            cell(g2, "🌊 물때 · 🌡 수온", "$mul · ${s?.pct ?: "-"}%" + (c?.sst?.let { String.format(Locale.KOREA, " · %.1f℃", it) } ?: ""), Ui.TEXT, false)
            box.addView(g1, Ui.lp(a, MATCH, WRAP, top = 9)); box.addView(g2, Ui.lp(a, MATCH, WRAP, top = 7))
            // v6.62 (347 ⑧): 오늘은 대표 항구 가까운 부이 · 등표 실측 한 줄
            if (dayIdx == 0 && KmaObs.hasKey()) {
                val ob = Ui.text(a, 12f, Color.parseColor("#9FDCF5"), false, "🛟 실제 바다 불러오는 중…")
                box.addView(ob, Ui.lp(a, MATCH, WRAP, top = 7))
                KmaObs.now(a, r.lat, r.lon) { n ->
                    val m = n?.m
                    if (m == null) { ob.text = "🛟 실제 바다: 가까운 부이 · 등표 자료 없음"; return@now }
                    val parts = listOfNotNull(
                        m.ws?.let { o -> "바람 " + (o.wd?.let { Weather.dir16(it) + " " } ?: "") + String.format(Locale.KOREA, "%.1fm/s", o.ws) },
                        m.wave?.let { o -> String.format(Locale.KOREA, "파고 %.1fm", o.whSig) },
                        m.tw?.let { o -> String.format(Locale.KOREA, "수온 %.1f℃", o.tw) })
                    val src = listOfNotNull(m.ws, m.wave, m.tw).distinctBy { it.stn.id }.joinToString(" · ") { "${it.stn.label} ${String.format(Locale.KOREA, "%.0f", it.km)}km" }
                    ob.text = "🛟 지금 실제: " + parts.joinToString(" · ") + "\n" + src + " (점수는 예보 기준)"
                }
            }
            if (s != null) {
                box.addView(Ui.text(a, 12f, Ui.SUB, false, "").apply {
                    text = "왜 이렇게?\n🌤 " + s.envWhy.ifBlank { "-" } + "\n🎣 " + s.catchWhy.ifBlank { "-" }
                    setLineSpacing(0f, 1.2f)
                }, Ui.lp(a, MATCH, WRAP, top = 9))
            }
            box.addView(Ui.text(a, 14f, Color.WHITE, true, "🌊 이 권역 물때 · 날씨 자세히 ›").apply {
                gravity = Gravity.CENTER; setPadding(0, dp(11), 0, dp(11)); background = Ui.bg(a, Color.parseColor("#2C8FC8"), 12)
                setOnClickListener { openTide(r) }
            }, Ui.lp(a, MATCH, WRAP, top = 10))
            return box
        }

        fun listRow(r: Reg): View {
            val s = scores[r.id]; val c = data?.get(today + dayIdx * 86_400_000L)?.get(r.id)
            val row = Ui.row(a).apply { setPadding(dp(12), dp(10), dp(12), dp(10)); background = Ui.bg(a, Ui.CARD, 12)
                setOnClickListener { sel = r; listView = false; prefs.edit().putBoolean(P_VIEW, false).apply(); render() } }
            row.addView(Ui.text(a, 15f, Color.parseColor("#FFD27A"), false, if (r.id in favs) "★" else "").apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(20), WRAP))
            val nm = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            nm.addView(Ui.text(a, 15f, Ui.TEXT, true, r.name + "권"))
            nm.addView(Ui.text(a, 11f, Ui.SUB, false, listOfNotNull(r.sub, c?.wind?.let { String.format(Locale.KOREA, "💨 %.0fm/s", it) },
                c?.wave?.let { String.format(Locale.KOREA, "🌊 %.1fm", it) }).joinToString(" · ")).apply { maxLines = 1 })
            row.addView(nm, LinearLayout.LayoutParams(0, WRAP, 1f))
            fun lvBox(t: String, i: Int) = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                addView(Ui.text(a, 10f, Ui.SUB, false, t))
                addView(pill(i), LinearLayout.LayoutParams(dp(66), WRAP).apply { topMargin = dp(2) })
            }
            row.addView(lvBox("출조환경", s?.env ?: -1), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
            row.addView(lvBox("예상조과", s?.catch ?: -1), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
            return row
        }

        render = {
            val ds = today + dayIdx * 86_400_000L
            val dm = data?.get(ds)
            val rec = recent(species)
            val avg = rec.values.filter { it.trips > 0 }.let { l -> if (l.sumOf { it.trips } >= 3) l.sumOf { it.fish }.toDouble() / l.sumOf { it.trips } else null }
            scores = LIST.associate { it.id to score(it, ds, dm?.get(it.id), species, rec, avg) }
            noteV.text = listOf("대충 계산 · 배와 포인트에 따라 달라요", note).filter { it.isNotBlank() }.joinToString(" · ")
            // 날짜
            days.removeAllViews()
            val md = SimpleDateFormat("d", Locale.KOREA); val wd = SimpleDateFormat("E", Locale.KOREA)
            for (i in 0..7) {
                val t = today + i * 86_400_000L + 12 * 3_600_000L
                val on = i == dayIdx
                val sun = java.util.Calendar.getInstance(KST).apply { timeInMillis = t }.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.SUNDAY
                val v = Ui.text(a, 11f, if (on) Color.WHITE else Ui.SUB, false, "").apply {
                    gravity = Gravity.CENTER; setPadding(dp(10), dp(4), dp(10), dp(5))
                    val big = if (i == 0) "오늘" else SimpleDateFormat("M월", Locale.KOREA).format(Date(t))
                    val sb = android.text.SpannableStringBuilder(big + "\n")
                    val st = sb.length
                    sb.append(md.format(Date(t)) + " " + wd.format(Date(t)))
                    sb.setSpan(android.text.style.RelativeSizeSpan(1.25f), st, sb.length, 0)
                    sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), st, sb.length, 0)
                    if (sun) sb.setSpan(android.text.style.ForegroundColorSpan(if (on) Color.parseColor("#FFD0D0") else Color.parseColor("#FF8A8A")), st, sb.length, 0)
                    text = sb
                    background = Ui.bg(a, if (on) Color.parseColor("#2C8FC8") else Color.parseColor("#1C3A52"), 10)
                    setOnClickListener { dayIdx = i; render() }
                }
                days.addView(v, LinearLayout.LayoutParams(WRAP, WRAP).apply { if (i > 0) leftMargin = dp(6) })
            }
            tabEnv.background = if (mode == 0) Ui.bg(a, Color.parseColor("#24506F"), 8) else null
            tabCatch.background = if (mode == 1) Ui.bg(a, Color.parseColor("#24506F"), 8) else null
            tabEnv.setTextColor(if (mode == 0) Color.WHITE else Ui.SUB); tabCatch.setTextColor(if (mode == 1) Color.WHITE else Ui.SUB)
            tabs.visibility = if (listView) View.GONE else View.VISIBLE
            spRow.removeAllViews()
            SPECIES.forEachIndexed { i, sp ->
                val on = sp == species
                spRow.addView(Ui.text(a, 12.5f, if (on) Color.parseColor("#1A1A1A") else Ui.TEXT, on, sp).apply {
                    gravity = Gravity.CENTER; setPadding(dp(11), dp(4), dp(11), dp(5))
                    background = if (on) Ui.bg(a, Color.parseColor("#FFD27A"), 99) else Ui.bg(a, Color.TRANSPARENT, 99, Color.parseColor("#2C5470"))
                    setOnClickListener { species = sp; prefs.edit().putString(P_SPECIES, sp).apply(); render() }
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { if (i > 0) leftMargin = dp(6) })
            }
            viewBtn.text = if (listView) "🗺 지도" else "☰ 목록"
            body.removeAllViews()
            if (!listView) {
                mapView.levels = LIST.associate { it.id to ((if (mode == 0) scores[it.id]?.env else scores[it.id]?.catch) ?: -1) }
                mapView.selected = sel?.id
                mapView.onPick = { r -> sel = r; render(); scroll.post { scroll.smoothScrollTo(0, body.height) } }
                mapView.invalidate()
                (mapView.parent as? LinearLayout)?.removeView(mapView)
                body.addView(mapView, LinearLayout.LayoutParams(MATCH, WRAP))
                // 범례
                val leg = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(8)) }
                NAMES.forEachIndexed { i, n ->
                    leg.addView(View(a).apply { background = Ui.bg(a, COLORS[i], 99) }, LinearLayout.LayoutParams(dp(9), dp(9)).apply { leftMargin = if (i > 0) dp(9) else 0 })
                    leg.addView(Ui.text(a, 11f, Ui.SUB, false, " $n"))
                }
                body.addView(leg)
                sel?.let { body.addView(card(it), LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(10), 0, dp(10), dp(14)) }) }
                    ?: body.addView(Ui.text(a, 12f, Ui.SUB, false, "점을 누르면 그 권역 자세히 · 위 ☰ 목록 = 좋은 순").apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(16)) }, Ui.lp(a, MATCH, WRAP))
            } else {
                val order = LIST.sortedWith(compareByDescending<Reg> { it.id in favs }.thenByDescending { (scores[it.id]?.env ?: -1) + (scores[it.id]?.catch ?: -1) })
                val lb = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(14)) }
                order.forEachIndexed { i, r -> lb.addView(listRow(r), Ui.lp(a, MATCH, WRAP, top = if (i == 0) 0 else 6)) }
                lb.addView(Ui.text(a, 11f, Ui.SUB, false, "★ 즐겨찾기 권역은 맨 위 · 줄을 누르면 지도에서 자세히 (카드의 ☆ 로 즐겨찾기)").apply { gravity = Gravity.CENTER }, Ui.lp(a, MATCH, WRAP, top = 10))
                body.addView(lb)
            }
        }
        viewBtn.setOnClickListener { listView = !listView; prefs.edit().putBoolean(P_VIEW, listView).apply(); render() }
        shotBtn.setOnClickListener {
            // 사진에는 버튼(날짜 줄 · 탭 · 어종 · 닫기) 대신 글로: 제목 + 날짜 · 종류 · 어종
            val t = today + dayIdx * 86_400_000L + 12 * 3_600_000L
            val dayS = SimpleDateFormat("M월 d일(E)", Locale.KOREA).format(Date(t))
            val kind = if (listView) "☰ 좋은 순 목록" else if (mode == 0) "🌤 출조환경" else "🎣 예상조과"
            val w = body.width.takeIf { it > 0 } ?: return@setOnClickListener
            val inner = w - 2 * dp(16)
            val head = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            head.addView(Ui.text(a, 19f, Ui.TEXT, true, "🗺 권역 낚시환경 · $dayS"))
            head.addView(Ui.text(a, 12f, Ui.SUB, false, "$kind · 어종 $species · 대충 계산 · 배와 포인트에 따라 달라요"), Ui.lp(a, MATCH, WRAP, top = 4))
            head.measure(View.MeasureSpec.makeMeasureSpec(inner, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            head.layout(0, 0, inner, head.measuredHeight)
            Snap.capture(a, listOf(head), body,
                "조과일지 · 권역 낚시환경 · $dayS · " + SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date()) + " 찍음",
                "권역-" + SimpleDateFormat("MMdd", Locale.KOREA).format(Date(t)))
        }
        tabEnv.setOnClickListener { mode = 0; render() }
        tabCatch.setOnClickListener { mode = 1; render() }

        render()
        dlg.setContentView(root)
        Ui.edge(dlg.window)
        dlg.show()
        load(a, false) { render() }
        // 게시판 최근 글 (폰 저장분 먼저)
        if (Cloud.user != null) PublicBoard.load(System.currentTimeMillis() - 8 * 86_400_000L, quick = { l -> if (board == null) { board = l; if (dlg.isShowing) render() } }) { l, _ ->
            if (l != null) { board = l; if (dlg.isShowing) render() }
        }
    }

    // ---------------- 지도 ----------------
    private class Geo(val w: Int, val h: Int, val k: Double, val tx: Double, val ty: Double, val korea: List<Path>, val other: List<Path>)
    @Volatile private var geo: Geo? = null

    private fun geo(c: Context): Geo? {
        geo?.let { return it }
        return try {
            val o = JSONObject(c.assets.open("korea_map.json").bufferedReader().use { it.readText() })
            fun paths(a: JSONArray): List<Path> = (0 until a.length()).map { i ->
                val r = a.getJSONArray(i); val p = Path()
                var j = 0
                while (j + 1 < r.length()) { val x = r.getInt(j).toFloat(); val y = r.getInt(j + 1).toFloat(); if (j == 0) p.moveTo(x, y) else p.lineTo(x, y); j += 2 }
                p.close(); p
            }
            Geo(o.getInt("w"), o.getInt("h"), o.getDouble("k"), o.getDouble("tx"), o.getDouble("ty"), paths(o.getJSONArray("K")), paths(o.getJSONArray("O"))).also { geo = it }
        } catch (_: Exception) { null }
    }

    /** 위도 · 경도 → 지도 그림 좌표 (assets 만들 때와 같은 메르카토르) */
    private fun proj(g: Geo, lat: Double, lon: Double): Pair<Float, Float> {
        val x = g.tx + g.k * Math.toRadians(lon)
        val y = g.ty - g.k * Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2))
        return x.toFloat() to y.toFloat()
    }

    private class MapView(c: Context) : View(c) {
        var levels: Map<String, Int> = emptyMap()
        var selected: String? = null
        var onPick: ((Reg) -> Unit)? = null
        private val g = geo(c)
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dens = c.resources.displayMetrics.density
        private val pts = HashMap<String, Pair<Float, Float>>()
        // 지도는 위아래 조금 잘라서 (북한 쪽 · 제주 아래 빈 바다)
        private val cropTop = 0.06f; private val cropBot = 0.02f

        override fun onMeasure(wm: Int, hm: Int) {
            val w = MeasureSpec.getSize(wm)
            val gg = g
            val h = if (gg == null) (w * 1.1f).toInt() else (w * gg.h * (1 - cropTop - cropBot) / gg.w).toInt()
            setMeasuredDimension(w, h)
        }

        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            // 바다
            p.shader = RadialGradient(w * .45f, h * .5f, maxOf(w, h) * .75f, intArrayOf(Color.parseColor("#123A57"), Color.parseColor("#0D2A40"), Color.parseColor("#081723")),
                floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, w, h, p); p.shader = null
            p.color = Color.argb(18, 95, 168, 216); p.strokeWidth = 1f
            var gx = 0f; while (gx < w) { cv.drawLine(gx, 0f, gx, h, p); gx += w / 10 }
            var gy = 0f; while (gy < h) { cv.drawLine(0f, gy, w, gy, p); gy += w / 10 }
            val gg = g ?: run {
                p.color = Ui.SUB; p.textSize = 14 * dens; p.textAlign = Paint.Align.CENTER
                cv.drawText("지도를 못 불러왔어요 · ☰ 목록으로 보세요", w / 2, h / 2, p); return
            }
            val sc = w / gg.w
            cv.save()
            cv.translate(0f, -gg.h * cropTop * sc)
            cv.scale(sc, sc)
            // 주변 나라 (어둡게)
            p.style = Paint.Style.FILL; p.color = Color.parseColor("#18262F")
            gg.other.forEach { cv.drawPath(it, p) }
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f / sc * dens * .6f; p.color = Color.parseColor("#2A3B47")
            gg.other.forEach { cv.drawPath(it, p) }
            // 해안 물빛 띠
            p.strokeJoin = Paint.Join.ROUND
            p.color = Color.argb(46, 79, 179, 232); p.strokeWidth = 14f
            gg.korea.forEach { cv.drawPath(it, p) }
            p.color = Color.argb(60, 79, 179, 232); p.strokeWidth = 6f
            gg.korea.forEach { cv.drawPath(it, p) }
            // 우리나라
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(0f, 0f, gg.w.toFloat(), gg.h.toFloat(), Color.parseColor("#2E5A48"), Color.parseColor("#1E3F35"), Shader.TileMode.CLAMP)
            gg.korea.forEach { cv.drawPath(it, p) }
            p.shader = null
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f; p.color = Color.parseColor("#8FD3B5")
            gg.korea.forEach { cv.drawPath(it, p) }
            p.style = Paint.Style.FILL
            cv.restore()
            fun X(x: Float) = x * sc
            fun Y(y: Float) = (y - gg.h * cropTop) * sc
            // 바다 이름
            p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true; p.textSize = 15 * dens; p.color = Color.argb(150, 95, 143, 176)
            p.letterSpacing = .3f
            listOf(Triple("서해", 35.4, 125.3), Triple("남해", 33.75, 128.3), Triple("동해", 37.15, 130.3)).forEach { (n, la, lo) ->
                val (x, y) = proj(gg, la, lo); cv.drawText(n, X(x), Y(y), p)
            }
            p.letterSpacing = 0f
            // 권역 점 + 이름표
            pts.clear()
            for (r in LIST) {
                val (x0, y0) = proj(gg, r.lat, r.lon)
                val x = X(x0); val y = Y(y0)
                pts[r.id] = x to y
                val lvl = levels[r.id] ?: -1
                val c = if (lvl < 0) Color.parseColor("#5A7A90") else COLORS[lvl]
                val sel = r.id == selected
                val label = r.name
                p.textSize = 11.5f * dens; p.isFakeBoldText = true
                val tw = p.measureText(label) + 14 * dens
                val lh = 20 * dens
                val (lx0, ly) = when (r.side) { -1 -> (x - 26 * dens - tw) to y; 1 -> (x + 26 * dens) to y; else -> (x - tw / 2) to (y + 30 * dens) }
                val lx = lx0.coerceIn(3 * dens, w - tw - 3 * dens)
                // 선
                p.color = c; p.alpha = 150; p.strokeWidth = 1 * dens
                if (r.side == 0) cv.drawLine(x, y, x, ly - lh / 2, p) else cv.drawLine(x, y, if (r.side < 0) lx + tw else lx, ly, p)
                p.alpha = 255
                // 이름표
                val rr = RectF(lx, ly - lh / 2, lx + tw, ly + lh / 2)
                p.style = Paint.Style.FILL; p.color = if (sel) Color.parseColor("#FFD27A") else Color.argb(225, 13, 34, 51)
                cv.drawRoundRect(rr, lh / 2, lh / 2, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f * dens; p.color = c
                cv.drawRoundRect(rr, lh / 2, lh / 2, p)
                p.style = Paint.Style.FILL; p.color = if (sel) Color.parseColor("#1A1A1A") else Color.parseColor("#EAF2F6"); p.textAlign = Paint.Align.CENTER
                cv.drawText(label, rr.centerX(), ly + 4 * dens, p)
                // 빛 + 점
                p.color = c; p.alpha = 140; p.maskFilter = BlurMaskFilter(7 * dens, BlurMaskFilter.Blur.NORMAL)
                cv.drawCircle(x, y, 12 * dens, p); p.maskFilter = null; p.alpha = 255
                if (sel) { p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * dens; p.color = Color.parseColor("#FFD27A"); cv.drawCircle(x, y, 13 * dens, p); p.style = Paint.Style.FILL }
                p.color = c; cv.drawCircle(x, y, 7 * dens, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 2 * dens; p.color = Color.WHITE; cv.drawCircle(x, y, 7 * dens, p); p.style = Paint.Style.FILL
            }
        }

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }   // 빛(Blur) 그리기

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_DOWN) return true
            if (e.action == MotionEvent.ACTION_UP) {
                val hit = pts.minByOrNull { (_, q) -> Math.hypot((q.first - e.x).toDouble(), (q.second - e.y).toDouble()) }
                if (hit != null) {
                    val q = hit.value
                    if (Math.hypot((q.first - e.x).toDouble(), (q.second - e.y).toDouble()) < 34 * dens) LIST.firstOrNull { it.id == hit.key }?.let { onPick?.invoke(it) }
                }
                return true
            }
            return super.onTouchEvent(e)
        }
    }
}
