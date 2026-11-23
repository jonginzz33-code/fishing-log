package com.fishlog.app

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * v6.62 (343): 👀 그룹 채팅 안 읽은 사람 수 (카톡 노란 숫자)
 *  - 그룹 채팅방만 (🌐 전체 채팅은 안 함)
 *  - 읽음 = groups/{gid}/chatRead/{내 uid} {t = 어디까지 읽었는지, at} · 10초에 한 번까지만 씀
 *  - 안 읽은 수 = 그룹원 − 보낸 사람 − (읽은 시각 ≥ 글 시각인 사람)
 *  - 앱을 업데이트 안 한 그룹원은 읽음 기록이 없어 '안 읽음'으로 셈
 */
object ChatRead {
    private val db get() = FirebaseFirestore.getInstance()
    private var lastWrite = HashMap<String, Long>()
    private var lastT = HashMap<String, Long>()

    @Volatile var gid: String? = null; private set
    var reads: Map<String, Long> = emptyMap(); private set
    var members: List<String> = emptyList(); private set
    private var reg: ListenerRegistration? = null

    /** 내가 여기까지 읽음 (10초에 한 번 · 더 앞선 시각일 때만) */
    fun mark(g: String, t: Long) {
        val me = Cloud.uid ?: return
        if (t <= (lastT[g] ?: 0L)) return
        val now = System.currentTimeMillis()
        if (now - (lastWrite[g] ?: 0L) < 10_000L) return
        lastWrite[g] = now; lastT[g] = t
        db.collection("groups").document(g).collection("chatRead").document(me)
            .set(mapOf("t" to t, "at" to now))
    }

    /** 그 방 읽음 · 그룹원 목록 듣기 (방 바꾸면 다시) */
    fun listen(g: String?, changed: () -> Unit) {
        if (g == gid && reg != null) return
        stop()
        gid = g
        if (g == null) return
        db.collection("groups").document(g).get().addOnSuccessListener { d ->
            @Suppress("UNCHECKED_CAST")
            members = (d.get("members") as? List<String>) ?: emptyList()
            if (gid == g) changed()
        }
        reg = db.collection("groups").document(g).collection("chatRead").addSnapshotListener { q, _ ->
            if (q == null || gid != g) return@addSnapshotListener
            reads = q.documents.associate { it.id to (it.getLong("t") ?: 0L) }
            changed()
        }
    }

    fun stop() { reg?.remove(); reg = null; gid = null; reads = emptyMap(); members = emptyList() }

    /** 이 글을 아직 안 읽은 그룹원 uid (보낸 사람 빼고) */
    fun unread(m: Chat.Msg): List<String> {
        if (members.isEmpty() || m.room != gid) return emptyList()
        return members.filter { it != m.uid && (reads[it] ?: 0L) < m.time }
    }
}
