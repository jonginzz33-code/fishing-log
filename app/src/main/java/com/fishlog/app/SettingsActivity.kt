package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 개인 설정 (⚙): 잠금 화면·AOD 표시, 친구 돌파 알림, 닉네임 */
class SettingsActivity : Activity() {

    private lateinit var box: LinearLayout
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
        top.addView(Ui.text(this, 20f, Ui.TEXT, true, "개인 설정"), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(14) })
        col.addView(top)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(box, Ui.lp(this, MATCH, WRAP, top = 6))
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(col) })
        if (intent.getBooleanExtra("askNick", false)) toast("다른 사람에게 보일 닉네임을 먼저 정해 주세요")
    }

    override fun onResume() { super.onResume(); render() }

    private fun section(t: String) = box.addView(Ui.text(this, 12f, Ui.SUB, false, t), Ui.lp(this, MATCH, WRAP, top = 18))

    /** 켜기/끄기 스위치 줄 */
    private fun switchRow(title: String, sub: String, on: Boolean, toggle: (Boolean) -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Ui.bg(this@SettingsActivity, Ui.CARD, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(Ui.text(this, 15f, Ui.TEXT, true, title))
        t.addView(Ui.text(this, 12f, Ui.SUB, false, sub), Ui.lp(this, MATCH, WRAP, top = 2))
        r.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        val sw = android.widget.Switch(this).apply {
            isChecked = on
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Ui.GREEN, Ui.LINE))
            setOnCheckedChangeListener { _, c -> toggle(c) }
        }
        r.addView(sw, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(10) })
        r.setOnClickListener { sw.isChecked = !sw.isChecked }
        box.addView(r, Ui.lp(this, MATCH, WRAP, top = 6))
    }

    private fun render() {
        box.removeAllViews()
        section("화면 표시")
        switchRow("꺼진 화면(AOD)·잠금 화면에 표시", "출조 중 주꾸미 아이콘, 마릿수, +1 버튼을 잠금 해제 없이 보여줘요",
            Store.lockScreenShow) { Store.lockScreenShow = it; if (Store.current != null) RemoteService.start(this) }
        section("알림")
        switchRow("같이 낚시 중 친구 돌파 알림", "50m 안에서 같이 출조 중인 그룹원이 10·30·50… 마리를 넘으면 알려줘요",
            Store.friendAlerts) { Store.friendAlerts = it }

        section("닉네임")
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.bg(this@SettingsActivity, Ui.CARD, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        box.addView(card, Ui.lp(this, MATCH, WRAP, top = 6))
        if (Cloud.user == null) { card.addView(Ui.text(this, 14f, Ui.SUB, false, "로그인하면 닉네임을 정할 수 있어요")); return }
        val n = Cloud.nick
        if (n == null) {
            // 처음 정하기
            val input = EditText(this).apply {
                hint = "닉네임 입력 (2~10자)"; inputType = InputType.TYPE_CLASS_TEXT; setSingleLine()
                setTextColor(Ui.TEXT); setHintTextColor(Ui.SUB)
            }
            card.addView(input)
            card.addView(Ui.text(this, 12f, Color.parseColor("#F2C14E"), false,
                "한 번 정하면 바꿀 때 관리자 승인이 필요해요 · 다른 사람과 같은 이름은 안 돼요\n구글 계정 실명 대신 이 이름이 그룹·게시판·같이 낚시 중에 보여요"),
                Ui.lp(this, MATCH, WRAP, top = 6))
            card.addView(Ui.button(this, "이 닉네임으로 정하기", true) {
                val v = input.text.toString().trim()
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("'$v'(으)로 정할까요?")
                    .setMessage("한 번 정하면 바꿀 때 관리자 승인이 필요해요")
                    .setPositiveButton("정하기") { _, _ -> Cloud.setNick(v) { err -> toast(err ?: "닉네임을 '$v'(으)로 정했어요"); render() } }
                    .setNegativeButton("취소", null).show()
            }.apply { minHeight = dp(46) }, Ui.lp(this, MATCH, WRAP, top = 10))
            return
        }
        val row = Ui.row(this)
        row.addView(Ui.text(this, 20f, Ui.TEXT, true, n))
        row.addView(Ui.text(this, 11f, Ui.SUB, false, "🔒 고정됨").apply {
            setPadding(dp(8), dp(2), dp(8), dp(2)); background = Ui.bg(this@SettingsActivity, Color.TRANSPARENT, 99, Ui.LINE)
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
        card.addView(row)
        card.addView(Ui.text(this, 12f, Ui.SUB, false, "전체 게시판 · 그룹 · 같이 낚시 중에 이 이름이 보여요"), Ui.lp(this, MATCH, WRAP, top = 4))
        val reqBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(reqBox, Ui.lp(this, MATCH, WRAP, top = 8))
        if (Cloud.isAdmin) {
            reqBox.addView(Ui.button(this, "닉네임 바로 바꾸기 (관리자)") { askNew(true) }.apply { minHeight = dp(44); textSize = 14f })
            return
        }
        Cloud.myNickRequest { r ->
            if (isFinishing) return@myNickRequest
            reqBox.removeAllViews()
            val df = SimpleDateFormat("M/d HH:mm", Locale.KOREA)
            when (r?.status) {
                "wait" -> {
                    reqBox.addView(Ui.text(this, 12f, Ui.TEXT, false,
                        "승인 대기 중 · ${r.old} → ${r.new} (${df.format(Date(r.time))} 요청)\n관리자가 승인하면 바뀌어요").apply {
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                        background = Ui.bg(this@SettingsActivity, Color.argb(30, 242, 193, 78), 10, Color.parseColor("#F2C14E"))
                    })
                    reqBox.addView(Ui.button(this, "요청 취소") { Cloud.cancelNickRequest { e -> toast(e ?: "요청을 취소했어요"); render() } }
                        .apply { minHeight = dp(40); textSize = 13f }, Ui.lp(this, MATCH, WRAP, top = 6))
                }
                "rejected" -> {
                    reqBox.addView(Ui.text(this, 12f, Ui.SUB, false, "'${r.new}'(으)로 변경 요청이 거절됐어요"))
                    reqBox.addView(Ui.button(this, "다시 요청하기") { Cloud.cancelNickRequest { askNew(false) } }
                        .apply { minHeight = dp(40); textSize = 13f }, Ui.lp(this, MATCH, WRAP, top = 6))
                }
                else -> reqBox.addView(Ui.button(this, "닉네임 변경 요청") { askNew(false) }.apply { minHeight = dp(44); textSize = 14f })
            }
        }
    }

    private fun askNew(admin: Boolean) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), 0) }
        val nn = EditText(this).apply { hint = "새 닉네임 (2~10자)"; inputType = InputType.TYPE_CLASS_TEXT; setSingleLine() }
        val why = EditText(this).apply { hint = "바꾸는 이유 (선택)"; inputType = InputType.TYPE_CLASS_TEXT }
        col.addView(nn); if (!admin) col.addView(why)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (admin) "닉네임 바꾸기" else "닉네임 변경 요청")
            .setMessage(if (admin) null else "관리자가 승인하면 바뀌어요")
            .setView(col)
            .setPositiveButton(if (admin) "바꾸기" else "요청 보내기") { _, _ ->
                val v = nn.text.toString()
                if (admin) Cloud.setNick(v) { e -> toast(e ?: "닉네임을 바꿨어요"); render() }
                else Cloud.requestNick(v, why.text.toString().trim()) { e -> toast(e ?: "변경 요청을 보냈어요. 승인되면 바뀌어요"); render() }
            }
            .setNegativeButton("취소", null).show()
    }
}
