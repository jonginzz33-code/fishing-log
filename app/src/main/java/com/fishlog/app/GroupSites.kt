package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import com.google.firebase.firestore.FirebaseFirestore

/**
 * v6.36 (294): 🔖 그룹 사이트 메모 — 그룹원이 같이 보는 사이트 목록 (내 폰 전용 '🌐 자주 가는 사이트'와 따로)
 *  - 저장 groups/{gid}/sites/{id} {name, url, memo, owner, ownerName, t, order}
 *  - 추가 = 그룹원 누구나 · 수정 · 삭제 = 올린 사람 + 그룹장 · 순서 ▲▼ = 그룹장
 *  - 줄마다 [🌐 열기] · [＋ 내 사이트에 담기]
 */
object GroupSites {
    class Site(val id: String, val name: String, val url: String, val memo: String, val owner: String, val ownerName: String, val t: Long, val order: Long)

    private val db get() = FirebaseFirestore.getInstance()
    private fun col(gid: String) = db.collection("groups").document(gid).collection("sites")

    fun show(a: Activity, g: Cloud.Group) {
        fun dp(v: Int) = Ui.dp(a, v)
        val dlg = Dialog(a, R.style.FlFullUp)
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG); setPadding(dp(14), dp(12), dp(14), dp(10)) }
        val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.text(a, 19f, Ui.TEXT, true, "🔖 그룹 사이트 메모"), LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(Ui.text(a, 13f, Ui.TEXT, true, "닫기").apply {
            gravity = Gravity.CENTER; setPadding(dp(14), dp(6), dp(14), dp(6)); background = Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE)
            setOnClickListener { dlg.dismiss() }
        })
        root.addView(top)
        root.addView(Ui.text(a, 12f, Ui.SUB, false, "${g.title} 그룹원이 같이 보는 목록이에요 · 내 폰에만 있는 '🌐 자주 가는 사이트'와 따로예요"), Ui.lp(a, MATCH, WRAP, top = 4))
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(a).apply { addView(box) }, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })
        lateinit var load: () -> Unit
        val isBoss = g.owner == Cloud.uid

        fun et(h: String, v: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(a).apply {
            hint = h; setText(v); setTextColor(Ui.TEXT); setHintTextColor(Ui.SUB); inputType = type
        }
        fun edit(s: Site?, nextOrder: Long) {
            val f = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), 0) }
            val n = et("이름 (예: 헌터호 예약)", s?.name ?: "")
            val u = et("주소 (예: www.OO.com)", s?.url ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            val m = et("메모 (선택 · 예: 쭈갑 · 오천항)", s?.memo ?: "")
            f.addView(n); f.addView(u); f.addView(m)
            AlertDialog.Builder(a, R.style.FlAlert).setTitle(if (s == null) "＋ 그룹 사이트 추가" else "그룹 사이트 수정").setView(f)
                .setPositiveButton("저장") { _, _ ->
                    val nm = n.text.toString().trim().take(40); val url = u.text.toString().trim().take(300)
                    if (nm.isEmpty() || url.isEmpty()) { Ui.toast(a, "이름과 주소를 적어 주세요"); return@setPositiveButton }
                    val me = Cloud.uid ?: return@setPositiveButton
                    val ref = if (s == null) col(g.id).document() else col(g.id).document(s.id)
                    ref.set(mapOf("name" to nm, "url" to url, "memo" to m.text.toString().trim().take(200),
                        "owner" to (s?.owner ?: me), "ownerName" to (s?.ownerName ?: Cloud.displayName),
                        "t" to (s?.t ?: System.currentTimeMillis()), "order" to (s?.order ?: nextOrder)))
                        .addOnSuccessListener { load() }
                        .addOnFailureListener { Ui.toast(a, "저장 실패 (보안 규칙 확인): ${it.message}") }
                }.setNegativeButton("취소", null).show()
        }

        fun render(list: List<Site>) {
            box.removeAllViews()
            if (list.isEmpty()) box.addView(Ui.text(a, 14f, Ui.SUB, false, "아직 없어요 · 아래 '＋ 그룹 사이트 추가'로 예약 사이트 · 선사 · 카페 주소를 같이 모아 보세요"), Ui.lp(a, MATCH, WRAP, top = 8))
            list.forEachIndexed { i, s ->
                val c = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); background = Ui.bg(a, Ui.CARD, 14) }
                c.addView(Ui.text(a, 16f, Ui.TEXT, true, s.name))
                c.addView(Ui.text(a, 12f, Ui.BLUE, false, s.url).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setOnClickListener { Boats.openUrl(a, s.url) } }, Ui.lp(a, MATCH, WRAP, top = 2))
                if (s.memo.isNotBlank()) c.addView(Ui.text(a, 12.5f, Ui.SUB, false, s.memo), Ui.lp(a, MATCH, WRAP, top = 2))
                c.addView(Ui.text(a, 11f, Ui.SUB, false, "올린 사람 ${s.ownerName}"), Ui.lp(a, MATCH, WRAP, top = 4))
                val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                fun b(t: String, col: Int, w: Int, on: () -> Unit) = r.addView(Ui.text(a, 12.5f, col, true, t).apply {
                    gravity = Gravity.CENTER; background = Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE); setOnClickListener { on() }
                }, LinearLayout.LayoutParams(if (w == 0) 0 else dp(w), dp(34), if (w == 0) 1f else 0f).apply { rightMargin = dp(6) })
                b("🌐 열기", Ui.BLUE, 0) { Boats.openUrl(a, s.url) }
                b("＋ 내 사이트에 담기", Ui.TEXT, 0) {
                    Ui.toast(a, if (Boats.addMine(a, Boats.Site(s.name, s.url, "", s.memo))) "내 '🌐 자주 가는 사이트'에 담았어요" else "이미 내 사이트에 있어요")
                }
                if (s.owner == Cloud.uid || isBoss) {
                    b("✎", Ui.TEXT, 38) { edit(s, s.order) }
                    b("🗑", Color.parseColor("#FF8A92"), 38) {
                        AlertDialog.Builder(a, R.style.FlAlert).setMessage("'${s.name}'을(를) 그룹 목록에서 지울까요?")
                            .setPositiveButton("삭제") { _, _ -> col(g.id).document(s.id).delete().addOnSuccessListener { load() }.addOnFailureListener { Ui.toast(a, "삭제 실패: ${it.message}") } }
                            .setNegativeButton("취소", null).show()
                    }
                }
                if (isBoss && list.size > 1) {
                    fun swap(j: Int) {
                        if (j !in list.indices) return
                        val o = list[j]
                        col(g.id).document(s.id).update("order", o.order)
                        col(g.id).document(o.id).update("order", s.order).addOnCompleteListener { load() }
                    }
                    if (i > 0) b("▲", Ui.SUB, 34) { swap(i - 1) }
                    if (i < list.size - 1) b("▼", Ui.SUB, 34) { swap(i + 1) }
                }
                c.addView(r, Ui.lp(a, MATCH, WRAP, top = 8))
                box.addView(c, Ui.lp(a, MATCH, WRAP, top = 8))
            }
            box.addView(Ui.text(a, 14f, Ui.TEXT, true, "＋ 그룹 사이트 추가").apply {
                gravity = Gravity.CENTER; minHeight = dp(46); background = Ui.bg(a, Color.TRANSPARENT, 14, Ui.LINE)
                setOnClickListener { edit(null, (list.maxOfOrNull { it.order } ?: 0L) + 1) }
            }, Ui.lp(a, MATCH, WRAP, top = 12))
            box.addView(Ui.text(a, 11.5f, Ui.SUB, false, "추가는 그룹원 누구나 · 수정 · 삭제는 올린 사람과 그룹장 · 순서는 그룹장").apply { gravity = Gravity.CENTER }, Ui.lp(a, MATCH, WRAP, top = 6))
        }
        load = {
            col(g.id).get().addOnSuccessListener { q ->
                if (!dlg.isShowing) return@addOnSuccessListener
                render(q.documents.map { d ->
                    Site(d.id, d.getString("name") ?: "", d.getString("url") ?: "", d.getString("memo") ?: "",
                        d.getString("owner") ?: "", d.getString("ownerName") ?: "", d.getLong("t") ?: 0L, d.getLong("order") ?: 0L)
                }.sortedWith(compareBy({ it.order }, { it.t })))
            }.addOnFailureListener {
                box.removeAllViews()
                box.addView(Ui.text(a, 13f, Ui.ORANGE, false, "불러오지 못했어요 · 서버 규칙이 아직 게시 전일 수 있어요 (${it.message})"), Ui.lp(a, MATCH, WRAP, top = 8))
            }
        }
        box.addView(Ui.text(a, 13f, Ui.SUB, false, "불러오는 중…"))
        dlg.setContentView(root)
        Ui.edge(dlg.window)
        dlg.show()
        load()
    }
}
