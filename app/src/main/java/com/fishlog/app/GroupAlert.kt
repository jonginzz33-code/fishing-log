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
        "event" to "⏰ 새 일정", "rsvp" to "🙋 내 일정 참석 답")

    private fun prefs(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)
    fun on(c: Context, kind: String) = prefs(c).getBoolean("ga_$kind", true)
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

    private fun post(c: Context, id: Int, ch: String, kind: String, title: String, lines: List<String>, pi: PendingIntent, count: Int = 0) {
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

    /** 1시간마다 (스레드에서) */
    fun check(c: Context) {
        if (!anyOn(c)) return
        val me = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val p = prefs(c)
        val db = FirebaseFirestore.getInstance()
        val now = System.currentTimeMillis()
        // 앱을 보고 있으면: 알림은 안 울리고 '여기까지 봤음'만
        val quiet = visible
        channels(c)
        val user = wait(db.collection("users").document(me).get(Source.SERVER)) ?: return
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
            if (on(c, "live")) wait(gref.collection("live").get())?.documents?.filter { d ->
                d.id != me && d.getBoolean("ended") != true && now - (d.getLong("t") ?: 0L) < 2 * 3600_000L
            }?.forEach { d ->
                val mins = ((now - (d.getLong("start") ?: now)) / 60_000L).coerceAtLeast(0)
                val dur = if (mins >= 60) "${mins / 60}시간 ${mins % 60}분째" else "${mins}분째"
                liveLines.add("${d.getString("name") ?: "?"} · ${d.getString("line") ?: ""} ($dur)".replace(" ·  (", " ("))
                if (liveGid.isEmpty()) liveGid = gid
            }
            if (last == 0L || quiet) return@forEach   // 처음이면 지난 글로 알림 폭탄 안 보내기
            // 💬 채팅
            if (on(c, "chat")) wait(gref.collection("chat").whereGreaterThan("t", last).get())?.documents
                ?.filter { it.getString("uid") != me }?.sortedBy { it.getLong("t") ?: 0L }?.let { l ->
                    if (l.isNotEmpty()) post(c, ("chat$gid").hashCode(), CH_GROUP, "chat", "💬 $gname · 새 글 ${l.size}개",
                        l.takeLast(5).map { "${it.getString("name") ?: "?"}: ${(it.getString("text") ?: "").ifBlank { "📷 사진" }.take(60)}" },
                        openPi(c, gid, "chat", ("chat$gid").hashCode()), l.size)
                }
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
        }
        // 🟢 출조 중: 있으면 조용히 (같은 알림을 고쳐 씀) · 없으면 지움
        val nm = c.getSystemService(NotificationManager::class.java)
        if (liveLines.isEmpty()) nm.cancel(ID_LIVE)
        else post(c, ID_LIVE, CH_LIVE, "live", "🟢 지금 출조 중 ${liveLines.size}명 (${hm.format(Date(now))} 확인)", liveLines,
            openPi(c, liveGid, "live", ID_LIVE), liveLines.size)
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
            GroupAlert.schedule(app)
            pr.finish()
        }.start()
    }
}
