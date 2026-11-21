package com.fishlog.app

import android.content.Context
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import java.util.concurrent.ConcurrentHashMap

/**
 * v6.51 (327): 💬 그룹 출조 댓글
 *  - 댓글 = groups/{gid}/trips/{tid}/comments/{cid} {uid, name, text, t, edited?}
 *  - 댓글 수 · 마지막 댓글 = groups/{gid}/cmtMeta/{tid} {n, at, by} (출조 문서를 다시 올려도 안 지워지게 따로)
 *  - 그룹원만 보고 씀 · 고치기 = 쓴 사람 · 지우기 = 쓴 사람 · 그룹장 · 관리자 · 욕설 **
 *  - 읽음: prefs cmtSeen_{gid}_{tid} (상세를 열어 본 시각) · 내가 댓글 단 출조: cmtMine_{gid}_{tid}
 */
object Comments {
    class Cmt(val id: String, val uid: String, val name: String, val text: String, val t: Long, val edited: Long) {
        val mine get() = uid == Cloud.uid
    }
    class Meta(val n: Int, val at: Long, val by: String)

    private val db get() = FirebaseFirestore.getInstance()
    private fun prefs() = Cloud.appContext.getSharedPreferences("fishlog", Context.MODE_PRIVATE)
    fun col(gid: String, tid: String) = db.collection("groups").document(gid).collection("trips").document(tid).collection("comments")
    private fun metaRef(gid: String, tid: String) = db.collection("groups").document(gid).collection("cmtMeta").document(tid)

    /** 그룹별 댓글 수 (gid → tid → Meta) */
    val metas = ConcurrentHashMap<String, Map<String, Meta>>()

    fun loadMeta(gid: String, done: (Map<String, Meta>?) -> Unit) {
        db.collection("groups").document(gid).collection("cmtMeta").get()
            .addOnSuccessListener { q ->
                val m = q.documents.associate { d ->
                    d.id to Meta((d.getLong("n") ?: 0L).toInt().coerceAtLeast(0), d.getLong("at") ?: 0L, d.getString("by") ?: "")
                }
                metas[gid] = m; done(m)
            }
            .addOnFailureListener { done(null) }
    }

    fun load(gid: String, tid: String, done: (List<Cmt>?, String?) -> Unit) {
        col(gid, tid).orderBy("t", Query.Direction.ASCENDING).limit(300).get()
            .addOnSuccessListener { q ->
                val l = q.documents.mapNotNull { d ->
                    val uid = d.getString("uid") ?: return@mapNotNull null
                    if (Moderation.isBlocked(uid)) return@mapNotNull null
                    Cmt(d.id, uid, d.getString("name") ?: "?", d.getString("text") ?: "", d.getLong("t") ?: 0L, d.getLong("edited") ?: 0L)
                }
                // 댓글 수가 어긋났으면 맞춰 둠 (화면에 보이는 수와 같게)
                val m = metas[gid]?.get(tid)
                if (m == null || m.n != l.size) metas[gid] = (metas[gid].orEmpty() + (tid to Meta(l.size, l.lastOrNull()?.t ?: (m?.at ?: 0L), l.lastOrNull()?.uid ?: (m?.by ?: ""))))
                done(l, null)
            }
            .addOnFailureListener { done(null, fail(it)) }
    }

    fun add(gid: String, tid: String, text: String, done: (String?) -> Unit) {
        val me = Cloud.uid ?: return done("로그인이 필요해요")
        val t = text.trim()
        if (t.isEmpty()) return done("내용을 적어 주세요")
        if (t.length > 300) return done("댓글은 300자까지 쓸 수 있어요")
        val now = System.currentTimeMillis()
        val b = db.batch()
        b.set(col(gid, tid).document(), mapOf("uid" to me, "name" to Cloud.displayName.take(30), "text" to Profanity.mask(t), "t" to now))
        b.set(metaRef(gid, tid), mapOf("n" to FieldValue.increment(1), "at" to now, "by" to me), SetOptions.merge())
        b.commit().addOnSuccessListener {
            prefs().edit().putBoolean("cmtMine_${gid}_$tid", true).putLong("cmtSeen_${gid}_$tid", now).apply()
            done(null)
        }.addOnFailureListener { done(fail(it)) }
    }

    fun edit(gid: String, tid: String, c: Cmt, text: String, done: (String?) -> Unit) {
        val t = text.trim()
        if (t.isEmpty()) return done("내용을 적어 주세요")
        if (t.length > 300) return done("댓글은 300자까지 쓸 수 있어요")
        col(gid, tid).document(c.id).update(mapOf("text" to Profanity.mask(t), "edited" to System.currentTimeMillis()))
            .addOnSuccessListener { done(null) }.addOnFailureListener { done(fail(it)) }
    }

    fun delete(gid: String, tid: String, c: Cmt, done: (String?) -> Unit) {
        val me = Cloud.uid ?: return done("로그인이 필요해요")
        val m = metas[gid]?.get(tid)
        val b = db.batch()
        b.delete(col(gid, tid).document(c.id))
        // 마지막 댓글 시각은 그대로 (지웠다고 새 댓글로 안 보이게)
        b.set(metaRef(gid, tid), mapOf("n" to FieldValue.increment(-1), "at" to (m?.at ?: c.t), "by" to me), SetOptions.merge())
        b.commit().addOnSuccessListener { done(null) }.addOnFailureListener { done(fail(it)) }
    }

    /** 상세를 열어 봄 → 그 출조 새 댓글 읽음 */
    fun markSeen(gid: String, tid: String) {
        prefs().edit().putLong("cmtSeen_${gid}_$tid", System.currentTimeMillis()).apply()
        GroupNews.markCmtSeen(gid, tid)
    }

    /** 남이 단 새 댓글이 있는지 (목록 카드 NEW) */
    fun hasNew(gid: String, tid: String): Boolean {
        val m = metas[gid]?.get(tid) ?: return false
        if (m.n <= 0 || m.by == Cloud.uid) return false
        return m.at > prefs().getLong("cmtSeen_${gid}_$tid", 0L)
    }

    /** 이 출조의 새 댓글을 나에게 알릴지: 내 출조이거나 내가 댓글을 단 출조 */
    fun concernsMe(gid: String, tid: String): Boolean =
        tid.substringBefore("_") == Cloud.uid || prefs().getBoolean("cmtMine_${gid}_$tid", false)

    fun seenAt(gid: String, tid: String): Long = prefs().getLong("cmtSeen_${gid}_$tid", 0L)

    private fun fail(e: Exception): String {
        val s = e.message ?: ""
        return if (s.contains("PERMISSION_DENIED", true)) "댓글을 쓸 수 없어요 (글쓰기 금지 상태이거나, Firebase 규칙을 아직 게시하지 않았어요)"
            else s.ifBlank { "실패 (인터넷 확인)" }
    }
}
