package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 지도에 그릴 한 사람(또는 한 출조)의 경로와 조과 */
class MapLayer(val color: Int, val track: List<GeoPoint>, val catches: List<MapCatch>)
class MapCatch(val point: GeoPoint, val color: Int, val title: String, val kind: String = "",
               val time: Long = 0L, val who: String = "", val species: String = "", val paid: Boolean = false,
               /** 대략 위치(GPS 아님) · 오차(m) */
               val rough: Boolean = false, val accM: Double = 0.0)

object MapDialog {
    /** v4.1 (185): 모바일 데이터로 위성 지도를 켜면 (앱 켜 있는 동안 한 번) 알려 주기 */
    private var satWarned = false
    fun satWarn(a: android.content.Context) {
        if (Store.satellite && !satWarned && Ui.metered(a)) { satWarned = true; Ui.toast(a, "📶 모바일 데이터예요 · 위성 지도는 일반 지도보다 데이터를 몇 배 더 써요") }
    }


    private var configured = false

    val SLOT_COLORS = intArrayOf(Color.parseColor("#F28C38"), Color.parseColor("#5BC8DF"))

    /** 버튼 색: 1·2번은 지도 색, 3번 이상은 퀵 버튼 색 */

    fun slotCol(slot: Int): Int = if (slot in 1..SLOT_COLORS.size) SLOT_COLORS[slot - 1] else Store.slotColor(slot)
    val FRIEND_COLORS = intArrayOf(
        Color.parseColor("#F2C14E"), Color.parseColor("#F07AA0"), Color.parseColor("#B9A6FF"),
        Color.parseColor("#5FD39A"), Color.parseColor("#FF7B5C"), Color.parseColor("#9ADBFF")
    )

    /** 위성 사진 (Esri World Imagery) */
    private val satelliteSource = object : OnlineTileSourceBase(
        "EsriWorldImagery", 0, 19, 256, "",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String =
            baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
                MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
    }

