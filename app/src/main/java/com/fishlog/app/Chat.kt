package com.fishlog.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions

/**
 * 실시간 채팅 (알림 없음 · 앱 안에서만)
 *  - chat/{id}                 : 전체 채팅방 (앱 쓰는 모든 사람)
 *  - groups/{gid}/chat/{id}    : 그룹 채팅방 (그룹원만)
 *  - presence/{uid}            : 지금 접속 중 (앱을 켜 두면 2분마다 시각 갱신)
 *  - chatMute/{uid}            : 관리자가 채팅 금지한 사람
 *  - 최근 3일 · 방마다 최근 80개만 받음 (무료 사용량 안에서)
 *  - 욕설은 보낼 때 별표로 가림 (Profanity)
 */
object Chat {

    const val ALL = "all"
    private const val KEEP_MS = 3 * 24 * 3_600_000L
    private const val LIMIT = 80L

    class Msg(val id: String, val room: String, val uid: String, val name: String, val text: String,
              val time: Long, val edited: Long) {
        val mine get() = uid == Cloud.uid
    }

    private val db get() = FirebaseFirestore.getInstance()
    private val main = Handler(Looper.getMainLooper())
    private fun prefs() = Cloud.appContext.getSharedPreferences("fishlog", Context.MODE_PRIVATE)

    fun ref(room: String): CollectionReference =
        if (room == ALL) db.collection("chat") else db.collection("groups").document(room).collection("chat")

    // ---------------- 실시간 받기 ----------------
    private val latest = HashMap<String, List<Msg>>()
    private var regs: List<ListenerRegistration> = emptyList()
    private var key = ""
    private val listeners = mutableSetOf<() -> Unit>()
    private val stopLater = Runnable { if (listeners.isEmpty()) stopAll() }

    fun rooms(): List<String> = listOf(ALL) + Cloud.groups.map { it.id }

    /** 화면이 켜져 있는 동안 모든 방을 지켜봄 (빨간 숫자용). 바뀌면 onChange (메인 스레드) */
    fun start(onChange: () -> Unit) {
        listeners.add(onChange)
        main.removeCallbacks(stopLater)
        if (Cloud.user == null || !Cloud.isInitialized()) return
        val k = rooms().joinToString(",")
        if (k == key && regs.isNotEmpty()) { onChange(); return }
        stopAll()
        key = k
        val since = System.currentTimeMillis() - KEEP_MS
        regs = rooms().map { r ->
            ref(r).whereGreaterThan("t", since).orderBy("t", Query.Direction.DESCENDING).limit(LIMIT)
                .addSnapshotListener { q, _ ->
                    if (q == null) return@addSnapshotListener
                    latest[r] = q.documents.map { d ->
                        Msg(d.id, r, d.getString("uid") ?: "", d.getString("name") ?: "?", d.getString("text") ?: "",
                            d.getLong("t") ?: 0L, d.getLong("edited") ?: 0L)
                    }.sortedBy { it.time }
                    fire()
                }
        }
    }

    /** 그룹이 바뀌었으면 방 목록 다시 (보고 있는 화면이 있을 때만) */
    fun refreshRooms() { listeners.firstOrNull()?.let { start(it) } }

    /** 그만 보기 (다른 화면이 곧 다시 볼 수 있어서 5초 뒤에 끊음) */
    fun stop(onChange: () -> Unit) {
        listeners.remove(onChange)
        if (listeners.isEmpty()) { main.removeCallbacks(stopLater); main.postDelayed(stopLater, 5_000L) }
    }

    private fun stopAll() {
        regs.forEach { try { it.remove() } catch (_: Exception) {} }
        regs = emptyList(); key = ""
    }

    private fun fire() = main.post { listeners.toList().forEach { it() } }

    fun messages(room: String): List<Msg> =
        latest[room].orEmpty().filter { !Moderation.isBlocked(it.uid) }

    // ---------------- 안 읽은 글 (폰에 저장) ----------------
    private fun seen(room: String): Long {
        val k = "chatSeen_$room"
        val p = prefs()
        if (!p.contains(k)) p.edit().putLong(k, System.currentTimeMillis()).apply()
        return p.getLong(k, 0L)
    }

