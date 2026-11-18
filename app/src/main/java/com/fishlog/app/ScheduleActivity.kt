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
    /** v3.9.2: 화면 크기 자동 맞춤 (Ui.fit) */
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(Ui.fit(newBase)) }


    private lateinit var listBox: LinearLayout
    private var events: List<Cloud.Event> = emptyList()
    /** v6.36 (296): 이미 공지에 올라간 일정 번호 */
    private var pinned: Set<String> = emptySet()
    private var showPast = false
    /** v6.39 (301): 일정별 참석 답 · 그룹 멤버 (아직 답 안 한 사람 보기) */
    private val rsvps = HashMap<String, List<Cloud.Rsvp>>()
    private var members: List<Cloud.Member> = emptyList()
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
        GroupBand.view(this, false)?.let { col.addView(it, Ui.lp(this, MATCH, WRAP, top = 12)) }
        col.addView(Ui.text(this, 13f, Ui.SUB, false, "그룹원 누구나 일정을 올리고 같이 볼 수 있어요"), Ui.lp(this, MATCH, WRAP, top = 8))
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
            Cloud.active?.id?.let { gid -> GroupNews.putEvents(gid, list); GroupNews.markKindSeen(gid, "event") }
            pinned = GroupNews.noticeList(Cloud.active?.id).map { it.eid }.filter { it.isNotBlank() }.toSet()
            render()
            loadRsvps()
            Cloud.active?.let { g -> Cloud.loadNoticesOf(g) { ns, _ -> if (ns != null && !isFinishing) {
                GroupNews.putNotices(g.id, ns); pinned = ns.map { it.eid }.filter { it.isNotBlank() }.toSet(); render() } } }
        }
    }

    /** 다가오는 일정 + 최근 지난 일정 3개만 참석 답 받기 (서버 읽기 줄이기) */
    private fun loadRsvps() {
        val today = keyFmt.format(Date())
        val need = events.filter { it.date >= today } + events.filter { it.date < today }.takeLast(3)
        need.forEach { e -> Cloud.loadRsvp(e.id) { l -> if (l != null && !isFinishing) { rsvps[e.id] = l; render() } } }
        if (members.isEmpty()) Cloud.loadMembers { l, _ -> if (l != null && !isFinishing) { members = l; render() } }
    }

    private class Att(val yes: List<Cloud.Rsvp>, val wait: List<Cloud.Rsvp>, val maybe: List<Cloud.Rsvp>, val no: List<Cloud.Rsvp>)
    private fun att(e: Cloud.Event): Att? {
        val l = rsvps[e.id] ?: return null
        val y = l.filter { it.s == "yes" }.sortedBy { it.t }
        val (inn, wait) = if (e.cap > 0) y.take(e.cap) to y.drop(e.cap) else y to emptyList()
        return Att(inn, wait, l.filter { it.s == "maybe" }, l.filter { it.s == "no" })
    }
    private fun noAnswer(e: Cloud.Event): List<Cloud.Member> {
        val answered = rsvps[e.id]?.map { it.uid }?.toSet() ?: return emptyList()
        return members.filter { it.uid !in answered }
    }

    /** 참석 현황 한 줄 + [🙋 참석] [🤔 미정] [🙅 불참] */
    private fun rsvpBox(e: Cloud.Event, past: Boolean): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val a = att(e)
        val sum = if (a == null) "참석 확인 불러오는 중…" else buildString {
            append("🙋 참석 ").append(a.yes.size); if (e.cap > 0) append("/").append(e.cap)
            if (e.cap > 0 && a.yes.size >= e.cap) append(" 마감")
            if (a.wait.isNotEmpty()) append(" · ⏳ 대기 ").append(a.wait.size)
            append(" · 🤔 미정 ").append(a.maybe.size).append(" · 🙅 불참 ").append(a.no.size)
            val na = noAnswer(e).size; if (na > 0) append(" · 아직 ").append(na)
            append("  ›")
        }
        box.addView(Ui.text(this, 13f, android.graphics.Color.parseColor("#9FE7C1"), true, sum).apply {
            setPadding(0, dp(4), 0, dp(4))
            setOnClickListener { showRsvp(e) }
        })
        if (past || a == null) return box
        val mine = rsvps[e.id]?.firstOrNull { it.uid == Cloud.uid }
        val myWait = mine?.s == "yes" && a.wait.any { it.uid == Cloud.uid }
        val r = Ui.row(this)
        listOf(Triple("yes", "🙋 참석", "#5FD39A"), Triple("maybe", "🤔 미정", "#F2C14E"), Triple("no", "🙅 불참", "#FF8A92")).forEachIndexed { i, (k, label, colS) ->
            val col = android.graphics.Color.parseColor(colS)
            val on = mine?.s == k
            val txt = if (k == "yes" && myWait) "⏳ 대기 ${a.wait.indexOfFirst { it.uid == Cloud.uid } + 1}번" else label
            r.addView(Ui.text(this, 13.5f, if (on) Ui.BG else col, true, txt).apply {
                gravity = Gravity.CENTER; minHeight = dp(40)
                background = if (on) Ui.bg(this@ScheduleActivity, col, 99) else Ui.bg(this@ScheduleActivity, android.graphics.Color.TRANSPARENT, 99, col)
                setOnClickListener {
                    if (k == "yes" && !on && e.cap > 0 && a.yes.size >= e.cap) toast("정원 ${e.cap}명이 다 찼어요 · 대기 ${a.wait.size + 1}번으로 넣어 둘게요 (누가 빠지면 순서대로 참석)")
                    answer(e, if (on) "" else k, mine?.note ?: "", mine)
                }
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) leftMargin = dp(6) })
        }
        box.addView(r, Ui.lp(this, MATCH, WRAP, top = 2))
        if (mine != null) box.addView(Ui.text(this, 12f, Ui.SUB, false,
            if (mine.note.isBlank()) "✎ 한마디 남기기 (예: 6시 도착 · 차 2명 태울 수 있어요)" else "✎ 내 한마디: ${mine.note}").apply {
            setPadding(0, dp(6), 0, 0)
            setOnClickListener {
                val et = EditText(this@ScheduleActivity).apply { setText(mine.note); hint = "한마디 (60자)"; setSingleLine() }
                AlertDialog.Builder(this@ScheduleActivity, R.style.FlAlert).setTitle("✎ 한마디")
                    .setView(LinearLayout(this@ScheduleActivity).apply { setPadding(dp(20), dp(4), dp(20), 0); addView(et, LinearLayout.LayoutParams(MATCH, WRAP)) })
                    .setPositiveButton("저장") { _, _ -> answer(e, mine.s, et.text.toString(), mine) }
                    .setNegativeButton("취소", null).show()
            }
        })
        return box
    }

    private fun answer(e: Cloud.Event, s: String, note: String, old: Cloud.Rsvp?) {
        Cloud.setRsvp(e.id, s, note, old) { err ->
            if (err != null) { toast(err); return@setRsvp }
            Cloud.loadRsvp(e.id) { l -> if (l != null && !isFinishing) { rsvps[e.id] = l; render() } }
        }
    }

    /** 누가 어떤 답을 했는지 (이름 · 한마디) */
    private fun showRsvp(e: Cloud.Event) {
        val a = att(e) ?: return
        fun lines(l: List<Cloud.Rsvp>) = l.joinToString("\n") { "  · " + it.name + (if (it.note.isNotBlank()) " — ${it.note}" else "") }
        val msg = buildString {
            append("🙋 참석 ").append(a.yes.size).append(if (e.cap > 0) "/${e.cap}" else "").append("\n")
            if (a.yes.isNotEmpty()) append(lines(a.yes)).append("\n")
            if (a.wait.isNotEmpty()) append("\n⏳ 대기 ").append(a.wait.size).append(" (누른 순서)\n")
                .append(a.wait.mapIndexed { i, r -> "  ${i + 1}. ${r.name}" + (if (r.note.isNotBlank()) " — ${r.note}" else "") }.joinToString("\n")).append("\n")
            append("\n🤔 미정 ").append(a.maybe.size).append("\n"); if (a.maybe.isNotEmpty()) append(lines(a.maybe)).append("\n")
            append("\n🙅 불참 ").append(a.no.size).append("\n"); if (a.no.isNotEmpty()) append(lines(a.no)).append("\n")
            val na = noAnswer(e)
            if (na.isNotEmpty()) append("\n❔ 아직 답 안 함 ").append(na.size).append("\n").append(na.joinToString("\n") { "  · " + it.name })
        }
        AlertDialog.Builder(this, R.style.FlAlert).setTitle("참석 확인 · " + dateText(e)).setMessage(msg.trimEnd())
            .setPositiveButton("닫기", null).show()
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
        // v6.39 (301): 참석 확인
        if (e.date >= today || rsvps.containsKey(e.id)) c.addView(rsvpBox(e, e.date < today), Ui.lp(this, MATCH, WRAP, top = 6))
        // v6.36 (296): 올린 사람 + [📢 공지에 올리기] (그룹원 누구나 · 이미 올렸으면 '✓ 공지에 올라감')
        val br = Ui.row(this)
        br.addView(Ui.text(this, 11f, Ui.SUB, false, "올린 사람 ${e.ownerName}"), LinearLayout.LayoutParams(0, WRAP, 1f))
        val on = e.id in pinned
        br.addView(Ui.text(this, 12.5f, if (on) android.graphics.Color.parseColor("#9FE7C1") else android.graphics.Color.parseColor("#FFB27A"), true,
            if (on) "✓ 공지에 올라감" else "📢 공지에 올리기").apply {
            gravity = Gravity.CENTER; setPadding(dp(12), dp(5), dp(12), dp(5))
            background = if (on) Ui.bg(this@ScheduleActivity, android.graphics.Color.parseColor("#1D3A2A"), 99, Ui.GREEN)
                else Ui.bg(this@ScheduleActivity, android.graphics.Color.parseColor("#3A2814"), 99, android.graphics.Color.parseColor("#FFB27A"))
            setOnClickListener {
                if (on) { startActivity(android.content.Intent(this@ScheduleActivity, NoticeActivity::class.java)); return@setOnClickListener }
                val title = dateText(e) + (if (e.time.isNotBlank()) " ${e.time}" else "") +
                    listOf(Store.catShort(e.cat), e.place, e.boat).filter { it.isNotBlank() }.joinToString(" · ").let { if (it.isBlank()) "" else " · $it" }
                Cloud.pinEvent(e, title, e.memo) { err -> toast(err ?: "공지에 올렸어요 · 그룹원 모두에게 보여요"); load() }
            }
        })
        c.addView(br, Ui.lp(this, MATCH, WRAP, top = 6))
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
        val b = AlertDialog.Builder(this, R.style.FlAlert)
            .setTitle("일정 상세")
            .setMessage(msg)
            .setPositiveButton("닫기", null)
        if (canEdit(e)) {
            b.setNeutralButton("수정") { _, _ -> editEvent(e) }
            b.setNegativeButton("삭제") { _, _ ->
                AlertDialog.Builder(this, R.style.FlAlert)
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
        // v6.39 (301): 참석 인원 제한 (넘으면 대기 순서)
        val cap = EditText(this).apply { hint = "참석 인원 제한 (예: 8 · 비우면 제한 없음)"; inputType = InputType.TYPE_CLASS_NUMBER
            if ((e?.cap ?: 0) > 0) setText(e!!.cap.toString()) }
        box.addView(dateBtn); box.addView(timeBtn, Ui.lp(this, MATCH, WRAP, top = 8))
        box.addView(catRow, Ui.lp(this, MATCH, WRAP, top = 10))
        box.addView(place); box.addView(boat); box.addView(memo); box.addView(cap)
        AlertDialog.Builder(this, R.style.FlAlert)
            .setTitle(if (e == null) "일정 올리기" else "일정 수정")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("저장") { _, _ ->
                Cloud.saveEvent(e?.id, date, time, place.text.toString().trim(), boat.text.toString().trim(), cat,
                    memo.text.toString().trim(), e, cap.text.toString().trim().toIntOrNull() ?: 0) { err -> toast(err ?: "일정을 올렸어요"); load() }
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
