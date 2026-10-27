package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 그룹 일정: 몇 월 며칠 어디로 가는지. 그룹원 누구나 올리고 모두 같은 내용을 봄 */
class ScheduleActivity : Activity() {

    private lateinit var listBox: LinearLayout
    private var events: List<Cloud.Event> = emptyList()
    private var showPast = false
    private val keyFmt = SimpleDateFormat("yyyyMMdd", Locale.KOREA)
    private val niceFmt = SimpleDateFormat("M월 d일 (E)", Locale.KOREA)

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
        top.addView(Ui.text(this, 20f, Ui.TEXT, true, "그룹 일정"), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(14) })
        col.addView(top)
        col.addView(Ui.text(this, 14f, Ui.SUB, false, Cloud.active?.let { "'${it.name}' 그룹 · 그룹원 누구나 일정을 올리고 같이 볼 수 있어요" } ?: ""),
            Ui.lp(this, MATCH, WRAP, top = 10))
        col.addView(Ui.button(this, "+ 일정 올리기", true) { editEvent(null) }, Ui.lp(this, MATCH, WRAP, top = 14))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox, Ui.lp(this, MATCH, WRAP, top = 6))
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(col) })
    }

    override fun onResume() { super.onResume(); load() }

    private fun load() {
        if (Cloud.user == null || Cloud.active == null) {
            listBox.removeAllViews()
            listBox.addView(Ui.text(this, 14f, Ui.SUB, false, "로그인하고 그룹에 가입하면 일정을 볼 수 있어요"), Ui.lp(this, MATCH, WRAP, top = 12))
            return
        }
        Cloud.loadEvents { list, err ->
            if (list == null) { toast(err ?: "불러오기 실패"); return@loadEvents }
            events = list
            render()
        }
    }

    private fun render() {
        listBox.removeAllViews()
        val today = keyFmt.format(Date())
        val upcoming = events.filter { it.date >= today }
        val past = events.filter { it.date < today }.reversed()
        listBox.addView(Ui.text(this, 16f, Ui.TEXT, true, "다가오는 일정 ${upcoming.size}개"), Ui.lp(this, MATCH, WRAP, top = 18))
        if (upcoming.isEmpty()) listBox.addView(Ui.text(this, 14f, Ui.SUB, false, "아직 올라온 일정이 없어요"), Ui.lp(this, MATCH, WRAP, top = 8))
        upcoming.forEach { listBox.addView(card(it, today), Ui.lp(this, MATCH, WRAP, top = 8)) }
        if (past.isNotEmpty()) {
            listBox.addView(Ui.button(this, if (showPast) "지난 일정 접기 ▲" else "지난 일정 ${past.size}개 보기 ▼") {
                showPast = !showPast; render()
            }.apply { minHeight = dp(44); textSize = 14f }, Ui.lp(this, MATCH, WRAP, top = 18))
            if (showPast) past.forEach { listBox.addView(card(it, today), Ui.lp(this, MATCH, WRAP, top = 8)) }
        }
    }

    private fun dateText(e: Cloud.Event): String =
        try { niceFmt.format(keyFmt.parse(e.date)!!) } catch (_: Exception) { e.date }

    private fun dday(e: Cloud.Event, today: String): String = try {
        val d = ((keyFmt.parse(e.date)!!.time - keyFmt.parse(today)!!.time) / 86_400_000L).toInt()
        when { d == 0 -> "오늘"; d > 0 -> "D-$d"; else -> "지남" }
    } catch (_: Exception) { "" }

    private fun card(e: Cloud.Event, today: String): LinearLayout {
        val c = Ui.card(this).apply { isClickable = true; setOnClickListener { detail(e) } }
        val r = Ui.row(this)
        r.addView(Ui.text(this, 16f, Ui.TEXT, true, dateText(e) + (if (e.time.isNotBlank()) " ${e.time}" else "")),
            LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(Ui.text(this, 13f, Ui.BG, true, dday(e, today)).apply {
            setPadding(dp(10), dp(3), dp(10), dp(3))
            background = Ui.bg(this@ScheduleActivity, if (e.date >= today) Ui.GREEN else Ui.SUB, 99)
        })
        c.addView(r)
        val where = listOf(Store.catShort(e.cat), e.place, e.boat).filter { it.isNotBlank() }.joinToString(" · ")
        if (where.isNotBlank()) c.addView(Ui.text(this, 14f, Ui.TEXT, false, where), Ui.lp(this, MATCH, WRAP, top = 4))
        if (e.memo.isNotBlank()) c.addView(Ui.text(this, 13f, Ui.SUB, false, e.memo).apply { maxLines = 2 }, Ui.lp(this, MATCH, WRAP, top = 2))
        c.addView(Ui.text(this, 11f, Ui.SUB, false, "올린 사람 ${e.ownerName}"), Ui.lp(this, MATCH, WRAP, top = 4))
        return c
    }

    private fun canEdit(e: Cloud.Event) = e.owner == Cloud.uid || Cloud.isOwner

    private fun detail(e: Cloud.Event) {
        val msg = buildString {
            append("날짜: ").append(dateText(e)).append(if (e.time.isNotBlank()) " ${e.time}" else "").append("\n")
            if (e.cat.isNotBlank()) append("종류: ").append(Store.catLabel(e.cat)).append("\n")
            if (e.place.isNotBlank()) append("장소(항구): ").append(e.place).append("\n")
            if (e.boat.isNotBlank()) append("배: ").append(e.boat).append("\n")
            if (e.memo.isNotBlank()) append("\n").append(e.memo).append("\n")
            append("\n올린 사람: ").append(e.ownerName)
        }
        val b = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("일정 상세")
            .setMessage(msg)
            .setPositiveButton("닫기", null)
        if (canEdit(e)) {
            b.setNeutralButton("수정") { _, _ -> editEvent(e) }
            b.setNegativeButton("삭제") { _, _ ->
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("이 일정을 지울까요?")
                    .setPositiveButton("지우기") { _, _ -> Cloud.deleteEvent(e.id) { err -> toast(err ?: "지웠어요"); load() } }
                    .setNegativeButton("취소", null)
                    .show()
            }
        }
        b.show()
    }

    private fun suggest(hint: String, items: List<String>, value: String) = AutoCompleteTextView(this).apply {
        this.hint = hint; setText(value); threshold = 0; inputType = InputType.TYPE_CLASS_TEXT
        setAdapter(ArrayAdapter(this@ScheduleActivity, android.R.layout.simple_dropdown_item_1line, items))
        setOnClickListener { if (items.isNotEmpty()) showDropDown() }
    }

    private fun editEvent(e: Cloud.Event?) {
        val cal = Calendar.getInstance()
        e?.let { try { cal.time = keyFmt.parse(it.date)!! } catch (_: Exception) {} }
        var date = keyFmt.format(cal.time)
        var time = e?.time ?: "05:00"
        val types = Cloud.active?.types?.ifEmpty { null } ?: Store.CATS
        var cat = e?.cat?.ifBlank { null } ?: types.first()

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val dateBtn = Ui.button(this, "") {}.apply { minHeight = dp(48); textSize = 16f }
        val timeBtn = Ui.button(this, "") {}.apply { minHeight = dp(48); textSize = 16f }
        fun refreshBtns() {
            dateBtn.text = "날짜  " + (try { niceFmt.format(keyFmt.parse(date)!!) } catch (_: Exception) { date })
            timeBtn.text = "출항 시각  $time"
        }
        refreshBtns()
        dateBtn.setOnClickListener {
            val c = Calendar.getInstance()
            c.time = keyFmt.parse(date)!!
            DatePickerDialog(this, { _, y, m, d ->
                c.set(y, m, d); date = keyFmt.format(c.time); refreshBtns()
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }
        timeBtn.setOnClickListener {
            val (h, mi) = time.split(":").map { it.toIntOrNull() ?: 0 }.let { (it.getOrElse(0) { 5 }) to (it.getOrElse(1) { 0 }) }
            TimePickerDialog(this, { _, hh, mm -> time = String.format(Locale.KOREA, "%02d:%02d", hh, mm); refreshBtns() }, h, mi, true).show()
        }
        val catRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun renderCat() {
            catRow.removeAllViews()
            types.forEachIndexed { i, c ->
                val on = c == cat
                catRow.addView(Ui.text(this, 14f, if (on) Ui.BG else Ui.TEXT, true, Store.catShort(c)).apply {
                    gravity = Gravity.CENTER; minHeight = dp(40)
                    background = if (on) Ui.bg(this@ScheduleActivity, Ui.TEXT, 20) else Ui.bg(this@ScheduleActivity, Ui.CARD, 20, Ui.LINE)
                    setOnClickListener { cat = c; renderCat() }
                }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) leftMargin = dp(6) })
            }
        }
        renderCat()
        val place = suggest("장소 (예: ○○항)", Store.ports(), e?.place ?: "")
        val boat = suggest("배 이름 (선상일 때)", Store.boats(), e?.boat ?: "")
        val memo = EditText(this).apply { hint = "메모 (모이는 곳, 준비물 등)"; setText(e?.memo ?: ""); minLines = 2 }
        box.addView(dateBtn); box.addView(timeBtn, Ui.lp(this, MATCH, WRAP, top = 8))
        box.addView(catRow, Ui.lp(this, MATCH, WRAP, top = 10))
        box.addView(place); box.addView(boat); box.addView(memo)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (e == null) "일정 올리기" else "일정 수정")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("저장") { _, _ ->
                Cloud.saveEvent(e?.id, date, time, place.text.toString().trim(), boat.text.toString().trim(), cat,
                    memo.text.toString().trim(), e) { err -> toast(err ?: "일정을 올렸어요"); load() }
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
