package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
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

/**
 * 그룹 출조 기록: 이 그룹에 올라온 출조만 모아 보기
 *  - 기간(이번 달·3개월·올해·전체) · 사람 고르기
 *  - 출조 횟수, 어종별 합계, 많이 잡은 사람 순위, 출조 카드(도장 포함)
 */
class GroupTripsActivity : Activity() {

    private lateinit var account: AccountUi
    private lateinit var periodRow: LinearLayout
    private lateinit var peopleRow: LinearLayout
    private lateinit var body: LinearLayout
    private var trips: List<Cloud.RemoteTrip> = emptyList()
    private var period = 0          // 0 이번 달, 1 3개월, 2 올해, 3 전체
    private var person: String? = null   // null = 모든 사람
    private var rankSpecies: String? = null
    // 꾹 눌러 고르기 (그룹에서 내리기): 내 글 · 그룹장은 모든 글
    private var selMode = false
    private val selected = LinkedHashSet<String>()
    private lateinit var delBar: LinearLayout
    private var shown: List<Cloud.RemoteTrip> = emptyList()
    private fun canDel(t: Cloud.RemoteTrip) = t.owner == Cloud.uid || Cloud.isOwner || Cloud.isAdmin

    private val dayFmt = SimpleDateFormat("yyyy.M.d(E)", Locale.KOREA)
    private val hm = SimpleDateFormat("HH:mm", Locale.KOREA)
    private fun dp(v: Int) = Ui.dp(this, v)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        account = AccountUi(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        val col = Ui.column(this)
        val top = Ui.row(this)
        top.addView(Ui.button(this, "← 뒤로") { finish() }.apply { minHeight = dp(44) }, LinearLayout.LayoutParams(WRAP, WRAP))
        val g = Cloud.active
        val gl = g?.let { if (it.name.trim().endsWith("그룹")) it.name else "${it.name} 그룹" } ?: "그룹"
        top.addView(Ui.text(this, 19f, Ui.TEXT, true, "$gl 출조 기록"), LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(14) })
        col.addView(top)
        GroupBand.view(this, false)?.let { col.addView(it, Ui.lp(this, MATCH, WRAP, top = 12)) }
        periodRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(periodRow, Ui.lp(this, MATCH, WRAP, top = 14))
        peopleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(peopleRow) }, Ui.lp(this, MATCH, WRAP, top = 8))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(body, Ui.lp(this, MATCH, WRAP, top = 6))
        // 맨 아래 고정 띠 (꾹 눌러 고를 때만)
        delBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(dp(16), dp(8), dp(16), dp(12)); setBackgroundColor(Ui.BG)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG)
            addView(ScrollView(this@GroupTripsActivity).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(col) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(delBar, LinearLayout.LayoutParams(MATCH, WRAP))
        })
        if (g == null) { body.addView(Ui.text(this, 14f, Ui.SUB, false, "그룹에 가입하면 그룹 출조를 모아 볼 수 있어요")); return }
        load()
    }

    private fun load() {
        val g = Cloud.active ?: return
        body.removeAllViews()
        body.addView(Ui.text(this, 13f, Ui.SUB, false, "불러오는 중…"), Ui.lp(this, MATCH, WRAP, top = 10))
        Cloud.recentTripsOf(g) { list, err ->
            if (isFinishing) return@recentTripsOf
            if (list == null) { body.removeAllViews(); body.addView(Ui.text(this, 13f, Ui.ORANGE, false, err ?: "불러오기 실패")); return@recentTripsOf }
            trips = list.sortedByDescending { it.start }
            // 열어 봤으니 새 출조 글은 확인한 걸로
            GroupNews.markKindSeen(g.id, "trip")
            render()
        }
    }

    private fun pill(label: String, on: Boolean, onColor: Int, onClick: () -> Unit): TextView =
        Ui.text(this, 13f, if (on) Ui.BG else Ui.TEXT, true, label).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            background = if (on) Ui.bg(this@GroupTripsActivity, onColor, 99) else Ui.bg(this@GroupTripsActivity, Color.TRANSPARENT, 99, Ui.LINE)
            setOnClickListener { onClick() }
        }

    private fun since(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return when (period) {
            0 -> { c.set(Calendar.DAY_OF_MONTH, 1); c.timeInMillis }
            1 -> { c.add(Calendar.MONTH, -3); c.timeInMillis }
            2 -> { c.set(Calendar.DAY_OF_YEAR, 1); c.timeInMillis }
            else -> 0L
        }
    }

    private fun render() {
        periodRow.removeAllViews()
        listOf("이번 달", "3개월", "올해", "전체").forEachIndexed { i, l ->
            periodRow.addView(pill(l, period == i, Ui.TEXT) { period = i; rankSpecies = null; render() },
                LinearLayout.LayoutParams(0, dp(36), 1f).apply { if (i > 0) leftMargin = dp(6) })
        }
        val inPeriod = trips.filter { it.start >= since() }
        // 사람: 기록이 있는 사람 (나 먼저)
        val owners = trips.map { it.owner to it.ownerName }.distinctBy { it.first }
        peopleRow.removeAllViews()
        fun addPerson(label: String, id: String?) {
            peopleRow.addView(pill(label, person == id, Ui.GREEN) { person = id; rankSpecies = null; render() },
                LinearLayout.LayoutParams(WRAP, dp(34)).apply { rightMargin = dp(6) })
        }
        addPerson("모든 사람", null)
        if (owners.any { it.first == Cloud.uid }) addPerson("나", Cloud.uid)
        owners.filter { it.first != Cloud.uid }.forEach { addPerson(it.second, it.first) }

        val list = inPeriod.filter { person == null || it.owner == person }
        body.removeAllViews()
        if (list.isEmpty()) {
            body.addView(Ui.text(this, 14f, Ui.SUB, false, "이 기간에는 그룹에 올라온 출조가 없어요"), Ui.lp(this, MATCH, WRAP, top = 14))
            return
        }
        // 어종별 합계
        val totals = LinkedHashMap<String, Int>()
        list.forEach { t -> for (s in t.slots()) { val n = t.count(s); if (n > 0) totals[t.name(s)] = (totals[t.name(s)] ?: 0) + n } }
        val sorted = totals.entries.sortedByDescending { it.value }
        val stat = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun statBox(label: String, value: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.bg(this@GroupTripsActivity, Ui.CARD, 12)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(Ui.text(this@GroupTripsActivity, 11f, Ui.SUB, false, label))
            addView(Ui.text(this@GroupTripsActivity, 20f, Ui.TEXT, true, value).apply { maxLines = 1 })
        }
        stat.addView(statBox("출조", "${list.size}번"), LinearLayout.LayoutParams(0, WRAP, 1f))
        val top2 = sorted.take(2)
        stat.addView(statBox(top2.joinToString(" · ") { it.key }.ifBlank { "조과" },
            top2.joinToString(" · ") { it.value.toString() }.ifBlank { "0" }),
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        body.addView(stat, Ui.lp(this, MATCH, WRAP, top = 10))
        if (sorted.size > 2) {
            body.addView(Ui.text(this, 12f, Ui.SUB, false, sorted.drop(2).joinToString(" · ") { "${it.key} ${it.value}" }),
                Ui.lp(this, MATCH, WRAP, top = 6))
        }

        // 많이 잡은 사람 순위 (모든 사람일 때)
        if (person == null && sorted.isNotEmpty()) {
            val sp = rankSpecies?.takeIf { totals.containsKey(it) } ?: sorted[0].key
            val card = Ui.card(this)
            card.background = Ui.bg(this, Ui.CARD, 14, Ui.LINE)
            val pname = listOf("이번 달", "3개월", "올해", "전체")[period]
            card.addView(Ui.text(this, 12f, Ui.SUB, false, "$pname $sp 많이 잡은 사람"))
            if (sorted.size > 1) {
                val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                sorted.forEach { e ->
                    chips.addView(pill(e.key, e.key == sp, Ui.BLUE) { rankSpecies = e.key; render() },
                        LinearLayout.LayoutParams(WRAP, dp(30)).apply { rightMargin = dp(6) })
                }
                card.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, Ui.lp(this, MATCH, WRAP, top = 6))
            }
            val per = HashMap<String, Int>()
            list.forEach { t -> for (s in t.slots()) if (t.name(s) == sp) per[t.owner] = (per[t.owner] ?: 0) + t.count(s) }
            val nameOf = owners.toMap()
            per.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(5).forEachIndexed { i, e ->
                val r = Ui.row(this)
                val me = e.key == Cloud.uid
                r.addView(Ui.text(this, 15f, if (i == 0) Color.parseColor("#F2C14E") else Ui.TEXT, i == 0 || me,
                    (if (i == 0) "♛ " else "${i + 1}  ") + (nameOf[e.key] ?: "?") + if (me) " (나)" else ""), LinearLayout.LayoutParams(0, WRAP, 1f))
                r.addView(Ui.text(this, 15f, Ui.TEXT, true, e.value.toString()))
                card.addView(r, Ui.lp(this, MATCH, WRAP, top = 6))
            }
            body.addView(card, Ui.lp(this, MATCH, WRAP, top = 10))
        }

        // 출조 카드
        body.addView(Ui.text(this, 12f, Ui.SUB, false, "카드를 누르면 함께한 출조 지도·순위, 사진 · 꾹 누르면 골라서 그룹에서 내리기"), Ui.lp(this, MATCH, WRAP, top = 14))
        shown = list.take(150)
        if (selMode) body.addView(selHeader(), Ui.lp(this, MATCH, WRAP, top = 8))
        shown.forEach { t ->
            val sel = t.id in selected
            val c = Ui.card(this).apply {
                isClickable = true
                setOnClickListener {
                    if (selMode) { if (canDel(t)) { if (!selected.add(t.id)) selected.remove(t.id); render() } else toast("그룹장·관리자·올린 사람만 내릴 수 있어요") }
                    else openTrip(t)
                }
                setOnLongClickListener {
                    if (!canDel(t)) { toast("내 글만 고를 수 있어요 (그룹장·관리자는 모든 글)"); return@setOnLongClickListener true }
                    selMode = true; selected.add(t.id); render(); true
                }
                if (selMode && sel) background = Ui.bg(this@GroupTripsActivity, Ui.CARD, 16, Ui.GREEN)
            }
            val r = Ui.row(this)
            if (selMode) r.addView(Ui.text(this, 14f, if (sel) Ui.BG else Ui.SUB, true, if (sel) "✓" else "").apply {
                gravity = Gravity.CENTER
                background = if (sel) Ui.bg(this@GroupTripsActivity, Ui.GREEN, 5) else Ui.bg(this@GroupTripsActivity, Color.TRANSPARENT, 5, if (canDel(t)) Ui.SUB else Ui.LINE)
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) })
            val where = listOf(t.place().ifBlank { null }, if (t.cat.isNotBlank()) Store.catShort(t.cat) else null).filterNotNull().joinToString(" · ")
            r.addView(Ui.text(this, 15f, Ui.TEXT, true, dayFmt.format(Date(t.start)) + if (where.isNotBlank()) " $where" else ""),
                LinearLayout.LayoutParams(0, WRAP, 1f))
            r.addView(Ui.text(this, 12f, if (t.owner == Cloud.uid) Ui.GREEN else Ui.SUB, true, if (t.owner == Cloud.uid) "나" else t.ownerName))
            c.addView(r)
            c.addView(Ui.agoChip(this, t.start), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = Ui.dp(this@GroupTripsActivity, 2) })
            val endText = if (t.end > 0) hm.format(Date(t.end)) else "출조 중"
            val counts = t.slots().filter { t.count(it) > 0 || it == 1 }.joinToString(" · ") { "${t.name(it)} ${t.count(it)}" }
            c.addView(Ui.text(this, 13f, Color.parseColor("#B8C7D4"), false,
                "$counts · ${Tide.mulName(t.start).substringBefore(" (")} · ${hm.format(Date(t.start))}~$endText" +
                    (if (t.photoCount > 0) " · 사진 ${t.photoCount}" else "")), Ui.lp(this, MATCH, WRAP, top = 3))
            stampRow(this, t.shownSlots().map { t.name(it) }, t.shownSlots().map { t.count(it) })?.let { c.addView(it, Ui.lp(this, MATCH, WRAP, top = 6)) }
            if (t.mthumb.isNotBlank()) Ui.withRightPhoto(this, c, MainPhoto.decode(t.mthumb), maxOf(0, t.photoCount - 1)) {
                Cloud.active?.let { g -> MainPhoto.showBig(this, t.bigMainPath(g.id), t.mthumb, "${t.ownerName} · ${t.line()}") }
            }
            body.addView(c, Ui.lp(this, MATCH, WRAP, top = 8))
        }
        if (list.size > 150) body.addView(Ui.text(this, 12f, Ui.SUB, false, "최근 150개까지 보여요"), Ui.lp(this, MATCH, WRAP, top = 8))
        paintDelBar()
    }

    /** 고르기 줄: ☐ 전체 선택 (N개) · N개 선택됨 · ✕ */
    private fun selHeader(): View {
        val can = shown.filter { canDel(it) }
        val all = can.isNotEmpty() && can.all { it.id in selected }
        val r = Ui.row(this).apply { setPadding(dp(12), dp(8), dp(12), dp(8)); background = Ui.bg(this@GroupTripsActivity, Ui.CARD, 12, Ui.LINE) }
        r.addView(Ui.text(this, 15f, Ui.TEXT, true, (if (all) "☑" else "☐") + " 전체 선택 (${can.size}개)").apply {
            setOnClickListener { if (all) selected.clear() else can.forEach { selected.add(it.id) }; render() }
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(Ui.text(this, 13f, Ui.GREEN, true, "${selected.size}개 선택됨"))
        r.addView(Ui.text(this, 18f, Ui.SUB, true, "  ✕").apply { setOnClickListener { endSel() } })
        return r
    }

    private fun endSel() { selMode = false; selected.clear(); render() }

    @Deprecated("old")
    override fun onBackPressed() { if (selMode) endSel() else @Suppress("DEPRECATION") super.onBackPressed() }

    private fun paintDelBar() {
        if (!::delBar.isInitialized) return
        delBar.removeAllViews()
        if (!selMode) { delBar.visibility = View.GONE; return }
        delBar.visibility = View.VISIBLE
        delBar.addView(Ui.text(this, 16f, Color.WHITE, true, "선택한 ${selected.size}개 그룹에서 내리기").apply {
            gravity = Gravity.CENTER
            background = Ui.bg(this@GroupTripsActivity, if (selected.isEmpty()) Ui.LINE else Color.parseColor("#C8404C"), 14)
            setOnClickListener { if (selected.isNotEmpty()) confirmRemove(trips.filter { it.id in selected }) }
        }, LinearLayout.LayoutParams(MATCH, dp(52)))
    }

    /** 그룹에서 내리기 (여러 개): 그룹에서만 사라지고 올린 사람 폰 기록은 그대로 · 남의 글은 휴지통 30일 + 알림 */
    private fun confirmRemove(list: List<Cloud.RemoteTrip>) {
        if (list.isEmpty()) return
        val others = list.count { it.owner != Cloud.uid }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("${list.size}개를 그룹에서 내릴까요?")
            .setMessage("이 그룹 화면에서만 사라지고, 올린 사람 폰의 출조 기록은 그대로예요." +
                if (others > 0) "\n남의 글 ${others}개는 휴지통에 30일 보관되고 올린 사람에게 알림이 가요." else "")
            .setPositiveButton("내리기") { _, _ ->
                toast("내리는 중…")
                var left = list.size; var fail = 0
                list.forEach { t ->
                    Cloud.deleteGroupTrip(t.id) { err ->
                        if (err != null) fail++
                        if (--left == 0) {
                            toast(if (fail == 0) "${list.size}개를 그룹에서 내렸어요" else "${list.size - fail}개 내림 · ${fail}개 실패")
                            selMode = false; selected.clear(); paintDelBar(); load()
                        }
                    }
                }
            }
            .setNegativeButton("취소", null).show()
    }

    private fun openTrip(t: Cloud.RemoteTrip) {
        val items = mutableListOf("함께한 출조 지도·순위")
        if (t.photoCount > 0) items.add("사진 보기 (${t.photoCount}장)")
        val mine = t.owner == Cloud.uid
        // 내 글: 내 출조 기록 열어서 수정 (폰에 기록이 있을 때) · 고친 뒤 다시 올리면 그룹에도 반영
        val local = if (mine) t.id.substringAfter("_").toLongOrNull()?.let { Store.find(it) } else null
        if (local != null) items.add("✎ 내 출조 기록 열기 (수정)")
        if (mine) items.add("🗑 그룹에서 내리기")
        else if (Cloud.isOwner) items.add("🗑 그룹에서 내리기 (그룹장)")
        else if (Cloud.isAdmin) items.add("🗑 그룹에서 내리기 (관리자)")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("${t.ownerName} · ${dayFmt.format(Date(t.start))} ${hm.format(Date(t.start))} 출조")
            .setItems(items.toTypedArray()) { _, i ->
                when {
                    items[i].startsWith("함께한") -> account.showTogether(t)
                    items[i].startsWith("사진") -> account.showPhotos(t)
                    items[i].startsWith("✎") -> local?.let { startActivity(Intent(this, TripActivity::class.java).putExtra("id", it.id)) }
                    else -> confirmRemove(listOf(t))
                }
            }
            .show()
    }
}
