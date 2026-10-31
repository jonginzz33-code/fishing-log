package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 휴지통: 다른 사람(운영자·그룹장)이 내 조과를 지우면
 *  - 지우기 전에 trash/{id} 에 통째로 보관 (30일) + 올린 사람에게 알림 (notify)
 *  - 올린 사람은 휴지통에서 30일 안에 복구, 30일이 지나면 자동으로 버림
 *  - trash/{id}: owner, kind(public/group), gid, path, data, by, byName, byRole, label, t, exp
 *  - trash/{id}/items/{n}: 하위 문서(경로·사진·상세) 보관
 */
object Trash {
    private const val KEEP_MS = 30L * 24 * 3_600_000L
    private val db get() = FirebaseFirestore.getInstance()
    private val df = SimpleDateFormat("yyyy.M.d", Locale.KOREA)

    class Item(val id: String, val owner: String, val kind: String, val gid: String, val path: String,
               val label: String, val by: String, val byName: String, val byRole: String, val time: Long, val exp: Long) {
        val daysLeft get() = maxOf(0L, (exp - System.currentTimeMillis() + 86_399_999L) / 86_400_000L)
    }

    /** 남의 글을 지우기 전에 보관 + 알림. 끝나면 done (실패해도 지우기는 계속하도록 null/메시지) */
    fun backup(ref: DocumentReference, owner: String, kind: String, gid: String, subs: List<String>, done: (String?) -> Unit) {
        val me = Cloud.uid ?: return done("로그인이 필요해요")
        if (owner == me) return done(null)
        ref.get().addOnSuccessListener { d ->
            if (!d.exists()) return@addOnSuccessListener done(null)
            val data = d.data ?: emptyMap<String, Any>()
            val start = (data["start"] as? Long) ?: 0L
            val where = if (kind == "public") "전체 게시판" else (Cloud.groups.firstOrNull { it.id == gid }?.name ?: "그룹") + " 그룹"
            val label = (if (start > 0) df.format(Date(start)) + " 출조" else "출조 기록") + " · $where"
            val role = if (Cloud.isAdmin) "운영자" else "그룹장"
            val now = System.currentTimeMillis()
            val tRef = db.collection("trash").document()
            tRef.set(mapOf("owner" to owner, "kind" to kind, "gid" to gid, "path" to ref.path, "data" to data,
                "by" to me, "byName" to Cloud.displayName, "byRole" to role, "label" to label, "t" to now, "exp" to now + KEEP_MS))
                .addOnSuccessListener {
                    // 하위 문서도 같이 보관 (경로·작은 사진)
                    subs.forEach { sub ->
                        ref.collection(sub).get().addOnSuccessListener { q ->
                            q.documents.forEach { s ->
                                tRef.collection("items").document().set(mapOf("sub" to sub, "id" to s.id, "data" to (s.data ?: emptyMap<String, Any>())))
                            }
                        }
                    }
                    db.collection("notify").document().set(mapOf("to" to owner, "t" to now, "trash" to tRef.id,
                        "text" to "$label 조과는 ${Cloud.displayName}($role)에 의해 삭제되었습니다.\n휴지통에서 30일 안에 복구할 수 있어요 (☰ 메뉴 → 🗑 휴지통)."))
                    done(null)
                }
                .addOnFailureListener { done(null) }
        }.addOnFailureListener { done(null) }
    }

    // ---------------- 내가 직접 지운 내 조과 (폰에 30일 보관) ----------------
    class Mine(val id: Long, val label: String, val time: Long, val json: String, val gids: Set<String>, val pub: Boolean) {
        val daysLeft get() = maxOf(0L, (time + KEEP_MS - System.currentTimeMillis() + 86_399_999L) / 86_400_000L)
    }
    private fun minePrefs() = Cloud.appContext.getSharedPreferences("my_trash", android.content.Context.MODE_PRIVATE)

    /** 지우기 전에: 출조 기록 통째로 + 올렸던 그룹·게시판 기억 */
    fun keepMine(trips: List<Store.Trip>) {
        val e = minePrefs().edit()
        val now = System.currentTimeMillis()
        trips.forEach { t ->
            val o = org.json.JSONObject().put("trip", Store.tripToJson(t)).put("t", now).put("pub", t.pub)
                .put("gids", org.json.JSONArray().apply { Store.sharedGroups(t.id).forEach { put(it) } })
                .put("label", df.format(Date(t.start)) + " 출조 · " + t.line())
            e.putString(t.id.toString(), o.toString())
        }
        e.apply()
    }

    fun mine(): List<Mine> {
        val now = System.currentTimeMillis()
        val p = minePrefs()
        val out = ArrayList<Mine>()
        p.all.forEach { (k, v) ->
            try {
                val o = org.json.JSONObject(v as String)
                val t = o.getLong("t")
                if (now - t > KEEP_MS) { p.edit().remove(k).apply(); return@forEach }   // 30일 지나면 자동으로 버림
                val g = o.optJSONArray("gids")
                out.add(Mine(k.toLong(), o.optString("label"), t, o.getJSONObject("trip").toString(),
                    (0 until (g?.length() ?: 0)).map { g!!.getString(it) }.toSet(), o.optBoolean("pub")))
            } catch (_: Exception) { p.edit().remove(k).apply() }
        }
        return out.sortedByDescending { it.time }
    }