    fun unread(room: String): Int { val s = seen(room); return messages(room).count { !it.mine && it.time > s } }
    fun totalUnread(): Int = rooms().sumOf { unread(it) }

    fun markSeen(room: String) {
        val t = maxOf(System.currentTimeMillis(), latest[room]?.maxOfOrNull { it.time } ?: 0L)
        prefs().edit().putLong("chatSeen_$room", t).apply()
    }

    // ---------------- 채팅 이름 (전체방만 바꿀 수 있음) ----------------
    /** 전체방에서 쓰는 이름. null = 내 닉네임 그대로 */
    var chatName: String?
        get() = prefs().getString("chatName", null)?.takeIf { it.isNotBlank() }
        private set(v) { prefs().edit().putString("chatName", v).apply() }

    fun nameFor(room: String): String = if (room == ALL) chatName ?: Cloud.displayName else Cloud.displayName

    fun useNickname() { chatName = null }

    /** 채팅 이름 바꾸기: 2~12자, 욕설 안 됨, 다른 사람 닉네임 흉내 안 됨 */
    fun setChatName(n: String, done: (String?) -> Unit) {
        val name = n.trim()
        if (name.length < 2 || name.length > 12) return done("채팅 이름은 2~12자로 적어 주세요")
        if (Profanity.has(name)) return done("쓸 수 없는 말이 들어 있어요")
        if (name.contains("운영자") || name.contains("관리자")) return done("'운영자·관리자'는 이름에 쓸 수 없어요")
        val key = name.lowercase().replace(" ", "")
        db.collection("nicknames").document(key).get()
            .addOnSuccessListener { d ->
                if (d.exists() && d.getString("uid") != Cloud.uid) done("다른 사람이 쓰는 닉네임이라 쓸 수 없어요")
                else { chatName = name; done(null) }
            }
            .addOnFailureListener { done(it.message ?: "확인 실패 (인터넷 확인)") }
    }

    // ---------------- 보내기 · 고치기 · 지우기 ----------------
    fun send(room: String, text: String, done: (String?) -> Unit) {
        val me = Cloud.uid ?: return done("로그인이 필요해요")
        val t = text.trim()
        if (t.isEmpty()) return done("내용을 적어 주세요")
        if (t.length > 500) return done("한 번에 500자까지 보낼 수 있어요")
        ref(room).document().set(mapOf("uid" to me, "name" to nameFor(room), "text" to Profanity.mask(t),
            "t" to System.currentTimeMillis()))
            .addOnSuccessListener { markSeen(room); done(null) }
            .addOnFailureListener { done(failMsg(it)) }
    }

    fun edit(m: Msg, text: String, done: (String?) -> Unit) {
        val t = text.trim()
        if (t.isEmpty()) return done("내용을 적어 주세요")
        if (t.length > 500) return done("한 번에 500자까지 보낼 수 있어요")
        ref(m.room).document(m.id).set(mapOf("uid" to m.uid, "name" to m.name, "text" to Profanity.mask(t),
            "t" to m.time, "edited" to System.currentTimeMillis()))
            .addOnSuccessListener { done(null) }
            .addOnFailureListener { done(failMsg(it)) }
    }

    fun delete(m: Msg, done: (String?) -> Unit) {
        ref(m.room).document(m.id).delete()
            .addOnSuccessListener { done(null) }
            .addOnFailureListener { done(it.message ?: "지우기 실패") }
    }

    private fun failMsg(e: Exception): String {
        val s = e.message ?: ""
        return if (s.contains("PERMISSION_DENIED", true))
            "보낼 수 없어요 (채팅 금지 상태이거나, 보안 규칙이 아직 바뀌지 않았어요)" else s.ifBlank { "보내기 실패 (인터넷 확인)" }
    }

    // ---------------- 관리자: 채팅 금지 · 실제 닉네임 ----------------
    fun mute(uid: String, name: String, done: (String?) -> Unit) {
        if (!Cloud.isAdmin) return done("운영자만 할 수 있어요")
        db.collection("chatMute").document(uid).set(mapOf("name" to name, "t" to System.currentTimeMillis()))
            .addOnSuccessListener { done(null) }.addOnFailureListener { done(it.message ?: "실패") }
    }

