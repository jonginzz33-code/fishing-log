package com.fishlog.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP

/**
 * 위치 정보 · 개인정보 활용 동의 (구글 플레이 등록 대비)
 *  - 처음 켤 때 한 번: [필수] 위치 정보, [필수] 개인정보 수집·이용, [선택] 같이 낚시 중 위치 공유
 *  - 개인 설정에서 다시 보고, 선택 항목은 언제든 끄고 켤 수 있음
 */
object Consent {
    private const val VER = 1
    private fun p(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)
    fun ok(c: Context) = p(c).getInt("consentVer", 0) >= VER
    /** 같이 낚시 중(실시간 위치·마릿수) 공유 동의 */
    fun shareLive(c: Context) = p(c).getBoolean("consentLive", true)
    fun setShareLive(c: Context, v: Boolean) { p(c).edit().putBoolean("consentLive", v).apply() }

    const val LOCATION = "• 출조 중 이동 경로와 고기 잡은 자리를 기록해요 (지도·포인트 도감).\n" +
        "• 내 위치에서 가까운 물때 관측소·날씨를 찾아요.\n" +
        "• 위치는 폰에 저장되고, 그룹·전체 게시판에 올릴 때만 함께 올라가요 (경로 숨기기·대략 위치 선택 가능)."
    const val PRIVACY = "• 구글 계정(이메일·이름)으로 로그인하고, 다른 사람에게는 닉네임만 보여요.\n" +
        "• 출조 기록·사진·채팅·문의 내용이 서버(Google Firebase)에 저장돼요.\n" +
        "• 기록은 내가 지우거나 탈퇴하면 지워져요. 운영자는 이용 정지·신고 처리를 위해 회원 목록을 볼 수 있어요."
    const val LIVE = "• 같은 그룹 사람이 가까이 출조 중이면 서로의 마릿수와 위치를 실시간으로 보여줘요 (출조 중에만)."

    fun show(a: Activity, readOnly: Boolean = false, onDone: () -> Unit) {
        fun dp(v: Int) = Ui.dp(a, v)
        val dlg = Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(20)); setBackgroundColor(Ui.BG) }
        col.addView(Ui.text(a, 22f, Ui.TEXT, true, "🎣 조과기록 이용 동의"))
        col.addView(Ui.text(a, 13f, Ui.SUB, false, "앱을 쓰려면 아래 내용을 확인하고 동의해 주세요."), Ui.lp(a, MATCH, WRAP, top = 6))
        fun block(title: String, body: String, required: Boolean, checked: Boolean): CheckBox {
            val c = Ui.card(a)
            val cb = CheckBox(a).apply {
                text = (if (required) "[필수] " else "[선택] ") + title
                setTextColor(Ui.TEXT); textSize = 15f; isChecked = checked
                buttonTintList = android.content.res.ColorStateList.valueOf(Ui.GREEN)
                isEnabled = !readOnly || !required
            }
            c.addView(cb)
            c.addView(Ui.text(a, 12.5f, Ui.SUB, false, body).apply { setLineSpacing(0f, 1.3f) }, Ui.lp(a, MATCH, WRAP, top = 4))
            col.addView(c, Ui.lp(a, MATCH, WRAP, top = 12))
            return cb
        }
        val c1 = block("위치 정보 이용 동의", LOCATION, true, readOnly)
        val c2 = block("개인정보 수집·이용 동의", PRIVACY, true, readOnly)
        val c3 = block("같이 낚시 중 위치·마릿수 공유", LIVE, false, shareLive(a))
        val all = CheckBox(a).apply {
            text = "모두 동의"; setTextColor(Ui.TEXT); textSize = 16f
            buttonTintList = android.content.res.ColorStateList.valueOf(Ui.GREEN)
            visibility = if (readOnly) android.view.View.GONE else android.view.View.VISIBLE
            setOnCheckedChangeListener { _, v -> c1.isChecked = v; c2.isChecked = v; c3.isChecked = v }
        }
        col.addView(all, Ui.lp(a, MATCH, WRAP, top = 12))
        val btn = Ui.text(a, 16f, Color.parseColor("#08311F"), true, if (readOnly) "저장하고 닫기" else "동의하고 시작하기").apply {
            gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(14))
            background = Ui.bg(a, Ui.GREEN, 14)
        }
        col.addView(btn, Ui.lp(a, MATCH, WRAP, top = 14))
        if (!readOnly) col.addView(Ui.text(a, 13f, Ui.SUB, false, "동의하지 않고 앱 닫기").apply {
            gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(8)); setOnClickListener { dlg.dismiss(); a.finish() }
        }, Ui.lp(a, MATCH, WRAP))
        btn.setOnClickListener {
            if (!c1.isChecked || !c2.isChecked) { android.widget.Toast.makeText(a, "[필수] 항목에 동의해야 쓸 수 있어요", android.widget.Toast.LENGTH_LONG).show(); return@setOnClickListener }
            p(a).edit().putInt("consentVer", VER).putLong("consentAt", System.currentTimeMillis()).apply()
            setShareLive(a, c3.isChecked)
            dlg.dismiss(); onDone()
        }
        dlg.setCancelable(readOnly)
        dlg.setContentView(ScrollView(a).apply { setBackgroundColor(Ui.BG); addView(col) })
        dlg.show()
    }
}
