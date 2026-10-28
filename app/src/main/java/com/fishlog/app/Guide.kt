package com.fishlog.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP

/**
 * 처음 쓰는 사람용 안내: 앱 화면을 본뜬 그림 + 짧은 설명, 넘겨 보기
 *  - 로그인 뒤 처음 한 번 자동으로, 메뉴·설명서에서 "처음 안내 다시 보기"
 */
object Guide {

    class Page(val image: Int, val title: String, val body: String)

    val PAGES = listOf(
        Page(R.drawable.guide_g1_start, "1. 출조 시작 → 카드 눌러 +1",
            "'출조 시작'을 누르고 배·항구를 적어요.\n잡을 때마다 주황·파란 카드를 누르면 +1 (리모컨·워치 버튼도 돼요).\n✎를 누르면 어종을 바꿔요."),
        Page(R.drawable.guide_g2_tide, "2. 물때 · 날씨 보기",
            "'물때 보기 ›'를 누르면 물높이 그래프, 만조·간조, 일출·일몰이 나와요.\n아래 '시간별 / 주간 (7일)' 탭으로 날씨·바람·파도·수온과 물세기 %를 봐요."),
        Page(R.drawable.guide_g3_group, "3. 그룹 · 공지 · 일정",
            "맨 아래 그룹 버튼을 누르면 큰 창이 열려요.\n빨간 숫자 = 아직 안 본 새 소식. 공지·일정·그룹 출조 기록을 확인하고, 그룹·낚시 종류를 바꿔요.\n출조 전날엔 '내일 출조!'로 알려줘요."),
        Page(R.drawable.guide_g7_live, "4. 같이 낚시 중 (실시간)",
            "같은 그룹 사람이 1km 안에서 출조 중이면 카드 아래와 지도에 그 사람 마릿수·위치가 보여요.\n출조를 끝낸 사람은 1시간 동안 '출조를 종료했어요'로 보여요."),
        Page(R.drawable.guide_g4_end, "5. 출조 종료 → 기록 확인",
            "출조 중엔 버튼이 빨간 '출조 종료'로 바뀌어요.\n끝내면 기록 화면에서 이용료·준비물을 바로 적고, 시간대별로 몇 마리·사진 몇 장인지 봐요."),
        Page(R.drawable.guide_g5_menu, "6. 메뉴",
            "☰ 메뉴: 개인 설정(닉네임·알림·같이 낚시 거리), 사용 설명서, 지난 출조, 전체 조황·포인트 도감.\n맨 위 💬 채팅: 전체방과 내 그룹방에서 실시간 대화 (알림 없음).\n새 버전이 나오면 큰 창으로 알려줘요.")
    )

    /** 처음 안내 (넘겨 보기). onDone = 다 봤을 때 */
    fun show(a: Activity, onDone: (() -> Unit)? = null) {
        if (a.isFinishing) return
        fun dp(v: Int) = Ui.dp(a, v)
        val dlg = Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG); setPadding(dp(16), dp(16), dp(16), dp(14)) }
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val step = Ui.text(a, 13f, Ui.SUB, true, "")
        head.addView(step, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(Ui.text(a, 14f, Ui.SUB, true, "건너뛰기").apply {
            setPadding(dp(10), dp(6), dp(4), dp(6))
            setOnClickListener { dlg.dismiss() }
        })
        root.addView(head)
        val img = ImageView(a).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
        val title = Ui.text(a, 20f, Ui.TEXT, true, "")
        val body = Ui.text(a, 15f, Ui.TEXT, false, "").apply { setLineSpacing(0f, 1.4f) }
        val content = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        content.addView(img, LinearLayout.LayoutParams(MATCH, WRAP))
        content.addView(title, Ui.lp(a, MATCH, WRAP, top = 14))
        content.addView(body, Ui.lp(a, MATCH, WRAP, top = 6))
        val scroll = ScrollView(a).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })
        val dots = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        root.addView(dots, Ui.lp(a, MATCH, WRAP, top = 10))
        val nav = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val prev = Ui.button(a, "이전")
        val next = Ui.button(a, "다음", true)
        nav.addView(prev, LinearLayout.LayoutParams(0, WRAP, 1f))
        nav.addView(next, LinearLayout.LayoutParams(0, WRAP, 1.6f).apply { leftMargin = dp(10) })
        root.addView(nav, Ui.lp(a, MATCH, WRAP, top = 10))

        var i = 0
        fun paint() {
            val p = PAGES[i]
            step.text = "처음 안내  ${i + 1} / ${PAGES.size}"
            img.setImageResource(p.image)
            title.text = p.title
            body.text = p.body
            scroll.scrollTo(0, 0)
            prev.visibility = if (i == 0) View.INVISIBLE else View.VISIBLE
            next.text = if (i == PAGES.size - 1) "시작하기" else "다음"
            dots.removeAllViews()
            PAGES.indices.forEach { k ->
                dots.addView(View(a).apply { background = Ui.bg(a, if (k == i) Ui.GREEN else Ui.LINE, 99) },
                    LinearLayout.LayoutParams(dp(if (k == i) 18 else 8), dp(8)).apply { leftMargin = dp(3); rightMargin = dp(3) })
            }
        }
        prev.setOnClickListener { if (i > 0) { i--; paint() } }
        next.setOnClickListener { if (i < PAGES.size - 1) { i++; paint() } else dlg.dismiss() }
        dlg.setOnDismissListener { Store.guideSeen = true; onDone?.invoke() }
        dlg.setContentView(root)
        paint()
        dlg.show()
    }

    /** 설명서 안 그림 한 장 (폭에 맞춰) */
    fun image(a: Activity, res: Int): View = ImageView(a).apply {
        adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
        setImageResource(res)
        background = Ui.bg(a, Color.parseColor("#0B1622"), 14)
        clipToOutline = true
    }
}
