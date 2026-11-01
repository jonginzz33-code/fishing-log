package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 포인트 게시판 '🗺 지도': ☑ 전체 포인트 · ☑ 나만의 포인트 (하나만 / 둘 다)
 *  + ⭕ 원 그리기 → 원 안 조과 분석 (Analysis)
 */
object PointMap {
    private fun prefs(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)

    fun open(a: Activity, pubList: List<PublicBoard.PTrip>, cat: String, since: Long) {
        fun dp(v: Int) = Ui.dp(a, v)
        val p = prefs(a)
        var showPub = p.getBoolean("pm_pub", true)
        var showMine = p.getBoolean("pm_mine", true)
        val df = SimpleDateFormat("yy.M.d HH:mm", Locale.KOREA)
        val me = Cloud.uid
        val mineRecs = Analysis.mine(cat, since)
        val pubRecs = Analysis.public(pubList, me)   // 남이 올린 글
        val pubMine = Analysis.public(pubList.filter { it.owner == me }, null)   // 내가 올린 글 (나만의 포인트를 끄면 여기서 보임)
        // v3.8.1 그룹 포인트: 내가 가입한 그룹 중 고른 그룹의 출조 기록 조과 (커튼에서 고름 · 폰에 기억)
        val myGroups = Cloud.groups
        val selGroups = (p.getStringSet("pm_groups", emptySet()) ?: emptySet()).filter { id -> myGroups.any { it.id == id } }.toMutableSet()
        val groupTrips = HashMap<String, List<Cloud.RemoteTrip>>()
        val groupLoading = HashSet<String>()
        var groupOpen = false

        val dlg = Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        val head = Ui.row(a).apply { setPadding(dp(14), dp(10), dp(10), dp(4)) }
        head.addView(Ui.text(a, 17f, Ui.TEXT, true, "🗺 포인트 지도" + if (cat.isNotBlank()) " · ${Store.catShort(cat)}" else ""), LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(Ui.text(a, 14f, Ui.TEXT, true, "닫기").apply {
            gravity = Gravity.CENTER; setPadding(dp(14), dp(6), dp(14), dp(6))
            background = Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE); setOnClickListener { dlg.dismiss() }
        })
        root.addView(head)
        val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(4), dp(12), dp(8)) }
        root.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(bar) })
        // v3.9.2 (161) 해루질 참고용 커튼 · map 은 아래에서 만들기 때문에 자리만 잡아 둠
        val zSlot = FrameLayout(a)
        root.addView(zSlot, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(12), 0, dp(12), dp(6)) })
        // 그룹 포인트 커튼 (그룹 목록 체크)
        val curtain = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(8)); visibility = View.GONE
            background = Ui.bg(a, Color.parseColor("#1A2A38"), 14, Color.parseColor("#FF8FB8"))
        }
        root.addView(curtain, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(12), 0, dp(12), dp(8)) })

        val map = MapDialog.newMap(a)
        val dots = DotsOverlay(a, emptyList(), false) { MapDialog.openCluster(a, it) }.apply { icons = true; mergeDp = 34f }
        val circle = CircleOverlay(a)
        val zBar = Zones.bar(a, map)
        zSlot.addView(zBar.curtain, FrameLayout.LayoutParams(MATCH, WRAP))
        val zoneOv = Zones.ZoneOverlay(a) { Zones.on(a) }
        zoneOv.onTapZones = { Zones.showInfo(a, it, map) }
        map.overlays.add(zoneOv)
        map.overlays.add(dots)
        map.overlays.add(circle)
        val liveMe = LiveMe.attach(a, map, emptyList(), "포인트", autoFit = false)
        val frame = FrameLayout(a)
        frame.addView(map, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val info = Ui.text(a, 12.5f, Ui.SUB, false, "")
        val anBtn = Ui.button(a, "📊 이 원 안 분석 보기", true).apply { visibility = View.GONE; minHeight = dp(46) }
        val bottom = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(12)) }
        val zoneHint = Ui.text(a, 12f, Color.parseColor("#FFB27A"), false, "").apply { visibility = View.GONE }
        bottom.addView(zoneHint)
        zoneOv.hint = { s -> zoneHint.text = s; zoneHint.visibility = if (s.isBlank()) View.GONE else View.VISIBLE }
        bottom.addView(info)
        bottom.addView(anBtn, Ui.lp(a, MATCH, WRAP, top = 8))
        root.addView(bottom)

        // 둘 다 켜면 내 글은 '나만의 포인트'로 한 번만 · 전체만 켜면 내가 올린 글도 전체에 같이
        fun groupRecs(): List<Analysis.Rec> {
            val out = ArrayList<Analysis.Rec>()
            val seen = HashSet<String>()   // 같은 출조를 여러 그룹에 올린 경우 한 번만
            selGroups.forEach { gid ->
                val g = myGroups.firstOrNull { it.id == gid } ?: return@forEach
                Analysis.group(groupTrips[gid] ?: emptyList(), g.title, cat, since, if (showMine) me else null).forEach { r ->
                    if (seen.add("${r.trip}|${r.time}|${r.lat}")) out.add(r)
                }
            }
            return out
        }
        fun recs(): List<Analysis.Rec> = (if (showMine) mineRecs else emptyList()) +
            (if (showPub) pubRecs + (if (showMine) emptyList() else pubMine) else emptyList()) +
            groupRecs()
        fun inside(): List<Analysis.Rec> {
            val c = circle.center ?: return emptyList()
            return Analysis.inCircle(recs(), c.latitude, c.longitude, circle.radiusM)
        }
        fun paintInfo() {
            val c = circle.center
            if (c == null) {
                info.text = "점 ${recs().size}개 · 어종 그림 + 숫자 = 조과 (테두리: 보라 = 전체 · 흰 = 나만의 · 분홍 = 그룹)\n" +
                    if (circle.drawMode) "지도를 눌러 원을 놓으세요 · 분홍 점을 끌면 크기가 바뀌어요" else "'⭕ 원 그리기'로 자리를 골라 분석해요"
                anBtn.visibility = View.GONE
            } else {
                val l = inside()
                info.text = String.format(Locale.KOREA, "반경 %s · 원 안 %d마리 · 출조 %d번", radiusLabel(circle.radiusM), l.size, l.map { it.trip }.distinct().size) +
                    "\n분홍 점을 끌면 크기 · 지도를 누르면 원 옮기기"
                anBtn.visibility = if (l.isEmpty()) View.GONE else View.VISIBLE
            }
        }
        fun paintDots() {
            dots.dots = recs().map { r ->
                val grp = r.group.isNotBlank()
                MapDot(GeoPoint(r.lat, r.lon), if (r.mine) MapDialog.slotCol(1) else if (grp) Color.parseColor("#F28C38") else Color.parseColor("#5BC8DF"),
                    (if (r.mine) "나" else if (grp) "${r.group} · ${r.who}" else "전체") + " · ${df.format(Date(r.time))} · ${r.species}" + if (r.approx) " (대략)" else "",
                    r.species, r.time, if (r.mine) "나" else if (grp) "${r.group} · ${r.who}" else "", r.species, false,
                    if (r.mine) 0 else if (grp) 1 else 2, if (r.approx) PublicBoard.APPROX_M / 2 else 0.0)
            }
            map.invalidate(); paintInfo()
        }
        circle.onChange = { paintInfo() }


        fun pill(label: String, on: Boolean, onClick: () -> Unit): TextView = Ui.text(a, 13f, if (on) Ui.BG else Ui.TEXT, true, label).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), dp(7), dp(12), dp(7)); maxLines = 1
            background = if (on) Ui.bg(a, Ui.TEXT, 99) else Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE)
            setOnClickListener { onClick() }
        }
        fun paintBar() {
            bar.removeAllViews()
            fun add(v: View) = bar.addView(v, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
            add(MapSearch.pill(a, map))   // v3.9.1 (159) 🔍 장소 검색
            add(pill((if (showPub) "☑" else "☐") + " 전체 포인트", showPub) {
                showPub = !showPub; p.edit().putBoolean("pm_pub", showPub).apply(); paintBar(); paintDots()
            })
            add(pill((if (showMine) "☑" else "☐") + " 나만의 포인트", showMine) {
                showMine = !showMine; p.edit().putBoolean("pm_mine", showMine).apply(); paintBar(); paintDots()
            })
            // 그룹 포인트: 누르면 아래로 커튼 (가입한 그룹 체크)
            val gOn = selGroups.isNotEmpty()
            add(Ui.text(a, 13f, if (gOn) Color.parseColor("#4B1528") else Color.parseColor("#FF8FB8"), true,
                (if (gOn) "☑" else "☐") + " 그룹 포인트" + (if (gOn) " (${selGroups.size})" else "") + if (groupOpen) " ▴" else " ▾").apply {
                gravity = Gravity.CENTER; setPadding(dp(12), dp(7), dp(12), dp(7)); maxLines = 1
                background = if (gOn) Ui.bg(a, Color.parseColor("#FF8FB8"), 99) else Ui.bg(a, Color.TRANSPARENT, 99, Color.parseColor("#FF8FB8"))
                setOnClickListener { groupOpen = !groupOpen; paintBar() }
            })
            add(Ui.text(a, 13f, if (circle.drawMode) Color.WHITE else Color.parseColor("#FF4FA8"), true, if (circle.drawMode) "⭕ 원 그리는 중" else "⭕ 원 그리기").apply {
                gravity = Gravity.CENTER; setPadding(dp(12), dp(7), dp(12), dp(7))
                background = if (circle.drawMode) Ui.bg(a, Color.parseColor("#FF4FA8"), 99) else Ui.bg(a, Color.TRANSPARENT, 99, Color.parseColor("#FF4FA8"))
                setOnClickListener { circle.drawMode = !circle.drawMode; paintBar(); paintInfo(); map.invalidate() }
            })
            if (circle.center != null) add(pill("원 지우기", false) { circle.center = null; circle.drawMode = false; paintBar(); paintInfo(); map.invalidate() })
            (zBar.pill.parent as? android.view.ViewGroup)?.removeView(zBar.pill)
            add(zBar.pill)
            add(pill(if (Store.satellite) "지도로" else "위성으로", false) { MapDialog.setSatellite(map, !Store.satellite); paintBar() })
            paintCurtain()
        }
        fun loadGroup(gid: String) {
            if (groupTrips.containsKey(gid) || !groupLoading.add(gid)) return
            val g = myGroups.firstOrNull { it.id == gid } ?: return
            Cloud.recentTripsOf(g) { list, _ ->
                groupLoading.remove(gid)
                groupTrips[gid] = list ?: emptyList()
                if (dlg.isShowing) { paintDots(); paintCurtainRef?.invoke() }
            }
        }
        paintCurtainRef = {
            curtain.removeAllViews()
            curtain.visibility = if (groupOpen) View.VISIBLE else View.GONE
            if (groupOpen) {
                curtain.addView(Ui.text(a, 12f, Ui.SUB, false, if (myGroups.isEmpty()) "가입한 그룹이 없어요" else "볼 그룹 고르기 (가입한 그룹만 · 그룹 출조 기록에 올라온 조과)"))
                myGroups.forEach { g ->
                    val on = g.id in selGroups
                    val n = if (on) groupTrips[g.id]?.let { l -> Analysis.group(l, g.title, cat, since, null).size } else null
                    curtain.addView(Ui.text(a, 15f, if (on) Ui.TEXT else Ui.SUB, true,
                        (if (on) "☑ " else "☐ ") + g.title + when {
                            on && g.id in groupLoading -> "  · 불러오는 중…"
                            n != null -> "  · ${n}마리"
                            else -> ""
                        }).apply {
                        setPadding(0, dp(8), 0, dp(8))
                        setOnClickListener {
                            if (g.id in selGroups) selGroups.remove(g.id) else { selGroups.add(g.id); loadGroup(g.id) }
                            p.edit().putStringSet("pm_groups", HashSet(selGroups)).apply()
                            paintBar(); paintDots()
                        }
                    })
                }
            }
        }
        selGroups.forEach { loadGroup(it) }
        anBtn.setOnClickListener {
            val c = circle.center ?: return@setOnClickListener
            showAnalysis(a, inside(), Area.name(a, c.latitude, c.longitude).ifBlank { "이 자리" } + " · 반경 " + radiusLabel(circle.radiusM),
                showPub, showMine, selGroups.mapNotNull { id -> myGroups.firstOrNull { it.id == id }?.title })
        }
        paintBar(); paintDots()
        dlg.setContentView(root)
        dlg.setOnDismissListener { liveMe.stop(); map.onPause(); map.onDetach() }
        dlg.show()
        map.onResume()
        map.post {
            val pts = recs().map { GeoPoint(it.lat, it.lon) }
            if (pts.size >= 2) {
                val box = BoundingBox.fromGeoPoints(pts)
                if (box.latitudeSpan < 0.002 && box.longitudeSpan < 0.002) { map.controller.setZoom(16.0); map.controller.setCenter(box.centerWithDateLine) }
                else map.zoomToBoundingBox(box.increaseByScale(1.3f), false)
            } else if (pts.size == 1) { map.controller.setZoom(15.0); map.controller.setCenter(pts[0]) }
            else { map.controller.setZoom(9.0); map.controller.setCenter(Tracker.last?.let { GeoPoint(it.latitude, it.longitude) } ?: GeoPoint(37.0, 126.6)) }
        }
    }

    fun radiusLabel(m: Double): String = if (m >= 1000) String.format(Locale.KOREA, "%.1fkm", m / 1000) else "${m.toInt()}m"

    /** 원: 그리기 모드에서 지도를 누르면 놓기 · 분홍 점(오른쪽 끝)을 끌면 크기 */
    private class CircleOverlay(c: Context) : Overlay() {
        private val d = c.resources.displayMetrics.density
        var center: GeoPoint? = null
        var radiusM = 300.0
        var drawMode = false
        var onChange: (() -> Unit)? = null
        private var dragging = false
        private var downX = 0f; private var downY = 0f
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 255, 79, 168) }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; color = Color.parseColor("#FF4FA8") }
        private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF4FA8") }
        private val hEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; color = Color.WHITE }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C0307A"); textSize = 12 * d; typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        private val txtBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        private fun handlePx(map: MapView): Pair<Float, Float>? {
            val c = center ?: return null
            val p = map.projection.toPixels(c, null)
            val rr = map.projection.metersToPixels(radiusM.toFloat())
            return (p.x + rr) to p.y.toFloat()
        }

        override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
            if (shadow) return
            val ctr = center ?: return
            val p = map.projection.toPixels(ctr, null)
            val rr = map.projection.metersToPixels(radiusM.toFloat())
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), rr, fill)
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), rr, edge)
            val (hx, hy) = handlePx(map) ?: return
            c.drawCircle(hx, hy, 11 * d, handle); c.drawCircle(hx, hy, 11 * d, hEdge)
            val label = "반경 ${radiusLabel(radiusM)}"
            val w = txt.measureText(label) + 12 * d
            val ty = p.y + rr + 22 * d
            c.drawRoundRect(android.graphics.RectF(p.x - w / 2, ty - 15 * d, p.x + w / 2, ty + 5 * d), 6 * d, 6 * d, txtBg)
            c.drawText(label, p.x.toFloat(), ty, txt)
        }

        override fun onTouchEvent(e: MotionEvent, map: MapView): Boolean {
            if (center == null && !drawMode) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y
                    val h = handlePx(map)
                    dragging = h != null && kotlin.math.hypot((e.x - h.first).toDouble(), (e.y - h.second).toDouble()) < 34 * d
                    return dragging
                }
                MotionEvent.ACTION_MOVE -> if (dragging) {
                    val ctr = center ?: return false
                    val p = map.projection.toPixels(ctr, null)
                    val px = kotlin.math.hypot((e.x - p.x).toDouble(), (e.y - p.y).toDouble()).toFloat()
                    val perPx = radiusM / map.projection.metersToPixels(radiusM.toFloat()).coerceAtLeast(1f)
                    radiusM = (px * perPx).coerceIn(30.0, 20_000.0)
                    map.invalidate(); onChange?.invoke(); return true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) { dragging = false; return true }
                    // 그리기 모드에서 살짝 누르면 원 놓기/옮기기
                    if (drawMode && kotlin.math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) < 12 * d) {
                        center = map.projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
                        map.invalidate(); onChange?.invoke(); return true
                    }
                }
            }
            return false
        }
    }

    // ---------------- 분석 창 ----------------
    /** 커튼 다시 그리기 (open 안에서 정함) */
    private var paintCurtainRef: (() -> Unit)? = null
    private fun paintCurtain() { paintCurtainRef?.invoke() }

    fun showAnalysis(a: Activity, recs: List<Analysis.Rec>, place: String, pub: Boolean, mine: Boolean, groups: List<String> = emptyList()) {
        fun dp(v: Int) = Ui.dp(a, v)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(6), dp(14), dp(10)) }
        val loading = Ui.text(a, 13f, Ui.SUB, false, "계산하는 중…")
        box.addView(loading)
        val dlg = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("⭕ $place")
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton("닫기", null).show()
        dlg.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, (a.resources.displayMetrics.heightPixels * 0.9).toInt())
        Analysis.run(a, recs, { loading.text = it }) { res ->
            if (!dlg.isShowing) return@run
            box.removeAllViews()
            val mf = SimpleDateFormat("yyyy.M", Locale.KOREA)
            box.addView(Ui.text(a, 14f, Ui.TEXT, true, "출조 ${res.trips}번 · 총 ${res.total}마리 · ${mf.format(Date(res.from))} ~ ${mf.format(Date(res.to))}"))
            box.addView(Ui.text(a, 11.5f, Ui.SUB, false, ("분석 대상: " + (listOfNotNull(if (pub) "전체" else null, if (mine) "나만의" else null) + groups).joinToString(" + ").ifBlank { "-" }) +
                " · 물세기·만조간조는 가까운 관측소 기준" + if (res.note.isNotBlank()) "\n${res.note}" else ""), Ui.lp(a, MATCH, WRAP, top = 2))
            // 어종별 한눈에 정리
            res.species.take(6).forEach { sp ->
                val c = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); background = Ui.bg(a, Ui.CARD, 14, Ui.LINE) }
                val h = Ui.row(a)
                h.addView(Ui.text(a, 16f, Ui.TEXT, true, sp.name), LinearLayout.LayoutParams(WRAP, WRAP))
                h.addView(Ui.text(a, 11.5f, Ui.BG, true, String.format(Locale.KOREA, " 1시간 평균 %.1f마리 ", sp.rate)).apply {
                    background = Ui.bg(a, Ui.ORANGE, 8) }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
                h.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
                h.addView(Ui.text(a, 11.5f, Ui.SUB, false, "${sp.count}마리 · 출조 ${sp.trips}번"))
                c.addView(h)
                sp.conds.forEach { cd ->
                    val b = cd.best() ?: return@forEach
                    val r = Ui.row(a)
                    r.addView(Ui.text(a, 12.5f, Ui.SUB, false, cd.title), LinearLayout.LayoutParams(dp(150), WRAP))
                    r.addView(Ui.text(a, 13f, if (b.weak) Ui.SUB else Color.parseColor("#FFB36B"), true, b.label + if (b.weak) " (데이터 부족)" else ""),
                        LinearLayout.LayoutParams(0, WRAP, 1f))
                    c.addView(r, Ui.lp(a, MATCH, WRAP, top = 4))
                }
                box.addView(c, Ui.lp(a, MATCH, WRAP, top = 10))
            }
            if (res.species.isEmpty()) { box.addView(Ui.text(a, 13f, Ui.SUB, false, "원 안에 조과가 없어요")); return@run }
            // 조건별 자세히 (탭 + 어종 고르기)
            box.addView(Ui.text(a, 15f, Ui.TEXT, true, "📊 조건별 자세히"), Ui.lp(a, MATCH, WRAP, top = 16))
            val spRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            val tabRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            box.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(spRow) }, Ui.lp(a, MATCH, WRAP, top = 6))
            box.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(tabRow) }, Ui.lp(a, MATCH, WRAP, top = 6))
            val detail = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            box.addView(detail, Ui.lp(a, MATCH, WRAP, top = 6))
            var spI = 0; var condI = 0
            fun chip(label: String, on: Boolean, col: Int, click: () -> Unit) = Ui.text(a, 12f, if (on) Color.WHITE else Ui.TEXT, on, label).apply {
                gravity = Gravity.CENTER; setPadding(dp(10), dp(5), dp(10), dp(5)); maxLines = 1
                background = if (on) Ui.bg(a, col, 99) else Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE)
                setOnClickListener { click() }
            }
            fun paint() {
                spRow.removeAllViews(); tabRow.removeAllViews(); detail.removeAllViews()
                res.species.forEachIndexed { i, s -> spRow.addView(chip(s.name, i == spI, Ui.ORANGE) { spI = i; paint() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) }) }
                val sp = res.species[spI]
                sp.conds.forEachIndexed { i, cd -> tabRow.addView(chip(cd.title.substringAfter(" "), i == condI, Color.parseColor("#FF4FA8")) { condI = i; paint() },
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) }) }
                val cd = sp.conds[condI.coerceIn(0, sp.conds.size - 1)]
                detail.addView(Ui.text(a, 13.5f, Ui.TEXT, true, cd.title + if (cd.rated) " · 1시간 평균 마릿수" else " · 마릿수"))
                if (cd.buckets.isEmpty()) { detail.addView(Ui.text(a, 12.5f, Ui.SUB, false, "이 조건 자료가 없어요 (예전 기록엔 날씨가 없을 수 있어요)"), Ui.lp(a, MATCH, WRAP, top = 4)); return }
                val best = cd.best()
                val max = cd.buckets.maxOf { it.rate ?: it.count.toDouble() }.coerceAtLeast(0.01)
                cd.buckets.forEach { b ->
                    val v = b.rate ?: b.count.toDouble()
                    val r = Ui.row(a).apply { setPadding(dp(4), dp(4), dp(4), dp(4)); if (b === best) background = Ui.bg(a, Color.parseColor("#2A2310"), 8) }
                    r.alpha = if (b.weak) 0.55f else 1f
                    r.addView(Ui.text(a, 12f, Ui.TEXT, b === best, b.label).apply { maxLines = 1 }, LinearLayout.LayoutParams(dp(118), WRAP))
                    val track = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                    track.addView(View(a).apply { background = Ui.bg(a, Ui.ORANGE, 4) }, LinearLayout.LayoutParams(0, dp(10), (v / max).toFloat()))
                    track.addView(View(a), LinearLayout.LayoutParams(0, dp(10), (1 - v / max).toFloat() + 0.0001f))
                    r.addView(track, LinearLayout.LayoutParams(0, WRAP, 1f))
                    r.addView(Ui.text(a, 12f, Ui.TEXT, true, (if (b.rate != null) String.format(Locale.KOREA, "%.1f", b.rate) else "${b.count}") + if (b === best) " 👑" else ""),
                        LinearLayout.LayoutParams(dp(54), WRAP).apply { leftMargin = dp(6) })
                    r.addView(Ui.text(a, 10.5f, Ui.SUB, false, "출조 ${b.trips}"), LinearLayout.LayoutParams(dp(44), WRAP))
                    detail.addView(r)
                }
                detail.addView(Ui.text(a, 10.5f, Ui.SUB, false, "흐린 칸 = 출조 3번 이하 (데이터 부족) · 👑 = 가장 잘 나온 조건"), Ui.lp(a, MATCH, WRAP, top = 4))
            }
            paint()
        }
    }
}
