package com.fishlog.app

import android.location.Location
import android.os.Handler
import android.os.Looper
import com.google.firebase.firestore.FirebaseFirestore

/**
 * 같이 낚시하는 그룹원 조과
 *  - 앱 화면을 보고 있을 땐 실시간 (서버 변화를 지켜봄), 화면이 꺼져 있으면 5분마다
 *  - 내 +1 은 10초 안에 바로 올림 (친구 폰에서 실시간으로 보이게)
 *  - 거리 기준은 ⚙ 설정 (50m · 200m · 1km · 거리 상관없이)
 *  - 출조 중이면 내 위치·마릿수를 가입한 그룹마다 groups/{gid}/live/{uid} 에 올림
 *  - 같은 그룹 사람 중 출조 중이고 내 위치에서 50m 안에 있는 사람만 보여줌
 *  - 출조를 끝내면 내 정보는 바로 지움
 */
object Live {

    /** 거리 기준 (m). null = 거리 상관없이 같은 그룹 출조 중이면 모두 */
    fun range(): Float? = Store.mateRange.takeIf { it > 0 }?.toFloat()
    fun rangeLabel(): String = Store.mateRange.let { if (it <= 0) "거리 상관없이" else if (it >= 1000) "${it / 1000}km 안" else "${it}m 안" }
    /** 안 보일 때 이유 (보이면 "") */
    @Volatile var reason: String = ""
        private set
    const val EVERY_MS = 5 * 60_000L
    private const val STALE_MS = 20 * 60_000L

    class Mate(val uid: String, val name: String, val names: List<String>, val c1: Int, val c2: Int,
               val time: Long, val meters: Float, val lat: Double? = null, val lon: Double? = null,
               val ended: Boolean = false)

