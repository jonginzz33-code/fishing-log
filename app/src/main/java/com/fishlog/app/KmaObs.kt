package com.fishlog.app

import android.content.Context
import android.location.Location
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * v6.62 (347): 🛟 기상청 API허브 실측 바다 자료 (부이 · 등표 · 기상1호 · 태풍 목록)
 *  - 키 = BuildConfig.KMA_HUB_KEY (GitHub 비밀값 KMA_HUB_KEY · 없으면 아무것도 안 함)
 *  - 지점표(위치)는 하루 한 번 받아 폰(filesDir/kma_stn.txt)에 저장
 *  - 자료는 글자 표(EUC-KR · '#' 머리말 줄 · 빈 값 -99) · 요청 시각 −59분~00분 묶음
 *  - 가까운 관측점은 60km 안 · 30km 넘으면 '멀어요' · 항구 안 부이는 '항구 안' 표시
 */
object KmaObs {
    private const val BASE = "https://apihub.kma.go.kr/api/typ01/url/"
    private val KST: TimeZone = TimeZone.getTimeZone("Asia/Seoul")
    private val EUC: Charset = try { Charset.forName("EUC-KR") } catch (_: Exception) { Charsets.UTF_8 }

    fun hasKey() = BuildConfig.KMA_HUB_KEY.isNotBlank()

    /** 관측점 (부이 · 등표 · 기상1호) */
    class Stn(val id: String, val name: String, val lat: Double, val lon: Double, val kind: String) {
        /** 항구 · 해수욕장 앞(연안) 부이 = 바깥 바다보다 바람 · 파도가 약하게 나와요 */
        val harbor: Boolean get() = kind == "buoy" && (name.endsWith("항") || id.toIntOrNull()?.let { it in 22312..22332 } == true)
        val label: String get() = when (kind) { "lh" -> "$name 등표"; "ship" -> name; else -> "$name 부이" }
    }

    /** 한 시각 관측값 (없으면 null) */
    class Obs(val stn: Stn, val time: Long, val wd: Double?, val ws: Double?, val gust: Double?, val pa: Double?,
              val ta: Double?, val tw: Double?, val whMax: Double?, val whSig: Double?, val wp: Double?, var km: Double = 0.0) {
        val hasAny get() = ws != null || whSig != null || tw != null || pa != null
    }

    // ---------------- 받기 ----------------
    private fun get(path: String): String? {
        if (!hasKey()) return null
        val c = URL(BASE + path + "&authKey=" + BuildConfig.KMA_HUB_KEY).openConnection() as HttpURLConnection
        c.connectTimeout = 7_000; c.readTimeout = 9_000
        return try {
            if (c.responseCode != 200) null else c.inputStream.use { String(it.readBytes(), EUC) }
        } catch (_: Exception) { null } finally { c.disconnect() }
    }

