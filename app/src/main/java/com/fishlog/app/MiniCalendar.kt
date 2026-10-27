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
 * 작은 달력: ◀ 2026년 10월 ▶
 *  - 년도를 누르면 년도 목록, 월을 누르면 그 달 전체 선택
 *  - marks[yyyyMMdd] = 그날 표시할 점 색들 (비어 있지 않으면 동그라미로 강조)
 */
class MiniCalendar(private val a: Activity, private val box: LinearLayout) {

    val month: Calendar = Calendar.getInstance()
    var selectedDay: String? = null
    var wholeMonth = true
    var marks: Map<String, List<Int>> = emptyMap()
    var onChange: (() -> Unit)? = null
    var minYear = Calendar.getInstance().get(Calendar.YEAR) - 5

    private val keyFmt = SimpleDateFormat("yyyyMMdd", Locale.KOREA)
    private fun dp(v: Int) = Ui.dp(a, v)

    fun key(c: Calendar): String = keyFmt.format(c.time)

    fun monthKey(): String = SimpleDateFormat("yyyyMM", Locale.KOREA).format(month.time)

    private fun changed() { render(); onChange?.invoke() }

    fun render() {
        box.removeAllViews()
        val nav = Ui.row(a)
        nav.addView(Ui.button(a, "◀") { month.add(Calendar.MONTH, -1); wholeMonth = true; selectedDay = null; changed() }
            .apply { minHeight = dp(44) }, LinearLayout.LayoutParams(dp(52), WRAP))
        val yearBtn = Ui.text(a, 17f, Ui.TEXT, true, "${month.get(Calendar.YEAR)}년 ▾").apply {
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setOnClickListener {
                val now = Calendar.getInstance().get(Calendar.YEAR)
                val years = (now downTo minYear).map { "${it}년" }
                BigList.show(a, "년도 선택", years) { i, _ ->
                    month.set(Calendar.YEAR, now - i); wholeMonth = true; selectedDay = null; changed()
                }
            }
        }
        nav.addView(yearBtn, LinearLayout.LayoutParams(0, WRAP, 1f))
        val monthBtn = Ui.text(a, 17f, if (wholeMonth) Ui.BG else Ui.TEXT, true, "${month.get(Calendar.MONTH) + 1}월").apply {
            gravity = Gravity.CENTER
            minHeight = dp(40)
            setPadding(dp(14), 0, dp(14), 0)
            background = if (wholeMonth) Ui.bg(a, Ui.TEXT, 20) else Ui.bg(a, Color.TRANSPARENT, 20, Ui.LINE)
            setOnClickListener { wholeMonth = true; selectedDay = null; changed() }
        }
        nav.addView(monthBtn, LinearLayout.LayoutParams(WRAP, WRAP))
        nav.addView(View(a), LinearLayout.LayoutParams(dp(8), 1))
        nav.addView(Ui.button(a, "▶") { month.add(Calendar.MONTH, 1); wholeMonth = true; selectedDay = null; changed() }
            .apply { minHeight = dp(44) }, LinearLayout.LayoutParams(dp(52), WRAP))
        box.addView(nav)

        val week = Ui.row(a)
        listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { i, d ->
            week.addView(Ui.text(a, 12f, if (i == 0) Color.parseColor("#F07AA0") else Ui.SUB, false, d).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        box.addView(week, Ui.lp(a, MATCH, WRAP, top = 8))

        val c = month.clone() as Calendar
        c.set(Calendar.DAY_OF_MONTH, 1)
        val lead = c.get(Calendar.DAY_OF_WEEK) - 1
        val days = c.getActualMaximum(Calendar.DAY_OF_MONTH)
        val todayKey = keyFmt.format(java.util.Date())
        var day = 1 - lead
        while (day <= days) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 0 until 7) {
                val cell = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(3), 0, dp(3))
                }
                if (day in 1..days) {
                    c.set(Calendar.DAY_OF_MONTH, day)
                    val key = key(c)
                    val dots = marks[key].orEmpty()
                    val has = dots.isNotEmpty()
                    val sel = key == selectedDay
                    cell.addView(Ui.text(a, 14f, Ui.TEXT, has, day.toString()).apply {
                        gravity = Gravity.CENTER
                        when {
                            sel -> { setTextColor(Ui.BG); background = Ui.bg(a, Ui.TEXT, 99) }
                            has -> background = Ui.bg(a, Color.parseColor("#2B4A5E"), 99, Ui.BLUE)
                            key == todayKey -> background = Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE)
                            else -> setTextColor(Color.parseColor("#5F7A8C"))
                        }
                    }, LinearLayout.LayoutParams(dp(34), dp(34)))
                    val dotRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                    dots.distinct().take(4).forEach { col ->
                        dotRow.addView(View(a).apply { background = Ui.bg(a, col, 99) },
                            LinearLayout.LayoutParams(dp(6), dp(6)).apply { setMargins(dp(1), dp(2), dp(1), 0) })
                    }
                    cell.addView(dotRow, LinearLayout.LayoutParams(WRAP, dp(8)))
                    cell.setOnClickListener { selectedDay = key; wholeMonth = false; changed() }
                }
                row.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f))
                day++
            }
            box.addView(row)
        }
    }
}
