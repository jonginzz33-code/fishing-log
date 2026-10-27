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
               val time: Long = 0L, val who: String = "", val species: String = "")

object MapDialog {

    private var configured = false

    val SLOT_COLORS = intArrayOf(Color.parseColor("#F28C38"), Color.parseColor("#5BC8DF"))
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

    private fun configure(a: Activity) {
        if (configured) return
        val c = Configuration.getInstance()
        c.userAgentValue = a.packageName
        c.osmdroidBasePath = File(a.filesDir, "osmdroid")
        c.osmdroidTileCache = File(a.cacheDir, "osmdroid-tiles")
        configured = true
    }

    private fun dp(a: Context, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    private fun toDots(cs: List<MapCatch>) = cs.map { MapDot(it.point, it.color, it.title, it.kind.ifBlank { it.title }, it.time, it.who, it.species) }

    private val dayKey = SimpleDateFormat("yyyyMMdd", Locale.KOREA)
    private val dayLabel = SimpleDateFormat("M월 d일 (E)", Locale.KOREA)
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
        val dlg = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("${items.size}마리 · ${days}일 · " + speciesLine(items))
            .setView(android.widget.ScrollView(a).apply { addView(col) })
            .setPositiveButton("닫기", null)
            .create()
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
        AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        val b = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title)
        if (items.size > 1) b.setItems(items.map { it.title }.sorted().toTypedArray(), null)
        b.setPositiveButton("닫기", null).show()
    }

    private fun fit(map: MapView, all: List<GeoPoint>) {
        when {
            all.isEmpty() -> { map.controller.setZoom(7.0); map.controller.setCenter(GeoPoint(36.3, 127.8)) }
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
        val dialog = Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        val handler = Handler(Looper.getMainLooper())

        val map = MapView(a).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            setTileSource(if (Store.satellite) satelliteSource else TileSourceFactory.MAPNIK)
            isTilesScaledToDpi = true
        }

        // 지난 기록 (맨 아래, 반투명)
        val past = DotsOverlay(a, emptyList(), true) { showCluster(a, it) }
        past.isEnabled = false
        map.overlays.add(past)

        val all = mutableListOf<GeoPoint>()
        val dotList = mutableListOf<MapDot>()
        layers.forEach { layer ->
            if (layer.track.size >= 2) {
                val line = Polyline(map)
                line.setPoints(layer.track)
                line.outlinePaint.color = layer.color
                line.outlinePaint.strokeWidth = dp(a, 4).toFloat()
                line.outlinePaint.isAntiAlias = true
                map.overlays.add(line)
            }
            all.addAll(layer.track)
            all.addAll(layer.catches.map { it.point })
            dotList.addAll(toDots(layer.catches))
        }

        // 실시간
        val liveLine = Polyline(map).apply {
            outlinePaint.color = Color.parseColor("#EAF2F6")
            outlinePaint.strokeWidth = dp(a, 4).toFloat()
            outlinePaint.isAntiAlias = true
        }
        val boat = BoatOverlay(a)
        if (live != null) {
            map.overlays.add(liveLine)
            val l = layerOf(live)
            liveLine.setPoints(l.track)
            dotList.addAll(toDots(l.catches))
            all.addAll(l.track); all.addAll(l.catches.map { it.point })
        }
        // 출발 지점 (초록)
        val startPt = (live?.let { layerOf(it).track.firstOrNull() }) ?: layers.firstOrNull()?.track?.firstOrNull()
        if (startPt != null) map.overlays.add(DotsOverlay(a, listOf(MapDot(startPt, Color.parseColor("#5FD39A"), "출발 지점", "출발")), false, null))
        val dots = DotsOverlay(a, dotList, false) { showCluster(a, it) }
        map.overlays.add(dots)
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
        fun updateLegend() { legendView.text = legend + pastNote + "\n지도 © OpenStreetMap · 위성 © Esri" }
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

        val pastBtn = pill("지난 기록 겹치기")
        var pastLoaded = false
        pastBtn.setOnClickListener {
            past.isEnabled = !past.isEnabled
            setOn(pastBtn, past.isEnabled)
            if (past.isEnabled && !pastLoaded) {
                pastLoaded = true
                pastNote = "\n지난 기록 불러오는 중…"
                updateLegend()
                loadPast(excludeId) { list, mine, friends ->
                    past.dots = list
                    pastNote = "\n반투명 = 지난 기록 (내 조과 $mine · 친구 조과 $friends) · 친구는 사람마다 색이 달라요"
                    updateLegend()
                    map.invalidate()
                }
            } else {
                pastNote = if (past.isEnabled && pastLoaded) pastNote else ""
                updateLegend()
            }
            map.invalidate()
        }
        addPill(pastBtn)

        val toggle = pill(if (Store.satellite) "지도로 보기" else "위성으로 보기")
        toggle.setOnClickListener {
            Store.satellite = !Store.satellite
            map.setTileSource(if (Store.satellite) satelliteSource else TileSourceFactory.MAPNIK)
            toggle.text = if (Store.satellite) "지도로 보기" else "위성으로 보기"
        }
        addPill(toggle)
        val close = pill("닫기")
        close.setOnClickListener { dialog.dismiss() }
        addPill(close)
        root.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(bar) })

        val frame = FrameLayout(a)
        frame.addView(map, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (all.isEmpty() && live == null) {
            frame.addView(TextView(a).apply {
                text = "아직 위치 기록이 없어요\n출조 중에 위치 권한을 허용하면 경로와 조과 위치가 표시돼요"
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 12))
                background = GradientDrawable().apply { setColor(Color.argb(200, 11, 26, 38)); cornerRadius = dp(a, 12).toFloat() }
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        if (liveMe != null) addStatus(a, frame, liveMe)
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
                    liveLine.setPoints(pts)
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
                            if (ageSec > 30) " · 위치 ${ageSec}초 전" else "") + "\n" + distanceText(pts)
                    } else {
                        liveInfo.text = "GPS 위치를 찾는 중… (위치 권한·GPS 켜짐 확인)"
                    }
                    map.invalidate()
                    handler.postDelayed(this, 1000)
                }
            }
        }

        dialog.setContentView(root)
        dialog.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
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
    private fun loadPast(excludeId: Long?, done: (List<MapDot>, Int, Int) -> Unit) {
        val df = SimpleDateFormat("yy.M.d", Locale.KOREA)
        val mine = Store.history.filter { it.id != excludeId }.take(150).flatMap { t ->
            val mul = Tide.mulName(t.start).substringBefore(" (")
            t.catches.filter { it.lat != null && it.lon != null }.map {
                MapDot(GeoPoint(it.lat!!, it.lon!!), SLOT_COLORS[it.slot - 1],
                    "나 · ${df.format(Date(it.time))} · ${t.name(it.slot)} · $mul", "나 ${t.name(it.slot)}",
                    it.time, "나", t.name(it.slot))
            }
        }
        val myId = Cloud.uid
        if (myId == null || Cloud.active == null) { done(mine, mine.size, 0); return }
        Cloud.recentTrips { list, _ ->
            val friendsTrips = list?.filter { it.owner != myId } ?: emptyList()
            val owners = friendsTrips.map { it.owner }.distinct()
            val friends = friendsTrips.flatMap { t ->
                val color = FRIEND_COLORS[owners.indexOf(t.owner) % FRIEND_COLORS.size]
                val mul = Tide.mulName(t.start).substringBefore(" (")
                t.catches.filter { it.lat != null && it.lon != null }.map {
                    MapDot(GeoPoint(it.lat!!, it.lon!!), color,
                        "${t.ownerName} · ${df.format(Date(it.time))} · ${t.name(it.slot)} · $mul", "${t.ownerName} ${t.name(it.slot)}",
                        it.time, t.ownerName, t.name(it.slot))
                }
            }
            done(mine + friends, mine.size, friends.size)
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
        val dialog = Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        val map = MapView(a).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            setTileSource(if (Store.satellite) satelliteSource else TileSourceFactory.MAPNIK)
            isTilesScaledToDpi = true
        }
        addCircles(a, map, dots, radiusOf)
        map.overlays.add(DotsOverlay(a, dots, false) { showCluster(a, it) }.apply { mergeDp = 34f })
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
        pill("내 위치로") { v -> liveMe.goToMe(v) }
        pill("나와 포인트 같이") { _ ->
            val p = liveMe.position
            if (p == null) android.widget.Toast.makeText(a, "아직 내 위치를 못 잡았어요", android.widget.Toast.LENGTH_SHORT).show()
            else LiveMe.fitMeAnd(map, p, targets)
        }
        pill("포인트 전체") { fit(map, dots.map { it.point }) }
        pill(if (Store.satellite) "지도로 보기" else "위성으로 보기") { v ->
            Store.satellite = !Store.satellite
            map.setTileSource(if (Store.satellite) satelliteSource else TileSourceFactory.MAPNIK)
            v.text = if (Store.satellite) "지도로 보기" else "위성으로 보기"
        }
        pill("닫기") { dialog.dismiss() }
        root.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(bar) })
        val frame = FrameLayout(a)
        frame.addView(map, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addStatus(a, frame, liveMe)
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(TextView(a).apply {
            text = legend + "\n파란 점 = 지금 내 위치 (뾰족한 쪽 = 폰이 향한 방향) · 점선 = $targetName 까지\n지도 © OpenStreetMap · 위성 © Esri"
            textSize = 12f; setTextColor(Color.parseColor("#9FB4C2"))
            setPadding(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 12))
        })
        dialog.setContentView(root)
        dialog.setOnDismissListener { liveMe.stop(); map.onPause(); map.onDetach() }
        dialog.show()
        map.onResume()
        map.post { fit(map, dots.map { it.point }) }
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
        map.overlays.add(DotsOverlay(a, dots, false) { showCluster(a, it) })
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
            val line = Polyline(map)
            line.setPoints(layer.track)
            line.outlinePaint.color = layer.color
            line.outlinePaint.strokeWidth = dp(a, 3).toFloat()
            map.overlays.add(line)
        }
        all.addAll(layer.track)
        all.addAll(layer.catches.map { it.point })
        map.overlays.add(DotsOverlay(a, toDots(layer.catches), false, null))
        map.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) onTap()
            true
        }
        map.addOnFirstLayoutListener { _, _, _, _, _ -> fit(map, all) }
        return map
    }

    // ---- 내 출조를 지도 레이어로 ----
    fun layerOf(t: Store.Trip, trackColor: Int = Color.parseColor("#EAF2F6")): MapLayer {
        val df = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
        val track = t.track.map { GeoPoint(it.lat, it.lon) }
        val catches = t.catches.filter { it.lat != null && it.lon != null }.map {
            MapCatch(GeoPoint(it.lat!!, it.lon!!), SLOT_COLORS[it.slot - 1],
                df.format(Date(it.time)) + " " + t.name(it.slot), t.name(it.slot), it.time, "", t.name(it.slot))
        }
        return MapLayer(trackColor, track, catches)
    }

    fun showTrip(a: Activity, t: Store.Trip) {
        val df = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)
        val withLoc = t.catches.count { it.lat != null }
        val isLive = Store.current?.id == t.id
        val legend = distanceText(t.track.map { GeoPoint(it.lat, it.lon) }) + "\n" +
            "● 주황 ${t.name(1)} · ● 하늘 ${t.name(2)} · 초록 = 출발 · 흰 선 이동 경로" +
            (if (isLive) " · 배 = 지금 내 위치" else "") +
            "\n위치가 있는 조과 $withLoc / ${t.catches.size} · 숫자 = 겹친 마릿수 · 점을 누르면 상세"
        if (isLive) show(a, df.format(Date(t.start)) + " 출조 중 (실시간)", emptyList(), legend, live = t, excludeId = t.id)
        else show(a, df.format(Date(t.start)) + " 출조", listOf(layerOf(t)), legend, excludeId = t.id)
    }
}
