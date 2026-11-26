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

    private fun get(url: String): String =
        if (isTfUrl(url) && url.startsWith("https://")) try { get0(url) } catch (e: Exception) { try { get0("http://" + url.removePrefix("https://")) } catch (_: Exception) { throw e } }
        else get0(url)
    private fun get0(url: String): String {
        val cn = URL(url).openConnection() as HttpURLConnection
        // 개인 사이트(무료 서버)는 처음 깨어나는 데 오래 걸려서 넉넉히
        val slow = !url.contains("sunsang24.com")
        cn.connectTimeout = if (slow) 20000 else 8000; cn.readTimeout = if (slow) 40000 else 12000; cn.instanceFollowRedirects = true
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
    val SORTS = arrayOf("자리 많은 순", "출항 빠른 순", "이름 순", "📝 조황 많은 순", "🕘 최근 조황 순")
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

    // v6.86 (395): 배 사진 (작은 사진 · 메모리 + 폰 파일)
    private val imgMem = android.util.LruCache<String, android.graphics.Bitmap>(60)
    private val imgBusy = HashSet<String>()
    fun shipImg(c: Context, url: String, view: android.widget.ImageView) {
        if (url.isBlank()) return
        view.tag = url
        imgMem.get(url)?.let { view.setImageBitmap(it); return }
        if (!synchronized(imgBusy) { imgBusy.add(url) }) return
        Thread {
            val f = java.io.File(c.cacheDir, "shipimg/" + Integer.toHexString(url.hashCode()) + ".jpg")
            val bm = try {
                if (f.exists() && System.currentTimeMillis() - f.lastModified() < 14 * 86400_000L) android.graphics.BitmapFactory.decodeFile(f.path)
                else {
                    val cn = URL(url).openConnection() as HttpURLConnection
                    cn.connectTimeout = 8000; cn.readTimeout = 10000
                    val bytes = cn.inputStream.use { it.readBytes() }; cn.disconnect()
                    f.parentFile?.mkdirs(); f.writeBytes(bytes)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
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
                srcOf(o.host) != src && o.also.none { srcOf(it.host) == src } && portsOk(portOf(c, o), portOf(c, s)) }
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
    }
    /** 기억해 둔 배 이름 중 목록에 없는 배를 목록에 붙임 (지역 · 선단 = 같은 선단 다른 배 것) */
    private fun withSeen(c: Context, l: List<TfShip>): List<TfShip> {
        val p = prefs(c); val have = l.map { it.host + "|" + it.uid }.toHashSet()
        val haveName = l.map { it.host + "|" + normShip(it.ship) }.toHashSet()
        val byHost = l.groupBy { it.host }
        val add = ArrayList<TfShip>()
        p.all.keys.filter { it.startsWith("tf_seen_") }.forEach { key ->
            val host = key.removePrefix("tf_seen_")
            val o = try { JSONObject(p.getString(key, "{}") ?: "{}") } catch (_: Exception) { return@forEach }
            if (System.currentTimeMillis() - o.optLong("t") > 30L * 86400000) { p.edit().remove(key).apply(); return@forEach }
            val m = o.optJSONObject("s") ?: return@forEach
            val ref = byHost[host]?.firstOrNull()
            m.keys().forEach { uid ->
                val n = m.optString(uid)
                if ("$host|$uid" !in have && "$host|${normShip(n)}" !in haveName)
                    add.add(TfShip(uid, n, ref?.area ?: "", ref?.fleet ?: host.substringBefore('.'), "", "", host))
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
        val base = try { JSONObject(c.assets.open("tf_sites.json").bufferedReader().readText()).let { baseT = it.optLong("t"); tfParse(it.optJSONArray("j")?.toString()) } } catch (_: Exception) { emptyList() }
        com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("config").document("tfSites").get()
            .addOnSuccessListener { d ->
                // 관리자가 새로 모은 목록: 주소를 못 찾은 배(자기 도메인 선단 등)는 기본 목록 주소로 채움
                val bu = base.associateBy { it.uid }
                val srv = if ((d.getLong("t") ?: 0L) > baseT) tfParse(d.getString("j")).map { x -> if (x.host.isBlank()) bu[x.uid]?.let { b -> TfShip(x.uid, x.ship, x.area, x.fleet, x.fish, x.seat, b.host) } ?: x else x }.filter { it.host.isNotBlank() } else emptyList()
                val l = withSeen(c, if (srv.size >= 100) srv else base)
                tfMem = l; done(l)
            }.addOnFailureListener { val l = withSeen(c, base); tfMem = l; done(l) }
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
    class ShipSite(val host: String?, val band: String?, val tel: String?, val name: String = "", val port: String = "")
    private const val SITES_URL = "https://talla.co.kr/data/seat_sites.json"
    @Volatile private var sitesMem: Map<String, ShipSite>? = null
    private val siteTried = HashSet<String>()
    private fun sitesFile(c: Context) = java.io.File(c.filesDir, "seat_sites.json")
    private fun parseSites(t: String): Map<String, ShipSite> {
        val o = JSONObject(t).optJSONObject("ships") ?: return emptyMap()
        val m = HashMap<String, ShipSite>()
        o.keys().forEach { k -> val v = o.optJSONObject(k) ?: return@forEach
            m[k] = ShipSite(v.optString("h").ifBlank { null }, v.optString("b").ifBlank { null }, v.optString("t").ifBlank { null }, v.optString("n"), v.optString("p")) }
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
        appCtx = c.applicationContext
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
                    w.hit = ok; w.last = s.remain; w.at = System.currentTimeMillis(); rememberSch(c, w, s)
                }
                continue
            }
            val sch = try { month(k.first, k.second, fresh = true) } catch (_: Exception) { continue }
            for (w in list) {
                val s = sch.firstOrNull { it.shipNo == w.shipNo && it.date == w.date } ?: continue
                val ok = s.open && s.remain >= w.need
                if (ok && !w.hit) { notify(c, w, s); fired++ }
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
        var useS24 = p.getBoolean("seat_s24", true)
        val areaSel = HashSet(p.getStringSet("seat_area", emptySet()) ?: emptySet())
        val s24 = ArrayList<Sch>()
        var s24Key = ""; var s24Total = 0; var s24Pg = 0; var s24Busy = false; var s24Err = false
        Thread { try { s24Filter(a) } catch (_: Exception) {} }.start()
        val fails = ArrayList<String>()
        var loading = false
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
        listOf(qBtn, areaBtn, fishBtn, needBtn, sortBtn, filtBtn).forEach { pick.addView(it, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) }) }
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

        fun savePrefs() = p.edit().putBoolean("seat_s24", useS24).putStringSet("seat_area", HashSet(areaSel)).putString("seat_day", day).putInt("seat_need", need).putInt("seat_sort", sort)
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
        fun fishMatch(s: Sch) = timeMatch(s) && (fishSel.isEmpty() || fishSel.any { f -> s.fish.split(',', '/', '·', ' ').any { it.trim() == f } || s.fish.contains(f) })
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
            else -> l.sortedWith(compareBy({ !(it.open && it.remain >= need) }, { -it.remain }, { it.ship }))
        }

        // v6.85 (393): 날짜 버튼 숫자에 선상24 배도 (아래 '빈 배 N척'과 같게)
        fun countOn(d: String): Int {
            val mine = all.filter { x -> x.date == d }
            val nos = mine.map { it.shipNo }.toSet()
            return dedupe(a, mine + s24.filter { it.date == d && it.shipNo !in nos }).count { x -> x.open && x.remain >= need && fishMatch(x) }
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
            val fleetName = if (s.host == S24) s.place.ifBlank { "선상24" } else fleets(a).firstOrNull { it.host == s.host }?.name ?: s.host.substringBefore('.')
            val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
            // 배 사진 (선상24 작은 사진 · 없으면 🚤)
            val picBox = android.widget.FrameLayout(a).apply { background = Ui.bg(a, Color.parseColor("#1B3A52"), 12, Color.parseColor("#2C4F66")); clipToOutline = true }
            picBox.addView(Ui.text(a, 26f, Color.WHITE, false, "🚤").apply { gravity = Gravity.CENTER }, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
            if (s.img.isNotBlank()) {
                val iv = android.widget.ImageView(a).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP }
                picBox.addView(iv, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
                shipImg(a, s.img, iv)
                picBox.setOnClickListener { showPhoto(a, s.img.replace("_smini.", "_mini."), s.ship) }
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
            (listOf(srcOf(s.host)) + s.also.map { srcOf(it.host) }).distinct().forEach { src ->
                l3.addView(Ui.text(a, 10.5f, Color.WHITE, true, src).apply {
                    setPadding(dp(6), dp(1), dp(6), dp(1)); background = Ui.bg(a, srcCol(src), 6)
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(4) })
            }
            portOf(a, s).takeIf { it.isNotBlank() && !fleetName.contains(it) }?.let { pt ->
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
            // 막대
            val bar = LinearLayout(a).apply { background = Ui.bg(a, Color.parseColor("#22384A"), 99) }
            val frac = if (s.cap > 0) s.remain.toFloat() / s.cap else 0f
            val col = when { !s.open -> Color.parseColor("#4A5966"); s.remain <= 3 -> Color.parseColor("#F2C14E"); else -> Color.parseColor("#2FBF8F") }
            bar.addView(View(a).apply { background = Ui.bg(a, col, 99) }, LinearLayout.LayoutParams(0, MATCH, frac.coerceIn(0f, 1f)))
            bar.addView(View(a), LinearLayout.LayoutParams(0, MATCH, (1f - frac).coerceIn(0f, 1f) + 0.0001f))
            box.addView(bar, LinearLayout.LayoutParams(MATCH, dp(7)).apply { topMargin = dp(8); bottomMargin = dp(5) })
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
            // v6.90 (399): 같은 배가 다른 곳에도 있으면 그쪽 예약 버튼 (남은 자리 같이)
            s.also.forEach { o -> link("${srcOf(o.host)} " + (if (o.open) "${o.remain}자리 ›" else "${o.stName} ›")) { Boats.openUrl(a, bookUrlOf(a, o)) } }
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
            areaBtn.visibility = if (useS24) View.VISIBLE else View.GONE
            needBtn.text = "👥 ${need}자리 이상 ▾"
            needBtn.setTextColor(if (need > 1) Color.parseColor("#7FD6FF") else Ui.TEXT)
            val nFilt = (if (openOnly) 1 else 0) + (if (todSel.isNotEmpty()) 1 else 0) + (if (durSel != 0) 1 else 0)
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
                val nameC = (all + s24).filter { norm(it.ship).contains(nq) || norm(it.ship.substringBefore('[')).contains(nq) }
                val portC = if (byPort) (all + s24).filter { portHit(portOf(a, it)) || (it.host == S24 && portHit(it.place.substringAfterLast(" · "))) } else emptyList()
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
                // 아직 안 읽은 선상24 배: talla.co.kr 배 목록에서 (이름 · 항구)
                val more = sites(a).filter { (no, v) -> no !in shown && ((v.name.isNotBlank() && norm(v.name).contains(nq)) || portHit(v.port)) }.entries.take(if (byPort) 30 else 12)
                more.forEach { (no, v) ->
                    val f = found[no]
                    val hit = f?.firstOrNull { it.date == day }
                    if (hit != null) { dayCards.add(hit); return@forEach }
                    val next = f?.filter { it.date > day && (it.status == "ING" || it.status == "END") }?.minByOrNull { it.date }
                    note("🚤 " + v.name + if (v.port.isNotBlank()) "  · ${v.port}" else "",
                        when { f == null -> "⏳ 일정 읽는 중…"; next != null -> "이 날 일정 없음 · 다음 출항 ${dayLabel(next.date)} › (남은 ${next.remain}자리)"; else -> "이 날 · 앞으로 두 달 일정이 없어요" },
                        if (next != null) Color.parseColor("#7FD6FF") else Ui.SUB, next?.let { n -> { setDay(n.date) } })
                    if (f == null && synchronized(foundBusy) { foundBusy.add(no) }) pool.execute {
                        val l = ArrayList<Sch>()
                        val cal = Calendar.getInstance().apply { time = ymd.parse(day)!! }
                        repeat(2) { try { l.addAll(s24Month(no, ymF.format(cal.time))) } catch (_: Exception) {}; cal.add(Calendar.MONTH, 1) }
                        a.runOnUiThread { found[no] = l; if (dlg.isShowing) later() }
                    }
                }
                // 더피싱 (기본 목록 678척)
                val tf = tfMem
                if (tf == null && synchronized(foundBusy) { foundBusy.add("tf") }) tfShips(a) { a.runOnUiThread { if (dlg.isShowing) render() } }
                val have = activeFleets(a).map { it.host }.toSet()
                // v6.91 (401 · 403): 더피싱 = 항구로 찾으면 그 항구 선단의 그 날 배 전부 · 이름으로 찾으면 그 배 · 선단 일정을 바로 읽어 카드로
                val tfPortHosts = if (byPort) (tf ?: emptyList()).filter { it.host.isNotBlank() && it.host !in have && portHit(it.area.substringAfterLast(" > ")) }
                    .groupBy { it.host }.entries.take(10).map { it.key to it.value } else emptyList()
                val tfPortSet = tfPortHosts.map { it.first }.toSet()
                val tfNameHosts = (tf ?: emptyList()).filter { it.host.isNotBlank() && it.host !in have && it.host !in tfPortSet && norm(it.ship).contains(nq) }
                    .groupBy { it.host }.entries.take(8).map { it.key to it.value }
                fun readTf(host: String, l: List<TfShip>, byName: Boolean) {
                    val k = "tf:$host|" + day.replace("-", "").take(6)
                    val f = found[k]
                    fun want(x: Sch) = !byName || norm(x.ship).contains(nq) || norm(x.ship.substringBefore('[')).contains(nq)
                    val hits = f?.filter { it.date == day && want(it) }.orEmpty()
                    if (hits.isNotEmpty()) { dayCards.addAll(hits); return }
                    val next = f?.filter { it.date > day && want(it) && (it.status == "ING" || it.status == "END") }?.minByOrNull { it.date }
                    val fleetNm = l.first().fleet.substringBefore(" (").ifBlank { host.substringBefore('.') }
                    val title = if (byName) "🎣 " + l.joinToString(" · ") { it.ship }.take(40) + "  · $fleetNm" else "🎣 $fleetNm  · " + l.joinToString(" · ") { it.ship }.take(40)
                    note(title,
                        when { f == null -> "⏳ 더피싱 일정 읽는 중…"; next != null -> "이 날 일정 없음 · 다음 출항 ${dayLabel(next.date)} ›"; else -> "이 달 · 이 날 일정이 없거나 못 읽었어요 · 꾹 = 내 선단에 넣기" },
                        if (next != null) Color.parseColor("#7FD6FF") else Ui.SUB, next?.let { n -> { setDay(n.date) } })
                    (notes.last()).setOnLongClickListener { addFleet(a, host, fleetNm) { load(true) }; true }
                    if (f == null && synchronized(foundBusy) { foundBusy.add(k) }) pool.execute {
                        val r = try { month(host, day.replace("-", "").take(6)) } catch (_: Exception) { emptyList() }
                        a.runOnUiThread { found[k] = r; if (dlg.isShowing) later() }
                    }
                }
                tfPortHosts.forEach { (h, l) -> readTf(h, l, false) }
                tfNameHosts.forEach { (h, l) -> readTf(h, l, true) }
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
            if (activeFleets(a).isEmpty() && !useS24) {
                list.addView(Ui.text(a, 14f, Ui.TEXT, false, "아직 볼 선단이 없어요.\n\n[⚙ 선단]에서 선상24 선단 주소를 넣어 주세요.\n예) sungbin.sunsang24.com").apply { setPadding(dp(6), dp(20), dp(6), dp(10)) })
                list.addView(Ui.button(a, "＋ 선단 추가") { openFleets(a) { load(true) } }, Ui.lp(a, MATCH, WRAP, top = 8))
                sum.text = ""; return@render
            }
            val mineDay = all.filter { it.date == day && it.host in fl.map { f -> f.host } }
            val mineNos = mineDay.map { it.shipNo }.toSet()
            var day0 = dedupe(a, mineDay + (if (useS24) s24.filter { it.date == day && it.shipNo !in mineNos } else emptyList()))
            val total = day0.size
            day0 = day0.filter { fishMatch(it) }
            val openN = day0.count { it.open && it.remain >= need }
            if (openOnly) day0 = day0.filter { it.open && it.remain >= need }
            day0 = sortList(day0)
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
            pageReset("m|$day|${areaSel.sorted()}|${fishSel.sorted()}|$sort|$openOnly|$favTop|${todSel.sorted()}|$durSel|$need|$useS24")
            val restShown = rest.take(maxOf(0, shownN - favL.size))
            if (favL.isNotEmpty()) { section("⭐ 즐겨찾기", Color.parseColor("#F2C14E")); favL.forEach { list.addView(card(it, true), Ui.lp(a, MATCH, WRAP, top = 6)) } }
            if (restShown.isNotEmpty()) {
                if (favL.isNotEmpty()) section("그 밖의 배", Ui.SUB)
                restShown.forEach { list.addView(card(it, it.key in fv), Ui.lp(a, MATCH, WRAP, top = 6)) }
            }
            if (restShown.size < rest.size) list.addView(moreBtn(favL.size + restShown.size, favL.size + rest.size), Ui.lp(a, MATCH, WRAP, top = 10))
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
            val fl = activeFleets(a)
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
            sh.body.addView(Ui.text(a, 11.5f, Ui.SUB, false, "조황 순은 선상24 배만 알아요 · 더피싱 · 개인 사이트 배는 맨 뒤 · 최근 조황 순은 날짜를 받아 오며 순서가 바뀌어요").apply { setPadding(0, dp(10), 0, 0) })
            sh.show()
        }
        fleetBtn.setOnClickListener { openFleets(a) { useS24 = p.getBoolean("seat_s24", true); load(true) } }
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
        if (i.getStringExtra("open") != null) { try { Seats.notifyOpen(app, i) } catch (_: Exception) {}; return }
        val pr = goAsync()
        Thread {
            try { Seats.check(app) } catch (_: Exception) {}
            Seats.schedule(app)
            pr.finish()
        }.start()
    }
}
