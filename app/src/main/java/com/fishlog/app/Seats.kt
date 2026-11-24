package com.fishlog.app

import android.app.Activity
import android.app.AlarmManager
import android.app.Dialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * v6.75 (380): 🚤 선상 빈자리 — 선상24 선단 페이지(○○.sunsang24.com/ship/schedule_fleet/yyyyMM)를 내 폰이 직접 읽음
 *  - 날짜 줄 · 🐟 어종(여러 개) · 👥 필요한 자리(1~6) · ⚙ 정렬 · 빈자리만 · ⭐ 즐겨찾기 맨 위
 *  - 🔔 자리 나면 알림: 걸어 둔 동안만 15분마다 확인 (AlarmManager · SeatAlarmReceiver) · 필요한 자리 이상 나면 알림
 *  - 확인 방법 'phone'(지금) · 'server'(344 Blaze 뒤 · 아직 없음) — Watch.via 로 나눠 둠
 *  - 저장은 폰 prefs: seat_fleets · seat_fav · seat_watch · seat_need · seat_sort · seat_open · seat_favtop · seat_fish
 */
object Seats {
    class Fleet(var host: String, var name: String, var on: Boolean = true)
    class Sch(val host: String, val shipNo: String, val schNo: String, val ship: String, val date: String,
              val fish: String, val way: String, val time: String, val remain: Int, val reserved: Int,
              val status: String, val tide: String, val place: String = "", val price: Int = 0) {
        val open get() = status == "ING" && remain > 0
        val stName get() = when (status) { "CHECK" -> "점검일"; "BAD_WEATHER" -> "기상악화"; "ING", "END" -> "마감"; else -> "예약 안 받음" }
        val cap get() = remain + reserved
        val key get() = "$host|$shipNo"
    }
    class Watch(val host: String, val shipNo: String, val ship: String, val date: String, var need: Int,
                var hit: Boolean = false, var last: Int = -1, var at: Long = 0, val via: String = "phone")

    private fun prefs(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)
    private val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
    private val ymF = SimpleDateFormat("yyyyMM", Locale.KOREA)

