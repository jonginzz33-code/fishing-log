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

    private var all: List<PublicBoard.PTrip> = emptyList()
    private var tab = 0            // 0 = 최근 조황, 1 = 포인트 도감
    private var cat = ""           // "" = 전체
    private var period = 1         // 0 = 1개월, 1 = 3개월, 2 = 전체
    private var species: String? = null
    private var nearFirst = false
    private var loadedPeriod = -1

    private val dayFmt = SimpleDateFormat("M/d (E)", Locale.KOREA)
    private val dateFmt = SimpleDateFormat("yy.M.d (E)", Locale.KOREA)
    private val hm = SimpleDateFormat("HH:mm", Locale.KOREA)

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
        top.addView(Ui.text(this, 20f, Ui.TEXT, true, "전체 조황 · 포인트"), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(14) })
        col.addView(top)
        nickText = Ui.text(this, 12f, Ui.SUB, false).apply { setOnClickListener { askNick() } }
        col.addView(nickText, Ui.lp(this, MATCH, WRAP, top = 8))

        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(tabRow, Ui.lp(this, MATCH, WRAP, top = 10))
        catRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(catRow, Ui.lp(this, MATCH, WRAP, top = 10))
        periodRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(periodRow, Ui.lp(this, MATCH, WRAP, top = 8))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox, Ui.lp(this, MATCH, WRAP, top = 6))
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(col) })
        renderControls()
        load()
    }

    private fun refreshNick() {
        nickText.text = "내 게시판 이름: ${PublicBoard.nickShown()} ✎  ·  내 출조 공개는 출조 종료 때 또는 출조 기록 화면에서"
    }

    private fun askNick() {
        val input = EditText(this).apply { setText(Store.nick ?: Cloud.displayName); inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("전체 게시판에 보일 이름")
            .setMessage("앞으로 공개하는 출조부터 이 이름으로 보여요")
            .setView(input)
            .setPositiveButton("저장") { _, _ -> Store.nick = input.text.toString().trim().take(20); refreshNick() }
            .setNeutralButton("익명") { _, _ -> Store.nick = ""; refreshNick() }
            .setNegativeButton("취소", null)
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
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) leftMargin = dp(6) })
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
        PublicBoard.load(since) { list, err ->
            if (want != period) return@load
            if (list == null) {
                listBox.removeAllViews()
                listBox.addView(Ui.text(this, 14f, Ui.ORANGE, false, err ?: "실패"), Ui.lp(this, MATCH, WRAP, top = 14))
                return@load
            }
            all = list; loadedPeriod = want
            render()
        }
    }

    private fun filtered() = all.filter { cat == "" || it.cat == cat }

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

    private fun renderRecent() {
        val list = filtered()
        listBox.addView(Ui.text(this, 13f, Ui.SUB, false, "공개된 출조 ${list.size}개 · 최신순"), Ui.lp(this, MATCH, WRAP, top = 12))
        if (list.isEmpty()) {
            listBox.addView(Ui.text(this, 14f, Ui.SUB, false, "아직 공개된 출조가 없어요. 출조를 종료할 때 '전체 게시판에도 공개'를 체크해 보세요."),
                Ui.lp(this, MATCH, WRAP, top = 10))
            return
        }
        list.forEach { t ->
            val c = Ui.card(this).apply {
                isClickable = true
                setOnClickListener { tripDetail(t) }
                if (Cloud.isAdmin || t.owner == Cloud.uid) {
                    isLongClickable = true
                    setOnLongClickListener { askRemove(t); true }
                }
            }
            val r = Ui.row(this)
            r.addView(Ui.text(this, 15f, Ui.TEXT, true, dayFmt.format(Date(t.start)) +
                (if (t.cat.isNotBlank()) " · " + Store.catShort(t.cat) else "") + " · " +
                Tide.mulName(t.start).substringBefore(" ·")), LinearLayout.LayoutParams(0, WRAP, 1f))
            if (t.photoCount > 0) r.addView(Ui.chip(this, "사진 ${t.photoCount}"), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
            c.addView(r)
            c.addView(Ui.text(this, 14f, Ui.TEXT, false, "${t.nick} · ${t.speciesLine()} · 총 ${t.catches.size}"),
                Ui.lp(this, MATCH, WRAP, top = 4))
            val reg = regionOf(t)
            if (reg.isNotBlank()) c.addView(Ui.text(this, 12f, Ui.SUB, false, reg), Ui.lp(this, MATCH, WRAP, top = 2))
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
        box.addView(Ui.text(this, 14f, Ui.TEXT, false, sb.toString().trimEnd()).apply { setLineSpacing(0f, 1.3f) })
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
        val b = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("${t.nick} · ${dateFmt.format(Date(t.start))}")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("닫기", null)
        if (t.owner == Cloud.uid || Cloud.isAdmin) b.setNegativeButton("공개 내리기") { _, _ -> askRemove(t) }
        b.show()
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
        val counts = list.flatMap { t -> t.catches.filter { it.cell != null }.map { t.name(it.slot) } }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        if (counts.isEmpty()) {
            listBox.addView(Ui.text(this, 14f, Ui.SUB, false, "위치가 있는 공개 조과가 아직 없어요. 데이터가 모이면 어종별 포인트 순위가 보여요."),
                Ui.lp(this, MATCH, WRAP, top = 12))
            return
        }
        if (species == null || counts.none { it.key == species }) species = counts[0].key
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        counts.forEach { e ->
            chips.addView(pill("${e.key} ${e.value}", e.key == species, 13f) { species = e.key; render() },
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
            "$species 포인트 ${pts.size}곳 · 약 150m 범위로 묶음 · 순위는 1시간당 마릿수 (출조 2번 이상인 곳 먼저)"),
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

    private fun bars(title: String, rows: List<Pair<String, Int>>): String {
        if (rows.isEmpty()) return ""
        val max = rows.maxOf { it.second }.coerceAtLeast(1)
        val sb = StringBuilder(title).append("\n")
        rows.forEach { (k, v) ->
            sb.append(k.padEnd(6)).append(" ").append("■".repeat(((v * 10.0) / max).toInt().coerceAtLeast(1))).append(" ").append(v).append("\n")
        }
        return sb.append("\n").toString()
    }

    private fun pointDetail(p: PublicBoard.Point, title: String) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(6)) }
        val map = MapDialog.circleMap(this, p.cell.lat, p.cell.lon, PublicBoard.CELL_M / 2 * 1.2)
        box.addView(map, LinearLayout.LayoutParams(MATCH, dp(200)))
        val sb = StringBuilder()
        sb.append(String.format(Locale.KOREA, "%s %d마리 · 출조 %d번 · 1시간에 평균 %.1f마리\n\n", species, p.total, p.trips.size, p.rate))
        sb.append("날짜별\n")
        p.catches.groupBy { dateFmt.format(Date(it.first.start)) + "|" + it.first.nick }
            .entries.sortedByDescending { e -> e.value[0].first.start }
            .forEach { e -> sb.append(e.key.substringBefore("|")).append("  ").append(e.key.substringAfter("|"))
                .append("  ").append(e.value.size).append("마리 · ").append(Tide.mulName(e.value[0].first.start).substringBefore(" ·")).append("\n") }
        sb.append("\n")
        val cs = p.catches.map { it.second }
        sb.append(bars("물때 단계", cs.groupingBy { Tide.mulName(it.time).substringAfter("· ").substringBefore(" (") }
            .eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }))
        val ph = phaseText(cs)
        if (ph.isNotBlank()) sb.append("물 흐름 · 만조·간조 기준\n").append(ph).append("\n")
        val hourFmt = SimpleDateFormat("H", Locale.KOREA)
        sb.append(bars("시간대", cs.groupingBy { hourFmt.format(Date(it.time)).toInt() }.eachCount()
            .entries.sortedBy { it.key }.map { "${it.key}시" to it.value }))
        val monFmt = SimpleDateFormat("M월", Locale.KOREA)
        sb.append(bars("잘 나온 달", cs.groupingBy { monFmt.format(Date(it.time)) }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key to it.value }))
        box.addView(Ui.text(this, 13f, Ui.TEXT, false, sb.toString().trimEnd()).apply {
            typeface = android.graphics.Typeface.MONOSPACE; setLineSpacing(0f, 1.25f)
        }, Ui.lp(this, MATCH, WRAP, top = 10))
        val dlg = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("$title · $species")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("닫기", null)
            .create()
        dlg.setOnDismissListener { map.onPause(); map.onDetach() }
        dlg.show()
    }

    override fun onResume() { super.onResume(); refreshNick() }
}
