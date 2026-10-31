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

    private lateinit var tabRow: LinearLayout
    private lateinit var catRow: LinearLayout
    private lateinit var periodRow: LinearLayout
    private lateinit var listBox: LinearLayout
    private lateinit var nickText: TextView
    private lateinit var blockBtn: TextView

    private var all: List<PublicBoard.PTrip> = emptyList()
    private var tab = 0            // 0 = 최근 조황, 1 = 포인트 도감
    private var cat = ""           // "" = 전체
    private var period = 1         // 0 = 1개월, 1 = 3개월, 2 = 전체
    private var species: String? = null
    private var nearFirst = false
    private var loadedPeriod = -1

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
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        super.onCreate(savedInstanceState)
        Store.init(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        val col = Ui.column(this)
        val top = Ui.row(this)
        top.addView(Ui.button(this, "← 뒤로") { finish() }.apply { minHeight = dp(44) }, LinearLayout.LayoutParams(WRAP, WRAP))
        top.addView(Ui.text(this, 20f, Ui.TEXT, true, "전체 조황 · 포인트"), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(14) })
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

        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(tabRow, Ui.lp(this, MATCH, WRAP, top = 10))
        // 종류 칩: 글자가 줄바꿈되지 않게 가로로 밀어서 보기
        catRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(catRow) },
            Ui.lp(this, MATCH, WRAP, top = 10))
        periodRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(periodRow, Ui.lp(this, MATCH, WRAP, top = 8))
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
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        listOf("최근 조황", "포인트 도감").forEachIndexed { i, s ->
            tabRow.addView(pill(s, tab == i, 15f) { tab = i; renderControls(); render() },
                LinearLayout.LayoutParams(0, dp(46), 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
        catRow.removeAllViews()
        (listOf("") + Store.CATS).forEachIndexed { i, c ->
            catRow.addView(pill(if (c == "") "전체" else Store.catShort(c), cat == c, 13f) {
                cat = c; species = null; renderControls(); render()
            }.apply { maxLines = 1 }, LinearLayout.LayoutParams(WRAP, WRAP).apply { if (i > 0) leftMargin = dp(6) })
        }
        periodRow.removeAllViews()
        listOf("최근 1개월", "3개월", "전체 기간").forEachIndexed { i, s ->
            periodRow.addView(pill(s, period == i, 13f) {
                period = i; renderControls(); load()
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) leftMargin = dp(6) })
        }
    }

    private fun load() {
        if (Cloud.user == null) {
            listBox.removeAllViews()
            listBox.addView(Ui.text(this, 14f, Ui.SUB, false, "구글 로그인을 하면 전체 조황을 볼 수 있어요"), Ui.lp(this, MATCH, WRAP, top = 14))
            return
        }
        if (loadedPeriod == period) { render(); return }
        listBox.removeAllViews()
        listBox.addView(Ui.text(this, 14f, Ui.SUB, false, "불러오는 중…"), Ui.lp(this, MATCH, WRAP, top = 14))
        val day = 86_400_000L
        val since = when (period) { 0 -> System.currentTimeMillis() - 31 * day; 1 -> System.currentTimeMillis() - 92 * day; else -> 0L }
        val want = period
        var left = 5
        var got: List<PublicBoard.PTrip>? = null
        var err: String? = null
        fun step() {
            left--
            if (left > 0 || want != period) return
            val list = got
            if (list == null) {
                listBox.removeAllViews()
                listBox.addView(Ui.text(this, 14f, Ui.ORANGE, false, err ?: "실패"), Ui.lp(this, MATCH, WRAP, top = 14))
                return
            }
            all = list; loadedPeriod = want
            refreshNick()
            render()
        }
        PublicBoard.load(since) { list, e -> got = list; err = e; step() }
        Moderation.syncBlocked { step() }
        Moderation.reportCounts { m -> reports = m; step() }
        Moderation.loadLimit { step() }
        Moderation.bannedUids { b -> banned = b; step() }
    }

    /**
     * 보여줄 글: 낚시 종류 + 내가 차단한 사람, 신고 누적 기준 이상, 관리자가 정지한 사람은 빼기
     * (내 글은 항상 보임)
     */
    private fun visible(t: PublicBoard.PTrip): Boolean {
        if (t.owner == Cloud.uid) return true
        if (Moderation.isBlocked(t.owner)) return false
        if (t.owner in banned) return false
        return (reports[t.owner] ?: 0) < Moderation.reportLimit
    }

    private fun filtered() = all.filter { (cat == "" || it.cat == cat) && visible(it) }

    private fun render() {
        if (Cloud.user == null || loadedPeriod != period) return
        listBox.removeAllViews()
        if (tab == 0) renderRecent() else renderPoints()
    }

    // ---------------- 최근 조황 ----------------
    private fun regionOf(t: PublicBoard.PTrip): String {
        val c = t.anyCell() ?: return ""
        return Tide.stationsNear(this, c.lat, c.lon).firstOrNull()?.name?.let { "$it 부근" } ?: ""
    }

    private var listOpen = false

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
        val periodText = listOf("최근 1개월", "최근 3개월", "전체 기간")[period]
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

    private fun renderRecent() {
        val list = filtered()
        if (list.isEmpty()) {
            listBox.addView(Ui.text(this, 14f, Ui.SUB, false, "아직 공개된 출조가 없어요. 출조를 종료할 때 '전체 게시판에도 공개'를 체크해 보세요."),
                Ui.lp(this, MATCH, WRAP, top = 12))
            return
        }
        // 어종 칩: 누르면 지도 (같은 어종도 낚시 종류가 다르면 따로)
        val counts = list.flatMap { t -> t.catches.map { t.key(it.slot) } }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
        listBox.addView(Ui.text(this, 14f, Ui.TEXT, true, "어종을 누르면 잡힌 자리를 지도로 봐요"), Ui.lp(this, MATCH, WRAP, top = 14))
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        counts.chunked(2).forEach { rowItems ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
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
                    else tripDetail(t)
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
                Tide.mulName(t.start).substringBefore(" ·")), LinearLayout.LayoutParams(0, WRAP, 1f))
            val nRep = reports[t.owner] ?: 0
            if (nRep > 0) r.addView(Ui.chip(this, "⚠ 신고 $nRep", Ui.ORANGE), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
            if (t.photoCount > 0) r.addView(Ui.chip(this, "사진 ${t.photoCount}"), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
            c.addView(r)
            val pr = Ui.row(this)
            if (t.gimg.isNotBlank()) try {
                val bytes = android.util.Base64.decode(t.gimg, android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bm ->
                    pr.addView(android.widget.ImageView(this).apply { setImageBitmap(GroupIcon.circle(bm, dp(26))) },
                        LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(6) })
                }
            } catch (_: Exception) {}
            pr.addView(Ui.text(this, 14f, Ui.TEXT, false, "${t.nick}" + (if (t.gname.isNotBlank()) " · ${t.gname}" else "") +
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
            box.addView(e.view, LinearLayout.LayoutParams(MATCH, dp(240)))
            box.addView(Ui.text(this, 12f, Ui.SUB, false,
                (if (t.exact) "📍 정확한 위치 공개 · ● 주황 ${t.name(1)} · ● 하늘 ${t.name(2)}" else "◯ 대략 공개 · 원 안 어딘가에서 잡았어요") +
                    "\n파란 점 = 지금 내 위치 · 점선과 위 글씨 = 가장 가까운 자리까지 방향·거리"),
                Ui.lp(this, MATCH, WRAP, top = 6))
        }
        val sb = StringBuilder()
        sb.append(Tide.mulName(t.start)).append("\n")
        if (cs.isNotEmpty()) sb.append("잡은 시간 ").append(hm.format(Date(cs.first().time))).append(" ~ ").append(hm.format(Date(cs.last().time))).append("\n")
        regionOf(t).takeIf { it.isNotBlank() }?.let { sb.append("지역: ").append(it).append("\n") }
        sb.append("\n").append(t.speciesLine()).append(" · 총 ").append(cs.size).append("마리\n\n")
        phaseText(cs).takeIf { it.isNotBlank() }?.let { sb.append(it).append("\n") }
        sb.append("시각별\n")
        cs.forEach { c ->
            sb.append(hm.format(Date(c.time))).append("  ").append(t.name(c.slot))
            c.rising?.let { sb.append(" · ").append(if (it) "들물" else "날물") }
            if (c.near.isNotBlank()) sb.append(" · ").append(c.near)
            sb.append("\n")
        }
        box.addView(Ui.text(this, 14f, Ui.TEXT, false, sb.toString().trimEnd()).apply { setLineSpacing(0f, 1.3f) },
            Ui.lp(this, MATCH, WRAP, top = 10))
        val photoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (t.photoCount > 0) {
            box.addView(HorizontalScrollView(this).apply { addView(photoRow) }, Ui.lp(this, MATCH, WRAP, top = 10))
            PublicBoard.loadPhotos(t.id) { ps ->
                ps.forEach { p ->
                    photoRow.addView(ImageView(this).apply {
                        setImageBitmap(p.bitmap); scaleType = ImageView.ScaleType.CENTER_CROP
                        setOnClickListener { ZoomImageView.show(this@BoardActivity, p.bitmap, hm.format(Date(p.time))) }
                    }, LinearLayout.LayoutParams(dp(96), dp(96)).apply { rightMargin = dp(6) })
                }
            }
        }
        val mine = t.owner == Cloud.uid
        val b = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("${t.nick} · ${dateFmt.format(Date(t.start))}")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("닫기", null)
        if (mine || Cloud.isAdmin) b.setNegativeButton("공개 내리기") { _, _ -> askRemove(t) }
        if (mine) b.setNeutralButton(if (t.exact) "위치 대략으로" else "위치 정확하게") { _, _ ->
            toast("바꾸는 중…")
            PublicBoard.changePrecision(this, t.id, !t.exact) { err ->
                toast(err ?: if (!t.exact) "정확한 위치로 공개해요" else "대략적인 위치로만 공개해요")
                if (err == null) { loadedPeriod = -1; load() }
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
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        // 어종 칩 (많이 잡힌 순)
        val counts = list.flatMap { t -> t.catches.filter { it.cell != null }.map { t.key(it.slot) } }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        if (counts.isEmpty()) {
            listBox.addView(Ui.text(this, 14f, Ui.SUB, false, "위치가 있는 공개 조과가 아직 없어요. 데이터가 모이면 어종별 포인트 순위가 보여요."),
                Ui.lp(this, MATCH, WRAP, top = 12))
            return
        }
        if (species == null || counts.none { it.key == species }) species = counts[0].key
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        counts.forEach { e ->
            chips.addView(pill("${PublicBoard.keyLabel(e.key)} ${e.value}", e.key == species, 13f) { species = e.key; render() },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
        }
        listBox.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, Ui.lp(this, MATCH, WRAP, top = 12))

        val me = myLocation()
        val sortRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sortRow.addView(pill("잘 나온 순", !nearFirst, 13f) { nearFirst = false; render() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        sortRow.addView(pill("가까운 순", nearFirst, 13f) {
            if (me == null) toast("내 위치를 몰라서 가까운 순으로 볼 수 없어요") else { nearFirst = true; render() }
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(6) })
        listBox.addView(sortRow, Ui.lp(this, MATCH, WRAP, top = 8))

        var pts = PublicBoard.points(list, species!!)
        if (nearFirst && me != null) pts = pts.sortedBy { distKm(me, it.cell.lat, it.cell.lon) }
        listBox.addView(Ui.text(this, 13f, Ui.SUB, false,
            "${PublicBoard.keyLabel(species!!)} 포인트 ${pts.size}곳 · 약 150m 범위로 묶음 (대략 공개는 1km) · 순위는 1시간당 마릿수 (출조 2번 이상인 곳 먼저)"),
            Ui.lp(this, MATCH, WRAP, top = 10))
        val names = HashMap<String, Int>()
        pts.take(60).forEachIndexed { i, p ->
            val base = Tide.stationsNear(this, p.cell.lat, p.cell.lon).firstOrNull()?.name ?: "포인트"
            val n = (names[base] ?: 0) + 1; names[base] = n
            val title = "$base 부근" + if (n > 1) " #$n" else ""
            val c = Ui.card(this).apply { isClickable = true; setOnClickListener { pointDetail(p, title) } }
            val r = Ui.row(this)
            r.addView(Ui.text(this, 16f, Ui.TEXT, true, "${i + 1}. $title"), LinearLayout.LayoutParams(0, WRAP, 1f))
            if (p.trips.size < 2) r.addView(Ui.chip(this, "데이터 부족", Ui.SUB), LinearLayout.LayoutParams(WRAP, WRAP))
            else r.addView(Ui.text(this, 13f, Ui.SUB, false, "출조 ${p.trips.size}번 ›"))
            c.addView(r)
            val dist = me?.let { String.format(Locale.KOREA, " · 내 위치에서 %.1fkm", distKm(it, p.cell.lat, p.cell.lon)) } ?: ""
            c.addView(Ui.text(this, 14f, Ui.TEXT, false,
                String.format(Locale.KOREA, "총 %d마리 · 1시간에 평균 %.1f마리", p.total, p.rate) + dist), Ui.lp(this, MATCH, WRAP, top = 4))
            val best = PublicBoard.bestWhen(p)
            if (best.isNotBlank()) c.addView(Ui.text(this, 13f, Ui.GREEN, false, "잘 나온 때: $best"), Ui.lp(this, MATCH, WRAP, top = 2))
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
        fun para(text: String, top: Int = 10, bold: Boolean = false) =
            box.addView(Ui.text(this, 13.5f, Ui.TEXT, bold, text).apply { setLineSpacing(0f, 1.25f) }, Ui.lp(this, MATCH, WRAP, top = top))
        fun barRows(title: String, rows: List<Pair<String, Int>>) {
            if (rows.isEmpty()) return
            para(title, 14, true)
            val max = rows.maxOf { it.second }.coerceAtLeast(1)
            rows.forEach { (k, v) ->
                val r = Ui.row(this)
                r.addView(Ui.text(this, 13f, Ui.SUB, false, k), LinearLayout.LayoutParams(dp(64), WRAP))
                val track = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                track.addView(View(this).apply { background = Ui.bg(this@BoardActivity, Ui.ORANGE, 3) },
                    LinearLayout.LayoutParams(0, dp(10), v.toFloat() / max))
                track.addView(View(this), LinearLayout.LayoutParams(0, dp(10), 1f - v.toFloat() / max + 0.0001f))
                r.addView(track, LinearLayout.LayoutParams(0, WRAP, 1f))
                r.addView(Ui.text(this, 13f, Ui.TEXT, true, v.toString()), LinearLayout.LayoutParams(dp(32), WRAP).apply { leftMargin = dp(8) })
                box.addView(r, Ui.lp(this, MATCH, WRAP, top = 4))
            }
        }
        para(String.format(Locale.KOREA, "%s %d마리 · 출조 %d번 · 1시간에 평균 %.1f마리", spLabel, p.total, p.trips.size, p.rate), 10, true)
        val days = StringBuilder()
        p.catches.groupBy { dateFmt.format(Date(it.first.start)) + "|" + it.first.nick }
            .entries.sortedByDescending { e -> e.value[0].first.start }
            .forEach { e -> days.append(e.key.substringBefore("|")).append("  ").append(e.key.substringAfter("|"))
                .append("  ").append(e.value.size).append("마리 · ").append(Tide.mulName(e.value[0].first.start).substringBefore(" ·")).append("\n") }
        para("날짜별", 14, true)
        para(days.toString().trimEnd(), 2)
        val cs = p.catches.map { it.second }
        barRows("물때 단계", cs.groupingBy { Tide.mulName(it.time).substringAfter("· ").substringBefore(" (") }
            .eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value })
        val ph = phaseText(cs)
        if (ph.isNotBlank()) { para("물 흐름 · 만조·간조 기준", 14, true); para(ph.trimEnd(), 2) }
        val hourFmt = SimpleDateFormat("H", Locale.KOREA)
        barRows("시간대", cs.groupingBy { hourFmt.format(Date(it.time)).toInt() }.eachCount()
            .entries.sortedBy { it.key }.map { "${it.key}시" to it.value })
        val monFmt = SimpleDateFormat("M월", Locale.KOREA)
        barRows("잘 나온 달", cs.groupingBy { monFmt.format(Date(it.time)) }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key to it.value })
        val dlg = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("$title · $spLabel")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("닫기", null)
            .create()
        dlg.setOnDismissListener { em.live.stop(); em.map.onPause(); em.map.onDetach() }
        dlg.show()
    }

    override fun onResume() { super.onResume(); refreshNick() }
}