    /** 복구: 폰 기록 되살리고, 올렸던 그룹에 다시 올리고, 게시판이었으면 다시 공개 */
    fun restoreMine(a: Activity, m: Mine, done: (String) -> Unit) {
        val t = try { Store.tripFromJson(org.json.JSONObject(m.json)) } catch (_: Exception) { return done("복구 실패: 기록을 못 읽었어요") }
        t.uploaded = false; t.backedUp = false; t.pub = false
        Store.mergeRestored(listOf(t))
        minePrefs().edit().remove(m.id.toString()).apply()
        Cloud.backupPending()
        val gs = Cloud.groups.filter { it.id in m.gids }
        gs.forEach { g -> Cloud.uploadTrip(t, g) { e -> if (e == null) Store.addShared(t.id, g.id) } }
        if (m.pub) PublicBoard.publishFlow(a, t) { e -> if (e != null) Toast.makeText(a, "게시판 다시 공개: $e", Toast.LENGTH_LONG).show() }
        done("복구했어요" + (if (gs.isNotEmpty()) " · 그룹 ${gs.size}곳에 다시 올려요" else "") + (if (m.pub) " · 전체 게시판에도 다시 공개해요" else ""))
    }

    fun purgeMine(m: Mine) { minePrefs().edit().remove(m.id.toString()).apply() }

    fun list(done: (List<Item>?, String?) -> Unit) {
        val me = Cloud.uid ?: return done(null, "로그인이 필요해요")
        db.collection("trash").whereEqualTo("owner", me).get()
            .addOnSuccessListener { q ->
                val now = System.currentTimeMillis()
                val all = q.documents.map { d ->
                    Item(d.id, d.getString("owner") ?: "", d.getString("kind") ?: "", d.getString("gid") ?: "", d.getString("path") ?: "",
                        d.getString("label") ?: "출조 기록", d.getString("by") ?: "", d.getString("byName") ?: "?", d.getString("byRole") ?: "",
                        d.getLong("t") ?: 0L, d.getLong("exp") ?: 0L)
                }
                // 30일 지난 건 자동으로 버림
                all.filter { it.exp in 1 until now }.forEach { purge(it) { } }
                done(all.filter { it.exp >= now }.sortedByDescending { it.time }, null)
            }
            .addOnFailureListener { done(null, it.message ?: "불러오기 실패 (보안 규칙 확인)") }
    }

    /** 복구: 원래 자리에 다시 쓰고 휴지통에서 지움 */
    fun restore(it: Item, done: (String?) -> Unit) {
        val tRef = db.collection("trash").document(it.id)
        tRef.get().addOnSuccessListener { d ->
            @Suppress("UNCHECKED_CAST")
            val data = d.get("data") as? Map<String, Any> ?: return@addOnSuccessListener done("보관된 내용이 없어요")
            val ref = db.document(it.path)
            ref.set(data).addOnSuccessListener {
                tRef.collection("items").get().addOnSuccessListener { q ->
                    q.documents.forEach { s ->
                        @Suppress("UNCHECKED_CAST")
                        val sd = s.get("data") as? Map<String, Any> ?: return@forEach
                        ref.collection(s.getString("sub") ?: return@forEach).document(s.getString("id") ?: return@forEach).set(sd)
                        s.reference.delete()
                    }
                    tRef.delete()
                    Cloud.invalidateAll()
                    PublicBoard.clearCache()
                    done(null)
                }
            }.addOnFailureListener { e ->
                done(if (it.kind == "group") "복구 실패: 그 그룹에서 나갔으면 복구할 수 없어요 (${e.message ?: ""})" else "복구 실패: ${e.message ?: ""}")
            }
        }.addOnFailureListener { done(it.message ?: "복구 실패") }
    }

    fun purge(it: Item, done: (String?) -> Unit) {
        val tRef = db.collection("trash").document(it.id)
        tRef.collection("items").get().addOnCompleteListener { q ->
            q.result?.documents?.forEach { s -> s.reference.delete() }
            tRef.delete().addOnSuccessListener { done(null) }.addOnFailureListener { done(it.message ?: "실패") }
        }
    }

    /** 앱을 켤 때: 내 조과가 지워졌다는 알림을 한 번 보여 주고 지움 */
    fun checkNotices(a: Activity) {
        val me = Cloud.uid ?: return
        db.collection("notify").whereEqualTo("to", me).get().addOnSuccessListener { q ->
            if (q.isEmpty || a.isFinishing) return@addOnSuccessListener
            val text = q.documents.sortedBy { it.getLong("t") ?: 0L }.joinToString("\n\n") { it.getString("text") ?: "" }
            AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("🗑 내 조과가 삭제되었어요")
                .setMessage(text)
                .setPositiveButton("휴지통 보기") { _, _ -> show(a) }
                .setNegativeButton("확인", null)
                .show()
            q.documents.forEach { it.reference.delete() }
        }
    }

