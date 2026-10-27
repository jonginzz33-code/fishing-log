package com.fishlog.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import org.json.JSONObject

/**
 * 차단 · 신고 누적 · 이용 정지
 *  users/{uid}.blocked            : 내가 차단한 사람 {uid: {l: 설명, t: 시각}} (나만 봄)
 *  reports/{target}.n             : 그 사람을 차단한 사람 수 (모두가 봄)
 *  reports/{target}/by/{blocker}  : 누가 차단했는지 (본인·관리자만 봄)
 *  config/moderation.reportLimit  : 이 수 이상 차단되면 모두의 집계에서 빠짐 (관리자가 정함)
 *  bans/{uid}                     : 관리자 이용 정지 (계정)
 *  bannedDevices/{기기 ID}        : 관리자 이용 정지 (폰)
 */
object Moderation {

    private val db get() = FirebaseFirestore.getInstance()
    const val DEFAULT_LIMIT = 3

    // ---------------- 기기 ID ----------------
    @SuppressLint("HardwareIds")
    fun deviceId(ctx: Context): String =
        try { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "" } catch (_: Exception) { "" }

    private fun prefs() = Cloud.appContext.getSharedPreferences("moderation", Context.MODE_PRIVATE)

    // ---------------- 내가 차단한 사람 ----------------
    class Blocked(val uid: String, val label: String, val time: Long)

