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

    class Group(val id: String, val name: String, val code: String, val owner: String, val types: List<String> = emptyList()) {
        fun typesLabel(): String = types.map { Store.catLabel(it) }.filter { it.isNotBlank() }.joinToString(" · ")
    }

    class RemoteCatch(val time: Long, val slot: Int, val lat: Double?, val lon: Double?)
    class RemoteTrip(
        val id: String, val owner: String, val ownerName: String,
        val start: Long, val end: Long, val names: List<String>,
        val catches: List<RemoteCatch>, val track: List<Store.Pt>,
        val boat: String = "", val port: String = "", val photoCount: Int = 0, val cat: String = ""
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

    /** 앱 관리자 (어종 목록 관리, 사용자 확인) */
    const val ADMIN_EMAIL = "jonginzz33@gmail.com"
    val isAdmin: Boolean get() = auth.currentUser?.email.equals(ADMIN_EMAIL, ignoreCase = true)

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
        touchUser()
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
                            val types = (g.get("types") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                            if (g.exists()) loaded.add(Group(g.id, g.getString("name") ?: "그룹", g.getString("code") ?: "",
                                g.getString("owner") ?: "", types))
                        }
                        .addOnCompleteListener {
                            left--
                            if (left == 0) {
                                groups = ids.mapNotNull { i -> loaded.firstOrNull { it.id == i } }
                                // 멤버 목록에 내 이름 남기기 (예전에 가입한 경우 대비)
                                groups.forEach { gr ->
                                    db.collection("groups").document(gr.id).collection("members").document(id)
                                        .set(mapOf("name" to displayName), SetOptions.merge())
                                }
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

    fun createGroup(name: String, types: List<String>, done: (String?) -> Unit) {
        val id = uid ?: return done("로그인이 필요해요")
        val code = newCode()
        val ref = db.collection("groups").document()
        val batch = db.batch()
        batch.set(ref, mapOf("name" to name, "owner" to id, "code" to code, "members" to listOf(id),
            "types" to types, "created" to System.currentTimeMillis()))
        batch.set(db.collection("invites").document(code), mapOf("gid" to ref.id, "owner" to id))
        batch.set(db.collection("users").document(id),
            mapOf("groups" to FieldValue.arrayUnion(ref.id), "active" to ref.id), SetOptions.merge())
        batch.commit()
            .addOnSuccessListener {
                ref.collection("members").document(id).set(mapOf("name" to displayName, "joined" to System.currentTimeMillis()))
                activeId = ref.id; refresh(done)
            }
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
                        db.collection("groups").document(gid).collection("members").document(id)
                            .set(mapOf("name" to displayName, "joined" to System.currentTimeMillis()), SetOptions.merge())
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
            "boat" to t.boat, "port" to t.port, "cat" to t.cat,
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

    /** 사진 업로드: 목록용 작은 사진(photos) + 크게 볼 1080px 사진(photosBig) */
    private fun uploadPhotos(tripRef: com.google.firebase.firestore.DocumentReference, t: Store.Trip) {
        val id = uid ?: return
        if (!::appContext.isInitialized) return
        val list = t.photos.toList()
        Thread {
            list.forEach { p ->
                val docId = p.file.removeSuffix(".jpg")
                val thumb = Photos.file(appContext, Photos.thumbName(p.file))
                if (thumb.exists() && thumb.length() < 700_000) {
                    val m = hashMapOf<String, Any>("owner" to id, "t" to p.time,
                        "data" to android.util.Base64.encodeToString(thumb.readBytes(), android.util.Base64.NO_WRAP))
                    p.catchTime?.let { m["catchT"] = it }
                    tripRef.collection("photos").document(docId).set(m)
                }
                val big = Photos.shareFile(appContext, p)
                if (big != null && big.length() < 720_000) {
                    tripRef.collection("photosBig").document(docId).set(mapOf("owner" to id,
                        "data" to android.util.Base64.encodeToString(big.readBytes(), android.util.Base64.NO_WRAP)))
                }
            }
        }.start()
    }

    class RemotePhoto(val id: String, val time: Long, val catchTime: Long?, val bitmap: android.graphics.Bitmap)

    fun loadPhotos(t: RemoteTrip, done: (List<RemotePhoto>?, String?) -> Unit) {
        val g = active ?: return done(null, "그룹이 없어요")
        db.collection("groups").document(g.id).collection("trips").document(t.id).collection("photos").get()
            .addOnSuccessListener { q ->
                val out = q.documents.mapNotNull { d ->
                    try {
                        val bytes = android.util.Base64.decode(d.getString("data") ?: return@mapNotNull null, android.util.Base64.DEFAULT)
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@mapNotNull null
                        RemotePhoto(d.id, d.getLong("t") ?: 0L, d.getLong("catchT"), bmp)
                    } catch (_: Exception) { null }
                }.sortedBy { it.time }
                done(out, null)
            }
            .addOnFailureListener { done(null, it.message ?: "사진 불러오기 실패") }
    }

    /** 누르면 받는 큰 사진. 없으면(예전 업로드) null */
    fun loadBigPhoto(tripId: String, photoId: String, done: (android.graphics.Bitmap?) -> Unit) {
        val g = active ?: return done(null)
        db.collection("groups").document(g.id).collection("trips").document(tripId).collection("photosBig").document(photoId).get()
            .addOnSuccessListener { d ->
                val s64 = d.getString("data")
                if (s64 == null) { done(null); return@addOnSuccessListener }
                val bytes = android.util.Base64.decode(s64, android.util.Base64.DEFAULT)
                done(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            }
            .addOnFailureListener { done(null) }
    }

    /** 그룹에 올린 내 출조 내리기 (사진까지) */
    fun deleteGroupTrip(docId: String, done: (String?) -> Unit) {
        val g = active ?: return done("그룹이 없어요")
        val ref = db.collection("groups").document(g.id).collection("trips").document(docId)
        ref.collection("photos").get().addOnCompleteListener { a ->
            a.result?.documents?.forEach { it.reference.delete() }
            ref.collection("photosBig").get().addOnCompleteListener { b ->
                b.result?.documents?.forEach { it.reference.delete() }
                ref.delete()
                    .addOnSuccessListener {
                        val myId = uid
                        if (myId != null && docId.startsWith(myId + "_")) {
                            docId.removePrefix(myId + "_").toLongOrNull()?.let { Store.markNotUploaded(it) }
                        }
                        done(null)
                    }
                    .addOnFailureListener { done(it.message ?: "지우기 실패") }
            }
        }
    }

    val isOwner: Boolean get() = active?.owner != null && active?.owner == uid

    /** 그룹 이름 바꾸기 (그룹장만) */
    fun renameGroup(name: String, done: (String?) -> Unit) {
        val g = active ?: return done("그룹이 없어요")
        db.collection("groups").document(g.id).update("name", name)
            .addOnSuccessListener { refresh(done) }
            .addOnFailureListener { done(it.message ?: "이름 바꾸기 실패") }
    }

    /** 그룹 종류 바꾸기 (그룹장만) */
    fun setGroupTypes(types: List<String>, done: (String?) -> Unit) {
        val g = active ?: return done("그룹이 없어요")
        db.collection("groups").document(g.id).update("types", types)
            .addOnSuccessListener { refresh(done) }
            .addOnFailureListener { done(it.message ?: "바꾸기 실패") }
    }

    /** 초대 코드 새로 만들기 (그룹장만). 예전 코드는 더 이상 안 됨 */
    fun newInviteCode(done: (String?) -> Unit) {
        val id = uid ?: return done("로그인이 필요해요")
        val g = active ?: return done("그룹이 없어요")
        val code = newCode()
        val batch = db.batch()
        if (g.code.isNotBlank()) batch.delete(db.collection("invites").document(g.code))
        batch.set(db.collection("invites").document(code), mapOf("gid" to g.id, "owner" to id))
        batch.update(db.collection("groups").document(g.id), "code", code)
        batch.commit()
            .addOnSuccessListener { refresh(done) }
            .addOnFailureListener { done(it.message ?: "코드 만들기 실패") }
    }

    /** 멤버 내보내기 (그룹장만). removeTrips = 그 사람이 올린 출조도 지우기 */
    fun kickMember(memberUid: String, removeTrips: Boolean, done: (String?) -> Unit) {
        val g = active ?: return done("그룹이 없어요")
        if (memberUid == uid) return done("자기 자신은 내보낼 수 없어요")
        val gref = db.collection("groups").document(g.id)
        gref.update("members", FieldValue.arrayRemove(memberUid))
            .addOnSuccessListener {
                gref.collection("members").document(memberUid).delete()
                if (!removeTrips) { done(null); return@addOnSuccessListener }
                gref.collection("trips").whereEqualTo("owner", memberUid).get()
                    .addOnSuccessListener { q ->
                        if (q.isEmpty) { done(null); return@addOnSuccessListener }
                        var left = q.size()
                        q.documents.forEach { d ->
                            deleteGroupTrip(d.id) { _ -> left--; if (left == 0) done(null) }
                        }
                    }
                    .addOnFailureListener { done("내보냈지만 출조 지우기 실패: ${it.message}") }
            }
            .addOnFailureListener { done(it.message ?: "내보내기 실패") }
    }

    // ---------------- 사용자 마지막 접속 (관리자 확인용) ----------------
    private var lastTouch = 0L
    fun touchUser() {
        val id = uid ?: return
        val now = System.currentTimeMillis()
        if (now - lastTouch < 5 * 60_000L) return
        lastTouch = now
        db.collection("users").document(id).set(mapOf(
            "name" to displayName, "email" to (auth.currentUser?.email ?: ""),
            "lastSeen" to now, "app" to BuildConfigInfo.VERSION), SetOptions.merge())
    }

    class AppUser(val uid: String, val name: String, val email: String, val lastSeen: Long, val groups: Int, val version: String)

    fun adminUsers(done: (List<AppUser>?, String?) -> Unit) {
        db.collection("users").get()
            .addOnSuccessListener { q ->
                done(q.documents.map { d ->
                    AppUser(d.id, d.getString("name") ?: "?", d.getString("email") ?: "",
                        d.getLong("lastSeen") ?: 0L, (d.get("groups") as? List<*>)?.size ?: 0, d.getString("app") ?: "")
                }.sortedByDescending { it.lastSeen }, null)
            }
            .addOnFailureListener { done(null, it.message ?: "불러오기 실패 (보안 규칙 확인)") }
    }

    // ---------------- 어종 목록 (관리자가 정하고 모두가 받음) ----------------
    fun loadSpecies() {
        db.collection("config").document("species").get()
            .addOnSuccessListener { d ->
                if (!d.exists()) return@addOnSuccessListener
                val map = Store.CATS.associateWith { c -> (d.get(c) as? List<*>)?.mapNotNull { it as? String } }
                Store.applyServerSpecies(map)
            }
    }

    fun saveSpecies(done: (String?) -> Unit) {
        if (!isAdmin) return done("관리자만 바꿀 수 있어요")
        db.collection("config").document("species").set(Store.CATS.associateWith { Store.species(it) })
            .addOnSuccessListener { done(null) }
            .addOnFailureListener { done(it.message ?: "저장 실패") }
    }

    // ---------------- 그룹 일정 ----------------
    class Event(
        val id: String, val owner: String, val ownerName: String,
        val date: String, val time: String, val place: String, val boat: String,
        val cat: String, val memo: String, val created: Long
    )

    fun loadEvents(done: (List<Event>?, String?) -> Unit) {
        val g = active ?: return done(null, "그룹이 없어요")
        db.collection("groups").document(g.id).collection("events").get()
            .addOnSuccessListener { q ->
                done(q.documents.map { d ->
                    Event(d.id, d.getString("owner") ?: "", d.getString("ownerName") ?: "?",
                        d.getString("date") ?: "", d.getString("time") ?: "", d.getString("place") ?: "",
                        d.getString("boat") ?: "", d.getString("cat") ?: "", d.getString("memo") ?: "",
                        d.getLong("created") ?: 0L)
                }.sortedWith(compareBy({ it.date }, { it.time })), null)
            }
            .addOnFailureListener { done(null, it.message ?: "일정 불러오기 실패 (보안 규칙 확인)") }
    }

    fun saveEvent(id: String?, date: String, time: String, place: String, boat: String, cat: String, memo: String,
                  ownerOf: Event? = null, done: (String?) -> Unit) {
        val me = uid ?: return done("로그인이 필요해요")
        val g = active ?: return done("그룹이 없어요")
        val col = db.collection("groups").document(g.id).collection("events")
        val ref = if (id == null) col.document() else col.document(id)
        ref.set(mapOf(
            "owner" to (ownerOf?.owner ?: me), "ownerName" to (ownerOf?.ownerName ?: displayName),
            "date" to date, "time" to time, "place" to place, "boat" to boat, "cat" to cat, "memo" to memo,
            "created" to (ownerOf?.created ?: System.currentTimeMillis())))
            .addOnSuccessListener { done(null) }
            .addOnFailureListener { done(it.message ?: "일정 저장 실패") }
    }

    fun deleteEvent(id: String, done: (String?) -> Unit) {
        val g = active ?: return done("그룹이 없어요")
        db.collection("groups").document(g.id).collection("events").document(id).delete()
            .addOnSuccessListener { done(null) }
            .addOnFailureListener { done(it.message ?: "삭제 실패") }
    }

    class Member(val uid: String, val name: String, val joined: Long)

    /** 그룹 멤버 (예전 버전 앱 멤버는 이름 없이 나옴) */
    fun loadMembers(done: (List<Member>?, String?) -> Unit) {
        val g = active ?: return done(null, "그룹이 없어요")
        val gref = db.collection("groups").document(g.id)
        gref.get().addOnSuccessListener { gd ->
            @Suppress("UNCHECKED_CAST")
            val uids = (gd.get("members") as? List<String>) ?: emptyList()
            gref.collection("members").get()
                .addOnSuccessListener { q ->
                    val named = q.documents.associate { it.id to (it.getString("name") to (it.getLong("joined") ?: 0L)) }
                    done(uids.map { u ->
                        val n = named[u]
                        Member(u, n?.first ?: "이름 없음 (앱 업데이트 전)", n?.second ?: 0L)
                    }, null)
                }
                .addOnFailureListener { done(uids.map { Member(it, "?", 0L) }, null) }
        }.addOnFailureListener { done(null, it.message ?: "불러오기 실패") }
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
            d.getString("boat") ?: "", d.getString("port") ?: "", (d.getLong("photoCount") ?: 0L).toInt(), d.getString("cat") ?: "")
    } catch (e: Exception) { null }

    /** 그룹의 최근 출조 목록 */
    fun recentTrips(done: (List<RemoteTrip>?, String?) -> Unit) {
        val g = active ?: return done(null, "그룹이 없어요")
        db.collection("groups").document(g.id).collection("trips")
            .orderBy("start", Query.Direction.DESCENDING).limit(300).get()
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
