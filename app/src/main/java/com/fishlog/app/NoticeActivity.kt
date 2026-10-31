package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 그룹 공지: 그룹장만 쓰기·고치기·지우기, 그룹원은 보기. 열면 새 공지는 확인한 걸로 */
class NoticeActivity : Activity() {

    private lateinit var box: LinearLayout
    private val df = SimpleDateFormat("M/d(E) HH:mm", Locale.KOREA)
    private fun dp(v: Int) = Ui.dp(this, v)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        val col = Ui.column(this)
        val top = Ui.row(this)
        top.addView(Ui.button(this, "← 뒤로") { finish() }.apply { minHeight = dp(44) }, LinearLayout.LayoutParams(WRAP, WRAP))
        val g = Cloud.active
        top.addView(Ui.text(this, 20f, Ui.TEXT, true, (g?.let { groupLabel(it) } ?: "그룹") + " 공지"),
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(14) })
        col.addView(top)
        GroupBand.view(this, false)?.let { col.addView(it, Ui.lp(this, MATCH, WRAP, top = 12)) }
        if (Cloud.isOwner) col.addView(Ui.button(this, "+ 공지 쓰기", true) { edit(null) }.apply { minHeight = dp(46) },
            Ui.lp(this, MATCH, WRAP, top = 12))
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(box, Ui.lp(this, MATCH, WRAP, top = 6))
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(col) })
    }

    override fun onResume() { super.onResume(); load() }

    private fun groupLabel(g: Cloud.Group) = g.title

    private fun load() {
        val g = Cloud.active ?: run { box.removeAllViews(); box.addView(Ui.text(this, 14f, Ui.SUB, false, "그룹에 가입하면 공지를 볼 수 있어요")); return }
        box.removeAllViews()
        box.addView(Ui.text(this, 13f, Ui.SUB, false, "불러오는 중…"), Ui.lp(this, MATCH, WRAP, top = 10))
        Cloud.loadNoticesOf(g) { list, err ->
            if (isFinishing) return@loadNoticesOf
            if (list != null) { GroupNews.putNotices(g.id, list); GroupNews.markKindSeen(g.id, "notice") }
            render(g, list, err)
        }
    }

    private fun render(g: Cloud.Group, list: List<Cloud.Notice>?, err: String?) {
        box.removeAllViews()
        // 예전 한 줄 공지
        if (g.notice.isNotBlank()) {
            box.addView(Ui.text(this, 14f, Color.parseColor("#FFE2C2"), true, "📌 ${g.notice}").apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = Ui.bg(this@NoticeActivity, Color.parseColor("#2A2310"), 12, Ui.ORANGE)
            }, Ui.lp(this, MATCH, WRAP, top = 10))
        }
        if (list == null) { box.addView(Ui.text(this, 13f, Ui.ORANGE, false, err ?: "불러오기 실패"), Ui.lp(this, MATCH, WRAP, top = 10)); return }
        if (list.isEmpty()) {
            box.addView(Ui.text(this, 14f, Ui.SUB, false,
                if (Cloud.isOwner) "아직 공지가 없어요 · '+ 공지 쓰기'로 그룹원 모두에게 알려 보세요" else "아직 공지가 없어요"),
                Ui.lp(this, MATCH, WRAP, top = 12))
            return
        }
        list.forEach { n ->
            val c = Ui.card(this)
            c.background = Ui.bg(this, Ui.CARD, 14, Color.parseColor("#F2C14E"))
            val head = Ui.row(this)
            head.addView(Ui.text(this, 11f, Color.parseColor("#3A2A02"), true, "공지").apply {
                setPadding(dp(6), dp(2), dp(6), dp(2)); background = Ui.bg(this@NoticeActivity, Color.parseColor("#F2C14E"), 6)
            })
            head.addView(Ui.text(this, 16f, Ui.TEXT, true, n.title), LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
            c.addView(head)
            c.addView(Ui.text(this, 11f, Ui.SUB, false, n.ownerName + " · " + df.format(Date(n.created)) +
                (if (n.edited > n.created + 60_000L) " · 고침 " + df.format(Date(n.edited)) else "")), Ui.lp(this, MATCH, WRAP, top = 4))
            if (n.body.isNotBlank()) c.addView(Ui.text(this, 14f, Ui.TEXT, false, n.body).apply { setLineSpacing(0f, 1.3f); setTextIsSelectable(true) },
                Ui.lp(this, MATCH, WRAP, top = 8))
            if (Cloud.isOwner) {
                val r = Ui.row(this)
                r.addView(Ui.button(this, "고치기") { edit(n) }.apply { minHeight = dp(38); textSize = 13f }, LinearLayout.LayoutParams(0, WRAP, 1f))
                r.addView(Ui.button(this, "지우기") {
                    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("이 공지를 지울까요?")
                        .setPositiveButton("지우기") { _, _ -> Cloud.deleteNotice(n.id) { e -> toast(e ?: "지웠어요"); load() } }
                        .setNegativeButton("취소", null).show()
                }.apply { minHeight = dp(38); textSize = 13f }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
                c.addView(r, Ui.lp(this, MATCH, WRAP, top = 10))
            }
            box.addView(c, Ui.lp(this, MATCH, WRAP, top = 10))
        }
    }

    private fun edit(old: Cloud.Notice?) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), 0) }
        val title = EditText(this).apply { hint = "제목"; inputType = InputType.TYPE_CLASS_TEXT; setSingleLine(); setText(old?.title ?: "") }
        val body = EditText(this).apply {
            hint = "내용 (선택)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3; setText(old?.body ?: "")
        }
        col.addView(title); col.addView(body)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (old == null) "공지 쓰기" else "공지 고치기")
            .setMessage("그룹원 모두에게 빨간 숫자로 새 소식이 떠요")
            .setView(col)
            .setPositiveButton("올리기") { _, _ ->
                Cloud.saveNotice(old, title.text.toString(), body.text.toString()) { e -> toast(e ?: "공지를 올렸어요"); load() }
            }
            .setNegativeButton("취소", null).show()
    }
}