    /** 다른 화면에서 쓰는 지도 하나 (설정 · 위성 여부 그대로) */
    fun newMap(a: Activity): MapView {
        configure(a)
        return MapView(a).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)   // v5.2 (237): 앱 ＋/－ 버튼을 따로 씀
            setTileSource(if (Store.satellite) satelliteSource else TileSourceFactory.MAPNIK)
            isTilesScaledToDpi = true
            isVerticalMapRepetitionEnabled = false; minZoomLevel = 4.0   // v6.73: 세계가 위아래로 겹쳐 보이지 않게
        }
    }
    /**
     * v5.2 (237): 지도 ＋/－ 버튼 — 오른쪽 아래 반투명 어두운 둥근 묶음 (＋ 위 · － 아래) + 그 아래 ◎ 내 위치
     *  onMe = null 이면 ◎ 없음
     */
    fun zoomButtons(a: Activity, frame: FrameLayout, map: MapView, onMe: ((TextView?) -> Unit)? = null, meView: ((TextView) -> Unit)? = null) {
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        fun box() = GradientDrawable().apply { setColor(Color.argb(225, 11, 26, 38)); cornerRadius = dp(a, 14).toFloat(); setStroke(dp(a, 1), Color.parseColor("#2C4656")) }
        val pair = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = box(); elevation = dp(a, 3).toFloat() }
        fun key(label: String, click: () -> Unit) = TextView(a).apply {
            text = label; textSize = 24f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; includeFontPadding = false
            setOnClickListener { click() }
        }
        pair.addView(key("＋") { map.controller.zoomIn() }, LinearLayout.LayoutParams(dp(a, 46), dp(a, 46)))
        pair.addView(android.view.View(a).apply { setBackgroundColor(Color.parseColor("#2C4656")) },
            LinearLayout.LayoutParams(dp(a, 30), dp(a, 1)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        pair.addView(key("－") { map.controller.zoomOut() }, LinearLayout.LayoutParams(dp(a, 46), dp(a, 46)))
        col.addView(pair, LinearLayout.LayoutParams(dp(a, 46), ViewGroup.LayoutParams.WRAP_CONTENT))
        if (onMe != null) col.addView(TextView(a).apply {
            // v6.43 (302): ◎ 글자 대신 조준(GPS) 그림
            // v6.44 (307): 그림을 동그라미 정가운데 (예전엔 글자칸 왼쪽 그림이라 왼쪽으로 치우침)
            text = ""; gravity = Gravity.CENTER; includeFontPadding = false
            foreground = a.getDrawable(R.drawable.ic_target)?.mutate()?.apply { setTint(Color.parseColor("#6FC3FF")) }
            foregroundGravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(225, 11, 26, 38)); setStroke(dp(a, 1), Color.parseColor("#2C4656")) }
            elevation = dp(a, 3).toFloat()
            contentDescription = "내 위치로"
            setOnClickListener { onMe(null) }
            meView?.invoke(this)
        }, LinearLayout.LayoutParams(dp(a, 46), dp(a, 46)).apply { topMargin = dp(a, 10) })
        frame.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(a, 12); bottomMargin = dp(a, 64) })
    }

    fun setSatellite(map: MapView, on: Boolean) { Store.satellite = on; map.setTileSource(if (on) satelliteSource else TileSourceFactory.MAPNIK) }
    fun openCluster(a: Activity, items: List<MapDot>) = showCluster(a, items)

    private fun configure(a: Activity) {
        if (configured) return
        val c = Configuration.getInstance()
        c.userAgentValue = a.packageName
        c.osmdroidBasePath = File(a.filesDir, "osmdroid")
        c.osmdroidTileCache = File(a.cacheDir, "osmdroid-tiles")
        configured = true
    }

    private fun dp(a: Context, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    private fun toDots(cs: List<MapCatch>) = cs.map { MapDot(it.point, it.color, it.title, it.kind.ifBlank { it.title }, it.time, it.who, it.species, it.paid, 0, it.accM, it.rough) }

    /** 해루질에서 유료로 적은 출조만 지도에 "유료" 표시 (선상낚시는 원래 다 유료라 표시 안 함) */
    fun paidMark(cat: String, paid: Boolean?): Boolean = cat == "night" && paid == true

    private val dayKey = SimpleDateFormat("yyyyMMdd", Locale.KOREA)
    private val dayLabel = SimpleDateFormat("yyyy년 M월 d일 (E)", Locale.KOREA)
    private val hmFmt = SimpleDateFormat("HH:mm", Locale.KOREA)

    private fun speciesLine(items: List<MapDot>): String =
        items.groupingBy { it.species }.eachCount().entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.key} ${it.value}" }

    /** 묶음 점을 눌렀을 때: 날짜별(사람별)로 한 줄씩 요약 */
    private fun showCluster(a: Activity, items: List<MapDot>, onFocus: ((String) -> Unit)? = null) {
        if (items.isEmpty()) return
        if (items.any { it.time <= 0L || it.species.isBlank() }) { showClusterOld(a, items); return }
        // v6.9 (256): 출조를 알면 출조별로 (모르면 예전처럼 날짜 · 사람별)
        val groups = items.groupBy { if (it.trip.isNotBlank()) it.trip else dayKey.format(Date(it.time)) + "|" + it.who }.values
            .sortedByDescending { g -> g.maxOf { it.time } }
        val days = groups.map { dayKey.format(Date(it[0].time)) }.distinct().size
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(a, 16), dp(a, 4), dp(a, 16), dp(a, 4)) }
        val dlg = AlertDialog.Builder(a, R.style.FlAlert)
            .setTitle("${items.size}마리 · ${days}일 · " + speciesLine(items))
            .setView(android.widget.ScrollView(a).apply { addView(col) })
            .setPositiveButton("닫기", null)
            .create()
        // 이 자리 어종별 마릿수 (많은 순)
        val sp = items.groupingBy { it.species }.eachCount().entries.sortedByDescending { it.value }
        if (sp.size >= 2) {
            val box = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(a, 14), dp(a, 10), dp(a, 14), dp(a, 10))
                background = Ui.bg(a, Color.parseColor("#13212F"), 12, Ui.LINE)
            }
            box.addView(Ui.text(a, 13f, Ui.SUB, true, "이 자리 · ${sp.size}종 ${items.size}마리"))
            sp.forEach { e ->
                val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
                r.addView(Ui.text(a, 14f, Ui.TEXT, false, e.key), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                r.addView(Ui.text(a, 14f, Ui.ORANGE, true, "${e.value}마리"))
                box.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(a, 3) })
            }
            col.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(a, 8) })
        }
        groups.forEach { g ->
            val first = g.minByOrNull { it.time }!!
            val card = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(a, 14), dp(a, 10), dp(a, 14), dp(a, 10))
                background = Ui.bg(a, Ui.CARD, 12, Ui.LINE)
                isClickable = true
                setOnClickListener { showDay(a, g, pastTrips[g[0].trip]?.fresh == true) }
            }
            val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL }
            top.addView(android.view.View(a).apply { background = Ui.bg(a, first.color, 99) },
                LinearLayout.LayoutParams(dp(a, 10), dp(a, 10)).apply { rightMargin = dp(a, 8) })
            val freshTrip = pastTrips[first.trip]?.fresh == true
            top.addView(Ui.text(a, 15f, Ui.TEXT, true, dayLabel.format(Date(first.time)) + " · " +
                (if (freshTrip) "🏞 민물" else Tide.mulName(first.time).substringBefore(" ("))), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(Ui.text(a, 16f, Ui.SUB, true, "›"))
            card.addView(top)
            card.addView(Ui.text(a, 14f, Ui.TEXT, false, (if (first.who.isNotBlank()) first.who + "  " else "") + speciesLine(g)),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(a, 4) })
            // v6.9 (256): 잡은 시각 · 이 출조만 보기 · 상세
            val times = g.sortedBy { it.time }.groupBy { it.species }.entries.joinToString(" · ") { e ->
                e.key + " " + e.value.take(6).joinToString(" · ") { hmFmt.format(Date(it.time)) } + if (e.value.size > 6) " 외 ${e.value.size - 6}" else ""
            }
            card.addView(Ui.text(a, 12.5f, Ui.SUB, false, "⏱ $times"),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(a, 3) })
            val pt = pastTrips[first.trip]
            if (pt != null) {
                card.background = Ui.bg(a, Ui.CARD, 12, pt.color)
                val btns = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
                if (onFocus != null) btns.addView(Ui.text(a, 13f, Color.parseColor("#06301C"), true, "🗺 이 출조만 보기").apply {
                    gravity = Gravity.CENTER; setPadding(dp(a, 12), dp(a, 7), dp(a, 12), dp(a, 7))
                    background = Ui.bg(a, Color.parseColor("#5FD49A"), 10)
                    setOnClickListener { dlg.dismiss(); onFocus(pt.key) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(a, 6) })
                btns.addView(Ui.text(a, 13f, Ui.TEXT, true, "상세 ›").apply {
                    gravity = Gravity.CENTER; setPadding(dp(a, 12), dp(a, 7), dp(a, 12), dp(a, 7))
                    background = Ui.bg(a, Color.TRANSPARENT, 10, Ui.LINE)
                    setOnClickListener { dlg.dismiss(); pt.open(a) }
                })
                card.addView(btns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(a, 8) })
            }
            col.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(a, 8) })
        }
        dlg.show()
    }

    /** 하루 상세: 어종별 마릿수, 시간대, 물때·들물/날물·만조·간조 전후 */
    private fun showDay(a: Activity, g: List<MapDot>, fresh: Boolean = false) {
        val list = g.sortedBy { it.time }
        val first = list.first()
        val title = (if (first.who.isNotBlank()) first.who + " · " else "") + dayLabel.format(Date(first.time))
        val tv = Ui.text(a, 14f, Ui.TEXT, false, "물때 계산 중…").apply {
            setPadding(dp(a, 20), dp(a, 8), dp(a, 20), dp(a, 8)); setLineSpacing(0f, 1.35f); setTextIsSelectable(true)
        }
        AlertDialog.Builder(a, R.style.FlAlert)
            .setTitle(title)
            .setView(android.widget.ScrollView(a).apply { addView(tv) })
            .setPositiveButton("닫기", null)
            .show()
        var dayStrength: Tide.Strength? = null   // v6.17 (263): 잡은 때 물살용
        fun render(day: Tide.Day?) {
            val sb = StringBuilder()
            sb.append("물때: ").append(Tide.mulName(first.time)).append("\n")
            sb.append("잡은 시간: ").append(hmFmt.format(Date(first.time))).append(" ~ ").append(hmFmt.format(Date(list.last().time))).append("\n\n")
            sb.append("어종별\n")
            list.groupingBy { it.species }.eachCount().entries.sortedByDescending { it.value }
                .forEach { sb.append("  ").append(it.key).append(" ").append(it.value).append("마리\n") }
            val phases = list.map { if (day != null) Tide.phaseAt(day, it.time) else null }
            if (day != null) {
                val up = phases.count { it?.first == true }; val down = phases.count { it?.first == false }
                sb.append("\n물 흐름\n  들물 ").append(up).append("마리 · 날물 ").append(down).append("마리\n")
                val near = phases.mapNotNull { it?.second?.takeIf { s -> s.isNotBlank() } }.groupingBy { it }.eachCount()
                    .entries.sortedByDescending { it.value }.take(6)
                if (near.isNotEmpty()) {
                    sb.append("\n만조·간조 기준\n")
                    near.forEach { sb.append("  ").append(it.key).append(" ").append(it.value).append("마리\n") }
                }
                val ex = Tide.extremes(day)
                if (ex.isNotEmpty()) sb.append("\n그날 ").append(ex.joinToString(" · ") {
                    (if (it.high) "만조 " else "간조 ") + hmFmt.format(Date(it.time)) }).append("\n")
            } else {
                sb.append("\n(물때 정보를 못 받아서 들물·날물은 계산하지 못했어요)\n")
            }
            sb.append("\n시각별\n")
            list.forEachIndexed { i, d ->
                sb.append("  ").append(hmFmt.format(Date(d.time))).append("  ").append(d.species)
                phases[i]?.let { p ->
                    p.first?.let { sb.append(" · ").append(if (it) "들물" else "날물") }
                    if (p.second.isNotBlank()) sb.append(" · ").append(p.second)
                }
                BiteMemo.flowAt(day, dayStrength, d.time)?.let { f -> sb.append(" · 💧").append(f).append("%") }
                sb.append("\n")
            }
            if (dayStrength != null) sb.append("\n💧 = 잡은 때 물살 (그날 물세기 ").append(dayStrength!!.pct).append("% 기준 · 가장 빠를 때 잡으면 그날 물세기와 같아요)")
            tv.text = sb.toString().trimEnd()
        }
        // v6.9 (254): 민물이면 물때 대신 해 기준
        if (fresh) {
            val sb = StringBuilder()
            sb.append("🏞 민물 · ").append(SunPhase.riseSet(first.time, first.point.latitude, first.point.longitude)).append("\n")
            sb.append("잡은 시간: ").append(hmFmt.format(Date(first.time))).append(" ~ ").append(hmFmt.format(Date(list.last().time))).append("\n\n")
            sb.append("어종별\n")
            list.groupingBy { it.species }.eachCount().entries.sortedByDescending { it.value }
                .forEach { sb.append("  ").append(it.key).append(" ").append(it.value).append("마리\n") }
            sb.append("\n해 기준 (골든타임 = 일출 · 일몰 전후 1시간)\n")
            sb.append(SunPhase.summary(list.map { it.time }, first.point.latitude, first.point.longitude)).append("\n")
            sb.append("\n시각별\n")
            list.forEach { d -> sb.append("  ").append(hmFmt.format(Date(d.time))).append("  ").append(d.species)
                .append(" · ").append(SunPhase.of(d.time, d.point.latitude, d.point.longitude)).append("\n") }
            tv.text = sb.toString().trimEnd()
            return
        }
        Tide.fetch(a, first.point.latitude, first.point.longitude, first.time) { d, _ ->
            render(d)
            if (d != null) Tide.strength(a, d) { st -> dayStrength = st; render(d) }
        }
    }

    private fun showClusterOld(a: Activity, items: List<MapDot>) {
        val counts = items.groupingBy { it.kind }.eachCount().entries.sortedByDescending { it.value }
        val title = if (items.size == 1) items[0].title
        else "${items.size}마리 · " + counts.joinToString(" · ") { "${it.key} ${it.value}" }
        val b = AlertDialog.Builder(a, R.style.FlAlert).setTitle(title)
        if (items.size > 1) b.setItems(items.map { it.title }.sorted().toTypedArray(), null)
        b.setPositiveButton("닫기", null).show()
    }

    /** 폰이 아는 가장 최근 내 위치 (앱 위치 사용·권한 있을 때만) */
    @android.annotation.SuppressLint("MissingPermission")
    private fun knownLoc(c: Context): GeoPoint? {
        val l0 = Tracker.last
        if (l0 != null) return GeoPoint(l0.latitude, l0.longitude)
        if (!Tracker.hasPermission(c)) return null
        return try {
            val lm = c.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { p: String -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
                .maxByOrNull { it.time }?.let { GeoPoint(it.latitude, it.longitude) }
        } catch (_: Exception) { null }
    }

    /** v6.73: 지도 크기가 0이면 첫 배치 뒤에 실행 */
    fun whenSized(map: MapView, f: () -> Unit) {
        if (map.width > 0 && map.height > 0) map.post(f)
        else map.addOnFirstLayoutListener { _, _, _, _, _ -> map.post(f) }
    }

    private fun fit(map: MapView, all: List<GeoPoint>) {
        if (map.width == 0 || map.height == 0) { whenSized(map) { fit(map, all) }; return }
        when {
            // 위치 기록이 없으면 한국 전체 말고 내 현재 위치로 (모르면 한국 전체)
            all.isEmpty() -> {
                val me = knownLoc(map.context)
                if (me != null) { map.controller.setZoom(14.0); map.controller.setCenter(me) }
                else { map.controller.setZoom(7.0); map.controller.setCenter(GeoPoint(36.3, 127.8)) }
            }
            all.size == 1 -> { map.controller.setZoom(16.0); map.controller.setCenter(all[0]) }
            else -> {
                val box = BoundingBox.fromGeoPoints(all)
                if (box.latitudeSpan < 0.002 && box.longitudeSpan < 0.002) {
                    map.controller.setZoom(17.0); map.controller.setCenter(box.centerWithDateLine)
                } else {
                    map.zoomToBoundingBox(box.increaseByScale(1.3f), false)
                }
            }
        }
    }

    /** "7분 전" · "1시간 5분 전" · "40초 전" */
    fun agoText(sec: Long): String = when {
        sec < 60 -> "${sec}초 전"
        sec < 3600 -> "${sec / 60}분 전"
        else -> "${sec / 3600}시간" + (if ((sec % 3600) / 60 > 0) " ${(sec % 3600) / 60}분" else "") + " 전"
    }

    /** 이동 거리(m): 10m 안쪽 흔들림은 빼고 경로를 따라 더함 / 출발지에서 마지막 위치까지 직선거리(m) */
    fun distances(pts: List<GeoPoint>): Pair<Double, Double> {
        if (pts.size < 2) return 0.0 to 0.0
        var total = 0.0
        var anchor = pts[0]
        for (i in 1 until pts.size) {
            val d = anchor.distanceToAsDouble(pts[i])
            if (d >= 10.0) { total += d; anchor = pts[i] }
        }
        return total to pts[0].distanceToAsDouble(pts.last())
    }

    fun distanceText(pts: List<GeoPoint>): String {
        if (pts.size < 2) return "이동 경로가 없어요 (올린 사람이 공개하지 않았거나 그때 위치 기록이 꺼져 있었어요)"
        val (total, straight) = distances(pts)
        return String.format(Locale.KOREA, "이동 %.1fkm (%.1f해리) · 출발지에서 %.1fkm",
            total / 1000, total / 1852, straight / 1000)
    }

    private fun directionName(deg: Float): String {
        val names = arrayOf("북", "북동", "동", "남동", "남", "남서", "서", "북서")
        return names[(((deg % 360 + 360) % 360 + 22.5f) / 45f).toInt() % 8]
    }

    /**
     * 큰 지도
     *  live      : 지금 출조 중인 기록이면 실시간 배 위치·경로 갱신
     *  excludeId : 지난 기록 겹치기에서 뺄 출조 (지금 보는 출조)
     */
    fun show(a: Activity, title: String, layers: List<MapLayer>, legend: String,
             live: Store.Trip? = null, excludeId: Long? = null) {
        configure(a)
        val dialog = Dialog(a, R.style.FlFull)
        val handler = Handler(Looper.getMainLooper())

        val map = MapView(a).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)   // v5.2 (237): 앱 ＋/－ 버튼을 따로 씀
            setTileSource(if (Store.satellite) satelliteSource else TileSourceFactory.MAPNIK)
            isTilesScaledToDpi = true
            isVerticalMapRepetitionEnabled = false; minZoomLevel = 4.0   // v6.73: 세계가 위아래로 겹쳐 보이지 않게
        }

        // 🦀 해루질 참고 구역 (맨 아래 · ☑ 일 때만)
        val zoneOv = Zones.ZoneOverlay(a) { Zones.on(a) }
        zoneOv.onTapZones = { Zones.showInfo(a, it, map) }
        map.overlays.add(zoneOv)
        // 지난 기록 (맨 아래, 반투명) · v6.9 (256): 출조마다 얇은 경로 + 점 누르면 '이 출조만 보기'
        var focusFn: (String?) -> Unit = {}
        val pastRoutes = PastRoutesOverlay(a).apply { isEnabled = false }
        map.overlays.add(pastRoutes)
        var pastRef: DotsOverlay? = null
        val past = DotsOverlay(a, emptyList(), true) { items ->
            // v6.58 (336): 고른 어종이 있으면 창에도 그 어종만
            val o = pastRef?.only
            val f = if (o == null) items else items.filter { it.species.ifBlank { it.kind } in o }
            showCluster(a, f.ifEmpty { items }) { k -> focusFn(k) }
        }
        pastRef = past
        past.isEnabled = false
        map.overlays.add(past)

        val all = mutableListOf<GeoPoint>()
        val dotList = mutableListOf<MapDot>()
        layers.forEach { layer ->
            if (layer.track.size >= 2) {
                // 짙은 테두리 선을 먼저 깔고 그 위에 색 선 (지도·위성 어디서나 잘 보이게)
                map.overlays.add(Polyline(map).apply {
                    setPoints(layer.track); outlinePaint.color = ROUTE_EDGE
                    outlinePaint.strokeWidth = dp(a, 7).toFloat(); outlinePaint.isAntiAlias = true
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND; outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                })
                val line = Polyline(map)
                line.setPoints(layer.track)
                line.outlinePaint.color = layer.color
                line.outlinePaint.strokeWidth = dp(a, 4).toFloat()
                line.outlinePaint.isAntiAlias = true
                line.outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND; line.outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                map.overlays.add(line)
            }
            all.addAll(layer.track)
            all.addAll(layer.catches.map { it.point })
            dotList.addAll(toDots(layer.catches))
        }

        // 실시간
        val liveEdge = Polyline(map).apply {
            outlinePaint.color = ROUTE_EDGE
            outlinePaint.strokeWidth = dp(a, 7).toFloat()
            outlinePaint.isAntiAlias = true
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND; outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
        }
        val liveLine = Polyline(map).apply {
            outlinePaint.color = ROUTE
            outlinePaint.strokeWidth = dp(a, 4).toFloat()
            outlinePaint.isAntiAlias = true
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND; outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
        }
        val boat = BoatOverlay(a)
        if (live != null) {
            map.overlays.add(liveEdge)
            map.overlays.add(liveLine)
            val l = layerOf(live)
            liveLine.setPoints(l.track); liveEdge.setPoints(l.track)
            dotList.addAll(toDots(l.catches))
            all.addAll(l.track); all.addAll(l.catches.map { it.point })
        }
        // 출발 지점 (초록)
        val startPt = (live?.let { layerOf(it).track.firstOrNull() }) ?: layers.firstOrNull()?.track?.firstOrNull()
        if (startPt != null) map.overlays.add(DotsOverlay(a, listOf(MapDot(startPt, Color.parseColor("#5FD39A"), "출발 지점", "출발")), false, null))
        // v3.8: 조과 점 = 어종 그림 + 마릿수 (숫자 동그라미 대신)
        val dots = DotsOverlay(a, dotList, false) { showCluster(a, it) }.apply { icons = true; mergeDp = 34f }
        map.overlays.add(dots)
        // 같이 출조 중인 그룹원 위치 (실시간)
        val matesOv = MatesOverlay(a) { m ->
            AlertDialog.Builder(a, R.style.FlAlert)
                .setTitle(m.name + if (m.ended) " (출조 종료)" else "")
                .setMessage("${m.names.getOrElse(0) { "1번" }} ${m.c1} · ${m.names.getOrElse(1) { "2번" }} ${m.c2}\n" +
                    (if (m.meters > 0) String.format(Locale.KOREA, "나와 약 %.0fm\n", m.meters) else "") +
                    "마지막 갱신 " + SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(m.time)))
                .setPositiveButton("확인", null).show()
        }
        val matesListener: () -> Unit = { matesOv.mates = Live.mates + Live.endedMates; map.invalidate() }
        if (live != null) {
            matesOv.mates = Live.mates + Live.endedMates
            map.overlays.add(matesOv)
            Live.addListener(matesListener)
            Live.watch()
        }
        if (live != null) map.overlays.add(boat)
        // 출조 중이 아니면: 지도를 여는 순간부터 내 위치를 계속 보여줌
        val liveMe = if (live == null) LiveMe.attach(a, map, emptyList(), fitWith = all.toList()) else null

        // ---- 화면 구성 ----
        val bg = Color.parseColor("#0B1A26")
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        // v6.73 (370): 제목 줄 오른쪽에 닫기 (버튼 줄은 지도 왼쪽 세로 줄로)
        val headRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(a, 16), dp(a, 8), dp(a, 12), dp(a, 6)) }
        headRow.addView(TextView(a).apply {
            text = title
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(headRow)

        fun pill(label: String): TextView = TextView(a).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minHeight = dp(a, 40)
            setPadding(dp(a, 14), 0, dp(a, 14), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(a, 20).toFloat()
                setStroke(dp(a, 1), Color.parseColor("#3A5A70"))
            }
        }
        fun setOn(v: TextView, on: Boolean) {
            (v.background as GradientDrawable).setColor(if (on) Color.parseColor("#2B4A5E") else Color.argb(235, 15, 29, 41))   // v6.73: 지도 위에 떠 있어 끔도 짙은 바탕
        }
        val bar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false
            setPadding(dp(a, 12), dp(a, 4), dp(a, 12), dp(a, 8))
        }
        fun addPill(v: TextView) = bar.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(a, 8) })
        // v3.9.1 (159): 맨 왼쪽 🔍 장소 검색
        val searchPill = MapSearch.pill(a, map)
        addPill(searchPill)

        val legendView = TextView(a).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#9FB4C2"))
            setPadding(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 12))
        }
        val liveInfo = TextView(a).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(a, 16), dp(a, 8), dp(a, 16), 0)
        }
        var pastNote = ""
        var zoneNote = ""
        var pastSum: TextView? = null   // v6.60 (338): 지난 기록 합계는 지난 기록 상자 안에
        fun updateLegend() {
            pastSum?.let { it.text = pastNote.trim(); it.visibility = if (pastNote.isBlank()) android.view.View.GONE else android.view.View.VISIBLE }
            legendView.text = legend + (if (pastSum == null) pastNote else "") + (if (zoneNote.isNotBlank()) "\n$zoneNote" else "") + "\n지도 © OpenStreetMap · 위성 © Esri"
        }
        zoneOv.hint = { zoneNote = it; updateLegend() }
        updateLegend()

        var follow = live != null
        // v6.43 (302): '내 위치 따라가기' 위 버튼 없앰 → 지도 ＋ − 아래 동그란 조준 버튼 (켜짐 = 파랑 채움 · 지도를 끌면 꺼짐)
        var followView: TextView? = null
        fun paintFollow() {
            val v = followView ?: return
            v.foreground?.setTint(if (follow) Color.WHITE else Color.parseColor("#6FC3FF"))
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (follow) Color.parseColor("#2F80ED") else Color.argb(225, 11, 26, 38))
                setStroke(dp(a, 1), if (follow) Color.parseColor("#9CC8FF") else Color.parseColor("#2C4656"))
            }
            v.contentDescription = if (follow) "내 위치 따라가기 켜짐" else "내 위치 따라가기"
        }

        if (liveMe != null) {
            val meBtn = pill("내 위치로")
            meBtn.setOnClickListener { liveMe.goToMe(meBtn) }
            addPill(meBtn)
        }

        // 내 위치 기록 확인 (테스트용 · 출조 아닐 때 · 앱이 켜져 있는 동안만)
        var testListener: (() -> Unit)? = null
        // v6.68 (360): '📍 내 기록'(움직인 길 시험)은 관리자만
        if (live == null && Cloud.isAdmin) {
            val testLine = Polyline(map).apply {
                outlinePaint.color = Color.parseColor("#2F7BF6"); outlinePaint.strokeWidth = dp(a, 4).toFloat()
                outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(a, 8).toFloat(), dp(a, 6).toFloat()), 0f)
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            }
            map.overlays.add(testLine)
            val testBtn = pill("📍 내 기록")
            val clearBtn = pill("🗑 지우기")
            fun paintTest() {
                testLine.setPoints(TestTrack.points.map { GeoPoint(it.lat, it.lon) })
                if (TestTrack.on) {
                    val min = (System.currentTimeMillis() - TestTrack.startedAt) / 60_000L
                    val m = TestTrack.meters()
                    testBtn.text = "■ 기록 끄기 · ${min}분 · " + (if (m >= 1000) String.format(Locale.KOREA, "%.1fkm", m / 1000) else "${m.toInt()}m")
                    (testBtn.background as GradientDrawable).setColor(Color.parseColor("#1E5E7A"))
                } else {
                    testBtn.text = "📍 내 기록"
                    (testBtn.background as GradientDrawable).setColor(Color.TRANSPARENT)
                }
                clearBtn.visibility = if (TestTrack.points.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                map.invalidate()
            }
            testBtn.setOnClickListener {
                if (TestTrack.on) TestTrack.stop()
                else TestTrack.start(a)?.let { msg -> android.widget.Toast.makeText(a, msg, android.widget.Toast.LENGTH_LONG).show() }
                    ?: android.widget.Toast.makeText(a, "지금부터 움직인 길을 그려요 (앱을 닫으면 자동으로 꺼지고 지워져요)", android.widget.Toast.LENGTH_LONG).show()
                paintTest()
            }
            clearBtn.setOnClickListener { TestTrack.clear(); paintTest() }
            addPill(testBtn); addPill(clearBtn)
            val tl: () -> Unit = { paintTest() }
            testListener = tl
            TestTrack.addListener(tl)
            paintTest()
        }

        val pastBtn = pill("📜 지난 기록")
        var pastLoaded = false
        // v6.60 (338): 지난 기록 상자 — 버튼에 붙은 하늘 테두리 상자 하나에 제목 · 보기 바꾸기 · × · 출조 색 · 어종 · 합계
        val PANEL_BG = Color.parseColor("#16334A"); val PANEL_EDGE = Color.parseColor("#7FD6FF")
        val pastPanel = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(a, 10), dp(a, 8), dp(a, 10), dp(a, 8))
            background = GradientDrawable().apply { setColor(PANEL_BG); cornerRadius = dp(a, 16).toFloat(); setStroke(dp(a, 2), PANEL_EDGE) }
            visibility = android.view.View.GONE
        }
        val pastHead = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL }
        val pastBar = TextView(a).apply { textSize = 13f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; maxLines = 2 }
        pastHead.addView(pastBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val pastModeBtn = TextView(a).apply {
            text = "보기 바꾸기 ▾"; textSize = 12f; setTextColor(PANEL_EDGE); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(a, 10), dp(a, 4), dp(a, 10), dp(a, 4))
            background = GradientDrawable().apply { cornerRadius = dp(a, 14).toFloat(); setStroke(dp(a, 1), PANEL_EDGE) }
        }
        pastHead.addView(pastModeBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(a, 6) })
        val pastClose = TextView(a).apply {
            text = "×"; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(a, 14).toFloat(); setStroke(dp(a, 1), Color.parseColor("#3A5A70")) }
        }
        pastHead.addView(pastClose, LinearLayout.LayoutParams(dp(a, 30), dp(a, 28)).apply { leftMargin = dp(a, 6) })
        pastPanel.addView(pastHead)
        val pastSumView = TextView(a).apply { textSize = 11f; setTextColor(Color.parseColor("#9FB4C2")); setPadding(dp(a, 2), dp(a, 4), dp(a, 2), 0); visibility = android.view.View.GONE }
        pastSum = pastSumView
        fun paintPastBtn(on: Boolean) {
            pastBtn.text = if (on) "📜\n지난 기록 ▾" else "📜\n지난 기록"
            pastBtn.setTextColor(if (on) PANEL_EDGE else Color.WHITE)
            (pastBtn.background as GradientDrawable).apply { setColor(if (on) PANEL_BG else Color.TRANSPARENT); setStroke(dp(a, if (on) 2 else 1), if (on) PANEL_EDGE else Color.parseColor("#3A5A70")) }
        }
        past.icons = true
        // v6.9 (256): 출조 색 범례 줄 (누르면 그 출조만 보기)
        val chipRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        val chipWrap = Ui.hscroll(a, chipRow).apply { visibility = android.view.View.GONE }
        var pastList: List<PastTrip> = emptyList()
        fun paintChips() {
            chipRow.removeAllViews()
            val colored = pastList.filter { it.color != GREY_TRIP }
            colored.forEach { p ->
                chipRow.addView(TextView(a).apply {
                    text = android.text.SpannableString("● " + p.label).apply {
                        setSpan(android.text.style.ForegroundColorSpan(p.color), 0, 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                    setPadding(dp(a, 10), dp(a, 5), dp(a, 10), dp(a, 5))
                    background = GradientDrawable().apply { setColor(Color.parseColor("#12293A")); cornerRadius = dp(a, 14).toFloat(); setStroke(dp(a, 1), Color.parseColor("#3A5A70")) }
                    setOnClickListener { focusFn(p.key) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(a, 6) })
            }
            val rest = pastList.size - colored.size
            if (rest > 0) chipRow.addView(TextView(a).apply {
                text = android.text.SpannableString("● 그 전 $rest").apply {
                    setSpan(android.text.style.ForegroundColorSpan(GREY_TRIP), 0, 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                textSize = 12f; setTextColor(Color.parseColor("#9FB4C2"))
                setPadding(dp(a, 10), dp(a, 5), dp(a, 10), dp(a, 5))
            })
            chipWrap.visibility = if (past.isEnabled && pastList.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }
        // v6.58 (336): 🐟 지난 기록 어종 골라 보기 (기본 = 전체 · 고른 건 기억 · 안 고른 어종은 흐리게/숨김)
        val spPrefs = a.getSharedPreferences("fishlog", android.content.Context.MODE_PRIVATE)
        var spSel: Set<String> = spPrefs.getStringSet("past_sp", emptySet())?.toSet() ?: emptySet()
        var spHide = spPrefs.getBoolean("past_sp_hide", false)
        val spRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL }
        val spWrap = Ui.hscroll(a, spRow).apply { visibility = android.view.View.GONE }
        var spNote = ""
        var paintSpecies: () -> Unit = {}
        fun spOf(d: MapDot) = d.species.ifBlank { d.kind }
        fun applySpecies() {
            spPrefs.edit().putStringSet("past_sp", spSel).putBoolean("past_sp_hide", spHide).apply()
            val counts = past.dots.groupingBy { spOf(it) }.eachCount()
            val sel = spSel.filter { (counts[it] ?: 0) > 0 }.toSet()
            past.only = if (sel.isEmpty()) null else sel
            past.hideOthers = spHide
            val oldSp = spNote
            spNote = if (sel.isEmpty()) "" else {
                val ds = past.dots.filter { spOf(it) in sel }
                "\n🐟 " + sel.joinToString(" · ") + " ${ds.size}마리 (내 ${ds.count { it.src == 0 }} · 그 밖 ${ds.count { it.src != 0 }}) · 안 고른 어종은 " + (if (spHide) "숨김" else "흐리게")
            }
            if (oldSp.isNotEmpty() && pastNote.endsWith(oldSp)) pastNote = pastNote.removeSuffix(oldSp)
            if (pastNote.isNotEmpty() && !pastNote.contains("불러오는 중")) { pastNote += spNote; updateLegend() }
            val base = pastBar.text.toString().substringBefore("  ·  🐟")
            pastBar.text = base + if (sel.isEmpty()) "" else "  ·  🐟 " + sel.joinToString(" · ") + "만"
            paintSpecies()
            map.invalidate()
        }
        fun openSpeciesSheet() {
            val counts = past.dots.groupingBy { spOf(it) }.eachCount().entries.sortedByDescending { it.value }
            val pick = spSel.toMutableSet()
            var hide = spHide
            val sh = Sheet(a, 0.62f)
            sh.head.addView(Ui.text(a, 17f, Ui.TEXT, true, "🐟 보고 싶은 어종"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            sh.body.addView(Ui.text(a, 12f, Ui.SUB, false, "여러 개 고를 수 있어요 · 아무것도 안 고르면 전체 · 고른 건 다음에도 기억해요"))
            val listBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            sh.body.addView(listBox)
            val hideRow = TextView(a).apply { textSize = 13f; setTextColor(Ui.SUB); setPadding(dp(a, 4), dp(a, 12), dp(a, 4), dp(a, 4)) }
            sh.body.addView(hideRow)
            val go = Ui.button(a, "", true) {}
            fun paint() {
                listBox.removeAllViews()
                fun line(label: String, n: Int, on: Boolean, click: () -> Unit) = LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(a, 4), dp(a, 11), dp(a, 4), dp(a, 11))
                    addView(TextView(a).apply {
                        text = if (on) "✓" else ""; gravity = Gravity.CENTER; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.parseColor("#1B0E02"))
                        background = if (on) Ui.bg(a, Ui.ORANGE, 6) else Ui.bg(a, Color.TRANSPARENT, 6, Color.parseColor("#9FB4C2"))
                    }, LinearLayout.LayoutParams(dp(a, 24), dp(a, 24)))
                    addView(Ui.text(a, 15f, Ui.TEXT, on, label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(a, 12) })
                    addView(Ui.text(a, 13f, Ui.SUB, false, n.toString()))
                    setOnClickListener { click() }
                }
                listBox.addView(line("전체 (모두 보기 · 기본)", past.dots.size, pick.isEmpty()) { pick.clear(); paint() })
                counts.forEach { (sp, n) -> listBox.addView(line(sp, n, sp in pick) { if (!pick.add(sp)) pick.remove(sp); paint() }) }
                hideRow.text = "안 고른 어종:  " + (if (!hide) "● 흐리게   ○ 숨기기" else "○ 흐리게   ● 숨기기") + "   (눌러서 바꾸기)"
                go.text = if (pick.isEmpty()) "전체 보기" else pick.sortedByDescending { p -> counts.firstOrNull { it.key == p }?.value ?: 0 }.joinToString(" · ") + "만 보기"
            }
            hideRow.setOnClickListener { hide = !hide; paint() }
            go.setOnClickListener { spSel = pick.toSet(); spHide = hide; applySpecies(); sh.close() }
            sh.foot.visibility = android.view.View.VISIBLE
            sh.foot.addView(go, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            paint()
            sh.show()
        }
        paintSpecies = {
            spRow.removeAllViews()
            val counts = past.dots.groupingBy { spOf(it) }.eachCount().entries.sortedByDescending { it.value }
            spWrap.visibility = if (past.isEnabled && counts.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            if (counts.isNotEmpty()) {
                spRow.addView(TextView(a).apply { text = "🐟 어종"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#9FB4C2")) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(a, 6) })
                fun chip(label: String, on: Boolean, allChip: Boolean, click: () -> Unit) = TextView(a).apply {
                    text = label; textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                    setPadding(dp(a, 11), 0, dp(a, 11), 0); minHeight = dp(a, Ui.PILL_S); maxLines = 1
                    setTextColor(if (on) Color.parseColor(if (allChip) "#06303A" else "#1B0E02") else Color.parseColor("#C9D3DE"))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(a, 14).toFloat()
                        if (on) setColor(if (allChip) Color.parseColor("#7FD6FF") else Ui.ORANGE) else { setColor(Color.parseColor("#12293A")); setStroke(dp(a, 1), Color.parseColor("#3A5A70")) }
                    }
                    setOnClickListener { click() }
                }
                val sel = spSel.filter { s0 -> counts.any { it.key == s0 } }.toSet()
                spRow.addView(chip("전체", sel.isEmpty(), true) { spSel = emptySet(); applySpecies() },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(a, 6) })
                counts.take(6).forEach { (sp, n) ->
                    spRow.addView(chip("$sp $n", sp in sel, false) { spSel = if (sp in spSel) spSel - sp else spSel + sp; applySpecies() },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(a, 6) })
                }
                spRow.addView(chip(if (counts.size > 6) "⋯ 더보기 (${counts.size})" else "⋯ 고르기", false, false) { openSpeciesSheet() })
            }
        }
        // v6.60 (338): 기간 — 연도(0 = 전체) · 몇 월 ~ 몇 월(0 = 전체 · 11~2 처럼 해 넘김 가능) · 폰에서 거름 · 기억
        var pYear = spPrefs.getInt("past_year", 0)
        var pM1 = spPrefs.getInt("past_m1", 0); var pM2 = spPrefs.getInt("past_m2", 0)
        var rawDots: List<MapDot> = emptyList(); var rawTrips: List<PastTrip> = emptyList()
        val calP = java.util.Calendar.getInstance()
        fun inPeriod(t: Long): Boolean {
            if (pYear == 0 && pM1 == 0) return true
            calP.timeInMillis = t
            val y = calP.get(java.util.Calendar.YEAR); val m = calP.get(java.util.Calendar.MONTH) + 1
            if (pM1 == 0) return y == pYear
            val wrap = pM1 > pM2
            val inM = if (!wrap) m in pM1..pM2 else (m >= pM1 || m <= pM2)
            if (pYear == 0) return inM
            return if (!wrap) y == pYear && inM else (y == pYear && m >= pM1) || (y == pYear + 1 && m <= pM2)
        }
        fun periodLabel(y: Int = pYear, m1: Int = pM1, m2: Int = pM2): String {
            val mm = if (m1 == 0) "" else if (m1 == m2) "${m1}월" else "${m1}~${m2}월"
            return when {
                y == 0 && m1 == 0 -> "전체 기간"
                y == 0 -> "해마다 $mm"
                m1 == 0 -> "${y}년"
                m1 > m2 -> "${y}년 ${m1}월 ~ ${y + 1}년 ${m2}월"
                else -> "${y}년 $mm"
            }
        }
        fun whoLabel(mode: String): String = when {
            mode == "mine" -> "내 기록"
            mode == "allg" -> "내 기록 + 그룹 전부"
            mode.startsWith("public") -> "내 기록 + 게시판"
            mode.startsWith("g:") -> "내 기록 + " + (Cloud.groups.firstOrNull { "g:${it.id}" == mode }?.title ?: "그룹")
            else -> mode
        }
        fun paintPastTitle() {
            // v6.68 (361): 누구 기록인지 색 점 (내 것 주황 · 그룹 하늘 · 전체 보라)
            val m = pastModeNow()
            val sb = android.text.SpannableStringBuilder("📜 ")
            fun dot(col: Int) { val st = sb.length; sb.append("●"); sb.setSpan(android.text.style.ForegroundColorSpan(col), st, sb.length, 0) }
            dot(Ui.MINE)
            if (m == "allg" || m.startsWith("g:")) dot(Ui.GROUP)
            if (m.startsWith("public")) dot(Ui.ALL)
            sb.append(" ").append(whoLabel(m)).append(" · ").append(periodLabel())
            pastBar.text = sb
        }
        var lastMode = ""
        fun applyPeriod() {
            val dots = rawDots.filter { inPeriod(it.time) }
            val trips = rawTrips.filter { p -> inPeriod(p.start) }
            past.dots = dots
            pastList = trips
            pastRoutes.routes = trips.filter { it.track.size >= 2 }.map { PastRoutesOverlay.Route(it.key, it.color, it.track) }
            paintChips()
            val mine = dots.count { it.src == 0 }; val others = dots.size - mine
            pastNote = "\n" + periodLabel() + " · 내 조과 $mine · " + (if (lastMode.startsWith("public")) "게시판" else "친구") + " 조과 $others" +
                (if (dots.isEmpty() && rawDots.isNotEmpty()) " · 이 기간엔 기록이 없어요" else "") +
                " · 색 = 출조 · 숫자 = 마릿수 · 점이나 색을 누르면 '이 출조만 보기'"
            spNote = ""
            paintPastTitle()
            applySpecies()
            updateLegend()
            map.invalidate()
        }
        fun loadPastNow() {
            val mode = pastModeNow()
            lastMode = mode
            paintPastTitle()
            pastNote = "\n지난 기록 불러오는 중…"
            updateLegend()
            loadPast(excludeId, mode) { list, _, _, trips ->
                rawDots = list; rawTrips = trips
                applyPeriod()
            }
        }
        // v6.60 (338): 보기 바꾸기 = 밑에서 올라오는 창 (누구 기록 · 연도 · 몇 월 ~ 몇 월)
        fun openPastSheet() {
            val modes = mutableListOf("mine")
            Cloud.groups.forEach { modes.add("g:${it.id}") }
            if (Cloud.groups.size > 1) modes.add("allg")
            if (Cloud.user != null) modes.add("publicAll")
            var mode = pastModeNow().let { if (it == "public") "publicAll" else it }
            var y = pYear; var m1 = pM1; var m2 = pM2
            var picking = false
            val years = (rawDots.map { it.time } + rawTrips.map { it.start } + System.currentTimeMillis()).map { t -> calP.timeInMillis = t; calP.get(java.util.Calendar.YEAR) }.distinct().sortedDescending()
            val sh = Sheet(a, 0.8f)
            sh.head.addView(Ui.text(a, 17f, Ui.TEXT, true, "📜 지난 기록 보기"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            sh.body.addView(box)
            val go = Ui.button(a, "이 기간으로 보기", true) {}
            fun chipV(label: String, on: Boolean, onCol: Int, onInk: Int, click: () -> Unit) = TextView(a).apply {
                text = label; textSize = 13f; gravity = Gravity.CENTER; typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (on) onInk else Color.WHITE); setPadding(dp(a, 12), dp(a, 7), dp(a, 12), dp(a, 7))
                background = GradientDrawable().apply { cornerRadius = dp(a, 16).toFloat(); if (on) setColor(onCol) else setStroke(dp(a, 1), Color.parseColor("#3A5A70")) }
                setOnClickListener { click() }
            }
            fun flow(views: List<android.view.View>): android.view.View {
                val wrap = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                var row: LinearLayout? = null; var n = 0
                views.forEach { v ->
                    if (row == null || n == 3) { row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }; wrap.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(a, 6) }); n = 0 }
                    row!!.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(a, 6) }); n++
                }
                return wrap
            }
            fun sec(t: String) = Ui.text(a, 12.5f, Color.parseColor("#9FB4C2"), true, t).apply { setPadding(0, dp(a, 14), 0, 0) }
            val SKY = Color.parseColor("#7FD6FF"); val YEL = Color.parseColor("#F2C14E")
            fun paint() {
                box.removeAllViews()
                box.addView(sec("누구 기록"))
                box.addView(flow(modes.map { md -> chipV(whoLabel(md).replace("내 기록 + ", "+ ").let { if (md == "mine") "내 기록만" else it }, md == mode,
                    // v6.69 (364): 내 기록 주황 · 그룹 하늘 · 게시판 보라
                    if (md == "mine") Ui.MINE else if (md.startsWith("public")) Ui.ALL else Ui.GROUP, Color.WHITE) { mode = md; paint() } }))
                box.addView(sec("연도"))
                box.addView(flow(listOf(chipV("전체 연도", y == 0, YEL, Color.parseColor("#2A1E02")) { y = 0; paint() }) +
                    years.map { yy -> chipV("$yy", y == yy, YEL, Color.parseColor("#2A1E02")) { y = yy; paint() } }))
                box.addView(sec("몇 월 ~ 몇 월 (시작 → 끝 차례로 · 다시 누르면 새로)"))
                val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                for (r in 0 until 2) {
                    val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
                    for (c in 1..6) {
                        val m = r * 6 + c
                        val inR = m1 != 0 && (if (m1 <= m2) m in m1..m2 else (m >= m1 || m <= m2))
                        val end = m1 != 0 && (m == m1 || m == m2)
                        row.addView(TextView(a).apply {
                            text = "${m}월"; textSize = 13f; gravity = Gravity.CENTER
                            setTextColor(if (end) Color.parseColor("#1B0E02") else Color.WHITE); typeface = if (end) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                            setPadding(0, dp(a, 8), 0, dp(a, 8))
                            background = GradientDrawable().apply {
                                cornerRadius = dp(a, 10).toFloat()
                                when { end -> setColor(Ui.ORANGE); inR -> setColor(Color.parseColor("#1E4A63")); else -> setStroke(dp(a, 1), Color.parseColor("#3A5A70")) }
                            }
                            setOnClickListener {
                                if (!picking || m1 == 0) { m1 = m; m2 = m; picking = true } else { m2 = m; picking = false }
                                paint()
                            }
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(a, 5) })
                    }
                    grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(a, 5) })
                }
                box.addView(grid)
                val nowCal = java.util.Calendar.getInstance()
                box.addView(flow(listOf(
                    chipV("전체 달", m1 == 0, SKY, Color.parseColor("#06303A")) { m1 = 0; m2 = 0; picking = false; paint() },
                    chipV("이번 달", false, SKY, Color.parseColor("#06303A")) {
                        y = nowCal.get(java.util.Calendar.YEAR); m1 = nowCal.get(java.util.Calendar.MONTH) + 1; m2 = m1; picking = false; paint() },
                    chipV("지난 3개월", false, SKY, Color.parseColor("#06303A")) {
                        val c0 = java.util.Calendar.getInstance().apply { add(java.util.Calendar.MONTH, -2) }
                        y = c0.get(java.util.Calendar.YEAR); m1 = c0.get(java.util.Calendar.MONTH) + 1; m2 = nowCal.get(java.util.Calendar.MONTH) + 1; picking = false; paint() })))
                box.addView(Ui.text(a, 13.5f, Ui.TEXT, true, "📅 " + periodLabel(y, m1, m2) + " · " + whoLabel(mode)).apply {
                    setPadding(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10))
                    background = Ui.bg(a, Color.parseColor("#0F2233"), 12, Color.parseColor("#2C4256"))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(a, 14) })
                if (mode.startsWith("public")) box.addView(Ui.text(a, 11f, Color.parseColor("#9FB4C2"), false, "게시판 글은 최근 글부터 정해진 개수만 받아서, 아주 오래된 게시판 글은 빠질 수 있어요 (내 기록은 다 나와요)"),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(a, 6) })
            }
            go.setOnClickListener {
                pYear = y; pM1 = m1; pM2 = if (m1 == 0) 0 else m2
                spPrefs.edit().putInt("past_year", pYear).putInt("past_m1", pM1).putInt("past_m2", pM2).apply()
                val modeChanged = mode != pastModeNow()
                Store.pastMode = mode
                sh.close()
                if (modeChanged || rawDots.isEmpty()) loadPastNow() else { lastMode = mode; applyPeriod() }
            }
            sh.foot.visibility = android.view.View.VISIBLE
            sh.foot.addView(go, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            paint()
            sh.show()
        }
        pastBar.setOnClickListener { openPastSheet() }
        pastModeBtn.setOnClickListener { openPastSheet() }
        // v6.71: 지난 기록 3단 — 0 꺼짐 · 1 켜짐(상자 접힘 ▸) · 2 켜짐 + 상자 펼침
        //  누를 때마다 1 → 2 → 0 → 1 … · 메인 지도는 처음 1 · 그 뒤엔 마지막 상태를 기억 (prefs past_state)
        var pastState = 0
        fun setPast(stNew: Int, save: Boolean) {
            val wantOn = stNew != 0
            if (wantOn != past.isEnabled) {
                past.isEnabled = wantOn
                pastRoutes.isEnabled = wantOn
                if (!wantOn) focusFn(null)
                chipWrap.visibility = if (wantOn && pastList.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                paintSpecies()
                if (wantOn && !pastLoaded) { pastLoaded = true; loadPastNow() }
                else { pastNote = if (wantOn && pastLoaded) pastNote else ""; updateLegend() }
            }
            pastState = stNew
            paintPastBtn(wantOn)
            if (stNew == 1) pastBtn.text = "📜\n지난 기록 ▸"
            pastPanel.visibility = if (stNew == 2) android.view.View.VISIBLE else android.view.View.GONE
            if (save && layers.isEmpty()) spPrefs.edit().putInt("past_state", stNew).apply()
            map.invalidate()
        }
        pastBtn.setOnClickListener { setPast(when (pastState) { 1 -> 2; 2 -> 0; else -> 1 }, true) }
        addPill(pastBtn)
        // × = 바로 끄기
        pastClose.setOnClickListener { setPast(0, true) }
        // v6.37 (298): 🌋 지진 최근 4일 핀 (기상청) · 누르면 자세히
        val quakeOv = Quake.QuakeOverlay(a) { e ->
            val me = Tracker.last
            AlertDialog.Builder(a, R.style.FlAlert).setTitle("🌋 지진 정보")
                .setMessage(Quake.detail(e, me?.latitude, me?.longitude))
                .setPositiveButton("닫기", null).show()
        }.apply { isEnabled = false }
        map.overlays.add(quakeOv)
        val quakeBtn = pill("🌋 지진 ${Quake.DAYS}일")
        var quakeLoaded = false
        quakeBtn.setOnClickListener {
            quakeOv.isEnabled = !quakeOv.isEnabled
            setOn(quakeBtn, quakeOv.isEnabled)
            if (quakeOv.isEnabled && !quakeLoaded) {
                quakeBtn.text = "🌋 지진 받는 중…"
                Quake.load(a) { list, err ->
                    if (list != null) { quakeLoaded = err == null; quakeOv.list = list }
                    quakeBtn.text = when {
                        list == null -> "🌋 지진 ${Quake.DAYS}일 · 못 받음"
                        list.isEmpty() -> "🌋 지진 ${Quake.DAYS}일 · 없음"
                        else -> "🌋 지진 ${Quake.DAYS}일 · ${list.size}건"
                    }
                    if (err != null) Ui.toast(a, "지진 정보를 못 받았어요\n$err")
                    else if (list != null && list.isEmpty()) Ui.toast(a, "최근 ${Quake.DAYS}일 동안 우리나라 주변 지진이 없어요")
                    map.invalidate()
                }
            }
            map.invalidate()
        }
        addPill(quakeBtn)
        // v6.73 (369): ⚓ 항구 자동 표시 (앱 안 자료 · 켜고 끄기 기억)
        lateinit var pinOvRef: () -> Pins.PinOverlay
        lateinit var loadPinsRef: () -> Unit
        var portOv: Ports.PortOverlay? = null
        portOv = Ports.PortOverlay(a) { pt ->
            Ports.card(a, map, portOv!!, pt) { pp ->
                map.controller.animateTo(GeoPoint(pp.lat, pp.lon))
                map.postDelayed({ Pins.place(a, map, pinOvRef(), null) { loadPinsRef() } }, 400)
            }
        }.apply { isEnabled = Ports.on(a) }
        map.overlays.add(portOv)
        val portBtn = pill("⚓ 항구")
        setOn(portBtn, portOv.isEnabled)
        portBtn.setOnClickListener {
            val v = !portOv!!.isEnabled; portOv!!.isEnabled = v; Ports.setOn(a, v); setOn(portBtn, v); map.invalidate()
            if (v && map.zoomLevelDouble < 8.5) Ui.toast(a, "지도를 조금 확대하면 항구가 보여요")
        }
        addPill(portBtn)
        // v6.68 (360) · v6.72: 📌 포인트 — 누르면 지도 가운데에 핀 → 끌어서 옮기고 핀 위 [💾 저장] · 내 것 주황 · 그룹 하늘 · 전체 보라
        val pinBtn = pill("📌 포인트")
        val pinShowBtn = pill("👁 포인트 보기 ▾")
        lateinit var pinOv: Pins.PinOverlay
        fun loadPins() {
            Pins.load(a) { l ->
                pinOv.pins = l
                pinShowBtn.text = "👁 포인트 보기 " + (if (l.isNotEmpty()) "${l.size} " else "") + "▾"
                map.invalidate()
            }
        }
        pinOv = Pins.PinOverlay(a) { p -> Pins.card(a, map, pinOv, p, past.dots) { loadPins() } }
        pinOvRef = { pinOv }; loadPinsRef = { loadPins() }
        map.overlays.add(pinOv)
        pinBtn.setOnClickListener {
            if (pinOv.placing) { pinOv.onCancel?.invoke(); return@setOnClickListener }
            Pins.place(a, map, pinOv, null) { loadPins() }
        }
        pinShowBtn.setOnClickListener {
            val sh = Sheet(a, 0.5f)
            sh.head.addView(Ui.text(a, 17f, Ui.TEXT, true, "👁 지도에 보일 포인트"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val show = Pins.shown(a)
            fun row(scope: Int, label: String) {
                val cb = Ui.checkFit(android.widget.CheckBox(a)).apply {
                    text = label; textSize = 15f; setTextColor(Pins.color(scope)); isChecked = scope in show
                    buttonTintList = android.content.res.ColorStateList.valueOf(Pins.color(scope))
                    setOnCheckedChangeListener { _, on -> if (on) show.add(scope) else show.remove(scope); Pins.setShown(a, show); loadPins() }
                }
                sh.body.addView(cb, Ui.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 4))
            }
            row(Pins.MINE, "★ 내 포인트 (나만)"); row(Pins.GROUP, "★ 그룹 포인트"); row(Pins.ALL, "★ 전체 게시판 포인트")
            sh.body.addView(Ui.text(a, 13f, Ui.SUB, false, "새 포인트는 [📌 포인트]를 누르면 지도 가운데에 핀이 생겨요 → 핀을 끌어서 옮기고 → 핀 위 [💾 저장]"),
                Ui.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 12))
            sh.show()
        }
        addPill(pinBtn); addPill(pinShowBtn)
        if (Cloud.user != null) loadPins()
        // v6.54 (330): 🌬 바람 지도 (지금 보던 자리 가운데로)
        val windPill = pill("🌬 바람")
        addPill(windPill.apply {
            setOnClickListener {
                val c = map.mapCenter
                WindMap.show(a, c.latitude, c.longitude, "")
            }
        })
        // v6.38 (299): 메인 지도(내 위치)는 🌋 지진 · 지난 기록 처음부터 켜짐 (폰에 저장된 것 먼저 · 새 글만 서버)
        if (layers.isEmpty()) { quakeBtn.performClick(); setPast(spPrefs.getInt("past_state", 1).coerceIn(0, 2), false) }
        val zBar = Zones.bar(a, map) { updateLegend() }
        addPill(zBar.pill)

        val toggle = pill(if (Store.satellite) "지도로 보기" else "위성으로 보기")
        toggle.setOnClickListener {
            Store.satellite = !Store.satellite
            satWarn(a)
            map.setTileSource(if (Store.satellite) satelliteSource else TileSourceFactory.MAPNIK)
            toggle.text = if (Store.satellite) "지도로 보기" else "위성으로 보기"
        }
        addPill(toggle)
        val close = pill("닫기")
        close.setOnClickListener { dialog.dismiss() }
        // v6.73 (370): 닫기는 제목 줄 오른쪽
        close.minHeight = dp(a, 36); headRow.addView(close)
        // v6.73 (370) A안: 지도 왼쪽 세로 줄 — 🔍 장소 · 📜 지난 기록 · 📌 포인트 · 🗂 지도 표시(묶음) · 🌬 바람
        val rail = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val flyout = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(a, 10), dp(a, 8), dp(a, 10), dp(a, 10)); visibility = android.view.View.GONE
            background = GradientDrawable().apply { setColor(Color.argb(246, 19, 43, 61)); cornerRadius = dp(a, 16).toFloat(); setStroke(dp(a, 2), Color.parseColor("#7FD6FF")) }
            isClickable = true
        }
        val groupBtn = pill("🗂\n지도 표시")
        fun railify(v: TextView, label: String? = null) {
            label?.let { v.text = it }
            v.textSize = 11.5f; v.gravity = Gravity.CENTER; v.maxLines = 3
            v.setPadding(dp(a, 2), dp(a, 7), dp(a, 2), dp(a, 7)); v.minHeight = dp(a, 54)
            (v.background as? GradientDrawable)?.let { g -> g.cornerRadius = dp(a, 16).toFloat(); if (v !== pastBtn) g.setColor(Color.argb(235, 15, 29, 41)) }
            rail.addView(v, LinearLayout.LayoutParams(dp(a, 66), ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(a, 7) })
        }
        val railSet = setOf<android.view.View>(searchPill, pastBtn, pinBtn, windPill)
        val kids = (0 until bar.childCount).map { bar.getChildAt(it) }
        bar.removeAllViews()
        flyout.addView(TextView(a).apply { text = "🗂 지도에 표시"; textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#7FD6FF")) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(a, 6) })
        for (v in kids) {
            if (v in railSet) continue
            if (v is TextView && v.text.toString().startsWith("내 위치로")) continue   // ◎ 버튼과 같음
            flyout.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(a, 6) })
        }
        railify(searchPill, "🔍\n장소"); railify(pastBtn, pastBtn.text.toString())
        if (kids.contains(pinBtn)) railify(pinBtn, "📌\n포인트")
        railify(groupBtn); railify(windPill, "🌬\n바람")
        groupBtn.setOnClickListener {
            val open = flyout.visibility != android.view.View.VISIBLE
            flyout.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
            setOn(groupBtn, open); groupBtn.setTextColor(if (open) Color.parseColor("#7FD6FF") else Color.WHITE)
        }
        // v3.9.2 (161) 해루질 참고용 커튼 (버튼 줄 바로 아래)
        root.addView(zBar.curtain, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(Ui.dp(a, 12), 0, Ui.dp(a, 12), Ui.dp(a, 6)) })
        // v6.60 (338): 지난 기록 상자 하나 (제목 · 출조 색 · 어종 · 합계)
        pastPanel.addView(chipWrap, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(a, 6) })
        pastPanel.addView(spWrap, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(a, 6) })
        pastPanel.addView(pastSumView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(pastPanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(Ui.dp(a, 10), 0, Ui.dp(a, 10), Ui.dp(a, 6)) })
        // v6.37 (298): 🌊 지진해일 특보가 있으면 지도 맨 위 빨간 띠
        val tsuBar = TextView(a).apply {
            textSize = 13.5f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(a, 14), dp(a, 8), dp(a, 14), dp(a, 8))
            background = GradientDrawable().apply { setColor(Color.parseColor("#C62828")); cornerRadius = dp(a, 10).toFloat() }
            visibility = android.view.View.GONE
        }
        root.addView(tsuBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(Ui.dp(a, 12), 0, Ui.dp(a, 12), Ui.dp(a, 6)) })
        run {
            val p = Tracker.last?.let { it.latitude to it.longitude } ?: live?.anyLocation()
            Warn.tsunami(p?.first, p?.second) { t ->
                if (!t.isNullOrBlank()) { tsuBar.text = "🌊 $t — 바다에서 멀리, 높은 곳으로 피하세요"; tsuBar.visibility = android.view.View.VISIBLE }
            }
        }

        val frame = FrameLayout(a)
        frame.addView(map, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // v6.73 (370): 왼쪽 세로 줄 + 🗂 묶음 펼침 (지도 위에 떠 있음 · 길면 위아래로 밀기)
        frame.addView(android.widget.ScrollView(a).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; addView(rail) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { leftMargin = dp(a, 8); topMargin = dp(a, 8) })
        frame.addView(android.widget.ScrollView(a).apply { isVerticalScrollBarEnabled = false; addView(flyout) },
            FrameLayout.LayoutParams(dp(a, 232), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { leftMargin = dp(a, 82); topMargin = dp(a, 8) })
        // v3.9.1: 출조 기록을 볼 때만 (빈 지도 · 내 위치 지도에서는 안 띄움)
        if (all.isEmpty() && live == null && layers.isNotEmpty()) {
            frame.addView(TextView(a).apply {
                text = "아직 위치 기록이 없어요\n출조 중에 위치 권한을 허용하면 경로와 조과 위치가 표시돼요"
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 12))
                background = GradientDrawable().apply { setColor(Color.argb(200, 11, 26, 38)); cornerRadius = dp(a, 12).toFloat() }
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(a, 10); leftMargin = dp(a, 12); rightMargin = dp(a, 12)   // 지도 가운데를 가리지 않게 맨 위로
            })
        }
        if (liveMe != null) addStatus(a, frame, liveMe)
        if (live != null) zoomButtons(a, frame, map, { _ ->
                follow = !follow
                paintFollow()
                if (follow) boat.position?.let { map.controller.animateTo(it) }
                Ui.toast(a, if (follow) "📍 내 위치 따라가기 켬" else "따라가기 끔")
            }, { v -> followView = v; paintFollow() })
        else zoomButtons(a, frame, map, liveMe?.let { lm -> { v: TextView? -> lm.goToMe(v) } })
        // v6.9 (256): '이 출조만 보기' 안내 줄 (지도 맨 위 · 누르면 다 보기)
        val focusBar = TextView(a).apply {
            textSize = 12.5f; setTextColor(Color.WHITE); setLineSpacing(0f, 1.2f)
            setPadding(dp(a, 12), dp(a, 8), dp(a, 12), dp(a, 8))
            background = GradientDrawable().apply { setColor(Color.argb(238, 11, 26, 38)); cornerRadius = dp(a, 12).toFloat(); setStroke(dp(a, 1), Color.parseColor("#F28C38")) }
            visibility = android.view.View.GONE
        }
        frame.addView(focusBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            topMargin = dp(a, 8); leftMargin = dp(a, 10); rightMargin = dp(a, 10) })
        focusFn = { k ->
            val p = k?.let { pastTrips[it] }
            past.focus = p?.key; pastRoutes.focus = p?.key
            if (p == null) {
                pastRoutes.marks = emptyList(); focusBar.visibility = android.view.View.GONE
            } else {
                if (!past.isEnabled) { past.isEnabled = true; pastRoutes.isEnabled = true }
                pastRoutes.marks = p.catches.sortedBy { it.second }.map { PastRoutesOverlay.Mark(it.first, hmFmt.format(Date(it.second))) }
                val head = android.text.SpannableStringBuilder("● ").apply {
                    setSpan(android.text.style.ForegroundColorSpan(p.color), 0, 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    append(p.label); append(" 만 보는 중        × 다 보기\n")
                }
                val endT = if (p.end > 0) p.end else p.catches.maxOfOrNull { it.second } ?: p.start
                head.append(if (p.track.size >= 2) distanceText(p.track) + " · " else if (p.src == 2) "게시판 글은 이동 경로가 없어요 · " else "이동 경로 없음 · ")
                head.append(hmFmt.format(Date(p.start))).append("~").append(hmFmt.format(Date(endT)))
                head.append(" · 번호 = 잡은 순서")
                focusBar.text = head
                focusBar.visibility = android.view.View.VISIBLE
                val pts = p.track + p.catches.map { it.first }
                if (pts.isNotEmpty()) map.post { fit(map, pts) }
            }
            map.invalidate()
        }
        focusBar.setOnClickListener { focusFn(null) }
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (live != null) root.addView(liveInfo)
        root.addView(legendView)

        // 손으로 지도를 끌면 따라가기 잠시 끔
        map.setOnTouchListener { _, e ->
            if (live != null && follow && e.action == MotionEvent.ACTION_MOVE) {
                follow = false
                paintFollow()
            }
            false
        }

        // ---- 실시간 갱신 + 나침반 ----
        var compass: Float? = null
        val sm = a.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rot = FloatArray(9); val ori = FloatArray(3)
        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, ori)
                compass = ((Math.toDegrees(ori[0].toDouble()) + 360) % 360).toFloat()
            }
            override fun onAccuracyChanged(s: Sensor?, acc: Int) {}
        }
        var lastHeading = 0f
        val tick = object : Runnable {
            override fun run() {
                val t = Store.current
                if (live != null && t != null && t.id == live.id) {
                    val l = layerOf(t)
                    val loc = Tracker.last
                    val pts = l.track.toMutableList()
                    if (loc != null) pts.add(GeoPoint(loc.latitude, loc.longitude))
                    liveLine.setPoints(pts); liveEdge.setPoints(pts)
                    dots.dots = toDots(l.catches)
                    if (loc != null) {
                        val pos = GeoPoint(loc.latitude, loc.longitude)
                        val speed = if (loc.hasSpeed()) loc.speed else 0f
                        val heading = when {
                            loc.hasBearing() && speed >= 0.8f -> loc.bearing
                            compass != null -> compass!!
                            else -> lastHeading
                        }
                        lastHeading = heading
                        boat.position = pos
                        boat.bearing = heading
                        boat.accuracy = if (loc.hasAccuracy()) loc.accuracy else 0f
                        if (follow) map.controller.animateTo(pos)
                        val ageSec = (System.currentTimeMillis() - loc.time) / 1000
                        liveInfo.text = String.format(Locale.KOREA, "속도 %.1f노트 · 방향 %s (%d°)%s",
                            speed * 1.94384f, directionName(heading), heading.toInt(),
                            if (ageSec > 30) " · 위치 ${agoText(ageSec)}" else "") + "\n" + distanceText(pts)
                    } else {
                        liveInfo.text = "GPS 위치를 찾는 중… (위치 권한·GPS 켜짐 확인)"
                    }
                    map.invalidate()
                    handler.postDelayed(this, 1000)
                }
            }
        }

        dialog.setContentView(root)
        Ui.edge(dialog.window)   // v6.0 (242-1 · 243): 상태바 · 하단 버튼 여백 · 큰 화면 가운데
        dialog.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            Live.removeListener(matesListener)
            testListener?.let { TestTrack.removeListener(it) }
            if (live != null) { Tracker.live = false; sm.unregisterListener(sensorListener) }
            map.onPause(); map.onDetach()
        }
        dialog.show()
        map.onResume()

        // v6.73: 지도 크기가 정해진 뒤에 처음 자리 잡기 (전엔 크기 0일 때 잡혀 세계 전체로 열릴 때가 있었음)
        fun firstView() {
            val loc = Tracker.last
            if (live != null && loc != null) {
                map.controller.setZoom(16.0)
                map.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
            } else {
                fit(map, all)
            }
        }
        whenSized(map) { firstView(); map.postDelayed({ if (map.zoomLevelDouble < 5.0) firstView() }, 400) }
        if (live != null) {
            Tracker.live = true
            sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI) }
            handler.post(tick)
        }
    }

    /** 내 지난 출조 + 같은 그룹 친구 출조의 조과 점 */
    /** 지금 고른 "지난 기록 보기" */
    private fun pastModeNow(): String {
        val m = Store.pastMode
        val gs = Cloud.groups
        return when {
            Cloud.user == null -> "mine"
            m == null || m == "public" -> "publicAll"   // v6.60 (338): 기본 = 내 기록 + 전체 게시판 · 전체 기간 (기간은 보기 바꾸기에서)
            m.startsWith("g:") && gs.none { "g:${it.id}" == m } -> Cloud.active?.let { "g:${it.id}" } ?: "mine"
            else -> m
        }
    }

    private fun groupLabel(g: Cloud.Group) = g.title

    fun pastModeLabel(mode: String): String = when {
        mode == "mine" -> "내 기록만"
        mode == "allg" -> "내 기록 + 가입한 그룹 전부"
        mode == "public" -> "내 기록 + 전체 게시판 (최근 3개월)"
        mode == "publicAll" -> "내 기록 + 전체 게시판 (전체 기간)"
        mode.startsWith("g:") -> "내 기록 + " + (Cloud.groups.firstOrNull { "g:${it.id}" == mode }?.let { groupLabel(it) } ?: "그룹") + " 친구 기록"
        else -> mode
    }

    // ---------------- v6.9 (256): 지난 기록 출조별 색 · 경로 · '이 출조만 보기' ----------------
    /** 지난 기록 출조 하나 (key = "me:id" / "g:id" / "p:id") */
    class PastTrip(val key: String, val label: String, val start: Long, val end: Long, val src: Int,
                   var track: List<GeoPoint>, val catches: List<Pair<GeoPoint, Long>>, val open: (Activity) -> Unit) {
        var color: Int = GREY_TRIP
        /** v6.9 (254): 민물 출조 (물때 대신 해 기준) */
        var fresh = false
    }
    private val TRIP_COLORS = intArrayOf(Color.parseColor("#F28C38"), Color.parseColor("#5BC8DF"), Color.parseColor("#B58CF0"),
        Color.parseColor("#5FD39A"), Color.parseColor("#F2C14E"), Color.parseColor("#F07AA0"))
    private val GREY_TRIP = Color.parseColor("#8E9AA3")
    /** 지금 지도에 깔린 지난 기록 출조들 (점 → 창 → '상세 ›' · '이 출조만 보기') */
    private val pastTrips = HashMap<String, PastTrip>()
    private val mdFmt = SimpleDateFormat("M/d", Locale.KOREA)

    /**
     * 지난 기록: 내 기록 + (고른 그룹 친구 / 전체 게시판). 점에는 어종 그림 · 색 = 출조(최근 6개, 나머지 회색)
     *  done 은 두 번 불릴 수 있어요 (그룹 친구 이동 경로를 받은 뒤 한 번 더)
     */
    private fun loadPast(excludeId: Long?, mode: String, done: (List<MapDot>, Int, Int, List<PastTrip>) -> Unit) {
        val df = SimpleDateFormat("yy.M.d", Locale.KOREA)
        val trips = mutableListOf<PastTrip>()
        val pubWithTrack = mutableListOf<String>()   // v6.10 (257): 경로를 공개한 게시판 글
        val mine = Store.history.filter { it.id != excludeId }.take(150).flatMap { t ->
            val mul = Tide.mulName(t.start).substringBefore(" (")
            val key = "me:${t.id}"
            val cs = t.catches.filter { it.lat != null && it.lon != null }
            if (cs.isNotEmpty()) trips.add(PastTrip(key, "${mdFmt.format(Date(t.start))} 나", t.start, t.end, 0,
                t.track.map { GeoPoint(it.lat, it.lon) }, cs.map { GeoPoint(it.lat!!, it.lon!!) to it.time }) { a ->
                a.startActivity(android.content.Intent(a, TripActivity::class.java).putExtra("id", t.id)) })
            if (t.isFresh()) trips.lastOrNull { it.key == key }?.fresh = true
            cs.map {
                MapDot(GeoPoint(it.lat!!, it.lon!!), slotCol(it.slot),
                    "나 · ${df.format(Date(it.time))} · ${t.name(it.slot)} · $mul", "나 ${t.name(it.slot)}",
                    it.time, "나", t.name(it.slot), paidMark(t.cat, t.paid), 0, if (it.q == 1 && it.acc > 0f) it.acc.toDouble() else 0.0, it.q == 1, key)
            }
        }
        val mineTrips = trips.toList()
        // v6.38 (299): 폰에 저장된 것으로 먼저 한 번(final=false) → 서버에서 새 글까지 합친 뒤 한 번 더(final=true)
        var finalDone = false
        fun finish(all: List<MapDot>, nMine: Int, nOthers: Int, groupTrips: List<Pair<String, Cloud.RemoteTrip>> = emptyList(), final: Boolean = true) {
            if (!final && finalDone) return
            if (final) finalDone = true
            val sorted = trips.sortedByDescending { it.start }
            sorted.forEachIndexed { i, p -> p.color = if (i < TRIP_COLORS.size) TRIP_COLORS[i] else GREY_TRIP }
            pastTrips.clear(); sorted.forEach { pastTrips[it.key] = it }
            val colored = all.map { d -> pastTrips[d.trip]?.let { d.withColor(it.color) } ?: d }
            done(colored, nMine, nOthers, sorted)
            if (!final) return
            // 색이 있는 그룹 친구 출조만 이동 경로 받기 (서버 읽기 줄이기)
            val top = sorted.take(TRIP_COLORS.size).map { it.key }.toSet()
            // v6.10 (257): 색이 있는 게시판 글 중 경로를 공개한 것도
            val pubNeed = pubWithTrack.filter { "p:$it" in top }
            if (pubNeed.isNotEmpty()) {
                var pl = pubNeed.size
                pubNeed.forEach { id ->
                    PublicBoard.loadTrack(id, true) { pts ->
                        pastTrips["p:$id"]?.track = pts.map { GeoPoint(it.lat, it.lon) }
                        if (--pl == 0) done(colored, nMine, nOthers, sorted)
                    }
                }
            }
            val need = groupTrips.filter { (_, r) -> "g:${r.id}" in top && r.hasTrack && r.track.isEmpty() }
            if (need.isEmpty()) return
            var left = need.groupBy { it.first }.size
            need.groupBy { it.first }.forEach { (gid, l) ->
                Cloud.loadTracksIn(gid, l.map { it.second }) { full ->
                    full.forEach { r -> pastTrips["g:${r.id}"]?.track = r.track.map { GeoPoint(it.lat, it.lon) } }
                    if (--left == 0) done(colored, nMine, nOthers, sorted)
                }
            }
        }
        val myId = Cloud.uid
        if (myId == null || mode == "mine") { finish(mine, mine.size, 0); return }
        if (mode == "public" || mode == "publicAll") {
            // v6.26: 전체 기간 = 기간 거르기 없이 (게시판 최근 300개)
            fun buildPub(list: List<PublicBoard.PTrip>?, final: Boolean) {
                if (!final && finalDone) return
                trips.clear(); trips.addAll(mineTrips); pubWithTrack.clear()
                val others = list.orEmpty().filter { it.owner != myId && !Moderation.isBlocked(it.owner) }.flatMap { t ->
                    val mul = Tide.mulName(t.start).substringBefore(" (")
                    val key = "p:${t.id}"
                    val pts = t.catches.mapNotNull { c -> c.cell?.let { GeoPoint(c.exactLat ?: it.lat, c.exactLon ?: it.lon) to c.time } }
                    if (pts.isNotEmpty()) trips.add(PastTrip(key, "${mdFmt.format(Date(t.start))} ${t.nick}", t.start, t.end, 2, emptyList(), pts) { a ->
                        PublicBoard.openBig(a, t) })
                    if (t.exact) pubWithTrack.add(t.id)   // 경로를 올렸거나, 같은 그룹 기록에 경로가 있을 수 있음
                    if (t.isFresh()) trips.lastOrNull { it.key == key }?.fresh = true
                    t.catches.mapNotNull { c ->
                        val cell = c.cell ?: return@mapNotNull null
                        MapDot(GeoPoint(cell.lat, cell.lon), slotCol(c.slot),
                            "${t.nick} · ${df.format(Date(c.time))} · ${t.name(c.slot)} · $mul", "${t.nick} ${t.name(c.slot)}",
                            c.time, t.nick, t.name(c.slot), false, 2, if (t.exact) 0.0 else cell.r, false, key)
                    }
                }
                finish(mine + others, mine.size, others.size, final = final)
            }
            PublicBoard.load(if (mode == "publicAll") 0L else System.currentTimeMillis() - 92L * 86_400_000L,
                quick = { buildPub(it, false) }) { list, _ -> buildPub(list, true) }
            return
        }
        val gs = if (mode == "allg") Cloud.groups else Cloud.groups.filter { "g:${it.id}" == mode }
        if (gs.isEmpty()) { finish(mine, mine.size, 0); return }
        val got = mutableListOf<Pair<String, Cloud.RemoteTrip>>()
        val quickGot = HashMap<String, List<Cloud.RemoteTrip>>()
        var left = gs.size
        fun buildGroups(got: List<Pair<String, Cloud.RemoteTrip>>, final: Boolean) {
                    if (!final && finalDone) return
                    trips.clear(); trips.addAll(mineTrips)
                    val friendsTrips = got.filter { it.second.owner != myId }.distinctBy { it.second.id }
                    val friends = friendsTrips.flatMap { (_, t) ->
                        val mul = Tide.mulName(t.start).substringBefore(" (")
                        val key = "g:${t.id}"
                        val cs = t.catches.filter { it.lat != null && it.lon != null }
                        if (cs.isNotEmpty()) trips.add(PastTrip(key, "${mdFmt.format(Date(t.start))} ${t.ownerName}", t.start, t.end, 1,
                            t.track.map { GeoPoint(it.lat, it.lon) }, cs.map { GeoPoint(it.lat!!, it.lon!!) to it.time }) { a ->
                            TripActivity.remoteView = t
                            a.startActivity(android.content.Intent(a, TripActivity::class.java).putExtra("remote", true)) })
                        if (t.isFresh()) trips.lastOrNull { it.key == key }?.fresh = true
                        cs.map {
                            MapDot(GeoPoint(it.lat!!, it.lon!!), slotCol(it.slot),
                                "${t.ownerName} · ${df.format(Date(it.time))} · ${t.name(it.slot)} · $mul", "${t.ownerName} ${t.name(it.slot)}",
                                it.time, t.ownerName, t.name(it.slot), paidMark(t.cat, t.paid), 1, 0.0, false, key)
                        }
                    }
                    finish(mine + friends, mine.size, friends.size, friendsTrips, final)
        }
        gs.forEach { g ->
            Cloud.recentTripsOf(g, quick = { l ->
                quickGot[g.id] = l
                if (quickGot.size == gs.size) buildGroups(quickGot.flatMap { (gid, ts) -> ts.map { gid to it } }, false)
            }) { list, _ ->
                list?.let { l -> got.addAll(l.map { g.id to it }) }
                left--
                if (left == 0) buildGroups(got, true)
            }
        }
    }

    /** 지도 아래쪽에 "위치가 꺼져 있어요" 같은 안내 (누르면 켜기) */
    private fun addStatus(a: Activity, frame: FrameLayout, liveMe: LiveMe) {
        val tv = TextView(a).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(a, 14), dp(a, 8), dp(a, 14), dp(a, 8))
            background = GradientDrawable().apply { setColor(Color.argb(225, 11, 26, 38)); cornerRadius = dp(a, 18).toFloat(); setStroke(dp(a, 1), Color.parseColor("#4285F4")) }
            visibility = android.view.View.GONE
            setOnClickListener { askLocation(a, liveMe) }
        }
        frame.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(a, 14) })
        liveMe.onStatus = { msg ->
            if (msg == null) tv.visibility = android.view.View.GONE
            else {
                tv.text = if (msg.endsWith("…")) msg else "$msg  ›"
                tv.visibility = android.view.View.VISIBLE
            }
        }
    }

    /** 위치 권한 요청 / GPS 켜기 화면 → 잡히면 계속 따라가기 */
    private fun askLocation(a: Activity, live: LiveMe) {
        LocOnce.get(a) { l, err ->
            if (l != null) live.accept(l)
            else if (err != null) android.widget.Toast.makeText(a, err, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 조과 자리를 원 + 마릿수로 보여주는 큰 지도 (내 위치 + 가장 가까운 자리까지 길 안내)
     * dots = 조과 하나당 점 하나. radiusOf = 그 점에 그릴 원 반지름(m), 0 이하면 원 없이 점만
     * 원이나 숫자를 누르면 날짜별 요약
     */
    fun showSpots(a: Activity, title: String, dots: List<MapDot>, radiusOf: (MapDot) -> Double, legend: String,
                  targetName: String = "가장 가까운 자리") {
        configure(a)
        val dialog = Dialog(a, R.style.FlFull)
        val map = MapView(a).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)   // v5.2 (237): 앱 ＋/－ 버튼을 따로 씀
            setTileSource(if (Store.satellite) satelliteSource else TileSourceFactory.MAPNIK)
            isTilesScaledToDpi = true
            isVerticalMapRepetitionEnabled = false; minZoomLevel = 4.0   // v6.73: 세계가 위아래로 겹쳐 보이지 않게
        }
        // v3.9.3: 포인트 지도에도 🦀 해루질 참고 구역 (맨 아래)
        map.overlays.add(Zones.ZoneOverlay(a) { Zones.on(a) }.apply { onTapZones = { Zones.showInfo(a, it, map) } })
        addCircles(a, map, dots, radiusOf)
        map.overlays.add(DotsOverlay(a, dots, false) { showCluster(a, it) }.apply { icons = true; mergeDp = 34f })
        val targets = dots.map { it.point }.distinctBy { "${it.latitude},${it.longitude}" }
        val liveMe = LiveMe.attach(a, map, targets, targetName)

        val bg = Color.parseColor("#0B1A26")
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        root.addView(TextView(a).apply {
            text = title; textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(a, 16), dp(a, 10), dp(a, 16), dp(a, 4))
        })
        val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; setPadding(dp(a, 12), dp(a, 4), dp(a, 12), dp(a, 8)) }
        fun pill(label: String, onClick: (TextView) -> Unit) = TextView(a).apply {
            text = label; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            minHeight = dp(a, 40); setPadding(dp(a, 14), 0, dp(a, 14), 0)
            background = GradientDrawable().apply { cornerRadius = dp(a, 20).toFloat(); setStroke(dp(a, 1), Color.parseColor("#3A5A70")) }
            setOnClickListener { onClick(this) }
        }.also { bar.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(a, 8) }) }
        bar.addView(MapSearch.pill(a, map), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(a, 8) })
        pill("내 위치로") { v -> liveMe.goToMe(v) }
        pill("나와 포인트 같이") { _ ->
            val p = liveMe.position
            if (p == null) android.widget.Toast.makeText(a, "아직 내 위치를 못 잡았어요", android.widget.Toast.LENGTH_SHORT).show()
            else LiveMe.fitMeAnd(map, p, targets)
        }
        pill("포인트 전체") { fit(map, dots.map { it.point }) }
        val zBar = Zones.bar(a, map)
        bar.addView(zBar.pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(a, 8) })
        pill(if (Store.satellite) "지도로 보기" else "위성으로 보기") { v ->
            Store.satellite = !Store.satellite
            satWarn(a)
            map.setTileSource(if (Store.satellite) satelliteSource else TileSourceFactory.MAPNIK)
            v.text = if (Store.satellite) "지도로 보기" else "위성으로 보기"
        }
        pill("닫기") { dialog.dismiss() }
        root.addView(Ui.hscroll(a, bar))
        root.addView(zBar.curtain, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(a, 12), 0, dp(a, 12), dp(a, 6)) })
        val frame = FrameLayout(a)
        frame.addView(map, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addStatus(a, frame, liveMe)
        zoomButtons(a, frame, map) { v -> liveMe.goToMe(v) }
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(TextView(a).apply {
            text = legend + "\n파란 점 = 지금 내 위치 (뾰족한 쪽 = 폰이 향한 방향) · 점선 = $targetName 까지\n지도 © OpenStreetMap · 위성 © Esri"
            textSize = 12f; setTextColor(Color.parseColor("#9FB4C2"))
            setPadding(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 12))
        })
        dialog.setContentView(root)
        Ui.edge(dialog.window)   // v6.0 (242-1 · 243): 상태바 · 하단 버튼 여백 · 큰 화면 가운데
        dialog.setOnDismissListener { liveMe.stop(); map.onPause(); map.onDetach() }
        dialog.show()
        map.onResume()
        map.post { fit(map, dots.map { it.point }) }
    }

    /**
     * 지금 출조 중인 사람 위치 지도 (그룹 '지금 출조 중'에서 눌렀을 때)
     *  - 그 사람 위치가 가운데 · 초록 점 + 이름표, 내 위치(파란 점)와 점선·거리도 같이
     *  - 위 버튼: 📍 ○○ 위치로 · 내 위치로 · 둘 다 보기 · 위성 · 닫기
     */
    fun showPerson(a: Activity, name: String, lat: Double, lon: Double, info: String,
                   uid: String? = null, gids: List<String> = emptyList(), pub: Boolean = false) {
        configure(a)
        val dialog = Dialog(a, R.style.FlFull)
        val pt = GeoPoint(lat, lon)
        val map = MapView(a).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)   // v5.2 (237): 앱 ＋/－ 버튼을 따로 씀
            setTileSource(if (Store.satellite) satelliteSource else TileSourceFactory.MAPNIK)
            isTilesScaledToDpi = true
            isVerticalMapRepetitionEnabled = false; minZoomLevel = 4.0   // v6.73: 세계가 위아래로 겹쳐 보이지 않게
        }
        // 그 사람의 경로 + 잡은 자리 (관리자가 권한 준 사람만 받을 수 있음)
        fun route(w: Int, c: Int) = Polyline(map).apply {
            outlinePaint.color = c; outlinePaint.strokeWidth = dp(a, w).toFloat(); outlinePaint.isAntiAlias = true
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND; outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
        }
        val pEdge = route(7, ROUTE_EDGE); val pLine = route(4, ROUTE)
        val pDots = DotsOverlay(a, emptyList(), false) { showCluster(a, it) }.apply { icons = true; mergeDp = 34f }
        map.overlays.add(Zones.ZoneOverlay(a) { Zones.on(a) }.apply { onTapZones = { Zones.showInfo(a, it, map) } })   // v3.9.3
        // v6.3 (246): 지난 기록 (맨 아래 · 반투명 · 내 지도와 같은 방식)
        val past = DotsOverlay(a, emptyList(), true) { showCluster(a, it) }.apply { isEnabled = false; icons = true }
        map.overlays.add(past)
        map.overlays.add(pEdge); map.overlays.add(pLine); map.overlays.add(pDots)
        val liveMe = LiveMe.attach(a, map, listOf(pt), name, autoFit = false)
        map.overlays.add(PersonOverlay(a, pt, name))
        fun goPerson() { map.controller.setZoom(16.0); map.controller.animateTo(pt) }

        val bg = Color.parseColor("#0B1A26")
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        root.addView(TextView(a).apply {
            text = "🟢 $name · 지금 출조 중"; textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(a, 16), dp(a, 10), dp(a, 16), dp(a, 4))
        })
        val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; setPadding(dp(a, 12), dp(a, 4), dp(a, 12), dp(a, 8)) }
        fun pill(label: String, strong: Boolean = false, onClick: (TextView) -> Unit) = TextView(a).apply {
            text = label; textSize = 14f; setTextColor(if (strong) Color.parseColor("#0B1A26") else Color.WHITE); gravity = Gravity.CENTER
            if (strong) typeface = Typeface.DEFAULT_BOLD
            minHeight = dp(a, 40); setPadding(dp(a, 14), 0, dp(a, 14), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(a, 20).toFloat()
                if (strong) setColor(Color.parseColor("#5FD49A")) else setStroke(dp(a, 1), Color.parseColor("#3A5A70"))
            }
            setOnClickListener { onClick(this) }
        }.also { bar.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(a, 8) }) }
        bar.addView(MapSearch.pill(a, map), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(a, 8) })
        pill("📍 $name 위치로", true) { goPerson() }
        pill("내 위치로") { v -> liveMe.goToMe(v) }
        pill("둘 다 보기") { _ ->
            val p = liveMe.position
            if (p == null) android.widget.Toast.makeText(a, "아직 내 위치를 못 잡았어요", android.widget.Toast.LENGTH_SHORT).show()
            else {
                val box = BoundingBox.fromGeoPoints(listOf(p, pt))
                if (box.latitudeSpan < 0.002 && box.longitudeSpan < 0.002) goPerson() else map.zoomToBoundingBox(box.increaseByScale(1.4f), true)
            }
        }
        pill(if (Store.satellite) "지도로 보기" else "위성으로 보기") { v ->
            Store.satellite = !Store.satellite
            satWarn(a)
            map.setTileSource(if (Store.satellite) satelliteSource else TileSourceFactory.MAPNIK)
            v.text = if (Store.satellite) "지도로 보기" else "위성으로 보기"
        }
        val zBar = Zones.bar(a, map)
        bar.addView(zBar.pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(a, 8) })
        // v6.3 (246): 📍 지난 기록 — 이 사람이 지금 있는 곳에서 예전에 뭐가 잡혔나 (내 기록 / 그룹 친구 / 전체 게시판)
        var legendBase = ""
        var pastNote = ""
        var paintLegend: () -> Unit = {}
        var pastLoaded = false
        val pastBar = TextView(a).apply {
            textSize = 12f; setTextColor(Color.WHITE)
            setPadding(dp(a, 12), dp(a, 7), dp(a, 12), dp(a, 7))
            background = GradientDrawable().apply { setColor(Color.parseColor("#12293A")); cornerRadius = dp(a, 10).toFloat() }
            visibility = android.view.View.GONE
        }
        fun loadPastNow() {
            val mode = pastModeNow()
            pastBar.text = "지난 기록: " + pastModeLabel(mode) + "   보기 바꾸기 ▾"
            pastNote = "\n지난 기록 불러오는 중…"; paintLegend()
            loadPast(null, mode) { list, mine, others, _ ->
                if (!dialog.isShowing) return@loadPast
                past.dots = list
                pastNote = "\n지난 기록 (내 조과 $mine · " + (if (mode.startsWith("public")) "게시판" else "친구") + " 조과 $others)" +
                    " · 반투명 그림 = 예전에 잡힌 어종 · 누르면 그날 상세"
                paintLegend(); map.invalidate()
            }
        }
        pastBar.setOnClickListener {
            val modes = mutableListOf("mine")
            Cloud.groups.forEach { modes.add("g:${it.id}") }
            if (Cloud.groups.size > 1) modes.add("allg")
            if (Cloud.user != null) { modes.add("public"); modes.add("publicAll") }   // v6.26: 전체 기간도
            val cur = pastModeNow()
            AlertDialog.Builder(a, R.style.FlAlert)
                .setTitle("지난 기록 보기")
                .setItems(modes.map { (if (it == cur) "● " else "○ ") + pastModeLabel(it) }.toTypedArray()) { _, i ->
                    Store.pastMode = modes[i]
                    loadPastNow()
                }
                .show()
        }
        // v6.70: 켜기(설정 줄 보임) → 줄 접기 → 끄기 → 다시 처음
        var pFolded = false
        pill("지난 기록") { v ->
            if (past.isEnabled && !pFolded) { pFolded = true; pastBar.visibility = android.view.View.GONE; v.text = "지난 기록 ▸"; return@pill }
            pFolded = false; v.text = "지난 기록"
            past.isEnabled = !past.isEnabled
            (v.background as GradientDrawable).setColor(if (past.isEnabled) Color.parseColor("#2B4A5E") else Color.TRANSPARENT)
            pastBar.visibility = if (past.isEnabled) android.view.View.VISIBLE else android.view.View.GONE
            if (past.isEnabled && !pastLoaded) { pastLoaded = true; loadPastNow() }
            else { if (!past.isEnabled) pastNote = ""; paintLegend() }
            if (!past.isEnabled) pastLoaded = false
            map.invalidate()
        }
        pill("닫기") { dialog.dismiss() }
        root.addView(Ui.hscroll(a, bar))
        root.addView(pastBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(a, 12), 0, dp(a, 12), dp(a, 6)) })
        root.addView(zBar.curtain, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(a, 12), 0, dp(a, 12), dp(a, 6)) })
        val frame = FrameLayout(a)
        frame.addView(map, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addStatus(a, frame, liveMe)
        zoomButtons(a, frame, map) { v -> liveMe.goToMe(v) }
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val legend = TextView(a).apply {
            text = info + "\n초록 점 = $name · 파란 점 = 지금 내 위치 · 점선 = $name 까지 방향·거리\n지도 © OpenStreetMap · 위성 © Esri"
            textSize = 12f; setTextColor(Color.parseColor("#9FB4C2"))
            setPadding(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 12))
        }
        root.addView(legend)
        legendBase = legend.text.toString()
        paintLegend = { legend.text = legendBase + pastNote }
        var stopMap: () -> Unit = {}
        if (uid != null) {
            val tf = SimpleDateFormat("HH:mm", Locale.KOREA)
            stopMap = Live.watchMap(uid, gids, pub) { m ->
                if (m == null) {
                    legendBase = info + "\n초록 점 = $name · 파란 점 = 지금 내 위치\n(이동 경로 · 잡은 자리는 관리자가 권한 준 사람만 보여요)\n지도 © OpenStreetMap · 위성 © Esri"
                    paintLegend()
                    return@watchMap
                }
                val pts = m.track.map { GeoPoint(it.lat, it.lon) }
                pEdge.setPoints(pts); pLine.setPoints(pts)
                fun nm(slot: Int) = m.names.getOrElse(slot - 1) { "${slot}번" }
                pDots.dots = m.catches.map { c ->
                    MapDot(GeoPoint(c.lat!!, c.lon!!), slotCol(c.slot), "$name · ${tf.format(Date(c.time))} · ${nm(c.slot)}" + if (c.q == 1) " (대략 위치)" else "",
                        nm(c.slot), c.time, name, nm(c.slot), false, 1, 0.0, c.q == 1)
                }
                val counts = m.catches.groupingBy { nm(it.slot) }.eachCount().entries.joinToString(" · ") { "${it.key} ${it.value}" }
                // v6.4+ (250): 이동 거리 (내 지도와 같은 계산)
                val dist = if (pts.size >= 2) "\n" + distanceText(pts) else ""
                legendBase = info + dist + "\n지도에 보이는 조과: " + counts.ifBlank { "아직 없음" } +
                    "\n초록 점 = $name · 분홍 선 = 이동 경로 · 숫자 = 그 자리 마릿수 · 파란 점 = 내 위치\n지도 © OpenStreetMap · 위성 © Esri"
                paintLegend()
                map.invalidate()
            }
        }
        dialog.setContentView(root)
        Ui.edge(dialog.window)   // v6.0 (242-1 · 243): 상태바 · 하단 버튼 여백 · 큰 화면 가운데
        dialog.setOnDismissListener { stopMap(); liveMe.stop(); map.onPause(); map.onDetach() }
        dialog.show()
        map.onResume()
        map.post { goPerson() }
    }

    /** 출조 중인 사람: 초록 점 + 이름표 */
    private class PersonOverlay(ctx: Context, private val pt: GeoPoint, private val name: String) : org.osmdroid.views.overlay.Overlay() {
        private val d = ctx.resources.displayMetrics.density
        private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 95, 212, 154) }
        private val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2FBF71") }
        private val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 3 * d; color = Color.WHITE }
        private val box = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 11, 26, 38) }
        private val txt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 14 * d; typeface = Typeface.DEFAULT_BOLD; textAlign = android.graphics.Paint.Align.CENTER
        }
        override fun draw(c: android.graphics.Canvas, map: MapView, shadow: Boolean) {
            if (shadow) return
            val p = map.projection.toPixels(pt, null)
            val x = p.x.toFloat(); val y = p.y.toFloat()
            c.drawCircle(x, y, 22 * d, ring)
            c.drawCircle(x, y, 10 * d, dot)
            c.drawCircle(x, y, 10 * d, edge)
            val w = txt.measureText(name) + 16 * d
            val top = y - 46 * d
            c.drawRoundRect(android.graphics.RectF(x - w / 2, top, x + w / 2, top + 24 * d), 12 * d, 12 * d, box)
            c.drawText(name, x, top + 17 * d, txt)
        }
    }

    /** 칸마다 주황 원 (많이 잡힌 곳일수록 진하게) */
    private fun addCircles(a: Activity, map: MapView, dots: List<MapDot>, radiusOf: (MapDot) -> Double) {
        val byCell = dots.filter { radiusOf(it) > 0 }.groupBy { "${it.point.latitude},${it.point.longitude}" }
        val max = (byCell.values.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1)
        byCell.values.forEach { list ->
            val strength = 0.25 + 0.75 * list.size / max
            map.overlays.add(org.osmdroid.views.overlay.Polygon(map).apply {
                points = org.osmdroid.views.overlay.Polygon.pointsAsCircle(list[0].point, radiusOf(list[0]))
                fillPaint.color = Color.argb((70 + 130 * strength).toInt(), 242, 140, 56)
                outlinePaint.color = Color.parseColor("#FFB070")
                outlinePaint.strokeWidth = dp(a, 3).toFloat()
                setOnClickListener { _, _, _ -> showCluster(a, list); true }
            })
        }
    }

    /** 팝업 안에 넣는 작은 지도 + 내 위치 + 길 안내 */
    class Embedded(val view: android.view.View, val map: MapView, val live: LiveMe)

    /**
     * dots = 점 (정확한 자리), radiusOf 로 원, targetName = 안내 글씨에 쓸 이름
     * onBig = "크게 · 길찾기" 버튼을 누르면
     */
    fun embedded(a: Activity, dots: List<MapDot>, radiusOf: (MapDot) -> Double, targetName: String,
                 onBig: (() -> Unit)?): Embedded {
        configure(a)
        val map = MapView(a).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setTileSource(if (Store.satellite) satelliteSource else TileSourceFactory.MAPNIK)
            isTilesScaledToDpi = true
            isVerticalMapRepetitionEnabled = false; minZoomLevel = 4.0   // v6.73: 세계가 위아래로 겹쳐 보이지 않게
        }
        // 팝업을 스크롤하지 않고 지도를 움직이게
        map.setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
        addCircles(a, map, dots, radiusOf)
        map.overlays.add(DotsOverlay(a, dots, false) { showCluster(a, it) }.apply { icons = true; mergeDp = 34f })
        val targets = dots.map { it.point }.distinctBy { "${it.latitude},${it.longitude}" }
        val live = LiveMe.attach(a, map, targets, targetName)
        val frame = FrameLayout(a)
        frame.addView(map, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fun small(label: String, gravity: Int, onClick: (TextView) -> Unit) = TextView(a).apply {
            text = label; textSize = 13f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            this.gravity = Gravity.CENTER
            setPadding(dp(a, 12), dp(a, 7), dp(a, 12), dp(a, 7))
            background = GradientDrawable().apply { setColor(Color.argb(225, 11, 26, 38)); cornerRadius = dp(a, 16).toFloat(); setStroke(dp(a, 1), Color.parseColor("#3A5A70")) }
            setOnClickListener { onClick(this) }
        }.also { frame.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity).apply { setMargins(dp(a, 8), dp(a, 8), dp(a, 8), dp(a, 8)) }) }
        small("내 위치로", Gravity.BOTTOM or Gravity.END) { v -> live.goToMe(v) }
        if (onBig != null) small("크게 · 길찾기", Gravity.BOTTOM or Gravity.START) { _ -> onBig() }
        addStatusSmall(a, frame, live)
        map.addOnFirstLayoutListener { _, _, _, _, _ ->
            val pts = dots.map { it.point }
            val maxR = dots.maxOfOrNull { radiusOf(it) } ?: 0.0
            if (pts.size == 1 || (pts.isNotEmpty() && BoundingBox.fromGeoPoints(pts).latitudeSpan < 0.0005)) {
                map.controller.setZoom(if (maxR > 300) 14.5 else 16.0); map.controller.setCenter(pts[0])
            } else fit(map, pts)
        }
        map.onResume()
        return Embedded(frame, map, live)
    }

    private fun addStatusSmall(a: Activity, frame: FrameLayout, live: LiveMe) {
        val tv = TextView(a).apply {
            textSize = 12f; setTextColor(Color.WHITE)
            setPadding(dp(a, 10), dp(a, 5), dp(a, 10), dp(a, 5))
            background = GradientDrawable().apply { setColor(Color.argb(225, 11, 26, 38)); cornerRadius = dp(a, 12).toFloat() }
            visibility = android.view.View.GONE
            setOnClickListener { askLocation(a, live) }
        }
        frame.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(a, 8) })
        live.onStatus = { msg ->
            if (msg == null) tv.visibility = android.view.View.GONE
            else { tv.text = if (msg.endsWith("…")) msg else "$msg ›"; tv.visibility = android.view.View.VISIBLE }
        }
    }

    /** 포인트 범위(원) 하나를 보여주는 작은 지도 (내 위치 + 길 안내 포함) */
    fun circleMap(a: Activity, lat: Double, lon: Double, radiusM: Double, onBig: (() -> Unit)? = null): Embedded {
        val dot = MapDot(GeoPoint(lat, lon), Color.parseColor("#F28C38"), "포인트", "포인트")
        return embedded(a, listOf(dot), { radiusM }, "포인트", onBig)
    }

    /** 출조 기록 화면에 들어가는 작은 지도 (누르면 크게) */
    fun preview(a: Activity, t: Store.Trip, onTap: () -> Unit): MapView {
        configure(a)
        val map = MapView(a).apply {
            setMultiTouchControls(false)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setTileSource(if (Store.satellite) satelliteSource else TileSourceFactory.MAPNIK)
            isTilesScaledToDpi = true
            isVerticalMapRepetitionEnabled = false; minZoomLevel = 4.0   // v6.73: 세계가 위아래로 겹쳐 보이지 않게
        }
        val layer = layerOf(t)
        val all = mutableListOf<GeoPoint>()
        if (layer.track.size >= 2) {
            map.overlays.add(Polyline(map).apply {
                setPoints(layer.track); outlinePaint.color = ROUTE_EDGE; outlinePaint.strokeWidth = dp(a, 6).toFloat(); outlinePaint.isAntiAlias = true
            })
            val line = Polyline(map)
            line.setPoints(layer.track)
            line.outlinePaint.color = layer.color
            line.outlinePaint.strokeWidth = dp(a, 3).toFloat()
            line.outlinePaint.isAntiAlias = true
            map.overlays.add(line)
        }
        all.addAll(layer.track)
        all.addAll(layer.catches.map { it.point })
        map.overlays.add(DotsOverlay(a, toDots(layer.catches), false, null).apply { icons = true; mergeDp = 34f })
        map.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) onTap()
            true
        }
        map.addOnFirstLayoutListener { _, _, _, _, _ -> fit(map, all) }
        return map
    }

    // ---- 내 출조를 지도 레이어로 ----
    /** 내 이동 경로 색: 진한 분홍 + 짙은 테두리 (흰 선은 지도와 겹쳐 잘 안 보였음) */
    val ROUTE = Color.parseColor("#FF3EA5")
    val ROUTE_EDGE = Color.parseColor("#5A0F3A")

    fun layerOf(t: Store.Trip, trackColor: Int = ROUTE): MapLayer {
        val df = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
        val track = t.track.map { GeoPoint(it.lat, it.lon) }
        val catches = t.catches.filter { it.lat != null && it.lon != null }.map {
            MapCatch(GeoPoint(it.lat!!, it.lon!!), slotCol(it.slot),
                df.format(Date(it.time)) + " " + t.name(it.slot) + when (it.q) {
                    1 -> " (대략 위치" + (if (it.acc > 0f) " ±${it.acc.toInt()}m" else "") + ")"
                    2 -> " (경로로 계산한 위치)"
                    else -> if (it.acc > 0f) " (±${it.acc.toInt()}m)" else ""
                }, t.name(it.slot), it.time, "", t.name(it.slot), paidMark(t.cat, t.paid),
                it.q == 1, if (it.q == 1 && it.acc > 0f) it.acc.toDouble() else 0.0)
        }
        return MapLayer(trackColor, track, catches)
    }

    fun showTrip(a: Activity, t: Store.Trip) {
        val df = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)
        val withLoc = t.catches.count { it.lat != null }
        val isLive = Store.current?.id == t.id
        // 실시간일 땐 위 굵은 줄에 이동 거리가 이미 있어서 아래 설명엔 안 넣음 (두 번 나오지 않게)
        val legend = (if (isLive) "" else distanceText(t.track.map { GeoPoint(it.lat, it.lon) }) + "\n") +
            "● 주황 ${t.name(1)} · ● 하늘 ${t.name(2)} · 초록 = 출발 · 분홍 선 = 이동 경로" +
            (if (isLive) " · 배 = 지금 내 위치 · 이름표 = 같이 출조 중인 그룹원" else "") +
            "\n위치가 있는 조과 $withLoc / ${t.catches.size} · 어종 그림 + 숫자 = 그 자리 마릿수 · 누르면 상세"
        if (isLive) show(a, df.format(Date(t.start)) + " 출조 중 (실시간)", emptyList(), legend, live = t, excludeId = t.id)
        else show(a, df.format(Date(t.start)) + " 출조", listOf(layerOf(t)), legend, excludeId = t.id)
    }
}
