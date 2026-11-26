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
        // v6.86 (395 · 396): 배 사진 · 조황 글 수 · 그날 공지 · 예약 오픈 시각 · 선상24 물때
        var img = ""; var boards = -1; var notice = ""; var openAt = ""; var tideW = ""
        /** v6.90 (399): 다른 곳(선상24 · 더피싱 · 개인 사이트)에 같이 올라온 같은 배 · 같은 날 */
        var also: List<Sch> = emptyList()
        /** 운항 시간(시간 · 소수) · 출항 시각(분) — 모르면 -1 */
        val startMin: Int get() = Regex("^(\\d{1,2}):(\\d{2})").find(time)?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() } ?: -1
        val hours: Double get() {
            val m = Regex("(\\d{1,2}):(\\d{2})\\s*~\\s*(\\d{1,2}):(\\d{2})").find(time) ?: return -1.0
            val a = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt(); var b = m.groupValues[3].toInt() * 60 + m.groupValues[4].toInt()
            if (b <= a) b += 24 * 60
            return (b - a) / 60.0
        }
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

    // ─────────────── v6.92 (410): 플랫폼(선상24 · 더피싱 원 홈페이지) 자료는 관리자 · 권한 있는 사람이 켰을 때만 ───────────────
    // 모두에게 = 개인 선단 사이트(○○.sunsang24.com · ○○.thefishing.kr · 개인 사이트)만 · 뒤에서는 마지막으로 본 권한(prefs seat_p_*)
    const val PERM_S24 = "s24"
    const val PERM_TF = "tf"
    fun canS24(c: Context) = if (Cloud.isAdmin || Cloud.myPerms.isNotEmpty()) Cloud.can(PERM_S24) else prefs(c).getBoolean("seat_p_s24", false)
    fun canTf(c: Context) = if (Cloud.isAdmin || Cloud.myPerms.isNotEmpty()) Cloud.can(PERM_TF) else prefs(c).getBoolean("seat_p_tf", false)
    fun s24On(c: Context) = canS24(c) && prefs(c).getBoolean("seat_s24on", false)
    fun tfOn(c: Context) = canTf(c) && prefs(c).getBoolean("seat_tfon", false)
    /** 선상24 지역 이름 → 더피싱 목록 지역 글자 (더피싱 전체 보기에서 그 지역 선단 고르기) */
    private val AREA_KW = mapOf("인천인근" to listOf("인천", "영흥도"), "평택인근" to listOf("평택", "화성", "당진"), "태안인근" to listOf("태안", "안면도", "서산"),
        "보령인근" to listOf("보령", "홍성"), "군산인근" to listOf("군산", "서천", "격포"), "영광인근" to listOf("영광"), "목포인근" to listOf("목포"),
        "진도인근" to listOf("진도", "해남"), "여수인근" to listOf("여수"), "통영인근" to listOf("통영"), "삼천포인근" to listOf("사천", "삼천포"),
        "완도인근" to listOf("완도", "고흥", "녹동"), "제주인근" to listOf("제주"), "서귀포인근" to listOf("제주"), "성산포인근" to listOf("제주"), "모슬포인근" to listOf("제주"))
    fun areaKw(sel: Set<String>) = sel.flatMap { AREA_KW[it] ?: listOf(it.removeSuffix("인근")) }.distinct()

    // ─────────────── v6.91 (402): 관리자가 홈피(talla.co.kr/admin)에서 넣은 배 → 모든 앱에 기본 선단처럼 ───────────────
    // Firestore extraShips/{id} {name, url, port, tel, memo, t} · 폰 prefs 에 6시간 기억 · 내 선단 목록엔 안 넣음(따로 붙여 읽기)
    class Extra(val name: String, val host: String, val port: String, val tel: String, val memo: String)
    @Volatile private var exShips: List<Extra>? = null
    @Volatile private var extraAt = 0L
    fun extras(c: Context): List<Extra> {
        exShips?.let { return it }
        val l = try { val arr = JSONArray(prefs(c).getString("seat_extra", "[]") ?: "[]")
            (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Extra(o.optString("n"), o.optString("h"), o.optString("p"), o.optString("t"), o.optString("m")) } } } catch (_: Exception) { emptyList() }
        exShips = l; extraAt = prefs(c).getLong("seat_extra_at", 0L); return l
    }
    fun refreshExtras(c: Context, done: (Boolean) -> Unit) {
        extras(c)
        if (System.currentTimeMillis() - extraAt < 6 * 3600_000L) { done(false); return }
        com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("extraShips").get()
            .addOnSuccessListener { q ->
                val l = q.documents.mapNotNull { d -> anyHostOf(d.getString("url") ?: "")?.let { h ->
                    Extra((d.getString("name") ?: "").trim(), h, (d.getString("port") ?: "").trim(), (d.getString("tel") ?: "").trim(), (d.getString("memo") ?: "").trim()) } }
                val arr = JSONArray(); l.forEach { arr.put(JSONObject().put("n", it.name).put("h", it.host).put("p", it.port).put("t", it.tel).put("m", it.memo)) }
                prefs(c).edit().putString("seat_extra", arr.toString()).putLong("seat_extra_at", System.currentTimeMillis()).apply()
                val changed = l.map { it.host + it.name } != (exShips ?: emptyList()).map { it.host + it.name }
                exShips = l; extraAt = System.currentTimeMillis(); done(changed)
            }.addOnFailureListener { done(false) }
    }
    /** 내 선단(켠 것) + 관리자가 넣은 배(내 목록에 없는 것 · 끈 건 빼고) */
    fun activeFleets(c: Context): List<Fleet> {
        val mine = fleets(c)
        val off = prefs(c).getStringSet("seat_extra_off", emptySet()) ?: emptySet()
        return mine.filter { it.on } + extras(c).filter { e -> mine.none { it.host == e.host } && e.host !in off }.distinctBy { it.host }.map { Fleet(it.host, it.name) }
    }
    /** 개인 사이트 미리 넣어 둔 것 (사용자가 알려 준 곳) */
    val PRESETS = listOf("hunter-booking.onrender.com" to "거북섬 헌터호")

    // ─────────────── 읽기 · 풀기 ───────────────
    private val mem = HashMap<String, Pair<Long, List<Sch>>>()
    private const val KEEP = 10 * 60 * 1000L

    // v7.00 (412): 선단 홈페이지는 중계기(talla-relay · Cloudflare · 5분 같이 기억)로 먼저 · 안 되면 예전처럼 폰이 직접
    private const val RELAY = "https://talla-relay.jonginzz33.workers.dev/?u="
    @Volatile private var relayBadUntil = 0L
    private fun relayable(url: String): Boolean {
        val h = try { URL(url).host.lowercase() } catch (_: Exception) { return false }
        if (h.substringBefore('.') in setOf("www", "api", "m", "imga", "img")) return false
        return isS24Fleet(h) || isTf(h) || h == "hunter-booking.onrender.com"
    }
    private fun get(url: String): String {
        if (relayable(url) && System.currentTimeMillis() > relayBadUntil) {
            try { return get0(RELAY + java.net.URLEncoder.encode(url, "UTF-8")) }
            catch (e: Exception) { if (e.message?.contains("HTTP 4") != true) relayBadUntil = System.currentTimeMillis() + 10 * 60_000L }
        }
        return if (isTfUrl(url) && url.startsWith("https://")) try { get0(url) } catch (e: Exception) { try { get0("http://" + url.removePrefix("https://")) } catch (_: Exception) { throw e } }
        else get0(url)
    }
    private fun get0(url: String): String {
        val cn = URL(url).openConnection() as HttpURLConnection
        // 개인 사이트(무료 서버)는 처음 깨어나는 데 오래 걸려서 넉넉히
        val slow = !url.contains("sunsang24.com") && !isTfUrl(url)
        val tf = isTfUrl(url)
        cn.connectTimeout = if (slow) 20000 else if (tf) 10000 else 8000; cn.readTimeout = if (slow) 40000 else if (tf) 20000 else 12000; cn.instanceFollowRedirects = true
        // 더피싱은 PC 화면 모양으로 읽어요 (폰 모양은 다를 수 있어서 · 저장해 본 화면이 PC 크롬)
        cn.setRequestProperty("User-Agent", if (isTfUrl(url)) "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
            else "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Mobile")
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
        val l = if (isS24Fleet(host)) parse(host, get("https://$host/ship/schedule_fleet/$ym")) else if (isTf(host)) tfMonth(host, ym) else calSite(host, ym)
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
        else if (isTf(s.host)) tfBook(s.host, s.date)
        else if (!isS24Fleet(s.host)) "https://${s.host}/#reserve"
        else fleetDay(s.host, s.schNo, s.date)
    /** 397: 선단 사이트는 그 날 일정 예약 화면으로 바로 (일정 번호 없으면 달 페이지 · 열리면 1일로 감) */
    fun fleetDay(host: String, schNo: String, date: String) =
        if (schNo.toLongOrNull()?.let { it > 0 } == true) "https://$host/mypage/reservation_ready/$schNo" else siteUrl(host, date)
    fun bookUrl(w: Watch) = if (w.host == S24) "https://www.sunsang24.com/ship/list"
        else if (isTf(w.host)) tfBook(w.host, w.date)
        else if (!isS24Fleet(w.host)) "https://${w.host}/#reserve"
        else "https://${w.host}/ship/schedule_fleet/${w.date.replace("-", "").take(6)}"

    // ─────────────── v6.78: 🌐 선상24 전체 (선상24 목록 주소 api.sunsang24.com · 공개 문서 없는 주소라 바뀌면 멈출 수 있음) ───────────────
    const val S24 = "s24"
    val SORTS = arrayOf("자리 많은 순", "출항 빠른 순", "이름 순", "📝 조황 많은 순", "🕘 최근 조황 순", "📝 최근 조과 순")
    private const val API = "https://api.sunsang24.com"
    class S24Page(val total: Int, val list: List<Sch>)

    private fun hm(t: String) = t.take(5)
    private fun fromApi(o: JSONObject, date0: String = ""): Sch {
        val sh = o.optJSONObject("ship") ?: JSONObject()
        val cap = o.optInt("embarkation_num", sh.optInt("embarkation_num", 0))
        val remain = o.optInt("remain_embarkation_num", 0)
        val st = o.optString("schedule_status_code", o.optString("status_code", "ING"))
        val place = listOf(sh.optString("area_main") + " " + sh.optString("area_sub"), o.optString("port_name", sh.optString("port_name"))).map { it.trim() }.filter { it.isNotBlank() }.joinToString(" · ")
        // v6.85 (393): 시간 안 정한 배(00:00 ~ 00:00) · 'null' 글자 빼기
        fun js(k: String, d: String = "") = o.optString(k, d).let { if (it == "null") "" else it.trim() }
        val st0 = hm(js("stime")); val et0 = hm(js("etime"))
        val t = if ((st0 == "00:00" || st0.isBlank()) && (et0 == "00:00" || et0.isBlank())) "" else listOf(st0, et0).filter { it.isNotBlank() }.joinToString(" ~ ")
        return Sch(S24, sh.optInt("no").toString(), o.optString("schedule_no"), sh.optString("name").trim(), o.optString("sdate", date0),
            js("fish_type"), js("fishing_method"), t, remain, maxOf(0, cap - remain),
            if (st == "ING" && remain <= 0) "END" else st, "", place, o.optInt("price")).apply {
            img = sh.optString("image").let { if (it == "null") "" else it }
            boards = sh.optInt("board_count", o.optInt("board_write_num", -1))
            notice = noticeText(js("notice"))
            openAt = if (o.optInt("is_reservation_possible", 1) == 0) js("schedule_open_dt") else ""
            tideW = js("tide_water")
        }
    }
    private fun noticeText(h: String): String = if (h.isBlank()) "" else
        android.text.Html.fromHtml(h.replace(Regex("<br\\s*/?>|</p>|</div>", RegexOption.IGNORE_CASE), " · "), 0).toString()
            .replace(Regex("[\\s\\u00a0]+"), " ").replace(Regex("( · )+"), " · ").trim().trim('·', ' ').take(160)

    // v6.86 (395 · 396): 보이는 선상24 배만 한 달 일정에서 공지 · 오픈 시각 · 물때 채우기 + 조황 마지막 글 날짜
    private val extraMem = HashMap<String, Pair<Long, List<Sch>>>()
    fun s24Extra(shipNo: String, ym: String): List<Sch>? {
        val k = "${shipNo}_$ym"
        synchronized(extraMem) { extraMem[k]?.let { if (System.currentTimeMillis() - it.first < 60 * 60_000L) return it.second } }
        val l = try { s24Month(shipNo, ym) } catch (_: Exception) { return null }
        synchronized(extraMem) { extraMem[k] = System.currentTimeMillis() to l }
        return l
    }
    fun extraCached(shipNo: String, ym: String) = synchronized(extraMem) { extraMem["${shipNo}_$ym"]?.second }
    /** 조황 마지막 글 날짜 (yyyy-MM-dd) · 하루 기억 */
    fun lastBoard(c: Context, shipNo: String, fetch: Boolean): String? {
        val p = prefs(c); val today = ymd.format(Date())
        p.getString("seat_lb_$shipNo", null)?.let { v -> if (v.substringAfter('|') == today) return v.substringBefore('|') }
        if (!fetch) return null
        val o = try { JSONObject(get("$API/ship/fish_board_list?ship_no=$shipNo&page=1")) } catch (_: Exception) { return null }
        val d = o.optJSONArray("list")?.optJSONObject(0)?.optString("insert_dt")?.take(10) ?: ""
        p.edit().putString("seat_lb_$shipNo", "$d|$today").putInt("seat_lbn_$shipNo", o.optInt("total", -1)).apply()
        return d
    }

    /** v6.91 (407): 조황 글 목록에서 받은 글 수 (선상24 목록 board_count 가 0 으로 오는 배가 있어서) */
    fun boardTotal(c: Context, shipNo: String): Int = prefs(c).getInt("seat_lbn_$shipNo", -1)
    /** 마지막 조황 날짜 (오늘 받은 것만 · 정렬용) */
    fun lastBoardAny(c: Context, shipNo: String): String? = prefs(c).getString("seat_lb_$shipNo", null)?.substringBefore('|')

    // v6.97 (414): 선단 홈페이지 대표 사진 — prefs seat_img_<host> = 주소|yyyyMMdd (못 찾음 = 빈 주소 · 7일)
    private val OG_IMG = Regex("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']|<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:image[\"']", RegexOption.IGNORE_CASE)
    // v6.98: 선단 첫 화면 맨 위 배 사진 (.img_submain = ship_mobile_main · 없으면 ship_web_main) — og:image 는 선상24 기본 바다 그림이라 마지막
    private val WEB_MAIN = Regex("https?://imga\\.sunsang24\\.com/ship_img/[^\"'\\s)]+ship_(?:mobile|web)_main/[^\"'\\s)]+\\.(?:jpg|jpeg|png)", RegexOption.IGNORE_CASE)
    /** null = 아직 모름 · "" = 없음 */
    fun fleetImg(c: Context, host: String): String? {
        val v = prefs(c).getString("seat_img2_$host", null) ?: return null
        val u = v.substringBefore('|'); val d = v.substringAfterLast('|')
        val age = try { ((System.currentTimeMillis() - SimpleDateFormat("yyyyMMdd", Locale.KOREA).parse(d)!!.time) / 86400000L).toInt() } catch (_: Exception) { 99 }
        return if (age < (if (u.isBlank()) 7 else 30)) u else null
    }
    fun findFleetImg(c: Context, host: String) {
        var u = ""
        try {
            val h = get("https://$host/")
            u = WEB_MAIN.findAll(h).map { it.value }.sortedBy { if (it.contains("ship_mobile_main")) 0 else 1 }.firstOrNull() ?: ""
            if (u.isBlank()) u = OG_IMG.find(h)?.let { m -> m.groupValues[1].ifBlank { m.groupValues[2] } }?.trim() ?: ""
            // 선상24 · 더피싱 공용 기본 그림은 빼기
            if (u.contains("assets.sunsang24.com") || u.endsWith("/og.jpg") || u.contains("/n/images/")) u = ""
            if (u.startsWith("//")) u = "https:$u" else if (u.startsWith("/")) u = "https://$host$u"
            if (!u.startsWith("http")) u = ""
            // 사이트 기본 로고 · 아이콘은 빼기
            if (Regex("favicon|logo|icon|kakao|naver", RegexOption.IGNORE_CASE).containsMatchIn(u.substringAfterLast('/'))) u = ""
        } catch (_: Exception) { return }   // 못 읽음 → 다음에 다시
        prefs(c).edit().putString("seat_img2_$host", u + "|" + SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(Date())).apply()
    }

    // v6.86 (395): 배 사진 (작은 사진 · 메모리 + 폰 파일)
    private val imgMem = android.util.LruCache<String, android.graphics.Bitmap>(60)
    private val imgBusy = HashSet<String>()
    /** 큰 사진(선단 첫 화면 사진 등)은 긴 변 ~480px 로 줄여 읽기 */
    private fun small(b: ByteArray): android.graphics.Bitmap? {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var n = 1; while (maxOf(o.outWidth, o.outHeight) / (n * 2) >= 480) n *= 2
        return android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = n })
    }
    fun shipImg(c: Context, url: String, view: android.widget.ImageView) {
        if (url.isBlank()) return
        view.tag = url
        imgMem.get(url)?.let { view.setImageBitmap(it); return }
        if (!synchronized(imgBusy) { imgBusy.add(url) }) return
        Thread {
            val f = java.io.File(c.cacheDir, "shipimg/" + Integer.toHexString(url.hashCode()) + ".jpg")
            val bm = try {
                if (f.exists() && System.currentTimeMillis() - f.lastModified() < 14 * 86400_000L) small(f.readBytes())
                else {
                    val cn = URL(url).openConnection() as HttpURLConnection
                    cn.connectTimeout = 8000; cn.readTimeout = 10000
                    val bytes = cn.inputStream.use { it.readBytes() }; cn.disconnect()
                    f.parentFile?.mkdirs(); f.writeBytes(bytes)
                    small(bytes)
                }
            } catch (_: Exception) { null }
            synchronized(imgBusy) { imgBusy.remove(url) }
            if (bm != null) { imgMem.put(url, bm); view.post { if (view.tag == url) view.setImageBitmap(bm) } }
        }.start()
    }

    // v6.86 (395): 👍👎 인기도 (우리 앱 사용자 · 1인 1배 1표) — shipVotes/{배키}/v/{uid} {v: 1|-1, t}
    class Votes(val up: Int, val dn: Int, val mine: Int)
    private val voteMem = HashMap<String, Pair<Long, Votes>>()
    fun voteKey(s: Sch) = if (s.host == S24 || isS24Fleet(s.host)) "s24_${s.shipNo}" else (s.host.replace('.', '_') + "_" + s.shipNo).take(120)
    fun votesCached(k: String) = synchronized(voteMem) { voteMem[k]?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000L }?.second }
    fun loadVotes(k: String, done: (Votes) -> Unit) {
        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        val col = db.collection("shipVotes").document(k).collection("v")
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        val src = com.google.firebase.firestore.AggregateSource.SERVER
        col.whereEqualTo("v", 1).count().get(src).addOnSuccessListener { u ->
            col.whereEqualTo("v", -1).count().get(src).addOnSuccessListener { d ->
                fun fin(m: Int) { val v = Votes(u.count.toInt(), d.count.toInt(), m); synchronized(voteMem) { voteMem[k] = System.currentTimeMillis() to v }; done(v) }
                if (uid == null) fin(0) else col.document(uid).get().addOnSuccessListener { fin(it.getLong("v")?.toInt() ?: 0) }.addOnFailureListener { fin(0) }
            }
        }
    }
    fun vote(k: String, v: Int, done: (Boolean) -> Unit) {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return done(false)
        val ref = com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("shipVotes").document(k).collection("v").document(uid)
        val old = votesCached(k)
        val task = if (v == 0) ref.delete() else ref.set(mapOf("v" to v, "t" to System.currentTimeMillis()))
        task.addOnSuccessListener {
            if (old != null) synchronized(voteMem) {
                var up = old.up; var dn = old.dn
                if (old.mine == 1) up--; if (old.mine == -1) dn--
                if (v == 1) up++; if (v == -1) dn++
                voteMem[k] = System.currentTimeMillis() to Votes(maxOf(0, up), maxOf(0, dn), v)
            }
            done(true)
        }.addOnFailureListener { done(false) }
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

    // ─────────────── v6.90 (399): 출처 · 중복 거르기 · 항구 ───────────────
    fun srcOf(host: String) = when { host == S24 || isS24Fleet(host) -> "선상24"; isTf(host) -> "더피싱"; else -> "개인 사이트" }
    fun srcCol(src: String) = Color.parseColor(when (src) { "선상24" -> "#3D7BD9"; "더피싱" -> "#2FA06F"; else -> "#B8862B" })
    /** 배 이름 비교용: 띄어쓰기 · [선장] · (오전) · 끝 '호' 빼고 */
    fun normShip(x: String) = x.replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), "").replace(" ", "").trim().removeSuffix("호").lowercase()
    fun normPort(x: String) = x.replace(" ", "").removeSuffix("항").removeSuffix("포구").lowercase()
    private fun looksPort(x: String) = x.isNotBlank() && Regex("(항|포구|선착장|부두|포|항구|진두)$").containsMatchIn(x.trim())
    /** 그 배가 나가는 항구 (모르면 "") */
    fun portOf(c: Context, s: Sch): String = when {
        s.host == S24 -> if (s.place.contains(" · ")) s.place.substringAfterLast(" · ").trim() else sites(c)[s.shipNo]?.port ?: ""
        isS24Fleet(s.host) -> sites(c)[s.shipNo]?.port ?: ""
        exShips?.any { it.host == s.host && it.port.isNotBlank() } == true -> exShips!!.first { it.host == s.host && it.port.isNotBlank() }.port
        isTf(s.host) -> tfMem?.filter { it.host == s.host }?.let { l -> (l.firstOrNull { normShip(it.ship) == normShip(s.ship) } ?: l.firstOrNull())?.area?.substringAfterLast(" > ")?.substringAfterLast(" ") } ?: ""
        else -> ""
    }.trim().let { if (looksPort(it)) it else "" }
    private fun portsOk(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return true
        val x = normPort(a); val y = normPort(b)
        return x.contains(y) || y.contains(x)
    }
    /** 같은 날 · 같은 배 이름 · 같은 항구가 다른 곳(출처)에 또 있으면 한 카드로 (앞에 온 것 = 선단 페이지가 먼저) */
    fun dedupe(c: Context, l: List<Sch>): List<Sch> {
        val out = ArrayList<Sch>()
        l.forEach { it.also = emptyList() }
        l.forEach { s ->
            val src = srcOf(s.host); val n = normShip(s.ship)
            val p = out.firstOrNull { o -> o.date == s.date && n.isNotBlank() && normShip(o.ship) == n &&
                o.host != s.host && o.also.none { it.host == s.host } && (
                (srcOf(o.host) != src && o.also.none { srcOf(it.host) == src } && portsOk(portOf(c, o), portOf(c, s))) ||
                // v6.97 (416): 같은 배를 다른 선단 두 곳이 올림 (구라미끼호 · 뉴나성호) — 항구를 둘 다 알고 같을 때만
                (srcOf(o.host) == src && o.host != S24 && s.host != S24 && portOf(c, o).isNotBlank() && portOf(c, s).isNotBlank() && portsOk(portOf(c, o), portOf(c, s)))) }
            if (p != null) p.also = p.also + s else out.add(s)
        }
        return out
    }

    // ─────────────── v6.85 (394): 🎣 더피싱 (○○.thefishing.kr) ───────────────
    // 선단 예약현황 = /index.php?mid=bk&year=&month=&day=#list → 그 날부터 8일 · 날마다 <div id="new-div-YYYYMMDD">
    // 배 줄: 이름 <span style="font-size:15px; font-weight:bold;"> · 낚시종류 · 공지 · 상태 <div id="admin-right-날짜-배번호-0"><img alt="남은자리 N명">
    // ⚠ 더피싱은 해외 접속을 막아서 이 코드는 저장한 화면으로만 시험함 · 폰(한국)에서 확인
    /** 더피싱으로 만든 자기 도메인 선단 (assets/tf_sites.json 에 있는 것) */
    val TF_DOMAINS = setOf("djfishing.com", "jnaksi.com", "morningstarho.com", "raraho.kr", "teammansu.kr", "woorinaksi.com", "www.haejinho.com", "xn--9m1bx7rzqh1yhbmj.com", "youngdong2ho.net")
    fun isTf(host: String) = (host.endsWith(".thefishing.kr") && host.substringBefore('.') !in setOf("www", "js", "img", "image", "theimage", "css", "m")) || host.lowercase() in TF_DOMAINS
    private fun isTfUrl(url: String) = url.contains("thefishing.kr") || TF_DOMAINS.any { url.contains("//$it/") }
    fun tfBook(host: String, date: String): String {
        val d = date.replace("-", "")
        return if (d.length == 8) "https://$host/index.php?mid=bk&year=${d.take(4)}&month=${d.substring(4, 6)}&day=${d.substring(6)}#list" else "https://$host/index.php?mid=bk"
    }
    private fun tfText(x: String) = android.text.Html.fromHtml(x.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " "), 0).toString().replace(Regex("\\s+"), " ").trim()
    private val TF_SEC = Regex("id=\"new-div-(\\d{8})\"")
    private val TF_ROW = Regex("admin-right-(\\d{8})-(\\d+)-\\d+\"[^>]*>\\s*<img[^>]*alt=\"([^\"]*)\"")
    private val TF_NAME = Regex("font-size:\\s*15px;\\s*font-weight:\\s*bold;?\"\\s*>(.*?)</span>", RegexOption.DOT_MATCHES_ALL)
    private fun tfCell(seg: String, alt: String) = Regex("alt=\"$alt\"></td>\\s*<td[^>]*>(.*?)</td>", RegexOption.DOT_MATCHES_ALL).find(seg)?.let { tfText(it.groupValues[1]) } ?: ""
    fun parseTfFleet(host: String, html: String): List<Sch> {
        val out = ArrayList<Sch>()
        val secs = TF_SEC.findAll(html).toList()
        secs.forEachIndexed { i, m ->
            val body = html.substring(m.range.last + 1, if (i + 1 < secs.size) secs[i + 1].range.first else html.length)
            var prev = 0
            TF_ROW.findAll(body).forEach { r ->
                val seg = body.substring(prev, r.range.first); prev = r.range.last + 1
                val d8 = r.groupValues[1]; val uid = r.groupValues[2]; val alt = r.groupValues[3]
                val full = TF_NAME.findAll(seg).lastOrNull()?.let { tfText(it.groupValues[1]) } ?: ""
                val ship = full.substringBefore('[').substringBefore('(').trim().ifBlank { full.ifBlank { "배 $uid" } }
                val way = full.removePrefix(ship).trim()
                val fish = tfCell(seg, "낚시종류").replace("★", "").trim()
                val note = tfCell(seg, "공지")
                val n = Regex("(\\d+)\\s*명").find(alt)?.groupValues?.get(1)?.toIntOrNull()
                val st = when {
                    alt.contains("남은") && n != null -> if (n > 0) "ING" else "END"
                    alt.contains("완료") || alt.contains("마감") -> "END"
                    alt.contains("기상") -> "BAD_WEATHER"
                    alt.contains("정비") || alt.contains("점검") -> "CHECK"
                    else -> "NONE"
                }
                val date = "${d8.take(4)}-${d8.substring(4, 6)}-${d8.substring(6)}"
                out.add(Sch(host, uid, uid, ship, date, fish.ifBlank { note.take(30) }, way, "", n ?: 0, 0, st, ""))
            }
        }
        return out.distinctBy { it.date + it.shipNo }
    }
    /** 한 달 = 8일씩 몇 번 (지난 날은 건너뜀) */
    private fun tfMonth(host: String, ym: String): List<Sch> {
        val cal = Calendar.getInstance().apply { time = ymF.parse(ym)!!; set(Calendar.DAY_OF_MONTH, 1) }
        val last = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val today = Calendar.getInstance()
        var d = if (ymF.format(today.time) == ym) today.get(Calendar.DAY_OF_MONTH) else 1
        val out = ArrayList<Sch>(); var fails = 0
        while (d <= last) {
            try {
                val h = get("https://$host/index.php?mid=bk&year=${ym.take(4)}&month=${ym.substring(4)}&day=%02d".format(d))
                val got = parseTfFleet(host, h); out.addAll(got)
                rememberTfShips(host, parseTfShipList(h) + got.map { it.shipNo to it.ship })
            }
            catch (e: Exception) { if (++fails >= 2 && out.isEmpty()) throw e }
            d += 8
        }
        return out.filter { it.date.replace("-", "").take(6) == ym }.distinctBy { it.date + it.shipNo }
    }

    // ─────────────── v6.95: 📍 지역의 선단 전부 (선상24 선단 1,030곳 · 더피싱 선단 343곳) · 고른 날 하루치만 ───────────────
    /** 선상24 선단 · 하루치 (30KB · 한 달 페이지 0.5MB 대신) · 10분 기억 */
    fun s24Day(host: String, date: String): List<Sch> {
        val k = "${host}_d$date"
        synchronized(mem) { mem[k]?.let { if (System.currentTimeMillis() - it.first < KEEP) return it.second } }
        val l = parse(host, get("https://$host/ship/schedule_fleet/$date/0/simple_day1")).filter { it.date == date }.distinctBy { it.shipNo + it.schNo }
        synchronized(mem) { mem[k] = System.currentTimeMillis() to l }
        return l
    }
    /** v7.01 (425): 중계기 /day — 선단 40곳 그 날 일정을 한 번에 (정리된 JSON) · 받은 건 s24Day/tfDay 기억(mem)에 넣음 · 실패하면 null */
    fun dayBatch(hosts: List<String>, date: String): Map<String, List<Sch>>? {
        if (hosts.isEmpty() || System.currentTimeMillis() < relayBadUntil) return null
        val t = try { get0(RELAY.removeSuffix("/?u=") + "/day?d=$date&h=" + hosts.joinToString(",")) } catch (_: Exception) { return null }
        val o = try { JSONObject(t) } catch (_: Exception) { return null }
        val out = HashMap<String, List<Sch>>()
        hosts.forEach { h ->
            val arr = o.optJSONArray(h) ?: return@forEach   // null = 그 선단 못 읽음 → 나중에 한 곳씩
            val l = (0 until arr.length()).map { i -> val x = arr.getJSONObject(i)
                Sch(h, x.optString("shipNo"), x.optString("schNo"), x.optString("ship").ifBlank { "배" }, x.optString("date"), x.optString("fish"), x.optString("way"),
                    x.optString("time"), x.optInt("remain"), x.optInt("reserved"), x.optString("status", "ING"), x.optString("tide")) }
            synchronized(mem) { mem["${h}_d$date"] = System.currentTimeMillis() to l }
            if (isTf(h)) rememberTfShips(h, l.map { it.shipNo to it.ship })
            out[h] = l
        }
        return out
    }
    class AreaC(val name: String, val lat: Double, val lon: Double, val group: String)
    val AREAS = listOf(
        AreaC("인천인근", 37.45, 126.55, "서해안"), AreaC("평택인근", 36.98, 126.80, "서해안"), AreaC("태안인근", 36.70, 126.25, "서해안"),
        AreaC("보령인근", 36.35, 126.52, "서해안"), AreaC("군산인근", 35.95, 126.60, "서해안"), AreaC("영광인근", 35.30, 126.40, "서해안"),
        AreaC("목포인근", 34.78, 126.38, "서해안"), AreaC("진도인근", 34.45, 126.25, "서해안"),
        AreaC("완도인근", 34.33, 126.75, "남해안"), AreaC("여수인근", 34.72, 127.72, "남해안"), AreaC("삼천포인근", 34.93, 128.07, "남해안"),
        AreaC("통영인근", 34.84, 128.43, "남해안"), AreaC("진해인근", 35.12, 128.70, "남해안"), AreaC("부산인근", 35.10, 129.05, "남해안"),
        AreaC("포항인근", 36.03, 129.40, "동해안"), AreaC("후포인근", 36.68, 129.45, "동해안"), AreaC("울진인근", 36.99, 129.41, "동해안"),
        AreaC("삼척인근", 37.44, 129.17, "동해안"), AreaC("강릉인근", 37.77, 128.95, "동해안"), AreaC("주문진인근", 37.89, 128.83, "동해안"),
        AreaC("속초인근", 38.20, 128.60, "동해안"), AreaC("고성인근", 38.38, 128.48, "동해안"),
        AreaC("제주인근", 33.52, 126.53, "제주도"), AreaC("서귀포인근", 33.24, 126.56, "제주도"), AreaC("모슬포인근", 33.21, 126.25, "제주도"), AreaC("성산포인근", 33.46, 126.93, "제주도"))
    private fun km(a1: Double, o1: Double, a2: Double, o2: Double): Double {
        val r = Math.PI / 180; val x = (o2 - o1) * r * Math.cos((a1 + a2) / 2 * r); val y = (a2 - a1) * r
        return Math.sqrt(x * x + y * y) * 6371.0
    }
    fun nearestArea(lat: Double, lon: Double): String? = AREAS.minByOrNull { km(lat, lon, it.lat, it.lon) }?.takeIf { km(lat, lon, it.lat, it.lon) < 70 }?.name
    private val AREA_EXTRA = mapOf("보령인근" to listOf("오천", "대천", "무창포", "홍성", "남당"), "군산인근" to listOf("비응", "야미도", "신시도", "선유도", "홍원", "서천", "격포", "위도"),
        "태안인근" to listOf("마검포", "안흥", "신진도", "모항", "드르니", "백사장", "영목", "구매", "서산", "삼길포"), "인천인근" to listOf("영흥", "연안부두", "남항", "인천", "대부", "오이도", "시흥", "거북섬"),
        "평택인근" to listOf("궁평", "전곡", "화성", "당진", "평택", "장고항", "한진"), "여수인근" to listOf("여수", "국동", "소호", "돌산", "광양"), "통영인근" to listOf("통영", "거제", "장승포", "지세포"))
    private fun areaByWord(x: String): String? {
        AREA_EXTRA.forEach { (ar, ks) -> if (ks.any { x.contains(it) }) return ar }
        AREA_KW.forEach { (ar, ks) -> if (ks.any { x.contains(it) }) return ar }
        return null
    }
    class DirF(val host: String, val name: String, val port: String, val area: String, val src: String, val lat: Double = Double.NaN, val lon: Double = Double.NaN)
    // v7.02 (426): 앞으로 한 달 일정이 없는 선단 (talla.co.kr/data/fleets_off.json · GitHub 매일 새벽) → 평소엔 안 읽음
    @Volatile private var offMem: Set<String>? = null
    private const val OFF_URL = "https://talla.co.kr/data/fleets_off.json"
    fun offFleets(c: Context): Set<String> {
        offMem?.let { return it }
        val f = java.io.File(c.filesDir, "fleets_off.json")
        val m = try { JSONObject(f.readText()).optJSONArray("off")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet() } catch (_: Exception) { emptySet() }
        offMem = m
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 12 * 3600_000L) Thread {
            try { val t = get(OFF_URL); JSONObject(t); f.writeText(t); offMem = null } catch (_: Exception) {}
        }.start()
        return m
    }
    @Volatile private var dirMem: Pair<Int, List<DirF>>? = null
    /** 선단 모음 (선상24 선단 · 더피싱 선단) — 항구 위치(지도 항구 2,267곳)로 가까운 지역 · 못 찾으면 이름 글자로 */
    fun dirFleets(c: Context): List<DirF> {
        val st = sites(c); val tf = tfMem ?: emptyList()
        val key = st.size * 7919 + tf.size
        dirMem?.let { if (it.first == key) return it.second }
        val idx = HashMap<String, Ports.Port>()
        try { Ports.list(c).forEach { pt -> val k = normPort(pt.name); if (k.length >= 2 && (k !in idx || pt.big)) idx[k] = pt } } catch (_: Exception) {}
        fun portPt(p: String): Ports.Port? {
            p.split(',', '/', ' ', '(', ')', '>', '·').map { normPort(it.trim()) }.filter { it.length >= 2 }.forEach { t ->
                (idx[t] ?: idx.entries.firstOrNull { it.key.startsWith(t) }?.value)?.let { pt -> if (nearestArea(pt.lat, pt.lon) != null) return pt }
            }
            return null
        }
        fun byPort(p: String): String? = portPt(p)?.let { nearestArea(it.lat, it.lon) }
        val out = ArrayList<DirF>()
        st.values.filter { it.host != null && isS24Fleet(it.host) }.groupBy { it.host!! }.forEach { (h, l) ->
            val port = l.map { it.port }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: ""
            val ar = byPort(port) ?: areaByWord(port) ?: return@forEach
            val pt = portPt(port)
            out.add(DirF(h, l.map { it.name }.filter { it.isNotBlank() }.distinct().take(3).joinToString(" · "), port, ar, "선상24", pt?.lat ?: Double.NaN, pt?.lon ?: Double.NaN))
        }
        tf.filter { it.host.isNotBlank() }.groupBy { it.host }.forEach { (h, l) ->
            val a0 = l.first().area
            val ar = areaByWord(a0) ?: byPort(a0.substringAfterLast(" > ")) ?: return@forEach
            val pt = portPt(a0.substringAfterLast(" > "))
            out.add(DirF(h, l.first().fleet.substringBefore(" (").ifBlank { l.map { it.ship }.distinct().take(3).joinToString(" · ") }, a0.substringAfterLast(" > "), ar, "더피싱", pt?.lat ?: Double.NaN, pt?.lon ?: Double.NaN))
        }
        dirMem = key to out
        return out
    }
    fun dirName(host: String): String? = dirMem?.second?.firstOrNull { it.host == host }?.name

    /** v6.92 (410): 더피싱 선단 · 그 날이 든 8일 화면 한 번 (더피싱 전체 보기용 · 10분 기억) */
    fun tfDay(host: String, date: String): List<Sch> {
        val k = "${host}_d$date"
        synchronized(mem) { mem[k]?.let { if (System.currentTimeMillis() - it.first < KEEP) return it.second } }
        val h = get("https://$host/index.php?mid=bk&year=${date.take(4)}&month=${date.substring(5, 7)}&day=${date.substring(8, 10)}")
        val got = parseTfFleet(host, h)
        rememberTfShips(host, parseTfShipList(h) + got.map { it.shipNo to it.ship })
        val l = got.filter { it.date == date }.distinctBy { it.shipNo }
        synchronized(mem) { mem[k] = System.currentTimeMillis() to l }
        return l
    }

    // v6.91 (403): 선단 화면 위 '선박별 예약현황' 배 이름 · 읽은 일정의 배 → 폰에 기억(30일) → 목록에 없는 배(테티스호 등)도 찾기에
    private val TF_LIST = Regex("PA_N_UID=(\\d+)#list\"[^>]*>.*?<p><span>(.*?)</span>", RegexOption.DOT_MATCHES_ALL)
    fun parseTfShipList(html: String): List<Pair<String, String>> {
        val i = html.indexOf("re_s_list"); if (i < 0) return emptyList()
        val seg = html.substring(i, minOf(html.length, i + 20000))
        return TF_LIST.findAll(seg).map { it.groupValues[1] to tfText(it.groupValues[2]) }
            .filter { (_, n) -> n.isNotBlank() && !n.contains("공지") && !n.contains("취소") }.toList()
    }
    @Volatile var appCtx: Context? = null
    private fun rememberTfShips(host: String, l: List<Pair<String, String>>) {
        val c = appCtx ?: return
        if (l.isEmpty()) return
        val p = prefs(c); val key = "tf_seen_$host"
        val old = try { JSONObject(p.getString(key, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val m = old.optJSONObject("s") ?: JSONObject()
        var ch = false
        l.forEach { (uid, n) -> if (uid.isNotBlank() && n.isNotBlank() && m.optString(uid) != n) { m.put(uid, n); ch = true } }
        if (!ch && old.has("t")) return
        p.edit().putString(key, JSONObject().put("t", System.currentTimeMillis()).put("s", m).toString()).apply()
        tfMem?.let { tfMem = withSeen(c, it) }
        // v6.92 (409): 처음 본 배는 모두와 같이 (tfSeen/{선단} · 배 번호 → 이름만 · 60척까지)
        if (ch && Cloud.uid != null) try {
            val mm = HashMap<String, String>(); m.keys().forEach { k -> if (mm.size < 60) mm[k] = m.optString(k).take(40) }
            com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("tfSeen").document(host)
                .set(mapOf("s" to mm, "t" to System.currentTimeMillis()), com.google.firebase.firestore.SetOptions.merge())
        } catch (_: Exception) {}
    }
    /** 기억해 둔 배 이름 중 목록에 없는 배를 목록에 붙임 (지역 · 선단 = 같은 선단 다른 배 것) */
    private fun withSeen(c: Context, l: List<TfShip>): List<TfShip> {
        val p = prefs(c); val have = l.map { it.host + "|" + it.uid }.toHashSet()
        val haveName = l.map { it.host + "|" + normShip(it.ship) }.toHashSet()
        val byHost = l.groupBy { it.host }
        val add = ArrayList<TfShip>()
        val srv = try { JSONObject(p.getString("tf_srv", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val srcs = p.all.keys.filter { it.startsWith("tf_seen_") }.map { it.removePrefix("tf_seen_") to "L" } + srv.keys().asSequence().map { it to "S" }.toList()
        srcs.forEach { (host, kind) ->
            val m = if (kind == "S") srv.optJSONObject(host) ?: return@forEach else {
                val key = "tf_seen_$host"
                val o = try { JSONObject(p.getString(key, "{}") ?: "{}") } catch (_: Exception) { return@forEach }
                if (System.currentTimeMillis() - o.optLong("t") > 30L * 86400000) { p.edit().remove(key).apply(); return@forEach }
                o.optJSONObject("s") ?: return@forEach
            }
            val ref = byHost[host]?.firstOrNull()
            m.keys().forEach { uid ->
                val n = m.optString(uid)
                if ("$host|$uid" !in have && "$host|${normShip(n)}" !in haveName) {
                    have.add("$host|$uid"); haveName.add("$host|${normShip(n)}")
                    add.add(TfShip(uid, n, ref?.area ?: "", ref?.fleet ?: host.substringBefore('.'), "", "", host))
                }
            }
        }
        return if (add.isEmpty()) l else l + add
    }

    /** 더피싱 선단 목록 (관리자 폰이 모아 config/tfSites 에 저장 · 모두 읽기) */
    class TfShip(val uid: String, val ship: String, val area: String, val fleet: String, val fish: String, val seat: String, val host: String)
    @Volatile private var tfMem: List<TfShip>? = null
    private fun tfParse(j: String?): List<TfShip> = try { val a = JSONArray(j ?: "[]"); (0 until a.length()).map { i -> a.getJSONArray(i).let { x ->
        TfShip(x.optString(0), x.optString(1), x.optString(2), x.optString(3), x.optString(4), x.optString(5), x.optString(6)) } } } catch (_: Exception) { emptyList() }
    /** 기본 = 앱 안 assets/tf_sites.json (사용자가 뽑은 678척 · v6.89) · 관리자가 그 뒤에 새로 모으면(config/tfSites t 가 더 새것) 그것 */
    fun tfShips(c: Context, done: (List<TfShip>) -> Unit) {
        tfMem?.let { done(it); return }
        var baseT = 0L
        val base0 = try { JSONObject(c.assets.open("tf_sites.json").bufferedReader().readText()).let { baseT = it.optLong("t"); tfParse(it.optJSONArray("j")?.toString()) } } catch (_: Exception) { emptyList() }
        // v6.92 (409): 도메인(talla.co.kr/data/tf_sites.json)에 더 새것이 있으면 그것 (폰 파일 · 12시간마다 새로)
        val f = java.io.File(c.filesDir, "tf_sites.json")
        var base = base0
        try { if (f.exists()) JSONObject(f.readText()).let { o -> if (o.optLong("t") >= baseT) { val l = tfParse(o.optJSONArray("j")?.toString()); if (l.size >= 100) { base = l; baseT = o.optLong("t") } } } } catch (_: Exception) {}
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 12 * 3600_000L) Thread {
            try { val t = get("https://talla.co.kr/data/tf_sites.json"); if ((JSONObject(t).optJSONArray("j")?.length() ?: 0) >= 100) f.writeText(t) } catch (_: Exception) {}
        }.start()
        refreshTfSeen(c)
        com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("config").document("tfSites").get()
            .addOnSuccessListener { d ->
                // 관리자가 새로 모은 목록: 주소를 못 찾은 배(자기 도메인 선단 등)는 기본 목록 주소로 채움
                val bu = base.associateBy { it.uid }
                val srv = if ((d.getLong("t") ?: 0L) > baseT) tfParse(d.getString("j")).map { x -> if (x.host.isBlank()) bu[x.uid]?.let { b -> TfShip(x.uid, x.ship, x.area, x.fleet, x.fish, x.seat, b.host) } ?: x else x }.filter { it.host.isNotBlank() } else emptyList()
                val l = withSeen(c, if (srv.size >= 100) srv else base)
                tfMem = l; done(l)
            }.addOnFailureListener { val l = withSeen(c, base); tfMem = l; done(l) }
    }
    /** v6.92 (409): 다른 사람 폰이 선단 화면에서 찾은 배 (tfSeen · 12시간마다) → 목록에 자동으로 */
    private fun refreshTfSeen(c: Context) {
        val p = prefs(c)
        if (System.currentTimeMillis() - p.getLong("tf_srv_at", 0L) < 12 * 3600_000L || Cloud.uid == null) return
        com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("tfSeen").get().addOnSuccessListener { q ->
            val o = JSONObject()
            q.documents.forEach { d ->
                if (!isTf(d.id)) return@forEach
                @Suppress("UNCHECKED_CAST") val m = d.get("s") as? Map<String, Any?> ?: return@forEach
                val j = JSONObject(); m.forEach { (k, v) -> (v as? String)?.takeIf { it.isNotBlank() }?.let { j.put(k, it.take(40)) } }
                if (j.length() > 0) o.put(d.id, j)
            }
            p.edit().putString("tf_srv", o.toString()).putLong("tf_srv_at", System.currentTimeMillis()).apply()
            tfMem?.let { tfMem = withSeen(c, it) }
        }
    }
    /** 관리자: 더피싱 전체 목록 → 배마다 화면에서 선단 주소 → config/tfSites (몇 분 · 천천히) */
    fun tfCollect(progress: (String) -> Unit, done: (String) -> Unit) {
        Thread {
            try {
                val ships = LinkedHashMap<String, Array<String>>()
                val item = Regex("<a href=\"https://thefishing\\.kr/reservation/list\\.php\\?uid=(\\d+)\">(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
                fun cls(c: String, k: String) = Regex("class=\"$k[^\"]*\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL).find(c)?.let { tfText(it.groupValues[1]) } ?: ""
                for (pg in 1..60) {
                    val h = try { get("https://thefishing.kr/reservation/list.php?page=$pg") } catch (_: Exception) { break }
                    var neu = 0
                    item.findAll(h).forEach { m ->
                        val c = m.groupValues[2]; if (!c.contains("re_list_ship")) return@forEach
                        val u = m.groupValues[1]; if (u in ships) return@forEach
                        ships[u] = arrayOf(u, cls(c, "re_list_ship"), cls(c, "re_list_area"), cls(c, "re_list_kind"), cls(c, "re_list_fish"), cls(c, "re_list_price"), ""); neu++
                    }
                    progress("목록 ${pg}쪽 · 배 ${ships.size}척")
                    if (neu == 0) break
                    Thread.sleep(400)
                }
                val hostRe = Regex("https?://([a-z0-9-]+)\\.thefishing\\.kr/index\\.php\\?mid=bk")
                var k = 0
                ships.values.forEach { a ->
                    k++
                    try { hostRe.find(get("https://thefishing.kr/reservation/list.php?uid=${a[0]}"))?.let { a[6] = it.groupValues[1] + ".thefishing.kr" } } catch (_: Exception) {}
                    if (k % 10 == 0) progress("배 화면 $k / ${ships.size} · 선단 주소 ${ships.values.count { it[6].isNotBlank() }}곳")
                    Thread.sleep(300)
                }
                val arr = JSONArray(); ships.values.forEach { a -> arr.put(JSONArray(a.toList())) }
                val nh = ships.values.map { it[6] }.filter { it.isNotBlank() }.distinct().size
                com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("config").document("tfSites")
                    .set(mapOf("j" to arr.toString(), "t" to System.currentTimeMillis(), "n" to ships.size, "hosts" to nh))
                    .addOnSuccessListener { tfMem = null; done("✓ 더피싱 배 ${ships.size}척 · 선단 ${nh}곳 저장 (모든 앱이 ⚙ 선단에서 골라 넣을 수 있어요)") }
                    .addOnFailureListener { e -> done("저장 실패: ${e.message}") }
            } catch (e: Exception) { done("실패: ${e.message} (더피싱 접속을 확인해 주세요)") }
        }.start()
    }

    // ─────────────── v6.82 (390): 배 선단 사이트 · 밴드 · 전화 ───────────────
    // talla.co.kr/data/seat_sites.json (GitHub 'Seat sites' 가 하루 한 번 조황 글에서 모음) → 폰 파일 · 하루 한 번 새로
    // 파일에 없는 배만 폰이 직접 조황 글을 읽어 찾음 (prefs seat_site_<no> = host|band|tel|yyyyMMdd) · 직접 넣은 주소 = seat_site_m_<no>
    class ShipSite(val host: String?, val band: String?, val tel: String?, val name: String = "", val port: String = "", val posts: Int = -1)
    private const val SITES_URL = "https://talla.co.kr/data/seat_sites.json"
    @Volatile private var sitesMem: Map<String, ShipSite>? = null
    private val siteTried = HashSet<String>()
    private fun sitesFile(c: Context) = java.io.File(c.filesDir, "seat_sites.json")
    private fun parseSites(t: String): Map<String, ShipSite> {
        val o = JSONObject(t).optJSONObject("ships") ?: return emptyMap()
        val m = HashMap<String, ShipSite>()
        o.keys().forEach { k -> val v = o.optJSONObject(k) ?: return@forEach
            m[k] = ShipSite(v.optString("h").ifBlank { null }, v.optString("b").ifBlank { null }, v.optString("t").ifBlank { null }, v.optString("n"), v.optString("p"), v.optInt("c", -1)) }
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
        if (s.host == S24) siteOf(c, s.shipNo)?.host?.let { fleetDay(it, s.schNo, s.date) } ?: bookUrl(s) else bookUrl(s)
    fun siteUrl(host: String, date: String) = "https://$host/ship/schedule_fleet/" + (date.replace("-", "").take(6).ifBlank { ymF.format(Date()) })

    // ─────────────── 🔔 자리 나면 알림 (15분마다 · 내 폰) ───────────────
    /** v6.76: 알림은 관리자가 체크한 사람만 (Cloud.PERMS 'seatAlarm') · 뒤에서 확인할 땐 마지막으로 본 권한(prefs seat_perm) */
    // ─────────────── v7.01 (382): 🤖 자동 예약 (관리자 허락 · 지금은 헌터호처럼 로그인 없는 개인 사이트 · 무통장) ───────────────
    const val PERM_AUTO = "seatAuto"
    fun canAuto(c: Context): Boolean = if (Cloud.isAdmin || Cloud.myPerms.isNotEmpty()) Cloud.can(PERM_AUTO) else prefs(c).getBoolean("seat_p_auto", false)
    /** 자동 예약 되는 사이트 (예약 칸을 맞춰 둔 곳) */
    fun autoSite(host: String) = host == "hunter-booking.onrender.com"
    private fun autoKey(w: Watch) = "${w.host}|${w.shipNo}|${w.date}"
    fun autoOn(c: Context, w: Watch) = autoKey(w) in (prefs(c).getStringSet("seat_auto", emptySet()) ?: emptySet())
    fun setAuto(c: Context, w: Watch, on: Boolean) {
        val set = HashSet(prefs(c).getStringSet("seat_auto", emptySet()) ?: emptySet())
        if (on) set.add(autoKey(w)) else set.remove(autoKey(w))
        prefs(c).edit().putStringSet("seat_auto", set).apply()
    }
    /** 예약자 정보 (폰에만 · 서버에 안 올림) */
    fun autoInfo(c: Context): Triple<String, String, String> = prefs(c).let { Triple(it.getString("seat_auto_name", "") ?: "", it.getString("seat_auto_tel", "") ?: "", it.getString("seat_auto_payer", "") ?: "") }
    private fun night() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 23 || it < 7 }
    /** 실제 예약 신청 (헌터호 /reserve · 무통장) → 성공하면 받은 글 한 줄 · 실패면 null */
    private fun autoBook(c: Context, w: Watch, s: Sch): String? {
        val (name, tel, _) = autoInfo(c)
        if (name.isBlank() || tel.length < 10) return null
        if (w.host != "hunter-booking.onrender.com") return null
        val body = listOf("program" to w.shipNo, "date" to w.date, "people" to w.need.toString(), "name" to name, "phone" to tel.filter { it.isDigit() },
            "captain_request" to "yes", "refund_agree" to "on").joinToString("&") { (k, v) -> k + "=" + java.net.URLEncoder.encode(v, "UTF-8") }
        return try {
            val cn = URL("https://${w.host}/reserve").openConnection() as HttpURLConnection
            cn.requestMethod = "POST"; cn.doOutput = true; cn.instanceFollowRedirects = true
            cn.connectTimeout = 20000; cn.readTimeout = 40000
            cn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            cn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Mobile")
            cn.outputStream.use { it.write(body.toByteArray()) }
            val code = cn.responseCode
            val txt = try { (if (code < 400) cn.inputStream else cn.errorStream)?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
            cn.disconnect()
            if (code !in 200..399) return null
            val plain = android.text.Html.fromHtml(txt, 0).toString().replace(Regex("\\s+"), " ")
            if (Regex("마감|실패|오류|부족|초과|error", RegexOption.IGNORE_CASE).containsMatchIn(plain.take(400)) && !plain.contains("완료")) return null
            Regex("[^.!?\n]{0,60}(완료|접수|신청)[^.!?\n]{0,60}").find(plain)?.value?.trim() ?: "예약 신청을 보냈어요"
        } catch (_: Exception) { null }
    }
    private fun notifyBooked(c: Context, w: Watch, s: Sch, msg: String?) {
        channel(c)
        val req = 7900 + (w.host + w.shipNo + w.date).hashCode().and(0xFFF)
        val pi = PendingIntent.getActivity(c, req, Intent(Intent.ACTION_VIEW, Uri.parse(bookUrl(w))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val ok = msg != null
        val title = if (ok) "✅ ${dayLabel(w.date)} ${w.ship} ${w.need}명 예약 신청 완료" else "⚠ ${dayLabel(w.date)} ${w.ship} 자동 예약 실패"
        val text = if (ok) "${msg} · 사이트 안내대로 입금하세요 (입금해야 확정) · 눌러서 사이트 열기" else "자리는 났는데 자동 신청이 안 됐어요 · 눌러서 직접 예약해 주세요"
        val b = Notification.Builder(c, CH).setSmallIcon(R.drawable.ic_stat_octo)
            .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi).setAutoCancel(true).setShowWhen(true).setCategory(Notification.CATEGORY_REMINDER)
        try { c.getSystemService(NotificationManager::class.java).notify(req, b.build()) } catch (_: SecurityException) {}
    }
    /** 예약자 정보 입력 창 */
    fun openAutoInfo(a: Activity, done: () -> Unit) {
        fun dp(v: Int) = Ui.dp(a, v)
        val sh = Sheet(a, 0.7f)
        sh.head.addView(Ui.text(a, 17f, Ui.TEXT, true, "🤖 자동 예약 정보"), LinearLayout.LayoutParams(0, WRAP, 1f))
        val (n0, t0, p0) = autoInfo(a)
        fun ed(hint: String, v: String, num: Boolean = false) = android.widget.EditText(a).apply {
            this.hint = hint; setText(v); setSingleLine(); setTextColor(Ui.TEXT); setHintTextColor(Ui.SUB); textSize = 15f
            if (num) inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        val eN = ed("예약자 이름", n0); val eT = ed("휴대전화 (01012345678)", t0, true); val eP = ed("입금자명 (비우면 예약자 이름)", p0)
        sh.body.addView(Ui.text(a, 12.5f, Ui.SUB, false, "자리 나면 알림을 건 배 중 [🤖 자동]을 켠 것은 자리가 나면 이 정보로 예약 신청까지 해요 (무통장 · 카드 결제 없음 · 입금해야 확정).\n• 지금 되는 곳: 거북섬 헌터호 (다른 사이트는 맞춰 넣으면 추가)\n• 밤 11시 ~ 아침 7시는 신청하지 않고 알림만 (입금 기한 놓침 방지)\n• 같은 날 · 같은 배는 한 번만 · 정보는 이 폰에만 저장"))
        listOf(eN, eT, eP).forEach { sh.body.addView(it, Ui.lp(a, MATCH, WRAP, top = 8)) }
        sh.body.addView(Ui.button(a, "💾 저장", true) {
            prefs(a).edit().putString("seat_auto_name", eN.text.toString().trim()).putString("seat_auto_tel", eT.text.toString().filter { it.isDigit() })
                .putString("seat_auto_payer", eP.text.toString().trim()).apply()
            Ui.toast(a, "저장했어요"); sh.close(); done()
        }.apply { minHeight = dp(46) }, Ui.lp(a, MATCH, WRAP, top = 12))
        sh.show()
    }

    const val PERM = "seatAlarm"
    fun allowed(c: Context): Boolean = if (Cloud.isAdmin || Cloud.myPerms.isNotEmpty()) Cloud.can(PERM) else prefs(c).getBoolean("seat_perm", false)
    /** 권한 다시 받아 기억 · 없어졌으면 걸어 둔 알림 지움 */
    fun syncPerm(c: Context, done: () -> Unit) {
        fun apply() {
            val ok = Cloud.can(PERM)
            prefs(c).edit().putBoolean("seat_perm", ok).putBoolean("seat_p_s24", Cloud.can(PERM_S24)).putBoolean("seat_p_tf", Cloud.can(PERM_TF)).putBoolean("seat_p_auto", Cloud.can(PERM_AUTO)).apply()
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
        appCtx = c.applicationContext
        if (!prefs(c).getBoolean("seat_perm", false)) return 0
        val today = ymd.format(Date())
        val ws = watches(c).filter { it.date >= today }.toMutableList()
        var fired = 0
        val byMonth = ws.filter { it.via == "phone" }.groupBy { it.host to it.date.replace("-", "").take(6) }
        for ((k, list) in byMonth) {
            if (k.first == S24) {
                val s24ok = prefs(c).getBoolean("seat_p_s24", false)
                for (w in list) {
                    // v6.92 (410): 선상24 권한이 없으면 그 배 선단 홈페이지 일정으로 (선단 주소를 모르면 건너뜀)
                    val fh = if (s24ok) null else siteOf(c, w.shipNo)?.host ?: continue
                    val sch = try { if (fh != null) month(fh, k.second, fresh = true) else s24Month(w.shipNo, k.second) } catch (_: Exception) { continue }
                    val s = sch.firstOrNull { it.date == w.date && (fh == null || it.shipNo == w.shipNo) } ?: continue
                    val ok = s.open && s.remain >= w.need
                    if (ok && !w.hit) { notify(c, w, s); fired++ }
                    w.hit = ok; w.last = s.remain; w.at = System.currentTimeMillis(); rememberSch(c, w, s)
                }
                continue
            }
            val sch = try { month(k.first, k.second, fresh = true) } catch (_: Exception) { continue }
            for (w in list) {
                val s = sch.firstOrNull { it.shipNo == w.shipNo && it.date == w.date } ?: continue
                val ok = s.open && s.remain >= w.need
                // v7.01 (382): 🤖 자동 예약 켠 배 = 자리 나면 신청까지 (허락 · 밤 아님 · 한 번만)
                if (ok && !w.hit && autoSite(w.host) && autoOn(c, w) && prefs(c).getBoolean("seat_p_auto", false) && !night()) {
                    val msg = autoBook(c, w, s)
                    notifyBooked(c, w, s, msg); setAuto(c, w, false); fired++
                } else if (ok && !w.hit) { notify(c, w, s); fired++ }
                w.hit = ok; w.last = s.remain; w.at = System.currentTimeMillis(); rememberSch(c, w, s)
            }
        }
        saveWatches(c, ws)
        prefs(c).edit().putLong("seat_check_at", System.currentTimeMillis()).apply()
        return fired
    }

    // v6.91 (405): 알림 · 🔔 목록 '예약 ›' = 그 날 일정 예약 화면 (일정 번호 기억 · 모르면 달 페이지)
    private fun rememberSch(c: Context, w: Watch, s: Sch) { if (s.schNo.isNotBlank()) prefs(c).edit().putString("seat_wsch_${w.host}|${w.shipNo}|${w.date}", s.schNo).apply() }
    fun watchUrl(c: Context, w: Watch): String {
        val no = prefs(c).getString("seat_wsch_${w.host}|${w.shipNo}|${w.date}", "") ?: ""
        if (no.isBlank() || isTf(w.host)) return bookUrl(w)
        return bookUrlOf(c, Sch(w.host, w.shipNo, no, w.ship, w.date, "", "", "", 0, 0, "ING", ""))
    }
    private fun notify(c: Context, w: Watch, s: Sch) {
        channel(c)
        val req = 7700 + (w.host + w.shipNo + w.date).hashCode().and(0xFFF)
        val pi = PendingIntent.getActivity(c, req, Intent(Intent.ACTION_VIEW, Uri.parse(if (isTf(w.host) || s.schNo.isBlank()) bookUrl(w) else bookUrlOf(c, s)))
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
        appCtx = a.applicationContext
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
        // v6.92 (410): 선상24 · 더피싱 전체는 권한 있는 사람이 칩을 켰을 때만 (처음 꺼짐)
        var useS24 = s24On(a)
        var useTf = tfOn(a)
        val areaSel = HashSet(p.getStringSet("seat_area", emptySet()) ?: emptySet())
        val s24 = ArrayList<Sch>()
        var s24Key = ""; var s24Total = 0; var s24Pg = 0; var s24Busy = false; var s24Err = false
        val tfAll = ArrayList<Sch>(); var tfKey = ""; var tfBusy = false; var tfHosts = 0
        // v6.95: 📍 지역의 선단 전부 (모두에게 · 하루치만 · 6곳씩)
        val areaAll = ArrayList<Sch>(); var areaKey = ""; var areaLeft = 0; var areaTotal = 0; var areaGen = 0
        val areaRest = ArrayList<DirF>(); var areaSkip = 0; var areaReadFn: (List<DirF>) -> Unit = {}
        var areaEx: java.util.concurrent.ExecutorService? = null
        if (areaSel.isEmpty() && !p.getBoolean("seat_area_auto", false)) {
            p.edit().putBoolean("seat_area_auto", true).apply()
            Spot.base(a, null, null)?.let { (la, lo) -> nearestArea(la, lo)?.let { areaSel.add(it); p.edit().putStringSet("seat_area", HashSet(areaSel)).apply() } }
        }
        if (canS24(a)) Thread { try { s24Filter(a) } catch (_: Exception) {} }.start()
        val fails = ArrayList<String>()
        var loading = false
        var loadGen = 0; var loadDone = 0; var loadTotal = 0
        var siteBusy = false
        val extraBusy = HashSet<String>(); val voteAsked = HashSet<String>()
        val pool = java.util.concurrent.ThreadPoolExecutor(3, 3, 20, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue()).apply { allowCoreThreadTimeOut(true) }
        var rerender = false
        lateinit var laterFn: () -> Unit
        fun later() = laterFn()

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
        // v6.91 (404): 정렬은 따로 · 📝 조황 많은 순 · 🕘 최근 조황 순
        val sortBtn = Ui.pill(a, "↕ 자리 많은 순 ▾", false, Ui.TEXT, small = true)
        // v6.85 (393): 🔎 배 이름 찾기
        val qBtn = Ui.pill(a, "🔎", false, Ui.TEXT, small = true)
        val s24Chip = Ui.pill(a, "🚤 선상24", false, Ui.TEXT, small = true)
        val tfChip = Ui.pill(a, "🎣 더피싱", false, Ui.TEXT, small = true)
        listOf(qBtn, s24Chip, tfChip, areaBtn, fishBtn, needBtn, sortBtn, filtBtn).forEach { pick.addView(it, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) }) }
        var q = ""
        val qRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), dp(6)); visibility = View.GONE }
        val qEd = EditText(a).apply {
            hint = "배 이름 · 항구 (예: 뉴블루, 마검포항)"; setSingleLine(); textSize = 15f; setTextColor(Ui.TEXT); setHintTextColor(Ui.SUB)
            background = Ui.bg(a, Color.parseColor("#0F2231"), 12, Color.parseColor("#7FD6FF")); setPadding(dp(12), dp(9), dp(12), dp(9))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        qRow.addView(qEd, LinearLayout.LayoutParams(0, WRAP, 1f))
        val qX = Ui.pill(a, "✕", false, Ui.TEXT, small = true)
        qRow.addView(qX, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
        root.addView(qRow)
        val found = HashMap<String, List<Sch>>()      // 이름으로 찾은 배 · 두 달 일정
        val foundBusy = HashSet<String>()
        // v6.92 (408): 🔎 찾기 전용 빠른 통로 (6개 · 카드 사진 · 공지 받기 뒤에 줄 서지 않게) · 15초 넘으면 '못 읽었어요 · 다시 ↻'
        val qPool = java.util.concurrent.Executors.newFixedThreadPool(6)
        val foundErr = HashSet<String>()
        fun qFetch(k: String, f: () -> List<Sch>) {
            if (found.containsKey(k) || k in foundErr || !synchronized(foundBusy) { foundBusy.add(k) }) return
            qPool.execute {
                val ft = java.util.concurrent.FutureTask(f)
                Thread(ft).start()
                val r = try { ft.get(15, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { ft.cancel(true); null }
                a.runOnUiThread {
                    if (r == null) foundErr.add(k) else found[k] = r
                    synchronized(foundBusy) { foundBusy.remove(k) }
                    if (dlg.isShowing) later()
                }
            }
        }
        val sum = Ui.text(a, 12f, Ui.SUB, false, "").apply { setPadding(dp(16), 0, dp(16), dp(4)) }
        root.addView(sum)
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(24)) }
        root.addView(ScrollView(a).apply { addView(list) }, LinearLayout.LayoutParams(MATCH, 0, 1f))

        lateinit var render: () -> Unit
        lateinit var load: (Boolean) -> Unit
        lateinit var loadS24: (Boolean) -> Unit
        laterFn = { if (!rerender) { rerender = true; list.postDelayed({ rerender = false; if (dlg.isShowing) render() }, 500) } }
        refreshSites(a) { a.runOnUiThread { if (dlg.isShowing) render() } }
        refreshExtras(a) { ch -> if (ch) a.runOnUiThread { if (dlg.isShowing) load(false) } }

        fun savePrefs() = p.edit().putStringSet("seat_area", HashSet(areaSel)).putString("seat_day", day).putInt("seat_need", need).putInt("seat_sort", sort)
            .putBoolean("seat_open", openOnly).putBoolean("seat_favtop", favTop).putStringSet("seat_fish", HashSet(fishSel)).apply()

        // v6.86 (395): 출항 시간대 · 운항 시간 (시간 모르는 배는 숨기지 않음)
        val todSel = HashSet(p.getStringSet("seat_tod", emptySet()) ?: emptySet())   // dawn · am · pm · night
        var durSel = p.getInt("seat_dur", 0)                                          // 0 전체 · 1 ~5h · 2 5~9h · 3 9h~
        fun todOf(m: Int) = when { m < 0 -> ""; m < 6 * 60 -> "dawn"; m < 12 * 60 -> "am"; m < 18 * 60 -> "pm"; else -> "night" }
        fun timeMatch(s: Sch): Boolean {
            val m = s.startMin; val h = s.hours
            if (todSel.isNotEmpty() && m >= 0 && todOf(m) !in todSel) return false
            if (durSel != 0 && h > 0 && when (durSel) { 1 -> h > 5.0; 2 -> h < 5.0 || h > 9.0; else -> h < 9.0 }) return false
            return true
        }
        // v7.03 (427): 최근 조과 = 선단 홈페이지 조과 글 마지막 날짜 (이 배 글 · 없으면 선단 글) · null = 아직 모름 · "" = 글 없음
        var postDays = p.getInt("seat_pdays", 0)   // 0 = 거르지 않음 · N = 최근 N일 안 조과 있는 배만
        val postAsked = HashSet<String>()
        fun lastPost(x: Sch): String? {
            if (x.host == S24) return lastBoardAny(a, x.shipNo) ?: siteOf(a, x.shipNo)?.host?.let { h -> postsCached(h)?.let { ps -> (postsOf(ps, x.ship).firstOrNull() ?: ps.firstOrNull())?.date ?: "" } }
            if (!isS24Fleet(x.host) && !isTf(x.host)) return ""
            val ps = postsCached(x.host) ?: return null
            return (postsOf(ps, x.ship).firstOrNull() ?: ps.firstOrNull())?.date ?: ""
        }
        /** 모르는 선단 조과 글을 6곳씩 받아 오기 (1시간 기억) */
        fun askPosts(l: List<Sch>) {
            l.mapNotNull { x -> if (x.host == S24) siteOf(a, x.shipNo)?.host else x.host.takeIf { isS24Fleet(it) || isTf(it) } }
                .distinct().filter { postsCached(it) == null && synchronized(postAsked) { postAsked.add(it) } }.take(6)
                .forEach { h -> pool.execute { fleetPosts(h); a.runOnUiThread { later() } } }
        }
        fun postMatch(x: Sch): Boolean {
            if (postDays <= 0) return true
            if (lastPost(x) == null) askPosts(listOf(x))
            val d = lastPost(x) ?: return true          // 아직 모르면 보여 주고 받아 오는 중
            return d.isNotBlank() && daysAgo(d) in 0..postDays
        }
        fun fishMatch(s: Sch) = postMatch(s) && timeMatch(s) && (fishSel.isEmpty() || fishSel.any { f -> s.fish.split(',', '/', '·', ' ').any { it.trim() == f } || s.fish.contains(f) })
        fun fishList(s: Sch) = s.fish.split(',', '/', '·').map { it.trim() }.filter { it.isNotBlank() }
        // v6.91 (404): 정렬 5가지 · 최근 조황 순은 모르는 배의 마지막 조황 날짜를 3척씩 받아 오며 다시 정렬
        val lbAsked = HashSet<String>()
        // v6.91 (407): 카드 20척씩 · [⬇ 더 보기]
        var shownN = 20; var pageKey = ""
        fun pageReset(k: String) { if (k != pageKey) { pageKey = k; shownN = 20 } }
        fun moreBtn(n: Int, total: Int) = Ui.button(a, "⬇ 더 보기 ($n / ${total}척)") { shownN += 20; render() }.apply { minHeight = dp(46); textSize = 14f }
        fun boardsOf(x: Sch) = if (x.host != S24) -1 else maxOf(x.boards, boardTotal(a, x.shipNo))
        fun sortList(l: List<Sch>): List<Sch> = when (sort) {
            1 -> l.sortedWith(compareBy({ !it.open }, { it.time.ifBlank { "99" } }, { it.ship }))
            2 -> l.sortedBy { it.ship }
            3 -> l.sortedWith(compareBy({ -boardsOf(it) }, { !(it.open && it.remain >= need) }, { it.ship }))
            4 -> {
                val miss = l.filter { it.host == S24 && lastBoardAny(a, it.shipNo) == null && synchronized(lbAsked) { lbAsked.add(it.shipNo) } }.take(40)
                miss.forEach { x -> pool.execute { lastBoard(a, x.shipNo, true); a.runOnUiThread { later() } } }
                l.sortedWith(compareBy({ -(lastBoardAny(a, it.shipNo)?.replace("-", "")?.toIntOrNull() ?: 0) }, { !(it.open && it.remain >= need) }, { it.ship }))
            }
            5 -> {
                askPosts(l)
                l.sortedWith(compareBy({ x -> lastPost(x)?.takeIf { it.isNotBlank() }?.let { -(it.replace("-", "").toIntOrNull() ?: 0) } ?: 0 }, { !(it.open && it.remain >= need) }, { it.ship }))
            }
            else -> l.sortedWith(compareBy({ !(it.open && it.remain >= need) }, { -it.remain }, { it.ship }))
        }

        // v6.85 (393): 날짜 버튼 숫자에 선상24 배도 (아래 '빈 배 N척'과 같게)
        fun countOn(d: String): Int {
            val mine = all.filter { x -> x.date == d }
            val nos = mine.map { it.shipNo }.toSet()
            return dedupe(a, mine + (if (useS24) s24.filter { it.date == d && it.shipNo !in nos } else emptyList()) + (if (useTf) tfAll.filter { it.date == d } else emptyList()) + areaAll.filter { it.date == d }).count { x -> x.open && x.remain >= need && fishMatch(x) }
        }
        fun setDay(d: String) { day = d; savePrefs(); render(); load(false) }
        fun shift(n: Int) {
            val c = Calendar.getInstance().apply { time = ymd.parse(day) ?: Date(); add(Calendar.DAY_OF_MONTH, n) }
            val d = ymd.format(c.time); if (d >= ymd.format(Date())) setDay(d)
        }
        fun paintDates() {
            val dd = try { ymd.parse(day)!! } catch (_: Exception) { Date() }
            val n = countOn(day)
            val t0 = Calendar.getInstance().apply { time = dd; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            dayBtn.text = "📅 " + SimpleDateFormat("M월 d일 (E)", Locale.KOREA).format(dd) + " · " + DatePick.mulShort(t0) + (if (all.isNotEmpty() || s24.isNotEmpty()) " · 빈 배 ${n}척" else "") + " ▾"
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
                repeat(lead) { row.addView(View(a), LinearLayout.LayoutParams(0, dp(62), 1f)); cell++ }
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
                    // v6.86 (396): 날짜마다 물때 (사리 주황 · 조금 무렵 보라)
                    val t0 = (c.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                    box.addView(Ui.text(a, 9.5f, if (on) Color.WHITE else DatePick.mulCol(t0), false, DatePick.mulShort(t0)).apply { gravity = Gravity.CENTER; includeFontPadding = false })
                    row.addView(box, LinearLayout.LayoutParams(0, dp(62), 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
                    if (++cell % 7 == 0) flush()
                }
                if (cell % 7 != 0) { repeat(7 - cell % 7) { row.addView(View(a), LinearLayout.LayoutParams(0, dp(62), 1f)) }; flush() }
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
            // v6.86 (395 · 396): 선상24 배는 한 달 일정에서 공지 · 오픈 시각 · 물때 채우기 (보이는 카드만 · 한 시간 기억)
            val ym0 = s.date.replace("-", "").take(6)
            if (s.host == S24) {
                val ex = extraCached(s.shipNo, ym0)
                if (ex == null) { if (synchronized(extraBusy) { extraBusy.add(s.shipNo + ym0) }) pool.execute {
                    s24Extra(s.shipNo, ym0); lastBoard(a, s.shipNo, true); a.runOnUiThread { later() } } }
                else ex.firstOrNull { it.schNo == s.schNo }?.let { e ->
                    if (s.notice.isBlank()) s.notice = e.notice; if (s.openAt.isBlank()) s.openAt = e.openAt; if (s.tideW.isBlank()) s.tideW = e.tideW
                    if (s.img.isBlank()) s.img = e.img
                }
            }
            val ss = if (s.host == S24 || isS24Fleet(s.host)) siteOf(a, s.shipNo) else null
            val fleetName = if (s.host == S24) s.place.ifBlank { "선상24" } else fleets(a).firstOrNull { it.host == s.host }?.name?.ifBlank { null } ?: dirName(s.host) ?: s.host.substringBefore('.')
            // v6.97 (414): 사진 없으면 선단 홈페이지 대표 사진(og:image) · 30일 기억
            if (s.img.isBlank()) (if (s.host == S24) ss?.host else s.host)?.let { hh ->
                val u = fleetImg(a, hh)
                if (u == null) { if (synchronized(extraBusy) { extraBusy.add("i:$hh") }) pool.execute { findFleetImg(a, hh); a.runOnUiThread { later() } } }
                else if (u.isNotBlank()) s.img = u
            }
            val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
            // 배 사진 (선상24 작은 사진 · 없으면 🚤)
            val picBox = android.widget.FrameLayout(a).apply { background = Ui.bg(a, Color.parseColor("#1B3A52"), 12, Color.parseColor("#2C4F66")); clipToOutline = true }
            picBox.addView(Ui.text(a, 26f, Color.WHITE, false, "🚤").apply { gravity = Gravity.CENTER }, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
            if (s.img.isNotBlank()) {
                val iv = android.widget.ImageView(a).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP }
                picBox.addView(iv, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
                shipImg(a, s.img, iv)
                picBox.setOnClickListener { showPhoto(a, if (s.img.contains("_main/")) s.img else s.img.replace("_smini.", "_mini."), s.ship) }
            }
            head.addView(picBox, LinearLayout.LayoutParams(dp(62), dp(62)).apply { rightMargin = dp(10) })
            val info = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            val top = Ui.row(a)
            val vk = voteKey(s); val vv = votesCached(vk)
            val hot = vv != null && vv.up >= 5 && vv.up >= vv.dn * 3
            top.addView(TextView(a).apply {
                val sb = android.text.SpannableStringBuilder(s.ship)
                sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length, 0)
                if (hot) { val h = sb.length; sb.append(" 🔥"); sb.setSpan(android.text.style.RelativeSizeSpan(0.8f), h, sb.length, 0) }
                // v6.99 (418): 이름 옆 회색 선단 · 배 이름 글자 없앰 (아래 🌐 주소 줄로 충분)
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
            info.addView(top)
            val h = s.hours
            val timeTxt = if (s.time.isBlank()) "" else s.time + (if (h > 0) " (${if (h % 1.0 == 0.0) h.toInt().toString() else "%.1f".format(h)}시간)" else "")
            val tideTxt = listOf(s.tide, s.tideW).filter { it.isNotBlank() }.distinct().joinToString(" · ")
            info.addView(Ui.text(a, 12f, Color.parseColor("#B8CAD6"), false,
                listOf(s.fish, s.way, timeTxt, if (tideTxt.isNotBlank()) "🌊 $tideTxt" else "").filter { it.isNotBlank() }.joinToString(" · ")).apply { maxLines = 2 })
            // 금액 (노랑 굵게) · 📝 조황 N · 최근
            val l3 = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL }
            if (s.price > 0) l3.addView(Ui.text(a, 15f, Color.parseColor("#FFD36E"), true, "%,d원".format(s.price)), LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })
            if (s.host == S24 && s.boards <= 0) boardTotal(a, s.shipNo).takeIf { it > 0 }?.let { s.boards = it }
            if (s.host == S24 && s.boards >= 0) {
                val lb = lastBoard(a, s.shipNo, false)
                val ago = lb?.takeIf { it.length == 10 }?.let { d ->
                    val n = try { ((ymd.parse(ymd.format(Date()))!!.time - ymd.parse(d)!!.time) / 86400000L).toInt() } catch (_: Exception) { -1 }
                    when { n < 0 -> ""; n == 0 -> " · 오늘"; n == 1 -> " · 어제"; n < 30 -> " · ${n}일 전"; else -> " · ${n / 30}달 전" }
                } ?: ""
                val (bc, tc) = when { s.boards >= 100 -> Color.parseColor("#3A2410") to Color.parseColor("#FFB27A"); s.boards < 10 -> Color.TRANSPARENT to Ui.SUB; else -> Color.parseColor("#10324A") to Color.parseColor("#7FD6FF") }
                l3.addView(Ui.text(a, 11.5f, tc, true, "📝 조황 ${s.boards}$ago").apply {
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                    background = Ui.bg(a, bc, 99, if (s.boards >= 100) Ui.MINE else Color.parseColor("#3A6A88"))
                    setOnClickListener { openBoards(a, s) }
                })
            }
            // v6.90 (399): 출처 꼬리표 [선상24] [더피싱] [개인 사이트] (같은 배가 여러 곳이면 다)
            // v7.01 (423): 출처 꼬리표 [선상24] [더피싱] 없앰 (선단은 아래 🌐 주소 줄로)
            portOf(a, s).takeIf { it.isNotBlank() }?.let { pt ->
                l3.addView(Ui.text(a, 11f, Ui.SUB, false, "⚓ $pt"), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(2) })
            }
            if (l3.childCount > 0) info.addView(l3, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(3) })
            head.addView(info, LinearLayout.LayoutParams(0, WRAP, 1f))
            box.addView(head)
            // 📢 그날 공지 (노란 한 줄 · 누르면 전체)
            if (s.notice.isNotBlank()) box.addView(Ui.text(a, 12f, Color.parseColor("#F2C14E"), false, "📢 " + s.notice).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0)
                setOnClickListener { android.app.AlertDialog.Builder(a, R.style.FlAlert).setTitle("📢 ${s.ship} · ${dayLabel(s.date)}").setMessage(s.notice).setPositiveButton("닫기", null).show() }
            })
            // 🕙 예약 오픈 시각
            if (s.openAt.isNotBlank()) {
                val r = Ui.row(a).apply { setPadding(0, dp(6), 0, 0) }
                r.addView(Ui.text(a, 12.5f, Color.parseColor("#7FD6FF"), true, "🕙 " + openLabel(s.openAt) + " 예약 오픈"), LinearLayout.LayoutParams(0, WRAP, 1f))
                if (allowed(a)) r.addView(Ui.pill(a, if (openSet(a, s)) "🔔 알림 걸림" else "🔔 열리면 알림", openSet(a, s), Color.parseColor("#F2C14E"), small = true) { toggleOpen(a, s); render() })
                box.addView(r)
            }
            // v6.97 (416): 선단 홈페이지 조과 글 (이 배 글 · 없으면 선단 글) → 아래 '📝 조과 N · +N일' 버튼 · 🧭 탐사 꼬리표
            var fpMine: List<Post> = emptyList(); var fpAll: List<Post>? = null
            if (isS24Fleet(s.host) || isTf(s.host)) {
                fpAll = postsCached(s.host)
                if (fpAll == null) { if (synchronized(extraBusy) { extraBusy.add("p:" + s.host) }) pool.execute { fleetPosts(s.host); a.runOnUiThread { later() } } }
                else fpMine = postsOf(fpAll, s.ship)
            }
            val fpUse = fpMine.ifEmpty { fpAll ?: emptyList() }
            fpUse.firstOrNull { isScout(it) && daysAgo(it.date) in 0..14 }?.let { sp ->
                box.addView(Ui.text(a, 11.5f, Color.parseColor("#FFD36E"), true, "🧭 탐사 " + plusDays(sp.date) + " · " + sp.title).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0)
                    setOnClickListener { openFleetBoards(a, s.host, s.ship) }
                })
            }
            val col = when { !s.open -> Color.parseColor("#4A5966"); s.remain <= 3 -> Color.parseColor("#F2C14E"); else -> Color.parseColor("#2FBF8F") }
            // v6.97 (417): 막대 대신 선단 주소 한 줄 (누르면 홈페이지 · 꾹 = 주소 복사)
            val homeHost = if (s.host == S24) ss?.host else s.host
            if (homeHost != null) box.addView(Ui.text(a, 11.5f, Color.parseColor("#7FD6FF"), false, "🌐 " + homeHost.removePrefix("www.")).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, dp(2))
                setOnClickListener { Boats.openUrl(a, "https://$homeHost") }
                setOnLongClickListener {
                    (a.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("site", "https://$homeHost"))
                    Ui.toast(a, "주소를 복사했어요"); true
                }
            })
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
            // v6.96: 🏠 홈피 = 그 배 선단 홈페이지 첫 화면 (예약 › 옆)
            if (homeHost != null) bot.addView(Ui.pill(a, "🏠 홈피", false, Ui.TEXT, small = true) { Boats.openUrl(a, "https://$homeHost") }.apply { setTextColor(Color.parseColor("#7FD6FF")) },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
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
            // v6.86 (395): 버튼 줄 — 📞 전화 · 밴드 · 📝 조황 · 🌐 배 사이트 ··· 👍 👎
            val links = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL }
            val left = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL }
            fun link(t: String, f: () -> Unit) = left.addView(Ui.pill(a, t, false, Ui.TEXT, small = true) { f() }.apply { setTextColor(Color.parseColor("#7FD6FF")); setPadding(dp(9), 0, dp(9), 0) },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(5) })
            ss?.tel?.let { t -> link("📞 전화") { dial(a, t) } }
            ss?.band?.let { b -> link("밴드") { Boats.openUrl(a, b) } }
            if (s.host == S24) link("📝 조황") { openBoards(a, s) }
            if (isS24Fleet(s.host) || isTf(s.host)) {
                // v6.97 (416): [📝 조과 1,204 · +3일] · v7.01 (422) 더피싱 = 게시판 맨 위 번호 (글 수 = talla.co.kr 매일 · 날짜 = 선단 홈페이지 첫 글 · 초록 3일 · 노랑 14일 · 회색)
                val cnt = if (isTf(s.host)) tfPostCount(s.host) else sites(a)[s.shipNo]?.posts ?: -1
                val last = fpUse.firstOrNull()?.date ?: ""
                val dn = daysAgo(last)
                val t = "📝 조과" + (if (cnt > 0) " %,d".format(cnt) else "") + (if (last.isNotBlank() && dn >= 0) " · " + plusDays(last) else "")
                val tc = when { dn < 0 -> Color.parseColor("#7FD6FF"); dn <= 3 -> Color.parseColor("#2FBF8F"); dn <= 14 -> Color.parseColor("#F2C14E"); else -> Color.parseColor("#8A99A6") }
                left.addView(Ui.pill(a, t, false, Ui.TEXT, small = true) { openFleetBoards(a, s.host, s.ship) }.apply {
                    setTextColor(tc); setPadding(dp(9), 0, dp(9), 0); if (dn in 0..14) background = Ui.bg(a, Color.TRANSPARENT, 99, tc)
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(5) })
            }
            // v6.90 (399): 같은 배가 다른 곳에도 있으면 그쪽 예약 버튼 (남은 자리 같이)
            s.also.forEach { o -> link((if (srcOf(o.host) == srcOf(s.host) && o.host != S24) (dirName(o.host) ?: o.host.substringBefore('.')) else srcOf(o.host)) + " " + (if (o.open) "${o.remain}자리 ›" else "${o.stName} ›")) { Boats.openUrl(a, bookUrlOf(a, o)) } }
            if (ss?.host != null && s.host == S24 && !canBook) link("🌐 사이트") { Boats.openUrl(a, siteUrl(ss.host, s.date)) }
            links.addView(android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(left) }, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (vv == null && synchronized(voteAsked) { voteAsked.add(vk) }) loadVotes(vk) { a.runOnUiThread { later() } }
            fun thumb(up: Boolean): TextView {
                val n = if (vv == null) "" else " " + (if (up) vv.up else vv.dn)
                val on = vv != null && vv.mine == (if (up) 1 else -1)
                val c0 = if (up) Color.parseColor("#2FBF8F") else Color.parseColor("#FF8A8A")
                return Ui.text(a, 13f, if (on) Color.WHITE else c0, true, (if (up) "👍" else "👎") + n).apply {
                    gravity = Gravity.CENTER; setPadding(dp(9), dp(4), dp(9), dp(4))
                    background = if (on) Ui.bg(a, c0, 99) else Ui.bg(a, Color.TRANSPARENT, 99, c0)
                    setOnClickListener {
                        if (Cloud.uid == null) { Ui.toast(a, "로그인하면 누를 수 있어요"); return@setOnClickListener }
                        val want = if (on) 0 else if (up) 1 else -1
                        vote(vk, want) { ok -> a.runOnUiThread { if (!ok) Ui.toast(a, "저장하지 못했어요 (잠시 뒤 다시)"); render() } }
                    }
                }
            }
            links.addView(thumb(true), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
            links.addView(thumb(false), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(5) })
            box.addView(links, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
            box.setOnLongClickListener { shipMenu(a, s, { render() }) { load(true) }; true }
            return box
        }

        render = render@{
            paintDates()
            fishBtn.text = when (fishSel.size) { 0 -> "🐟 어종 전체 ▾"; 1 -> "🐟 ${fishSel.first()} ▾"; else -> "🐟 ${fishSel.first()} 외 ${fishSel.size - 1} ▾" }
            fishBtn.setTextColor(if (fishSel.isEmpty()) Ui.TEXT else Color.parseColor("#7FD6FF"))
            areaBtn.text = when (areaSel.size) { 0 -> "📍 지역 전체 ▾"; 1 -> "📍 ${areaSel.first()} ▾"; else -> "📍 ${areaSel.first()} 외 ${areaSel.size - 1} ▾" }
            areaBtn.setTextColor(if (areaSel.isEmpty()) Ui.TEXT else Color.parseColor("#7FD6FF"))
            areaBtn.visibility = View.VISIBLE
            // v6.92 (410): 관리자 · 권한 있는 사람만 [🚤 선상24] [🎣 더피싱] 칩
            fun chipLook(c0: TextView, on: Boolean, col: Int) { c0.background = if (on) Ui.bg(a, col, 99) else Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE); c0.setTextColor(if (on) Color.WHITE else Ui.TEXT) }
            s24Chip.visibility = if (canS24(a)) View.VISIBLE else View.GONE
            tfChip.visibility = View.GONE; useTf = false   // v6.95: 더피싱 선단은 📍 지역으로 모두에게
            if (!canS24(a)) useS24 = false
            if (!canTf(a)) useTf = false
            chipLook(s24Chip, useS24, Color.parseColor("#3D7BD9")); chipLook(tfChip, useTf, Color.parseColor("#2FA06F"))
            needBtn.text = "👥 ${need}자리 이상 ▾"
            needBtn.setTextColor(if (need > 1) Color.parseColor("#7FD6FF") else Ui.TEXT)
            val nFilt = (if (openOnly) 1 else 0) + (if (todSel.isNotEmpty()) 1 else 0) + (if (durSel != 0) 1 else 0) + (if (postDays > 0) 1 else 0) + (if (sort == 5) 1 else 0)
            filtBtn.text = "⚙ 필터" + (if (nFilt > 0) " · $nFilt" else "")
            filtBtn.setTextColor(if (nFilt > 0) Color.parseColor("#7FD6FF") else Ui.TEXT)
            sortBtn.text = "↕ " + SORTS[sort] + " ▾"
            alarmBtn.visibility = if (allowed(a)) View.VISIBLE else View.GONE
            val ws = watches(a)
            alarmBtn.text = if (ws.any { it.hit }) "🔔 ${ws.size} · 자리 남!" else "🔔 ${ws.size}"
            alarmBtn.setTextColor(if (ws.any { it.hit }) Color.parseColor("#FF6B6B") else Ui.TEXT)
            list.removeAllViews()
            val fl = activeFleets(a)
            // v6.85 (393): 🔎 배 이름 찾기 — 날짜 · 지역 · 어종과 상관없이 이름으로
            qBtn.background = if (qRow.visibility == View.VISIBLE) Ui.bg(a, Ui.MINE, 99) else Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE)
            if (q.isBlank() && qRow.visibility == View.VISIBLE) {
                val r = (p.getString("seat_q", "") ?: "").split('\n').filter { it.isNotBlank() }
                if (r.isNotEmpty()) {
                    val w = FlowLayout(a)
                    r.forEach { t -> w.addView(Ui.pill(a, "🕘 $t", false, Ui.TEXT, small = true) { qEd.setText(t); qEd.setSelection(t.length) }) }
                    list.addView(Ui.text(a, 12f, Ui.SUB, true, "최근 찾은 배").apply { setPadding(dp(4), dp(6), 0, dp(4)) })
                    list.addView(w)
                }
            }
            if (q.isNotBlank()) {
                fun norm(x: String) = x.replace(" ", "").replace("호", "").lowercase()
                val nq = norm(q)
                // v6.90 (399): ⚓ 항구 이름으로도 (마검포항 · 마검포 · 드르니항) — '항' 빼고 2글자 이상
                val pq = normPort(q)
                val byPort = pq.length >= 2
                fun portHit(x: String) = byPort && x.isNotBlank() && normPort(x).contains(pq)
                val pool0 = all + (if (useS24) s24 else emptyList()) + (if (useTf) tfAll else emptyList())
                val nameC = pool0.filter { norm(it.ship).contains(nq) || norm(it.ship.substringBefore('[')).contains(nq) }
                val portC = if (byPort) pool0.filter { portHit(portOf(a, it)) || (it.host == S24 && portHit(it.place.substringAfterLast(" · "))) } else emptyList()
                val dayCards = ArrayList<Sch>()
                dayCards.addAll((nameC + portC).filter { it.date == day }.distinctBy { it.host + it.shipNo + it.schNo })
                val shown = dayCards.map { it.shipNo }.toMutableSet()
                val notes = ArrayList<View>()
                fun note(title: String, sub: String, subCol: Int, click: (() -> Unit)?) {
                    val row = LinearLayout(a).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = Ui.bg(a, Color.parseColor("#12283A"), 14, Color.parseColor("#2C4F66"))
                        if (click != null) { isClickable = true; setOnClickListener { click() } }
                    }
                    row.addView(Ui.text(a, 15f, Ui.TEXT, true, title))
                    row.addView(Ui.text(a, 12.5f, subCol, false, sub))
                    notes.add(row)
                }
                val have = activeFleets(a).map { it.host }.toSet()
                val ym = day.replace("-", "").take(6)
                /** v6.92 (408 · 410): 선단 홈페이지 한 곳 읽어 그 날 맞는 배 카드 · 없으면 '다음 출항' · 못 읽으면 '다시 ↻' */
                fun readHost(host: String, title: String, emoji: String, want: (Sch) -> Boolean, fleetNm: String) {
                    val k = "h:$host|$ym"
                    val f = found[k]
                    val hits = f?.filter { it.date == day && want(it) }.orEmpty()
                    if (hits.isNotEmpty()) { dayCards.addAll(hits); hits.forEach { shown.add(it.shipNo) }; return }
                    val err = k in foundErr
                    val next = f?.filter { it.date > day && want(it) && (it.status == "ING" || it.status == "END") }?.minByOrNull { it.date }
                    note("$emoji $title",
                        when { err -> "⚠ 못 읽었어요 · 눌러서 다시 ↻"; f == null -> "⏳ 선단 일정 읽는 중…"; next != null -> "이 날 일정 없음 · 다음 출항 ${dayLabel(next.date)} ›" + (if (next.open) " (남은 ${next.remain}자리)" else ""); else -> "이 달 · 이 날 일정이 없어요 · 꾹 = 내 선단에 넣기" },
                        when { err -> Color.parseColor("#F2C14E"); next != null -> Color.parseColor("#7FD6FF"); else -> Ui.SUB },
                        when { err -> { { foundErr.remove(k); render() } }; next != null -> { { setDay(next.date) } }; else -> null })
                    (notes.last()).setOnLongClickListener { addFleet(a, host, fleetNm) { load(true) }; true }
                    if (f == null && !err) qFetch(k) { month(host, ym) }
                }
                // v7.04 (429): 🔎 에 선단 주소(○○.sunsang24.com · ○○.thefishing.kr · https://…)를 붙여 넣으면 그 선단을 바로 읽어 카드로 (40곳 제한 · 지역 상관없이)
                val hostQ = Regex("([a-z0-9][a-z0-9-]{1,40}\\.(?:sunsang24\\.com|thefishing\\.kr))", RegexOption.IGNORE_CASE).find(q)?.groupValues?.get(1)?.lowercase()
                    ?.takeIf { it.substringBefore('.') !in setOf("www", "api", "m") } ?: TF_DOMAINS.firstOrNull { q.lowercase().contains(it) }
                if (hostQ != null) {
                    val nm = fleets(a).firstOrNull { it.host == hostQ }?.name?.ifBlank { null } ?: dirName(hostQ) ?: dirFleets(a).firstOrNull { it.host == hostQ }?.name ?: hostQ.substringBefore('.')
                    readHost(hostQ, "$nm  · $hostQ", if (isTf(hostQ)) "🎣" else "🚤", { true }, nm)
                    if (hostQ !in have) note("⭐ 이 선단을 내 선단에 넣기", "넣어 두면 지역과 상관없이 늘 보여요 · $hostQ", Color.parseColor("#F2C14E")) { addFleet(a, hostQ, nm) { load(true) } }
                }
                // 선상24 배 (talla.co.kr 배 목록에서 이름 · 항구) — 선상24 전체를 켠 사람은 선상24 일정 · 아니면 그 배 선단 홈페이지만
                val more = sites(a).filter { (no, v) -> no !in shown && ((v.name.isNotBlank() && norm(v.name).contains(nq)) || portHit(v.port)) }.entries.take(if (byPort) 40 else 16)
                var hidden = 0
                if (useS24) more.take(if (byPort) 30 else 12).forEach { (no, v) ->
                    val k = "s24:$no"
                    val f = found[k]
                    val hit = f?.firstOrNull { it.date == day }
                    if (hit != null) { dayCards.add(hit); return@forEach }
                    val err = k in foundErr
                    val next = f?.filter { it.date > day && (it.status == "ING" || it.status == "END") }?.minByOrNull { it.date }
                    note("🚤 " + v.name + if (v.port.isNotBlank()) "  · ${v.port}" else "",
                        when { err -> "⚠ 못 읽었어요 · 눌러서 다시 ↻"; f == null -> "⏳ 일정 읽는 중…"; next != null -> "이 날 일정 없음 · 다음 출항 ${dayLabel(next.date)} › (남은 ${next.remain}자리)"; else -> "이 날 · 앞으로 두 달 일정이 없어요" },
                        when { err -> Color.parseColor("#F2C14E"); next != null -> Color.parseColor("#7FD6FF"); else -> Ui.SUB },
                        when { err -> { { foundErr.remove(k); render() } }; next != null -> { { setDay(next.date) } }; else -> null })
                    if (f == null && !err) qFetch(k) {
                        val l = ArrayList<Sch>()
                        val cal = Calendar.getInstance().apply { time = ymd.parse(day)!! }
                        repeat(2) { try { l.addAll(s24Month(no, ymF.format(cal.time))) } catch (_: Exception) {}; cal.add(Calendar.MONTH, 1) }
                        l
                    }
                } else {
                    val byHost = LinkedHashMap<String, MutableList<Pair<String, ShipSite>>>()
                    more.forEach { (no, v) ->
                        val h = siteOf(a, no)?.host ?: v.host
                        if (h == null || !isS24Fleet(h)) { hidden++; return@forEach }
                        if (h !in have) byHost.getOrPut(h) { ArrayList() }.add(no to v)
                    }
                    byHost.entries.take(10).forEach { (h, l) ->
                        val nos = l.map { it.first }.toSet(); val nms = l.map { normShip(it.second.name) }.toSet()
                        readHost(h, l.joinToString(" · ") { it.second.name }.take(40) + "  · " + h.substringBefore('.'), "🚤",
                            { x -> x.shipNo in nos || normShip(x.ship) in nms || (!byPort && norm(x.ship).contains(nq)) }, "")
                    }
                }
                // 더피싱 (기본 목록 678척 + 선단 화면에서 본 배) — 그 선단 홈페이지를 바로 읽어 카드로
                val tf = tfMem
                if (tf == null && synchronized(foundBusy) { foundBusy.add("tf") }) tfShips(a) { a.runOnUiThread { if (dlg.isShowing) render() } }
                val tfPortHosts = if (byPort) (tf ?: emptyList()).filter { it.host.isNotBlank() && it.host !in have && portHit(it.area.substringAfterLast(" > ")) }
                    .groupBy { it.host }.entries.take(10).map { it.key to it.value } else emptyList()
                val tfPortSet = tfPortHosts.map { it.first }.toSet()
                val tfNameHosts = (tf ?: emptyList()).filter { it.host.isNotBlank() && it.host !in have && it.host !in tfPortSet && norm(it.ship).contains(nq) }
                    .groupBy { it.host }.entries.take(8).map { it.key to it.value }
                tfPortHosts.forEach { (h, l) ->
                    val fleetNm = l.first().fleet.substringBefore(" (").ifBlank { h.substringBefore('.') }
                    readHost(h, "$fleetNm  · " + l.joinToString(" · ") { it.ship }.take(40), "🎣", { true }, fleetNm)
                }
                tfNameHosts.forEach { (h, l) ->
                    val fleetNm = l.first().fleet.substringBefore(" (").ifBlank { h.substringBefore('.') }
                    readHost(h, l.joinToString(" · ") { it.ship }.take(40) + "  · $fleetNm", "🎣",
                        { x -> norm(x.ship).contains(nq) || norm(x.ship.substringBefore('[')).contains(nq) }, fleetNm)
                }
                if (hidden > 0 && canS24(a)) note("🚤 선단 주소를 모르는 선상24 배 ${hidden}척", "위 [🚤 선상24]를 켜면 같이 찾아요", Ui.SUB) { useS24 = true; p.edit().putBoolean("seat_s24on", true).apply(); render(); loadS24(true) }
                // 항구 이름 자동 완성 (지도 항구 2,267곳)
                if (byPort) {
                    val pn = Ports.list(a).map { it.name }.filter { normPort(it).contains(pq) && normPort(it) != pq }.distinct().take(6)
                    if (pn.isNotEmpty()) {
                        val w = FlowLayout(a)
                        pn.forEach { t -> w.addView(Ui.pill(a, "⚓ $t", false, Ui.TEXT, small = true) { qEd.setText(t); qEd.setSelection(t.length) }) }
                        list.addView(w)
                    }
                }
                val cards = sortList(dedupe(a, dayCards.distinctBy { it.host + it.shipNo + it.schNo }))
                val portN = if (byPort) cards.count { c0 -> (listOf(c0) + c0.also).any { x -> portHit(portOf(a, x)) || (x.host == S24 && portHit(x.place.substringAfterLast(" · "))) || x.host in tfPortSet } } else 0
                sum.text = "🔎 '$q' · ${dayLabel(day)}" + (if (portN > 0) " · ⚓ 항구 배 ${portN}척" else "") + " · 빈 배 ${cards.count { it.open && it.remain >= need }}척"
                pageReset("q|$q|$day|$sort")
                cards.take(shownN).forEach { list.addView(card(it, it.key in favs(a)), Ui.lp(a, MATCH, WRAP, top = 6)) }
                if (cards.size > shownN) list.addView(moreBtn(shownN, cards.size), Ui.lp(a, MATCH, WRAP, top = 10))
                notes.forEach { list.addView(it, Ui.lp(a, MATCH, WRAP, top = 6)) }
                if (cards.isEmpty() && notes.isEmpty()) list.addView(Ui.text(a, 13.5f, Ui.SUB, false, "'$q' 배 · 항구를 못 찾았어요 · 띄어쓰기 없이 몇 글자만 쳐 보세요").apply { setPadding(dp(6), dp(20), dp(6), 0) })
                return@render
            }
            if (activeFleets(a).isEmpty() && !useS24 && !useTf && areaSel.isEmpty()) {
                list.addView(Ui.text(a, 14f, Ui.TEXT, false, "📍 지역을 골라 주세요 — 그 지역 선단(선상24 · 더피싱 선단 홈페이지)의 배를 모두 보여 줘요.").apply { setPadding(dp(6), dp(16), dp(6), dp(8)) })
                AREAS.groupBy { it.group }.forEach { (g, l) ->
                    list.addView(Ui.text(a, 12.5f, Ui.SUB, true, g).apply { setPadding(dp(4), dp(8), 0, dp(4)) })
                    val w = FlowLayout(a)
                    l.forEach { ar -> w.addView(Ui.pill(a, ar.name.removeSuffix("인근"), false, Ui.MINE, small = true) { areaSel.add(ar.name); savePrefs(); render(); load(false) }) }
                    list.addView(w)
                }
                list.addView(Ui.text(a, 12.5f, Ui.SUB, false, "또는 🔎 배 이름 · 항구로 찾거나 [⚙ 선단]에 자주 타는 선단을 넣어 두세요").apply { setPadding(dp(6), dp(14), dp(6), 0) })
                list.addView(Ui.button(a, "🔎 배 · 항구로 찾기", true) { qBtn.performClick() }, Ui.lp(a, MATCH, WRAP, top = 8))
                list.addView(Ui.button(a, "＋ 선단 추가") { openFleets(a) { load(true) } }, Ui.lp(a, MATCH, WRAP, top = 8))
                sum.text = ""; return@render
            }
            val mineDay = all.filter { it.date == day && it.host in fl.map { f -> f.host } }
            val mineNos = mineDay.map { it.shipNo }.toSet()
            val flHosts = fl.map { it.host }.toSet()
            var day0 = dedupe(a, mineDay + (if (useS24) s24.filter { it.date == day && it.shipNo !in mineNos } else emptyList()) +
                (if (useTf) tfAll.filter { it.date == day && it.host !in flHosts } else emptyList()) + areaAll.filter { it.date == day && it.host !in flHosts })
            val total = day0.size
            day0 = day0.filter { fishMatch(it) }
            val openN = day0.count { it.open && it.remain >= need }
            if (openOnly) day0 = day0.filter { it.open && it.remain >= need }
            day0 = sortList(day0)
            val fv = favs(a)
            sum.text = (if (loading) "⏳ 선단 ${loadTotal}곳 중 ${loadDone}곳 읽음 · " else if (areaLeft > 0) "⏳ 📍 선단 ${areaTotal}곳 중 ${areaTotal - areaLeft}곳 읽음 · " else if (s24Busy || tfBusy) "⏳ 읽는 중 · " else "") +
                (if (areaSel.isNotEmpty() && areaLeft == 0 && areaTotal > 0) "📍 선단 ${areaTotal}곳" + (if (areaRest.isNotEmpty()) " (남은 ${areaRest.size}곳)" else "") + (if (areaSkip > 0) " · 쉬는 ${areaSkip}곳 뺌" else "") + " · " else "") + "${dayLabel(day)} · 배 ${total}척" +
                (if (useS24 && s24Total > s24.size) " (선상24 ${s24Total}척 중 ${s24.size}척 읽음)" else "") + " · ${need}자리 이상 빈 배 ${openN}척" +
                (if (fails.isNotEmpty()) " · ⚠ 못 읽은 선단 ${fails.size}곳" else "") + (if (s24Err) " · ⚠ 선상24 목록을 못 읽었어요" else "") + (if (useTf && tfHosts > 0) " · 🎣 더피싱 선단 ${tfHosts}곳" else "")
            if (useTf && areaSel.isEmpty()) list.addView(Ui.text(a, 12f, Color.parseColor("#2FBF8F"), false, "🎣 더피싱 전체: 📍 지역을 고르면 그 지역 더피싱 선단 배를 같이 보여 줘요 (선단 10곳까지)").apply { setPadding(dp(4), dp(4), dp(4), dp(4)) })
            if (day0.isEmpty()) {
                list.addView(Ui.text(a, 13.5f, Ui.SUB, false, if (loading || s24Busy || tfBusy || areaLeft > 0) "읽는 중… (읽히는 선단부터 바로 나와요)" else if (total == 0) "이 날 나가는 배가 없어요 (선단이 일정을 아직 안 올렸을 수 있어요)" else "고른 조건에 맞는 배가 없어요").apply { setPadding(dp(6), dp(20), dp(6), 0) })
                if (areaRest.isNotEmpty() && areaLeft <= 0) list.addView(Ui.button(a, "⬇ 선단 ${minOf(40, areaRest.size)}곳 더 읽기 (남은 ${areaRest.size}곳)") {
                    val next = areaRest.take(40); areaRest.removeAll(next.toSet()); areaReadFn(next)
                }.apply { minHeight = dp(46); textSize = 14f }, Ui.lp(a, MATCH, WRAP, top = 10))
                return@render
            }
            fun section(t: String, c: Int) = list.addView(Ui.text(a, 12f, c, true, t).apply { setPadding(dp(4), dp(8), 0, dp(2)) })
            val favL = if (favTop) day0.filter { it.key in fv } else emptyList()
            val rest = day0.filter { it !in favL }
            pageReset("m|$day|${areaSel.sorted()}|${fishSel.sorted()}|$sort|$openOnly|$favTop|${todSel.sorted()}|$durSel|$need|$useS24|$postDays")
            val restShown = rest.take(maxOf(0, shownN - favL.size))
            if (favL.isNotEmpty()) { section("⭐ 즐겨찾기", Color.parseColor("#F2C14E")); favL.forEach { list.addView(card(it, true), Ui.lp(a, MATCH, WRAP, top = 6)) } }
            if (restShown.isNotEmpty()) {
                if (favL.isNotEmpty()) section("그 밖의 배", Ui.SUB)
                restShown.forEach { list.addView(card(it, it.key in fv), Ui.lp(a, MATCH, WRAP, top = 6)) }
            }
            if (restShown.size < rest.size) list.addView(moreBtn(favL.size + restShown.size, favL.size + rest.size), Ui.lp(a, MATCH, WRAP, top = 10))
            if (areaRest.isNotEmpty() && restShown.size >= rest.size) list.addView(Ui.button(a, if (areaLeft > 0) "⏳ 선단 읽는 중…" else "⬇ 선단 ${minOf(40, areaRest.size)}곳 더 읽기 (남은 ${areaRest.size}곳)") {
                if (areaLeft > 0) return@button
                val next = areaRest.take(40); areaRest.removeAll(next.toSet()); areaReadFn(next)
            }.apply { minHeight = dp(46); textSize = 14f }, Ui.lp(a, MATCH, WRAP, top = 10))
            // v6.82 (390): 배 사이트 목록에 없는 배만 폰이 조황 글에서 찾기 (한 번에 6척까지)
            val miss = (favL + restShown).filter { it.host == S24 && needsLookup(a, it.shipNo) }.map { it.shipNo }.distinct().take(6)
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
            list.addView(Ui.text(a, 11f, Ui.SUB, false, (if (useS24) "선상24 목록 · " else "") + "선단 홈페이지를 내 폰이 읽어 와요 · 정렬은 읽어 온 배 안에서 · 예약 › = 그 배 선단 홈페이지 · 🔎 = 배 이름 · 항구로 찾기 · 카드 꾹 = 메뉴(내 선단에 넣기 · 검색)").apply { setPadding(dp(4), dp(14), dp(4), 0) })
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

        // v6.92 (410): 🎣 더피싱 전체 = 고른 지역의 더피싱 선단(10곳까지) 그 날 화면 한 번씩
        fun loadTf() {
            if (!useTf) { tfAll.clear(); tfKey = ""; tfBusy = false; tfHosts = 0; return }
            val kws = areaKw(areaSel)
            val key = "$day|${kws.sorted()}"
            if (key == tfKey) return
            tfKey = key; tfAll.clear(); tfHosts = 0; tfBusy = false
            if (kws.isEmpty()) { render(); return }
            tfShips(a) { l ->
                val have = activeFleets(a).map { it.host }.toSet()
                val hosts = l.filter { it.host.isNotBlank() && it.host !in have && kws.any { k -> it.area.contains(k) } }.map { it.host }.distinct().take(10)
                val d0 = day
                a.runOnUiThread { if (key != tfKey) return@runOnUiThread; tfBusy = true; tfHosts = hosts.size; render() }
                Thread {
                    val got = ArrayList<Sch>()
                    val ths = hosts.map { h -> Thread { try { val r = tfDay(h, d0); synchronized(got) { got.addAll(r) } } catch (_: Exception) {} } }
                    ths.forEach { it.start() }; ths.forEach { try { it.join(25000) } catch (_: Exception) {} }
                    a.runOnUiThread { if (!dlg.isShowing || key != tfKey) return@runOnUiThread; tfAll.clear(); synchronized(got) { tfAll.addAll(got) }; tfBusy = false; render() }
                }.start()
            }
        }
        // v6.94: 선단마다 읽히는 대로 바로 보여 주기 (느린 선단 하나 때문에 전체가 '읽는 중'에 묶이지 않게)
        //        더피싱 선단 = 고른 날 8일 화면 한 번만 (한 달 치는 🔔 알림 · 달력에서)
        // v7.02 (메모 426 · 5번): 즐겨찾기 선단 · 가까운 선단 40곳 먼저 → 나머지는 [⬇ 선단 더 읽기] · 한 달 일정 없는 선단은 뺌
        fun areaRead(part: List<DirF>) {
            if (part.isEmpty()) return
            val gen = areaGen; val d0 = day
            areaTotal += part.size; areaLeft += part.size; render()
            val ex = areaEx ?: java.util.concurrent.Executors.newFixedThreadPool(6).also { areaEx = it }
            fun one(f: DirF) = ex.execute {
                val r = try { if (isTf(f.host)) tfDay(f.host, d0) else s24Day(f.host, d0) } catch (_: Exception) { emptyList() }
                a.runOnUiThread { if (gen != areaGen || !dlg.isShowing) return@runOnUiThread; areaAll.addAll(r); areaLeft--; if (areaLeft <= 0) render() else later() }
            }
            part.chunked(40).forEach { ch -> ex.execute {
                val got = dayBatch(ch.map { it.host }, d0)
                if (got == null) { ch.forEach { one(it) }; return@execute }
                val miss = ch.filter { it.host !in got }
                a.runOnUiThread {
                    if (gen != areaGen || !dlg.isShowing) return@runOnUiThread
                    got.values.forEach { areaAll.addAll(it) }; areaLeft -= got.size
                    if (areaLeft <= 0) render() else later()
                }
                miss.forEach { one(it) }
            } }
        }
        areaReadFn = { areaRead(it) }
        fun loadArea() {
            val key = "$day|${areaSel.sorted()}"
            if (key == areaKey) return
            areaKey = key; areaAll.clear(); ++areaGen
            areaEx?.shutdownNow(); areaEx = null; areaLeft = 0; areaTotal = 0; areaRest.clear()
            if (areaSel.isEmpty()) { render(); return }
            val gen = areaGen
            tfShips(a) { _ -> a.runOnUiThread {
                if (gen != areaGen || !dlg.isShowing) return@runOnUiThread
                val have = activeFleets(a).map { it.host }.toSet()
                val off = offFleets(a)
                val favH = favs(a).map { it.substringBefore('|') }.toSet()
                val me = Spot.base(a, null, null)
                val fl = dirFleets(a).filter { it.area in areaSel && it.host !in have && (it.host !in off || it.host in favH) }
                    .sortedWith(compareBy<DirF>({ if (it.host in favH) 0 else 1 }, { f ->
                        if (me == null || f.lat.isNaN()) AREAS.firstOrNull { it.name == f.area }?.let { ar -> if (me == null) 0.0 else km(me.first, me.second, ar.lat, ar.lon) + 5 } ?: 999.0
                        else km(me.first, me.second, f.lat, f.lon) }))
                areaSkip = dirFleets(a).count { it.area in areaSel && it.host in off && it.host !in favH }
                val first = fl.take(40); areaRest.addAll(fl.drop(40))
                areaRead(first)
            } }
        }
        load = { fresh ->
            loadS24(fresh)
            loadTf()
            loadArea()
            val fl = activeFleets(a)
            val months = linkedSetOf(day.replace("-", "").take(6), ymF.format(Date()))
            if (fl.isNotEmpty()) {
                val gen = ++loadGen
                loading = true; loadDone = 0; loadTotal = fl.size; fails.clear(); render()
                val d0 = day
                fl.forEach { f ->
                    Thread {
                        val got = ArrayList<Sch>(); var bad = false
                        try {
                            if (isTf(f.host)) got.addAll(tfDay(f.host, d0))
                            else months.forEach { m -> got.addAll(month(f.host, m, fresh)) }
                        } catch (_: Exception) { bad = got.isEmpty() }
                        a.runOnUiThread {
                            if (!dlg.isShowing || gen != loadGen) return@runOnUiThread
                            val tf0 = isTf(f.host); val ms = got.map { it.date.take(7) }.toSet()
                            if (got.isNotEmpty() || tf0) all.removeAll { s -> s.host == f.host && (if (tf0) s.date == d0 else s.date.take(7) in ms) }
                            all.addAll(got)
                            if (bad && f.name !in fails) fails.add(f.name)
                            if (++loadDone >= loadTotal) loading = false
                            render()
                        }
                    }.start()
                }
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
            val names = (all + tfAll + areaAll).filter { it.date >= ymd.format(Date()) }.flatMap { fishList(it) }.groupingBy { it }.eachCount()
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
            val (sh, chips, paint) = multiSheet("📍 지역", areaSel) { v -> areaSel.clear(); areaSel.addAll(v); savePrefs(); render(); loadS24(true); loadTf(); loadArea() }
            val sf = filterMem?.second
            // 선상24 지역 목록이 없으면(권한 · 못 받음) 기본 이름으로
            val areas = AREAS.groupBy { it.group }.map { (g, l) -> g to l.map { it.name } }
            areas.forEach { (t, l) ->
                if (l.isEmpty()) return@forEach
                chips(sh.body, t, l, emptyMap())
            }
            sh.body.addView(Ui.text(a, 11.5f, Ui.SUB, false, "여러 개 눌러 고르고 [✓ 보기] · 그 지역 선단(선상24 · 더피싱 선단 홈페이지) 배를 모두 읽어요 (지역이 많으면 오래 걸려요) · 내가 넣은 선단(⚙)은 늘 보여요").apply { setPadding(0, dp(12), 0, 0) })
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
            // v7.03 (427): 📝 최근 조과 순 · 최근 N일 안 조과 있는 배만
            sw("📝 최근 조과 순으로 보기", "선단 홈페이지에 조과 글이 최근에 올라온 배부터 (받아 오며 순서가 바뀌어요)", sort == 5) { sort = if (it) 5 else 0; savePrefs() }
            sh.body.addView(Ui.text(a, 13.5f, Ui.TEXT, true, "📝 최근 조과 있는 배만").apply { setPadding(0, dp(12), 0, dp(6)) })
            val pw = FlowLayout(a)
            fun paintPd() {
                pw.removeAllViews()
                listOf(0 to "전체", 3 to "3일 안", 7 to "7일 안", 14 to "14일 안", 30 to "한 달 안").forEach { (n, t) ->
                    pw.addView(Ui.pill(a, t, n == postDays, Ui.MINE, small = true) { postDays = n; p.edit().putInt("seat_pdays", n).apply(); paintPd(); render() })
                }
            }
            paintPd(); sh.body.addView(pw)
            // v6.86 (395): 출항 시간대(여러 개) · 운항 시간
            sh.body.addView(Ui.text(a, 13.5f, Ui.TEXT, true, "🕐 출항 시간대 (여러 개)").apply { setPadding(0, dp(12), 0, dp(6)) })
            val tw = FlowLayout(a)
            fun paintTod() {
                tw.removeAllViews()
                tw.addView(Ui.pill(a, "전체", todSel.isEmpty(), Ui.MINE, small = true) { todSel.clear(); p.edit().putStringSet("seat_tod", HashSet(todSel)).apply(); paintTod(); render() })
                listOf("dawn" to "🌅 새벽 (~6시)", "am" to "☀ 오전 (6~12시)", "pm" to "🌇 오후 (12~18시)", "night" to "🌙 야간 (18시~)").forEach { (k, t) ->
                    tw.addView(Ui.pill(a, t, k in todSel, Ui.MINE, small = true) {
                        if (k in todSel) todSel.remove(k) else todSel.add(k); p.edit().putStringSet("seat_tod", HashSet(todSel)).apply(); paintTod(); render()
                    })
                }
            }
            paintTod(); sh.body.addView(tw)
            sh.body.addView(Ui.text(a, 13.5f, Ui.TEXT, true, "⏱ 운항 시간").apply { setPadding(0, dp(12), 0, dp(6)) })
            val dw = FlowLayout(a)
            fun paintDur() {
                dw.removeAllViews()
                arrayOf("전체", "짧게 (~5시간)", "보통 (5~9시간)", "종일 (9시간~)").forEachIndexed { i, t ->
                    dw.addView(Ui.pill(a, t, i == durSel, Ui.MINE, small = true) { durSel = i; p.edit().putInt("seat_dur", i).apply(); paintDur(); render() })
                }
            }
            paintDur(); sh.body.addView(dw)
            sh.body.addView(Ui.text(a, 11.5f, Ui.SUB, false, "출항 시각을 안 적은 배(시간 모름)는 거르지 않고 보여 줘요").apply { setPadding(0, dp(6), 0, 0) })
            sh.show()
        }
        sortBtn.setOnClickListener {
            val sh = Sheet(a, 0.5f)
            sh.head.addView(Ui.text(a, 17f, Ui.TEXT, true, "↕ 정렬"), LinearLayout.LayoutParams(0, WRAP, 1f))
            SORTS.forEachIndexed { i, t ->
                sh.body.addView(Ui.pill(a, (if (i == sort) "✓ " else "") + t, i == sort, Ui.MINE) { sort = i; savePrefs(); sh.close(); render() }, Ui.lp(a, MATCH, WRAP, top = 6))
            }
            sh.body.addView(Ui.text(a, 11.5f, Ui.SUB, false, "조황 순은 선상24 배만 알아요 · 📝 최근 조과 순 = 선단 홈페이지 조과 글 날짜 (선상24 선단 · 더피싱 선단 · 받아 오며 순서가 바뀌어요)").apply { setPadding(0, dp(10), 0, 0) })
            sh.show()
        }
        fleetBtn.setOnClickListener { openFleets(a) { load(true) } }
        s24Chip.setOnClickListener {
            useS24 = !useS24; p.edit().putBoolean("seat_s24on", useS24).apply()
            Ui.toast(a, if (useS24) "🚤 선상24 전체 배를 같이 보여 줘요" else "🚤 선상24 전체 끔 · 선단 홈페이지만")
            render(); if (useS24) loadS24(true)
        }
        tfChip.setOnClickListener {
            useTf = !useTf; p.edit().putBoolean("seat_tfon", useTf).apply()
            Ui.toast(a, if (useTf) "🎣 더피싱 전체 · 📍 지역을 고르면 그 지역 선단을 같이 읽어요" else "🎣 더피싱 전체 끔")
            tfKey = ""; render(); loadTf()
        }
        dayBtn.setOnClickListener { openCalendar() }
        fun recentQ() = (p.getString("seat_q", "") ?: "").split('\n').filter { it.isNotBlank() }
        qBtn.setOnClickListener {
            if (qRow.visibility == View.VISIBLE) { qRow.visibility = View.GONE; q = ""; qEd.setText(""); render(); return@setOnClickListener }
            qRow.visibility = View.VISIBLE; qEd.requestFocus()
            (a.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(qEd, 0)
            render()
        }
        qX.setOnClickListener { qBtn.performClick() }
        qEd.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { q = e?.toString()?.trim() ?: ""; render() }
            override fun beforeTextChanged(x: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(x: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        qEd.setOnEditorActionListener { _, _, _ ->
            if (q.isNotBlank()) p.edit().putString("seat_q", (listOf(q) + recentQ().filter { it != q }).take(5).joinToString("\n")).apply()
            (a.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(qEd.windowToken, 0); true
        }
        prevBtn.setOnClickListener { shift(-1) }
        nextBtn.setOnClickListener { shift(1) }
        alarmBtn.setOnClickListener { openWatches(a) { render() } }

        dlg.setContentView(root)
        Ui.edge(dlg.window)
        dlg.setOnDismissListener { try { qPool.shutdownNow(); areaGen++; areaEx?.shutdownNow() } catch (_: Exception) {} }
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
            sh.body.addView(Ui.text(a, 12.5f, Ui.SUB, true, "⭐ 내 선단 (선단 홈페이지 · 한 달 일정 · 물때까지)").apply { setPadding(0, dp(4), 0, dp(4)) })
            val l = fleets(a)
            if (l.isEmpty()) sh.body.addView(Ui.text(a, 13f, Ui.SUB, false, "자주 타는 선단 홈페이지 주소를 넣어 주세요 (예: sungbin.sunsang24.com · ssfish.thefishing.kr) · 그 선단의 배 일정을 다 읽어 와요"))
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
            // v6.91 (402): 관리자가 홈피에서 넣은 배 (모든 사람에게 기본으로 보임 · 끄기 가능)
            val ex = extras(a).filter { e -> have.none { it == e.host } }.distinctBy { it.host }
            if (ex.isNotEmpty()) {
                val pp = prefs(a)
                sh.body.addView(Ui.text(a, 12.5f, Ui.SUB, true, "📌 관리자가 넣은 배 (기본으로 같이 보여요)").apply { setPadding(0, dp(14), 0, dp(6)) })
                ex.forEach { e ->
                    val r = Ui.row(a).apply { setPadding(dp(10), dp(6), dp(6), dp(6)); background = Ui.bg(a, Color.parseColor("#12283A"), 12, Color.parseColor("#2C4F66")) }
                    val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                    col.addView(Ui.text(a, 14f, Ui.TEXT, true, e.name.ifBlank { e.host }))
                    col.addView(Ui.text(a, 11.5f, Ui.SUB, false, listOf(e.port, e.host, e.memo).filter { it.isNotBlank() }.joinToString(" · ")))
                    r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
                    val off = pp.getStringSet("seat_extra_off", emptySet()) ?: emptySet()
                    r.addView(android.widget.Switch(a).apply { isChecked = e.host !in off
                        setOnCheckedChangeListener { _, v -> val m = HashSet(pp.getStringSet("seat_extra_off", emptySet()) ?: emptySet()); if (v) m.remove(e.host) else m.add(e.host); pp.edit().putStringSet("seat_extra_off", m).apply(); dirty = true } })
                    sh.body.addView(r, Ui.lp(a, MATCH, WRAP, top = 6))
                }
            }
            // v6.85 (394): 🎣 더피싱 선단 고르기 (관리자가 모아 둔 목록)
            sh.body.addView(Ui.button(a, "🎣 더피싱 선단 고르기") { openTfPicker(a) { dirty = true; draw() } }.apply { minHeight = dp(44); textSize = 13.5f }, Ui.lp(a, MATCH, WRAP, top = 14))
            sh.body.addView(Ui.button(a, "＋ 선단 추가 (주소 붙여 넣기)", true) {
                val et = EditText(a).apply { hint = "sungbin.sunsang24.com"; inputType = InputType.TYPE_TEXT_VARIATION_URI; setSingleLine() }
                android.app.AlertDialog.Builder(a, R.style.FlAlert).setTitle("🚤 선단 추가")
                    .setMessage("선상24 선단 (○○.sunsang24.com) · 더피싱 선단 (○○.thefishing.kr) · 개인 배 사이트 주소")
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

    // ─────────────── v6.86 (395 · 396): 사진 크게 · 조황 글 · 예약 오픈 알림 ───────────────
    private fun showPhoto(a: Activity, url: String, title: String) {
        val iv = android.widget.ImageView(a).apply { adjustViewBounds = true; scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; minimumHeight = Ui.dp(a, 200) }
        shipImg(a, url, iv)
        android.app.AlertDialog.Builder(a, R.style.FlAlert).setTitle("🚤 $title").setView(iv).setPositiveButton("닫기", null).show()
    }
    // ─────────────── v6.92 (411): 선단 홈페이지 조과 글 (○○.sunsang24.com/ship/board) ───────────────
    class Post(val id: String, val title: String, val text: String, val ship: String, val fish: String, val date: String, val img: String)
    private val postMem = HashMap<String, Pair<Long, List<Post>>>()
    fun postsCached(host: String) = synchronized(postMem) { postMem[host]?.takeIf { System.currentTimeMillis() - it.first < 60 * 60_000L }?.second }
    private val POST_LI = Regex("<li>\\s*<a href=\"/ship/board_detail/(\\d+)\"(.*?)</li>", RegexOption.DOT_MATCHES_ALL)
    fun parsePosts(html: String): List<Post> = POST_LI.findAll(html).map { m ->
        val seg = m.groupValues[2]
        fun g(rx: String) = Regex(rx, RegexOption.DOT_MATCHES_ALL).find(seg)?.let { tfText(it.groupValues[1]) } ?: ""
        val dm = Regex("class=\"date[^\"]*\">\\s*(\\d{4})\\.\\s*(\\d{1,2})\\.\\s*(\\d{1,2})").find(seg)
        Post(m.groupValues[1], g("class=\"data-title\">(.*?)</h5>"), g("class=\"data-text\">(.*?)</p>"), g("class=\"shipname\">(.*?)</span>"),
            g("class=\"fish\">(.*?)</span>"), dm?.let { "%s-%02d-%02d".format(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3].toInt()) } ?: "",
            Regex("data-src=\"([^\"]+)\"").find(seg)?.groupValues?.get(1) ?: "")
    }.filter { it.title.isNotBlank() }.distinctBy { it.id }.toList()
    /** 선단 조과 글 첫 쪽 (한 시간 기억 · 못 읽으면 null) */
    fun fleetPosts(host: String): List<Post>? {
        postsCached(host)?.let { return it }
        if (!isS24Fleet(host) && !isTf(host)) return null
        val l = try { if (isTf(host)) parseTfPosts(host, get("https://$host/index.php?mid=gall")) else parsePosts(get("https://$host/ship/board")) } catch (_: Exception) { return null }
        synchronized(postMem) { postMem[host] = System.currentTimeMillis() to l }
        return l
    }
    // v7.01 (422): 더피싱 선단 조황 게시판 (index.php?mid=gall · 줄 = <tr class="bg…"> · 배명 [배] · 분류 · 제목 · 앞부분 · 날짜 td.datetime · 맨 위 번호 = 글 수 대략)
    private val tfPostN = HashMap<String, Int>()
    fun tfPostCount(host: String) = synchronized(tfPostN) { tfPostN[host] ?: -1 }
    fun parseTfPosts(host: String, html: String): List<Post> {
        val rows = html.split("<tr class=\"bg").drop(1)
        fun g(seg: String, rx: String) = Regex(rx, RegexOption.DOT_MATCHES_ALL).find(seg)?.groupValues?.get(1)?.let { tfText(it) } ?: ""
        rows.firstOrNull()?.let { r -> Regex("class=\"num\"[^>]*>\\s*(\\d+)").find(r)?.groupValues?.get(1)?.toIntOrNull()?.let { n -> synchronized(tfPostN) { tfPostN[host] = n } } }
        return rows.mapNotNull { r ->
            val id = Regex("wr_uid=(\\d+)").find(r)?.groupValues?.get(1) ?: return@mapNotNull null
            val ship = g(r, "<!--배명-->\\s*<span>\\[(.*?)\\]</span>")
            val date = Regex("class=\"datetime\">\\s*(\\d{4}-\\d{2}-\\d{2})").find(r)?.groupValues?.get(1) ?: ""
            Post(id, g(r, "class=\"list_title[^\"]*\"[^>]*>(.*?)</a>"), g(r, "class=\"list_content[^\"]*\">(.*?)</p>"), ship,
                g(r, "ca_name=[^\"]*\"[^>]*>\\s*\\[(.*?)\\]"), date, Regex("<img src=\"(https?://[^\"]+/thumb/[^\"]+)\"").find(r)?.groupValues?.get(1) ?: "")
        }.filter { it.title.isNotBlank() }.distinctBy { it.id }
    }
    fun postUrl(host: String, id: String) = if (isTf(host)) "https://$host/index.php?mid=gall&mode=view&wr_uid=$id" else "https://$host/ship/board_detail/$id"
    fun postsOf(l: List<Post>, ship: String): List<Post> {
        val n = normShip(ship); if (n.isBlank()) return emptyList()
        return l.filter { p -> val pn = normShip(p.ship); pn.isNotBlank() && (pn.contains(n) || n.contains(pn)) }
    }
    fun agoLabel(d: String): String {
        val n = try { ((ymd.parse(ymd.format(Date()))!!.time - ymd.parse(d)!!.time) / 86400000L).toInt() } catch (_: Exception) { return "" }
        return when { n < 0 -> ""; n == 0 -> "오늘"; n == 1 -> "어제"; n < 30 -> "${n}일 전"; else -> "${n / 30}달 전" }
    }
    fun daysAgo(d: String): Int = try { ((ymd.parse(ymd.format(Date()))!!.time - ymd.parse(d)!!.time) / 86400000L).toInt() } catch (_: Exception) { -1 }
    /** '오늘' · '+3일' */
    fun plusDays(d: String): String { val n = daysAgo(d); return when { n < 0 -> ""; n == 0 -> "오늘"; else -> "+${n}일" } }
    /** 416: 탐사 글 (제목 또는 앞부분에 '탐사') */
    fun isScout(p: Post) = p.title.contains("탐사") || p.text.take(80).contains("탐사")
    /** 📝 선단 조과 글 목록 — 이 배 글 먼저 · 누르면 선단 홈페이지 원래 글 */
    private fun openFleetBoards(a: Activity, host: String, ship: String) {
        fun dp(v: Int) = Ui.dp(a, v)
        val sh = Sheet(a, 0.75f)
        sh.head.addView(Ui.text(a, 17f, Ui.TEXT, true, "📝 $ship 조과"), LinearLayout.LayoutParams(0, WRAP, 1f))
        sh.head.addView(Ui.pill(a, "닫기", false, Ui.TEXT, small = true) { sh.close() })
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        sh.body.addView(box)
        box.addView(Ui.text(a, 13f, Ui.SUB, false, "불러오는 중…"))
        Thread {
            val l = fleetPosts(host)
            a.runOnUiThread {
                box.removeAllViews()
                if (l == null || l.isEmpty()) { box.addView(Ui.text(a, 13f, Ui.SUB, false, if (l == null) "못 불러왔어요 · 인터넷을 확인해 주세요" else "아직 조과 글이 없어요")); return@runOnUiThread }
                val mine0 = postsOf(l, ship); val rest0 = l.filter { it !in mine0 }
                // 416: 최근 2주 탐사 글은 맨 위
                fun top(x: List<Post>) = x.filter { isScout(it) && daysAgo(it.date) in 0..14 } + x.filterNot { isScout(it) && daysAgo(it.date) in 0..14 }
                val mine = top(mine0); val rest = top(rest0)
                box.addView(Ui.text(a, 11.5f, Ui.SUB, false, "선단 홈페이지(${host}) 조과 글 최근 ${l.size}개 · 누르면 원래 글"))
                fun row(o: Post) {
                    val r = LinearLayout(a).apply {
                        orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(10), dp(8), dp(10), dp(8)); background = Ui.bg(a, Color.parseColor("#12283A"), 12, Color.parseColor("#2C4F66"))
                        isClickable = true; setOnClickListener { Boats.openUrl(a, postUrl(host, o.id)) }
                    }
                    if (o.img.isNotBlank()) {
                        val iv = android.widget.ImageView(a).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; background = Ui.bg(a, Color.parseColor("#1B3A52"), 10); clipToOutline = true }
                        shipImg(a, o.img, iv)
                        r.addView(iv, LinearLayout.LayoutParams(dp(54), dp(54)).apply { rightMargin = dp(10) })
                    }
                    val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                    if (isScout(o)) col.addView(Ui.text(a, 11f, Color.parseColor("#1B1206"), true, "🧭 탐사").apply { setPadding(dp(6), dp(1), dp(6), dp(1)); background = Ui.bg(a, Color.parseColor("#FFD36E"), 6) },
                        LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(3) })
                    col.addView(Ui.text(a, 14f, Ui.TEXT, true, o.title).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                    col.addView(Ui.text(a, 11.5f, Ui.SUB, false, listOf(o.date.takeIf { it.isNotBlank() }?.let { "$it (${agoLabel(it)})" } ?: "", o.ship, o.fish).filter { it.isNotBlank() }.joinToString(" · ")))
                    if (o.text.isNotBlank()) col.addView(Ui.text(a, 12f, Color.parseColor("#B8CAD6"), false, o.text).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                    r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
                    box.addView(r, Ui.lp(a, MATCH, WRAP, top = 6))
                }
                if (mine.isNotEmpty()) { box.addView(Ui.text(a, 12.5f, Color.parseColor("#7FD6FF"), true, "🚤 $ship").apply { setPadding(0, dp(10), 0, 0) }); mine.forEach { row(it) } }
                if (rest.isNotEmpty()) { box.addView(Ui.text(a, 12.5f, Ui.SUB, true, if (mine.isEmpty()) "선단 조과 글" else "같은 선단 다른 배").apply { setPadding(0, dp(12), 0, 0) }); rest.forEach { row(it) } }
                box.addView(Ui.button(a, "🌐 선단 조과 게시판 열기") { Boats.openUrl(a, if (isTf(host)) "https://$host/index.php?mid=gall" else "https://$host/ship/board") }.apply { minHeight = dp(44); textSize = 13.5f }, Ui.lp(a, MATCH, WRAP, top = 12))
            }
        }.start()
        sh.show()
    }
    /** 📝 그 배 조황 글 목록 (선상24) — 누르면 원래 글 */
    private fun openBoards(a: Activity, s: Sch) {
        fun dp(v: Int) = Ui.dp(a, v)
        val sh = Sheet(a, 0.75f)
        sh.head.addView(Ui.text(a, 17f, Ui.TEXT, true, "📝 ${s.ship} 조황"), LinearLayout.LayoutParams(0, WRAP, 1f))
        sh.head.addView(Ui.pill(a, "닫기", false, Ui.TEXT, small = true) { sh.close() })
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        sh.body.addView(box)
        box.addView(Ui.text(a, 13f, Ui.SUB, false, "불러오는 중…"))
        Thread {
            val r = try { JSONObject(get("$API/ship/fish_board_list?ship_no=${s.shipNo}&page=1")) } catch (_: Exception) { null }
            a.runOnUiThread {
                box.removeAllViews()
                val l = r?.optJSONArray("list")
                if (l == null || l.length() == 0) { box.addView(Ui.text(a, 13f, Ui.SUB, false, if (r == null) "못 불러왔어요 · 인터넷을 확인해 주세요" else "아직 조황 글이 없어요")); return@runOnUiThread }
                box.addView(Ui.text(a, 11.5f, Ui.SUB, false, "선상24 조황 글 ${r.optInt("total")}개 중 최근 ${l.length()}개 · 누르면 선상24 조황 화면"))
                for (i in 0 until l.length()) {
                    val o = l.getJSONObject(i)
                    val row = LinearLayout(a).apply {
                        orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(10), dp(8), dp(10), dp(8)); background = Ui.bg(a, Color.parseColor("#12283A"), 12, Color.parseColor("#2C4F66"))
                        isClickable = true; setOnClickListener { Boats.openUrl(a, "https://www.sunsang24.com/ship/board/?ship_no=" + s.shipNo) }
                    }
                    val img = o.optString("image").let { if (it == "null") "" else it }
                    if (img.isNotBlank()) {
                        val iv = android.widget.ImageView(a).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; background = Ui.bg(a, Color.parseColor("#1B3A52"), 10); clipToOutline = true }
                        shipImg(a, img, iv)
                        row.addView(iv, LinearLayout.LayoutParams(dp(54), dp(54)).apply { rightMargin = dp(10) })
                    }
                    val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                    col.addView(Ui.text(a, 14f, Ui.TEXT, true, o.optString("title")).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    col.addView(Ui.text(a, 11.5f, Ui.SUB, false, listOf(o.optString("insert_dt").take(10), o.optString("fish_type")).filter { it.isNotBlank() && it != "null" }.joinToString(" · ")))
                    col.addView(Ui.text(a, 12f, Color.parseColor("#B8CAD6"), false, o.optString("contents")).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                    row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
                    box.addView(row, Ui.lp(a, MATCH, WRAP, top = 6))
                }
            }
        }.start()
        sh.show()
    }
    private fun openTime(at: String): Long = try { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).parse(at.take(16))!!.time } catch (_: Exception) { 0L }
    fun openLabel(at: String): String { val t = openTime(at); return if (t == 0L) at else SimpleDateFormat("M/d(E) HH:mm", Locale.KOREA).format(Date(t)) }
    private fun openKey(s: Sch) = "${s.shipNo}_${s.date}"
    fun openSet(c: Context, s: Sch) = (prefs(c).getStringSet("seat_open_al", emptySet()) ?: emptySet()).any { it.startsWith(openKey(s) + "|") }
    /** 🔔 예약 열리면 알림 — 열리는 시각에 한 번 (AlarmManager) */
    fun toggleOpen(c: Context, s: Sch) {
        val p = prefs(c); val set = HashSet(p.getStringSet("seat_open_al", emptySet()) ?: emptySet())
        val k = openKey(s); val on = set.any { it.startsWith("$k|") }
        set.removeAll { it.startsWith("$k|") }
        val t = openTime(s.openAt)
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(c, 7900 + k.hashCode().and(0xFFF), Intent(c, SeatAlarmReceiver::class.java).setAction("fishlog.seat.open.$k")
            .putExtra("open", k).putExtra("ship", s.ship).putExtra("date", s.date).putExtra("url", bookUrlOf(c, s)), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (on || t <= System.currentTimeMillis()) { am.cancel(pi); if (!on && t > 0) Ui.toast(c, "이미 열린 시각이에요"); p.edit().putStringSet("seat_open_al", set).apply(); return }
        set.add("$k|$t"); p.edit().putStringSet("seat_open_al", set).apply()
        try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi) } catch (_: Exception) {}
        Ui.toast(c, "🔔 ${openLabel(s.openAt)}에 알려 드릴게요 (몇 분 늦을 수 있어요)")
    }
    fun notifyOpen(c: Context, i: Intent) {
        channel(c)
        val k = i.getStringExtra("open") ?: return
        val p = prefs(c); val set = HashSet(p.getStringSet("seat_open_al", emptySet()) ?: emptySet()); set.removeAll { it.startsWith("$k|") }; p.edit().putStringSet("seat_open_al", set).apply()
        val req = 7900 + k.hashCode().and(0xFFF)
        val pi = PendingIntent.getActivity(c, req, Intent(Intent.ACTION_VIEW, Uri.parse(i.getStringExtra("url") ?: "https://www.sunsang24.com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = "${dayLabel(i.getStringExtra("date") ?: "")} ${i.getStringExtra("ship") ?: ""} 예약이 열렸어요 · 눌러서 바로 예약"
        val b = Notification.Builder(c, CH).setSmallIcon(R.drawable.ic_stat_octo).setContentTitle("🕙 예약 열림!").setContentText(text)
            .setContentIntent(pi).setAutoCancel(true).setShowWhen(true).setCategory(Notification.CATEGORY_REMINDER)
        try { c.getSystemService(NotificationManager::class.java).notify(req, b.build()) } catch (_: SecurityException) {}
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
        val host = if (isS24Fleet(s.host) || isTf(s.host)) s.host else ss?.host
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
            item("🌐 배 사이트 열기", host) { Boats.openUrl(a, if (isTf(host)) tfBook(host, s.date) else siteUrl(host, s.date)) }
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

    /** v6.85 (394): 더피싱 선단 고르기 — 지역 · 이름 찾기 · 누르면 내 선단에 */
    private fun openTfPicker(a: Activity, added: () -> Unit) {
        fun dp(v: Int) = Ui.dp(a, v)
        val sh = Sheet(a, 0.8f)
        sh.head.addView(Ui.text(a, 17f, Ui.TEXT, true, "🎣 더피싱 선단"), LinearLayout.LayoutParams(0, WRAP, 1f))
        sh.head.addView(Ui.pill(a, "닫기", false, Ui.TEXT, small = true) { sh.close() })
        val q = EditText(a).apply {
            hint = "지역 · 항구 · 배 · 선단 이름 (예: 오천, 야무진)"; setSingleLine(); textSize = 14.5f; setTextColor(Ui.TEXT); setHintTextColor(Ui.SUB)
            background = Ui.bg(a, Color.parseColor("#0F2231"), 12, Ui.LINE); setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        sh.body.addView(q, Ui.lp(a, MATCH, WRAP))
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        sh.body.addView(box, Ui.lp(a, MATCH, WRAP, top = 6))
        var all: List<TfShip> = emptyList()
        fun draw() {
            box.removeAllViews()
            if (all.isEmpty()) { box.addView(Ui.text(a, 13f, Ui.SUB, false, "더피싱 목록을 못 읽었어요 · [＋ 선단 추가]에 ○○.thefishing.kr 주소를 직접 넣으세요").apply { setPadding(0, dp(10), 0, 0) }); return }
            val have = fleets(a).map { it.host }.toSet()
            val k = q.text.toString().trim()
            val groups = all.filter { it.host.isNotBlank() }.groupBy { it.host }
                .filter { (_, l) -> k.isBlank() || l.any { s -> listOf(s.ship, s.area, s.fleet, s.fish).any { it.contains(k) } } || l.first().host.contains(k) }
                .entries.sortedBy { it.value.first().area }
            box.addView(Ui.text(a, 11.5f, Ui.SUB, false, "선단 ${groups.size}곳 · 누르면 ⚙ 내 선단에 넣어요"))
            groups.take(80).forEach { (h, l) ->
                val r = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(9), dp(12), dp(9))
                    background = Ui.bg(a, Color.parseColor("#12283A"), 12, if (h in have) Ui.GREEN else Color.parseColor("#2C4F66"))
                    isClickable = true
                    setOnClickListener { if (h in have) Ui.toast(a, "이미 넣은 선단이에요") else addFleet(a, h, l.first().fleet.substringBefore(" (").ifBlank { "" }) { added(); draw() } }
                }
                r.addView(Ui.text(a, 14.5f, Ui.TEXT, true, (if (h in have) "✓ " else "🎣 ") + l.first().fleet.substringBefore(" (").ifBlank { h }))
                r.addView(Ui.text(a, 11.5f, Ui.SUB, false, l.first().area.split(" > ").drop(1).joinToString(" ") + " · " + l.joinToString(" · ") { it.ship }.take(60)))
                box.addView(r, Ui.lp(a, MATCH, WRAP, top = 6))
            }
        }
        q.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { draw() }
            override fun beforeTextChanged(x: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(x: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        box.addView(Ui.text(a, 13f, Ui.SUB, false, "불러오는 중…"))
        tfShips(a) { l -> all = l; draw() }
        sh.show()
    }

    /** 선단 추가: 한 번 읽어 보고 배 이름으로 이름 짓기 */
    private fun addFleet(a: Activity, host: String, name0: String, done: () -> Unit) {
        Ui.toast(a, if (isS24Fleet(host) || isTf(host)) "선단 페이지 확인 중…" else "사이트 확인 중… (처음엔 30초쯤 걸릴 수 있어요)")
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
                if (w.hit) r.addView(Ui.pill(a, "예약 ›", true, Ui.GROUP, small = true) { Boats.openUrl(a, watchUrl(a, w)) }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
                // v7.01 (382): 🤖 자동 예약 (허락받은 사람 · 맞춰 둔 사이트만)
                if (autoSite(w.host) && canAuto(a)) {
                    val on = autoOn(a, w)
                    r.addView(Ui.pill(a, if (on) "🤖 자동 켬" else "🤖 자동", on, Color.parseColor("#2FBF8F"), small = true) {
                        if (!on && autoInfo(a).let { it.first.isBlank() || it.second.length < 10 }) { openAutoInfo(a) { setAuto(a, w, true); draw() }; return@pill }
                        setAuto(a, w, !on); Ui.toast(a, if (!on) "🤖 자리가 나면 ${w.need}명으로 예약 신청까지 해요 (밤 11시~아침 7시는 알림만)" else "자동 예약 끔"); draw()
                    }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
                }
                r.addView(Ui.pill(a, "끄기", false, Ui.TEXT, small = true) {
                    val m = watches(a); m.removeAll { it.host == w.host && it.shipNo == w.shipNo && it.date == w.date }; saveWatches(a, m); schedule(a); draw()
                })
                sh.body.addView(r, Ui.lp(a, MATCH, WRAP, top = 6))
            }
            if (canAuto(a)) sh.body.addView(Ui.button(a, "🤖 자동 예약 정보 (이름 · 전화)") { openAutoInfo(a) { draw() } }.apply { minHeight = dp(42); textSize = 13.5f }, Ui.lp(a, MATCH, WRAP, top = 10))
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
        if (i.getStringExtra("open") != null) { try { Seats.notifyOpen(app, i) } catch (_: Exception) {}; return }
        val pr = goAsync()
        Thread {
            try { Seats.check(app) } catch (_: Exception) {}
            Seats.schedule(app)
            pr.finish()
        }.start()
    }
}
