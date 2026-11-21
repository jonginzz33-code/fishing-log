package com.fishlog.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * v6.40 (303): 🔔 그룹 알림 (상단 상태 표시줄 · 카톡처럼)
 *  - 서버 푸시 없이 폰이 1시간마다 정각쯤 한 번 확인 (안드로이드가 몇 분 늦출 수 있음)
 *  - 🟢 그룹원 출조 중 · 💬 채팅 새 글 · 📢 새 공지 · ⏰ 새 일정 · 🙋 내 일정 참석 답
 *  - 내가 쓴 것은 빼고 · 앱을 보고 있는 동안엔 안 울림 (앱 안 NEW 표시로)
 */
object GroupAlert {
    const val CH_LIVE = "ga_live"
    const val CH_GROUP = "ga_group"
    private const val ID_LIVE = 7101
    private const val REQ = 7100
    private const val ACT = "com.fishlog.app.GROUP_ALERT"

    val KINDS = listOf("live" to "🟢 그룹원 출조 중", "chat" to "💬 그룹 채팅 새 글", "notice" to "📢 새 공지",
        "event" to "⏰ 새 일정", "rsvp" to "🙋 내 일정 참석 답", "remind" to "📅 내일 출조 (전날 낮 12시)",
        "cmt" to "💬 출조 댓글 (내 출조 · 내가 댓글 단 출조)", "allchat" to "🌐 전체 채팅 새 글")

    private fun prefs(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)
    fun on(c: Context, kind: String) = prefs(c).getBoolean("ga_$kind", kind != "allchat")   // v6.48: 전체 채팅은 기본 꺼짐
    fun set(c: Context, kind: String, v: Boolean) { prefs(c).edit().putBoolean("ga_$kind", v).apply(); if (!v) clear(c, kind) }
    private fun anyOn(c: Context) = KINDS.any { on(c, it.first) }

