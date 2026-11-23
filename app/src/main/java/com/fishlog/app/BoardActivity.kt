package com.fishlog.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.location.LocationManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 전체 조황 · 포인트 도감 */
class BoardActivity : Activity() {
    /** v3.9.2: 화면 크기 자동 맞춤 (Ui.fit) */
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(Ui.fit(newBase)) }


    private lateinit var tabRow: LinearLayout
    private lateinit var catRow: LinearLayout
    private lateinit var periodRow: LinearLayout
    private lateinit var listBox: LinearLayout
    private lateinit var nickText: TextView
    private lateinit var blockBtn: TextView

    private var all: List<PublicBoard.PTrip> = emptyList()
    private var tab = 0            // 0 = 최근 조황, 1 = 포인트 도감
    private var cat = ""           // "" = 전체
    // v6.28 (280): 기간 = 📅 날짜 칸 (시작일 ~ 끝일) 또는 '전체 기간' · 마지막으로 고른 기간 기억
    private var allTime = false
    private var fromDay = 0L       // 시작일 0시
    private var toDay = 0L         // 끝일 0시 (그날 끝까지)
    private var species: String? = null
    private var nearFirst = false
    private var loaded = false

    // 차단·신고·정지 (불러온 값)
    private var reports: Map<String, Int> = emptyMap()
    private var banned: Set<String> = emptySet()

    private val dayFmt = SimpleDateFormat("yyyy.M.d (E)", Locale.KOREA)
    private val dateFmt = SimpleDateFormat("yy.M.d (E)", Locale.KOREA)
    private val hm = SimpleDateFormat("HH:mm", Locale.KOREA)

    private fun dp(v: Int) = Ui.dp(this, v)

    // ---- 꾹 눌러서 여러 개 선택 (내 글만, 운영자는 모든 글) ----
    private lateinit var delBar: LinearLayout
    private var selMode = false
    private val selected = LinkedHashSet<String>()
    private var selectable: List<PublicBoard.PTrip> = emptyList()
    private val SEL_RED = android.graphics.Color.parseColor("#E8505B")
    private fun canSelect(t: PublicBoard.PTrip) = Cloud.isAdmin || t.owner == Cloud.uid

    private fun selHeader(): View {
        val all = selectable.isNotEmpty() && selectable.all { it.id in selected }
        val r = Ui.row(this).apply {
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = Ui.bg(this@BoardActivity, android.graphics.Color.parseColor("#2A1418"), 14, SEL_RED)
        }
        r.addView(Ui.text(this, 15f, Ui.TEXT, true, (if (all) "☑" else "☐") + " 전체 선택 (${selectable.size}개)").apply {
            setOnClickListener { if (all) selected.clear() else selected.addAll(selectable.map { it.id }); render() }
        })
        r.addView(Ui.text(this, 13f, android.graphics.Color.parseColor("#FF8A92"), true, "${selected.size}개 선택됨").apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(Ui.text(this, 15f, Ui.SUB, true, "  ✕").apply { setOnClickListener { endSel() } })
        return r
    }

    private fun paintDelBar() {
        delBar.removeAllViews()
        if (!selMode) { delBar.visibility = View.GONE; return }
        delBar.visibility = View.VISIBLE
        delBar.addView(Ui.text(this, 15f, android.graphics.Color.WHITE, true,
            if (selected.isEmpty()) "내릴 글을 눌러서 고르세요" else "🗑 선택한 ${selected.size}개 내리기").apply {
            gravity = Gravity.CENTER; setPadding(0, dp(13), 0, dp(13))
            background = Ui.bg(this@BoardActivity, if (selected.isEmpty()) Ui.LINE else SEL_RED, 14)
            setOnClickListener { if (selected.isNotEmpty()) confirmRemoveSelected() }
        }, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun endSel() { selMode = false; selected.clear(); render() }

    @Deprecated("back")
    override fun onBackPressed() { if (selMode) endSel() else @Suppress("DEPRECATION") super.onBackPressed() }

    private fun confirmRemoveSelected() {
        val ts = all.filter { it.id in selected }
        AlertDialog.Builder(this, R.style.FlAlert)
            .setTitle("${ts.size}개 공개 출조를 내릴까요?")
            .setMessage("전체 게시판과 포인트 도감 계산에서 빠져요. 각자의 폰 기록과 그룹 공유는 그대로예요.")
            .setPositiveButton("${ts.size}개 내리기") { _, _ ->
                var left = ts.size; var fail = 0
                ts.forEach { t ->
                    val local = if (t.owner == Cloud.uid) t.id.substringAfter("_").toLongOrNull() else null
                    PublicBoard.unpublish(t.id, local) { err ->
                        if (err != null) fail++ else all = all.filter { it.id != t.id }
                        left--
                        if (left == 0) { toast(if (fail == 0) "${ts.size}개를 내렸어요" else "${fail}개는 못 내렸어요"); endSel() }
                    }
                }
            }
            .setNegativeButton("취소", null).show()
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        tab = try { getSharedPreferences("fishlog", MODE_PRIVATE).getInt("board_tab", 0).coerceIn(0, 1) } catch (_: Exception) { 0 }
        super.onCreate(savedInstanceState)
        Store.init(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        val col = Ui.column(this)
        val top = Ui.row(this)
        top.addView(Ui.button(this, "← 뒤로") { finish() }.apply { minHeight = dp(44) }, LinearLayout.LayoutParams(WRAP, WRAP))
        top.addView(Ui.text(this, 18f, Ui.TEXT, true, "전체 조황 · 포인트 게시판").apply { maxLines = 1 }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(14) })
        col.addView(top)
        val nickRow = Ui.row(this)
        nickText = Ui.text(this, 12f, Ui.SUB, false).apply { setOnClickListener { askNick() } }
        nickRow.addView(nickText, LinearLayout.LayoutParams(0, WRAP, 1f))
        blockBtn = Ui.text(this, 12f, Ui.TEXT, true, "차단 목록").apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = Ui.bg(this@BoardActivity, Ui.CARD, 99, Ui.LINE)
            setOnClickListener { Moderation.showBlockedList(this@BoardActivity) { refreshNick(); render() } }
        }
        nickRow.addView(blockBtn, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
        col.addView(nickRow, Ui.lp(this, MATCH, WRAP, top = 8))

        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        col.addView(tabRow, Ui.lp(this, MATCH, WRAP, top = 10))
        // 종류 칩: 글자가 줄바꿈되지 않게 가로로 밀어서 보기
        // v6.60 (339): 종류 · 기간을 '🔎 걸러 보기' 상자 하나로
        val (filterBox, filterIn) = Ui.panel(this, "🔎 걸러 보기")
        col.addView(filterBox, Ui.lp(this, MATCH, WRAP, top = 10))
        // v6.62 (335): 아직 게시판에 안 올린 내 출조 → 한 번에 공개하기
        pubBanner = Ui.text(this, 13.5f, android.graphics.Color.parseColor("#FFE2B8"), true, "").apply {
            setPadding(dp(14), dp(11), dp(14), dp(11)); visibility = View.GONE
            background = Ui.bg(this@BoardActivity, android.graphics.Color.parseColor("#3A2A12"), 14, android.graphics.Color.parseColor("#F2A13C"))
            setOnClickListener { openUnpublished() }
        }
        col.addView(pubBanner, Ui.lp(this, MATCH, WRAP, top = 8))
        paintPubBanner()
        catRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        filterIn.addView(Ui.hscroll(this, catRow),
            Ui.lp(this, MATCH, WRAP, top = 2))
        periodRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        filterIn.addView(periodRow, Ui.lp(this, MATCH, WRAP, top = 8))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox, Ui.lp(this, MATCH, WRAP, top = 6))
        // 맨 아래 고정 띠 (꾹 눌러 선택할 때만)
        delBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(dp(16), dp(8), dp(16), dp(12)); setBackgroundColor(Ui.BG)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG)
            addView(ScrollView(this@BoardActivity).apply { isFillViewport = true; addView(col) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(delBar, LinearLayout.LayoutParams(MATCH, WRAP))
        })
        loadRange()
        renderControls()
        load()
    }

    private fun refreshNick() {
        nickText.text = "내 게시판 이름: ${PublicBoard.nickShown()} ✎  ·  내 출조 공개는 출조 종료 때 또는 출조 기록 화면에서"
        val n = Moderation.blocked().size
        blockBtn.text = if (n > 0) "차단 목록 ($n)" else "차단 목록"
    }

    /** 게시판에 내 닉네임으로 올릴지, 익명으로 올릴지 (닉네임 자체는 개인 설정에서) */
    private fun askNick() {
        val items = arrayOf((if (Store.nick != "") "✓ " else "") + "내 닉네임으로 (${Cloud.nick ?: "아직 없음"})",
            (if (Store.nick == "") "✓ " else "") + "익명으로", "닉네임 정하기 · 바꾸기 (개인 설정)")
        AlertDialog.Builder(this, R.style.FlAlert)
            .setTitle("전체 게시판에 보일 이름")
            .setItems(items) { _, w ->
                when (w) {
                    0 -> { Store.nick = "me"; refreshNick() }
                    1 -> { Store.nick = ""; refreshNick() }
                    else -> startActivity(android.content.Intent(this, SettingsActivity::class.java))
                }
            }
            .show()
    }

    private fun pill(label: String, on: Boolean, size: Float = 14f, onClick: () -> Unit): TextView =
        Ui.text(this, size, if (on) Ui.BG else Ui.TEXT, true, label).apply {
            gravity = Gravity.CENTER
            minHeight = dp(40)
            setPadding(dp(14), 0, dp(14), 0)
            background = if (on) Ui.bg(this@BoardActivity, Ui.TEXT, 20) else Ui.bg(this@BoardActivity, Ui.CARD, 20, Ui.LINE)
            setOnClickListener { onClick() }
        }

    private fun renderControls() {
        refreshNick()
        tabRow.removeAllViews()
        // 맨 위 탭: 🎣 전체 조황 게시판 | 📍 전체 포인트 게시판 (마지막 본 탭 기억)
        listOf(Triple("🎣 전체 조황 게시판", "최근 출조 · 어종별 마릿수", Ui.ORANGE), Triple("📍 전체 포인트 게시판", "잘 잡히는 자리 · 지도", Ui.BLUE)).forEachIndexed { i, (t1, t2, col) ->
            val on = tab == i
            tabRow.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(6), dp(6), dp(6), dp(6))
                background = if (on) Ui.bg(this@BoardActivity, col, 14) else Ui.bg(this@BoardActivity, Ui.CARD, 14, col)
                addView(Ui.text(this@BoardActivity, 13.5f, if (on) Ui.BG else col, true, t1).apply { gravity = Gravity.CENTER; maxLines = 1 })
                addView(Ui.text(this@BoardActivity, 10f, if (on) Ui.BG else Ui.SUB, false, t2).apply { gravity = Gravity.CENTER; maxLines = 1 })
                setOnClickListener {
                    tab = i
                    try { getSharedPreferences("fishlog", MODE_PRIVATE).edit().putInt("board_tab", i).apply() } catch (_: Exception) {}
                    renderControls(); render()
                }
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
        // v6.28 (280): 출조 종류 = 글자 밑줄 탭 (옆으로 밀기)
        catRow.removeAllViews()
        (listOf("") + Store.CATS).forEachIndexed { i, c ->
            val on = cat == c
            catRow.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(if (i == 0) 2 else 10), dp(6), dp(10), 0)
                addView(Ui.text(this@BoardActivity, 15f, if (on) Ui.TEXT else Ui.SUB, on, if (c == "") "전체" else Store.catShort(c)).apply { maxLines = 1 })
                addView(View(this@BoardActivity).apply { background = Ui.bg(this@BoardActivity, if (on) Ui.ORANGE else android.graphics.Color.TRANSPARENT, 2) },
                    LinearLayout.LayoutParams(MATCH, dp(3)).apply { topMargin = dp(6) })
                setOnClickListener { cat = c; species = null; renderControls(); render() }
            }, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        // 기간: 📅 날짜 칸 + '전체 기간'
        periodRow.removeAllViews()
        val field = Ui.row(this).apply {
            setPadding(dp(12), 0, dp(12), 0); minimumHeight = dp(44)
            background = Ui.bg(this@BoardActivity, if (allTime) Ui.CARD else android.graphics.Color.parseColor("#163447"), 12, if (allTime) Ui.LINE else Ui.BLUE)
            addView(Ui.text(this@BoardActivity, 14f, if (allTime) Ui.SUB else Ui.TEXT, !allTime, "📅  " + if (allTime) "날짜 고르기" else rangeText()).apply { maxLines = 1 },
                LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(Ui.text(this@BoardActivity, 12f, Ui.SUB, false, "▾"))
            isClickable = true
            setOnClickListener { pickRange() }
        }
        periodRow.addView(field, LinearLayout.LayoutParams(0, WRAP, 1f))
        periodRow.addView(pill("전체 기간", allTime, 13f) {
            allTime = true; saveRange(); renderControls(); render()
        }.apply { minHeight = dp(44) }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
    }

    // ---------------- v6.28 (280): 기간 ----------------
    private val DAY = 86_400_000L
    private fun dayStart(ms: Long): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = ms; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    private fun addDays(day: Long, n: Int): Long = java.util.Calendar.getInstance().apply { timeInMillis = day; add(java.util.Calendar.DAY_OF_MONTH, n) }.timeInMillis
    private fun rangeText(f: Long = fromDay, t: Long = toDay): String {
        val a = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(Date(f))
        val sameY = SimpleDateFormat("yyyy", Locale.KOREA).let { it.format(Date(f)) == it.format(Date(t)) }
        val b = SimpleDateFormat(if (sameY) "MM.dd" else "yyyy.MM.dd", Locale.KOREA).format(Date(t))
        return if (f == t) a else "$a ~ $b"
    }
    private fun periodLabel() = if (allTime) "전체 기간" else rangeText()
    /** 기간 안에 든 글인가 */
    private fun inRange(t: PublicBoard.PTrip) = allTime || (t.start >= fromDay && t.start < addDays(toDay, 1))
    private fun loadRange() {
        val p = getSharedPreferences("fishlog", MODE_PRIVATE)
        val today = dayStart(System.currentTimeMillis())
        allTime = p.getBoolean("board_all", false)
        fromDay = p.getLong("board_from", 0L); toDay = p.getLong("board_to", 0L)
        // 기본 = 최근 1개월 (오늘 끝 · 한 달 전 시작)
        if (fromDay <= 0L || toDay < fromDay) { toDay = today; fromDay = java.util.Calendar.getInstance().apply { timeInMillis = today; add(java.util.Calendar.MONTH, -1) }.timeInMillis }
    }
    private fun saveRange() {
        try { getSharedPreferences("fishlog", MODE_PRIVATE).edit().putBoolean("board_all", allTime).putLong("board_from", fromDay).putLong("board_to", toDay).apply() } catch (_: Exception) {}
    }

    /** 📅 날짜 칸 → 밑에서 달력: 시작일 → 끝일 · 출조 글 있는 날 주황 점 · 빠른 선택 */
    private fun pickRange() {
        val sh = Sheet(this, 0.74f)
        var a: Long? = if (allTime) null else fromDay
        var b: Long? = if (allTime) null else toDay
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = b ?: System.currentTimeMillis(); set(java.util.Calendar.DAY_OF_MONTH, 1) }
        var month = dayStart(cal.timeInMillis)
        val postDays = all.filter { visible(it) && (cat == "" || it.cat == cat) }.map { dayStart(it.start) }.toHashSet()
        val today = dayStart(System.currentTimeMillis())
        lateinit var paint: () -> Unit
        val sel = Ui.text(this, 13f, Ui.BLUE, true, "")
        sh.head.addView(Ui.text(this, 17f, Ui.TEXT, true, "기간 고르기"), LinearLayout.LayoutParams(0, WRAP, 1f))
        sh.head.addView(sel)
        val ok = Ui.text(this, 16f, Ui.BG, true, "이 기간으로 보기").apply {
            gravity = Gravity.CENTER; minHeight = dp(50)
            setOnClickListener {
                val s0 = a ?: return@setOnClickListener toast("시작일을 눌러 주세요")
                fromDay = s0; toDay = b ?: s0; allTime = false
                saveRange(); renderControls(); render(); sh.close()
            }
        }
        sh.foot.visibility = View.VISIBLE
        sh.foot.addView(ok, LinearLayout.LayoutParams(MATCH, WRAP))
        fun quick(label: String, f: Long, t: Long) = pill(label, false, 12.5f) { a = f; b = t; month = java.util.Calendar.getInstance().apply { timeInMillis = t; set(java.util.Calendar.DAY_OF_MONTH, 1) }.let { dayStart(it.timeInMillis) }; paint() }.apply { minHeight = dp(34) }
        paint = {
            sh.body.removeAllViews()
            sel.text = when { a == null -> "시작일을 눌러요"; b == null -> rangeText(a!!, a!!).substring(5) + " ~ 끝일?"; else -> rangeText(a!!, b!!).substring(5) }
            ok.background = Ui.bg(this, if (a != null) Ui.BLUE else Ui.LINE, 14)
            // 빠른 선택
            val qs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
            val wk = java.util.Calendar.getInstance().apply { timeInMillis = today; set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.SUNDAY) }.timeInMillis
            val m1 = java.util.Calendar.getInstance().apply { timeInMillis = today; set(java.util.Calendar.DAY_OF_MONTH, 1) }.timeInMillis
            val pm1 = java.util.Calendar.getInstance().apply { timeInMillis = m1; add(java.util.Calendar.MONTH, -1) }.timeInMillis
            listOf(quick("오늘", today, today), quick("이번 주", wk, today), quick("이번 달", m1, today), quick("지난달", pm1, addDays(m1, -1))).forEachIndexed { i, v ->
                qs.addView(v, LinearLayout.LayoutParams(WRAP, WRAP).apply { if (i > 0) leftMargin = dp(6) })
            }
            sh.body.addView(Ui.hscroll(this, qs), Ui.lp(this, MATCH, WRAP, top = 2))
            // 달 넘기기
            val nav = Ui.row(this)
            fun mv(d: Int) { month = java.util.Calendar.getInstance().apply { timeInMillis = month; add(java.util.Calendar.MONTH, d) }.timeInMillis; paint() }
            nav.addView(Ui.text(this, 20f, Ui.TEXT, true, "‹").apply { gravity = Gravity.CENTER; setOnClickListener { mv(-1) } }, LinearLayout.LayoutParams(dp(44), dp(40)))
            nav.addView(Ui.text(this, 16f, Ui.TEXT, true, SimpleDateFormat("yyyy년 M월", Locale.KOREA).format(Date(month))).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, WRAP, 1f))
            nav.addView(Ui.text(this, 20f, Ui.TEXT, true, "›").apply { gravity = Gravity.CENTER; setOnClickListener { mv(1) } }, LinearLayout.LayoutParams(dp(44), dp(40)))
            sh.body.addView(nav, Ui.lp(this, MATCH, WRAP, top = 8))
            val wd = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
            listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { i, w ->
                wd.addView(Ui.text(this, 11.5f, if (i == 0) android.graphics.Color.parseColor("#FF8A92") else Ui.SUB, false, w).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            sh.body.addView(wd, Ui.lp(this, MATCH, WRAP, top = 4))
            val mc = java.util.Calendar.getInstance().apply { timeInMillis = month }
            val first = mc.get(java.util.Calendar.DAY_OF_WEEK) - 1
            val days = mc.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
            val lo = a; val hi = b ?: a
            var row: LinearLayout? = null
            for (cell in 0 until ((first + days + 6) / 7) * 7) {
                if (cell % 7 == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }; sh.body.addView(row, Ui.lp(this, MATCH, WRAP, top = 3)) }
                val dn = cell - first + 1
                if (dn < 1 || dn > days) { row!!.addView(View(this), LinearLayout.LayoutParams(0, dp(42), 1f)); continue }
                val d = addDays(month, dn - 1)
                val edge = d == lo || d == hi
                val inside = lo != null && hi != null && d > lo && d < hi
                val future = d > today
                val v = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    background = when { edge -> Ui.bg(this@BoardActivity, Ui.BLUE, 99); inside -> Ui.bg(this@BoardActivity, android.graphics.Color.parseColor("#1D4A5E"), 4); else -> null }
                    addView(Ui.text(this@BoardActivity, 14f, when { edge -> Ui.BG; future -> android.graphics.Color.parseColor("#55697A"); else -> Ui.TEXT }, edge || d == today, "$dn").apply { gravity = Gravity.CENTER })
                    addView(View(this@BoardActivity).apply { background = Ui.bg(this@BoardActivity, if (d in postDays) Ui.ORANGE else android.graphics.Color.TRANSPARENT, 99) },
                        LinearLayout.LayoutParams(dp(5), dp(5)).apply { topMargin = dp(2) })
                    setOnClickListener {
                        val x = a; val y = b
                        when {
                            x == null || y != null -> { a = d; b = null }
                            d < x -> { a = d }
                            else -> b = d
                        }
                        paint()
                    }
                }
                row!!.addView(v, LinearLayout.LayoutParams(0, dp(42), 1f))
            }
            sh.body.addView(Ui.text(this, 11.5f, Ui.SUB, false, "시작일 → 끝일 순서로 눌러요 · 주황 점 = 출조 글 있는 날").apply { gravity = Gravity.CENTER }, Ui.lp(this, MATCH, WRAP, top = 10))
        }
        paint()
        sh.show()
    }

    private fun load() {
        if (Cloud.user == null) {
            listBox.removeAllViews()
            listBox.addView(Ui.text(this, 14f, Ui.SUB, false, "구글 로그인을 하면 전체 조황을 볼 수 있어요"), Ui.lp(this, MATCH, WRAP, top = 14))
            return
        }
        if (loaded) { render(); return }
        listBox.removeAllViews()
        listBox.addView(Ui.text(this, 14f, Ui.SUB, false, "불러오는 중…"), Ui.lp(this, MATCH, WRAP, top = 14))
        var left = 5
        var got: List<PublicBoard.PTrip>? = null
        var err: String? = null
        fun step() {
            left--
            if (left > 0) return
            val list = got
            if (list == null) {
                listBox.removeAllViews()
                listBox.addView(Ui.text(this, 14f, Ui.ORANGE, false, err ?: "실패"), Ui.lp(this, MATCH, WRAP, top = 14))
                return
            }
            all = list; loaded = true
            // v6.28 (279): 글의 그룹 번호로 지금 그룹 이름 받기 (바뀐 게 있으면 다시 그림)
            GroupNames.load(list.map { it.gid }) { if (!isFinishing && !selMode) render() }
            refreshNick()
            render()
        }
        // v6.28 (280): 최근 글 전부 받고 기간은 폰에서 거름 (기간을 바꿔도 다시 안 받음)
        PublicBoard.load(0L) { list, e -> got = list; err = e; step() }
        Moderation.syncBlocked { step() }
        Moderation.reportCounts { m -> reports = m; step() }
        Moderation.loadLimit { step() }
        Moderation.bannedUids { b -> banned = b; step() }
    }

    /**
     * 보여줄 글: 출조 종류 + 내가 차단한 사람, 신고 누적 기준 이상, 관리자가 정지한 사람은 빼기
     * (내 글은 항상 보임)
     */
    private fun visible(t: PublicBoard.PTrip): Boolean {
        if (t.owner == Cloud.uid) return true
        if (Moderation.isBlocked(t.owner)) return false
        if (t.owner in banned) return false
        return (reports[t.owner] ?: 0) < Moderation.reportLimit
    }

    private fun filtered() = all.filter { (cat == "" || it.cat == cat) && inRange(it) && visible(it) }

    private fun render() {
        if (Cloud.user == null || !loaded) return
        listBox.removeAllViews()
        if (tab == 0) renderRecent() else renderPoints()
    }

    // ---------------- 최근 조황 ----------------
    private fun regionOf(t: PublicBoard.PTrip): String {
        val c = t.anyCell() ?: return ""
        // 실제 동네 이름 (처음 보는 곳은 뒤에서 찾고, 찾으면 목록 다시 그림)
        return Area.name(this, c.lat, c.lon) { areaFound() }.ifBlank { return "" } + " 부근"
    }

    private var listOpen = false
    private var areaPending = false
    /** 동네 이름을 새로 찾았으면 잠깐 모았다가 한 번만 다시 그림 */
    private fun areaFound() {
        if (areaPending || isFinishing) return
        areaPending = true
        listBox.postDelayed({ areaPending = false; if (!isFinishing && !selMode) render() }, 800)
    }

    /** 조과 하나 → 지도 점. 정확 공개면 그 자리, 아니면 칸 가운데 (radius = 원 크기, 0 = 원 없음) */
    private class SpotDot(val dot: MapDot, val radius: Double)

    private fun spotDots(list: List<Pair<PublicBoard.PTrip, PublicBoard.PCatch>>, color: (PublicBoard.PTrip, PublicBoard.PCatch) -> Int): List<SpotDot> {
        val df = SimpleDateFormat("yy.M.d", Locale.KOREA)
        return list.mapNotNull { (t, c) ->
            val sp = t.name(c.slot)
            val (pt, r) = when {
                c.hasExact -> org.osmdroid.util.GeoPoint(c.exactLat!!, c.exactLon!!) to 0.0
                c.cell != null -> org.osmdroid.util.GeoPoint(c.cell.lat, c.cell.lon) to c.cell.r
                else -> return@mapNotNull null
            }
            SpotDot(MapDot(pt, color(t, c), "${t.nick} · ${df.format(Date(c.time))} · $sp", "${t.nick} $sp", c.time, t.nick, sp,
                MapDialog.paidMark(t.cat, t.paid)), r)
        }
    }

    private fun spotLegend(spots: List<SpotDot>): String {
        val exact = spots.count { it.radius <= 0 }
        val parts = mutableListOf<String>()
        if (exact > 0) parts.add("점 = 정확히 잡은 자리")
        if (spots.any { it.radius in 1.0..100.0 }) parts.add("작은 원 = 약 150m 범위")
        if (spots.any { it.radius > 100 }) parts.add("큰 원 = 대략 공개 (반경 약 500m)")
        return parts.joinToString(" · ")
    }

    /** 어종을 누르면 그 어종이 잡힌 자리를 지도로 (key = 어종|종류) */
    private fun openSpeciesMap(key: String, list: List<PublicBoard.PTrip>) {
        val owners = list.map { it.owner }.distinct()
        val hits = list.flatMap { t -> t.catches.filter { t.key(it.slot) == key }.map { t to it } }
        val spots = spotDots(hits) { t, _ -> MapDialog.FRIEND_COLORS[owners.indexOf(t.owner).coerceAtLeast(0) % MapDialog.FRIEND_COLORS.size] }
        val label = PublicBoard.keyLabel(key)
        if (spots.isEmpty()) { toast("$label 은(는) 위치가 있는 기록이 없어요"); return }
        val periodText = periodLabel()
        val radii = HashMap<MapDot, Double>().apply { spots.forEach { put(it.dot, it.radius) } }
        MapDialog.showSpots(this, "$label · $periodText", spots.map { it.dot }, { d -> radii[d] ?: 0.0 },
            "$label ${spots.size}마리 · ${spotLegend(spots)}\n진할수록 많이 잡힌 곳 · 숫자 = 마릿수 · 누르면 날짜별 요약")
    }

    /** "낙지 (해루질)" — 종류는 작게, 선상낚시·워킹낚시처럼 긴 건 더 작고 흐리게 */
    private fun speciesTitle(key: String): CharSequence {
        val name = PublicBoard.keyName(key)
        val catLabel = Store.catLabel(PublicBoard.keyCat(key)).ifBlank { "종류 없음" }
        val sb = android.text.SpannableStringBuilder(name).append(" ")
        val start = sb.length
        sb.append("($catLabel)")
        val long = catLabel.length >= 4
        sb.setSpan(android.text.style.AbsoluteSizeSpan(if (long) 11 else 12, true), start, sb.length, 0)
        sb.setSpan(android.text.style.ForegroundColorSpan(if (long) android.graphics.Color.parseColor("#7A8D9E") else Ui.SUB), start, sb.length, 0)
        sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.NORMAL), start, sb.length, 0)
        return sb
    }

    private var liveOpen = false
    /** 맨 위 '🟢 지금 출조 중 N명' 커튼바 (전체 사용자에게 공유한 사람들) */
    private fun liveCurtain() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); visibility = View.GONE
            background = Ui.bg(this@BoardActivity, android.graphics.Color.parseColor("#16352A"), 12, Ui.GREEN)
        }
        listBox.addView(box, Ui.lp(this, MATCH, WRAP, top = 12))
        fun draw(l: List<Live.PubNow>) {
            box.removeAllViews()
            box.visibility = if (l.isEmpty()) View.GONE else View.VISIBLE
            if (l.isEmpty()) return
            val head = Ui.row(this).apply { isClickable = true; setOnClickListener { liveOpen = !liveOpen; draw(l) } }
            head.addView(Ui.text(this, 14f, android.graphics.Color.parseColor("#9FE7C1"), true, "🟢 지금 출조 중 ${l.size}명"), LinearLayout.LayoutParams(0, WRAP, 1f))
            head.addView(Ui.text(this, 12f, Ui.SUB, false, if (liveOpen) "▾ 접기" else "▸ 펼치기"))
            box.addView(head)
            if (!liveOpen) return
            l.forEach { m ->
                val mins = ((System.currentTimeMillis() - m.start) / 60_000L).coerceAtLeast(0)
                val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(2)) }
                r.addView(Ui.text(this, 13f, Ui.TEXT, true, listOf(Store.catShort(m.cat).ifBlank { "출조" }, m.name,
                    if (mins >= 60) "${mins / 60}시간 ${mins % 60}분" else "${mins}분").joinToString(" · ")))
                r.addView(Ui.text(this, 12f, android.graphics.Color.parseColor("#C9D3DE"), false,
                    listOf(m.line.ifBlank { "0마리" }, m.area.takeIf { it.isNotBlank() }?.let { "$it 부근" }).filterNotNull().joinToString(" · ")))
                box.addView(r)
            }
            box.addView(Ui.text(this, 11f, Ui.SUB, false, "출조 시작 때 '전체 사용자에게 공유하기'를 고른 사람만 · 정확한 위치는 안 보여요"),
                Ui.lp(this, MATCH, WRAP, top = 6))
        }
        draw(Live.publicCached())
        Live.publicNow { l -> if (!isFinishing) draw(l) }
    }

    private fun renderRecent() {
        liveCurtain()
        val list = filtered()
        if (list.isEmpty()) {
            listBox.addView(Ui.text(this, 14f, Ui.SUB, false, "아직 공개된 출조가 없어요. 출조를 종료할 때 '전체 게시판에도 공개'를 체크해 보세요."),
                Ui.lp(this, MATCH, WRAP, top = 12))
            return
        }
        // 어종 칩: 누르면 지도 (같은 어종도 출조 종류가 다르면 따로)
        val counts = list.flatMap { t -> t.catches.map { t.key(it.slot) } }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
        listBox.addView(Ui.text(this, 14f, Ui.TEXT, true, "어종을 누르면 잡힌 자리를 지도로 봐요"), Ui.lp(this, MATCH, WRAP, top = 14))
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        counts.chunked(2).forEach { rowItems ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
            rowItems.forEachIndexed { i, e ->
                row.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(6), dp(10), dp(6), dp(10))
                    background = Ui.bg(this@BoardActivity, Ui.CARD, 14, Ui.ORANGE)
                    isClickable = true
                    setOnClickListener { openSpeciesMap(e.key, list) }
                    addView(Ui.text(this@BoardActivity, 17f, Ui.TEXT, true).apply { text = speciesTitle(e.key); gravity = Gravity.CENTER; maxLines = 1 })
                    addView(Ui.text(this@BoardActivity, 14f, Ui.ORANGE, true, "${e.value}마리 ›").apply { gravity = Gravity.CENTER })
                }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) leftMargin = dp(8) })
            }
            repeat(2 - rowItems.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(8) }) }
            grid.addView(row, Ui.lp(this, MATCH, WRAP, top = 8))
        }
        listBox.addView(grid)

        listBox.addView(Ui.button(this, if (listOpen) "최근 공개 출조 접기 ▲" else "최근 공개 출조 ${list.size}개 보기 ▼") {
            listOpen = !listOpen; render()
        }.apply { minHeight = dp(44); textSize = 14f }, Ui.lp(this, MATCH, WRAP, top = 18))
        if (!listOpen) { paintDelBar(); return }
        selectable = list.filter { canSelect(it) }
        selected.retainAll(selectable.map { it.id }.toSet())
        if (selMode) listBox.addView(selHeader(), Ui.lp(this, MATCH, WRAP, top = 10))
        paintDelBar()
        list.forEach { t ->
            val picked = t.id in selected
            val c = Ui.card(this).apply {
                isClickable = true
                if (selMode && picked) background = Ui.bg(this@BoardActivity, Ui.CARD, 14, SEL_RED)
                if (selMode && !canSelect(t)) alpha = 0.5f
                setOnClickListener {
                    if (selMode) { if (canSelect(t)) { if (!selected.remove(t.id)) selected.add(t.id); render() } }
                    else PublicBoard.openBig(this@BoardActivity, t, REQ_PUB)   // v6.9 (255): 그룹처럼 큰 상세 화면
                }
                // 꾹 누르면: 내 글(운영자는 모든 글)은 선택 모드, 남의 글은 지금처럼 차단 메뉴(tripDetail 안)
                if (canSelect(t)) {
                    isLongClickable = true
                    setOnLongClickListener {
                        if (!selMode) { selMode = true; selected.clear() }
                        selected.add(t.id)
                        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        render(); true
                    }
                }
            }
            val r = Ui.row(this)
            if (selMode && canSelect(t)) r.addView(Ui.text(this, 14f, android.graphics.Color.WHITE, true, if (picked) "✓" else "").apply {
                gravity = Gravity.CENTER
                background = if (picked) Ui.bg(this@BoardActivity, SEL_RED, 6) else Ui.bg(this@BoardActivity, android.graphics.Color.TRANSPARENT, 6, Ui.SUB)
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) })
            r.addView(Ui.text(this, 15f, Ui.TEXT, true, dayFmt.format(Date(t.start)) +
                (if (t.cat.isNotBlank()) " · " + Store.catShort(t.cat) else "") + " · " +
                if (t.isFresh()) "🏞 민물 · " + SunPhase.riseSet(t.start, t.anyCell()?.lat, t.anyCell()?.lon) else Tide.mulName(t.start).substringBefore(" ·")), LinearLayout.LayoutParams(0, WRAP, 1f))
            val nRep = reports[t.owner] ?: 0
            if (nRep > 0) r.addView(Ui.chip(this, "⚠ 신고 $nRep", Ui.ORANGE), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
            if (t.photoCount > 0) r.addView(Ui.chip(this, "사진 ${t.photoCount}"), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
            c.addView(r)
            val pr = Ui.row(this)
            val gImg = t.groupImg(); val gName = t.groupName()   // v6.28 (279): 그룹 번호로 지금 이름
            if (gImg.isNotBlank()) try {
                val bytes = android.util.Base64.decode(gImg, android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bm ->
                    pr.addView(android.widget.ImageView(this).apply { setImageBitmap(GroupIcon.circle(bm, dp(26))) },
                        LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(6) })
                }
            } catch (_: Exception) {}
            pr.addView(Ui.text(this, 14f, Ui.TEXT, false, "${t.nick}" + (if (gName.isNotBlank()) " · $gName" else "") +
                " · ${t.speciesLine()} · 총 ${t.catches.size}"),
                LinearLayout.LayoutParams(0, WRAP, 1f))
            if (MapDialog.paidMark(t.cat, t.paid)) pr.addView(Ui.chip(this, "유료", android.graphics.Color.parseColor("#F2C14E")),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
            if (t.anyCell() != null) pr.addView(Ui.text(this, 12f, if (t.exact) Ui.GREEN else Ui.SUB, true,
                if (t.exact) "📍정확" else "◯대략"), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
            c.addView(pr, Ui.lp(this, MATCH, WRAP, top = 4))
            val reg = regionOf(t)
            if (reg.isNotBlank()) c.addView(Ui.text(this, 12f, Ui.SUB, false, reg), Ui.lp(this, MATCH, WRAP, top = 2))
            // 대표 조과 사진: 카드 오른쪽 (누르면 크게)
            if (t.mthumb.isNotBlank()) Ui.withRightPhoto(this, c, MainPhoto.decode(t.mthumb), maxOf(0, t.photoCount - 1)) {
                MainPhoto.showBig(this, "public/${t.id}/photos/main", t.mthumb, "${t.nick} · ${dayFmt.format(Date(t.start))}")
            }
            listBox.addView(c, Ui.lp(this, MATCH, WRAP, top = 8))
        }
    }

    private fun phaseText(cs: List<PublicBoard.PCatch>): String {
        val sb = StringBuilder()
        val up = cs.count { it.rising == true }; val down = cs.count { it.rising == false }
        if (up + down > 0) sb.append("들물 ").append(up).append("마리 · 날물 ").append(down).append("마리\n")
        val near = cs.mapNotNull { it.near.ifBlank { null } }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(5)
        if (near.isNotEmpty()) sb.append(near.joinToString("\n") { "${it.key} ${it.value}마리" }).append("\n")
        return sb.toString()
    }

    private fun tripDetail(t: PublicBoard.PTrip) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), dp(6)) }
        val cs = t.catches.sortedBy { it.time }
        // 📸 사진 (맨 위): 대표 사진 크게 + 나머지는 옆으로 밀어서
        if (t.mthumb.isNotBlank() || t.photoCount > 0) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
            box.addView(Ui.hscroll(this, row), Ui.lp(this, MATCH, WRAP, top = 4))
            val caption = "${t.nick} · ${dayFmt.format(Date(t.start))}"
            MainPhoto.decode(t.mthumb)?.let { th ->
                row.addView(ImageView(this).apply {
                    setImageBitmap(th); scaleType = ImageView.ScaleType.CENTER_CROP
                    outlineProvider = android.view.ViewOutlineProvider.BACKGROUND; clipToOutline = true
                    background = Ui.bg(this@BoardActivity, Ui.CARD, 10)
                    setOnClickListener { MainPhoto.showBig(this@BoardActivity, "public/${t.id}/photos/main", t.mthumb, caption) }
                    // 큰 사진으로 바꿔 끼우기
                    com.google.firebase.firestore.FirebaseFirestore.getInstance().document("public/${t.id}/photos/main").get()
                        .addOnSuccessListener { d -> d.getString("data")?.let { MainPhoto.decode(it) }?.let { setImageBitmap(it) } }
                }, LinearLayout.LayoutParams(dp(180), dp(150)).apply { rightMargin = dp(6) })
            }
            if (t.photoCount > 0) PublicBoard.loadPhotos(t.id) { ps ->
                // 대표 사진은 위에 이미 크게 있으니 목록에서 뺌
                ps.filter { it.id != t.mainId }.forEach { p ->
                    row.addView(ImageView(this).apply {
                        setImageBitmap(p.bitmap); scaleType = ImageView.ScaleType.CENTER_CROP
                        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND; clipToOutline = true
                        background = Ui.bg(this@BoardActivity, Ui.CARD, 10)
                        setOnClickListener { ZoomImageView.show(this@BoardActivity, p.bitmap, hm.format(Date(p.time))) }
                    }, LinearLayout.LayoutParams(dp(96), dp(150)).apply { rightMargin = dp(6) })
                }
            }
        }
        // 지도: 잡은 자리 (정확 = 어종 색 점, 대략 = 원) + 지금 내 위치 + 길 안내
        val spots = spotDots(cs.map { t to it }) { _, c -> MapDialog.slotCol(c.slot) }
        var embedded: MapDialog.Embedded? = null
        if (spots.isNotEmpty()) {
            val radii = HashMap<MapDot, Double>().apply { spots.forEach { put(it.dot, it.radius) } }
            val target = if (t.exact) "잡은 자리" else "포인트"
            val e = MapDialog.embedded(this, spots.map { it.dot }, { d -> radii[d] ?: 0.0 }, target) {
                MapDialog.showSpots(this, "${t.nick} · ${dateFmt.format(Date(t.start))}", spots.map { it.dot }, { d -> radii[d] ?: 0.0 },
                    "${t.speciesLine()} · ${spotLegend(spots)}\n● 주황 ${t.name(1)} · ● 하늘 ${t.name(2)}", target)
            }
            embedded = e
            box.addView(e.view, Ui.lp(this, MATCH, dp(240), top = 10))
            box.addView(Ui.text(this, 12f, Ui.SUB, false,
                (if (t.exact) "📍 정확한 위치 공개 · ● 주황 ${t.name(1)} · ● 하늘 ${t.name(2)}" else "◯ 대략 공개 · 원 안 어딘가에서 잡았어요") +
                    "\n파란 점 = 지금 내 위치 · 점선과 위 글씨 = 가장 가까운 자리까지 방향·거리"),
                Ui.lp(this, MATCH, WRAP, top = 6))
        }
        val info = Ui.text(this, 14f, Ui.TEXT, false, "").apply { setLineSpacing(0f, 1.3f) }
        fun paintInfo() {
            val sb = StringBuilder()
            sb.append(Tide.mulName(t.start)).append("\n")
            if (cs.isNotEmpty()) sb.append("잡은 시간 ").append(hm.format(Date(cs.first().time))).append(" ~ ").append(hm.format(Date(cs.last().time))).append("\n")
            regionOf(t).takeIf { it.isNotBlank() }?.let { sb.append("지역: ").append(it).append("\n") }
            sb.append(t.speciesLine()).append(" · 총 ").append(cs.size).append("마리\n")
            phaseText(cs).takeIf { it.isNotBlank() }?.let { sb.append(it) }
            info.text = sb.toString().trimEnd()
        }
        paintInfo()
        // 동네 이름을 처음 찾는 중이면, 찾은 뒤 다시 씀
        t.anyCell()?.let { c -> if (Area.cached(this, c.lat, c.lon) == null) Area.fetch(this, c.lat, c.lon) { paintInfo() } }
        box.addView(info, Ui.lp(this, MATCH, WRAP, top = 10))
        // 그때 날씨 (기온 · 하늘 · 바람 · 파고 · 수온) — 누르면 시간별
        if (t.wx.isNotBlank()) WxView.curtain(this, t.wx, t.start, t.end)?.let { box.addView(it, Ui.lp(this, MATCH, WRAP, top = 10)) }
        // ⏱ 시각별: 한 시간씩 커튼바 (눌러서 펼치기 · 지금 시간대만 처음부터 펼침)
        if (cs.isNotEmpty()) {
            box.addView(Ui.text(this, 14f, Ui.TEXT, true, "⏱ 시각별 기록"), Ui.lp(this, MATCH, WRAP, top = 14))
            val hourFmt = SimpleDateFormat("H", Locale.KOREA)
            val nowKey = SimpleDateFormat("yyMMddH", Locale.KOREA).format(Date())
            val keyFmt = SimpleDateFormat("yyMMddH", Locale.KOREA)
            val multiDay = SimpleDateFormat("yyMMdd", Locale.KOREA).let { f -> f.format(Date(cs.first().time)) != f.format(Date(cs.last().time)) }
            cs.groupBy { keyFmt.format(Date(it.time)) }.forEach { (k, list) ->
                val hourCount = list.groupingBy { it.slot }.eachCount().entries.sortedBy { it.key }
                    .joinToString(" · ") { "${t.name(it.key)} ${it.value}" }
                val ups = list.count { it.rising == true }; val downs = list.count { it.rising == false }
                val phase = when { ups > 0 && downs == 0 -> "들물"; downs > 0 && ups == 0 -> "날물"; ups + downs > 0 -> "들물·날물"; else -> "" }
                val day = if (multiDay) SimpleDateFormat("M/d ", Locale.KOREA).format(Date(list.first().time)) else ""
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(9), dp(12), dp(9))
                }
                val head = Ui.row(this)
                val title = Ui.text(this, 13f, Ui.TEXT, true, "")
                head.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
                if (phase.isNotBlank()) head.addView(Ui.text(this, 11f, Ui.SUB, false, phase), LinearLayout.LayoutParams(WRAP, WRAP))
                card.addView(head)
                val body = Ui.text(this, 12f, Ui.SUB, false, list.joinToString("\n") { c ->
                    StringBuilder(hm.format(Date(c.time))).append("  ").append(t.name(c.slot)).apply {
                        c.rising?.let { append(" · ").append(if (it) "들물" else "날물") }
                        if (c.near.isNotBlank()) append(" · ").append(c.near)
                    }.toString()
                }).apply { setLineSpacing(0f, 1.25f) }
                card.addView(body, Ui.lp(this, MATCH, WRAP, top = 6))
                var open = k == nowKey
                fun paint() {
                    title.text = "${if (open) "▾" else "▸"} $day${hourFmt.format(Date(list.first().time))}시 · $hourCount"
                    body.visibility = if (open) View.VISIBLE else View.GONE
                    card.background = Ui.bg(this, Ui.CARD, 10, if (open) Ui.GREEN else Ui.LINE)
                }
                paint()
                card.setOnClickListener { open = !open; paint() }
                box.addView(card, Ui.lp(this, MATCH, WRAP, top = 6))
            }
        }
        val mine = t.owner == Cloud.uid
        val b = AlertDialog.Builder(this, R.style.FlAlert)
            .setTitle("${t.nick} · ${dateFmt.format(Date(t.start))}")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("닫기", null)
        if (mine || Cloud.isAdmin) b.setNegativeButton("공개 내리기") { _, _ -> askRemove(t) }
        if (mine) b.setNeutralButton(if (t.exact) "위치 대략으로" else "위치 정확하게") { _, _ ->
            toast("바꾸는 중…")
            PublicBoard.changePrecision(this, t.id, !t.exact) { err ->
                toast(err ?: if (!t.exact) "정확한 위치로 공개해요" else "대략적인 위치로만 공개해요")
                if (err == null) { loaded = false; load() }
            }
        }
        else b.setNeutralButton("이 사람 차단") { _, _ -> askBlock(t) }
        val dlg = b.create()
        dlg.setOnDismissListener { embedded?.let { it.live.stop(); it.map.onPause(); it.map.onDetach() } }
        dlg.show()
    }

    /** 장난 기록 막기: 이 사람 글을 내 조황·포인트 계산에서 빼기 */
    private fun askBlock(t: PublicBoard.PTrip) {
        if (Cloud.user == null) { toast("로그인이 필요해요"); return }
        val label = "${t.nick} · ${dayFmt.format(Date(t.start))}" + regionOf(t).takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty() + " 글에서 차단"
        AlertDialog.Builder(this, R.style.FlAlert)
            .setTitle("이 사람을 차단할까요?")
            .setMessage("이 사람이 올린 출조가 내 조황 목록, 어종 마릿수, 지도, 포인트 계산에서 모두 빠져요.\n" +
                "익명이어도 같은 사람이 올린 글은 모두 빠지고, 앞으로 올리는 글도 빠져요.\n" +
                "상대에게는 알림이 가지 않아요. '차단 목록'에서 언제든 해제할 수 있어요.")
            .setPositiveButton("차단") { _, _ ->
                Moderation.block(t.owner, label) { err ->
                    toast(err ?: "차단했어요")
                    Moderation.reportCounts { m -> reports = m; refreshNick(); render() }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun askRemove(t: PublicBoard.PTrip) {
        val mine = t.owner == Cloud.uid
        AlertDialog.Builder(this, R.style.FlAlert)
            .setTitle(if (mine) "내 출조 공개를 내릴까요?" else "관리자: 이 글을 지울까요?")
            .setMessage("전체 게시판에서만 사라지고, 폰의 기록은 그대로예요")
            .setPositiveButton("내리기") { _, _ ->
                val local = if (mine) t.id.substringAfter("_").toLongOrNull() else null
                PublicBoard.unpublish(t.id, local) { err ->
                    toast(err ?: "내렸어요")
                    if (err == null) { all = all.filter { it.id != t.id }; render() }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ---------------- 포인트 도감 ----------------
    @SuppressLint("MissingPermission")
    private fun myLocation(): Pair<Double, Double>? {
        Tracker.last?.let { return it.latitude to it.longitude }
        if (Tracker.hasPermission(this)) {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try { lm.getLastKnownLocation(p)?.let { return it.latitude to it.longitude } } catch (_: Exception) {}
            }
        }
        return Store.history.firstNotNullOfOrNull { it.anyLocation() }
    }

    private fun distKm(a: Pair<Double, Double>, lat: Double, lon: Double): Float {
        val r = FloatArray(1)
        android.location.Location.distanceBetween(a.first, a.second, lat, lon, r)
        return r[0] / 1000f
    }

    private fun renderPoints() {
        val list = filtered()
        // 🗺 지도: 전체 포인트 · 나만의 포인트 체크 + 원 그려서 분석
        listBox.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(10), dp(10), dp(10), dp(10))
            background = Ui.bg(this@BoardActivity, android.graphics.Color.parseColor("#2A1023"), 14, android.graphics.Color.parseColor("#FF4FA8"))
            addView(Ui.text(this@BoardActivity, 15f, android.graphics.Color.parseColor("#FFD0E8"), true, "🗺 포인트 지도 · ⭕ 원 그려서 분석").apply { gravity = Gravity.CENTER })
            addView(Ui.text(this@BoardActivity, 11f, Ui.SUB, false, "☑ 전체 포인트 · ☑ 나만의 포인트(안 올린 내 기록, 나만 보여요)").apply { gravity = Gravity.CENTER })
            isClickable = true
            setOnClickListener {
                PointMap.open(this@BoardActivity, list, cat, if (allTime) 0L else fromDay)
            }
        }, Ui.lp(this, MATCH, WRAP, top = 10))
        // 출조 한 건씩 (지역으로 쪼개지 않음) — 자리별 분석은 위 '포인트 지도'에서 원 그리기로
        if (list.isEmpty()) {
            listBox.addView(Ui.text(this, 14f, Ui.SUB, false, "공개된 출조가 아직 없어요."), Ui.lp(this, MATCH, WRAP, top = 12))
            return
        }
        // 어종 칩: 전체 + 많이 잡힌 순 (고르면 그 어종 1시간 평균으로 순위)
        val counts = list.flatMap { t -> t.catches.map { t.key(it.slot) } }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        if (species != null && counts.none { it.key == species }) species = null
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        chips.addView(pill("전체 어종", species == null, 13f) { species = null; render() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
        counts.forEach { e ->
            chips.addView(pill("${PublicBoard.keyLabel(e.key)} ${e.value}", e.key == species, 13f) { species = e.key; render() },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
        }
        listBox.addView(Ui.hscroll(this, chips), Ui.lp(this, MATCH, WRAP, top = 12))

        val me = myLocation()
        val sortRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        sortRow.addView(pill("잘 나온 순", !nearFirst, 13f) { nearFirst = false; render() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        sortRow.addView(pill("가까운 순", nearFirst, 13f) {
            if (me == null) toast("내 위치를 몰라서 가까운 순으로 볼 수 없어요") else { nearFirst = true; render() }
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(6) })
        listBox.addView(sortRow, Ui.lp(this, MATCH, WRAP, top = 8))

        val sp = species
        fun nOf(t: PublicBoard.PTrip) = if (sp == null) t.catches.size else t.catches.count { t.key(it.slot) == sp }
        fun hoursOf(t: PublicBoard.PTrip): Double {
            val end = if (t.end > t.start) t.end else (t.catches.maxOfOrNull { it.time } ?: t.start)
            return ((end - t.start).coerceAtLeast(15 * 60_000L)) / 3_600_000.0
        }
        fun rateOf(t: PublicBoard.PTrip) = nOf(t) / hoursOf(t)
        fun locOf(t: PublicBoard.PTrip): Pair<Double, Double>? = t.catches.firstNotNullOfOrNull { c ->
            if (c.hasExact) c.exactLat!! to c.exactLon!! else c.cell?.let { it.lat to it.lon } }
        var trips = list.filter { nOf(it) > 0 }
        trips = if (nearFirst && me != null) trips.sortedBy { t -> locOf(t)?.let { distKm(me, it.first, it.second) } ?: Float.MAX_VALUE }
            else trips.sortedByDescending { rateOf(it) }
        listBox.addView(Ui.text(this, 13f, Ui.SUB, false,
            "${if (sp == null) "전체 어종" else PublicBoard.keyLabel(sp)} · 출조 ${trips.size}건 · 순위는 1시간 평균 마릿수 · 누르면 그 출조 지도 · 조과 · 사진 · 날씨"),
            Ui.lp(this, MATCH, WRAP, top = 10))
        val hmF = SimpleDateFormat("HH:mm", Locale.KOREA)
        trips.take(80).forEachIndexed { i, t ->
            val c = Ui.card(this).apply { isClickable = true; setOnClickListener { PublicBoard.openBig(this@BoardActivity, t, REQ_PUB) } }
            val r = Ui.row(this)
            val end = if (t.end > t.start) t.end else (t.catches.maxOfOrNull { it.time } ?: t.start)
            r.addView(Ui.text(this, 15f, Ui.TEXT, true, "${i + 1}. " + dateFmt.format(Date(t.start)) + " " + hmF.format(Date(t.start)) + "~" + hmF.format(Date(end)) +
                (if (t.cat.isNotBlank()) " · " + Store.catShort(t.cat) else "")).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (t.photoCount > 0) r.addView(Ui.chip(this, "사진 ${t.photoCount}"), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
            c.addView(r)
            c.addView(Ui.text(this, 14f, Ui.TEXT, false, "${t.nick} · ${t.speciesLine()}"), Ui.lp(this, MATCH, WRAP, top = 4))
            val dist = me?.let { m -> locOf(t)?.let { String.format(Locale.KOREA, " · 내 위치에서 %.1fkm", distKm(m, it.first, it.second)) } } ?: ""
            c.addView(Ui.text(this, 13.5f, Ui.ORANGE, true, String.format(Locale.KOREA, "1시간에 평균 %.1f마리", rateOf(t)) +
                (if (sp != null) " (${PublicBoard.keyLabel(sp)})" else "") + dist), Ui.lp(this, MATCH, WRAP, top = 2))
            val reg = regionOf(t)
            c.addView(Ui.text(this, 12f, Ui.GREEN, false, (if (t.isFresh()) "🏞 민물" else Tide.mulName(t.start).substringBefore(" (")) + if (reg.isNotBlank()) " · $reg" else ""),
                Ui.lp(this, MATCH, WRAP, top = 2))
            if (t.mthumb.isNotBlank()) Ui.withRightPhoto(this, c, MainPhoto.decode(t.mthumb), maxOf(0, t.photoCount - 1)) {
                MainPhoto.showBig(this, "public/${t.id}/photos/main", t.mthumb, "${t.nick} · ${dayFmt.format(Date(t.start))}")
            }
            listBox.addView(c, Ui.lp(this, MATCH, WRAP, top = 8))
        }
    }

    private fun pointDetail(p: PublicBoard.Point, title: String) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(6)) }
        val spLabel = PublicBoard.keyLabel(species!!)
        val em = MapDialog.circleMap(this, p.cell.lat, p.cell.lon, p.cell.r * 1.2) {
            val dot = MapDot(org.osmdroid.util.GeoPoint(p.cell.lat, p.cell.lon), Ui.ORANGE, title, title)
            MapDialog.showSpots(this, "$title · $spLabel", listOf(dot), { p.cell.r * 1.2 },
                String.format(Locale.KOREA, "%s %d마리 · 주황 원 = 포인트 범위", spLabel, p.total), "포인트")
        }
        box.addView(em.view, LinearLayout.LayoutParams(MATCH, dp(240)))
        box.addView(Ui.text(this, 12f, Ui.SUB, false, "파란 점 = 지금 내 위치 · 점선과 위 글씨 = 포인트까지 방향·거리"),
            Ui.lp(this, MATCH, WRAP, top = 6))
        // 글자는 보통 글꼴, 막대는 줄 맞춰 그림 (예전엔 고정폭 글꼴이라 글자 사이가 벌어져 보였음)
        fun para(text: String, top: Int = 10, bold: Boolean = false, into: LinearLayout = box) =
            into.addView(Ui.text(this, 13.5f, Ui.TEXT, bold, text).apply { setLineSpacing(0f, 1.25f) }, Ui.lp(this, MATCH, WRAP, top = top))
        fun barRows(title: String, rows: List<Pair<String, Int>>, into: LinearLayout = box) {
            if (rows.isEmpty()) return
            para(title, 14, true, into)
            val max = rows.maxOf { it.second }.coerceAtLeast(1)
            rows.forEach { (k, v) ->
                val r = Ui.row(this)
                r.addView(Ui.text(this, 13f, Ui.SUB, false, k), LinearLayout.LayoutParams(dp(64), WRAP))
                val track = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
                track.addView(View(this).apply { background = Ui.bg(this@BoardActivity, Ui.ORANGE, 3) },
                    LinearLayout.LayoutParams(0, dp(10), v.toFloat() / max))
                track.addView(View(this), LinearLayout.LayoutParams(0, dp(10), 1f - v.toFloat() / max + 0.0001f))
                r.addView(track, LinearLayout.LayoutParams(0, WRAP, 1f))
                r.addView(Ui.text(this, 13f, Ui.TEXT, true, v.toString()), LinearLayout.LayoutParams(dp(32), WRAP).apply { leftMargin = dp(8) })
                into.addView(r, Ui.lp(this, MATCH, WRAP, top = 4))
            }
        }
        para(String.format(Locale.KOREA, "%s %d마리 · 출조 %d번 · 1시간에 평균 %.1f마리", spLabel, p.total, p.trips.size, p.rate), 10, true)
        val days = StringBuilder()
        p.catches.groupBy { dateFmt.format(Date(it.first.start)) + "|" + it.first.nick }
            .entries.sortedByDescending { e -> e.value[0].first.start }
            .forEach { e -> days.append(e.key.substringBefore("|")).append("  ").append(e.key.substringAfter("|"))
                .append("  ").append(e.value.size).append("마리 · ").append(if (e.value[0].first.isFresh()) "🏞 민물" else Tide.mulName(e.value[0].first.start).substringBefore(" ·")).append("\n") }
        para("날짜별", 14, true)
        para(days.toString().trimEnd(), 2)
        val cs = p.catches.map { it.second }
        // v6.9 (254): 물때 · 물 흐름은 바다 글만
        val seaCs = p.catches.filter { !it.first.isFresh() }.map { it.second }
        barRows("물때 단계", seaCs.groupingBy { Tide.mulName(it.time).substringAfter("· ").substringBefore(" (") }
            .eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value })
        val ph = phaseText(seaCs)
        if (ph.isNotBlank()) { para("물 흐름 · 만조·간조 기준", 14, true); para(ph.trimEnd(), 2) }
        // v6.17 (263): 💧 잡은 때 물살 (그날 물세기 × 그 시각 물흐름 ÷ 그날 최고) — 바다 글만 · 출조 20개까지
        val seaPairs = p.catches.filter { !it.first.isFresh() }
        if (seaPairs.isNotEmpty()) {
            val flowBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(flowBox, Ui.lp(this, MATCH, WRAP))
            para("💧 잡은 때 물살 계산 중…", 14, false, flowBox)
            val app = applicationContext
            Thread {
                val flows = ArrayList<Int>()
                seaPairs.groupBy { it.first.id }.entries.take(20).forEach { (_, l) ->
                    val t0 = l[0].first; val c0 = l[0].second
                    val la = c0.exactLat ?: c0.cell?.lat ?: return@forEach
                    val lo = c0.exactLon ?: c0.cell?.lon ?: return@forEach
                    val d = try { Tide.fetchNow(app, la, lo, t0.start) } catch (_: Exception) { null } ?: return@forEach
                    val st = try { Tide.strengthOf(app, d) } catch (_: Exception) { null } ?: return@forEach
                    l.forEach { (_, c) -> BiteMemo.flowAt(d, st, c.time)?.let { flows.add(it) } }
                }
                runOnUiThread {
                    flowBox.removeAllViews()
                    if (flows.isEmpty()) { para("💧 잡은 때 물살: 물때 자료를 못 받았어요", 14, false, flowBox); return@runOnUiThread }
                    val order = listOf("~20%", "20~40%", "40~60%", "60~80%", "80%~")
                    val m = flows.groupingBy { f -> when { f < 20 -> order[0]; f < 40 -> order[1]; f < 60 -> order[2]; f < 80 -> order[3]; else -> order[4] } }.eachCount()
                    barRows("💧 잡은 때 물살 (그날 물세기 기준)", order.map { it to (m[it] ?: 0) }, flowBox)
                }
            }.start()
        }
        val hourFmt = SimpleDateFormat("H", Locale.KOREA)
        barRows("시간대", cs.groupingBy { hourFmt.format(Date(it.time)).toInt() }.eachCount()
            .entries.sortedBy { it.key }.map { "${it.key}시" to it.value })
        val monFmt = SimpleDateFormat("M월", Locale.KOREA)
        barRows("잘 나온 달", cs.groupingBy { monFmt.format(Date(it.time)) }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key to it.value })
        val dlg = AlertDialog.Builder(this, R.style.FlAlert)
            .setTitle("$title · $spLabel")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("닫기", null)
            .create()
        dlg.setOnDismissListener { em.live.stop(); em.map.onPause(); em.map.onDetach() }
        dlg.show()
    }

    override fun onResume() { super.onResume(); refreshNick(); paintPubBanner() }

    private var pubBanner: TextView? = null
    private fun paintPubBanner() {
        val b = pubBanner ?: return
        val off = getSharedPreferences("fishlog", Context.MODE_PRIVATE).getBoolean("pub_nag_off", false)
        val n = if (Cloud.user == null || off) 0 else PublicBoard.unpublished().size
        b.visibility = if (n > 0) View.VISIBLE else View.GONE
        b.text = "📤 내 출조 ${n}개가 아직 게시판에 없어요 · 공개하기 ›"
    }

    /** v6.62 (335): 안 올린 출조 목록 (줄마다 공개 · 전부 대략으로 · 다시 묻지 않기) */
    private fun openUnpublished() {
        val sh = Sheet(this, 0.7f)
        sh.head.addView(Ui.text(this, 17f, Ui.TEXT, true, "📤 아직 안 올린 내 출조"), LinearLayout.LayoutParams(0, WRAP, 1f))
        val listB = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sh.body.addView(Ui.text(this, 12f, Ui.SUB, false, "그룹에만 올렸거나 공개를 안 누른 출조예요. [공개]를 누르면 위치(정확/대략)를 고르고 올려요."))
        sh.body.addView(listB, Ui.lp(this, MATCH, WRAP, top = 6))
        val fmt = SimpleDateFormat("M/d(E) HH:mm", Locale.KOREA)
        fun paint() {
            listB.removeAllViews()
            val l = PublicBoard.unpublished()
            if (l.isEmpty()) { listB.addView(Ui.text(this, 14f, Ui.GREEN, true, "✓ 다 올렸어요"), Ui.lp(this, MATCH, WRAP, top = 12)); return }
            l.forEach { t ->
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(10), dp(4), dp(10)) }
                val tx = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                tx.addView(Ui.text(this, 14.5f, Ui.TEXT, true, fmt.format(Date(t.start)) + " · " + Store.catLabel(t.cat)))
                tx.addView(Ui.text(this, 12f, Ui.SUB, false, t.slots().filter { t.count(it) > 0 }.joinToString(" · ") { "${t.name(it)} ${t.count(it)}" } + " · 총 ${t.catches.size}마리"))
                r.addView(tx, LinearLayout.LayoutParams(0, WRAP, 1f))
                r.addView(Ui.text(this, 13f, android.graphics.Color.parseColor("#1B0E02"), true, "공개").apply {
                    gravity = Gravity.CENTER; setPadding(dp(14), dp(7), dp(14), dp(7)); background = Ui.bg(this@BoardActivity, Ui.ORANGE, 99)
                    setOnClickListener { PublicBoard.publishFlow(this@BoardActivity, t) { err ->
                        Toast.makeText(this@BoardActivity, err ?: "전체 게시판에 공개했어요", Toast.LENGTH_SHORT).show(); paint(); paintPubBanner()
                    } }
                })
                listB.addView(r)
            }
        }
        paint()
        val all = Ui.button(this, "전부 대략 위치로 공개 (사진 빼고)", true) {
            val l = PublicBoard.unpublished()
            if (l.isEmpty()) return@button
            var left = l.size; var bad = 0
            Toast.makeText(this, "${l.size}개 올리는 중…", Toast.LENGTH_SHORT).show()
            l.forEach { t -> PublicBoard.publishQuiet(this, t, false) { err ->
                if (err != null) bad++
                if (--left == 0) { Toast.makeText(this, if (bad == 0) "다 올렸어요" else "${l.size - bad}개 올림 · ${bad}개 실패", Toast.LENGTH_LONG).show(); paint(); paintPubBanner() }
            } }
        }
        val never = Ui.text(this, 12.5f, Ui.SUB, false, "이 안내 다시 보지 않기").apply {
            gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(2))
            setOnClickListener { getSharedPreferences("fishlog", Context.MODE_PRIVATE).edit().putBoolean("pub_nag_off", true).apply(); paintPubBanner(); sh.close() }
        }
        sh.foot.visibility = View.VISIBLE
        sh.foot.addView(all, LinearLayout.LayoutParams(MATCH, WRAP))
        sh.foot.addView(never, LinearLayout.LayoutParams(MATCH, WRAP))
        sh.show()
    }

    // v6.9 (255): 큰 상세 화면에서 차단 · 내리기 하고 돌아오면 목록 새로
    private val REQ_PUB = 7255
    @Deprecated("startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PUB && resultCode == RESULT_OK && data?.getBooleanExtra("pubChanged", false) == true) {
            PublicBoard.clearCache(); loaded = false
            Moderation.reportCounts { m -> reports = m; refreshNick(); load() }
        }
    }
}
