package com.fishlog.app

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * v6.81 (375): 📅 날짜 고르기 = 우리 앱 달력 시트 (폰 기본 달력 대신)
 *  빠른 버튼 [오늘 · 내일 · 이번 주말 · 다음 사리] · ‹ 달 › · 날짜 아래 물때 이름(사리 주황 · 조금 무렵 보라)
 *  날짜를 누르면 바로 그 날로 (확인 없음) · ⚠ 물때 계산은 안 바꿈 (Tide.mulIndex · 이름만)
 */
object DatePick {
    private val SARI = Color.parseColor("#F2A65A")
    private val JOGUM = Color.parseColor("#B58CF0")

    private fun dayStart(t: Long) = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    fun mulShort(t: Long): String { val i = Tide.mulIndex(t + 12 * 3_600_000L); return when (i) { 0 -> "무시"; 14 -> "조금"; else -> "${i}물" } }
    fun mulCol(t: Long): Int { val i = Tide.mulIndex(t + 12 * 3_600_000L); return when (i) { in 5..9 -> SARI; 0, 12, 13, 14 -> JOGUM; else -> Ui.SUB } }

    fun show(a: Activity, cur: Long, onPick: (Long) -> Unit) {
        fun dp(v: Int) = Ui.dp(a, v)
        val sh = Sheet(a, 0.78f)
        val today = dayStart(System.currentTimeMillis())
        val view = Calendar.getInstance().apply { timeInMillis = cur; set(Calendar.DAY_OF_MONTH, 1) }
        sh.head.addView(Ui.text(a, 17f, Ui.TEXT, true, "📅 날짜 고르기"), LinearLayout.LayoutParams(0, WRAP, 1f))
        sh.head.addView(Ui.pill(a, "닫기", false, Ui.TEXT, small = true) { sh.close() })
        fun pick(t: Long) { sh.close(); onPick(dayStart(t)) }

        fun draw() {
            sh.body.removeAllViews()
            // 빠른 버튼
            val quick = Ui.row(a)
            fun q(t: String, at: Long) = quick.addView(Ui.pill(a, t, false, Ui.TEXT, small = true) { pick(at) }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(5) })
            q("오늘", today)
            q("내일", today + 86_400_000L)
            val sat = Calendar.getInstance().apply { timeInMillis = today; while (get(Calendar.DAY_OF_WEEK) != Calendar.SATURDAY) add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
            q("이번 주말", sat)
            var sari = today + 86_400_000L
            for (i in 1..20) { val t = today + i * 86_400_000L; if (Tide.mulIndex(t + 12 * 3_600_000L) == 7) { sari = t; break } }
            q("다음 사리", sari)
            sh.body.addView(quick, Ui.lp(a, MATCH, WRAP))
            // 달 이동
            val nav = Ui.row(a).apply { setPadding(0, dp(12), 0, dp(6)) }
            nav.addView(Ui.pill(a, "‹", false, Ui.TEXT, small = true) { view.add(Calendar.MONTH, -1); draw() }, LinearLayout.LayoutParams(dp(48), WRAP))
            nav.addView(Ui.text(a, 16.5f, Ui.TEXT, true, SimpleDateFormat("yyyy년 M월", Locale.KOREA).format(view.time)).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, WRAP, 1f))
            nav.addView(Ui.pill(a, "›", false, Ui.TEXT, small = true) { view.add(Calendar.MONTH, 1); draw() }, LinearLayout.LayoutParams(dp(48), WRAP))
            sh.body.addView(nav)
            val wk = Ui.row(a)
            arrayOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { i, t ->
                wk.addView(Ui.text(a, 12.5f, when (i) { 0 -> Color.parseColor("#FF8A8A"); 6 -> Color.parseColor("#7FB8FF"); else -> Ui.SUB }, true, t).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            sh.body.addView(wk)
            val c = view.clone() as Calendar
            val lead = c.get(Calendar.DAY_OF_WEEK) - 1
            val days = c.getActualMaximum(Calendar.DAY_OF_MONTH)
            val sel = dayStart(cur)
            var cell = 0
            var row = Ui.row(a)
            fun flush() { sh.body.addView(row, Ui.lp(a, MATCH, WRAP, top = 4)); row = Ui.row(a) }
            repeat(lead) { row.addView(View(a), LinearLayout.LayoutParams(0, dp(54), 1f)); cell++ }
            for (dn in 1..days) {
                c.set(Calendar.DAY_OF_MONTH, dn)
                val t = dayStart(c.timeInMillis)
                val on = t == sel
                val dow = c.get(Calendar.DAY_OF_WEEK)
                val box = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    background = when { on -> Ui.bg(a, Ui.GROUP, 14, Color.parseColor("#7FD6FF")); t == today -> Ui.bg(a, Color.TRANSPARENT, 14, Color.parseColor("#F2C14E")); else -> null }
                    isClickable = true; setOnClickListener { pick(t) }
                }
                box.addView(Ui.text(a, 16f, when { on -> Color.WHITE; dow == Calendar.SUNDAY -> Color.parseColor("#FF8A8A"); dow == Calendar.SATURDAY -> Color.parseColor("#7FB8FF"); else -> Ui.TEXT },
                    true, "$dn").apply { gravity = Gravity.CENTER; includeFontPadding = false })
                box.addView(Ui.text(a, 10f, if (on) Color.WHITE else mulCol(t), false, mulShort(t)).apply { gravity = Gravity.CENTER })
                row.addView(box, LinearLayout.LayoutParams(0, dp(54), 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
                if (++cell % 7 == 0) flush()
            }
            if (cell % 7 != 0) { repeat(7 - cell % 7) { row.addView(View(a), LinearLayout.LayoutParams(0, dp(54), 1f)) }; flush() }
            // 범례 + 고른 날
            val leg = Ui.row(a).apply { setPadding(dp(4), dp(10), 0, 0) }
            fun dot(cl: Int, t: String) { leg.addView(View(a).apply { background = Ui.bg(a, cl, 99) }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(4) }); leg.addView(Ui.text(a, 11.5f, Ui.SUB, false, "$t   ")) }
            dot(SARI, "사리 (5~9물)"); dot(JOGUM, "조금 무렵"); dot(Color.parseColor("#F2C14E"), "오늘")
            sh.body.addView(leg)
            sh.body.addView(Ui.text(a, 13f, Ui.TEXT, true, "고른 날 · " + SimpleDateFormat("M월 d일 (E)", Locale.KOREA).format(sel) + " · " + mulShort(sel) +
                " · 물세기 약 ${Tide.estimatePct(sel + 12 * 3_600_000L)}%").apply { setPadding(dp(4), dp(8), 0, 0) })
        }
        draw(); sh.show()
    }
}
