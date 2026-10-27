package com.fishlog.app

import android.location.Location
import android.os.Handler
import android.os.Looper
import com.google.firebase.firestore.FirebaseFirestore

/**
 * 같이 낚시하는 그룹원 조과 (5분마다)
 *  - 출조 중이면 내 위치·마릿수를 가입한 그룹마다 groups/{gid}/live/{uid} 에 올림
 *  - 같은 그룹 사람 중 출조 중이고 내 위치에서 50m 안에 있는 사람만 보여줌
 *  - 출조를 끝내면 내 정보는 바로 지움
 */
object Live {

    const val RANGE_M = 50f
    const val EVERY_MS = 5 * 60_000L
    private const val STALE_MS = 20 * 60_000L

    class Mate(val uid: String, val name: String, val names: List<String>, val c1: Int, val c2: Int,
               val time: Long, val meters: Float)

    @Volatile var mates: List<Mate> = emptyList()
        private set
    @Volatile var updatedAt = 0L
        private set

    /** 친구 돌파 축하 띠: 1분 동안 */
    class Cheer(val text: String, val time: Long)
    @Volatile var cheer: Cheer? = null
        private set
    private val prevCounts = HashMap<String, Pair<Int, Int>>()

    /** 직전 갱신 때보다 늘어난 사이에 넘은 가장 큰 단계 */
    private fun crossed(old: Int, now: Int): Int? = Milestone.STEPS.filter { it in (old + 1)..now }.maxOrNull()

    private fun checkCheers(list: List<Mate>) {
        list.forEach { m ->
            val prev = prevCounts[m.uid]
            if (prev != null) {
                listOf(Triple(prev.first, m.c1, m.names.getOrElse(0) { "1번" }), Triple(prev.second, m.c2, m.names.getOrElse(1) { "2번" }))
                    .forEach { (o, n, sp) ->
                        crossed(o, n)?.let { step ->
                            val text = "${m.name}님 $sp ${step}마리 돌파! " + Milestone.titleWithCrown(Milestone.STEPS.indexOf(step))
                            cheer = Cheer(text, System.currentTimeMillis())
                            notifyCheer(text, m)
                        }
                    }
            }
            prevCounts[m.uid] = m.c1 to m.c2
        }
    }

    private fun notifyCheer(text: String, m: Mate) {
        if (!Store.friendAlerts || !Cloud.isInitialized()) return
        val c = Cloud.appContext
        try {
            val nm = c.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (nm.getNotificationChannel("friendCheer") == null) {
                nm.createNotificationChannel(android.app.NotificationChannel("friendCheer", "같이 낚시 중 친구 돌파 알림",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT))
            }
            val open = android.app.PendingIntent.getActivity(c, 11, android.content.Intent(c, MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            val n = android.app.Notification.Builder(c, "friendCheer")
                .setSmallIcon(R.drawable.ic_stat_octo)
                .setColor(Ui.ORANGE)
                .setContentTitle("🎉 $text")
                .setContentText("${m.name} " + m.names.getOrElse(0) { "1번" } + " ${m.c1} · " + m.names.getOrElse(1) { "2번" } + " ${m.c2}")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setVisibility(if (Store.lockScreenShow) android.app.Notification.VISIBILITY_PUBLIC else android.app.Notification.VISIBILITY_PRIVATE)
                .build()
            nm.notify(20 + (m.uid.hashCode() and 0xff), n)
        } catch (_: Exception) {}
    }

    private val db get() = FirebaseFirestore.getInstance()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun fire() = main.post { listeners.toList().forEach { it() } }

    private fun myLocation(): Pair<Double, Double>? {
        Tracker.last?.let { return it.latitude to it.longitude }
        val t = Store.current ?: return null
        t.track.lastOrNull()?.let { return it.lat to it.lon }
        return t.anyLocation()
    }

    /** 올리고 받아오기. force=false 면 5분이 안 지났을 땐 건너뜀 */
    fun sync(force: Boolean = false) {
        val me = Cloud.uid ?: return
        val t = Store.current ?: return
        val groups = Cloud.groups
        if (groups.isEmpty()) return
        val now = System.currentTimeMillis()
        if (!force && now - updatedAt < EVERY_MS - 10_000L) return
        updatedAt = now
        val loc = myLocation()
        val doc = hashMapOf<String, Any>(
            "name" to Cloud.displayName, "t" to now, "start" to t.start, "cat" to t.cat,
            "names" to t.names.toList(), "c1" to t.count(1), "c2" to t.count(2))
        if (loc != null) { doc["lat"] = loc.first; doc["lon"] = loc.second }
        groups.forEach { g -> db.collection("groups").document(g.id).collection("live").document(me).set(doc) }
        if (loc == null) { mates = emptyList(); fire(); return }

        val found = HashMap<String, Mate>()
        var left = groups.size
        groups.forEach { g ->
            db.collection("groups").document(g.id).collection("live").get()
                .addOnSuccessListener { q ->
                    q.documents.forEach { d ->
                        if (d.id == me) return@forEach
                        val la = d.getDouble("lat") ?: return@forEach
                        val lo = d.getDouble("lon") ?: return@forEach
                        val tt = d.getLong("t") ?: 0L
                        if (now - tt > STALE_MS) return@forEach
                        val r = FloatArray(1)
                        Location.distanceBetween(loc.first, loc.second, la, lo, r)
                        if (r[0] > RANGE_M) return@forEach
                        val m = Mate(d.id, d.getString("name") ?: "?",
                            (d.get("names") as? List<*>)?.map { it.toString() } ?: listOf("1번", "2번"),
                            (d.getLong("c1") ?: 0L).toInt(), (d.getLong("c2") ?: 0L).toInt(), tt, r[0])
                        val old = found[d.id]
                        if (old == null || old.time < m.time) found[d.id] = m
                    }
                }
                .addOnCompleteListener {
                    left--
                    if (left == 0) {
                        mates = found.values.sortedBy { it.meters }
                        checkCheers(mates)
                        fire()
                    }
                }
        }
    }

    /** 출조 종료: 내 정보 지우기 */
    fun clear() {
        val me = Cloud.uid ?: return
        Cloud.groups.forEach { g -> db.collection("groups").document(g.id).collection("live").document(me).delete() }
        mates = emptyList(); updatedAt = 0L; prevCounts.clear(); cheer = null
        fire()
    }
}