    /** 휴지통 창: 복구 · 바로 버리기, 남은 날 */
    fun show(a: Activity) {
        fun dp(v: Int) = Ui.dp(a, v)
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(6)) }
        col.addView(Ui.text(a, 12f, Ui.SUB, false, "지운 조과는 30일 동안 여기 있어요. 복구하면 출조 기록·그룹·전체 게시판까지 원래대로, 30일이 지나면 자동으로 버려져요."))
        // 내가 지운 것 (폰에 보관)
        val mineBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(mineBox, Ui.lp(a, MATCH, WRAP, top = 8))
        col.addView(Ui.text(a, 14f, Ui.TEXT, true, "다른 사람(운영자·그룹장)이 지운 내 조과"), Ui.lp(a, MATCH, WRAP, top = 14))
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(box, Ui.lp(a, MATCH, WRAP, top = 8))
        val dlg = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("🗑 휴지통")
            .setView(ScrollView(a).apply { addView(col) })
            .setPositiveButton("닫기", null)
            .create()
        lateinit var drawMine: () -> Unit
        drawMine = {
            mineBox.removeAllViews()
            val ms = mine()
            mineBox.addView(Ui.text(a, 14f, Ui.TEXT, true, "내가 지운 조과 (${ms.size})"))
            if (ms.isEmpty()) mineBox.addView(Ui.text(a, 13f, Ui.SUB, false, "없어요"), Ui.lp(a, MATCH, WRAP, top = 4))
            ms.forEach { m ->
                val c = Ui.card(a)
                c.addView(Ui.text(a, 14f, Ui.TEXT, true, m.label))
                c.addView(Ui.text(a, 12f, Ui.SUB, false, "${df.format(Date(m.time))}에 지움 · ${m.daysLeft}일 남음" +
                    (if (m.gids.isNotEmpty()) " · 그룹 ${m.gids.size}곳" else "") + (if (m.pub) " · 전체 게시판" else "")), Ui.lp(a, MATCH, WRAP, top = 2))
                val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                r.addView(Ui.button(a, "복구", true) {
                    restoreMine(a, m) { msg -> Toast.makeText(a, msg, Toast.LENGTH_LONG).show(); drawMine() }
                }.apply { minHeight = dp(40) }, LinearLayout.LayoutParams(0, WRAP, 1f))
                r.addView(Ui.button(a, "영구 삭제") {
                    AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setMessage("완전히 버릴까요? 되돌릴 수 없어요")
                        .setPositiveButton("영구 삭제") { _, _ -> purgeMine(m); drawMine() }
                        .setNegativeButton("취소", null).show()
                }.apply { minHeight = dp(40); setTextColor(android.graphics.Color.parseColor("#FF8A92")) }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
                c.addView(r, Ui.lp(a, MATCH, WRAP, top = 8))
                mineBox.addView(c, Ui.lp(a, MATCH, WRAP, top = 8))
            }
        }
        drawMine()
        fun load() {
            box.removeAllViews()
            box.addView(Ui.text(a, 13f, Ui.SUB, false, "불러오는 중…"))
            list { items, err ->
                if (!dlg.isShowing) return@list
                box.removeAllViews()
                if (items == null) { box.addView(Ui.text(a, 13f, Ui.ORANGE, false, err ?: "실패")); return@list }
                if (items.isEmpty()) { box.addView(Ui.text(a, 13f, Ui.SUB, false, "없어요")); return@list }
                items.forEach { it ->
                    val c = Ui.card(a)
                    c.addView(Ui.text(a, 14f, Ui.TEXT, true, it.label))
                    c.addView(Ui.text(a, 12f, Ui.SUB, false,
                        "${it.byName}(${it.byRole})이 ${df.format(Date(it.time))}에 삭제 · ${it.daysLeft}일 남음"), Ui.lp(a, MATCH, WRAP, top = 2))
                    val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                    r.addView(Ui.button(a, "복구", true) {
                        restore(it) { e -> Toast.makeText(a, e ?: "복구했어요", Toast.LENGTH_LONG).show(); load() }
                    }.apply { minHeight = dp(40) }, LinearLayout.LayoutParams(0, WRAP, 1f))
                    r.addView(Ui.button(a, "바로 버리기") {
                        AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setMessage("완전히 버릴까요? 되돌릴 수 없어요")
                            .setPositiveButton("버리기") { _, _ -> purge(it) { load() } }
                            .setNegativeButton("취소", null).show()
                    }.apply { minHeight = dp(40) }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
                    c.addView(r, Ui.lp(a, MATCH, WRAP, top = 8))
                    box.addView(c, Ui.lp(a, MATCH, WRAP, top = 8))
                }
            }
        }
        dlg.show()
        load()
    }
}
