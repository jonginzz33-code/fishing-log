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
        }
    }
    /**
     * v5.2 (237): 지도 ＋/－ 버튼 — 오른쪽 아래 반투명 어두운 둥근 묶음 (＋ 위 · － 아래) + 그 아래 ◎ 내 위치
     *  onMe = null 이면 ◎ 없음
     */
    fun zoomButtons(a: Activity, frame: FrameLayout, map: MapView, onMe: ((TextView?) -> Unit)? = null) {
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
            text = "◎"; textSize = 22f; setTextColor(Color.parseColor("#6FC3FF")); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; includeFontPadding = false
            background = box(); elevation = dp(a, 3).toFloat()
            contentDescription = "내 위치로"
            setOnClickListener { onMe(null) }
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
    private fun showCluster(a: Activity, items: List<MapDot>) {
        if (items.isEmpty()) return
        if (items.any { it.time <= 0L || it.species.isBlank() }) { showClusterOld(a, items); return }
        val groups = items.groupBy { dayKey.format(Date(it.time)) + "|" + it.who }.values
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
                val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
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
                setOnClickListener { showDay(a, g) }
            }
            val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(android.view.View(a).apply { background = Ui.bg(a, first.color, 99) },
                LinearLayout.LayoutParams(dp(a, 10), dp(a, 10)).apply { rightMargin = dp(a, 8) })
            top.addView(Ui.text(a, 15f, Ui.TEXT, true, dayLabel.format(Date(first.time)) + " · " +
                Tide.mulName(first.time).substringBefore(" (")), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(Ui.text(a, 16f, Ui.SUB, true, "›"))
            card.addView(top)
            card.addView(Ui.text(a, 14f, Ui.TEXT, false, (if (first.who.isNotBlank()) first.who + "  " else "") + speciesLine(g)),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(a, 4) })
            col.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(a, 8) })
        }
        dlg.show()
    }

    /** 하루 상세: 어종별 마릿수, 시간대, 물때·들물/날물·만조·간조 전후 */
    private fun showDay(a: Activity, g: List<MapDot>) {
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
                sb.append("\n")
            }
            tv.text = sb.toString().trimEnd()
        }
        Tide.fetch(a, first.point.latitude, first.point.longitude, first.time) { d, _ -> render(d) }
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

    private fun fit(map: MapView, all: List<GeoPoint>) {
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
        if (pts.size < 2) return "이동 거리: 위치 기록이 부족해요"
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
        }

        // 🦀 해루질 참고 구역 (맨 아래 · ☑ 일 때만)
        val zoneOv = Zones.ZoneOverlay(a) { Zones.on(a) }
        zoneOv.onTapZones = { Zones.showInfo(a, it, map) }
        map.overlays.add(zoneOv)
        // 지난 기록 (맨 아래, 반투명)
        val past = DotsOverlay(a, emptyList(), true) { showCluster(a, it) }
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
        root.addView(TextView(a).apply {
            text = title
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(a, 16), dp(a, 10), dp(a, 16), dp(a, 4))
        })

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
            (v.background as GradientDrawable).setColor(if (on) Color.parseColor("#2B4A5E") else Color.TRANSPARENT)
        }
        val bar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(a, 12), dp(a, 4), dp(a, 12), dp(a, 8))
        }
        fun addPill(v: TextView) = bar.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(a, 8) })
        // v3.9.1 (159): 맨 왼쪽 🔍 장소 검색
        addPill(MapSearch.pill(a, map))

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
        fun updateLegend() { legendView.text = legend + pastNote + (if (zoneNote.isNotBlank()) "\n$zoneNote" else "") + "\n지도 © OpenStreetMap · 위성 © Esri" }
        zoneOv.hint = { zoneNote = it; updateLegend() }
        updateLegend()

        var follow = live != null
        val followBtn = pill("내 위치 따라가기")
        if (live != null) {
            setOn(followBtn, true)
            followBtn.setOnClickListener {
                follow = !follow
                setOn(followBtn, follow)
                if (follow) boat.position?.let { map.controller.animateTo(it) }
            }
            addPill(followBtn)
        }

        if (liveMe != null) {
            val meBtn = pill("내 위치로")
            meBtn.setOnClickListener { liveMe.goToMe(meBtn) }
            addPill(meBtn)
        }

        // 내 위치 기록 확인 (테스트용 · 출조 아닐 때 · 앱이 켜져 있는 동안만)
        var testListener: (() -> Unit)? = null
        if (live == null) {
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

        val pastBtn = pill("지난 기록")
        var pastLoaded = false
        // 맨 위 한 줄: 지금 어떤 기록을 겹쳐 보는지 + 보기 바꾸기
        val pastBar = TextView(a).apply {
            textSize = 12f; setTextColor(Color.WHITE)
            setPadding(dp(a, 12), dp(a, 7), dp(a, 12), dp(a, 7))
            background = GradientDrawable().apply { setColor(Color.parseColor("#12293A")); cornerRadius = dp(a, 10).toFloat() }
            visibility = android.view.View.GONE
        }
        past.icons = true
        fun loadPastNow() {
            val mode = pastModeNow()
            pastBar.text = "지난 기록: " + pastModeLabel(mode) + "   보기 바꾸기 ▾"
            pastNote = "\n지난 기록 불러오는 중…"
            updateLegend()
            loadPast(excludeId, mode) { list, mine, others ->
                past.dots = list
                pastNote = "\n지난 기록 (내 조과 $mine · " + (if (mode == "public") "게시판" else "친구") + " 조과 $others)" +
                    " · 그림 = 많이 잡힌 어종 · 숫자 = 마릿수 · 분홍 테두리 = 친구 · 보라 = 게시판"
                updateLegend()
                map.invalidate()
            }
        }
        pastBar.setOnClickListener {
            val modes = mutableListOf("mine")
            Cloud.groups.forEach { modes.add("g:${it.id}") }
            if (Cloud.groups.size > 1) modes.add("allg")
            if (Cloud.user != null) modes.add("public")
            val cur = pastModeNow()
            AlertDialog.Builder(a, R.style.FlAlert)
                .setTitle("지난 기록 보기")
                .setItems(modes.map { (if (it == cur) "● " else "○ ") + pastModeLabel(it) }.toTypedArray()) { _, i ->
                    Store.pastMode = modes[i]
                    loadPastNow()
                }
                .show()
        }
        pastBtn.setOnClickListener {
            past.isEnabled = !past.isEnabled
            setOn(pastBtn, past.isEnabled)
            pastBar.visibility = if (past.isEnabled) android.view.View.VISIBLE else android.view.View.GONE
            if (past.isEnabled && !pastLoaded) {
                pastLoaded = true
                loadPastNow()
            } else {
                pastNote = if (past.isEnabled && pastLoaded) pastNote else ""
                updateLegend()
            }
            map.invalidate()
        }
        addPill(pastBtn)
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
        addPill(close)
        root.addView(Ui.hscroll(a, bar))
        // v3.9.2 (161) 해루질 참고용 커튼 (버튼 줄 바로 아래)
        root.addView(zBar.curtain, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(Ui.dp(a, 12), 0, Ui.dp(a, 12), Ui.dp(a, 6)) })
        root.addView(pastBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(Ui.dp(a, 12), 0, Ui.dp(a, 12), Ui.dp(a, 6)) })

        val frame = FrameLayout(a)
        frame.addView(map, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
        zoomButtons(a, frame, map, liveMe?.let { lm -> { v: TextView? -> lm.goToMe(v) } })
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (live != null) root.addView(liveInfo)
        root.addView(legendView)

        // 손으로 지도를 끌면 따라가기 잠시 끔
        map.setOnTouchListener { _, e ->
            if (live != null && follow && e.action == MotionEvent.ACTION_MOVE) {
                follow = false
                setOn(followBtn, false)
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

        map.post {
            val loc = Tracker.last
            if (live != null && loc != null) {
                map.controller.setZoom(16.0)
                map.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
            } else {
                fit(map, all)
            }
        }
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
            m == null -> Cloud.active?.let { "g:${it.id}" } ?: "mine"
            m.startsWith("g:") && gs.none { "g:${it.id}" == m } -> Cloud.active?.let { "g:${it.id}" } ?: "mine"
            else -> m
        }
    }

    private fun groupLabel(g: Cloud.Group) = g.title

    fun pastModeLabel(mode: String): String = when {
        mode == "mine" -> "내 기록만"
        mode == "allg" -> "내 기록 + 가입한 그룹 전부"
        mode == "public" -> "내 기록 + 전체 게시판 (최근 3개월)"
        mode.startsWith("g:") -> "내 기록 + " + (Cloud.groups.firstOrNull { "g:${it.id}" == mode }?.let { groupLabel(it) } ?: "그룹") + " 친구 기록"
        else -> mode
    }

    /** 지난 기록: 내 기록 + (고른 그룹 친구 / 전체 게시판). 점에는 어종 그림 */
    private fun loadPast(excludeId: Long?, mode: String, done: (List<MapDot>, Int, Int) -> Unit) {
        val df = SimpleDateFormat("yy.M.d", Locale.KOREA)
        val mine = Store.history.filter { it.id != excludeId }.take(150).flatMap { t ->
            val mul = Tide.mulName(t.start).substringBefore(" (")
            t.catches.filter { it.lat != null && it.lon != null }.map {
                MapDot(GeoPoint(it.lat!!, it.lon!!), slotCol(it.slot),
                    "나 · ${df.format(Date(it.time))} · ${t.name(it.slot)} · $mul", "나 ${t.name(it.slot)}",
                    it.time, "나", t.name(it.slot), paidMark(t.cat, t.paid), 0, if (it.q == 1 && it.acc > 0f) it.acc.toDouble() else 0.0, it.q == 1)
            }
        }
        val myId = Cloud.uid
        if (myId == null || mode == "mine") { done(mine, mine.size, 0); return }
        if (mode == "public") {
            PublicBoard.load(System.currentTimeMillis() - 92L * 86_400_000L) { list, _ ->
                val others = list.orEmpty().filter { it.owner != myId && !Moderation.isBlocked(it.owner) }.flatMap { t ->
                    val mul = Tide.mulName(t.start).substringBefore(" (")
                    t.catches.mapNotNull { c ->
                        val cell = c.cell ?: return@mapNotNull null
                        MapDot(GeoPoint(cell.lat, cell.lon), slotCol(c.slot),
                            "${t.nick} · ${df.format(Date(c.time))} · ${t.name(c.slot)} · $mul", "${t.nick} ${t.name(c.slot)}",
                            c.time, t.nick, t.name(c.slot), false, 2, if (t.exact) 0.0 else cell.r)
                    }
                }
                done(mine + others, mine.size, others.size)
            }
            return
        }
        val gs = if (mode == "allg") Cloud.groups else Cloud.groups.filter { "g:${it.id}" == mode }
        if (gs.isEmpty()) { done(mine, mine.size, 0); return }
        val got = mutableListOf<Cloud.RemoteTrip>()
        var left = gs.size
        gs.forEach { g ->
            Cloud.recentTripsOf(g) { list, _ ->
                list?.let { got.addAll(it) }
                left--
                if (left == 0) {
                    val friendsTrips = got.filter { it.owner != myId }.distinctBy { it.id }
                    val friends = friendsTrips.flatMap { t ->
                        val mul = Tide.mulName(t.start).substringBefore(" (")
                        t.catches.filter { it.lat != null && it.lon != null }.map {
                            MapDot(GeoPoint(it.lat!!, it.lon!!), slotCol(it.slot),
                                "${t.ownerName} · ${df.format(Date(it.time))} · ${t.name(it.slot)} · $mul", "${t.ownerName} ${t.name(it.slot)}",
                                it.time, t.ownerName, t.name(it.slot), paidMark(t.cat, t.paid), 1)
                        }
                    }
                    done(mine + friends, mine.size, friends.size)
                }
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
        val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(a, 12), dp(a, 4), dp(a, 12), dp(a, 8)) }
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
        }
        // 그 사람의 경로 + 잡은 자리 (관리자가 권한 준 사람만 받을 수 있음)
        fun route(w: Int, c: Int) = Polyline(map).apply {
            outlinePaint.color = c; outlinePaint.strokeWidth = dp(a, w).toFloat(); outlinePaint.isAntiAlias = true
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND; outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
        }
        val pEdge = route(7, ROUTE_EDGE); val pLine = route(4, ROUTE)
        val pDots = DotsOverlay(a, emptyList(), false) { showCluster(a, it) }.apply { icons = true; mergeDp = 34f }
        map.overlays.add(Zones.ZoneOverlay(a) { Zones.on(a) }.apply { onTapZones = { Zones.showInfo(a, it, map) } })   // v3.9.3
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
        val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(a, 12), dp(a, 4), dp(a, 12), dp(a, 8)) }
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
        pill("닫기") { dialog.dismiss() }
        root.addView(Ui.hscroll(a, bar))
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
        var stopMap: () -> Unit = {}
        if (uid != null) {
            val tf = SimpleDateFormat("HH:mm", Locale.KOREA)
            stopMap = Live.watchMap(uid, gids, pub) { m ->
                if (m == null) {
                    legend.text = info + "\n초록 점 = $name · 파란 점 = 지금 내 위치\n(이동 경로 · 잡은 자리는 관리자가 권한 준 사람만 보여요)\n지도 © OpenStreetMap · 위성 © Esri"
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
                legend.text = info + "\n지도에 보이는 조과: " + counts.ifBlank { "아직 없음" } +
                    "\n초록 점 = $name · 분홍 선 = 이동 경로 · 숫자 = 그 자리 마릿수 · 파란 점 = 내 위치\n지도 © OpenStreetMap · 위성 © Esri"
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
