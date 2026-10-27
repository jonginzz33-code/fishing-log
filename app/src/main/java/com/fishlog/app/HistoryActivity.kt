package com.fishlog.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 지난 출조 · 내 기록 보관함 */
class HistoryActivity : Activity() {

    private lateinit var summary: LinearLayout
    private lateinit var chips: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var restoreBtn: android.widget.TextView
    private var filter: String? = null
    private var byDate = false
    private lateinit var modeRow: LinearLayout
    private lateinit var calCard: LinearLayout
    private lateinit var cal: MiniCalendar
    private val keyFmt = SimpleDateFormat("yyyyMMdd", Locale.KOREA)
    private val ymFmt = SimpleDateFormat("yyyyMM", Locale.KOREA)
    private val listener: () -> Unit = { render() }

    private val monthFmt = SimpleDateFormat("yyyy년 M월", Locale.KOREA)
    private val dayFmt = SimpleDateFormat("M월 d일 (E) HH:mm", Locale.KOREA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        val col = Ui.column(this)
        val top = Ui.row(this)
        top.addView(Ui.button(this, "← 뒤로") { finish() }.apply { minHeight = Ui.dp(this@HistoryActivity, 44) },
            LinearLayout.LayoutParams(WRAP, WRAP))
        top.addView(Ui.text(this, 20f, Ui.TEXT, true, "지난 출조"),
            LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = Ui.dp(this@HistoryActivity, 14) })
        col.addView(top)

        summary = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(summary, Ui.lp(this, MATCH, WRAP, top = 16))

