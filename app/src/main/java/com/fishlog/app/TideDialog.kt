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
            src.text = day.source + "\n물때 단계: 달 나이로 계산 (7물때식, 하루 정도 차이 가능)"
        }
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
