package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
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

/** 그룹 화면: 그룹 이름·초대 코드, 멤버, 달력(출조한 날 강조), 그날 출조 카드 */
class GroupActivity : Activity() {

    private lateinit var account: AccountUi
    private lateinit var root: LinearLayout
    private lateinit var head: LinearLayout
    private lateinit var membersBox: LinearLayout
    private lateinit var calBox: LinearLayout
    private lateinit var dayBox: LinearLayout

    private var trips: List<Cloud.RemoteTrip> = emptyList()
    private var members: List<Cloud.Member> = emptyList()
    private var colorOf: Map<String, Int> = emptyMap()
    private val month: Calendar = Calendar.getInstance()
    private var selected: String? = null
    private var membersOpen = false

    private val keyFmt = SimpleDateFormat("yyyyMMdd", Locale.KOREA)
    private val hm = SimpleDateFormat("HH:mm", Locale.KOREA)
    private val md = SimpleDateFormat("M/d", Locale.KOREA)
    private val dayTitle = SimpleDateFormat("M월 d일 (E)", Locale.KOREA)
    private val cloudListener: () -> Unit = { load() }

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        account = AccountUi(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        root = Ui.column(this)

        val top = Ui.row(this)
        top.addView(Ui.button(this, "← 뒤로") { finish() }.apply { minHeight = dp(44) }, LinearLayout.LayoutParams(WRAP, WRAP))
        top.addView(Ui.text(this, 20f, Ui.TEXT, true, "그룹"),
            LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(14) })
        root.addView(top)

        head = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(head, Ui.lp(this, MATCH, WRAP, top = 16))
        membersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(membersBox, Ui.lp(this, MATCH, WRAP, top = 8))

