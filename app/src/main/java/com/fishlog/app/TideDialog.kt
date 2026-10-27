package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.ScrollView
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 물때 팝업 (메인 화면 "물때" 버튼) */
object TideDialog {

    fun show(a: Activity, lat: Double, lon: Double, time: Long, trip: Store.Trip?) {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 20), Ui.dp(a, 8))
        }
        val head = Ui.text(a, 16f, Ui.TEXT, true, Tide.mulName(time))
        val info = Ui.text(a, 13f, Ui.SUB, false, "물때 그래프 불러오는 중…")
        val graph = TideView(a)
        col.addView(head)
        col.addView(graph, Ui.lp(a, MATCH, Ui.dp(a, 250), top = 10))
        // 물세기: 그날 % + 시간별 물흐름 세기
        val strength = Ui.text(a, 15f, Ui.TEXT, true, "물세기 계산 중…")
        col.addView(strength, Ui.lp(a, MATCH, WRAP, top = 10))
        val flow = FlowBarView(a)
        col.addView(flow, Ui.lp(a, MATCH, Ui.dp(a, 70), top = 4))
        val flowNote = Ui.text(a, 11f, Ui.SUB, false, "")
        col.addView(flowNote, Ui.lp(a, MATCH, WRAP, top = 2))
        col.addView(info, Ui.lp(a, MATCH, WRAP, top = 8))
        val src = Ui.text(a, 11f, Ui.SUB, false, "")
        col.addView(src, Ui.lp(a, MATCH, WRAP, top = 10))

        val dlg = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(SimpleDateFormat("M월 d일 (E) 물때", Locale.KOREA).format(Date(time)))
            .setView(ScrollView(a).apply { addView(col) })
            .setPositiveButton("닫기", null)
            .show()

        Tide.fetch(a, lat, lon, time) { day, err ->
            if (!dlg.isShowing) return@fetch
            if (day == null) { info.text = err ?: "불러오기 실패"; return@fetch }
            graph.set(day, trip)
            info.text = describe(day, trip)
            Tide.strength(a, day) { st ->
                if (!dlg.isShowing) return@strength
                strength.text = strengthLine(day, st, time)
                flow.set(day, st)
                flowNote.text = "막대 = 시간별 물흐름 세기 (물이 빨리 들고 나는 때일수록 높고 주황) · 초록 테두리 = 지금" +
                    (if (st.estimated) "\n물세기는 물때(달 나이)로 짐작한 값이에요" else "\n물세기 = 오늘 조차 ÷ 가까운 사리(7물) 때 조차")
            }
            src.text = day.source + "\n물때 단계: 달 나이로 계산 (7물때식, 하루 정도 차이 가능)"
        }
    }

    /** "물세기 85% ▰▰▰▰▰▰▰▰▱▱ · 지금 물살 셈 (들물)" */
    fun strengthLine(day: Tide.Day, st: Tide.Strength, time: Long = System.currentTimeMillis()): String {
        val sb = StringBuilder("물세기 ${st.pct}%")
        if (st.estimated) sb.append("(추정)")
        sb.append(" ").append(Tide.strengthBar(st.pct))
        val now = System.currentTimeMillis()
        if (now >= day.dateMillis && now < day.dateMillis + 86_400_000L) {
            val lv = st.levelAt(day, now)
            if (lv >= 0) {
                sb.append(" · 지금 물살 ").append(Tide.levelName(lv))
                Tide.rising(day, now)?.let { sb.append(if (it) " (들물)" else " (날물)") }
            }
        }
        return sb.toString()
    }

    fun describe(day: Tide.Day, trip: Store.Trip?): String {
        val tf = SimpleDateFormat("HH:mm", Locale.KOREA)
        val sb = StringBuilder()
        Tide.extremes(day).forEach { e ->
            sb.append(if (e.high) "만조 " else "간조 ").append(tf.format(Date(e.time)))
                .append(String.format(Locale.KOREA, " (%.0fcm)", e.value * 100)).append("   ")
        }
        if (trip != null && trip.catches.isNotEmpty()) {
            var up1 = 0; var up2 = 0; var dn1 = 0; var dn2 = 0
            trip.catches.forEach { c ->
                when (Tide.rising(day, c.time)) {
                    true -> if (c.slot == 1) up1++ else up2++
                    false -> if (c.slot == 1) dn1++ else dn2++
                    null -> {}
                }
            }
            sb.append("\n들물(들어오는 물): ${trip.name(1)} $up1 · ${trip.name(2)} $up2")
            sb.append("\n날물(빠지는 물): ${trip.name(1)} $dn1 · ${trip.name(2)} $dn2")
        }
        return sb.toString().trim()
    }
}