    // ---- 앱을 보고 있는지 (보고 있으면 안 울림) ----
    @Volatile private var started = 0
    val visible: Boolean get() = started > 0
    fun install(app: android.app.Application) {
        app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) {
                started++
                // 앱을 열면 채팅 · 공지 · 일정 · 참석 알림은 정리 (앱 안 NEW 표시로 보여요)
                if (started == 1) try {
                    val nm = a.getSystemService(NotificationManager::class.java)
                    nm.activeNotifications.filter { it.id != ID_LIVE && it.notification.channelId == CH_GROUP }.forEach { nm.cancel(it.id) }
                } catch (_: Exception) {}
            }
            override fun onActivityStopped(a: android.app.Activity) { if (started > 0) started-- }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        schedule(app)
    }

    /** 다음 정각에 한 번 (계속 이어서) */
    fun schedule(c: Context) {
        try {
            val am = c.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(c, REQ, Intent(c, GroupAlertReceiver::class.java).setAction(ACT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if (!anyOn(c)) { am.cancel(pi); return }
            val cal = Calendar.getInstance().apply {
                add(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 5); set(Calendar.MILLISECOND, 0)
            }
            // 정확 알람 권한 없이: 절전 중에도 깨워서 (몇 분 늦을 수 있음)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        } catch (_: Exception) {}
    }

    private fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_LIVE) == null)
            nm.createNotificationChannel(NotificationChannel(CH_LIVE, "그룹원 출조 중", NotificationManager.IMPORTANCE_LOW).apply {
                description = "그룹원이 출조 중일 때 상단에 조용히 표시" })
        if (nm.getNotificationChannel(CH_GROUP) == null)
            nm.createNotificationChannel(NotificationChannel(CH_GROUP, "그룹 채팅 · 공지 · 일정", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "1시간마다 확인해서 새 글이 있으면 알려 줘요" })
    }

    private fun clear(c: Context, kind: String) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (kind == "live") nm.cancel(ID_LIVE)
            else nm.activeNotifications.filter { it.notification.extras.getString("ga_kind") == kind }.forEach { nm.cancel(it.id) }
        } catch (_: Exception) {}
    }

    private fun openPi(c: Context, gid: String, kind: String, req: Int): PendingIntent =
        PendingIntent.getActivity(c, req, Intent(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("open", "ga:$gid:$kind"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** v6.47: 이번 확인에서 새로 띄운 소식 수 (출조 중 알림은 빼고) */
    @Volatile private var posted = 0
    private fun post(c: Context, id: Int, ch: String, kind: String, title: String, lines: List<String>, pi: PendingIntent, count: Int = 0) {
        if (kind != "live") posted += maxOf(1, count)
        val text = lines.joinToString("\n")
        val b = Notification.Builder(c, ch)
            .setSmallIcon(R.drawable.ic_stat_octo)
            .setContentTitle(title).setContentText(lines.firstOrNull() ?: "")
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi).setAutoCancel(kind != "live")
            .setShowWhen(true).setOnlyAlertOnce(kind == "live")
            .setExtras(android.os.Bundle().apply { putString("ga_kind", kind) })
        if (count > 1) b.setNumber(count)
        try { c.getSystemService(NotificationManager::class.java).notify(id, b.build()) } catch (_: SecurityException) {}
    }

    private fun <T> wait(t: com.google.android.gms.tasks.Task<T>): T? = try { Tasks.await(t, 12, TimeUnit.SECONDS) } catch (_: Exception) { null }

    /**
     * v6.48 (321): 그 방의 안 읽은 글 (chatSeen_방 보다 늦은 남의 글) → 개수 돌려줌
     *  알림: kind 스위치가 켜져 있고 · 앱을 안 보고 있고 · 이미 알린 글보다 새 글이 있을 때만 (같은 글로 계속 안 울림)
     *  안 읽은 글이 없으면 그 방 알림 지움
     */
    private fun chatUnread(c: Context, col: com.google.firebase.firestore.CollectionReference, room: String, title: String, kind: String,
                           me: String, quiet: Boolean): Int {
        val p = prefs(c)
        val seen = p.getLong("chatSeen_$room", 0L).takeIf { it > 0 } ?: return 0   // 한 번도 안 열어 본 방 = 앱도 0
        val l = wait(col.whereGreaterThan("t", seen).orderBy("t").limitToLast(100).get())?.documents
            ?.filter { it.getString("uid") != me && !Moderation.isBlocked(it.getString("uid") ?: "") }?.sortedBy { it.getLong("t") ?: 0L }
            ?: return 0
        val id = ("chat$room").hashCode()
        if (l.isEmpty()) { try { c.getSystemService(NotificationManager::class.java).cancel(id) } catch (_: Exception) {}; return 0 }
        val newest = l.last().getLong("t") ?: 0L
        val noted = p.getLong("ga_chat_noti_$room", 0L)
        if (on(c, kind) && !quiet && newest > noted) {
            post(c, id, CH_GROUP, kind, "$title · 안 읽은 글 ${l.size}개",
                l.takeLast(5).map { "${it.getString("name") ?: "?"}: ${(it.getString("text") ?: "").ifBlank { "📷 사진" }.take(60)}" },
                openPi(c, room, "chat", id), l.size)
            p.edit().putLong("ga_chat_noti_$room", newest).apply()
        }
        return l.size
    }

    /** v6.50 (325): 앱이 그리는 '새 소식' 표시와 같은 값을 받아 위젯용으로 저장 (스레드에서 · 최대 20초) */
    private fun widgetExtras(c: Context) {
        val latch = java.util.concurrent.CountDownLatch(3)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val go = {
                    GroupNews.load(true) {
                        try {
                            val gid = Cloud.active?.id
                            WidgetData.groupNews(c, GroupNews.total(), GroupNews.today(gid), GroupNews.tomorrow(gid))
                        } catch (_: Exception) {}
                        latch.countDown()
                    }
                }
                if (Cloud.groups.isEmpty()) Cloud.refresh { go() } else go()
            } catch (_: Exception) { latch.countDown() }
            try { Inquiry.unread { n -> WidgetData.setInt(c, "w_inq_n", n); latch.countDown() } } catch (_: Exception) { latch.countDown() }
            try {
                val t = WidgetData.tide(c)
                val la = t?.optDouble("lat") ?: Double.NaN; val lo = t?.optDouble("lon") ?: Double.NaN
                if (la.isNaN() || lo.isNaN()) latch.countDown()
                else Warn.forPlace(la, lo) { w -> if (w != null) WidgetData.setWarn(c, if (w.isBlank()) "" else "${Warn.seaArea(la, lo)} $w"); latch.countDown() }
            } catch (_: Exception) { latch.countDown() }
        }
        try { latch.await(20, TimeUnit.SECONDS) } catch (_: Exception) {}
    }

    /** v6.48 (321): 채팅방을 읽으면 → 그 방 알림 지우기 + 위젯 숫자 (폰에 있는 글 기준) */
    fun chatRead(c: Context, room: String) {
        try { c.getSystemService(NotificationManager::class.java).cancel(("chat$room").hashCode()) } catch (_: Exception) {}
        try { prefs(c).edit().putInt("w_chat_n", Chat.totalUnread()).apply(); QuickWidget.updateAll(c) } catch (_: Exception) {}
    }

    /** 1시간마다 (스레드에서) */
    /** v6.47: [force] = 퀵바 위젯 🔄 새로고침 (알림을 다 꺼 놨어도 위젯 '출조 중' 은 새로) · 돌려주는 값 = 새 소식 개수 */
    fun check(c: Context, force: Boolean = false): Int {
        if (!anyOn(c) && !force) return 0
        val me = FirebaseAuth.getInstance().currentUser?.uid ?: return 0
        var newN = 0
        var unreadN = 0
        val liveIds = HashSet<String>()
        val p = prefs(c)
        val db = FirebaseFirestore.getInstance()
        val now = System.currentTimeMillis()
        // 앱을 보고 있으면: 알림은 안 울리고 '여기까지 봤음'만
        val quiet = visible
        posted = 0
        channels(c)
        val user = wait(db.collection("users").document(me).get(Source.SERVER)) ?: return 0
        @Suppress("UNCHECKED_CAST")
        val gids = (user.get("groups") as? List<String>).orEmpty()
        val liveLines = mutableListOf<String>()
        var liveGid = ""
        val hm = SimpleDateFormat("HH:mm", Locale.KOREA)
        gids.forEach { gid ->
            val gref = db.collection("groups").document(gid)
            val gname = wait(gref.get())?.getString("name") ?: "그룹"
            val last = p.getLong("ga_last_$gid", 0L)
            p.edit().putLong("ga_last_$gid", now).apply()
            // 🟢 출조 중 (지금 상태라 처음이어도 보여 줌)
            // v6.47: 위젯 '출조중' 칸 숫자도 같이 (알림을 꺼 놨어도)
            wait(gref.collection("live").get())?.documents?.filter { d ->
                d.id != me && d.getBoolean("ended") != true && now - (d.getLong("t") ?: 0L) < 2 * 3600_000L
            }?.also { l -> l.forEach { liveIds.add(it.id) } }?.takeIf { on(c, "live") }?.forEach { d ->
                val mins = ((now - (d.getLong("start") ?: now)) / 60_000L).coerceAtLeast(0)
                val dur = if (mins >= 60) "${mins / 60}시간 ${mins % 60}분째" else "${mins}분째"
                liveLines.add("${d.getString("name") ?: "?"} · ${d.getString("line") ?: ""} ($dur)".replace(" ·  (", " ("))
                if (liveGid.isEmpty()) liveGid = gid
            }
            // v6.44 (312): 📅 내일 출조 — 내가 참석(대기 포함)을 누른 일정 전날 낮 12시부터 한 번 (앱을 보고 있어도)
            if (on(c, "remind") && Calendar.getInstance().get(Calendar.HOUR_OF_DAY) >= 12) {
                val tmr = SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(Date(now + 86_400_000L))
                wait(gref.collection("events").whereEqualTo("date", tmr).get())?.documents?.forEach { e ->
                    val key = "ga_rem_${gid}_${e.id}"
                    if (p.getBoolean(key, false)) return@forEach
                    val rs = wait(e.reference.collection("rsvp").get())?.documents ?: return@forEach
                    if (rs.firstOrNull { it.id == me }?.getString("s") != "yes") return@forEach
                    p.edit().putBoolean(key, true).apply()
                    val yes = rs.count { it.getString("s") == "yes" }; val maybe = rs.count { it.getString("s") == "maybe" }
                    val cap = (e.getLong("cap") ?: 0L).toInt()
                    val waitN = if (cap > 0) (yes - cap).coerceAtLeast(0) else 0
                    val lines = listOf(Cloud.eventTitle(e.getString("date") ?: "", e.getString("time") ?: "", e.getString("cat") ?: "",
                        e.getString("place") ?: "", e.getString("boat") ?: ""), (e.getString("memo") ?: "").take(60),
                        "🙋 참석 ${if (cap > 0) minOf(yes, cap) else yes} · 🤔 미정 $maybe" + (if (cap > 0) " · 대기 $waitN" else "")).filter { it.isNotBlank() }
                    val id = ("rem$gid${e.id}").hashCode()
                    val b = Notification.Builder(c, CH_GROUP).setSmallIcon(R.drawable.ic_stat_octo)
                        .setContentTitle("⏰ 내일 출조예요! · $gname").setContentText(lines.first())
                        .setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
                        .setContentIntent(openPi(c, gid, "event", id)).setAutoCancel(true)
                        .setExtras(android.os.Bundle().apply { putString("ga_kind", "remind") })
                        .addAction(Notification.Action.Builder(null, "📅 일정 보기", openPi(c, gid, "event", id + 1)).build())
                        .addAction(Notification.Action.Builder(null, "💬 그룹 채팅", openPi(c, gid, "chat", id + 2)).build())
                    try { c.getSystemService(NotificationManager::class.java).notify(id, b.build()) } catch (_: SecurityException) {}
                }
            }
            // v6.48 (321): 💬 그룹 채팅 = '아직 안 읽은 글'(앱 숫자와 같은 기준) · 위젯 숫자 + 알림
            unreadN += chatUnread(c, gref.collection("chat"), gid, "💬 $gname", "chat", me, quiet)
            if (last == 0L || quiet) return@forEach   // 처음이면 지난 글로 알림 폭탄 안 보내기
            // 📢 공지
            if (on(c, "notice")) wait(gref.collection("notices").whereGreaterThan("created", last).get())?.documents
                ?.filter { it.getString("owner") != me }?.let { l ->
                    if (l.isNotEmpty()) post(c, ("notice$gid").hashCode(), CH_GROUP, "notice", "📢 $gname · 새 공지 ${l.size}개",
                        l.map { (it.getString("title") ?: "") + (it.getString("body")?.takeIf { b -> b.isNotBlank() }?.let { b -> " — " + b.take(40) } ?: "") },
                        openPi(c, gid, "notice", ("notice$gid").hashCode()), l.size)
                }
            // ⏰ 일정
            if (on(c, "event")) wait(gref.collection("events").whereGreaterThan("created", last).get())?.documents
                ?.filter { it.getString("owner") != me }?.let { l ->
                    val df = SimpleDateFormat("yyyyMMdd", Locale.KOREA); val nf = SimpleDateFormat("M월 d일(E)", Locale.KOREA)
                    if (l.isNotEmpty()) post(c, ("event$gid").hashCode(), CH_GROUP, "event", "⏰ $gname · 새 일정 ${l.size}개",
                        l.map { e ->
                            val d = try { nf.format(df.parse(e.getString("date") ?: "")!!) } catch (_: Exception) { e.getString("date") ?: "" }
                            listOf(d + (e.getString("time")?.let { " $it" } ?: ""), e.getString("place") ?: "", e.getString("ownerName")?.let { "올린 사람 $it" } ?: "")
                                .filter { it.isNotBlank() }.joinToString(" · ")
                        }, openPi(c, gid, "event", ("event$gid").hashCode()), l.size)
                }
            // 🙋 내가 올린 일정에 참석 답
            if (on(c, "rsvp")) {
                val today = SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(Date())
                val mine = wait(gref.collection("events").whereEqualTo("owner", me).get())?.documents
                    ?.filter { (it.getString("date") ?: "") >= today }?.take(5).orEmpty()
                val lines = mutableListOf<String>()
                mine.forEach { e ->
                    wait(e.reference.collection("rsvp").whereGreaterThan("t", last).get())?.documents?.filter { it.id != me }?.forEach { r ->
                        val s = when (r.getString("s")) { "yes" -> "🙋 참석"; "maybe" -> "🤔 미정"; else -> "🙅 불참" }
                        lines.add("${r.getString("name") ?: "?"} $s · ${e.getString("date")?.drop(4)?.let { "${it.take(2).toInt()}/${it.drop(2).toInt()}" } ?: ""} 일정" +
                            (r.getString("note")?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""))
                    }
                }
                if (lines.isNotEmpty()) post(c, ("rsvp$gid").hashCode(), CH_GROUP, "rsvp", "🙋 $gname · 참석 답 ${lines.size}개",
                    lines, openPi(c, gid, "rsvp", ("rsvp$gid").hashCode()), lines.size)
            }
            // v6.51 (327): 💬 출조 댓글 — 내 출조 · 내가 댓글 단 출조에 남이 단 새 댓글
            if (on(c, "cmt")) {
                val lines = mutableListOf<String>()
                wait(gref.collection("cmtMeta").whereGreaterThan("at", last).get())?.documents
                    ?.filter { it.getString("by") != me && (it.id.substringBefore("_") == me || p.getBoolean("cmtMine_${gid}_${it.id}", false)) }
                    ?.take(5)?.forEach { m ->
                        val mineTrip = m.id.substringBefore("_") == me
                        wait(gref.collection("trips").document(m.id).collection("comments").whereGreaterThan("t", maxOf(last, p.getLong("cmtSeen_${gid}_${m.id}", 0L))).get())
                            ?.documents?.filter { it.getString("uid") != me && !Moderation.isBlocked(it.getString("uid") ?: "") }
                            ?.sortedBy { it.getLong("t") ?: 0L }?.forEach { d ->
                                lines.add((if (mineTrip) "내 출조" else "댓글 단 출조") + " · ${d.getString("name") ?: "?"}: " + (d.getString("text") ?: "").take(60))
                            }
                    }
                if (lines.isNotEmpty()) post(c, ("cmt$gid").hashCode(), CH_GROUP, "cmt", "💬 $gname · 출조 댓글 ${lines.size}개",
                    lines.takeLast(5), openPi(c, gid, "cmt", ("cmt$gid").hashCode()), lines.size)
            }
        }
        // v6.48 (321): 🌐 전체 채팅 (숫자는 늘 · 알림은 스위치 켰을 때만)
        unreadN += chatUnread(c, db.collection("chat"), Chat.ALL, "🌐 전체 채팅", "allchat", me, quiet)
        p.edit().putInt("w_chat_n", unreadN).apply()
        // 🟢 출조 중: 있으면 조용히 (같은 알림을 고쳐 씀) · 없으면 지움
        val nm = c.getSystemService(NotificationManager::class.java)
        if (liveLines.isEmpty()) nm.cancel(ID_LIVE)
        else post(c, ID_LIVE, CH_LIVE, "live", "🟢 지금 출조 중 ${liveLines.size}명 (${hm.format(Date(now))} 확인)", liveLines,
            openPi(c, liveGid, "live", ID_LIVE), liveLines.size)
        // v6.50 (325): 위젯에 그룹 새 소식 · 오늘/내일 일정 · 1:1 문의 답장 · 기상 특보도 같이 새로
        try { widgetExtras(c) } catch (_: Exception) {}
        // v6.47: 퀵바 위젯 '출조중 N명' · 마지막 확인 시각
        p.edit().putLong("w_check_at", now).apply()
        QuickWidget.saveLive(c, liveIds.size)
        QuickWidget.updateAll(c)
        newN = posted
        return newN
    }
}

/** 정각 알람 · 폰 켤 때 · 앱 업데이트 뒤 */
class GroupAlertReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val app = c.applicationContext
        if (i.action != "com.fishlog.app.GROUP_ALERT") { GroupAlert.schedule(app); return }
        val pr = goAsync()
        Thread {
            try { GroupAlert.check(app) } catch (_: Exception) {}
            try { QuickWidget.updateAll(app) } catch (_: Exception) {}
            GroupAlert.schedule(app)
            pr.finish()
        }.start()
    }
}