    @Volatile var mates: List<Mate> = emptyList()
        private set
    /** 같이 출조하다 종료한 사람 (종료 후 1시간만) */
    @Volatile var endedMates: List<Mate> = emptyList()
        private set
    private const val ENDED_SHOW_MS = 60 * 60_000L
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
            "names" to t.names.toList(), "c1" to t.count(1), "c2" to t.count(2), "ended" to false)
        if (loc != null) { doc["lat"] = loc.first; doc["lon"] = loc.second }
        groups.forEach { g -> db.collection("groups").document(g.id).collection("live").document(me).set(doc, com.google.firebase.firestore.SetOptions.merge()) }
        if (watching.isNotEmpty()) return  // 화면을 보고 있으면 실시간으로 이미 받는 중
        val gotDocs = HashMap<String, List<com.google.firebase.firestore.DocumentSnapshot>>()
        var left = groups.size
        groups.forEach { g ->
            db.collection("groups").document(g.id).collection("live").get()
                .addOnSuccessListener { q -> gotDocs[g.id] = q.documents }
                .addOnCompleteListener {
                    left--
                    if (left == 0) { liveDocs.clear(); liveDocs.putAll(gotDocs); recompute() }
                }
        }
    }

    // ---------------- 실시간 ----------------
    private val liveDocs = HashMap<String, List<com.google.firebase.firestore.DocumentSnapshot>>()
    private var watching: List<com.google.firebase.firestore.ListenerRegistration> = emptyList()
    private var watchKey = ""

    /** 앱 화면을 보고 있을 때: 그룹마다 같이 낚시 중 목록을 지켜봄 */
    fun watch() {
        if (Store.current == null || Cloud.uid == null) { unwatch(); return }
        val key = Cloud.groups.joinToString(",") { it.id }
        if (key == watchKey && watching.isNotEmpty()) return
        unwatch()
        watchKey = key
        watching = Cloud.groups.map { g ->
            db.collection("groups").document(g.id).collection("live").addSnapshotListener { q, _ ->
                if (q != null) { liveDocs[g.id] = q.documents; recompute() }
            }
        }
        sync(true)
    }

    fun unwatch() {
        watching.forEach { try { it.remove() } catch (_: Exception) {} }
        watching = emptyList(); watchKey = ""
    }

    /** 받은 목록 → 거리·시간 조건으로 같이 낚시 중인 사람 */
    private fun recompute() {
        val me = Cloud.uid ?: return
        val now = System.currentTimeMillis()
        val loc = myLocation()
        val rng = range()
        val found = HashMap<String, Mate>()
        val endedFound = HashMap<String, Mate>()
        var others = 0
        liveDocs.values.flatten().forEach { d ->
            if (d.id == me) return@forEach
            val tt = d.getLong("t") ?: 0L
            val isEnded = d.getBoolean("ended") == true
            if (isEnded) { if (now - (d.getLong("endedAt") ?: tt) > ENDED_SHOW_MS) return@forEach }
            else { if (now - tt > STALE_MS) return@forEach; others++ }
            val la = d.getDouble("lat"); val lo = d.getDouble("lon")
            var dist = -1f
            if (la != null && lo != null && loc != null) {
                val r = FloatArray(1)
                Location.distanceBetween(loc.first, loc.second, la, lo, r)
                dist = r[0]
            }
            if (rng != null && (dist < 0 || dist > rng)) return@forEach
            val m = Mate(d.id, Cloud.shownName(d.id, d.getString("name") ?: "?"),
                (d.get("names") as? List<*>)?.map { it.toString() } ?: listOf("1번", "2번"),
                (d.getLong("c1") ?: 0L).toInt(), (d.getLong("c2") ?: 0L).toInt(), if (isEnded) (d.getLong("endedAt") ?: tt) else tt,
                dist.coerceAtLeast(0f), la, lo, isEnded)
            val bucket = if (isEnded) endedFound else found
            val old = bucket[d.id]
            if (old == null || old.time < m.time) bucket[d.id] = m
        }
        mates = found.values.sortedBy { it.meters }
        endedMates = endedFound.values.filter { it.uid !in found.keys }.sortedByDescending { it.time }
        reason = when {
            mates.isNotEmpty() -> ""
            rng != null && loc == null -> "내 위치가 아직 없어요 (GPS·위치 권한 확인)"
            others == 0 -> "지금 같이 출조 중인 그룹원이 없어요"
            else -> "${rangeLabel()}에 같이 출조 중인 그룹원이 없어요 (⚙ 설정에서 거리 바꾸기)"
        }
        checkCheers(mates)
        fire()
    }

    // ---------------- 내 +1 바로 올리기 (3초 안에, 연달아 눌러도 3초마다 꼭) ----------------
    @Volatile private var pushPending = false
    private val pushRunnable = Runnable { pushPending = false; pushCounts() }
    fun pushSoon() {
        if (Cloud.uid == null || Store.current == null) return
        if (pushPending) return          // 이미 3초 안에 올리기로 잡혀 있음 (그때 최신 마릿수로 올림)
        pushPending = true
        main.postDelayed(pushRunnable, 3_000L)
    }

    private fun pushCounts() {
        val me = Cloud.uid ?: return
        val t = Store.current ?: return
        val doc = hashMapOf<String, Any>(
            "name" to Cloud.displayName, "t" to System.currentTimeMillis(), "start" to t.start, "cat" to t.cat,
            "names" to t.names.toList(), "c1" to t.count(1), "c2" to t.count(2), "ended" to false)
        // +1 할 때 위치도 같이 (없으면 지난 위치 그대로)
        myLocation()?.let { doc["lat"] = it.first; doc["lon"] = it.second }
        Cloud.groups.forEach { g ->
            db.collection("groups").document(g.id).collection("live").document(me)
                .set(doc, com.google.firebase.firestore.SetOptions.merge())
        }
    }

    /** 출조 종료: 내 정보 지우기 */
    fun clear(last: Store.Trip? = null) {
        val me = Cloud.uid ?: return
        // 지우지 않고 '종료' 표시 + 최종 마릿수 → 같이 출조하던 사람에게 1시간 동안 "출조를 종료했어요"
        val now = System.currentTimeMillis()
        val doc = hashMapOf<String, Any>("ended" to true, "endedAt" to now, "t" to now, "name" to Cloud.displayName)
        last?.let { doc["names"] = it.names.toList(); doc["c1"] = it.count(1); doc["c2"] = it.count(2) }
        Cloud.groups.forEach { g ->
            db.collection("groups").document(g.id).collection("live").document(me)
                .set(doc, com.google.firebase.firestore.SetOptions.merge())
        }
        mates = emptyList(); endedMates = emptyList(); updatedAt = 0L; prevCounts.clear(); cheer = null; reason = ""
        unwatch(); liveDocs.clear(); main.removeCallbacks(pushRunnable); pushPending = false
        fire()
    }
}
