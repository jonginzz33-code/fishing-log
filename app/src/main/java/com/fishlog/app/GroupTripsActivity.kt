package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
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
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(col) })
        if (g == null) { body.addView(Ui.text(this, 14f, Ui.SUB, false, "그룹에 가입하면 그룹 출조를 모아 볼 수 있어요")); return }
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
        list.forEach { t -> for (s in 1..2) { val n = t.count(s); if (n > 0) totals[t.name(s)] = (totals[t.name(s)] ?: 0) + n } }
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
            list.forEach { t -> for (s in 1..2) if (t.name(s) == sp) per[t.owner] = (per[t.owner] ?: 0) + t.count(s) }
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
        body.addView(Ui.text(this, 12f, Ui.SUB, false, "카드를 누르면 함께한 출조 지도·순위, 사진"), Ui.lp(this, MATCH, WRAP, top = 14))
        list.take(150).forEach { t ->
            val c = Ui.card(this).apply { isClickable = true; setOnClickListener { openTrip(t) } }
            val r = Ui.row(this)
            val where = listOf(t.place().ifBlank { null }, if (t.cat.isNotBlank()) Store.catShort(t.cat) else null).filterNotNull().joinToString(" · ")
            r.addView(Ui.text(this, 15f, Ui.TEXT, true, dayFmt.format(Date(t.start)) + if (where.isNotBlank()) " $where" else ""),
                LinearLayout.LayoutParams(0, WRAP, 1f))
            r.addView(Ui.text(this, 12f, if (t.owner == Cloud.uid) Ui.GREEN else Ui.SUB, true, if (t.owner == Cloud.uid) "나" else t.ownerName))
            c.addView(r)
            c.addView(Ui.agoChip(this, t.start), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = Ui.dp(this@GroupTripsActivity, 2) })
            val endText = if (t.end > 0) hm.format(Date(t.end)) else "출조 중"
            val counts = (1..2).filter { t.count(it) > 0 || it == 1 }.joinToString(" · ") { "${t.name(it)} ${t.count(it)}" }
            c.addView(Ui.text(this, 13f, Color.parseColor("#B8C7D4"), false,
                "$counts · ${Tide.mulName(t.start).substringBefore(" (")} · ${hm.format(Date(t.start))}~$endText" +
                    (if (t.photoCount > 0) " · 사진 ${t.photoCount}" else "")), Ui.lp(this, MATCH, WRAP, top = 3))
            stampRow(this, listOf(t.name(1), t.name(2)), listOf(t.count(1), t.count(2)))?.let { c.addView(it, Ui.lp(this, MATCH, WRAP, top = 6)) }
            body.addView(c, Ui.lp(this, MATCH, WRAP, top = 8))
        }
        if (list.size > 150) body.addView(Ui.text(this, 12f, Ui.SUB, false, "최근 150개까지 보여요"), Ui.lp(this, MATCH, WRAP, top = 8))
    }

    private fun openTrip(t: Cloud.RemoteTrip) {
        val items = mutableListOf("함께한 출조 지도·순위")
        if (t.photoCount > 0) items.add("사진 보기 (${t.photoCount}장)")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("${t.ownerName} · ${dayFmt.format(Date(t.start))} ${hm.format(Date(t.start))} 출조")
            .setItems(items.toTypedArray()) { _, i ->
                if (items[i].startsWith("함께한")) account.showTogether(t) else account.showPhotos(t)
            }
            .show()
    }
}
