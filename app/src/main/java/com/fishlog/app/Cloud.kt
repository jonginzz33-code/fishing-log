package com.fishlog.app

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions

/**
 * Firebase 연동
 *  users/{uid}              : 이름, 가입한 그룹 목록, 선택한 그룹
 *  invites/{코드}           : 초대 코드 → 그룹 id
 *  groups/{gid}             : 그룹 이름, 방장, 멤버 목록
 *  groups/{gid}/trips/{id}  : 각자 올린 출조 기록
 */
object Cloud {

    class Group(val id: String, val name: String, val code: String, val owner: String)

    class RemoteCatch(val time: Long, val slot: Int, val lat: Double?, val lon: Double?)
    class RemoteTrip(
        val id: String, val owner: String, val ownerName: String,
        val start: Long, val end: Long, val names: List<String>,
        val catches: List<RemoteCatch>, val track: List<Store.Pt>,
        val boat: String = "", val port: String = ""
    ) {
        fun count(slot: Int) = catches.count { it.slot == slot }
        fun name(slot: Int) = names.getOrElse(slot - 1) { "${slot}번" }
        fun place(): String = listOf(boat, port).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()

    val user: FirebaseUser? get() = auth.currentUser
    val uid: String? get() = auth.currentUser?.uid
    val displayName: String get() = auth.currentUser?.displayName?.takeIf { it.isNotBlank() } ?: "나"

    var groups: List<Group> = emptyList()
        private set
    var activeId: String? = null
        private set
    val active: Group? get() = groups.firstOrNull { it.id == activeId } ?: groups.firstOrNull()

    private val listeners = mutableSetOf<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun fireChanged() = listeners.toList().forEach { it() }

    fun signOut(ctx: Context) {
        auth.signOut()
        groups = emptyList()
        activeId = null
        fireChanged()
    }

    /** 내 그룹 목록 불러오기 */
    fun refresh(done: ((String?) -> Unit)? = null) {
        val id = uid ?: run { done?.invoke("로그인이 필요해요"); return }
        db.collection("users").document(id).get()
            .addOnSuccessListener { doc ->
                @Suppress("UNCHECKED_CAST")
                val ids = (doc.get("groups") as? List<String>) ?: emptyList()
                activeId = doc.getString("active")
                if (ids.isEmpty()) { groups = emptyList(); fireChanged(); done?.invoke(null); return@addOnSuccessListener }
                val loaded = mutableListOf<Group>()
                var left = ids.size
                ids.forEach { gid ->
                    db.collection("groups").document(gid).get()
                        .addOnSuccessListener { g ->
                            if (g.exists()) loaded.add(Group(g.id, g.getString("name") ?: "그룹", g.getString("code") ?: "", g.getString("owner") ?: ""))
                        }
                        .addOnCompleteListener {
                            left--
                            if (left == 0) {
                                groups = ids.mapNotNull { i -> loaded.firstOrNull { it.id == i } }
                                fireChanged()
                                done?.invoke(null)
                            }
                        }
                }
            }
            .addOnFailureListener { done?.invoke(it.message ?: "불러오기 실패") }
    }

    /** 로그인 직후: 내 정보 저장 + 그룹 불러오기 */
    fun afterSignIn(done: (String?) -> Unit) {
        val id = uid ?: return done("로그인 실패")
        db.collection("users").document(id)
            .set(mapOf("name" to displayName), SetOptions.merge())
            .addOnCompleteListener { refresh(done) }
    }

    fun setActive(gid: String) {
        activeId = gid
        uid?.let { db.collection("users").document(it).set(mapOf("active" to gid), SetOptions.merge()) }
        fireChanged()
    }

    private fun newCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // 헷갈리는 0,O,1,I 제외
        return (1..6).map { chars.random() }.joinToString("")
    }

    fun createGroup(name: String, done: (String?) -> Unit) {
        val id = uid ?: return done("로그인이 필요해요")
        val code = newCode()
        val ref = db.collection("groups").document()
        val batch = db.batch()
        batch.set(ref, mapOf("name" to name, "owner" to id, "code" to code, "members" to listOf(id),
            "created" to System.currentTimeMillis()))
        batch.set(db.collection("invites").document(code), mapOf("gid" to ref.id, "owner" to id))
        batch.set(db.collection("users").document(id),
            mapOf("groups" to FieldValue.arrayUnion(ref.id), "active" to ref.id), SetOptions.merge())
        batch.commit()
            .addOnSuccessListener { activeId = ref.id; refresh(done) }
            .addOnFailureListener { done(it.message ?: "그룹 만들기 실패") }
    }

