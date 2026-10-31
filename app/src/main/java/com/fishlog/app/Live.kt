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
                nm.createNotificationChannel(android.app.NotificationChannel("friendCheer", "같이 출조 중 친구 돌파 알림",
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

    /** 체크한 그룹 id (한 번도 안 골랐으면 지금 그룹, '*' = 모두) */
    fun checkedGroupIds(): Set<String> {
        val v = Store.liveGroups ?: return setOfNotNull(sharedGid ?: Cloud.active?.id)
        return if ("*" in v) Cloud.groups.map { it.id }.toSet() else v
    }
    /** 내 출조를 알릴 그룹 (출조 시작 창 '내 출조 알리기') — 전체 공유면 내 그룹 모두에도 */
    fun shareGroups(): List<Cloud.Group> = when (Store.liveScope) {
        "none" -> emptyList()
        "public" -> Cloud.groups
        else -> checkedGroupIds().let { ids -> Cloud.groups.filter { it.id in ids } }
    }
    fun sharePublic() = Store.liveScope == "public"

    // ---------------- 전체 사용자에게 공유 (publicLive/{uid}) ----------------
    /** 전체 게시판 맨 위 '지금 출조 중' 한 사람 (정확한 위치는 안 올림, 동네 이름만) */
    class PubNow(val uid: String, val name: String, val cat: String, val start: Long, val line: String, val area: String)
    private var pubWrote = false
    private var pubCleaned = false   // 앱을 다시 켠 뒤 한 번은 지워 둠 (예전에 전체 공유하다 바꾼 경우)
    private fun writePublic(t: Store.Trip, now: Long) {
        val me = Cloud.uid ?: return
        if (!sharePublic()) {
            if (pubWrote || !pubCleaned) { db.collection("publicLive").document(me).delete(); pubWrote = false; pubCleaned = true }
            return
        }
        val doc = hashMapOf<String, Any>("owner" to me, "name" to PublicBoard.nickShown(), "t" to now, "start" to t.start,
            "cat" to t.cat, "line" to t.line())
        myLocation()?.let { (la, lo) -> Area.name(Cloud.appContext, la, lo).takeIf { it.isNotBlank() }?.let { doc["area"] = it } }
        pubWrote = true
        db.collection("publicLive").document(me).set(doc, com.google.firebase.firestore.SetOptions.merge())
    }
    private var pubCache: Pair<Long, List<PubNow>>? = null
    fun publicCached(): List<PubNow> = pubCache?.second ?: emptyList()
    /** 전체 사용자에게 공유 중인 사람들 (1분 기억) */
    fun publicNow(done: (List<PubNow>) -> Unit) {
        if (Cloud.uid == null) return done(emptyList())
        pubCache?.let { (at, l) -> if (System.currentTimeMillis() - at < 60_000L) return done(l) }
        db.collection("publicLive").get().addOnSuccessListener { q ->
            val now = System.currentTimeMillis()
            val l = q.documents.filter { d -> now - (d.getLong("t") ?: 0L) < STALE_MS && !Moderation.isBlocked(d.id) }
                .map { d -> PubNow(d.id, if (d.id == Cloud.uid) "나" else d.getString("name") ?: "익명", d.getString("cat") ?: "",
                    d.getLong("start") ?: 0L, d.getString("line") ?: "", d.getString("area") ?: "") }
                .sortedBy { it.start }
            pubCache = now to l
            done(l)
        }.addOnFailureListener { done(publicCached()) }
    }
    /** 출조를 시작할 때의 그룹 (지금 그룹만 알릴 때) */
    @Volatile var sharedGid: String? = null
    /** 이번 출조에서 내 정보를 올린 그룹들 (끝날 때 '종료' 표시·범위 바꿀 때 지우기용) */
    private val wroteTo = HashSet<String>()

    /** 알리기 범위를 바꿨을 때: 빠진 그룹에서는 내 정보 지우고 다시 올리기 */
    fun scopeChanged() {
        val me = Cloud.uid ?: return
        val keep = shareGroups().map { it.id }.toSet()
        wroteTo.filter { it !in keep }.forEach { gid -> db.collection("groups").document(gid).collection("live").document(me).delete() }
        wroteTo.retainAll(keep)
        Store.current?.let { writePublic(it, System.currentTimeMillis()) }
        pubCache = null
        sync(true)
    }

    /** 위치 공유를 켰을 때만 위치를 올림 (끄면 서버에 있던 위치도 지움) */
    private fun putLoc(doc: HashMap<String, Any>, loc: Pair<Double, Double>?) {
        if (!Store.liveLoc) { doc["lat"] = com.google.firebase.firestore.FieldValue.delete(); doc["lon"] = com.google.firebase.firestore.FieldValue.delete(); return }
        if (loc != null) { doc["lat"] = loc.first; doc["lon"] = loc.second }
    }

    private fun liveDoc(t: Store.Trip, now: Long): HashMap<String, Any> = hashMapOf(
        "name" to Cloud.displayName, "t" to now, "start" to t.start, "cat" to t.cat,
        "names" to t.names.toList(), "c1" to t.count(1), "c2" to t.count(2), "ended" to false, "line" to t.line())

    /** 올리고 받아오기. force=false 면 5분이 안 지났을 땐 건너뜀 */
    fun sync(force: Boolean = false) {
        val me = Cloud.uid ?: return
        if (Cloud.isInitialized() && !Consent.shareLive(Cloud.appContext)) return
        val t = Store.current ?: return
        val groups = Cloud.groups
        if (groups.isEmpty() && !sharePublic()) return
        val now = System.currentTimeMillis()
        if (!force && now - updatedAt < EVERY_MS - 10_000L) return
        updatedAt = now
        val loc = myLocation()
        val doc = liveDoc(t, now)
        putLoc(doc, loc)
        if (sharedGid == null) sharedGid = Cloud.active?.id
        shareGroups().forEach { g -> wroteTo.add(g.id); db.collection("groups").document(g.id).collection("live").document(me).set(doc, com.google.firebase.firestore.SetOptions.merge()) }
        writePublic(t, now)
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

    // ---------------- 그룹에서 지금 출조 중인 사람 (출조 안 해도 보임) ----------------
    class Now(val uid: String, val name: String, val cat: String, val start: Long, val line: String,
              val lat: Double? = null, val lon: Double? = null, val at: Long = 0L)
    private val nowCache = HashMap<String, Pair<Long, List<Now>>>()
    fun nowCached(gid: String): List<Now> = nowCache[gid]?.second ?: emptyList()
    fun nowFishing(gid: String, done: (List<Now>) -> Unit) {
        val me = Cloud.uid ?: return done(emptyList())
        nowCache[gid]?.let { (at, l) -> if (System.currentTimeMillis() - at < 2 * 60_000L) return done(l) }
        db.collection("groups").document(gid).collection("live").get().addOnSuccessListener { q ->
            val now = System.currentTimeMillis()
            val l = q.documents.filter { d -> d.id != me && d.getBoolean("ended") != true && now - (d.getLong("t") ?: 0L) < STALE_MS }
                .map { d ->
                    val names = (d.get("names") as? List<*>)?.map { it.toString() } ?: listOf("1번", "2번")
                    val line = d.getString("line") ?: listOf(names.getOrElse(0) { "1번" } to (d.getLong("c1") ?: 0L), names.getOrElse(1) { "2번" } to (d.getLong("c2") ?: 0L))
                        .joinToString(" · ") { "${it.first} ${it.second}" }
                    Now(d.id, Cloud.shownName(d.id, d.getString("name") ?: "?"), d.getString("cat") ?: "", d.getLong("start") ?: 0L, line,
                        d.getDouble("lat"), d.getDouble("lon"), d.getLong("t") ?: 0L)
                }.sortedBy { it.start }
            nowCache[gid] = now to l
            done(l)
        }.addOnFailureListener { done(nowCached(gid)) }
    }

    /** 한 사람 (여러 그룹에 같이 있으면 하나로) · groups = 어느 그룹에서 보이는지 · pub = 전체 공유 */
    class AnyNow(val uid: String, val name: String, val cat: String, val start: Long, val line: String,
                 val lat: Double?, val lon: Double?, val at: Long, val groups: List<String>, val pub: Boolean, val area: String)
    private var allCache: List<AnyNow> = emptyList()
    fun allCached(): List<AnyNow> = allCache
    /** 내 그룹 모두 + 전체 공유에서 지금 출조 중인 사람 (나는 빼고) */
    fun nowAll(done: (List<AnyNow>) -> Unit) {
        val me = Cloud.uid ?: return done(emptyList())
        val gs = Cloud.groups
        val got = HashMap<String, List<Now>>()
        var left = gs.size + 1
        var pubL: List<PubNow> = emptyList()
        fun finish() {
            val out = LinkedHashMap<String, AnyNow>()
            gs.forEach { g ->
                got[g.id].orEmpty().forEach { n ->
                    val o = out[n.uid]
                    out[n.uid] = if (o == null) AnyNow(n.uid, n.name, n.cat, n.start, n.line, n.lat, n.lon, n.at, listOf(g.title), false, "")
                    else AnyNow(o.uid, o.name, o.cat, o.start, if (n.at > o.at) n.line else o.line,
                        o.lat ?: n.lat, o.lon ?: n.lon, maxOf(o.at, n.at), o.groups + g.title, false, "")
                }
            }
            pubL.filter { it.uid != me }.forEach { p ->
                val o = out[p.uid]
                out[p.uid] = if (o == null) AnyNow(p.uid, p.name, p.cat, p.start, p.line, null, null, 0L, emptyList(), true, p.area)
                else AnyNow(o.uid, o.name, o.cat, o.start, o.line, o.lat, o.lon, o.at, o.groups, true, p.area)
            }
            allCache = out.values.sortedBy { it.start }
            done(allCache)
        }
        fun step() { left--; if (left == 0) finish() }
        gs.forEach { g -> nowFishing(g.id) { l -> got[g.id] = l; step() } }
        publicNow { l -> pubL = l; step() }
    }

    // ---------------- 실시간 ----------------
    private val liveDocs = HashMap<String, List<com.google.firebase.firestore.DocumentSnapshot>>()
    private var watching: List<com.google.firebase.firestore.ListenerRegistration> = emptyList()
    private var watchKey = ""

    /** 앱 화면을 보고 있을 때: 그룹마다 같이 출조 중 목록을 지켜봄 */
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

    /** 받은 목록 → 거리·시간 조건으로 같이 출조 중인 사람 */
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
        if (Cloud.isInitialized() && !Consent.shareLive(Cloud.appContext)) return
        val t = Store.current ?: return
        val doc = liveDoc(t, System.currentTimeMillis())
        // +1 할 때 위치도 같이 (없으면 지난 위치 그대로)
        putLoc(doc, myLocation())
        shareGroups().forEach { g ->
            wroteTo.add(g.id)
            db.collection("groups").document(g.id).collection("live").document(me)
                .set(doc, com.google.firebase.firestore.SetOptions.merge())
        }
        writePublic(t, System.currentTimeMillis())
    }

    /** 출조 종료: 내 정보 지우기 */
    fun clear(last: Store.Trip? = null) {
        val me = Cloud.uid ?: return
        // 지우지 않고 '종료' 표시 + 최종 마릿수 → 같이 출조하던 사람에게 1시간 동안 "출조를 종료했어요"
        val now = System.currentTimeMillis()
        val doc = hashMapOf<String, Any>("ended" to true, "endedAt" to now, "t" to now, "name" to Cloud.displayName)
        last?.let { doc["names"] = it.names.toList(); doc["c1"] = it.count(1); doc["c2"] = it.count(2); doc["line"] = it.line() }
        // 이번 출조를 알린 그룹에만 '종료' 표시
        (if (wroteTo.isEmpty()) shareGroups() else Cloud.groups.filter { it.id in wroteTo }).forEach { g ->
            db.collection("groups").document(g.id).collection("live").document(me)
                .set(doc, com.google.firebase.firestore.SetOptions.merge())
        }
        mates = emptyList(); endedMates = emptyList(); updatedAt = 0L; prevCounts.clear(); cheer = null; reason = ""
        unwatch(); liveDocs.clear(); main.removeCallbacks(pushRunnable); pushPending = false
        wroteTo.clear(); sharedGid = null
        // 전체 공유는 끝나면 바로 지움
        if (pubWrote || Store.liveScope == "public") { db.collection("publicLive").document(me).delete(); pubWrote = false }
        pubCache = null
        fire()
    }
}