    fun unmute(uid: String, done: (String?) -> Unit) {
        if (!Cloud.isAdmin) return done("운영자만 할 수 있어요")
        db.collection("chatMute").document(uid).delete()
            .addOnSuccessListener { done(null) }.addOnFailureListener { done(it.message ?: "실패") }
    }

    fun mutedUids(done: (Set<String>) -> Unit) {
        db.collection("chatMute").get()
            .addOnSuccessListener { q -> done(q.documents.map { it.id }.toSet()) }
            .addOnFailureListener { done(emptySet()) }
    }

    /** 운영자만: uid → 실제 닉네임 (채팅 이름을 바꿔도 누군지) */
    val realNames = HashMap<String, String>()
    fun loadRealNames(done: () -> Unit) {
        if (!Cloud.isAdmin) return done()
        Cloud.adminUsers { list, _ ->
            list?.forEach { u -> realNames[u.uid] = u.nick?.takeIf { it.isNotBlank() } ?: u.name }
            done()
        }
    }

    // ---------------- 접속 중 ----------------
    private var presenceCb: ((Long) -> Unit)? = null
    @Volatile var online: Long = 0L
        private set
    private val presenceTick = object : Runnable {
        override fun run() { ping(); count(); main.postDelayed(this, 120_000L) }
    }

    /** 앱 화면이 켜져 있는 동안 2분마다 '접속 중' 표시 + 접속 인원 세기 */
    fun startPresence(cb: (Long) -> Unit) {
        presenceCb = cb
        main.removeCallbacks(presenceTick)
        if (Cloud.user == null) return
        cb(online)
        presenceTick.run()
    }

    fun stopPresence() { presenceCb = null; main.removeCallbacks(presenceTick) }

    private var lastPing = 0L
    fun ping() {
        val me = Cloud.uid ?: return
        val now = System.currentTimeMillis()
        if (now - lastPing < 90_000L) return
        lastPing = now
        db.collection("presence").document(me).set(mapOf("t" to now, "name" to Cloud.displayName), SetOptions.merge())
    }

    fun count(done: ((Long) -> Unit)? = null) {
        db.collection("presence").whereGreaterThan("t", System.currentTimeMillis() - 4 * 60_000L).count()
            .get(AggregateSource.SERVER)
            .addOnSuccessListener { r -> online = maxOf(1L, r.count); presenceCb?.invoke(online); done?.invoke(online) }
            .addOnFailureListener { done?.invoke(online) }
    }
}

/** 욕설 가리기: 글자 사이에 띄어쓰기·숫자·기호를 넣어도 찾아서 같은 길이의 * 로 바꿈 */
object Profanity {
    // (낚시 앱이라 '새끼'(어린 고기)·'씹다'처럼 평소 쓰는 말은 빼고, 욕으로만 쓰는 말만)
    private val WORDS = listOf(
        "씨발", "시발", "씨빨", "씨팔", "시팔", "쓰발", "씌발", "씨바알", "씹새", "씹할", "씹년", "씹놈", "좆", "좃같", "존나", "졸라", "존내",
        "병신", "븅신", "빙신", "병싄", "개새끼", "개새기", "개색기", "개색끼", "개쉐", "개섀", "이새끼", "니새끼", "저새끼", "색히", "섀끼",
        "지랄", "미친놈", "미친년", "미친새", "또라이", "엠창", "니미럴", "느금", "니애미", "니엄마", "니애비",
        "꺼져", "닥쳐", "썅", "쌍놈", "쌍년", "육시럴", "염병", "호로새", "후레자식", "등신", "찐따", "한남충", "김치녀",
        "ㅅㅂ", "ㅆㅂ", "ㅂㅅ", "ㅈㄹ", "ㅈㄴ", "ㅄ",
        "fuck", "shit", "bitch", "asshole", "fxxk", "sibal", "ssibal"
    )
    private const val GAP = "[\\s\\d._\\-~!*^@#]*"
    private val REGEX = Regex(
        WORDS.sortedByDescending { it.length }.joinToString("|") { w -> w.map { Regex.escape(it.toString()) }.joinToString(GAP) },
        RegexOption.IGNORE_CASE)

    fun has(s: String): Boolean = REGEX.containsMatchIn(s)

    fun mask(s: String): String = REGEX.replace(s) { m -> m.value.map { c -> if (c.isWhitespace()) c else '*' }.joinToString("") }
}
