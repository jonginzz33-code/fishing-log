package com.fishlog.app

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONObject

/**
 * v6.28 (279): 게시판 글의 그룹 이름을 그룹 고유 번호(gid)로 — 서버 표 groupNames/{gid} {name, img, t}
 *  - 그룹장(또는 관리자)이 그룹을 만들거나 이름 · 그림을 바꿀 때 씀 (그룹장 앱이 켜질 때도 한 번 맞춤)
 *  - 게시판은 글의 gid 로 지금 이름을 찾아 보여 줌 (폰에 6시간 기억) · gid 가 없는 예전 글은 글에 적힌 이름 그대로
 */
object GroupNames {
    class N(val name: String, val img: String, val at: Long)

    private val db get() = FirebaseFirestore.getInstance()
    private val mem = HashMap<String, N>()
    private const val KEEP = 6 * 3_600_000L
    private fun prefs(): android.content.SharedPreferences? =
        try { Cloud.appContext.getSharedPreferences("group_names", Context.MODE_PRIVATE) } catch (_: Exception) { null }

    /** 폰에 기억한 이름 (없으면 null) */
    fun get(gid: String): N? {
        if (gid.isBlank()) return null
        mem[gid]?.let { return it }
        val s = prefs()?.getString(gid, null) ?: return null
        return try { val o = JSONObject(s); N(o.optString("n"), o.optString("i"), o.optLong("t")).also { mem[gid] = it } } catch (_: Exception) { null }
    }

    private fun keep(gid: String, n: N) {
        mem[gid] = n
        prefs()?.edit()?.putString(gid, JSONObject().put("n", n.name).put("i", n.img).put("t", n.at).toString())?.apply()
    }

    /** 오래된 것만 서버에서 받아서 · 뭐라도 바뀌었으면 changed() */
    fun load(gids: Collection<String>, changed: () -> Unit) {
        val now = System.currentTimeMillis()
        val need = gids.filter { it.isNotBlank() }.distinct().filter { g -> (get(g)?.at ?: 0L) < now - KEEP }
        if (need.isEmpty()) return
        var left = need.size; var any = false
        need.forEach { g ->
            db.collection("groupNames").document(g).get()
                .addOnSuccessListener { d ->
                    val old = get(g)
                    val n = N(d.getString("name") ?: old?.name ?: "", d.getString("img") ?: old?.img ?: "", now)
                    if (old == null || old.name != n.name || old.img != n.img) any = true
                    keep(g, n)
                }
                .addOnCompleteListener { left--; if (left == 0 && any) changed() }
        }
    }

    /** 그룹 이름표 쓰기 (그룹장 · 관리자만 되는 규칙) */
    fun push(g: Cloud.Group, done: ((String?) -> Unit)? = null) {
        val img = GroupIcon.cached(g.id)?.let { PublicBoard.smallB64(it) } ?: ""
        val now = System.currentTimeMillis()
        db.collection("groupNames").document(g.id).set(mapOf("name" to g.title, "img" to img, "t" to now))
            .addOnSuccessListener {
                keep(g.id, N(g.title, img, now))
                prefs()?.edit()?.putString("pushed_${g.id}", sig(g, img))?.apply()
                done?.invoke(null)
            }
            .addOnFailureListener { done?.invoke(it.message ?: "그룹 이름표 저장 실패 (보안 규칙 확인)") }
    }
    private fun sig(g: Cloud.Group, img: String) = "${g.title}|${img.hashCode()}"

    /** 앱이 켜질 때: 내가 그룹장인 그룹 중 이름표가 다르거나 없는 것만 */
    fun syncMine() {
        val me = Cloud.uid ?: return
        Cloud.groups.filter { it.owner == me }.forEach { g ->
            val img = GroupIcon.cached(g.id)?.let { PublicBoard.smallB64(it) } ?: ""
            if (prefs()?.getString("pushed_${g.id}", null) != sig(g, img)) push(g)
        }
    }

    /**
     * 관리자 1회: 그룹 번호가 없는 예전 게시판 글에 gid · gname 채우기
     *  - 글 id(올린사람_출조번호) 와 같은 출조가 있는 그룹(groups/{gid}/trips/{같은 id}) 을 올린 사람의 그룹 목록에서 찾음
     *  - 여러 그룹이면 글에 적힌 이름과 같은 그룹 · 아니면 첫 그룹 · 그 그룹 이름표(groupNames)도 같이 만듦
     */
    fun backfill(progress: (String) -> Unit, done: (String) -> Unit) {
        db.collection("public").get().addOnSuccessListener { q ->
            val todo = q.documents.filter { (it.getString("gid") ?: "").isBlank() && it.id.contains("_") }
            if (todo.isEmpty()) { done("채울 글이 없어요 (모두 그룹 번호가 있어요)"); return@addOnSuccessListener }
            var left = todo.size; var ok = 0; var none = 0; var fail = 0
            val made = HashSet<String>()
            fun end() { left--; progress("${todo.size - left}/${todo.size}"); if (left == 0) done("채움 $ok · 그룹 기록 없음 $none · 실패 $fail") }
            todo.forEach { d ->
                val owner = d.getString("owner") ?: d.id.substringBefore("_")
                db.collection("users").document(owner).get().addOnSuccessListener { u ->
                    val gids = (u.get("groups") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
                    if (gids.isEmpty()) { none++; end(); return@addOnSuccessListener }
                    val hits = mutableListOf<Pair<String, String>>()   // gid → 그룹 이름
                    var gl = gids.size
                    fun pick() {
                        if (hits.isEmpty()) { none++; end(); return }
                        val old = d.getString("gname") ?: ""
                        val (gid, title) = hits.firstOrNull { it.second == old } ?: hits.first()
                        val upd = hashMapOf<String, Any>("gid" to gid, "upd" to System.currentTimeMillis())
                        if (title.isNotBlank()) upd["gname"] = title
                        d.reference.update(upd).addOnSuccessListener { ok++; end() }.addOnFailureListener { fail++; end() }
                        // 그룹 이름표가 아직 없으면 같이 (관리자는 쓸 수 있음)
                        if (title.isNotBlank() && made.add(gid)) db.collection("groupNames").document(gid).get().addOnSuccessListener { gn ->
                            if (!gn.exists()) db.collection("groupNames").document(gid)
                                .set(mapOf("name" to title, "img" to (d.getString("gimg") ?: ""), "t" to System.currentTimeMillis()))
                        }
                    }
                    gids.forEach { gid ->
                        db.collection("groups").document(gid).collection("trips").document(d.id).get()
                            .addOnSuccessListener { tr ->
                                if (tr.exists()) {
                                    db.collection("groups").document(gid).get().addOnSuccessListener { g ->
                                        val n = g.getString("name") ?: ""
                                        hits.add(gid to if (n.isBlank()) "" else if (n.trim().endsWith("그룹")) n else "$n 그룹")
                                    }.addOnCompleteListener { gl--; if (gl == 0) pick() }
                                } else { gl--; if (gl == 0) pick() }
                            }
                            .addOnFailureListener { gl--; if (gl == 0) pick() }
                    }
                }.addOnFailureListener { fail++; end() }
            }
        }.addOnFailureListener { done("불러오기 실패: ${it.message}") }
    }
}
