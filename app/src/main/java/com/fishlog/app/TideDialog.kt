package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 물때 팝업 (메인 화면 "물때" 버튼): 날짜를 골라서 물때·물세기·바람·파도·수온 */
object TideDialog {

    private val titleFmt = SimpleDateFormat("M월 d일 (E)", Locale.KOREA)
    private val hFmt = SimpleDateFormat("HH:mm", Locale.KOREA)

    private fun dayStart(time: Long): Long = Calendar.getInstance().apply {
        timeInMillis = time; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun show(a: Activity, lat: Double, lon: Double, time: Long, trip: Store.Trip?) {
        fun dp(v: Int) = Ui.dp(a, v)
        var cur = dayStart(time)
        val today = dayStart(System.currentTimeMillis())
        var loadId = 0

        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }

        // ---- 날짜 고르기: ◀ 날짜 ▶ 오늘 ----
        val nav = Ui.row(a)
        fun navBtn(label: String) = Ui.text(a, 16f, Ui.TEXT, true, label).apply {
            gravity = Gravity.CENTER; background = Ui.bg(a, Ui.CARD, 12, Ui.LINE); isClickable = true
        }
        val prev = navBtn("◀")
        val dateText = navBtn("")
        val next = navBtn("▶")
        val todayBtn = navBtn("오늘").apply { textSize = 13f }
        nav.addView(prev, LinearLayout.LayoutParams(dp(44), dp(44)))
        nav.addView(dateText, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(6) })
        nav.addView(next, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(6) })
        nav.addView(todayBtn, LinearLayout.LayoutParams(dp(56), dp(44)).apply { leftMargin = dp(6) })
        col.addView(nav)

        val head = Ui.text(a, 16f, Ui.TEXT, true, "")
        col.addView(head, Ui.lp(a, MATCH, WRAP, top = 10))
        val graph = TideView(a)
        col.addView(graph, Ui.lp(a, MATCH, dp(250), top = 8))
        // 물세기: 그날 % + 시간별 물흐름 세기
        val strength = Ui.text(a, 15f, Ui.TEXT, true, "")
        col.addView(strength, Ui.lp(a, MATCH, WRAP, top = 10))
        val flow = FlowBarView(a)
        col.addView(flow, Ui.lp(a, MATCH, dp(70), top = 4))
        val flowNote = Ui.text(a, 11f, Ui.SUB, false, "")
        col.addView(flowNote, Ui.lp(a, MATCH, WRAP, top = 2))
        val info = Ui.text(a, 13f, Ui.SUB, false, "")
        col.addView(info, Ui.lp(a, MATCH, WRAP, top = 8))

        // ---- 바람 · 파도 · 수온 ----
        col.addView(Ui.text(a, 15f, Ui.TEXT, true, "바람 · 파도 · 수온"), Ui.lp(a, MATCH, WRAP, top = 18))
        val wxSummary = Ui.text(a, 14f, Ui.TEXT, false, "")
        col.addView(wxSummary, Ui.lp(a, MATCH, WRAP, top = 4))
        val wxGrid = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(wxGrid, Ui.lp(a, MATCH, WRAP, top = 6))
        val wxNote = Ui.text(a, 11f, Ui.SUB, false, "")
        col.addView(wxNote, Ui.lp(a, MATCH, WRAP, top = 4))

        val src = Ui.text(a, 11f, Ui.SUB, false, "")
        col.addView(src, Ui.lp(a, MATCH, WRAP, top = 12))

        val dlg = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("물때 · 바람 · 수온")
            .setView(ScrollView(a).apply { addView(col) })
            .setPositiveButton("닫기", null)
            .show()

        fun renderWeather(w: Weather.Day) {
            wxGrid.removeAllViews()
            val hrs = w.hours
            if (hrs.isEmpty()) {
                wxSummary.text = w.windNote.ifBlank { w.seaNote }.ifBlank { "바람·파도 정보가 없어요" }
            } else {
                val parts = mutableListOf<String>()
                hrs.filter { it.wind != null }.maxByOrNull { it.wind!! }?.let {
                    parts.add(String.format(Locale.KOREA, "바람 최대 %.0fm/s (%s시)", it.wind, hFmt.format(Date(it.time)).take(2)))
                }
                hrs.mapNotNull { it.wave }.maxOrNull()?.let { parts.add(String.format(Locale.KOREA, "파고 최대 %.1fm", it)) }
                hrs.mapNotNull { it.sst }.takeIf { it.isNotEmpty() }?.let { parts.add(String.format(Locale.KOREA, "수온 약 %.0f℃ (예측)", it.average())) }
                wxSummary.text = parts.joinToString(" · ")
                // 3시간마다: 시각 / 바람 방향 화살표 / 풍속 / 파고
                for (h in 0 until 24 step 3) {
                    val x = w.at(h)
                    val c = LinearLayout(a).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(0, dp(6), 0, dp(6))
                        background = Ui.bg(a, Ui.CARD, 10)
                    }
                    c.addView(Ui.text(a, 11f, Ui.SUB, false, "${h}시").apply { gravity = Gravity.CENTER })
                    val ws = x?.wind
                    val lvColor = when (ws?.let { Weather.windLevel(it) }) { 0 -> Ui.GREEN; 1 -> Color.parseColor("#F2C14E"); 2 -> Ui.ORANGE; else -> Ui.SUB }
                    // 화살표 = 바람이 불어가는 쪽
                    c.addView(Ui.text(a, 18f, lvColor, true, if (x?.dir != null) "↑" else "·").apply {
                        gravity = Gravity.CENTER
                        x?.dir?.let { rotation = (it.toFloat() + 180f) % 360f }
                    })
                    c.addView(Ui.text(a, 12f, lvColor, true, ws?.let { String.format(Locale.KOREA, "%.0f", it) } ?: "-").apply { gravity = Gravity.CENTER })
                    c.addView(Ui.text(a, 10f, Ui.SUB, false, x?.dir?.let { LiveMe.directionName(it) } ?: "").apply { gravity = Gravity.CENTER })
                    c.addView(Ui.text(a, 11f, Ui.BLUE, false, x?.wave?.let { String.format(Locale.KOREA, "%.1fm", it) } ?: "-").apply { gravity = Gravity.CENTER })
                    wxGrid.addView(c, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (h > 0) leftMargin = dp(3) })
                }
            }
            val notes = mutableListOf<String>()
            w.measured?.let { notes.add(String.format(Locale.KOREA, "실측 수온 %.1f℃ · %s 관측소 %s", it.temp, it.station, it.at)) }
            if (w.measuredNote.isNotBlank()) notes.add(w.measuredNote)
            if (hrs.isNotEmpty()) {
                notes.add("칸 = 3시간마다 · 화살표 = 바람이 부는 쪽 · 숫자 = 풍속(m/s, 초록 약함 · 노랑 주의 · 주황 강함) · 파랑 = 파고")
                if (w.windNote.isNotBlank()) notes.add(w.windNote)
                if (w.seaNote.isNotBlank()) notes.add(w.seaNote)
                notes.add("바람·파도·수온 예측: Open-Meteo")
            }
            wxNote.text = notes.joinToString("\n")
            if (w.measured != null) wxSummary.text = wxSummary.text.toString().let { t ->
                (if (t.isBlank()) "" else "$t · ") + String.format(Locale.KOREA, "실측 수온 %.1f℃", w.measured.temp)
            }
        }

        fun load() {
            val id = ++loadId
            val noon = cur + 12 * 3_600_000L
            dateText.text = titleFmt.format(Date(cur)) + if (cur == today) " · 오늘" else ""
            head.text = Tide.mulName(noon)
            info.text = "물때 불러오는 중…"
            strength.text = "물세기 계산 중…"
            flowNote.text = ""; src.text = ""
            wxSummary.text = "바람·파도·수온 불러오는 중…"; wxGrid.removeAllViews(); wxNote.text = ""
            // 그날 출조한 기록이면 그래프에 조과 점
            val t = trip?.takeIf { dayStart(it.start) == cur }
            Tide.fetch(a, lat, lon, noon) { day, err ->
                if (!dlg.isShowing || id != loadId) return@fetch
                if (day == null) { info.text = err ?: "불러오기 실패"; strength.text = ""; return@fetch }
                graph.set(day, t)
                info.text = describe(day, t)
                src.text = day.source + "\n물때 단계: 실제 그믐 시각으로 계산 (서해 7물때식)"
                Tide.strength(a, day) { st ->
                    if (!dlg.isShowing || id != loadId) return@strength
                    strength.text = strengthLine(day, st)
                    flow.set(day, st)
                    flowNote.text = "막대 = 시간별 물흐름 세기 (물이 빨리 들고 나는 때일수록 높고 주황)" +
                        (if (cur == today) " · 초록 테두리 = 지금" else "") +
                        (if (st.estimated) "\n물세기는 물때(달 나이)로 짐작한 값이에요" else "\n물세기 = 그날 조차 ÷ 가까운 사리(7물) 때 조차")
                }
            }
            Weather.fetch(a, lat, lon, cur) { w -> if (dlg.isShowing && id == loadId) renderWeather(w) }
        }

        prev.setOnClickListener { cur = dayStart(cur - 12 * 3_600_000L); load() }
        next.setOnClickListener { cur = dayStart(cur + 36 * 3_600_000L); load() }
        todayBtn.setOnClickListener { cur = today; load() }
        dateText.setOnClickListener {
            val c = Calendar.getInstance().apply { timeInMillis = cur }
            DatePickerDialog(a, android.R.style.Theme_DeviceDefault_Dialog_Alert, { _, y, m, d ->
                cur = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                load()
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }
        load()
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