    // ─────────────── 선단 · 즐겨찾기 · 알림 저장 ───────────────
    fun fleets(c: Context): MutableList<Fleet> = try {
        val a = JSONArray(prefs(c).getString("seat_fleets", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Fleet(o.optString("h"), o.optString("n"), o.optBoolean("on", true)) } }.toMutableList()
    } catch (_: Exception) { mutableListOf() }
    fun saveFleets(c: Context, l: List<Fleet>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("h", it.host).put("n", it.name).put("on", it.on)) }
        prefs(c).edit().putString("seat_fleets", a.toString()).apply()
    }
    fun favs(c: Context): MutableSet<String> = HashSet(prefs(c).getStringSet("seat_fav", emptySet()) ?: emptySet())
    fun setFav(c: Context, key: String, on: Boolean) {
        val s = favs(c); if (on) s.add(key) else s.remove(key); prefs(c).edit().putStringSet("seat_fav", s).apply()
    }
    fun watches(c: Context): MutableList<Watch> = try {
        val a = JSONArray(prefs(c).getString("seat_watch", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o ->
            Watch(o.optString("h"), o.optString("s"), o.optString("n"), o.optString("d"), o.optInt("need", 1),
                o.optBoolean("hit"), o.optInt("last", -1), o.optLong("at"), o.optString("via", "phone")) } }.toMutableList()
    } catch (_: Exception) { mutableListOf() }
    fun saveWatches(c: Context, l: List<Watch>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("h", it.host).put("s", it.shipNo).put("n", it.ship).put("d", it.date)
            .put("need", it.need).put("hit", it.hit).put("last", it.last).put("at", it.at).put("via", it.via)) }
        prefs(c).edit().putString("seat_watch", a.toString()).putInt("w_seat_n", l.count { it.hit }).apply()
        try { QuickWidget.updateAll(c) } catch (_: Exception) {}
    }
    fun watchOf(c: Context, s: Sch) = watches(c).firstOrNull { it.host == s.host && it.shipNo == s.shipNo && it.date == s.date }

    /** 'sungbin.sunsang24.com' · 'https://sungbin.sunsang24.com/ship/...' → 'sungbin.sunsang24.com' (선상24 아니면 null) */
    fun hostOf(raw: String): String? {
        val h = raw.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore('?').substringBefore('#').trim()
        return if (h.endsWith(".sunsang24.com") && h != "www.sunsang24.com" && h != "api.sunsang24.com" && h.count { it == '.' } == 2) h else null
    }
    /** v6.80 (385): 선상24 선단 또는 개인 배 사이트 주소 → 호스트 */
    fun anyHostOf(raw: String): String? {
        hostOf(raw)?.let { return it }
        val h = raw.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore('?').substringBefore('#').trim()
        return if (h.contains('.') && h.matches(Regex("[a-z0-9.-]+")) && !h.endsWith("sunsang24.com")) h else null
    }
    fun isS24Fleet(host: String) = host.endsWith(".sunsang24.com")
    /** 개인 사이트 미리 넣어 둔 것 (사용자가 알려 준 곳) */
    val PRESETS = listOf("hunter-booking.onrender.com" to "거북섬 헌터호")

    // ─────────────── 읽기 · 풀기 ───────────────
    private val mem = HashMap<String, Pair<Long, List<Sch>>>()
    private const val KEEP = 10 * 60 * 1000L

    private fun get(url: String): String {
        val cn = URL(url).openConnection() as HttpURLConnection
        // 개인 사이트(무료 서버)는 처음 깨어나는 데 오래 걸려서 넉넉히
        val slow = !url.contains("sunsang24.com")
        cn.connectTimeout = if (slow) 20000 else 8000; cn.readTimeout = if (slow) 40000 else 12000; cn.instanceFollowRedirects = true
        cn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Mobile")
        cn.setRequestProperty("Accept-Language", "ko-KR,ko")
        try {
            if (cn.responseCode !in 200..299) throw Exception("HTTP ${cn.responseCode}")
            return cn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally { cn.disconnect() }
    }

    /** 한 선단 · 한 달 (fresh = 기억 무시) · 실패하면 예외 */
    fun month(host: String, ym: String, fresh: Boolean = false): List<Sch> {
        val k = "${host}_$ym"
        if (!fresh) synchronized(mem) { mem[k]?.let { if (System.currentTimeMillis() - it.first < KEEP) return it.second } }
        val l = if (isS24Fleet(host)) parse(host, get("https://$host/ship/schedule_fleet/$ym")) else calSite(host, ym)
        synchronized(mem) { mem[k] = System.currentTimeMillis() to l }
        return l
    }

    // ─────────────── v6.80 (385): 개인 배 사이트 (달력 주소형 · 예: 헌터호 /api/calendar?program=&month=yyyy-MM) ───────────────
    private val siteMem = HashMap<String, Pair<Long, Pair<String, List<Pair<String, String>>>>>()
    /** 사이트 첫 화면에서 이름 · 프로그램(체험) 목록 */
    private fun siteInfo(host: String): Pair<String, List<Pair<String, String>>> {
        synchronized(siteMem) { siteMem[host]?.let { if (System.currentTimeMillis() - it.first < 86400000L) return it.second } }
        val html = get("https://$host/")
        val title = Regex("<title>([^<]*)</title>").find(html)?.groupValues?.get(1)?.replace("예약", "")?.trim()?.ifBlank { null } ?: host.substringBefore('.')
        val progs = Regex("""<option value="([^"]+)">([^<]+)""").findAll(html).map { it.groupValues[1] to it.groupValues[2].trim() }
            .filter { !it.first.contains("30분") }.distinctBy { it.first }.toList()
        val r = title to progs
        synchronized(siteMem) { siteMem[host] = System.currentTimeMillis() to r }
        return r
    }
    private fun calSite(host: String, ym: String): List<Sch> {
        val (title, progs) = siteInfo(host)
        if (progs.isEmpty()) throw Exception("이 사이트는 아직 못 읽어요")
        val month = ym.take(4) + "-" + ym.drop(4)
        val out = ArrayList<Sch>()
        progs.forEach { (v, label) ->
            val o = try { JSONObject(get("https://$host/api/calendar?program=${Uri.encode(v)}&month=$month")) } catch (_: Exception) { return@forEach }
            val days = o.optJSONArray("days") ?: return@forEach
            for (i in 0 until days.length()) {
                val d = days.getJSONObject(i)
                val st = d.optString("state")
                if (st == "지난날짜") continue
                val rem = d.optInt("remaining")
                val code = when (st) { "예약가능" -> if (rem > 0) "ING" else "END"; "예약마감" -> "END"; else -> "CLOSED" }
                out.add(Sch(host, v, "", title, d.optString("date"), label, "체험", "", if (code == "ING") rem else 0, 0, code, "", "개인 사이트"))
            }
        }
        return out
    }

    private val rxTide = Regex("""id="d(\d{4}-\d{2}-\d{2})"[\s\S]{0,600}?class="swell">([^<]*)<""")
    private val rxName = Regex("""<li class="title">\s*<span[^>]*>([^<]+)</span>""")
    private val rxFish = Regex("""<span class="fishspecies"><strong>([^<]*)</strong></span>\s*/\s*<span class="way"><strong>([^<]*)</strong>""")
    private val rxTime = Regex("""운항시간\s*:\s*</strong></span>\s*([^<]+)<""")
    private val rxTag = Regex("<[^>]+>")

    fun parse(host: String, html: String): List<Sch> {
        val tide = HashMap<String, String>()
        rxTide.findAll(html).forEach { tide[it.groupValues[1]] = it.groupValues[2].trim() }
        val out = ArrayList<Sch>()
        val parts = html.split("<div class=\"ship_unit_ship_no_")
        for (b in parts.drop(1)) {
            try {
                val date = Regex("""data-sdate="([^"]+)"""").find(b)?.groupValues?.get(1) ?: continue
                val shipNo = Regex("""data-ship_no="(\d+)"""").find(b)?.groupValues?.get(1) ?: continue
                val schNo = Regex("""data-schedule_no="(\d+)"""").find(b)?.groupValues?.get(1) ?: ""
                val name = rxName.find(b)?.groupValues?.get(1)?.trim()?.let { android.text.Html.fromHtml(it, 0).toString() } ?: "배"
                val fm = rxFish.findAll(b).lastOrNull()
                val time = rxTime.findAll(b).lastOrNull()?.groupValues?.get(1)?.replace("&nbsp;", " ")?.trim() ?: ""
                val si = b.indexOf("<li class=\"seat\">")
                val seatHtml = if (si >= 0) b.substring(si, minOf(b.length, b.indexOf("</li>", si).let { if (it < 0) b.length else it })) else ""
                val seat = rxTag.replace(seatHtml, " ").replace("&nbsp;", " ").replace(Regex("\\s+"), " ")
                val status = Regex("""data-status_code="(\w+)"""").find(seatHtml)?.groupValues?.get(1)
                    ?: Regex("""data-ship_status_code="(\w+)"""").find(seatHtml)?.groupValues?.get(1) ?: "ING"
                var remain = Regex("""남은자리\s*:\s*(\d+)""").find(seat)?.groupValues?.get(1)?.toInt() ?: 0
                var reserved = Regex("""예약\s*:\s*(\d+)""").find(seat)?.groupValues?.get(1)?.toInt() ?: 0
                if (status == "END") {   // '예약마감 20명 예약 : 20명' — 앞 숫자 = 정원
                    val capN = Regex("""(\d+)\s*명""").find(seat)?.groupValues?.get(1)?.toInt() ?: reserved
                    reserved = maxOf(reserved, capN); remain = 0
                }
                out.add(Sch(host, shipNo, schNo, name, date, fm?.groupValues?.get(1)?.trim() ?: "", fm?.groupValues?.get(2)?.trim() ?: "",
                    time, remain, reserved, status, tide[date] ?: ""))
            } catch (_: Exception) {}
        }
        return out
    }

    fun bookUrl(s: Sch) = if (s.host == S24) "https://www.sunsang24.com/mypage/reservation_ready/${s.shipNo}/${s.schNo}/${s.date}"
        else if (!isS24Fleet(s.host)) "https://${s.host}/#reserve"
        else "https://${s.host}/ship/schedule_fleet/${s.date.replace("-", "").take(6)}"
    fun bookUrl(w: Watch) = if (w.host == S24) "https://www.sunsang24.com/ship/list"
        else if (!isS24Fleet(w.host)) "https://${w.host}/#reserve"
        else "https://${w.host}/ship/schedule_fleet/${w.date.replace("-", "").take(6)}"

    // ─────────────── v6.78: 🌐 선상24 전체 (선상24 목록 주소 api.sunsang24.com · 공개 문서 없는 주소라 바뀌면 멈출 수 있음) ───────────────
    const val S24 = "s24"
    private const val API = "https://api.sunsang24.com"
    class S24Page(val total: Int, val list: List<Sch>)

    private fun hm(t: String) = t.take(5)
    private fun fromApi(o: JSONObject, date0: String = ""): Sch {
        val sh = o.optJSONObject("ship") ?: JSONObject()
        val cap = o.optInt("embarkation_num", sh.optInt("embarkation_num", 0))
        val remain = o.optInt("remain_embarkation_num", 0)
        val st = o.optString("schedule_status_code", o.optString("status_code", "ING"))
        val place = listOf(sh.optString("area_main") + " " + sh.optString("area_sub"), o.optString("port_name", sh.optString("port_name"))).map { it.trim() }.filter { it.isNotBlank() }.joinToString(" · ")
        val t = listOf(hm(o.optString("stime")), hm(o.optString("etime"))).filter { it.isNotBlank() }.joinToString(" ~ ")
        return Sch(S24, sh.optInt("no").toString(), o.optString("schedule_no"), sh.optString("name").trim(), o.optString("sdate", date0),
            o.optString("fish_type"), o.optString("fishing_method"), t, remain, maxOf(0, cap - remain),
            if (st == "ING" && remain <= 0) "END" else st, "", place, o.optInt("price"))
    }

    /** 날짜 · 지역(권역 이름) · 어종으로 한 쪽(30척) */
    fun s24Page(date: String, areas: Set<String>, fish: Set<String>, page: Int): S24Page {
        val q = StringBuilder("$API/ship/list?type=general&page=$page&sdate=$date,$date")
        if (areas.isNotEmpty()) q.append("&area=").append(Uri.encode(areas.joinToString(",")))
        if (fish.isNotEmpty()) q.append("&fish=").append(Uri.encode(fish.joinToString(",")))
        val o = JSONObject(get(q.toString()))
        val a = o.optJSONArray("list") ?: JSONArray()
        return S24Page(o.optInt("total"), (0 until a.length()).map { fromApi(a.getJSONObject(it), date) }.filter { it.date == date })
    }

    /** 한 배 · 한 달 (알림 확인용) */
    fun s24Month(shipNo: String, ym: String): List<Sch> {
        val a = JSONArray(get("$API/ship/schedule_fleet_list/$shipNo/$ym?simple=&possible=&eyyyymm="))
        return (0 until a.length()).map { fromApi(a.getJSONObject(it)) }
    }

    /** 선상24 거르기 목록 (어종 무리 · 권역) — 하루 기억 */
    class S24Filter(val fish: List<Pair<String, List<String>>>, val area: List<Pair<String, List<String>>>)
    @Volatile private var filterMem: Pair<Long, S24Filter>? = null
    fun s24Filter(c: Context): S24Filter? {
        filterMem?.let { if (System.currentTimeMillis() - it.first < 86400000L) return it.second }
        val raw = try { get("$API/ship/filter/").also { prefs(c).edit().putString("seat_s24f", it).apply() } }
            catch (_: Exception) { prefs(c).getString("seat_s24f", null) } ?: return null
        return try {
            val o = JSONObject(raw)
            fun grp(k: String) = (o.optJSONArray(k) ?: JSONArray()).let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { g ->
                val it2 = g.optJSONArray("items") ?: JSONArray()
                g.optString("title") to (0 until it2.length()).map { j -> it2.optString(j) }.filter { it.isNotBlank() && it != "전체" } } } }
            S24Filter(grp("fish"), grp("area")).also { filterMem = System.currentTimeMillis() to it }
        } catch (_: Exception) { null }
    }

    // ─────────────── v6.82 (390): 배 선단 사이트 · 밴드 · 전화 ───────────────
    // talla.co.kr/data/seat_sites.json (GitHub 'Seat sites' 가 하루 한 번 조황 글에서 모음) → 폰 파일 · 하루 한 번 새로
    // 파일에 없는 배만 폰이 직접 조황 글을 읽어 찾음 (prefs seat_site_<no> = host|band|tel|yyyyMMdd) · 직접 넣은 주소 = seat_site_m_<no>
    class ShipSite(val host: String?, val band: String?, val tel: String?)
    private const val SITES_URL = "https://talla.co.kr/data/seat_sites.json"
    @Volatile private var sitesMem: Map<String, ShipSite>? = null
    private val siteTried = HashSet<String>()
    private fun sitesFile(c: Context) = java.io.File(c.filesDir, "seat_sites.json")
    private fun parseSites(t: String): Map<String, ShipSite> {
        val o = JSONObject(t).optJSONObject("ships") ?: return emptyMap()
        val m = HashMap<String, ShipSite>()
        o.keys().forEach { k -> val v = o.optJSONObject(k) ?: return@forEach
            m[k] = ShipSite(v.optString("h").ifBlank { null }, v.optString("b").ifBlank { null }, v.optString("t").ifBlank { null }) }
        return m
    }
    fun sites(c: Context): Map<String, ShipSite> {
        sitesMem?.let { return it }
        val m = try { parseSites(sitesFile(c).readText()) } catch (_: Exception) { emptyMap() }
        sitesMem = m; return m
    }
    /** 하루 한 번 talla.co.kr 에서 새로 받기 (뒤에서) · 새로 받았으면 done */
    fun refreshSites(c: Context, done: () -> Unit) {
        val f = sitesFile(c)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 12 * 3600_000L) return
        Thread {
            try {
                val t = get(SITES_URL); val m = parseSites(t)
                if (m.isNotEmpty()) { f.writeText(t); sitesMem = m; done() }
            } catch (_: Exception) {}
        }.start()
    }
    fun siteOf(c: Context, shipNo: String): ShipSite? {
        val p = prefs(c)
        val base = p.getString("seat_site_$shipNo", null)?.split('|')?.let { ShipSite(it.getOrNull(0)?.ifBlank { null }, it.getOrNull(1)?.ifBlank { null }, it.getOrNull(2)?.ifBlank { null }) }
            ?: sites(c)[shipNo]
        val man = p.getString("seat_site_m_$shipNo", null)?.ifBlank { null }
        if (man == null) return base
        return ShipSite(man, base?.band, base?.tel)
    }
    fun needsLookup(c: Context, shipNo: String): Boolean {
        if (shipNo.isBlank() || synchronized(siteTried) { shipNo in siteTried }) return false
        val p = prefs(c)
        p.getString("seat_site_$shipNo", null)?.let { v ->
            val d = v.substringAfterLast('|')
            val keep = if (v.startsWith("|")) 7 else 30    // 못 찾음은 7일 뒤 다시
            val age = try { ((System.currentTimeMillis() - SimpleDateFormat("yyyyMMdd", Locale.KOREA).parse(d)!!.time) / 86400000L).toInt() } catch (_: Exception) { 99 }
            if (age < keep) return false
        }
        return sites(c)[shipNo]?.host == null
    }
    private val HOST_RE = Regex("([a-z0-9][a-z0-9-]{1,40})\\.sunsang24\\.com", RegexOption.IGNORE_CASE)
    private val BAND_RE = Regex("band\\.us/(@[A-Za-z0-9._-]+|band/\\d+|n/[A-Za-z0-9]+)", RegexOption.IGNORE_CASE)
    private val SKIP_HOST = setOf("imga", "imgb", "img", "assets", "www", "api", "service", "serviceback", "m", "cdn", "static", "admin", "partner", "pay")
    /** 폰이 직접: 조황 글 최근 3개 본문에서 선단 주소 · 밴드 + 전화 (선상24 배 정보) */
    fun findSite(c: Context, shipNo: String): ShipSite? {
        synchronized(siteTried) { siteTried.add(shipNo) }
        val hosts = HashMap<String, Int>(); val bands = HashMap<String, Int>()
        var tel: String? = null
        try { tel = JSONObject(get("$API/ship/simple_info/$shipNo")).optString("tel").trim().ifBlank { null } } catch (_: Exception) {}
        try {
            val l = JSONObject(get("$API/ship/fish_board_list?ship_no=$shipNo&page=1")).optJSONArray("list") ?: JSONArray()
            for (i in 0 until minOf(3, l.length())) {
                val t = try { get("$API/ship/fish_board_detail/" + l.getJSONObject(i).optString("no")).replace("\\/", "/") } catch (_: Exception) { continue }
                HOST_RE.findAll(t).forEach { val h = it.groupValues[1].lowercase(); if (h !in SKIP_HOST) hosts[h] = (hosts[h] ?: 0) + 1 }
                BAND_RE.findAll(t).forEach { val b = it.groupValues[1]; bands[b] = (bands[b] ?: 0) + 1 }
            }
        } catch (_: Exception) { return null }
        val host = hosts.maxByOrNull { it.value }?.key?.let { "$it.sunsang24.com" }
        val band = bands.maxByOrNull { it.value }?.key?.let { "band.us/$it" }
        prefs(c).edit().putString("seat_site_$shipNo", "${host ?: ""}|${band ?: ""}|${tel ?: ""}|" + SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(Date())).apply()
        return ShipSite(host, band, tel)
    }
    /** 선상24 카드라도 선단 사이트를 찾았으면 그 선단 예약 화면 (못 찾았으면 선상24 예약 창) */
    fun bookUrlOf(c: Context, s: Sch): String =
        if (s.host == S24) siteOf(c, s.shipNo)?.host?.let { siteUrl(it, s.date) } ?: bookUrl(s) else bookUrl(s)
    fun siteUrl(host: String, date: String) = "https://$host/ship/schedule_fleet/" + (date.replace("-", "").take(6).ifBlank { ymF.format(Date()) })

    // ─────────────── 🔔 자리 나면 알림 (15분마다 · 내 폰) ───────────────
    /** v6.76: 알림은 관리자가 체크한 사람만 (Cloud.PERMS 'seatAlarm') · 뒤에서 확인할 땐 마지막으로 본 권한(prefs seat_perm) */
    const val PERM = "seatAlarm"
    fun allowed(c: Context): Boolean = if (Cloud.isAdmin || Cloud.myPerms.isNotEmpty()) Cloud.can(PERM) else prefs(c).getBoolean("seat_perm", false)
    /** 권한 다시 받아 기억 · 없어졌으면 걸어 둔 알림 지움 */
    fun syncPerm(c: Context, done: () -> Unit) {
        fun apply() {
            val ok = Cloud.can(PERM)
            prefs(c).edit().putBoolean("seat_perm", ok).apply()
            if (!ok && watches(c).isNotEmpty()) { saveWatches(c, emptyList()); schedule(c) }
            done()
        }
        if (Cloud.uid == null) { apply(); return }
        try { Cloud.loadMyPerms { apply() } } catch (_: Exception) { apply() }
    }

    private const val ACT = "com.fishlog.app.SEAT_ALARM"
    private const val CH = "seat_open"
    const val EVERY = 15 * 60 * 1000L

    fun schedule(c: Context) {
        try {
            val am = c.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(c, 7601, Intent(c, SeatAlarmReceiver::class.java).setAction(ACT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if (watches(c).none { it.via == "phone" } || !prefs(c).getBoolean("seat_perm", false)) { am.cancel(pi); return }
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + EVERY, pi)
        } catch (_: Exception) {}
    }

    private fun channel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "선상 빈자리 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "알림 걸어 둔 배에 자리가 나면 바로 알려 줘요"; enableVibration(true) })
    }

    /** 걸어 둔 알림 확인 · 지난 날짜는 지움 · 새로 자리 난 수 돌려줌 */
    fun check(c: Context): Int {
        if (!prefs(c).getBoolean("seat_perm", false)) return 0
        val today = ymd.format(Date())
        val ws = watches(c).filter { it.date >= today }.toMutableList()
        var fired = 0
        val byMonth = ws.filter { it.via == "phone" }.groupBy { it.host to it.date.replace("-", "").take(6) }
        for ((k, list) in byMonth) {
            if (k.first == S24) {
                for (w in list) {
                    val sch = try { s24Month(w.shipNo, k.second) } catch (_: Exception) { continue }
                    val s = sch.firstOrNull { it.date == w.date } ?: continue
                    val ok = s.open && s.remain >= w.need
                    if (ok && !w.hit) { notify(c, w, s); fired++ }
                    w.hit = ok; w.last = s.remain; w.at = System.currentTimeMillis()
                }
                continue
            }
            val sch = try { month(k.first, k.second, fresh = true) } catch (_: Exception) { continue }
            for (w in list) {
                val s = sch.firstOrNull { it.shipNo == w.shipNo && it.date == w.date } ?: continue
                val ok = s.open && s.remain >= w.need
                if (ok && !w.hit) { notify(c, w, s); fired++ }
                w.hit = ok; w.last = s.remain; w.at = System.currentTimeMillis()
            }
        }
        saveWatches(c, ws)
        prefs(c).edit().putLong("seat_check_at", System.currentTimeMillis()).apply()
        return fired
    }

    private fun notify(c: Context, w: Watch, s: Sch) {
        channel(c)
        val req = 7700 + (w.host + w.shipNo + w.date).hashCode().and(0xFFF)
        val pi = PendingIntent.getActivity(c, req, Intent(Intent.ACTION_VIEW, Uri.parse(if (w.host == S24) bookUrlOf(c, s) else bookUrl(w)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val d = dayLabel(w.date)
        val title = "🚤 $d ${w.ship} 자리 났어요!"
        val text = "남은 ${s.remain}자리 (필요 ${w.need}자리) · ${listOf(s.fish, s.time).filter { it.isNotBlank() }.joinToString(" · ")} · 눌러서 바로 예약"
        val b = Notification.Builder(c, CH).setSmallIcon(R.drawable.ic_stat_octo)
            .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi).setAutoCancel(true).setShowWhen(true).setCategory(Notification.CATEGORY_REMINDER)
        try { c.getSystemService(NotificationManager::class.java).notify(req, b.build()) } catch (_: SecurityException) {}
    }

    fun dayLabel(date: String): String = try {
        val d = ymd.parse(date)!!
        SimpleDateFormat("M/d(E)", Locale.KOREA).format(d)
    } catch (_: Exception) { date }

    // ─────────────── 화면 ───────────────
    private const val CEPH = "문어 주꾸미 쭈꾸미 갑오징어 무늬오징어 한치 오징어 낙지 화살촉오징어 호래기 갑오"
    private val NEEDS = intArrayOf(1, 2, 3, 4, 6)

    fun show(a: Activity) {
        fun dp(v: Int) = Ui.dp(a, v)
        val p = prefs(a)
        val dlg = Dialog(a, R.style.FlFull)
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0B161E")) }

        var day = p.getString("seat_day", null)?.takeIf { it >= ymd.format(Date()) } ?: ymd.format(Date())
        var need = p.getInt("seat_need", 1)
        var sort = p.getInt("seat_sort", 0)          // 0 자리 많은 순 · 1 출항 빠른 순 · 2 이름 순
        var openOnly = p.getBoolean("seat_open", false)
        var favTop = p.getBoolean("seat_favtop", true)
        val fishSel = HashSet(p.getStringSet("seat_fish", emptySet()) ?: emptySet())
        val all = ArrayList<Sch>()
        // v6.78: 🌐 선상24 전체 (고른 날짜 · 지역 · 어종 · 30척씩)
        var useS24 = p.getBoolean("seat_s24", true)
        val areaSel = HashSet(p.getStringSet("seat_area", emptySet()) ?: emptySet())
        val s24 = ArrayList<Sch>()
        var s24Key = ""; var s24Total = 0; var s24Pg = 0; var s24Busy = false; var s24Err = false
        Thread { try { s24Filter(a) } catch (_: Exception) {} }.start()
        val fails = ArrayList<String>()
        var loading = false
        var siteBusy = false

        // 제목 줄
        val head = Ui.row(a).apply { setPadding(dp(16), dp(8), dp(12), dp(4)) }
        head.addView(Ui.text(a, 18f, Color.WHITE, true, "🚤 선상 빈자리"), LinearLayout.LayoutParams(0, WRAP, 1f))
        val alarmBtn = Ui.pill(a, "🔔 0", false, Ui.TEXT, small = true)
        head.addView(alarmBtn, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
        val fleetBtn = Ui.pill(a, "⚙ 선단", false, Ui.TEXT, small = true)
        head.addView(fleetBtn, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
        head.addView(Ui.pill(a, "닫기", false, Ui.TEXT, small = true) { dlg.dismiss() })
        root.addView(head)

        // v6.77: 날짜 = ‹ [📅 11월 24일 (화) ▾] › · 가운데 누르면 달력 시트
        val dateRow = Ui.row(a).apply { setPadding(dp(12), dp(4), dp(12), dp(8)) }
        val prevBtn = Ui.pill(a, "‹", false, Ui.TEXT)
        val dayBtn = Ui.pill(a, "", true, Ui.GROUP).apply { textSize = 15.5f }
        val nextBtn = Ui.pill(a, "›", false, Ui.TEXT)
        dateRow.addView(prevBtn, LinearLayout.LayoutParams(dp(52), WRAP))
        dateRow.addView(dayBtn, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
        dateRow.addView(nextBtn, LinearLayout.LayoutParams(dp(52), WRAP))
        root.addView(dateRow)
        // 고르기 줄
        val pick = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; setPadding(dp(10), 0, dp(10), dp(6)) }
        root.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(pick) })
        val areaBtn = Ui.pill(a, "📍 지역 전체 ▾", false, Ui.TEXT, small = true)
        val fishBtn = Ui.pill(a, "🐟 어종 전체 ▾", false, Ui.TEXT, small = true)
        val needBtn = Ui.pill(a, "👥 1자리 이상 ▾", false, Ui.TEXT, small = true)
        val filtBtn = Ui.pill(a, "⚙ 필터", false, Ui.TEXT, small = true)
        listOf(areaBtn, fishBtn, needBtn, filtBtn).forEach { pick.addView(it, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) }) }
        val sum = Ui.text(a, 12f, Ui.SUB, false, "").apply { setPadding(dp(16), 0, dp(16), dp(4)) }
        root.addView(sum)
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(24)) }
        root.addView(ScrollView(a).apply { addView(list) }, LinearLayout.LayoutParams(MATCH, 0, 1f))

        lateinit var render: () -> Unit
        lateinit var load: (Boolean) -> Unit
        lateinit var loadS24: (Boolean) -> Unit
        refreshSites(a) { a.runOnUiThread { if (dlg.isShowing) render() } }

        fun savePrefs() = p.edit().putBoolean("seat_s24", useS24).putStringSet("seat_area", HashSet(areaSel)).putString("seat_day", day).putInt("seat_need", need).putInt("seat_sort", sort)
            .putBoolean("seat_open", openOnly).putBoolean("seat_favtop", favTop).putStringSet("seat_fish", HashSet(fishSel)).apply()

        fun fishMatch(s: Sch) = fishSel.isEmpty() || fishSel.any { f -> s.fish.split(',', '/', '·', ' ').any { it.trim() == f } || s.fish.contains(f) }
        fun fishList(s: Sch) = s.fish.split(',', '/', '·').map { it.trim() }.filter { it.isNotBlank() }

        fun countOn(d: String) = all.count { x -> x.date == d && x.open && x.remain >= need && fishMatch(x) }
        fun setDay(d: String) { day = d; savePrefs(); render(); load(false) }
        fun shift(n: Int) {
            val c = Calendar.getInstance().apply { time = ymd.parse(day) ?: Date(); add(Calendar.DAY_OF_MONTH, n) }
            val d = ymd.format(c.time); if (d >= ymd.format(Date())) setDay(d)
        }
        fun paintDates() {
            val dd = try { ymd.parse(day)!! } catch (_: Exception) { Date() }
            val n = countOn(day)
            dayBtn.text = "📅 " + SimpleDateFormat("M월 d일 (E)", Locale.KOREA).format(dd) + (if (all.isNotEmpty()) " · 빈 배 ${n}척" else "") + " ▾"
            prevBtn.alpha = if (day > ymd.format(Date())) 1f else 0.35f
        }
        fun openCalendar() {
            val sh = Sheet(a, 0.72f)
            val today = ymd.format(Date())
            val cur = Calendar.getInstance().apply { time = ymd.parse(day) ?: Date(); set(Calendar.DAY_OF_MONTH, 1) }
            val title = Ui.text(a, 17f, Ui.TEXT, true, "")
            val prevM = Ui.pill(a, "‹", false, Ui.TEXT, small = true); val nextM = Ui.pill(a, "›", false, Ui.TEXT, small = true)
            sh.head.addView(prevM); sh.head.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(10) })
            title.gravity = Gravity.CENTER
            sh.head.addView(nextM, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(10) })
            fun draw() {
                sh.body.removeAllViews()
                title.text = SimpleDateFormat("yyyy년 M월", Locale.KOREA).format(cur.time)
                val thisM = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
                prevM.alpha = if (cur.after(thisM)) 1f else 0.35f
                val wk = Ui.row(a)
                arrayOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { i, t ->
                    wk.addView(Ui.text(a, 12.5f, when (i) { 0 -> Color.parseColor("#FF8A8A"); 6 -> Color.parseColor("#7FB8FF"); else -> Ui.SUB }, true, t).apply { gravity = Gravity.CENTER },
                        LinearLayout.LayoutParams(0, WRAP, 1f))
                }
                sh.body.addView(wk, Ui.lp(a, MATCH, WRAP, top = 2))
                val c = cur.clone() as Calendar
                val lead = c.get(Calendar.DAY_OF_WEEK) - 1
                val days = c.getActualMaximum(Calendar.DAY_OF_MONTH)
                val loadedMonth = all.any { it.date.take(7) == ymd.format(c.time).take(7) }
                var cell = 0
                var row = Ui.row(a)
                fun flush() { sh.body.addView(row, Ui.lp(a, MATCH, WRAP, top = 4)); row = Ui.row(a) }
                repeat(lead) { row.addView(View(a), LinearLayout.LayoutParams(0, dp(54), 1f)); cell++ }
                for (dn in 1..days) {
                    c.set(Calendar.DAY_OF_MONTH, dn)
                    val d = ymd.format(c.time)
                    val past = d < today
                    val on = d == day
                    val n = countOn(d)
                    val dow = c.get(Calendar.DAY_OF_WEEK)
                    val box = LinearLayout(a).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                        background = when { on -> Ui.bg(a, Ui.GROUP, 14, Color.parseColor("#7FD6FF")); d == today -> Ui.bg(a, Color.TRANSPARENT, 14, Color.parseColor("#F2C14E")); else -> null }
                        alpha = if (past) 0.3f else 1f
                        if (!past) { isClickable = true; setOnClickListener { sh.close(); setDay(d) } }
                    }
                    box.addView(Ui.text(a, 16f, when { on -> Color.WHITE; dow == Calendar.SUNDAY -> Color.parseColor("#FF8A8A"); dow == Calendar.SATURDAY -> Color.parseColor("#7FB8FF"); else -> Ui.TEXT },
                        true, "$dn").apply { gravity = Gravity.CENTER; includeFontPadding = false })
                    box.addView(Ui.text(a, 10f, when { on -> Color.WHITE; n > 0 -> Color.parseColor("#2FBF8F"); else -> Color.parseColor("#4A5966") }, n > 0,
                        when { past -> ""; !loadedMonth -> "·"; n > 0 -> "${n}척"; else -> "-" }).apply { gravity = Gravity.CENTER })
                    row.addView(box, LinearLayout.LayoutParams(0, dp(54), 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
                    if (++cell % 7 == 0) flush()
                }
                if (cell % 7 != 0) { repeat(7 - cell % 7) { row.addView(View(a), LinearLayout.LayoutParams(0, dp(54), 1f)) }; flush() }
                sh.body.addView(Ui.text(a, 11.5f, Ui.SUB, false,
                    if (loadedMonth) "초록 숫자 = 고른 어종 · ${need}자리 이상 빈 배 수 · 노란 테두리 = 오늘" else "이 달은 아직 안 읽었어요 · 날짜를 누르면 읽어 와요").apply { setPadding(dp(4), dp(10), 0, 0) })
            }
            prevM.setOnClickListener { val t = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }; if (cur.after(t)) { cur.add(Calendar.MONTH, -1); draw() } }
            nextM.setOnClickListener { cur.add(Calendar.MONTH, 1); draw() }
            draw(); sh.show()
        }

        fun card(s: Sch, fav: Boolean): View {
            val w = watchOf(a, s)
            val box = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
                background = Ui.bg(a, Color.parseColor("#12283A"), 14, if (fav) Color.parseColor("#F2C14E") else Color.parseColor("#2C4F66"))
                alpha = if (s.open) 1f else 0.6f
            }
            val top = Ui.row(a)
            val fleetName = if (s.host == S24) s.place.ifBlank { "선상24" } else fleets(a).firstOrNull { it.host == s.host }?.name ?: s.host.substringBefore('.')
            top.addView(TextView(a).apply {
                val sb = android.text.SpannableStringBuilder(s.ship)
                sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length, 0)
                sb.append("  ")
                val st = sb.length; sb.append(fleetName)
                sb.setSpan(android.text.style.ForegroundColorSpan(Ui.SUB), st, sb.length, 0)
                sb.setSpan(android.text.style.RelativeSizeSpan(0.78f), st, sb.length, 0)
                text = sb; textSize = 15.5f; setTextColor(Ui.TEXT); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            top.addView(Ui.text(a, 22f, if (fav) Color.parseColor("#F2C14E") else Color.parseColor("#4A5966"), false, if (fav) "★" else "☆").apply {
                gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(2), 0)
                setOnClickListener {
                    setFav(a, s.key, !fav)
                    Ui.toast(a, if (!fav) "⭐ ${s.ship} 즐겨찾기 · 맨 위에 보여요" else "${s.ship} 즐겨찾기 뺌")
                    render()
                }
            })
            box.addView(top)
            box.addView(Ui.text(a, 12f, Color.parseColor("#B8CAD6"), false,
                listOf(s.fish, s.way, s.time, s.tide, if (s.price > 0) "%,d원".format(s.price) else "").filter { it.isNotBlank() }.joinToString(" · ")).apply { maxLines = 2 })
            // 막대
            val bar = LinearLayout(a).apply { background = Ui.bg(a, Color.parseColor("#22384A"), 99) }
            val frac = if (s.cap > 0) s.remain.toFloat() / s.cap else 0f
            val col = when { !s.open -> Color.parseColor("#4A5966"); s.remain <= 3 -> Color.parseColor("#F2C14E"); else -> Color.parseColor("#2FBF8F") }
            bar.addView(View(a).apply { background = Ui.bg(a, col, 99) }, LinearLayout.LayoutParams(0, MATCH, frac.coerceIn(0f, 1f)))
            bar.addView(View(a), LinearLayout.LayoutParams(0, MATCH, (1f - frac).coerceIn(0f, 1f) + 0.0001f))
            box.addView(bar, LinearLayout.LayoutParams(MATCH, dp(7)).apply { topMargin = dp(7); bottomMargin = dp(5) })
            val bot = Ui.row(a)
            bot.addView(TextView(a).apply {
                val sb = android.text.SpannableStringBuilder()
                val head2 = if (s.open) "${s.remain}자리" else s.stName
                sb.append(head2)
                sb.setSpan(android.text.style.ForegroundColorSpan(col), 0, sb.length, 0)
                sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length, 0)
                sb.setSpan(android.text.style.RelativeSizeSpan(1.2f), 0, sb.length, 0)
                val tail = when {
                    s.status != "ING" && s.status != "END" -> ""
                    !s.open -> " · 예약 ${s.reserved}/${s.cap}"
                    s.cap <= s.remain -> " 남음"
                    s.remain <= 3 -> " 남음 · 곧 마감"
                    else -> " 남음 · 예약 ${s.reserved}/${s.cap}"
                }
                sb.append(tail); text = sb; textSize = 12.5f; setTextColor(Ui.TEXT)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            val canBook = s.open && s.remain >= need
            val ss = if (s.host == S24 || isS24Fleet(s.host)) siteOf(a, s.shipNo) else null
            if (canBook) {
                // v6.84: 선단 사이트를 찾은 배는 '예약 ›' = 그 선단 예약 화면 (내 선단에 넣은 배처럼 · 선상24 창은 꾹 메뉴)
                bot.addView(Ui.pill(a, "예약 ›", true, Ui.GROUP, small = true) { Boats.openUrl(a, bookUrlOf(a, s)) })
            } else if ((s.status == "ING" || s.status == "END") && allowed(a)) {
                val label = if (w != null) "🔔 알림 켜짐 · ${w.need}자리" else "🔔 자리 나면 알림"
                bot.addView(Ui.pill(a, label, w != null, Color.parseColor("#F2C14E"), small = true) {
                    if (w != null) askWatchOff(a, w) { render() } else addWatch(a, s, need) { render() }
                })
            } else if (s.status == "ING" || s.status == "END") {
                bot.addView(Ui.pill(a, "🔒 알림", false, Ui.TEXT, small = true) {
                    Ui.toast(a, "🔔 '자리 나면 알림'은 관리자가 허락한 분만 쓸 수 있어요 · ✉ 1:1 문의로 요청해 주세요")
                }.apply { alpha = 0.7f })
            }
            box.addView(bot)
            // v6.82 (390): 🌐 배 사이트 · 📞 전화 · 밴드 (선상24 배 번호가 있는 카드)
            if (ss != null && (ss.host != null || ss.tel != null || ss.band != null)) {
                val links = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL }
                fun link(t: String, f: () -> Unit) = links.addView(Ui.pill(a, t, false, Ui.TEXT, small = true) { f() }.apply { setTextColor(Color.parseColor("#7FD6FF")) },
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
                if (ss.host != null && s.host == S24 && !canBook) link("🌐 배 사이트") { Boats.openUrl(a, siteUrl(ss.host, s.date)) }
                if (ss.tel != null) link("📞 전화") { dial(a, ss.tel) }
                if (ss.band != null) link("밴드") { Boats.openUrl(a, ss.band) }
                box.addView(links, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
            }
            box.setOnLongClickListener { shipMenu(a, s, { render() }) { load(true) }; true }
            return box
        }

        render = render@{
            paintDates()
            fishBtn.text = when (fishSel.size) { 0 -> "🐟 어종 전체 ▾"; 1 -> "🐟 ${fishSel.first()} ▾"; else -> "🐟 ${fishSel.first()} 외 ${fishSel.size - 1} ▾" }
            fishBtn.setTextColor(if (fishSel.isEmpty()) Ui.TEXT else Color.parseColor("#7FD6FF"))
            areaBtn.text = when (areaSel.size) { 0 -> "📍 지역 전체 ▾"; 1 -> "📍 ${areaSel.first()} ▾"; else -> "📍 ${areaSel.first()} 외 ${areaSel.size - 1} ▾" }
            areaBtn.setTextColor(if (areaSel.isEmpty()) Ui.TEXT else Color.parseColor("#7FD6FF"))
            areaBtn.visibility = if (useS24) View.VISIBLE else View.GONE
            needBtn.text = "👥 ${need}자리 이상 ▾"
            needBtn.setTextColor(if (need > 1) Color.parseColor("#7FD6FF") else Ui.TEXT)
            filtBtn.text = "⚙ " + arrayOf("자리 많은 순", "출항 빠른 순", "이름 순")[sort] + (if (openOnly) " · 빈자리만" else "")
            alarmBtn.visibility = if (allowed(a)) View.VISIBLE else View.GONE
            val ws = watches(a)
            alarmBtn.text = if (ws.any { it.hit }) "🔔 ${ws.size} · 자리 남!" else "🔔 ${ws.size}"
            alarmBtn.setTextColor(if (ws.any { it.hit }) Color.parseColor("#FF6B6B") else Ui.TEXT)
            list.removeAllViews()
            val fl = fleets(a).filter { it.on }
            if (fleets(a).isEmpty() && !useS24) {
                list.addView(Ui.text(a, 14f, Ui.TEXT, false, "아직 볼 선단이 없어요.\n\n[⚙ 선단]에서 선상24 선단 주소를 넣어 주세요.\n예) sungbin.sunsang24.com").apply { setPadding(dp(6), dp(20), dp(6), dp(10)) })
                list.addView(Ui.button(a, "＋ 선단 추가") { openFleets(a) { load(true) } }, Ui.lp(a, MATCH, WRAP, top = 8))
                sum.text = ""; return@render
            }
            val mineDay = all.filter { it.date == day && it.host in fl.map { f -> f.host } }
            val mineNos = mineDay.map { it.shipNo }.toSet()
            var day0 = mineDay + (if (useS24) s24.filter { it.date == day && it.shipNo !in mineNos } else emptyList())
            val total = day0.size
            day0 = day0.filter { fishMatch(it) }
            val openN = day0.count { it.open && it.remain >= need }
            if (openOnly) day0 = day0.filter { it.open && it.remain >= need }
            day0 = when (sort) {
                1 -> day0.sortedWith(compareBy({ !it.open }, { it.time.ifBlank { "99" } }, { it.ship }))
                2 -> day0.sortedBy { it.ship }
                else -> day0.sortedWith(compareBy({ !(it.open && it.remain >= need) }, { -it.remain }, { it.ship }))
            }
            val fv = favs(a)
            sum.text = (if (loading || s24Busy) "⏳ 읽는 중 · " else "") + "${dayLabel(day)} · 배 ${total}척" +
                (if (useS24 && s24Total > s24.size) " (선상24 ${s24Total}척 중 ${s24.size}척 읽음)" else "") + " · ${need}자리 이상 빈 배 ${openN}척" +
                (if (fails.isNotEmpty()) " · ⚠ 못 읽은 선단 ${fails.size}곳" else "") + (if (s24Err) " · ⚠ 선상24 목록을 못 읽었어요" else "")
            if (day0.isEmpty()) {
                list.addView(Ui.text(a, 13.5f, Ui.SUB, false, if (loading || s24Busy) "선상24에서 읽는 중…" else if (total == 0) "이 날 나가는 배가 없어요 (선단이 일정을 아직 안 올렸을 수 있어요)" else "고른 조건에 맞는 배가 없어요").apply { setPadding(dp(6), dp(20), dp(6), 0) })
                return@render
            }
            fun section(t: String, c: Int) = list.addView(Ui.text(a, 12f, c, true, t).apply { setPadding(dp(4), dp(8), 0, dp(2)) })
            val favL = if (favTop) day0.filter { it.key in fv } else emptyList()
            val rest = day0.filter { it !in favL }
            if (favL.isNotEmpty()) { section("⭐ 즐겨찾기", Color.parseColor("#F2C14E")); favL.forEach { list.addView(card(it, true), Ui.lp(a, MATCH, WRAP, top = 6)) } }
            if (rest.isNotEmpty()) {
                if (favL.isNotEmpty()) section("그 밖의 배", Ui.SUB)
                rest.forEach { list.addView(card(it, it.key in fv), Ui.lp(a, MATCH, WRAP, top = 6)) }
            }
            // v6.82 (390): 배 사이트 목록에 없는 배만 폰이 조황 글에서 찾기 (한 번에 6척까지)
            val miss = (favL + rest).filter { it.host == S24 && needsLookup(a, it.shipNo) }.map { it.shipNo }.distinct().take(6)
            if (miss.isNotEmpty() && !siteBusy) {
                siteBusy = true
                miss.forEach { n -> synchronized(siteTried) { siteTried.add(n) } }
                Thread {
                    var got = 0
                    miss.forEach { n -> if (findSite(a, n)?.let { it.host != null || it.tel != null } == true) got++ }
                    a.runOnUiThread { siteBusy = false; if (dlg.isShowing && got > 0) render() }
                }.start()
            }
            if (useS24 && s24Total > s24.size) list.addView(Ui.button(a, if (s24Busy) "⏳ 읽는 중…" else "🌐 선상24 더 보기 (${s24.size} / ${s24Total}척)") { if (!s24Busy) loadS24(false) }.apply { minHeight = dp(46); textSize = 14f }, Ui.lp(a, MATCH, WRAP, top = 10))
            list.addView(Ui.text(a, 11f, Ui.SUB, false, "선상24 목록 · 선단 페이지를 내 폰이 읽어 와요 · 정렬은 읽어 온 배 안에서 · 예약 › = 그 배 선단 홈페이지(조황 글에서 찾음 · 못 찾으면 선상24) · 카드 꾹 = 메뉴(선상24 예약 창 · 내 선단에 넣기 · 주소 직접 넣기 · 검색)").apply { setPadding(dp(4), dp(14), dp(4), 0) })
        }

        loadS24 = { reset ->
            if (useS24) {
                val key = "$day|${areaSel.sorted()}|${fishSel.sorted()}"
                if (reset || key != s24Key) { s24.clear(); s24Total = 0; s24Pg = 0; s24Key = key }
                if (s24Pg == 0 || s24.size < s24Total) {
                    s24Busy = true; s24Err = false; render()
                    val d0 = day; val ar = HashSet(areaSel); val fs = HashSet(fishSel); val k0 = s24Key
                    val first = s24Pg + 1
                    Thread {
                        val got = ArrayList<Sch>(); var tot = -1; var err = false; var pg = first
                        // 처음엔 3쪽(90척) · 더 보기 = 3쪽씩
                        try { repeat(3) { val r = s24Page(d0, ar, fs, pg); tot = r.total; got.addAll(r.list); if (r.list.isEmpty()) return@repeat; pg++ } } catch (_: Exception) { err = got.isEmpty() }
                        a.runOnUiThread {
                            if (!dlg.isShowing || k0 != s24Key) return@runOnUiThread
                            val have = s24.map { it.shipNo + it.schNo }.toHashSet()
                            s24.addAll(got.filter { have.add(it.shipNo + it.schNo) })
                            if (tot >= 0) s24Total = tot
                            if (got.isEmpty() && !err) s24Total = s24.size
                            s24Pg = pg - 1; s24Busy = false; s24Err = err; render()
                        }
                    }.start()
                }
            }
        }

        load = { fresh ->
            loadS24(fresh)
            val fl = fleets(a).filter { it.on }
            val months = linkedSetOf(day.replace("-", "").take(6), ymF.format(Date()))
            if (fl.isNotEmpty()) {
                loading = true; render()
                Thread {
                    val got = ArrayList<Sch>(); val bad = ArrayList<String>()
                    val ths = fl.flatMap { f -> months.map { m -> Thread { try { val l = month(f.host, m, fresh); synchronized(got) { got.addAll(l) } } catch (_: Exception) { synchronized(bad) { if (f.name !in bad) bad.add(f.name) } } } } }
                    ths.forEach { it.start() }; ths.forEach { try { it.join(20000) } catch (_: Exception) {} }
                    a.runOnUiThread {
                        if (!dlg.isShowing) return@runOnUiThread
                        all.removeAll { s -> got.any { it.host == s.host && it.date.take(7) == s.date.take(7) } }
                        all.addAll(got); fails.clear(); fails.addAll(bad); loading = false; render()
                    }
                }.start()
            }
        }

        // v6.82 (389): 여러 개 고르기 — 눌러도 창은 그대로 · [✓ 보기] 또는 창 닫을 때 한 번에 적용
        fun multiSheet(title: String, cur: HashSet<String>, apply: (Set<String>) -> Unit): Triple<Sheet, (LinearLayout, String, List<String>, Map<String, Int>) -> Unit, () -> Unit> {
            val sh = Sheet(a, 0.7f)
            val sel = HashSet(cur)
            val tvs = HashMap<String, MutableList<TextView>>()
            val grpBtns = ArrayList<Pair<TextView, List<String>>>()   // 무리마다 '전체' (서해안 전체 등)
            val cnt = Ui.text(a, 12.5f, Ui.SUB, false, "")
            sh.head.addView(Ui.text(a, 17f, Ui.TEXT, true, title), LinearLayout.LayoutParams(WRAP, WRAP))
            sh.head.addView(cnt, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
            val allBtn = Ui.pill(a, "전체", false, Ui.MINE, small = true)
            val okBtn = Ui.pill(a, "", true, Ui.GROUP, small = true)
            sh.head.addView(okBtn)
            var done = false
            fun finish() { if (done) return; done = true; if (sel != cur) apply(HashSet(sel)) }
            fun paint() {
                tvs.forEach { (k, l) -> l.forEach { tv ->
                    val on = k in sel
                    tv.background = if (on) Ui.bg(a, Ui.MINE, 99) else Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE)
                    tv.setTextColor(if (on) Ui.BG else Ui.TEXT)
                } }
                grpBtns.forEach { (b, l) ->
                    val on = l.isNotEmpty() && sel.containsAll(l)
                    b.background = if (on) Ui.bg(a, Ui.MINE, 99) else Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE)
                    b.setTextColor(if (on) Ui.BG else Color.parseColor("#7FD6FF"))
                }
                allBtn.background = if (sel.isEmpty()) Ui.bg(a, Ui.MINE, 99) else Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE)
                allBtn.setTextColor(if (sel.isEmpty()) Ui.BG else Ui.TEXT)
                cnt.text = if (sel.isEmpty()) "전체" else "${sel.size}개 고름"
                okBtn.text = if (sel.isEmpty()) "✓ 전체 보기" else "✓ ${sel.size}개 보기"
            }
            allBtn.setOnClickListener { sel.clear(); paint() }
            okBtn.setOnClickListener { sh.close() }
            sh.onClose = { finish() }
            sh.dlg.setOnDismissListener { finish() }
            sh.body.addView(allBtn, LinearLayout.LayoutParams(WRAP, WRAP))
            val chips: (LinearLayout, String, List<String>, Map<String, Int>) -> Unit = { box, t, l, nums ->
                val hr = Ui.row(a).apply { setPadding(0, dp(14), 0, dp(6)) }
                hr.addView(Ui.text(a, 13.5f, Ui.TEXT, true, t), LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })
                if (l.size > 1) {
                    val gb = Ui.pill(a, "$t 전체", false, Ui.MINE, small = true) {
                        if (sel.containsAll(l)) sel.removeAll(l.toSet()) else sel.addAll(l); paint()
                    }
                    grpBtns.add(gb to l); hr.addView(gb)
                }
                box.addView(hr)
                val wrap = FlowLayout(a)
                l.forEach { f ->
                    val tv = Ui.pill(a, f + (nums[f]?.let { " $it" } ?: ""), false, Ui.MINE, small = true) { if (f in sel) sel.remove(f) else sel.add(f); paint() }
                    tvs.getOrPut(f) { ArrayList() }.add(tv)
                    wrap.addView(tv)
                }
                box.addView(wrap)
            }
            return Triple(sh, chips, { paint() })
        }

        fishBtn.setOnClickListener {
            val names = all.filter { it.date >= ymd.format(Date()) }.flatMap { fishList(it) }.groupingBy { it }.eachCount()
            val (sh, chipsF, paint) = multiSheet("🐟 어종", fishSel) { v -> fishSel.clear(); fishSel.addAll(v); savePrefs(); render(); loadS24(true) }
            fun chips(t: String, l: List<String>) = chipsF(sh.body, t, l, names)
            val sf = if (useS24) filterMem?.second else null
            if (sf != null && sf.fish.isNotEmpty()) {
                val shown = HashSet<String>()
                sf.fish.forEach { (t, l) -> if (l.isNotEmpty()) { chips(t, l); shown.addAll(l) } }
                val extra = names.keys.filter { it !in shown }
                if (extra.isNotEmpty()) chips("내 선단에 있는 어종", extra)
                sh.body.addView(Ui.text(a, 11.5f, Ui.SUB, false, "여러 개 눌러 고르고 [✓ 보기] · 창을 닫아도 고른 대로 보여요").apply { setPadding(0, dp(12), 0, 0) })
                paint(); sh.show(); return@setOnClickListener
            }
            val ceph = names.keys.filter { n -> CEPH.split(' ').any { n.contains(it) } }.sortedByDescending { names[it] }
            val fish = names.keys.filter { it !in ceph }.sortedByDescending { names[it] }
            if (names.isEmpty()) sh.body.addView(Ui.text(a, 13f, Ui.SUB, false, "선단 일정을 다 읽으면 어종이 나와요").apply { setPadding(0, dp(12), 0, 0) })
            if (ceph.isNotEmpty()) chips("두족류", ceph)
            if (fish.isNotEmpty()) chips("물고기 · 그 밖", fish)
            sh.body.addView(Ui.text(a, 11.5f, Ui.SUB, false, "여러 개 눌러 고르고 [✓ 보기] · 숫자 = 앞으로 나가는 날 수").apply { setPadding(0, dp(12), 0, 0) })
            paint(); sh.show()
        }
        areaBtn.setOnClickListener {
            val (sh, chips, paint) = multiSheet("📍 지역", areaSel) { v -> areaSel.clear(); areaSel.addAll(v); savePrefs(); render(); loadS24(true) }
            val sf = filterMem?.second
            if (sf == null || sf.area.isEmpty()) sh.body.addView(Ui.text(a, 13f, Ui.SUB, false, "선상24 지역 목록을 아직 못 받았어요 · 잠시 뒤 다시 열어 주세요").apply { setPadding(0, dp(12), 0, 0) })
            sf?.area?.forEach { (t, l) ->
                if (l.isEmpty()) return@forEach
                chips(sh.body, t, l, emptyMap())
            }
            sh.body.addView(Ui.text(a, 11.5f, Ui.SUB, false, "여러 개 눌러 고르고 [✓ 보기] · 내가 넣은 선단(⚙)은 지역과 상관없이 늘 보여요").apply { setPadding(0, dp(12), 0, 0) })
            paint(); sh.show()
        }
        needBtn.setOnClickListener {
            val sh = Sheet(a, 0.42f)
            sh.head.addView(Ui.text(a, 17f, Ui.TEXT, true, "👥 필요한 자리"), LinearLayout.LayoutParams(0, WRAP, 1f))
            sh.body.addView(Ui.text(a, 12.5f, Ui.SUB, false, "같이 가는 사람 수만큼 · 남은 자리가 이만큼 이상인 배만 '빈 배'로 세요 · 🔔 알림도 이 수 이상 날 때 울려요").apply { setPadding(0, 0, 0, dp(10)) })
            val wrap = FlowLayout(a)
            NEEDS.forEach { n -> wrap.addView(Ui.pill(a, if (n == 6) "6자리 이상" else "${n}자리", n == need, Ui.MINE) { need = n; savePrefs(); sh.close(); render() }) }
            sh.body.addView(wrap); sh.show()
        }
        filtBtn.setOnClickListener {
            val sh = Sheet(a, 0.55f)
            sh.head.addView(Ui.text(a, 17f, Ui.TEXT, true, "⚙ 필터"), LinearLayout.LayoutParams(0, WRAP, 1f))
            fun sw(t: String, sub: String, on: Boolean, f: (Boolean) -> Unit) {
                val r = Ui.row(a).apply { setPadding(0, dp(8), 0, dp(8)) }
                val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                col.addView(Ui.text(a, 14.5f, Ui.TEXT, true, t)); col.addView(Ui.text(a, 11.5f, Ui.SUB, false, sub))
                r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
                val s = android.widget.Switch(a).apply { isChecked = on; setOnCheckedChangeListener { _, v -> f(v); render() } }
                r.addView(s); sh.body.addView(r)
            }
            sw("빈자리 있는 배만", "마감 · 필요한 자리보다 적은 배는 숨겨요", openOnly) { openOnly = it; savePrefs() }
            sw("⭐ 즐겨찾기 배 맨 위", "☆ 를 눌러 넣은 배를 먼저 보여 줘요", favTop) { favTop = it; savePrefs() }
            sh.body.addView(Ui.text(a, 13.5f, Ui.TEXT, true, "정렬").apply { setPadding(0, dp(12), 0, dp(6)) })
            val wrap = FlowLayout(a)
            arrayOf("자리 많은 순", "출항 빠른 순", "이름 순").forEachIndexed { i, t -> wrap.addView(Ui.pill(a, t, i == sort, Ui.MINE, small = true) { sort = i; savePrefs(); sh.close(); render() }) }
            sh.body.addView(wrap)
            sh.show()
        }
        fleetBtn.setOnClickListener { openFleets(a) { useS24 = p.getBoolean("seat_s24", true); load(true) } }
        dayBtn.setOnClickListener { openCalendar() }
        prevBtn.setOnClickListener { shift(-1) }
        nextBtn.setOnClickListener { shift(1) }
        alarmBtn.setOnClickListener { openWatches(a) { render() } }

        dlg.setContentView(root)
        Ui.edge(dlg.window)
        dlg.show()
        render()
        load(false)
        syncPerm(a) { a.runOnUiThread { if (dlg.isShowing) render() } }
        // 처음: 선단이 없으면 내 사이트 · 그룹 사이트에 있는 선상24 주소 추천
        if (fleets(a).isEmpty()) suggest(a)?.let { hs -> if (hs.isNotEmpty()) openFleets(a) { load(true) } }
    }

    /** 🌐 자주 가는 사이트에 있는 선상24 주소 */
    private fun suggest(c: Context): List<Pair<String, String>>? = try {
        Boats.load(c).mapNotNull { s -> hostOf(s.url)?.let { it to s.name } }.distinctBy { it.first }
    } catch (_: Exception) { null }

    // ─────────────── ⚙ 선단 관리 ───────────────
    fun openFleets(a: Activity, changed: () -> Unit) {
        val sh = Sheet(a, 0.75f)
        fun dp(v: Int) = Ui.dp(a, v)
        sh.head.addView(Ui.text(a, 17f, Ui.TEXT, true, "⚙ 볼 선단"), LinearLayout.LayoutParams(0, WRAP, 1f))
        var dirty = false
        sh.onClose = { if (dirty) changed() }
        fun draw() {
            sh.body.removeAllViews()
            // v6.78: 🌐 선상24 전체 켜고 끄기
            val r0 = Ui.row(a).apply { setPadding(0, dp(4), 0, dp(10)) }
            val c0 = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            c0.addView(Ui.text(a, 14.5f, Ui.TEXT, true, "🌐 선상24 전체 배"))
            c0.addView(Ui.text(a, 11.5f, Ui.SUB, false, "고른 날짜 · 지역 · 어종의 선상24 배를 모두 찾아요 (30척씩)"))
            r0.addView(c0, LinearLayout.LayoutParams(0, WRAP, 1f))
            r0.addView(android.widget.Switch(a).apply { isChecked = prefs(a).getBoolean("seat_s24", true)
                setOnCheckedChangeListener { _, v -> prefs(a).edit().putBoolean("seat_s24", v).apply(); dirty = true } })
            sh.body.addView(r0)
            sh.body.addView(Ui.text(a, 12.5f, Ui.SUB, true, "⭐ 내 선단 (선단 홈페이지 · 한 달 일정 · 물때까지)").apply { setPadding(0, dp(4), 0, dp(4)) })
            val l = fleets(a)
            if (l.isEmpty()) sh.body.addView(Ui.text(a, 13f, Ui.SUB, false, "선상24에서 자주 타는 선단 홈페이지 주소를 넣어 주세요 (예: sungbin.sunsang24.com) · 그 선단의 배 일정을 다 읽어 와요"))
            l.forEachIndexed { i, f ->
                val r = Ui.row(a).apply { setPadding(0, dp(8), 0, dp(8)) }
                val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                col.addView(Ui.text(a, 14.5f, if (f.on) Ui.TEXT else Ui.SUB, true, "🚤 " + f.name))
                col.addView(Ui.text(a, 11.5f, Ui.SUB, false, f.host))
                r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
                r.addView(android.widget.Switch(a).apply { isChecked = f.on; setOnCheckedChangeListener { _, v -> l[i].on = v; saveFleets(a, l); dirty = true } })
                r.addView(Ui.pill(a, "🗑", false, Ui.TEXT, small = true) {
                    l.removeAt(i); saveFleets(a, l); dirty = true; draw()
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
                sh.body.addView(r)
                sh.body.addView(View(a).apply { setBackgroundColor(Ui.LINE) }, LinearLayout.LayoutParams(MATCH, 1))
            }
            val have = l.map { it.host }.toSet()
            val sug = (suggest(a) ?: emptyList()).filter { it.first !in have }
            if (sug.isNotEmpty()) {
                sh.body.addView(Ui.text(a, 12.5f, Ui.SUB, true, "🌐 자주 가는 사이트에 있는 선상24 주소").apply { setPadding(0, dp(14), 0, dp(6)) })
                sug.forEach { (h, n) -> sh.body.addView(Ui.button(a, "＋ $n ($h)") { addFleet(a, h, n) { dirty = true; draw() } }.apply { minHeight = dp(44); textSize = 13.5f }, Ui.lp(a, MATCH, WRAP, top = 6)) }
            }
            // v6.80 (385): 개인 배 사이트 (선상24에 없는 곳)
            val pre = PRESETS.filter { it.first !in have }
            if (pre.isNotEmpty()) {
                sh.body.addView(Ui.text(a, 12.5f, Ui.SUB, true, "🛥 개인 배 사이트").apply { setPadding(0, dp(14), 0, dp(6)) })
                pre.forEach { (h, n) -> sh.body.addView(Ui.button(a, "＋ $n ($h)") { addFleet(a, h, n) { dirty = true; draw() } }.apply { minHeight = dp(44); textSize = 13.5f }, Ui.lp(a, MATCH, WRAP, top = 6)) }
            }
            sh.body.addView(Ui.button(a, "＋ 선단 추가 (주소 붙여 넣기)", true) {
                val et = EditText(a).apply { hint = "sungbin.sunsang24.com"; inputType = InputType.TYPE_TEXT_VARIATION_URI; setSingleLine() }
                android.app.AlertDialog.Builder(a, R.style.FlAlert).setTitle("🚤 선단 추가")
                    .setMessage("선상24 선단 홈페이지 주소 (○○.sunsang24.com) 또는 개인 배 사이트 주소")
                    .setView(LinearLayout(a).apply { setPadding(dp(20), 0, dp(20), 0); addView(et, LinearLayout.LayoutParams(MATCH, WRAP)) })
                    .setPositiveButton("추가") { _, _ ->
                        val h = anyHostOf(et.text.toString())
                        if (h == null) Ui.toast(a, "주소를 확인해 주세요 (예: sungbin.sunsang24.com · 개인 배 사이트 주소)")
                        else addFleet(a, h, "") { dirty = true; draw() }
                    }.setNegativeButton("취소", null).show()
            }, Ui.lp(a, MATCH, WRAP, top = 14))
            sh.body.addView(Ui.text(a, 11.5f, Ui.SUB, false, "앱이 선단 일정 페이지를 직접 읽어요 (선상24 공식 기능이 아니라 사이트가 바뀌면 안 될 수 있어요) · 열 때 · 🔔 알림 걸어 둔 동안 15분마다만 읽어요").apply { setPadding(0, dp(12), 0, 0) })
        }
        draw(); sh.show()
    }

    private fun dial(a: Activity, tel: String) {
        try { a.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + tel.filter { it.isDigit() || it == '+' }))) }
        catch (_: Exception) { Ui.toast(a, "전화를 걸 수 없어요") }
    }

    /** v6.82 (390): 카드 꾹 = 배 메뉴 (배 사이트 · 내 선단에 넣기 · 전화 · 밴드 · 예약 화면 · 주소 직접 넣기 · 검색) */
    private fun shipMenu(a: Activity, s: Sch, changed: () -> Unit, fleetAdded: () -> Unit) {
        fun dp(v: Int) = Ui.dp(a, v)
        val sh = Sheet(a, 0.6f)
        sh.head.addView(Ui.text(a, 17f, Ui.TEXT, true, "🚤 " + s.ship), LinearLayout.LayoutParams(0, WRAP, 1f))
        sh.head.addView(Ui.pill(a, "닫기", false, Ui.TEXT, small = true) { sh.close() })
        val ss = if (s.host == S24 || isS24Fleet(s.host)) siteOf(a, s.shipNo) else null
        val host = if (isS24Fleet(s.host)) s.host else ss?.host
        sh.body.addView(Ui.text(a, 12f, Ui.SUB, false, listOfNotNull(s.place.ifBlank { null }, host?.let { "🌐 $it" }, ss?.tel?.let { "📞 $it" }).joinToString(" · ")).apply { setPadding(0, 0, 0, dp(8)) })
        fun item(t: String, sub: String, f: () -> Unit) {
            val r = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
                background = Ui.bg(a, Color.parseColor("#12283A"), 12, Color.parseColor("#2C4F66"))
                isClickable = true; setOnClickListener { sh.close(); f() }
            }
            r.addView(Ui.text(a, 14.5f, Ui.TEXT, true, t))
            if (sub.isNotBlank()) r.addView(Ui.text(a, 11.5f, Ui.SUB, false, sub))
            sh.body.addView(r, Ui.lp(a, MATCH, WRAP, top = 6))
        }
        if (host != null) {
            item("🌐 배 사이트 열기", host) { Boats.openUrl(a, siteUrl(host, s.date)) }
            if (fleets(a).none { it.host == host })
                item("＋ 내 선단에 넣기", "이 선단의 다른 배까지 같이 보여요 · 선단에 따라 로그인 없이 예약돼요") { addFleet(a, host, "") { fleetAdded() } }
        }
        ss?.tel?.let { t -> item("📞 전화 걸기", t) { dial(a, t) } }
        ss?.band?.let { b -> item("밴드 열기", b) { Boats.openUrl(a, b) } }
        item(if (s.host == S24) "🧾 선상24 예약 창" else "🧾 예약 화면", if (s.host == S24) "선상24 앱 · 사이트 예약 (로그인이 필요할 수 있어요)" else "선단 예약 화면") { Boats.openUrl(a, bookUrl(s)) }
        if (s.host == S24) item("✎ 배 사이트 주소 직접 넣기", "못 찾았거나 틀렸을 때 · 예) oceanleader.sunsang24.com") {
            val ed = EditText(a).apply { setText(prefs(a).getString("seat_site_m_${s.shipNo}", "") ?: ""); hint = "○○.sunsang24.com"; inputType = InputType.TYPE_TEXT_VARIATION_URI; setSingleLine() }
            android.app.AlertDialog.Builder(a).setTitle("🌐 ${s.ship} 사이트 주소").setView(LinearLayout(a).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(ed, LinearLayout.LayoutParams(MATCH, WRAP)) })
                .setPositiveButton("저장") { _, _ ->
                    val raw = ed.text.toString().trim()
                    val h = if (raw.isBlank()) "" else anyHostOf(raw) ?: raw.removePrefix("https://").removePrefix("http://").substringBefore('/')
                    prefs(a).edit().putString("seat_site_m_${s.shipNo}", h).apply()
                    Ui.toast(a, if (h.isBlank()) "직접 넣은 주소를 지웠어요" else "✓ $h"); changed()
                }.setNegativeButton("취소", null).show()
        }
        item("🔎 배 이름으로 검색", "인터넷에서 '${s.ship} ${s.place.substringAfterLast("· ").trim()}' 찾기") {
            Boats.openUrl(a, "https://m.search.naver.com/search.naver?query=" + Uri.encode("${s.ship} ${s.place.substringAfterLast("· ").trim()} 선상낚시"))
        }
        sh.show()
    }

    /** 선단 추가: 한 번 읽어 보고 배 이름으로 이름 짓기 */
    private fun addFleet(a: Activity, host: String, name0: String, done: () -> Unit) {
        Ui.toast(a, if (isS24Fleet(host)) "선단 페이지 확인 중…" else "사이트 확인 중… (처음엔 30초쯤 걸릴 수 있어요)")
        Thread {
            val r = try { month(host, ymF.format(Date()), true) } catch (_: Exception) { null }
            a.runOnUiThread {
                if (r == null) { Ui.toast(a, if (isS24Fleet(host)) "읽지 못했어요 · 주소를 확인해 주세요 ($host)" else "이 개인 사이트는 아직 앱이 못 읽어요 · ✉ 1:1 문의로 주소를 알려 주시면 맞춰 넣을게요"); return@runOnUiThread }
                val ships = r.map { it.ship }.distinct()
                val name = name0.ifBlank { ships.take(2).joinToString(" · ").ifBlank { host.substringBefore('.') } + if (ships.size > 2) " 외 ${ships.size - 2}" else "" }
                val l = fleets(a); if (l.none { it.host == host }) l.add(Fleet(host, name)); saveFleets(a, l)
                Ui.toast(a, "✓ $name 추가 · 이번 달 일정 ${r.size}개 · 배 ${ships.size}척")
                done()
            }
        }.start()
    }

    // ─────────────── 🔔 알림 걸기 · 목록 ───────────────
    private fun addWatch(a: Activity, s: Sch, need: Int, done: () -> Unit) {
        val l = watches(a)
        l.removeAll { it.host == s.host && it.shipNo == s.shipNo && it.date == s.date }
        l.add(Watch(s.host, s.shipNo, s.ship, s.date, need, hit = false, last = s.remain, at = System.currentTimeMillis()))
        saveWatches(a, l); schedule(a)
        Ui.toast(a, "🔔 ${dayLabel(s.date)} ${s.ship} · ${need}자리 이상 나면 알려 드려요 (15분마다 확인)")
        done()
    }

    private fun askWatchOff(a: Activity, w: Watch, done: () -> Unit) {
        android.app.AlertDialog.Builder(a, R.style.FlAlert).setTitle("🔔 ${dayLabel(w.date)} ${w.ship}")
            .setMessage("${w.need}자리 이상 나면 알림 · 마지막 확인 남은 ${maxOf(0, w.last)}자리")
            .setPositiveButton("알림 끄기") { _, _ -> val l = watches(a); l.removeAll { it.host == w.host && it.shipNo == w.shipNo && it.date == w.date }; saveWatches(a, l); schedule(a); done() }
            .setNegativeButton("그대로", null).show()
    }

    fun openWatches(a: Activity, changed: () -> Unit) {
        val sh = Sheet(a, 0.6f)
        fun dp(v: Int) = Ui.dp(a, v)
        sh.head.addView(Ui.text(a, 17f, Ui.TEXT, true, "🔔 자리 나면 알림"), LinearLayout.LayoutParams(0, WRAP, 1f))
        sh.onClose = changed
        fun draw() {
            sh.body.removeAllViews()
            val l = watches(a)
            val at = prefs(a).getLong("seat_check_at", 0)
            sh.body.addView(Ui.text(a, 12f, Ui.SUB, false, "확인: 📱 내 폰 · 15분마다 (안드로이드가 몇 분 늦출 수 있어요)" +
                (if (at > 0) " · 마지막 확인 " + SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(at)) else "") +
                "\n☁ 서버 확인(더 빠름 · 폰 꺼져도)은 나중에 (Blaze 요금제 뒤)").apply { setPadding(0, 0, 0, dp(8)) })
            if (l.isEmpty()) sh.body.addView(Ui.text(a, 13.5f, Ui.TEXT, false, "걸어 둔 알림이 없어요 · 마감된 배 카드에서 [🔔 자리 나면 알림]을 눌러요"))
            l.sortedBy { it.date }.forEach { w ->
                val r = Ui.row(a).apply { setPadding(dp(10), dp(9), dp(10), dp(9)); background = Ui.bg(a, Color.parseColor("#12283A"), 12, if (w.hit) Color.parseColor("#2FBF8F") else Color.parseColor("#2C4F66")) }
                val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                col.addView(Ui.text(a, 14.5f, Ui.TEXT, true, "${dayLabel(w.date)} ${w.ship}"))
                col.addView(Ui.text(a, 11.5f, if (w.hit) Color.parseColor("#2FBF8F") else Ui.SUB, w.hit,
                    (if (w.hit) "● 자리 났어요! 남은 ${w.last}자리 · " else "남은 ${maxOf(0, w.last)}자리 · ") + "${w.need}자리 이상이면 알림"))
                r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
                if (w.hit) r.addView(Ui.pill(a, "예약 ›", true, Ui.GROUP, small = true) { Boats.openUrl(a, bookUrl(w)) }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
                r.addView(Ui.pill(a, "끄기", false, Ui.TEXT, small = true) {
                    val m = watches(a); m.removeAll { it.host == w.host && it.shipNo == w.shipNo && it.date == w.date }; saveWatches(a, m); schedule(a); draw()
                })
                sh.body.addView(r, Ui.lp(a, MATCH, WRAP, top = 6))
            }
            if (l.isNotEmpty()) sh.body.addView(Ui.button(a, "🔄 지금 확인") {
                Ui.toast(a, "확인 중…")
                Thread { val n = try { check(a) } catch (_: Exception) { -1 }
                    a.runOnUiThread { Ui.toast(a, when { n < 0 -> "확인하지 못했어요"; n > 0 -> "🔔 자리 난 배 ${n}척!"; else -> "아직 자리가 없어요" }); draw() } }.start()
            }.apply { minHeight = dp(44) }, Ui.lp(a, MATCH, WRAP, top = 12))
        }
        draw(); sh.show()
    }
}

/** 줄바꿈 되는 칩 묶음 (간단) */
class FlowLayout(c: Context) : ViewGroup(c) {
    private val gap = Ui.dp(c, 6)
    override fun onMeasure(ws: Int, hs: Int) {
        val maxW = MeasureSpec.getSize(ws) - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val v = getChildAt(i); if (v.visibility == GONE) continue
            measureChild(v, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), hs)
            if (x > 0 && x + v.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            x += v.measuredWidth + gap; rowH = maxOf(rowH, v.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(ws), y + rowH + paddingTop + paddingBottom)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val v = getChildAt(i); if (v.visibility == GONE) continue
            if (x > 0 && x + v.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            v.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + v.measuredWidth, paddingTop + y + v.measuredHeight)
            x += v.measuredWidth + gap; rowH = maxOf(rowH, v.measuredHeight)
        }
    }
}

class SeatAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val app = c.applicationContext
        val pr = goAsync()
        Thread {
            try { Seats.check(app) } catch (_: Exception) {}
            Seats.schedule(app)
            pr.finish()
        }.start()
    }
}
