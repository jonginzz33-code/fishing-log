package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 관리자 1:1 문의 (비공개): 문의한 사람과 운영자만 보는 방
 *  - inquiries/{uid}: name, last, t, unreadAdmin, unreadUser
 *  - inquiries/{uid}/msgs/{id}: admin(true/false), text, t
 *  - 운영자가 지우기 전까지 영구 보관
 *  - 운영자: 문의 목록 → 방 열기 · 방 지우기
 */
object Inquiry {
    private val db get() = FirebaseFirestore.getInstance()
    fun ref(uid: String) = db.collection("inquiries").document(uid)

    /** 안 읽은 수: 사용자 = 운영자 답장 수, 운영자 = 새 문의가 있는 방 수 */
    fun unread(done: (Int) -> Unit) {
        val me = Cloud.uid ?: return done(0)
        if (Cloud.isAdmin) db.collection("inquiries").whereGreaterThan("unreadAdmin", 0).get()
            .addOnSuccessListener { done(it.size()) }.addOnFailureListener { done(0) }
        else ref(me).get().addOnSuccessListener { done((it.getLong("unreadUser") ?: 0L).toInt()) }.addOnFailureListener { done(0) }
    }

    fun send(room: String, text: String, done: (String?) -> Unit) {
        val t = text.trim()
        if (t.isEmpty()) return done("내용을 적어 주세요")
        if (t.length > 1000) return done("1000자까지 보낼 수 있어요")
        val admin = Cloud.isAdmin && room != Cloud.uid
        val now = System.currentTimeMillis()
        ref(room).collection("msgs").document().set(mapOf("admin" to admin, "text" to t, "t" to now))
            .addOnSuccessListener {
                val m = hashMapOf<String, Any>("last" to t.take(60), "t" to now)
                if (admin) m["unreadUser"] = FieldValue.increment(1) else { m["unreadAdmin"] = FieldValue.increment(1); m["name"] = Cloud.displayName }
                ref(room).set(m, SetOptions.merge())
                done(null)
            }
            .addOnFailureListener { done(it.message ?: "보내기 실패 (보안 규칙 확인)") }
    }

    fun markRead(room: String) {
        val admin = Cloud.isAdmin && room != Cloud.uid
        ref(room).set(mapOf(if (admin) "unreadAdmin" to 0 else "unreadUser" to 0), SetOptions.merge())
    }

    /** 운영자만: 방 통째로 지우기 */
    fun deleteRoom(room: String, done: (String?) -> Unit) {
        ref(room).collection("msgs").get().addOnSuccessListener { q ->
            q.documents.forEach { it.reference.delete() }
            ref(room).delete().addOnSuccessListener { done(null) }.addOnFailureListener { done(it.message) }
        }.addOnFailureListener { done(it.message) }
    }
}

