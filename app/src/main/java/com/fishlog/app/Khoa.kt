package com.fishlog.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import org.osmdroid.util.GeoPoint
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.floor

/**
 * 🌊 국립해양조사원 '개방海' 해양공간정보 WFS (v3.9)
 *  - 지도에 보이는 칸(0.25°)마다 레이어별로 구역 도형을 받아 옴 → 전국 자동 (파일 올리기 필요 없음)
 *  - 인증키: 관리자가 앱에서 한 번 입력 → 서버 haeruMeta/khoa 에 두고 모든 사용자가 같이 씀
 *  - 받은 칸은 폰에 30일 기억 (다시 안 물어봄)
 *  - 요청: otmsWfsApi.do?ServiceKey=&Layer=&minlon=도^분^초 … &srsName=EPSG:5179 → GML
 */
object Khoa {
    class Layer(val id: String, val name: String, val group: String, val type: String, val point: Boolean = false)

    val LAYERS = listOf(
        Layer("TL_DIST_FSHFRM", "어장정보(23년)", "farm", "auto"),
        Layer("VI_MARCUL_A", "양식장(해도)", "farm", "farm"),
        Layer("TL_LEIHAZ_TIDCHA_A", "갯골", "tidal", "risk"),
        Layer("TL_LEIHAZ_PUDDLE_A", "웅덩이", "tidal", "risk"),
        Layer("VI_NSHPAC_P", "인명사고 지점", "accident", "risk", point = true),
        Layer("TL_RESARE_ENS", "낚시금지구역", "ban", "ban"),
        Layer("TL_RESARE_FR", "낚시제한구역", "ban", "ban"),
        Layer("TL_WLAPRHA_A", "수중레저활동금지구역", "ban", "ban")
    )
    /** 사용자가 켜고 끄는 묶음 */
    val GROUPS = linkedMapOf(
        "farm" to "🐚🦪 마을어장 · 양식장 (면허기간 포함)",
        "tidal" to "⚠ 갯골 · 웅덩이",
        "accident" to "⚠ 인명사고 지점 (많이 확대했을 때 작은 점)",
        "ban" to "🚫 낚시금지 · 낚시제한 · 수중레저금지 구역"
    )

