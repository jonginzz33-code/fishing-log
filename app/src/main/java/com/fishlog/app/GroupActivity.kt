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
    private lateinit var groupsBox: LinearLayout
    private lateinit var bandBox: LinearLayout
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
        bandBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(bandBox, Ui.lp(this, MATCH, WRAP, top = 12))

        // (그룹 바꾸기는 메인 맨 아래 '그룹' 칸에서) 지금 고른 그룹만 보여줌
        groupsBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        head = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(head, Ui.lp(this, MATCH, WRAP, top = 12))
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
        bandBox.removeAllViews()
        GroupBand.view(this, false)?.let { bandBox.addView(it) }
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
    private fun renderGroups() {
        groupsBox.removeAllViews()
        val gs = Cloud.groups
        if (gs.size <= 1) return
        gs.forEach { g ->
            val on = g.id == Cloud.active?.id
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = if (on) Ui.bg(this@GroupActivity, Ui.TEXT, 14) else Ui.bg(this@GroupActivity, Ui.CARD, 14, Ui.LINE)
                isClickable = true
                setOnClickListener {
                    if (!on) { selected = null; trips = emptyList(); members = emptyList(); Cloud.setActive(g.id) }
                }
            }
            chip.addView(Ui.text(this, 15f, if (on) Ui.BG else Ui.TEXT, true, g.name))
            chip.addView(Ui.text(this, 11f, if (on) Color.parseColor("#2B4A5E") else Ui.SUB, false,
                g.typesLabel().ifBlank { "종류 미정" }))
            groupsBox.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
    }

    private fun renderHead() {
        renderGroups()
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
        val nameRow = Ui.row(this)
        nameRow.addView(Ui.text(this, 22f, Ui.TEXT, true, g.name), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (Cloud.isOwner) {
            nameRow.addView(Ui.button(this, "✎ 이름") { askRename(g.name) }.apply { minHeight = dp(40); textSize = 13f },
                LinearLayout.LayoutParams(WRAP, WRAP))
        }
        card.addView(nameRow)
        // 공지 한 줄 (그룹장만 쓰기)
        if (g.notice.isNotBlank() || Cloud.isOwner) {
            val nb = Ui.row(this).apply {
                background = Ui.bg(this@GroupActivity, Color.parseColor("#2A2310"), 12, if (g.notice.isNotBlank()) Ui.ORANGE else Ui.LINE)
                setPadding(dp(12), dp(8), dp(8), dp(8))
            }
            nb.addView(Ui.text(this, 14f, if (g.notice.isNotBlank()) Color.parseColor("#FFE2C2") else Ui.SUB, g.notice.isNotBlank(),
                if (g.notice.isNotBlank()) "공지  ${g.notice}" else "공지 없음 · 그룹원 모두에게 보일 한 줄을 적어 보세요"),
                LinearLayout.LayoutParams(0, WRAP, 1f))
            if (Cloud.isOwner) {
                nb.addView(Ui.button(this, "✎ 공지") { askNotice(g.notice) }.apply { minHeight = dp(36); textSize = 12f },
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
            }
            card.addView(nb, Ui.lp(this, MATCH, WRAP, top = 8))
        }
        val typeRow = Ui.row(this)
        typeRow.addView(Ui.text(this, 14f, Ui.BLUE, true, g.typesLabel().ifBlank { "낚시 종류 미정" }), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (Cloud.isOwner) {
            typeRow.addView(Ui.button(this, "✎ 종류") {
                account.askTypes("그룹 낚시 종류 (여러 개 선택 가능)", g.types) { types ->
                    Cloud.setGroupTypes(types) { err -> toast(err ?: "그룹 종류를 바꿨어요"); load() }
                }
            }.apply { minHeight = dp(40); textSize = 13f }, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        card.addView(typeRow, Ui.lp(this, MATCH, WRAP, top = 4))
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
        if (Cloud.isOwner) {
            card.addView(Ui.button(this, "초대 코드 새로 만들기") { askNewCode() }.apply { minHeight = dp(44); textSize = 14f },
                Ui.lp(this, MATCH, WRAP, top = 8))
        }
        card.addView(Ui.button(this, "그룹 바꾸기 · 만들기 · 가입") { account.openGroups() }.apply { minHeight = dp(44); textSize = 14f },
            Ui.lp(this, MATCH, WRAP, top = 8))
        if (Cloud.isOwner) {
            val orow = Ui.row(this)
            orow.addView(Ui.button(this, "그룹장 넘겨주기") { askTransfer() }.apply { minHeight = dp(44); textSize = 13f },
                LinearLayout.LayoutParams(0, WRAP, 1f))
            orow.addView(Ui.button(this, "그룹 삭제") { askDeleteGroup(g) }.apply {
                minHeight = dp(44); textSize = 13f; setTextColor(Color.parseColor("#FF8A8A"))
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
            card.addView(orow, Ui.lp(this, MATCH, WRAP, top = 8))
        }
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
            val nameLine = Ui.row(this)
            nameLine.addView(Ui.text(this, 15f, Ui.TEXT, true, m.name + if (m.uid == Cloud.uid) " (나)" else ""))
            if (m.uid == owner) {
                nameLine.addView(CrownView(this), LinearLayout.LayoutParams(dp(16), dp(13)).apply { leftMargin = dp(6) })
            }
            col.addView(nameLine)
            val parts = mutableListOf<String>()
            if (m.joined > 0) parts.add("가입 ${md.format(Date(m.joined))}")
            parts.add(if (mine.isEmpty()) "출조 기록 없음" else "마지막 출조 ${md.format(Date(mine.maxOf { it.start }))}")
            parts.add("출조 ${mine.size}회 · 총 ${mine.sumOf { it.catches.size }}마리")
            col.addView(Ui.text(this, 12f, Ui.SUB, false, parts.joinToString(" · ")))
            r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(12) })
            if (Cloud.isOwner && m.uid != Cloud.uid) {
                r.isLongClickable = true
                r.setOnLongClickListener { askKick(m); true }
            }
            card.addView(r)
        }
        if (Cloud.isOwner && members.size > 1) {
            card.addView(Ui.text(this, 12f, Ui.SUB, false, "멤버 이름을 길게 누르면 내보낼 수 있어요"), Ui.lp(this, MATCH, WRAP, top = 6))
        }
        membersBox.addView(card)
    }

    // ---------------- 그룹장 기능 ----------------
    private fun askNotice(cur: String) {
        val input = android.widget.EditText(this).apply {
            setText(cur); setSelection(cur.length); hint = "예: 11/2(토) 새벽 5시 ○○항 집합"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("그룹 공지 (그룹 화면 맨 위에 고정)")
            .setView(input)
            .setPositiveButton("저장") { _, _ ->
                val v = input.text.toString().trim().take(100)
                Cloud.setNotice(v) { err -> toast(err ?: if (v.isEmpty()) "공지를 지웠어요" else "공지를 올렸어요"); load() }
            }
            .setNeutralButton("공지 지우기") { _, _ -> Cloud.setNotice("") { err -> toast(err ?: "공지를 지웠어요"); load() } }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun askTransfer() {
        val others = members.filter { it.uid != Cloud.uid }
        if (others.isEmpty()) { toast(if (members.isEmpty()) "멤버를 불러오는 중이에요. 잠시 후 다시 눌러주세요" else "넘겨줄 다른 멤버가 없어요"); return }
        BigList.show(this, "누구에게 그룹장을 넘길까요?", others.map { it.name }) { i, _ ->
            val m = others[i]
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("${m.name}님에게 그룹장을 넘길까요?")
                .setMessage("왕관과 그룹장 권한(이름·종류·공지·초대 코드·내보내기·삭제)이 넘어가요. 되돌리려면 새 그룹장이 다시 넘겨줘야 해요.")
                .setPositiveButton("넘겨주기") { _, _ ->
                    Cloud.transferOwner(m.uid) { err -> toast(err ?: "${m.name}님이 이제 그룹장이에요"); load() }
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    private fun askDeleteGroup(g: Cloud.Group) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("'${g.name}' 그룹을 삭제할까요?")
            .setMessage("그룹에 올라간 모든 출조·사진·일정이 지워지고, 멤버 모두 이 그룹을 볼 수 없게 돼요.\n각자 폰에 있는 기록은 그대로예요.")
            .setPositiveButton("다음") { _, _ ->
                val input = android.widget.EditText(this).apply { hint = g.name; inputType = android.text.InputType.TYPE_CLASS_TEXT }
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("정말 삭제하려면 그룹 이름을 똑같이 적어주세요")
                    .setView(input)
                    .setPositiveButton("삭제") { _, _ ->
                        if (input.text.toString().trim() != g.name.trim()) { toast("이름이 달라서 삭제하지 않았어요"); return@setPositiveButton }
                        toast("그룹을 지우는 중…")
                        Cloud.deleteGroup { err ->
                            toast(err ?: "'${g.name}' 그룹을 삭제했어요")
                            selected = null; trips = emptyList(); members = emptyList(); load()
                        }
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun askRename(cur: String) {
        val input = android.widget.EditText(this).apply {
            setText(cur); setSelection(cur.length)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("그룹 이름 바꾸기")
            .setView(input)
            .setPositiveButton("저장") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) Cloud.renameGroup(v) { err -> toast(err ?: "그룹 이름을 '$v'(으)로 바꿨어요"); load() }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun askNewCode() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("초대 코드를 새로 만들까요?")
            .setMessage("예전 코드로는 더 이상 가입할 수 없어요. 이미 가입한 멤버는 그대로예요.")
            .setPositiveButton("새로 만들기") { _, _ ->
                Cloud.newInviteCode { err -> toast(err ?: "새 초대 코드: ${Cloud.active?.code}"); load() }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun askKick(m: Cloud.Member) {
        val count = trips.count { it.owner == m.uid }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val delTrips = android.widget.CheckBox(this).apply {
            text = "이 사람이 올린 출조 ${count}개도 지우기"
            isChecked = false
        }
        val newCode = android.widget.CheckBox(this).apply {
            text = "초대 코드도 새로 만들기 (다시 못 들어오게)"
            isChecked = true
        }
        box.addView(delTrips); box.addView(newCode)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("'${m.name}'님을 내보낼까요?")
            .setView(box)
            .setPositiveButton("내보내기") { _, _ ->
                Cloud.kickMember(m.uid, delTrips.isChecked) { err ->
                    if (err != null) { toast(err); load(); return@kickMember }
                    if (newCode.isChecked) {
                        Cloud.newInviteCode { e2 -> toast(e2 ?: "내보냈어요 · 새 초대 코드 ${Cloud.active?.code}"); load() }
                    } else { toast("내보냈어요"); load() }
                }
            }
            .setNegativeButton("취소", null)
            .show()
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
                t.ownerName + (if (t.cat.isNotBlank()) " · ${Store.catShort(t.cat)}" else "") +
                    (if (t.place().isNotBlank()) " · ${t.place()}" else "") + " · ${hm.format(Date(t.start))}~$endText"),
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
            if (t.photoCount > 0) r.addView(Ui.chip(this, "사진 ${t.photoCount}"),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
            card.addView(r)
            card.addView(Ui.text(this, 14f, Ui.TEXT, false,
                "${t.name(1)} ${t.count(1)} · ${t.name(2)} ${t.count(2)} · 총 ${t.catches.size}"), Ui.lp(this, MATCH, WRAP, top = 4))
            stampRow(this, listOf(t.name(1), t.name(2)), listOf(t.count(1), t.count(2)))?.let { card.addView(it, Ui.lp(this, MATCH, WRAP, top = 6)) }
            // 이용료·준비물 (그룹원만 봄)
            val cost = listOfNotNull(t.costLabel().ifBlank { null }, if (t.gear.isNotEmpty()) "준비물: " + t.gear.joinToString(", ") else null)
            if (cost.isNotEmpty()) card.addView(Ui.text(this, 12f, Ui.SUB, false, cost.joinToString(" · ")), Ui.lp(this, MATCH, WRAP, top = 2))
            dayBox.addView(card, Ui.lp(this, MATCH, WRAP, top = 8))
        }
    }

    private fun openTrip(t: Cloud.RemoteTrip) {
        val items = mutableListOf("함께한 출조 지도·순위")
        if (t.photoCount > 0) items.add("사진 보기 (${t.photoCount}장)")
        val mine = t.owner == Cloud.uid
        if (mine) items.add("그룹에서 내리기")
        else if (Cloud.isOwner) items.add("그룹에서 내리기 (그룹장)")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("${t.ownerName} · ${hm.format(Date(t.start))} 출조")
            .setItems(items.toTypedArray()) { _, i ->
                when {
                    items[i].startsWith("함께한") -> account.showTogether(t)
                    items[i].startsWith("사진") -> account.showPhotos(t)
                    items[i].startsWith("그룹에서") -> AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(if (mine) "이 출조를 그룹에서 내릴까요?" else "${t.ownerName}님의 이 출조를 그룹에서 내릴까요?")
                        .setMessage(if (mine) "친구들 화면에서 사라지고, 내 폰 기록은 그대로예요"
                            else "그룹 화면에서만 사라지고, ${t.ownerName}님 폰의 기록은 그대로예요")
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