class InquiryActivity : Activity() {
    private fun dp(v: Int) = Ui.dp(this, v)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private val tf = SimpleDateFormat("yyyy.M.d HH:mm", Locale.KOREA)
    private var room: String? = null
    private var reg: ListenerRegistration? = null
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        window.statusBarColor = Ui.BG; window.navigationBarColor = Ui.BG
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        room = intent.getStringExtra("room") ?: if (Cloud.isAdmin) null else Cloud.uid
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG); setPadding(dp(14), dp(14), dp(14), dp(10)) }
        val top = Ui.row(this)
        top.addView(Ui.button(this, "← 뒤로") { finish() }.apply { minHeight = dp(42) }, LinearLayout.LayoutParams(WRAP, WRAP))
        val title = if (room == null) "1:1 문의함 (운영자)" else if (Cloud.isAdmin && room != Cloud.uid) "1:1 문의 · ${intent.getStringExtra("name") ?: ""}" else "운영자에게 1:1 문의"
        top.addView(Ui.text(this, 19f, Ui.TEXT, true, title).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(12) })
        if (Cloud.isAdmin && room != null && room != Cloud.uid) top.addView(Ui.text(this, 13f, Color.parseColor("#FF8A92"), true, "방 지우기").apply {
            setPadding(dp(8), dp(6), dp(4), dp(6))
            setOnClickListener {
                AlertDialog.Builder(this@InquiryActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage("이 1:1 문의방을 통째로 지울까요? 되돌릴 수 없어요")
                    .setPositiveButton("지우기") { _, _ -> Inquiry.deleteRoom(room!!) { e -> toast(e ?: "지웠어요"); finish() } }
                    .setNegativeButton("취소", null).show()
            }
        })
        root.addView(top)
        root.addView(Ui.text(this, 11.5f, Ui.SUB, false,
            if (room == null) "새 문의가 있는 방은 빨간 숫자 · 눌러서 답장" else "🔒 나와 운영자만 볼 수 있는 비공개 방이에요 · 알림 없음 · 운영자가 지우기 전까지 보관"),
            Ui.lp(this, MATCH, WRAP, top = 8))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(8)) }
        scroll = ScrollView(this).apply { addView(list); isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(6) })
        if (room != null) {
            val bar = Ui.row(this)
            val input = EditText(this).apply {
                hint = "문의 내용을 적어 주세요…"; setHintTextColor(Ui.SUB); setTextColor(Ui.TEXT); textSize = 15f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; maxLines = 5
                background = Ui.bg(this@InquiryActivity, Ui.CARD, 14, Ui.LINE); setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            bar.addView(input, LinearLayout.LayoutParams(0, WRAP, 1f))
            bar.addView(Ui.text(this, 15f, Color.parseColor("#08311F"), true, "보내기").apply {
                gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0)
                background = Ui.bg(this@InquiryActivity, Ui.GREEN, 14)
                setOnClickListener { Inquiry.send(room!!, input.text.toString()) { e -> if (e != null) toast(e) else input.setText("") } }
            }, LinearLayout.LayoutParams(WRAP, dp(46)).apply { leftMargin = dp(8) })
            root.addView(bar, Ui.lp(this, MATCH, WRAP, top = 4))
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val r = room
        if (Cloud.user == null) { list.addView(Ui.text(this, 14f, Ui.SUB, false, "구글 로그인이 필요해요")); return }
        if (r == null) loadRooms() else {
            reg = Inquiry.ref(r).collection("msgs").orderBy("t", Query.Direction.ASCENDING).addSnapshotListener { q, _ ->
                if (q == null || isFinishing) return@addSnapshotListener
                list.removeAllViews()
                if (q.isEmpty) list.addView(Ui.text(this, 13f, Ui.SUB, false, "궁금한 점, 그룹 만들기 권한 요청, 오류 신고 등을 남겨 주세요 🙂").apply { gravity = Gravity.CENTER },
                    Ui.lp(this, MATCH, WRAP, top = 30))
                q.documents.forEach { d ->
                    val fromAdmin = d.getBoolean("admin") == true
                    val mine = if (Cloud.isAdmin && r != Cloud.uid) fromAdmin else !fromAdmin
                    list.addView(bubble(d.getString("text") ?: "", d.getLong("t") ?: 0L, mine, fromAdmin), Ui.lp(this, MATCH, WRAP, top = 10))
                }
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                Inquiry.markRead(r)
            }
        }
    }

    override fun onPause() { reg?.remove(); reg = null; super.onPause() }

    private fun bubble(text: String, t: Long, mine: Boolean, fromAdmin: Boolean): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = if (mine) Gravity.END else Gravity.START }
        col.addView(Ui.text(this, 11f, Ui.SUB, false, (if (fromAdmin) "운영자" else if (mine) "나" else (intent.getStringExtra("name") ?: "문의자")) + " · " + tf.format(Date(t))),
            LinearLayout.LayoutParams(WRAP, WRAP))
        col.addView(Ui.text(this, 14.5f, if (mine) Color.parseColor("#04342C") else Ui.TEXT, false, text).apply {
            setPadding(dp(11), dp(8), dp(11), dp(8)); setLineSpacing(0f, 1.15f)
            maxWidth = (resources.displayMetrics.widthPixels * 0.78).toInt()
            background = Ui.bg(this@InquiryActivity, if (mine) Ui.BLUE else Color.parseColor(if (fromAdmin) "#2A2310" else "#1E3247"), 14)
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) })
        return col
    }

    /** 운영자: 문의 목록 */
    private fun loadRooms() {
        list.removeAllViews()
        list.addView(Ui.text(this, 13f, Ui.SUB, false, "불러오는 중…"))
        FirebaseFirestore.getInstance().collection("inquiries").orderBy("t", Query.Direction.DESCENDING).get()
            .addOnSuccessListener { q ->
                if (isFinishing) return@addOnSuccessListener
                list.removeAllViews()
                if (q.isEmpty) { list.addView(Ui.text(this, 14f, Ui.SUB, false, "아직 문의가 없어요")); return@addOnSuccessListener }
                q.documents.forEach { d ->
                    val n = (d.getLong("unreadAdmin") ?: 0L).toInt()
                    val name = d.getString("name") ?: "회원 ${d.id.takeLast(4)}"
                    val c = Ui.card(this).apply {
                        isClickable = true
                        if (n > 0) background = Ui.bg(this@InquiryActivity, Ui.CARD, 14, Color.parseColor("#E8505B"))
                        setOnClickListener { startActivity(android.content.Intent(this@InquiryActivity, InquiryActivity::class.java).putExtra("room", d.id).putExtra("name", name)) }
                    }
                    val r = Ui.row(this)
                    r.addView(Ui.text(this, 15f, Ui.TEXT, true, name), LinearLayout.LayoutParams(0, WRAP, 1f))
                    if (n > 0) r.addView(Ui.chip(this, "새 글 $n", Color.parseColor("#E8505B")))
                    c.addView(r)
                    c.addView(Ui.text(this, 13f, Ui.SUB, false, (d.getString("last") ?: "") + " · " + tf.format(Date(d.getLong("t") ?: 0L))).apply { maxLines = 2 },
                        Ui.lp(this, MATCH, WRAP, top = 2))
                    list.addView(c, Ui.lp(this, MATCH, WRAP, top = 8))
                }
            }
            .addOnFailureListener { list.removeAllViews(); list.addView(Ui.text(this, 13f, Ui.ORANGE, false, it.message ?: "실패 (보안 규칙 확인)")) }
    }
}