    private const val URL = "https://www.khoa.go.kr/oceanmap/otmsWfsApi.do"
    private const val KEEP_MS = 30L * 24 * 3600_000L
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)
    private val db get() = FirebaseFirestore.getInstance()

    private fun prefs(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)
    fun groupOn(c: Context, g: String) = prefs(c).getBoolean("khoa_g_$g", g != "accident")
    fun setGroupOn(c: Context, g: String, v: Boolean) = prefs(c).edit().putBoolean("khoa_g_$g", v).apply()

    // ---------------- 인증키 ----------------
    @Volatile private var keyChecked = false
    fun key(c: Context): String = prefs(c).getString("khoa_key", "") ?: ""
    /** 서버에서 관리자가 넣은 키를 한 번 받아 옴 (앱을 켤 때마다 한 번) */
    fun loadKey(c: Context, done: () -> Unit) {
        if (keyChecked || !Cloud.isInitialized() || Cloud.user == null) return done()
        keyChecked = true
        db.collection("haeruMeta").document("khoa").get()
            .addOnSuccessListener { d ->
                val k = d.getString("key") ?: ""
                if (k != key(c)) { prefs(c).edit().putString("khoa_key", k).apply(); mem.clear() }
                done()
            }
            .addOnFailureListener { keyChecked = false; done() }
    }
    fun saveKey(c: Context, k: String, done: (String?) -> Unit) {
        db.collection("haeruMeta").document("khoa").set(mapOf("key" to k.trim(), "at" to System.currentTimeMillis()), SetOptions.merge())
            .addOnSuccessListener {
                prefs(c).edit().putString("khoa_key", k.trim()).apply()
                mem.clear(); dir(c).listFiles()?.forEach { it.delete() }; lastErr = ""
                done(null)
            }
            .addOnFailureListener { done("저장 실패: ${it.message}") }
    }

    // ---------------- 칸별 기억 ----------------
    private val mem = HashMap<String, List<Zones.Zone>>()     // "레이어|칸" → 구역
    private val loading = HashSet<String>()
    /** 실패한 칸: 이 시각 전엔 다시 안 물어봄 (1분 뒤 다시) */
    private val retryAt = HashMap<String, Long>()
    @Volatile var lastErr = ""
    fun pending() = loading.size
    private fun dir(c: Context) = File(c.filesDir, "khoa").apply { mkdirs() }

    /** 지금 켜 둔 레이어 */
    fun activeLayers(c: Context) = LAYERS.filter { groupOn(c, it.group) }

    /** 이 칸들에 들어 있는 (켜 둔 레이어의) 구역 */
    fun zonesOf(c: Context, cellIds: List<String>): List<Zones.Zone> {
        if (key(c).isBlank()) return emptyList()
        val out = ArrayList<Zones.Zone>()
        activeLayers(c).forEach { l -> cellIds.forEach { id -> mem["${l.id}|$id"]?.let { out.addAll(it) } } }
        return out
    }

    /** 이 칸들 준비 (폰에 있으면 바로, 없으면 개방海에서) · 하나 받을 때마다 done */
    fun ensure(c: Context, cellIds: List<String>, done: () -> Unit) {
        if (!Zones.on(c)) return
        loadKey(c) {
            val k = key(c)
            if (k.isBlank()) return@loadKey
            val app = c.applicationContext
            activeLayers(c).forEach { l ->
                cellIds.take(12).forEach { id ->
                    val mk = "${l.id}|$id"
                    if (mem.containsKey(mk) || mk in loading) return@forEach
                    if ((retryAt[mk] ?: 0L) > System.currentTimeMillis()) return@forEach
                    val f = File(dir(c), "${l.id}_$id.txt")
                    if (f.exists()) {
                        mem[mk] = try { f.readLines().mapNotNull { Zones.decodeLine(it) } } catch (_: Exception) { emptyList() }
                        // v3.9.2 (164): 오래된 파일도 먼저 보여 주고 (바다에서 인터넷 없어도) · 오래됐으면 뒤에서 새로 받음
                        if (System.currentTimeMillis() - f.lastModified() < KEEP_MS) return@forEach
                    }
                    loading.add(mk)
                    pool.execute {
                        val list = try { fetch(k, l, id) } catch (e: Exception) { lastErr = "${l.name}: ${friendly(e)}"; null }
                        main.post {
                            loading.remove(mk)
                            if (list != null) {
                                mem[mk] = list
                                try { File(dir(app), "${l.id}_$id.txt").writeText(list.joinToString("\n") { it.encode() }) } catch (_: Exception) {}
                            } else retryAt[mk] = System.currentTimeMillis() + 60_000L   // v3.9.1: 빈 칸으로 기억하지 않고 1분 뒤 다시
                            done()
                        }
                    }
                }
            }
        }
    }

    /** 칸 이름 '위_경' (0.25° 단위) → 범위 */
    private fun cellBox(id: String): DoubleArray {
        val (y, x) = id.split("_").map { it.toInt() }
        return doubleArrayOf(y / Zones.CELL, (y + 1) / Zones.CELL, x / Zones.CELL, (x + 1) / Zones.CELL)
    }

    /** 도 → '도^분^초' */
    private fun dms(v: Double): String {
        val d = floor(v).toInt(); val mf = (v - d) * 60; val m = floor(mf).toInt(); val s = Math.round((mf - m) * 60).toInt()
        return if (s >= 60) "$d^${m + 1}^0" else "$d^$m^$s"
    }

    fun urlFor(k: String, layer: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double) =
        "$URL?ServiceKey=" + java.net.URLEncoder.encode(k, "UTF-8") + "&Layer=$layer" +
            "&minlon=${dms(minLon)}&minlat=${dms(minLat)}&maxlon=${dms(maxLon)}&maxlat=${dms(maxLat)}&srsName=EPSG:5179"

    fun get(url: String): String {
        val con = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        con.connectTimeout = 15_000; con.readTimeout = 45_000
        val code = con.responseCode
        val body = (if (code in 200..299) con.inputStream else con.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299) throw Exception("HTTP $code ${body.take(120)}")
        return body
    }

    private fun fetch(k: String, l: Layer, cellId: String): List<Zones.Zone> {
        val b = cellBox(cellId)
        val out = fetchBox(k, l, b[0], b[1], b[2], b[3], 0)
        lastErr = ""
        // 칸을 나눠 받으면 같은 구역이 겹쳐 올 수 있어 한 번만
        return out.distinctBy { it.id }
    }

    /** v3.9.1: 한 번에 받다가 끊기면(자료가 큰 곳: 보령 · 남해 · 전남) 4칸으로 나눠 다시 · 최대 3단계 (약 3km 칸) */
    private fun fetchBox(k: String, l: Layer, la0: Double, la1: Double, lo0: Double, lo1: Double, depth: Int): List<Zones.Zone> {
        var lastE: Exception? = null
        for (tryN in 0..1) {
            try {
                val body = get(urlFor(k, l.id, la0, la1, lo0, lo1))
                if (!body.contains("FeatureCollection")) {
                    // 인증키 오류 등 (XML/JSON 메시지) → 나눠도 소용없음
                    throw IllegalStateException(body.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(150))
                }
                return parse(body, l)
            } catch (e: IllegalStateException) { throw e
            } catch (e: Exception) { lastE = e; if (depth < 3) break }
        }
        if (depth >= 3) throw lastE ?: Exception("받지 못했어요")
        val mLa = (la0 + la1) / 2; val mLo = (lo0 + lo1) / 2
        return fetchBox(k, l, la0, mLa, lo0, mLo, depth + 1) + fetchBox(k, l, la0, mLa, mLo, lo1, depth + 1) +
            fetchBox(k, l, mLa, la1, lo0, mLo, depth + 1) + fetchBox(k, l, mLa, la1, mLo, lo1, depth + 1)
    }

    // ---------------- GML 읽기 ----------------
    private val FEATURE = Regex("<(mapprime:[A-Za-z0-9_]+)\\s+gml:id=\"([^\"]*)\"[^>]*>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)
    private val PROP = Regex("<mapprime:([A-Za-z0-9_]+)>([^<]*)</mapprime:\\1>")
    private val EXTERIOR = Regex("<gml:exterior>\\s*<gml:LinearRing>\\s*<gml:posList[^>]*>([^<]+)</gml:posList>", RegexOption.DOT_MATCHES_ALL)
    private val OUTER_OLD = Regex("<gml:outerBoundaryIs>\\s*<gml:LinearRing>\\s*<gml:coordinates[^>]*>([^<]+)</gml:coordinates>", RegexOption.DOT_MATCHES_ALL)
    private val POS = Regex("<gml:pos(?:\\s[^>]*)?>([^<]+)</gml:pos>")
    private val COORDS = Regex("<gml:coordinates[^>]*>([^<]+)</gml:coordinates>")

    fun parse(body: String, l: Layer): List<Zones.Zone> {
        val out = ArrayList<Zones.Zone>()
        FEATURE.findAll(body).forEach { m ->
            val gid = m.groupValues[2]
            val inner = m.groupValues[3]
            val p = HashMap<String, String>()
            PROP.findAll(inner).forEach { pm -> p[pm.groupValues[1].lowercase(Locale.US)] = pm.groupValues[2].trim() }
            val (type, name, note) = describe(l, p)
            val id = "k" + (l.id.hashCode() and 0xffff).toString(36) + gid.substringAfterLast('.').ifBlank { gid.hashCode().toString(36) }
            val rings = EXTERIOR.findAll(inner).map { flat(it.groupValues[1]) }.toMutableList()
            if (rings.isEmpty()) OUTER_OLD.findAll(inner).forEach { rings.add(pairs(it.groupValues[1])) }
            if (rings.isNotEmpty()) {
                rings.forEachIndexed { i, r ->
                    val pts = Zones.simplifyPts(r.map { Zones.fromUtmK(it[0], it[1]) }, 0.00002)
                    if (pts.size >= 3) out.add(Zones.Zone(if (i == 0) id else "$id-$i", type, name, pts, null, 0.0, 0, note))
                }
            } else {
                // 점 (인명사고 등) → 반지름 60m 원
                val pt = POS.find(inner)?.groupValues?.get(1)?.let { flat(it).firstOrNull() }
                    ?: COORDS.find(inner)?.groupValues?.get(1)?.let { pairs(it).firstOrNull() }
                if (pt != null) out.add(Zones.Zone(id, type, name, emptyList(), Zones.fromUtmK(pt[0], pt[1]), 60.0, 0, note))
            }
        }
        return out
    }

    private fun flat(s: String): List<DoubleArray> {
        val n = s.trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
        return (0 until n.size / 2).map { doubleArrayOf(n[it * 2], n[it * 2 + 1]) }
    }
    private fun pairs(s: String): List<DoubleArray> = s.trim().split(Regex("\\s+")).mapNotNull { t ->
        val v = t.split(",").mapNotNull { it.toDoubleOrNull() }; if (v.size >= 2) doubleArrayOf(v[0], v[1]) else null
    }

    private fun ymd(s: String?): String {
        val d = (s ?: "").filter { it.isDigit() }
        return if (d.length >= 8) "${d.substring(0, 4)}-${d.substring(4, 6)}-${d.substring(6, 8)}" else ""
    }

    /** 레이어 + 속성 → (종류, 이름, 자세한 정보 ¶ 줄바꿈) */
    private fun describe(l: Layer, p: Map<String, String>): Triple<String, String, String> {
        fun v(vararg k: String) = k.firstNotNullOfOrNull { p[it]?.takeIf { s -> s.isNotBlank() } } ?: ""
        if (l.id == "TL_DIST_FSHFRM") {
            val se = v("fids_se")
            val type = when { se.contains("마을") -> "village"; se.contains("양식") -> "farm"; else -> "etc" }
            val rgn = v("rgn_nm", "sgg_nm")
            val addr = v("addr")
            val lines = ArrayList<String>()
            val b = ymd(v("lcns_bgng_ymd")); val e = ymd(v("lcns_end_ymd"))
            if (b.isNotBlank() || e.isNotBlank()) lines.add("📅 면허기간 $b ~ $e")
            val area = v("area").toDoubleOrNull()
            lines.add(listOfNotNull(v("lcns_no").takeIf { it.isNotBlank() }?.let { "🔖 면허번호 $it" },
                area?.takeIf { it > 0 }?.let { String.format(Locale.KOREA, "📐 면적 %.1fha", it / 10_000.0) }).joinToString(" · "))
            lines.add(listOf(v("fids_knd"), v("fids_mthd"), v("farm_knd")).filter { it.isNotBlank() }.distinct().joinToString(" · ").let { if (it.isBlank()) "" else "🎣 $it" })
            lines.add(listOf(v("rel_dept"), rgn).filter { it.isNotBlank() }.joinToString(" · ").let { if (it.isBlank()) "" else "🏛 $it (문의처)" })
            lines.add("자료 ${v("rvsn_yr").ifBlank { "2023" }} · 국립해양조사원 개방海")
            val name = listOf(rgn.substringAfter(" ", rgn), addr).filter { it.isNotBlank() }.joinToString(" ").ifBlank { se }
            return Triple(type, name.take(40), lines.filter { it.isNotBlank() }.joinToString("¶"))
        }
        // 그 밖의 레이어: 이름처럼 보이는 속성 하나 + 레이어 이름
        val nm = p.entries.firstOrNull { (k, s) -> s.isNotBlank() && s.any { it in '가'..'힣' } && (k.contains("nm") || k.contains("nam") || k.contains("name") || k.contains("title")) }?.value
            ?: p.values.firstOrNull { s -> s.isNotBlank() && s.length in 2..40 && s.any { it in '가'..'힣' } } ?: ""
        val extra = p.entries.filter { (k, s) -> s.isNotBlank() && s.any { it in '가'..'힣' } && s != nm }.take(3).joinToString(" · ") { it.value.take(40) }
        val note = listOf(if (extra.isNotBlank()) "ℹ $extra" else "", "자료: 국립해양조사원 개방海 · ${l.name}").filter { it.isNotBlank() }.joinToString("¶")
        return Triple(l.type, (if (nm.isNotBlank()) "${l.name} · $nm" else l.name).take(40), note)
    }

    /** 관리자: 연결 확인 (신안 앞바다 예시 범위) → 받은 개수 · 응답 앞부분 */
    fun test(c: Context, done: (String) -> Unit) {
        val k = key(c)
        if (k.isBlank()) return done("인증키가 아직 없어요")
        pool.execute {
            val msg = try {
                val body = get(urlFor(k, "TL_DIST_FSHFRM", 34.758, 34.805, 125.983, 126.140))
                val n = if (body.contains("FeatureCollection")) parse(body, LAYERS[0]).size else -1
                if (n >= 0) "✅ 연결됐어요 · 시험 범위(신안)에서 어장 ${n}곳 받음" else "❌ 응답이 이상해요:\n" + body.take(400)
            } catch (e: Exception) { "❌ 받지 못했어요: ${friendly(e)}" }
            main.post { done(msg) }
        }
    }

    /** v3.9.2 (163): 인터넷 오류를 한국어로 */
    fun friendly(e: Exception): String {
        val m = (e.message ?: "") + " " + e.javaClass.simpleName
        return when {
            e is java.net.UnknownHostException || m.contains("resolve host", true) || m.contains("abort", true) ||
                e is java.net.SocketTimeoutException || m.contains("timeout", true) || m.contains("timed out", true) ||
                e is java.net.ConnectException || m.contains("reset", true) || m.contains("unreachable", true) ->
                "인터넷 연결이 끊겼어요 · 연결되면 자동으로 다시 받아요"
            m.contains("ServiceKey", true) -> "인증키가 맞지 않아요 (관리자 확인)"
            else -> e.message ?: "알 수 없는 오류"
        }
    }

    // ---------------- 📥 오프라인 지역 (v3.9.2 · 164) ----------------
    class Region(val id: String, val name: String, val s: Double, val n: Double, val w: Double, val e: Double, val at: Long, val cells: List<String>)

    fun regions(c: Context): List<Region> {
        val arr = try { org.json.JSONArray(prefs(c).getString("khoa_regions", "[]")) } catch (_: Exception) { org.json.JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val cs = o.optJSONArray("cells")
            Region(o.optString("id"), o.optString("name"), o.optDouble("s"), o.optDouble("n"), o.optDouble("w"), o.optDouble("e"), o.optLong("at"),
                (0 until (cs?.length() ?: 0)).map { cs!!.optString(it) })
        }.sortedByDescending { it.at }
    }
    private fun saveRegions(c: Context, list: List<Region>) {
        val arr = org.json.JSONArray()
        list.forEach { r ->
            arr.put(org.json.JSONObject().put("id", r.id).put("name", r.name).put("s", r.s).put("n", r.n).put("w", r.w).put("e", r.e)
                .put("at", r.at).put("cells", org.json.JSONArray(r.cells)))
        }
        prefs(c).edit().putString("khoa_regions", arr.toString()).apply()
    }
    private fun filesOf(c: Context, r: Region) = r.cells.flatMap { id -> LAYERS.map { File(dir(c), "${it.id}_$id.txt") } }.filter { it.exists() }
    fun sizeOf(c: Context, r: Region) = filesOf(c, r).sumOf { it.length() }
    fun totalSize(c: Context) = dir(c).listFiles()?.sumOf { it.length() } ?: 0L
    fun sizeText(b: Long) = if (b < 1024 * 1024) "${(b + 1023) / 1024}KB" else String.format(Locale.KOREA, "%.1fMB", b / 1048576.0)

    /** 지우기: 다른 저장 지역이 같이 쓰는 칸은 남김 */
    fun deleteRegion(c: Context, r: Region) {
        val rest = regions(c).filter { it.id != r.id }
        val keep = rest.flatMap { it.cells }.toSet()
        r.cells.filter { it !in keep }.forEach { id -> LAYERS.forEach { l -> File(dir(c), "${l.id}_$id.txt").delete(); mem.remove("${l.id}|$id") } }
        saveRegions(c, rest)
    }
    fun deleteAll(c: Context) {
        dir(c).listFiles()?.forEach { it.delete() }; mem.clear(); retryAt.clear()
        saveRegions(c, emptyList())
    }

    /** 이 범위 미리 받기: 모든 레이어 (꺼 둔 묶음도 · 나중에 켜도 바로 보이게) · progress(받은, 전체) · done(성공, 실패) */
    fun prefetch(c: Context, s: Double, n: Double, w: Double, e: Double, name: String, oldId: String?,
                 progress: (Int, Int) -> Unit, done: (Int, Int) -> Unit) {
        val k = key(c)
        if (k.isBlank()) return done(0, 0)
        val app = c.applicationContext
        val cells = Zones.cellsFor(s, n, w, e, 16)
        val jobs = cells.flatMap { id -> LAYERS.map { it to id } }
        var ok = 0; var fail = 0
        pool.execute {
            jobs.forEachIndexed { i, (l, id) ->
                val list = try { fetch(k, l, id) } catch (ex: Exception) { lastErr = "${l.name}: ${friendly(ex)}"; null }
                if (list != null) {
                    ok++
                    try { File(dir(app), "${l.id}_$id.txt").writeText(list.joinToString("\n") { it.encode() }) } catch (_: Exception) {}
                    main.post { mem["${l.id}|$id"] = list; retryAt.remove("${l.id}|$id") }
                } else fail++
                main.post { progress(i + 1, jobs.size) }
            }
            main.post {
                if (ok > 0) {
                    val id = oldId ?: System.currentTimeMillis().toString()
                    saveRegions(app, regions(app).filter { it.id != id } + Region(id, name, s, n, w, e, System.currentTimeMillis(), cells))
                }
                done(ok, fail)
            }
        }
    }
    fun redownload(c: Context, r: Region, progress: (Int, Int) -> Unit, done: (Int, Int) -> Unit) =
        prefetch(c, r.s, r.n, r.w, r.e, r.name, r.id, progress, done)

    fun distanceOk(a: Double, b: Double) = abs(a - b) < 1e-9
}