    private fun tmFmt() = SimpleDateFormat("yyyyMMddHHmm", Locale.US).apply { timeZone = KST }
    private fun parseTm(s: String): Long? = try { tmFmt().parse(s)?.time } catch (_: Exception) { null }
    /** 그 시각이 든 '정각' (요청은 정각 기준 −59분~00분이라 다음 정각으로) */
    private fun hourTm(t: Long): String {
        val h = 3_600_000L; val now = System.currentTimeMillis()
        val up = ((t + h - 1) / h) * h
        // 지금 묶음은 지금 시각(10분 단위)까지 · 앞날은 안 물어봄
        return tmFmt().format(if (up > now) (now / 600_000L) * 600_000L else up)
    }
    private fun num(s: String?): Double? = s?.toDoubleOrNull()?.takeIf { it > -90.0 }
    private fun rows(text: String?): List<List<String>> =
        text.orEmpty().lines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }.map { it.trim().split(Regex("\\s+")) }

    // ---------------- 지점표 ----------------
    @Volatile private var stns: List<Stn>? = null
    private fun stnFile(c: Context) = File(c.filesDir, "kma_stn.txt")

    fun stations(c: Context): List<Stn> {
        stns?.let { return it }
        val f = stnFile(c)
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < 24 * 3_600_000L
        var text = if (f.exists()) try { f.readText() } catch (_: Exception) { "" } else ""
        if (!fresh || text.isBlank()) {
            val tm = tmFmt().format(System.currentTimeMillis())
            val b = get("stn_inf.php?inf=BUOY&stn=&tm=$tm&help=0")
            val l = get("stn_inf.php?inf=LHAWS&stn=&tm=$tm&help=0")
            if (b != null || l != null) {
                text = rows(b).joinToString("\n") { "buoy " + it.joinToString(" ") } + "\n" + rows(l).joinToString("\n") { "lh " + it.joinToString(" ") }
                try { f.writeText(text) } catch (_: Exception) {}
            }
        }
        val list = text.lines().mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"))
            if (p.size < 4) return@mapNotNull null
            val lon = p[2].toDoubleOrNull() ?: return@mapNotNull null
            val lat = p[3].toDoubleOrNull() ?: return@mapNotNull null
            if (lat < 30 || lat > 40 || lon < 120 || lon > 134) return@mapNotNull null
            Stn(p[1], p.getOrNull(7) ?: p[1], lat, lon, p[0])
        }
        if (list.isNotEmpty()) stns = list
        return list
    }

    private fun dist(lat: Double, lon: Double, s: Stn): Double {
        val r = FloatArray(1); Location.distanceBetween(lat, lon, s.lat, s.lon, r); return r[0] / 1000.0
    }

    /** 가까운 지점 (km 순) */
    fun near(c: Context, lat: Double, lon: Double, maxKm: Double = 60.0): List<Pair<Stn, Double>> =
        stations(c).map { it to dist(lat, lon, it) }.filter { it.second <= maxKm }.sortedBy { it.second }

    // ---------------- 관측값 ----------------
    private fun parseBuoy(text: String?, byId: Map<String, Stn>): List<Obs> = rows(text).mapNotNull { p ->
        if (p.size < 17) return@mapNotNull null
        val s = byId[p[1]] ?: return@mapNotNull null
        val t = parseTm(p[0]) ?: return@mapNotNull null
        Obs(s, t, num(p[2]), num(p[3]), num(p[4]), num(p[8]), num(p[10]), num(p[11]), num(p[12]), num(p[13]), num(p[15]))
    }
    private fun parseLh(text: String?, byId: Map<String, Stn>): List<Obs> = rows(text).mapNotNull { p ->
        if (p.size < 17) return@mapNotNull null
        val s = byId[p[1]] ?: return@mapNotNull null
        val t = parseTm(p[0]) ?: return@mapNotNull null
        Obs(s, t, num(p[2]), num(p[3]), null, num(p[12]), num(p[7]), num(p[13]), num(p[14]), num(p[15]), num(p[16]))
    }
    /** 기상1호 (배 · 위치가 같이 옴) */
    private fun parseShip(text: String?): List<Obs> = rows(text).mapNotNull { p ->
        if (p.size < 23) return@mapNotNull null
        val lon = p[2].toDoubleOrNull() ?: return@mapNotNull null
        val lat = p[3].toDoubleOrNull() ?: return@mapNotNull null
        if (lat < 30 || lon < 120) return@mapNotNull null
        val t = parseTm(p[0]) ?: return@mapNotNull null
        Obs(Stn(p[1], "기상1호(관측선)", lat, lon, "ship"), t, num(p[4]), num(p[5]), null, num(p[15]), num(p[9]), num(p[17]), num(p[18]), num(p[19]), num(p[22]))
    }

    private val hourCache = ConcurrentHashMap<String, Pair<Long, List<Obs>>>()

    /** 그 시각(정각 묶음)의 관측값 · stnIds 가 있으면 그 지점만(작게) · 지금 묶음은 10분 · 지난 묶음은 하루 기억 */
    private fun hourObs(c: Context, t: Long, buoyIds: List<String>?, lhIds: List<String>?, ship: Boolean): List<Obs> {
        val tm = hourTm(t)
        val key = "$tm|${buoyIds?.joinToString(",")}|${lhIds?.joinToString(",")}|$ship"
        val now = System.currentTimeMillis()
        val keep = if (t > now - 2 * 3_600_000L) 10 * 60_000L else 24 * 3_600_000L
        hourCache[key]?.let { (at, v) -> if (now - at < keep) return v }
        val byId = stations(c).associateBy { it.id }
        val out = ArrayList<Obs>()
        val jobs = ArrayList<() -> Unit>()
        if (buoyIds == null || buoyIds.isNotEmpty()) (buoyIds ?: listOf("0")).forEach { id ->
            jobs.add { parseBuoy(get("kma_buoy.php?tm=$tm&stn=$id&help=0"), byId).let { synchronized(out) { out.addAll(it) } } } }
        if (lhIds == null || lhIds.isNotEmpty()) (lhIds ?: listOf("0")).forEach { id ->
            jobs.add { parseLh(get("kma_lhaws.php?tm=$tm&stn=$id&help=0"), byId).let { synchronized(out) { out.addAll(it) } } } }
        if (ship) jobs.add { parseShip(get("kma_kship.php?tm=$tm&stn=0&help=0")).let { synchronized(out) { out.addAll(it) } } }
        val ts = jobs.map { j -> Thread { try { j() } catch (_: Exception) {} }.apply { start() } }
        ts.forEach { try { it.join(15_000L) } catch (_: Exception) {} }
        if (out.isNotEmpty()) hourCache[key] = now to out
        return out
    }

    /** 지점마다 그 시각에 가장 가까운 값 하나 */
    private fun latestPer(list: List<Obs>, t: Long): List<Obs> =
        list.filter { it.hasAny }.groupBy { it.stn.id }.values.mapNotNull { l -> l.minByOrNull { kotlin.math.abs(it.time - t) } }

    /** 가까운 관측점들의 그 시각 값 (km 순 · 60km 안) — 백그라운드에서 부르기 */
    fun nearAt(c: Context, lat: Double, lon: Double, t: Long, maxKm: Double = 60.0): List<Obs> {
        if (!hasKey()) return emptyList()
        val near = near(c, lat, lon, maxKm).take(6)
        val bIds = near.filter { it.first.kind == "buoy" }.map { it.first.id }
        val lIds = near.filter { it.first.kind == "lh" }.map { it.first.id }
        val all = hourObs(c, t, bIds, lIds, ship = true)
        return latestPer(all, t).onEach { it.km = dist(lat, lon, it.stn) }.filter { it.km <= maxKm }.sortedBy { it.km }
    }

    /** 전국 부이 · 등표 · 기상1호 그 시각 값 (바람 지도 점용 · 요청 3번) — 백그라운드 */
    fun allAt(c: Context, t: Long): List<Obs> {
        if (!hasKey()) return emptyList()
        return latestPer(hourObs(c, t, null, null, ship = true), t)
    }

    /** 칸마다 가장 가까운 관측점 값으로 합친 '한 시각 실측' */
    class Merged(val ws: Obs?, val wave: Obs?, val tw: Obs?, val pa: Obs?) {
        val any get() = ws != null || wave != null || tw != null || pa != null
    }
    fun merge(list: List<Obs>): Merged {
        // 항구 안 부이는 바람 · 파도엔 마지막으로
        val open = list.sortedWith(compareBy<Obs>({ it.stn.harbor }, { it.km }))
        return Merged(open.firstOrNull { it.ws != null }, open.firstOrNull { it.whSig != null },
            list.firstOrNull { it.tw != null }, list.firstOrNull { it.pa != null })
    }

    // ---------------- 지금 실제 바다 (물때 창) ----------------
    class Now(val m: Merged, val paDrop3h: Double?)

    fun now(c: Context, lat: Double, lon: Double, done: (Now?) -> Unit) {
        if (!hasKey()) { done(null); return }
        Thread {
            val t = System.currentTimeMillis()
            val r = try {
                val m = merge(nearAt(c, lat, lon, t))
                // 기압 3시간 변화 (같은 지점)
                val drop = m.pa?.let { p0 ->
                    val before = hourObs(c, t - 3 * 3_600_000L, if (p0.stn.kind == "buoy") listOf(p0.stn.id) else emptyList(),
                        if (p0.stn.kind == "lh") listOf(p0.stn.id) else emptyList(), ship = false)
                    latestPer(before, t - 3 * 3_600_000L).firstOrNull { it.stn.id == p0.stn.id }?.pa?.let { p0.pa!! - it }
                }
                if (m.any) Now(m, drop) else null
            } catch (_: Exception) { null }
            android.os.Handler(android.os.Looper.getMainLooper()).post { done(r) }
        }.start()
    }

    /** 하루 시간별 실측 수온 (지난 날 · 오늘 · 가장 가까운 부이 · 40km 안) — 백그라운드 · 2시간마다 묻고 그 사이는 가까운 값 */
    fun daySst(c: Context, lat: Double, lon: Double, dayStart: Long): Pair<Stn, Map<Long, Double>>? {
        if (!hasKey()) return null
        val h = 3_600_000L
        val now = System.currentTimeMillis()
        val ask = (1..12).map { dayStart + it * 2 * h }.filter { it - 2 * h <= now }
        near(c, lat, lon, 40.0).filter { it.first.kind == "buoy" }.take(2).forEach { (stn, _) ->
            val pts = ConcurrentHashMap<Long, Double>()
            val ts = ask.map { t -> Thread {
                hourObs(c, t, listOf(stn.id), emptyList(), false).filter { it.stn.id == stn.id && it.tw != null }.forEach { pts[it.time] = it.tw!! }
            }.apply { start() } }
            ts.forEach { try { it.join(20_000L) } catch (_: Exception) {} }
            if (pts.isEmpty()) return@forEach
            val out = HashMap<Long, Double>()
            (0 until 24).map { dayStart + it * h }.filter { it <= now }.forEach { hr ->
                pts.entries.minByOrNull { kotlin.math.abs(it.key - hr) }?.takeIf { kotlin.math.abs(it.key - hr) <= 90 * 60_000L }?.let { out[hr] = it.value }
            }
            if (out.isNotEmpty()) return stn to out
        }
        return null
    }

    // ---------------- 출조 기록에 넣을 실측 (시간별) ----------------
    /** from~to 정각마다 실측(바람 · 돌풍 · 파고 · 수온 · 기압 · 관측점 · km) — JSON (출조 끝날 때 한 번 · 백그라운드) */
    fun tripJson(c: Context, lat: Double, lon: Double, from: Long, to: Long): org.json.JSONObject? {
        if (!hasKey()) return null
        val h = 3_600_000L
        val hours = generateSequence((from / h) * h) { it + h }.takeWhile { it <= to }.toList().take(30)
        val o = org.json.JSONObject()
        val near = near(c, lat, lon, 60.0).take(5)
        val bIds = near.filter { it.first.kind == "buoy" }.map { it.first.id }
        val lIds = near.filter { it.first.kind == "lh" }.map { it.first.id }
        hours.forEach { t ->
            val m = merge(latestPer(hourObs(c, t, bIds, lIds, false), t).onEach { it.km = dist(lat, lon, it.stn) })
            if (!m.any) return@forEach
            o.put(t.toString(), org.json.JSONObject().apply {
                m.ws?.let { put("ws", it.ws); it.gust?.let { g -> put("gs", g) }; it.wd?.let { d -> put("wd", d) }; put("wsn", it.stn.label); put("wsk", it.km) }
                m.wave?.let { put("wh", it.whSig); it.whMax?.let { x -> put("whx", x) }; put("whn", it.stn.label); put("whk", it.km) }
                m.tw?.let { put("tw", it.tw); put("twn", it.stn.label); put("twk", it.km) }
                m.pa?.let { put("pa", it.pa) }
            })
        }
        return if (o.length() > 0) o else null
    }

    // ---------------- 태풍 ----------------
    @Volatile private var typCache: Pair<Long, String>? = null
    /** 진행 중 · 한반도 영향 있는 태풍 한 줄 ("" = 없음) — 백그라운드에서 */
    fun typhoonLine(): String {
        if (!hasKey()) return ""
        typCache?.let { (at, v) -> if (System.currentTimeMillis() - at < 3_600_000L) return v }
        val y = SimpleDateFormat("yyyy", Locale.US).apply { timeZone = KST }.format(System.currentTimeMillis())
        val t = get("typ_lst.php?YY=$y&disp=0&help=0") ?: return typCache?.second ?: ""
        val line = rows(t).filter { it.size >= 7 && it[2] == "1" && it[3].toIntOrNull() in 1..3 }.joinToString(" · ") { p ->
            val eff = when (p[3]) { "1" -> "상륙 예상"; "2" -> "직접 영향"; else -> "간접 영향" }
            "🌀 제${p[1]}호 태풍 ${p[6]} 진행 중 (${eff})"
        }
        typCache = System.currentTimeMillis() to line
        return line
    }
}