    fun blocked(): List<Blocked> {
        val o = try { JSONObject(prefs().getString("blocked", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        return o.keys().asSequence().map { k ->
            val v = o.optJSONObject(k)
            Blocked(k, v?.optString("l") ?: "", v?.optLong("t") ?: 0L)
        }.sortedByDescending { it.time }.toList()
    }

    fun isBlocked(uid: String): Boolean =
        try { JSONObject(prefs().getString("blocked", "{}") ?: "{}").has(uid) } catch (_: Exception) { false }

    private fun saveLocal(list: List<Blocked>) {
        val o = JSONObject()
        list.forEach { b -> o.put(b.uid, JSONObject().put("l", b.label).put("t", b.time)) }
        prefs().edit().putString("blocked", o.toString()).apply()
    }

    /** 서버에 저장된 내 차단 목록을 폰으로 (폰을 바꿔도 유지) */
    fun syncBlocked(done: (() -> Unit)? = null) {
        val me = Cloud.uid ?: run { done?.invoke(); return }
        db.collection("users").document(me).get()
            .addOnSuccessListener { d ->
                @Suppress("UNCHECKED_CAST")
                val m = d.get("blocked") as? Map<String, Any?>
                if (m != null) {
                    saveLocal(m.mapNotNull { (k, v) ->
                        val vm = v as? Map<*, *> ?: return@mapNotNull null
                        Blocked(k, vm["l"] as? String ?: "", (vm["t"] as? Number)?.toLong() ?: 0L)
                    })
                }
                done?.invoke()
            }
            .addOnFailureListener { done?.invoke() }
    }

    /** 차단: 내 화면·계산에서 빼고, 신고 누적 1 올림 */
    fun block(target: String, label: String, done: (String?) -> Unit) {
        val me = Cloud.uid ?: return done("로그인이 필요해요")
        if (target == me) return done("내 글은 차단할 수 없어요")
        val now = System.currentTimeMillis()
        saveLocal(blocked().filter { it.uid != target } + Blocked(target, label, now))
        db.collection("users").document(me)
            .set(mapOf("blocked" to mapOf(target to mapOf("l" to label, "t" to now))), SetOptions.merge())
        val rRef = db.collection("reports").document(target)
        val byRef = rRef.collection("by").document(me)
        db.runTransaction<Long> { tr ->
            val by = tr.get(byRef)
            if (!by.exists()) {
                val r = tr.get(rRef)
                val n = if (r.exists()) (r.getLong("n") ?: 0L) else 0L
                tr.set(rRef, mapOf("n" to n + 1, "updated" to now))
                tr.set(byRef, mapOf("t" to now))
            }
            0L
        }
            .addOnSuccessListener { done(null) }
            .addOnFailureListener { done(null) } // 신고 수는 실패해도 내 차단은 그대로 적용
    }

    /** 차단 해제: 다시 내 화면·계산에 들어오고, 신고 누적 1 내림 */
    fun unblock(target: String, done: (String?) -> Unit) {
        val me = Cloud.uid ?: return done("로그인이 필요해요")
        saveLocal(blocked().filter { it.uid != target })
        db.collection("users").document(me)
            .set(mapOf("blocked" to mapOf(target to FieldValue.delete())), SetOptions.merge())
        val rRef = db.collection("reports").document(target)
        val byRef = rRef.collection("by").document(me)
        db.runTransaction<Long> { tr ->
            val by = tr.get(byRef)
            if (by.exists()) {
                val r = tr.get(rRef)
                val n = if (r.exists()) (r.getLong("n") ?: 1L) else 1L
                tr.set(rRef, mapOf("n" to n - 1, "updated" to System.currentTimeMillis()))
                tr.delete(byRef)
            }
            0L
        }
            .addOnSuccessListener { done(null) }
            .addOnFailureListener { done(null) }
    }

    // ---------------- 신고 누적 ----------------
    var reportLimit: Int
        get() = prefs().getInt("limit", DEFAULT_LIMIT)
        private set(v) { prefs().edit().putInt("limit", v).apply() }

    fun loadLimit(done: ((Int) -> Unit)? = null) {
        db.collection("config").document("moderation").get()
            .addOnSuccessListener { d ->
                val v = d.getLong("reportLimit")?.toInt()
                if (v != null && v >= 1) reportLimit = v
                done?.invoke(reportLimit)
            }
            .addOnFailureListener { done?.invoke(reportLimit) }
    }

    fun saveLimit(v: Int, done: (String?) -> Unit) {
        if (!Cloud.isAdmin) return done("관리자만 바꿀 수 있어요")
        if (v < 1) return done("1 이상 숫자를 적어 주세요")
        db.collection("config").document("moderation").set(mapOf("reportLimit" to v), SetOptions.merge())
            .addOnSuccessListener { reportLimit = v; done(null) }
            .addOnFailureListener { done(it.message ?: "저장 실패 (보안 규칙 확인)") }
    }

    /** 사람마다 차단당한 수 */
    fun reportCounts(done: (Map<String, Int>) -> Unit) {
        db.collection("reports").whereGreaterThan("n", 0).get()
            .addOnSuccessListener { q -> done(q.documents.associate { it.id to (it.getLong("n") ?: 0L).toInt() }) }
            .addOnFailureListener { done(emptyMap()) }
    }

    /** 관리자: 신고 초기화 (차단한 사람들의 개인 차단은 그대로) */
    fun resetReports(target: String, done: (String?) -> Unit) {
        val rRef = db.collection("reports").document(target)
        rRef.collection("by").get()
            .addOnSuccessListener { q ->
                val b = db.batch()
                q.documents.forEach { b.delete(it.reference) }
                b.delete(rRef)
                b.commit().addOnSuccessListener { done(null) }.addOnFailureListener { done(it.message ?: "초기화 실패") }
            }
            .addOnFailureListener { done(it.message ?: "초기화 실패 (보안 규칙 확인)") }
    }

    // ---------------- 이용 정지 (관리자) ----------------
    class Ban(val uid: String, val name: String, val reason: String, val time: Long, val device: String)

    fun loadBans(done: (List<Ban>?, String?) -> Unit) {
        db.collection("bans").get()
            .addOnSuccessListener { q ->
                done(q.documents.map { d ->
                    Ban(d.id, d.getString("name") ?: "?", d.getString("reason") ?: "", d.getLong("t") ?: 0L, d.getString("device") ?: "")
                }.sortedByDescending { it.time }, null)
            }
            .addOnFailureListener { done(null, it.message ?: "불러오기 실패 (보안 규칙 확인)") }
    }

    /** 정지된 계정 uid 목록 (전체 조황에서 빼기용) */
    fun bannedUids(done: (Set<String>) -> Unit) {
        db.collection("bans").get()
            .addOnSuccessListener { q -> done(q.documents.map { it.id }.toSet()) }
            .addOnFailureListener { done(emptySet()) }
    }

    /** 계정 + 폰을 같이 정지. extra = 같은 폰으로 쓴 다른 계정 */
    fun ban(uid: String, name: String, device: String, reason: String, extra: List<Pair<String, String>>, done: (String?) -> Unit) {
        if (!Cloud.isAdmin) return done("관리자만 할 수 있어요")
        if (uid == Cloud.uid) return done("관리자 자신은 정지할 수 없어요")
        val now = System.currentTimeMillis()
        val b = db.batch()
        b.set(db.collection("bans").document(uid), mapOf("name" to name, "reason" to reason, "t" to now, "device" to device))
        extra.forEach { (u, n) ->
            if (u != Cloud.uid) b.set(db.collection("bans").document(u),
                mapOf("name" to n, "reason" to "같은 폰 · $reason".trim(), "t" to now, "device" to device))
        }
        if (device.isNotBlank()) b.set(db.collection("bannedDevices").document(device), mapOf("uid" to uid, "name" to name, "t" to now))
        b.commit().addOnSuccessListener { done(null) }.addOnFailureListener { done(it.message ?: "정지 실패 (보안 규칙 확인)") }
    }

    /** 정지 해제: 계정 + 같은 폰으로 묶인 계정 + 폰 */
    fun unban(uids: List<String>, device: String, done: (String?) -> Unit) {
        if (!Cloud.isAdmin) return done("관리자만 할 수 있어요")
        val b = db.batch()
        uids.distinct().forEach { b.delete(db.collection("bans").document(it)) }
        if (device.isNotBlank()) b.delete(db.collection("bannedDevices").document(device))
        b.commit().addOnSuccessListener { done(null) }.addOnFailureListener { done(it.message ?: "해제 실패 (보안 규칙 확인)") }
    }

    // ---------------- 내가 정지됐는지 ----------------
    /** 마지막으로 확인한 결과 (인터넷이 없어도 막히게) */
    val bannedCached: Boolean get() = prefs().getBoolean("banned", false)
    val banReason: String get() = prefs().getString("banReason", "") ?: ""

    fun checkBan(ctx: Context, done: (Boolean) -> Unit) {
        val dev = deviceId(ctx)
        fun finish(banned: Boolean, reason: String) {
            prefs().edit().putBoolean("banned", banned).putString("banReason", reason).apply()
            done(banned)
        }
        fun checkAccount() {
            val me = Cloud.uid
            if (me == null || Cloud.isAdmin) { finish(false, ""); return }
            db.collection("bans").document(me).get()
                .addOnSuccessListener { d -> finish(d.exists(), d.getString("reason") ?: "") }
                .addOnFailureListener { done(bannedCached) }
        }
        if (dev.isBlank() || Cloud.isAdmin) { checkAccount(); return }
        db.collection("bannedDevices").document(dev).get()
            .addOnSuccessListener { d -> if (d.exists()) finish(true, "") else checkAccount() }
            .addOnFailureListener { checkAccount() }
    }

    /** 정지된 사람에게만 보이는 화면 (다른 화면으로 못 넘어감) */
    fun showBlockedScreen(a: Activity) {
        val col = Ui.column(a).apply { gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(Ui.text(a, 22f, Ui.TEXT, true, "이용이 제한된 계정입니다").apply { gravity = Gravity.CENTER },
            Ui.lp(a, MATCH, WRAP, top = 120))
        col.addView(Ui.text(a, 15f, Ui.SUB, false,
            "관리자에 의해 이 계정(또는 이 폰)의 앱 이용이 정지되었어요.\n잘못된 정지라고 생각되면 관리자에게 문의해 주세요.").apply {
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.3f)
        }, Ui.lp(a, MATCH, WRAP, top = 16))
        if (banReason.isNotBlank()) col.addView(Ui.text(a, 14f, Ui.ORANGE, false, "사유: $banReason").apply { gravity = Gravity.CENTER },
            Ui.lp(a, MATCH, WRAP, top = 12))
        col.addView(Ui.button(a, "다시 확인") {
            checkBan(a) { banned -> if (!banned) a.recreate() }
        }, Ui.lp(a, MATCH, WRAP, top = 28))
        a.setContentView(ScrollView(a).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(col) })
    }

    // ---------------- 차단 목록 화면 ----------------
    fun showBlockedList(a: Activity, onChange: () -> Unit) {
        val list = blocked()
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(a, 16), Ui.dp(a, 4), Ui.dp(a, 16), Ui.dp(a, 4)) }
        val dlg = android.app.AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("내가 차단한 사람 ${list.size}명")
            .setView(ScrollView(a).apply { addView(col) })
            .setPositiveButton("닫기", null)
            .create()
        if (list.isEmpty()) col.addView(Ui.text(a, 14f, Ui.SUB, false,
            "차단한 사람이 없어요.\n공개 출조를 눌러 '이 사람 차단'을 하면 그 사람 글이 내 조황·포인트 계산에서 빠져요."),
            Ui.lp(a, MATCH, WRAP, top = 8))
        val df = java.text.SimpleDateFormat("yy.M.d", java.util.Locale.KOREA)
        list.forEach { b ->
            val card = Ui.card(a)
            val r = Ui.row(a)
            r.addView(Ui.text(a, 14f, Ui.TEXT, false, b.label.ifBlank { "익명" } +
                (if (b.time > 0) "\n" + df.format(java.util.Date(b.time)) + " 차단" else "")),
                LinearLayout.LayoutParams(0, WRAP, 1f))
            r.addView(Ui.button(a, "해제") {
                android.app.AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("차단을 해제할까요?")
                    .setMessage("이 사람이 올린 출조가 다시 내 조황·포인트 계산에 들어가요")
                    .setPositiveButton("해제") { _, _ ->
                        unblock(b.uid) { _ -> dlg.dismiss(); onChange(); android.widget.Toast.makeText(a, "차단을 해제했어요", android.widget.Toast.LENGTH_SHORT).show() }
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }.apply { minHeight = Ui.dp(a, 40); textSize = 14f }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = Ui.dp(a, 8) })
            card.addView(r)
            col.addView(card, Ui.lp(a, MATCH, WRAP, top = 8))
        }
        dlg.show()
    }
}
