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
              val status: String, val tide: String) {
        val open get() = status != "END" && status != "CHECK" && remain > 0
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
        val h = raw.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore('?').trim()
        return if (h.endsWith(".sunsang24.com") && h != "www.sunsang24.com" && h != "api.sunsang24.com" && h.count { it == '.' } == 2) h else null
    }

    // ─────────────── 읽기 · 풀기 ───────────────
    private val mem = HashMap<String, Pair<Long, List<Sch>>>()
    private const val KEEP = 10 * 60 * 1000L

    private fun get(url: String): String {
        val cn = URL(url).openConnection() as HttpURLConnection
        cn.connectTimeout = 8000; cn.readTimeout = 12000; cn.instanceFollowRedirects = true
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
        val l = parse(host, get("https://$host/ship/schedule_fleet/$ym"))
        synchronized(mem) { mem[k] = System.currentTimeMillis() to l }
        return l
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

    fun bookUrl(s: Sch) = "https://${s.host}/ship/schedule_fleet/${s.date.replace("-", "").take(6)}"
    fun bookUrl(w: Watch) = "https://${w.host}/ship/schedule_fleet/${w.date.replace("-", "").take(6)}"

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
        val pi = PendingIntent.getActivity(c, req, Intent(Intent.ACTION_VIEW, Uri.parse(bookUrl(w)))
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
        val fails = ArrayList<String>()
        var loading = false

        // 제목 줄
        val head = Ui.row(a).apply { setPadding(dp(16), dp(8), dp(12), dp(4)) }
        head.addView(Ui.text(a, 18f, Color.WHITE, true, "🚤 선상 빈자리"), LinearLayout.LayoutParams(0, WRAP, 1f))
        val alarmBtn = Ui.pill(a, "🔔 0", false, Ui.TEXT, small = true)
        head.addView(alarmBtn, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
        val fleetBtn = Ui.pill(a, "⚙ 선단", false, Ui.TEXT, small = true)
        head.addView(fleetBtn, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
        head.addView(Ui.pill(a, "닫기", false, Ui.TEXT, small = true) { dlg.dismiss() })
        root.addView(head)

        // 날짜 줄 (오늘부터 30일)
        val dateRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; setPadding(dp(10), dp(4), dp(10), dp(6)) }
        root.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(dateRow) })
        // 고르기 줄
        val pick = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; setPadding(dp(10), 0, dp(10), dp(6)) }
        root.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(pick) })
        val fishBtn = Ui.pill(a, "🐟 어종 전체 ▾", false, Ui.TEXT, small = true)
        val needBtn = Ui.pill(a, "👥 1자리 이상 ▾", false, Ui.TEXT, small = true)
        val filtBtn = Ui.pill(a, "⚙ 필터", false, Ui.TEXT, small = true)
        listOf(fishBtn, needBtn, filtBtn).forEach { pick.addView(it, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) }) }
        val sum = Ui.text(a, 12f, Ui.SUB, false, "").apply { setPadding(dp(16), 0, dp(16), dp(4)) }
        root.addView(sum)
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(24)) }
        root.addView(ScrollView(a).apply { addView(list) }, LinearLayout.LayoutParams(MATCH, 0, 1f))

        lateinit var render: () -> Unit
        lateinit var load: (Boolean) -> Unit

        fun savePrefs() = p.edit().putString("seat_day", day).putInt("seat_need", need).putInt("seat_sort", sort)
            .putBoolean("seat_open", openOnly).putBoolean("seat_favtop", favTop).putStringSet("seat_fish", HashSet(fishSel)).apply()

        fun fishMatch(s: Sch) = fishSel.isEmpty() || fishSel.any { f -> s.fish.split(',', '/', '·', ' ').any { it.trim() == f } || s.fish.contains(f) }
        fun fishList(s: Sch) = s.fish.split(',', '/', '·').map { it.trim() }.filter { it.isNotBlank() }

        fun paintDates() {
            dateRow.removeAllViews()
            val cal = Calendar.getInstance()
            repeat(30) {
                val d = ymd.format(cal.time)
                val dow = cal.get(Calendar.DAY_OF_WEEK)
                val n = all.count { s -> s.date == d && s.open && s.remain >= need && fishMatch(s) }
                val on = d == day
                val cell = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, dp(3), 0, dp(3))
                    background = if (on) Ui.bg(a, Ui.GROUP, 12, Color.parseColor("#7FD6FF")) else Ui.bg(a, Color.parseColor("#12283A"), 12, Color.parseColor("#2C4F66"))
                    isClickable = true
                    setOnClickListener { day = d; savePrefs(); render(); load(false) }
                }
                cell.addView(Ui.text(a, 10.5f, if (on) Color.WHITE else Ui.SUB, false, SimpleDateFormat("E", Locale.KOREA).format(cal.time)).apply { gravity = Gravity.CENTER })
                cell.addView(Ui.text(a, 16f, when { on -> Color.WHITE; dow == Calendar.SUNDAY -> Color.parseColor("#FF8A8A"); dow == Calendar.SATURDAY -> Color.parseColor("#7FB8FF"); else -> Ui.TEXT },
                    true, "${cal.get(Calendar.DAY_OF_MONTH)}").apply { gravity = Gravity.CENTER; includeFontPadding = false })
                cell.addView(Ui.text(a, 9.5f, if (on) Color.WHITE else if (n > 0) Color.parseColor("#2FBF8F") else Color.parseColor("#4A5966"), false,
                    if (all.isEmpty()) "·" else if (n > 0) "${n}척" else "-").apply { gravity = Gravity.CENTER })
                dateRow.addView(cell, LinearLayout.LayoutParams(dp(48), WRAP).apply { rightMargin = dp(5) })
                cal.add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        fun card(s: Sch, fav: Boolean): View {
            val w = watchOf(a, s)
            val box = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
                background = Ui.bg(a, Color.parseColor("#12283A"), 14, if (fav) Color.parseColor("#F2C14E") else Color.parseColor("#2C4F66"))
                alpha = if (s.open) 1f else 0.6f
            }
            val top = Ui.row(a)
            val fleetName = fleets(a).firstOrNull { it.host == s.host }?.name ?: s.host.substringBefore('.')
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
                listOf(s.fish, s.way, s.time, s.tide).filter { it.isNotBlank() }.joinToString(" · ")).apply { maxLines = 2 })
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
                val head2 = when { s.status == "CHECK" -> "점검일"; !s.open -> "마감"; else -> "${s.remain}자리" }
                sb.append(head2)
                sb.setSpan(android.text.style.ForegroundColorSpan(col), 0, sb.length, 0)
                sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length, 0)
                sb.setSpan(android.text.style.RelativeSizeSpan(1.2f), 0, sb.length, 0)
                val tail = when {
                    s.status == "CHECK" -> ""
                    !s.open -> " · 예약 ${s.reserved}/${s.cap}"
                    s.remain <= 3 -> " 남음 · 곧 마감"
                    else -> " 남음 · 예약 ${s.reserved}/${s.cap}"
                }
                sb.append(tail); text = sb; textSize = 12.5f; setTextColor(Ui.TEXT)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            val canBook = s.open && s.remain >= need
            if (canBook) {
                bot.addView(Ui.pill(a, "예약 ›", true, Ui.GROUP, small = true) { Boats.openUrl(a, bookUrl(s)) })
            } else if (s.status != "CHECK" && allowed(a)) {
                val label = if (w != null) "🔔 알림 켜짐 · ${w.need}자리" else "🔔 자리 나면 알림"
                bot.addView(Ui.pill(a, label, w != null, Color.parseColor("#F2C14E"), small = true) {
                    if (w != null) askWatchOff(a, w) { render() } else addWatch(a, s, need) { render() }
                })
            } else if (s.status != "CHECK") {
                bot.addView(Ui.pill(a, "🔒 알림", false, Ui.TEXT, small = true) {
                    Ui.toast(a, "🔔 '자리 나면 알림'은 관리자가 허락한 분만 쓸 수 있어요 · ✉ 1:1 문의로 요청해 주세요")
                }.apply { alpha = 0.7f })
            }
            box.addView(bot)
            box.setOnLongClickListener { Boats.openUrl(a, bookUrl(s)); true }
            return box
        }

        render = render@{
            paintDates()
            fishBtn.text = when (fishSel.size) { 0 -> "🐟 어종 전체 ▾"; 1 -> "🐟 ${fishSel.first()} ▾"; else -> "🐟 ${fishSel.first()} 외 ${fishSel.size - 1} ▾" }
            fishBtn.setTextColor(if (fishSel.isEmpty()) Ui.TEXT else Color.parseColor("#7FD6FF"))
            needBtn.text = "👥 ${need}자리 이상 ▾"
            needBtn.setTextColor(if (need > 1) Color.parseColor("#7FD6FF") else Ui.TEXT)
            filtBtn.text = "⚙ " + arrayOf("자리 많은 순", "출항 빠른 순", "이름 순")[sort] + (if (openOnly) " · 빈자리만" else "")
            alarmBtn.visibility = if (allowed(a)) View.VISIBLE else View.GONE
            val ws = watches(a)
            alarmBtn.text = if (ws.any { it.hit }) "🔔 ${ws.size} · 자리 남!" else "🔔 ${ws.size}"
            alarmBtn.setTextColor(if (ws.any { it.hit }) Color.parseColor("#FF6B6B") else Ui.TEXT)
            list.removeAllViews()
            val fl = fleets(a).filter { it.on }
            if (fleets(a).isEmpty()) {
                list.addView(Ui.text(a, 14f, Ui.TEXT, false, "아직 볼 선단이 없어요.\n\n[⚙ 선단]에서 선상24 선단 주소를 넣어 주세요.\n예) sungbin.sunsang24.com").apply { setPadding(dp(6), dp(20), dp(6), dp(10)) })
                list.addView(Ui.button(a, "＋ 선단 추가") { openFleets(a) { load(true) } }, Ui.lp(a, MATCH, WRAP, top = 8))
                sum.text = ""; return@render
            }
            var day0 = all.filter { it.date == day && it.host in fl.map { f -> f.host } }
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
            sum.text = (if (loading) "⏳ 읽는 중 · " else "") + "${dayLabel(day)} · 배 ${total}척 · ${need}자리 이상 빈 배 ${openN}척" +
                (if (fails.isNotEmpty()) " · ⚠ 못 읽은 선단 ${fails.size}곳" else "")
            if (day0.isEmpty()) {
                list.addView(Ui.text(a, 13.5f, Ui.SUB, false, if (loading) "선상24에서 읽는 중…" else if (total == 0) "이 날 나가는 배가 없어요 (선단이 일정을 아직 안 올렸을 수 있어요)" else "고른 조건에 맞는 배가 없어요").apply { setPadding(dp(6), dp(20), dp(6), 0) })
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
            list.addView(Ui.text(a, 11f, Ui.SUB, false, "선상24 선단 페이지를 내 폰이 읽어 와요 (10분 기억) · 카드 꾹 = 예약 화면 · 예약은 선상24에서").apply { setPadding(dp(4), dp(14), dp(4), 0) })
        }

        load = { fresh ->
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

        fishBtn.setOnClickListener {
            val sh = Sheet(a, 0.7f)
            sh.head.addView(Ui.text(a, 17f, Ui.TEXT, true, "🐟 어종"), LinearLayout.LayoutParams(0, WRAP, 1f))
            val names = all.filter { it.date >= ymd.format(Date()) }.flatMap { fishList(it) }.groupingBy { it }.eachCount()
            val sel = HashSet(fishSel)
            fun chips(box: LinearLayout, l: List<String>) {
                val wrap = FlowLayout(a)
                l.forEach { f ->
                    lateinit var tv: TextView
                    tv = Ui.pill(a, f + (names[f]?.let { " $it" } ?: ""), f in sel, Ui.MINE, small = true) {
                        if (f in sel) sel.remove(f) else sel.add(f); sh.close(); fishSel.clear(); fishSel.addAll(sel); savePrefs(); render()
                    }
                    wrap.addView(tv)
                }
                box.addView(wrap)
            }
            sh.body.addView(Ui.pill(a, "전체 보기", sel.isEmpty(), Ui.MINE, small = true) { fishSel.clear(); savePrefs(); sh.close(); render() }, LinearLayout.LayoutParams(WRAP, WRAP))
            val ceph = names.keys.filter { n -> CEPH.split(' ').any { n.contains(it) } }.sortedByDescending { names[it] }
            val fish = names.keys.filter { it !in ceph }.sortedByDescending { names[it] }
            if (names.isEmpty()) sh.body.addView(Ui.text(a, 13f, Ui.SUB, false, "선단 일정을 다 읽으면 어종이 나와요").apply { setPadding(0, dp(12), 0, 0) })
            if (ceph.isNotEmpty()) { sh.body.addView(Ui.text(a, 13.5f, Ui.TEXT, true, "두족류").apply { setPadding(0, dp(14), 0, dp(6)) }); chips(sh.body, ceph) }
            if (fish.isNotEmpty()) { sh.body.addView(Ui.text(a, 13.5f, Ui.TEXT, true, "물고기 · 그 밖").apply { setPadding(0, dp(14), 0, dp(6)) }); chips(sh.body, fish) }
            sh.body.addView(Ui.text(a, 11.5f, Ui.SUB, false, "누를 때마다 더하고 빼요 (여러 개 · 숫자 = 앞으로 나가는 날 수)").apply { setPadding(0, dp(12), 0, 0) })
            sh.show()
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
        fleetBtn.setOnClickListener { openFleets(a) { load(true) } }
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
            sh.body.addView(Ui.button(a, "＋ 선단 추가 (주소 붙여 넣기)", true) {
                val et = EditText(a).apply { hint = "sungbin.sunsang24.com"; inputType = InputType.TYPE_TEXT_VARIATION_URI; setSingleLine() }
                android.app.AlertDialog.Builder(a, R.style.FlAlert).setTitle("🚤 선단 추가")
                    .setMessage("선상24 선단 홈페이지 주소 (○○.sunsang24.com)")
                    .setView(LinearLayout(a).apply { setPadding(dp(20), 0, dp(20), 0); addView(et, LinearLayout.LayoutParams(MATCH, WRAP)) })
                    .setPositiveButton("추가") { _, _ ->
                        val h = hostOf(et.text.toString())
                        if (h == null) Ui.toast(a, "선상24 선단 주소가 아니에요 (예: sungbin.sunsang24.com)")
                        else addFleet(a, h, "") { dirty = true; draw() }
                    }.setNegativeButton("취소", null).show()
            }, Ui.lp(a, MATCH, WRAP, top = 14))
            sh.body.addView(Ui.text(a, 11.5f, Ui.SUB, false, "앱이 선단 일정 페이지를 직접 읽어요 (선상24 공식 기능이 아니라 사이트가 바뀌면 안 될 수 있어요) · 열 때 · 🔔 알림 걸어 둔 동안 15분마다만 읽어요").apply { setPadding(0, dp(12), 0, 0) })
        }
        draw(); sh.show()
    }

    /** 선단 추가: 한 번 읽어 보고 배 이름으로 이름 짓기 */
    private fun addFleet(a: Activity, host: String, name0: String, done: () -> Unit) {
        Ui.toast(a, "선단 페이지 확인 중…")
        Thread {
            val r = try { month(host, ymF.format(Date()), true) } catch (_: Exception) { null }
            a.runOnUiThread {
                if (r == null) { Ui.toast(a, "읽지 못했어요 · 주소를 확인해 주세요 ($host)"); return@runOnUiThread }
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