        // 전체 보기 / 날짜별 보기
        modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(modeRow, Ui.lp(this, MATCH, WRAP, top = 16))
        calCard = Ui.card(this)
        col.addView(calCard, Ui.lp(this, MATCH, WRAP, top = 10))
        cal = MiniCalendar(this, calCard)
        cal.onChange = { render() }
        Store.history.minOfOrNull { it.start }?.let {
            cal.minYear = java.util.Calendar.getInstance().apply { timeInMillis = it }.get(java.util.Calendar.YEAR)
        }
        Store.history.firstOrNull()?.let { cal.month.timeInMillis = it.start }

        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) },
            Ui.lp(this, MATCH, WRAP, top = 14))

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list, Ui.lp(this, MATCH, WRAP, top = 6))

        restoreBtn = Ui.button(this, "서버에서 내 기록 복원하기") { restore() }
        col.addView(restoreBtn, Ui.lp(this, MATCH, WRAP, top = 20))
        col.addView(Ui.text(this, 12f, Ui.SUB, false,
            "로그인하면 출조 기록이 자동으로 서버에 백업돼요. 폰을 바꾸면 같은 구글 계정으로 로그인하고 복원을 누르세요. (사진 원본은 폰에만 저장)"),
            Ui.lp(this, MATCH, WRAP, top = 8))

        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(col) })
    }

    override fun onResume() { super.onResume(); Store.addListener(listener); render() }
    override fun onPause() { Store.removeListener(listener); super.onPause() }

    private fun restore() {
        if (Cloud.user == null) {
            AccountUi(this).signIn { restore() }
            return
        }
        Toast.makeText(this, "서버에서 불러오는 중…", Toast.LENGTH_SHORT).show()
        Cloud.restore { n, err ->
            Toast.makeText(this, err ?: if (n == 0) "새로 가져올 기록이 없어요 (이미 다 있어요)" else "${n}개 출조를 가져왔어요", Toast.LENGTH_LONG).show()
        }
    }

    private fun render() {
        val trips = Store.history
        // 요약
        summary.removeAllViews()
        val totals = LinkedHashMap<String, Int>()
        trips.forEach { t -> t.catches.forEach { c -> totals[t.name(c.slot)] = (totals[t.name(c.slot)] ?: 0) + 1 } }
        val tiles = mutableListOf("출조" to "${trips.size}회", "총 조과" to "${trips.sumOf { it.catches.size }}")
        totals.entries.sortedByDescending { it.value }.take(2).forEach { tiles.add(it.key to it.value.toString()) }
        tiles.forEachIndexed { i, (label, value) ->
            summary.addView(Ui.stat(this, label, value), LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                if (i > 0) leftMargin = Ui.dp(this@HistoryActivity, 8)
            })
        }

        // 보기 방식
        modeRow.removeAllViews()
        listOf(false to "전체 보기", true to "날짜별 보기").forEachIndexed { i, (m, label) ->
            val on = byDate == m
            modeRow.addView(Ui.text(this, 15f, if (on) Ui.BG else Ui.TEXT, true, label).apply {
                gravity = android.view.Gravity.CENTER
                minHeight = Ui.dp(this@HistoryActivity, 44)
                background = if (on) Ui.bg(this@HistoryActivity, Ui.TEXT, 22) else Ui.bg(this@HistoryActivity, Ui.CARD, 22, Ui.LINE)
                setOnClickListener { byDate = m; render() }
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) leftMargin = Ui.dp(this@HistoryActivity, 8) })
        }
        calCard.visibility = if (byDate) View.VISIBLE else View.GONE
        if (byDate) {
            cal.marks = trips.groupBy { keyFmt.format(Date(it.start)) }
                .mapValues { (_, l) -> l.map { if (it.cat == "night") Ui.BLUE else if (it.cat == "walk") Ui.GREEN else Ui.ORANGE } }
            cal.render()
        }

        // 어종 필터
        chips.removeAllViews()
        val names = listOf<String?>(null) + totals.keys.toList()
        names.forEach { n ->
            val sel = n == filter
            chips.addView(Ui.text(this, 14f, if (sel) Ui.BG else Ui.TEXT, true, n ?: "전체").apply {
                setPadding(Ui.dp(this@HistoryActivity, 14), Ui.dp(this@HistoryActivity, 8), Ui.dp(this@HistoryActivity, 14), Ui.dp(this@HistoryActivity, 8))
                background = if (sel) Ui.bg(this@HistoryActivity, Ui.TEXT, 99) else Ui.bg(this@HistoryActivity, Ui.CARD, 99, Ui.LINE)
                setOnClickListener { filter = n; render() }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = Ui.dp(this@HistoryActivity, 8) })
        }

        // 목록 (월별)
        list.removeAllViews()
        val shown = trips.filter { t -> filter == null || (t.name(1) == filter && t.count(1) > 0) || (t.name(2) == filter && t.count(2) > 0) }
            .filter { t ->
                if (!byDate) true
                else if (cal.wholeMonth || cal.selectedDay == null) ymFmt.format(Date(t.start)) == cal.monthKey()
                else keyFmt.format(Date(t.start)) == cal.selectedDay
            }
        if (shown.isEmpty()) {
            list.addView(Ui.text(this, 14f, Ui.SUB, false, when {
                trips.isEmpty() -> "종료한 출조가 여기에 쌓여요"
                byDate -> "이 날짜에는 출조 기록이 없어요"
                else -> "이 어종을 잡은 출조가 없어요"
            }),
                Ui.lp(this, MATCH, WRAP, top = 10))
        }
        var lastMonth = ""
        shown.forEach { t ->
            val m = monthFmt.format(Date(t.start))
            if (m != lastMonth) {
                lastMonth = m
                list.addView(Ui.text(this, 14f, Ui.SUB, true, m), Ui.lp(this, MATCH, WRAP, top = 16))
            }
            val dur = ((if (t.end > 0) t.end else t.start) - t.start) / 60000
            val card = Ui.card(this).apply {
                isClickable = true
                setOnClickListener { startActivity(Intent(this@HistoryActivity, TripActivity::class.java).putExtra("id", t.id)) }
            }
            val head = Ui.row(this)
            head.addView(Ui.text(this, 15f, Ui.TEXT, true, dayFmt.format(Date(t.start)) +
                (if (t.place().isNotBlank()) " · " + t.place() else "")), LinearLayout.LayoutParams(0, WRAP, 1f))
            if (t.photos.isNotEmpty()) head.addView(Ui.chip(this, "사진 ${t.photos.size}"),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = Ui.dp(this@HistoryActivity, 6) })
            card.addView(head)
            card.addView(Ui.text(this, 14f, Ui.TEXT, false,
                "${t.name(1)} ${t.count(1)} · ${t.name(2)} ${t.count(2)} · 총 ${t.catches.size}"), Ui.lp(this, MATCH, WRAP, top = 4))
            val tags = mutableListOf("${dur / 60}시간 ${dur % 60}분")
            if (t.cat.isNotBlank()) tags.add(0, Store.catShort(t.cat))
            if (t.uploaded) tags.add("그룹에 올림")
            if (t.pub) tags.add("전체 공개")
            if (t.backedUp) tags.add("백업됨")
            card.addView(Ui.text(this, 12f, Ui.SUB, false, tags.joinToString(" · ")), Ui.lp(this, MATCH, WRAP, top = 4))
            list.addView(card, Ui.lp(this, MATCH, WRAP, top = 8))
        }
        restoreBtn.visibility = View.VISIBLE
    }
}