    fun joinGroup(codeRaw: String, done: (String?) -> Unit) {
        val id = uid ?: return done("로그인이 필요해요")
        val code = codeRaw.trim().uppercase()
        db.collection("invites").document(code).get()
            .addOnSuccessListener { inv ->
                val gid = inv.getString("gid")
                if (!inv.exists() || gid == null) { done("없는 초대 코드예요"); return@addOnSuccessListener }
                if (groups.any { it.id == gid }) { setActive(gid); done(null); return@addOnSuccessListener }
                db.collection("groups").document(gid).update("members", FieldValue.arrayUnion(id))
                    .addOnSuccessListener {
                        db.collection("users").document(id)
                            .set(mapOf("groups" to FieldValue.arrayUnion(gid), "active" to gid), SetOptions.merge())
                            .addOnCompleteListener { activeId = gid; refresh(done) }
                    }
                    .addOnFailureListener { done(it.message ?: "가입 실패") }
            }
            .addOnFailureListener { done(it.message ?: "코드 확인 실패") }
    }

    /** 내 출조를 선택한 그룹에 올리기 (인터넷이 없으면 연결될 때 자동으로 올라감) */
    fun uploadTrip(t: Store.Trip, done: (String?) -> Unit) {
        val id = uid ?: return done("로그인이 필요해요")
        val g = active ?: return done("먼저 그룹을 만들거나 가입해 주세요")
        val catches = t.catches.map {
            val m = hashMapOf<String, Any>("t" to it.time, "s" to it.slot)
            if (it.lat != null && it.lon != null) { m["la"] = it.lat; m["lo"] = it.lon }
            m
        }
        // 경로는 최대 600점으로 줄여서 올림
        val src = if (Store.hideRoute) emptyList() else t.track
        val step = if (src.size > 600) src.size / 600 + 1 else 1
        val track = src.filterIndexed { i, _ -> i % step == 0 }.map { mapOf("t" to it.time, "la" to it.lat, "lo" to it.lon) }
        val doc = hashMapOf<String, Any>(
            "owner" to id, "ownerName" to displayName,
            "start" to t.start, "end" to t.end, "names" to t.names.toList(),
            "catches" to catches, "track" to track,
            "boat" to t.boat, "port" to t.port,
            "total" to t.catches.size, "photoCount" to t.photos.size,
            "updated" to System.currentTimeMillis()
        )
        val tripRef = db.collection("groups").document(g.id).collection("trips").document("${id}_${t.id}")
        tripRef.set(doc)
            .addOnSuccessListener {
                Store.markUploaded(t.id)
                uploadPhotos(tripRef, t)
                done(null)
            }
            .addOnFailureListener { done(it.message ?: "올리기 실패") }
    }

    lateinit var appContext: Context

    /** 작게 줄인 사진(약 20~40KB)을 출조 아래에 하나씩 저장 */
    private fun uploadPhotos(tripRef: com.google.firebase.firestore.DocumentReference, t: Store.Trip) {
        val id = uid ?: return
        if (!::appContext.isInitialized) return
        t.photos.forEach { p ->
            val f = Photos.file(appContext, Photos.thumbName(p.file))
            if (!f.exists() || f.length() > 700_000) return@forEach
            val data = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
            val m = hashMapOf<String, Any>("owner" to id, "t" to p.time, "data" to data)
            p.catchTime?.let { m["catchT"] = it }
            tripRef.collection("photos").document(p.file.removeSuffix(".jpg")).set(m)
        }
    }

    class RemotePhoto(val time: Long, val catchTime: Long?, val bitmap: android.graphics.Bitmap)

    fun loadPhotos(t: RemoteTrip, done: (List<RemotePhoto>?, String?) -> Unit) {
        val g = active ?: return done(null, "그룹이 없어요")
        db.collection("groups").document(g.id).collection("trips").document(t.id).collection("photos").get()
            .addOnSuccessListener { q ->
                val out = q.documents.mapNotNull { d ->
                    try {
                        val bytes = android.util.Base64.decode(d.getString("data") ?: return@mapNotNull null, android.util.Base64.DEFAULT)
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@mapNotNull null
                        RemotePhoto(d.getLong("t") ?: 0L, d.getLong("catchT"), bmp)
                    } catch (_: Exception) { null }
                }.sortedBy { it.time }
                done(out, null)
            }
            .addOnFailureListener { done(null, it.message ?: "사진 불러오기 실패") }
    }

    // ---- 내 기록 보관함 (서버 백업 / 복원) ----
    /** 백업 안 된 기록을 내 계정에 올림 (사진 원본은 폰에만, 기록·경로만) */
    fun backupPending() {
        val id = uid ?: return
        Store.history.filter { !it.backedUp }.forEach { t ->
            val json = Store.tripToJson(t)
            json.remove("photos")
            db.collection("users").document(id).collection("trips").document(t.id.toString())
                .set(mapOf("json" to json.toString(), "start" to t.start))
                .addOnSuccessListener { Store.markBackedUp(t.id) }
        }
    }