        root.addView(Ui.text(this, 16f, Ui.TEXT, true, "출조 달력"), Ui.lp(this, MATCH, WRAP, top = 22))
        calBox = Ui.card(this)
        root.addView(calBox, Ui.lp(this, MATCH, WRAP, top = 10))
        dayBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(dayBox, Ui.lp(this, MATCH, WRAP, top = 6))

        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(root) })
    }

    override fun onResume() {
        super.onResume()
        Cloud.addListener(cloudListener)
        load()
    }

    override fun onPause() { Cloud.removeListener(cloudListener); super.onPause() }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun load() {
        renderHead()
        if (Cloud.user == null || Cloud.active == null) {
            membersBox.removeAllViews(); dayBox.removeAllViews(); calBox.removeAllViews()
            calBox.addView(Ui.text(this, 14f, Ui.SUB, false, "로그인하고 그룹을 만들거나 가입하면 여기에 친구들 출조가 보여요"))
            return
        }
        Cloud.loadMembers { list, _ ->
            members = list ?: emptyList()
            assignColors()
            renderHead(); renderMembers(); renderCalendar(); renderDay()
        }
        Cloud.recentTrips { list, err ->
            if (list == null) { toast(err ?: "불러오기 실패"); return@recentTrips }
            trips = list
            assignColors()
            if (selected == null) {
                list.firstOrNull()?.let {
                    selected = keyFmt.format(Date(it.start))
                    month.timeInMillis = it.start
                }
            }
            renderHead(); renderMembers(); renderCalendar(); renderDay()
        }
    }

    private fun assignColors() {
        val order = (members.map { it.uid } + trips.map { it.owner }).distinct()
        colorOf = order.mapIndexed { i, u -> u to MapDialog.FRIEND_COLORS[i % MapDialog.FRIEND_COLORS.size] }.toMap()
    }

    // ---------------- 그룹 정보 + 멤버 ----------------
    private fun renderHead() {
        head.removeAllViews()
        val g = Cloud.active
        val card = Ui.card(this)
        if (g == null) {
            card.addView(Ui.text(this, 15f, Ui.TEXT, false, if (Cloud.user == null) "로그인이 필요해요" else "아직 그룹이 없어요"))
            card.addView(Ui.button(this, if (Cloud.user == null) "구글 로그인" else "그룹 만들기 / 가입") {
                if (Cloud.user == null) account.signIn { load() } else account.openGroups()
            }.apply { minHeight = dp(44) }, Ui.lp(this, MATCH, WRAP, top = 10))
            head.addView(card)
            return
        }
        card.addView(Ui.text(this, 22f, Ui.TEXT, true, g.name))
        card.addView(Ui.text(this, 15f, Ui.SUB, false, "초대 코드  ").apply {
            append(g.code)
        }, Ui.lp(this, MATCH, WRAP, top = 4))
        val row = Ui.row(this)
        row.addView(Ui.button(this, "코드 복사") {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("초대 코드", g.code))
            toast("초대 코드 ${g.code} 복사했어요")
        }.apply { minHeight = dp(44); textSize = 14f }, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(Ui.button(this, "카톡으로 보내기") { account.shareCode() }.apply { minHeight = dp(44); textSize = 14f },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        card.addView(row, Ui.lp(this, MATCH, WRAP, top = 10))
        val memberBtn = Ui.button(this, "멤버 ${if (members.isEmpty()) "" else "${members.size}명 "}${if (membersOpen) "접기 ▲" else "보기 ▼"}", true) {
            membersOpen = !membersOpen
            renderHead(); renderMembers()
        }.apply { minHeight = dp(44); textSize = 14f }
        card.addView(memberBtn, Ui.lp(this, MATCH, WRAP, top = 8))
        card.addView(Ui.button(this, "그룹 바꾸기 · 만들기 · 가입") { account.openGroups() }.apply { minHeight = dp(44); textSize = 14f },
            Ui.lp(this, MATCH, WRAP, top = 8))
        head.addView(card)
    }

    private fun renderMembers() {
        membersBox.removeAllViews()
        if (!membersOpen) return
        val owner = Cloud.active?.owner
        val card = Ui.card(this)
        if (members.isEmpty()) card.addView(Ui.text(this, 14f, Ui.SUB, false, "멤버 불러오는 중…"))
        members.forEach { m ->
            val mine = trips.filter { it.owner == m.uid }
            val r = Ui.row(this).apply { setPadding(0, dp(8), 0, dp(8)) }
            r.addView(View(this).apply { background = Ui.bg(this@GroupActivity, colorOf[m.uid] ?: Ui.SUB, 99) },
                LinearLayout.LayoutParams(dp(12), dp(12)))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val name = m.name + (if (m.uid == owner) " · 방장" else "") + (if (m.uid == Cloud.uid) " · 나" else "")
            col.addView(Ui.text(this, 15f, Ui.TEXT, true, name))
            val parts = mutableListOf<String>()
            if (m.joined > 0) parts.add("가입 ${md.format(Date(m.joined))}")
            parts.add(if (mine.isEmpty()) "출조 기록 없음" else "마지막 출조 ${md.format(Date(mine.maxOf { it.start }))}")
            parts.add("출조 ${mine.size}회 · 총 ${mine.sumOf { it.catches.size }}마리")
            col.addView(Ui.text(this, 12f, Ui.SUB, false, parts.joinToString(" · ")))
            r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(12) })
            card.addView(r)
        }
        membersBox.addView(card)
    }

    // ---------------- 달력 ----------------
    private fun renderCalendar() {
        calBox.removeAllViews()
        val byDay = trips.groupBy { keyFmt.format(Date(it.start)) }

        val nav = Ui.row(this)
        nav.addView(Ui.button(this, "◀") { month.add(Calendar.MONTH, -1); renderCalendar() }.apply { minHeight = dp(44) },
            LinearLayout.LayoutParams(dp(56), WRAP))
        nav.addView(Ui.text(this, 17f, Ui.TEXT, true, "${month.get(Calendar.YEAR)}년 ${month.get(Calendar.MONTH) + 1}월").apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        nav.addView(Ui.button(this, "▶") { month.add(Calendar.MONTH, 1); renderCalendar() }.apply { minHeight = dp(44) },
            LinearLayout.LayoutParams(dp(56), WRAP))
        calBox.addView(nav)

        val week = Ui.row(this)
        listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { i, d ->
            week.addView(Ui.text(this, 12f, if (i == 0) Color.parseColor("#F07AA0") else Ui.SUB, false, d).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        calBox.addView(week, Ui.lp(this, MATCH, WRAP, top = 10))

        val c = month.clone() as Calendar
        c.set(Calendar.DAY_OF_MONTH, 1)
        val lead = c.get(Calendar.DAY_OF_WEEK) - 1
        val days = c.getActualMaximum(Calendar.DAY_OF_MONTH)
        val todayKey = keyFmt.format(Date())
        var day = 1 - lead
        while (day <= days) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 0 until 7) {
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                if (day in 1..days) {
                    c.set(Calendar.DAY_OF_MONTH, day)
                    val key = keyFmt.format(c.time)
                    val list = byDay[key].orEmpty()
                    val isSel = key == selected
                    val num = Ui.text(this, 14f, Ui.TEXT, list.isNotEmpty(), day.toString()).apply {
                        gravity = Gravity.CENTER
                        when {
                            isSel && list.isNotEmpty() -> { setTextColor(Ui.BG); background = Ui.bg(this@GroupActivity, Ui.TEXT, 99) }
                            list.isNotEmpty() -> background = Ui.bg(this@GroupActivity, Color.parseColor("#2B4A5E"), 99, Ui.BLUE)
                            key == todayKey -> background = Ui.bg(this@GroupActivity, Color.TRANSPARENT, 99, Ui.LINE)
                            else -> setTextColor(Ui.SUB)
                        }
                    }
                    cell.addView(num, LinearLayout.LayoutParams(dp(36), dp(36)))
                    val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                    list.map { it.owner }.distinct().take(4).forEach { o ->
                        dots.addView(View(this).apply { background = Ui.bg(this@GroupActivity, colorOf[o] ?: Ui.SUB, 99) },
                            LinearLayout.LayoutParams(dp(6), dp(6)).apply { setMargins(dp(1), dp(3), dp(1), 0) })
                    }
                    cell.addView(dots, LinearLayout.LayoutParams(WRAP, dp(9)))
                    if (list.isNotEmpty()) cell.setOnClickListener { selected = key; renderCalendar(); renderDay() }
                }
                row.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f))
                day++
            }
            calBox.addView(row)
        }
        val inMonth = trips.count {
            val t = Calendar.getInstance().apply { timeInMillis = it.start }
            t.get(Calendar.YEAR) == month.get(Calendar.YEAR) && t.get(Calendar.MONTH) == month.get(Calendar.MONTH)
        }
        calBox.addView(Ui.text(this, 12f, Ui.SUB, false,
            if (inMonth == 0) "이 달은 출조 기록이 없어요" else "이 달 출조 ${inMonth}회 · 동그라미 날짜를 눌러 보기 · 점 = 출조한 사람"),
            Ui.lp(this, MATCH, WRAP, top = 8))
    }

    // ---------------- 선택한 날 출조 카드 ----------------
    private fun renderDay() {
        dayBox.removeAllViews()
        val key = selected ?: return
        val list = trips.filter { keyFmt.format(Date(it.start)) == key }.sortedBy { it.start }
        if (list.isEmpty()) return
        dayBox.addView(Ui.text(this, 16f, Ui.TEXT, true,
            dayTitle.format(Date(list[0].start)) + " · " + Tide.mulName(list[0].start).substringBefore(" (")),
            Ui.lp(this, MATCH, WRAP, top = 16))
        list.forEach { t ->
            val card = Ui.card(this).apply { isClickable = true; setOnClickListener { openTrip(t) } }
            val r = Ui.row(this)
            r.addView(View(this).apply { background = Ui.bg(this@GroupActivity, colorOf[t.owner] ?: Ui.SUB, 99) },
                LinearLayout.LayoutParams(dp(10), dp(10)))
            val endText = if (t.end > 0) hm.format(Date(t.end)) else "출조 중"
            r.addView(Ui.text(this, 15f, Ui.TEXT, true,
                t.ownerName + (if (t.place().isNotBlank()) " · ${t.place()}" else "") + " · ${hm.format(Date(t.start))}~$endText"),
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
            card.addView(r)
            card.addView(Ui.text(this, 14f, Ui.TEXT, false,
                "${t.name(1)} ${t.count(1)} · ${t.name(2)} ${t.count(2)} · 총 ${t.catches.size}" +
                    (if (t.photoCount > 0) " · 사진 ${t.photoCount}" else "")), Ui.lp(this, MATCH, WRAP, top = 4))
            dayBox.addView(card, Ui.lp(this, MATCH, WRAP, top = 8))
        }
    }

    private fun openTrip(t: Cloud.RemoteTrip) {
        val items = mutableListOf("함께한 출조 지도·순위")
        if (t.photoCount > 0) items.add("사진 보기 (${t.photoCount}장)")
        val mine = t.owner == Cloud.uid
        if (mine) items.add("그룹에서 내리기")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("${t.ownerName} · ${hm.format(Date(t.start))} 출조")
            .setItems(items.toTypedArray()) { _, i ->
                when {
                    items[i].startsWith("함께한") -> account.showTogether(t)
                    items[i].startsWith("사진") -> account.showPhotos(t)
                    items[i].startsWith("그룹에서") -> AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("이 출조를 그룹에서 내릴까요?")
                        .setMessage("친구들 화면에서 사라지고, 내 폰 기록은 그대로예요")
                        .setPositiveButton("내리기") { _, _ ->
                            Cloud.deleteGroupTrip(t.id) { err -> toast(err ?: "그룹에서 내렸어요"); load() }
                        }
                        .setNegativeButton("취소", null)
                        .show()
                }
            }
            .show()
    }
}