    /** 서버에 백업된 내 기록을 이 폰으로 가져오기 */
    fun restore(done: (Int?, String?) -> Unit) {
        val id = uid ?: return done(null, "로그인이 필요해요")
        db.collection("users").document(id).collection("trips").get()
            .addOnSuccessListener { q ->
                val list = q.documents.mapNotNull { d ->
                    try {
                        val t = Store.tripFromJson(org.json.JSONObject(d.getString("json") ?: return@mapNotNull null))
                        t.backedUp = true
                        t
                    } catch (_: Exception) { null }
                }
                done(Store.mergeRestored(list), null)
            }
            .addOnFailureListener { done(null, it.message ?: "복원 실패") }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(d: com.google.firebase.firestore.DocumentSnapshot): RemoteTrip? = try {
        val catches = (d.get("catches") as? List<Map<String, Any?>>)?.map {
            RemoteCatch((it["t"] as Number).toLong(), (it["s"] as Number).toInt(),
                (it["la"] as? Number)?.toDouble(), (it["lo"] as? Number)?.toDouble())
        } ?: emptyList()
        val track = (d.get("track") as? List<Map<String, Any?>>)?.mapNotNull {
            val la = (it["la"] as? Number)?.toDouble(); val lo = (it["lo"] as? Number)?.toDouble()
            if (la == null || lo == null) null else Store.Pt((it["t"] as? Number)?.toLong() ?: 0L, la, lo)
        } ?: emptyList()
        RemoteTrip(d.id, d.getString("owner") ?: "", d.getString("ownerName") ?: "?",
            d.getLong("start") ?: 0L, d.getLong("end") ?: 0L,
            (d.get("names") as? List<String>) ?: listOf("1번", "2번"), catches, track,
            d.getString("boat") ?: "", d.getString("port") ?: "")
    } catch (e: Exception) { null }

    /** 그룹의 최근 출조 목록 */
    fun recentTrips(done: (List<RemoteTrip>?, String?) -> Unit) {
        val g = active ?: return done(null, "그룹이 없어요")
        db.collection("groups").document(g.id).collection("trips")
            .orderBy("start", Query.Direction.DESCENDING).limit(60).get()
            .addOnSuccessListener { q -> done(q.documents.mapNotNull { parse(it) }, null) }
            .addOnFailureListener { done(null, it.message ?: "불러오기 실패") }
    }

    /** 이 시각 앞뒤 하루 안에 올라온 그룹 출조 */
    fun tripsAround(time: Long, done: (List<RemoteTrip>?, String?) -> Unit) {
        val g = active ?: return done(null, "그룹이 없어요")
        val day = 24 * 3600_000L
        db.collection("groups").document(g.id).collection("trips")
            .whereGreaterThanOrEqualTo("start", time - day)
            .whereLessThanOrEqualTo("start", time + day)
            .get()
            .addOnSuccessListener { q -> done(q.documents.mapNotNull { parse(it) }, null) }
            .addOnFailureListener { done(null, it.message ?: "불러오기 실패") }
    }

    // ---- 같이 출조했는지 판별 ----
    private fun center(t: RemoteTrip): Pair<Double, Double>? {
        val pts = t.track.map { it.lat to it.lon } +
            t.catches.filter { it.lat != null && it.lon != null }.map { it.lat!! to it.lon!! }
        if (pts.isEmpty()) return null
        return pts.sumOf { it.first } / pts.size to pts.sumOf { it.second } / pts.size
    }

    private fun distM(a: Pair<Double, Double>, b: Pair<Double, Double>): Float {
        val r = FloatArray(1)
        android.location.Location.distanceBetween(a.first, a.second, b.first, b.second, r)
        return r[0]
    }

    /** 시간이 1시간 이상 겹치고, 위치가 3km 안이면 같은 출조 */
    fun together(base: RemoteTrip, all: List<RemoteTrip>): List<RemoteTrip> {
        val bc = center(base)
        val endOf = { t: RemoteTrip -> if (t.end > 0) t.end else System.currentTimeMillis() }
        return all.filter { o ->
            if (o.id == base.id) return@filter true
            val overlap = minOf(endOf(base), endOf(o)) - maxOf(base.start, o.start)
            if (overlap < 3600_000L) return@filter false
            val oc = center(o)
            bc == null || oc == null || distM(bc, oc) <= 3000f
        }.sortedByDescending { it.catches.size }
    }
}
