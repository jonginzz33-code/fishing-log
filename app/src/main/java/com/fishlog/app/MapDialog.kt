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
class MapCatch(val point: GeoPoint, val color: Int, val title: String, val kind: String = "")

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

    private fun toDots(cs: List<MapCatch>) = cs.map { MapDot(it.point, it.color, it.title, it.kind.ifBlank { it.title }) }

    /** 묶음 점을 눌렀을 때 */
    private fun showCluster(a: Activity, items: List<MapDot>) {
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
        val dots = DotsOverlay(a, dotList, false) { showCluster(a, it) }
        map.overlays.add(dots)
        if (live != null) map.overlays.add(boat)

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
                            if (ageSec > 30) " · 위치 ${ageSec}초 전" else "")
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
                    "나 · ${df.format(Date(it.time))} · ${t.name(it.slot)} · $mul", "나 ${t.name(it.slot)}")
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
                        "${t.ownerName} · ${df.format(Date(it.time))} · ${t.name(it.slot)} · $mul", "${t.ownerName} ${t.name(it.slot)}")
                }
            }
            done(mine + friends, mine.size, friends.size)
        }
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
                df.format(Date(it.time)) + " " + t.name(it.slot), t.name(it.slot))
        }
        return MapLayer(trackColor, track, catches)
    }

    fun showTrip(a: Activity, t: Store.Trip) {
        val df = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)
        val withLoc = t.catches.count { it.lat != null }
        val isLive = Store.current?.id == t.id
        val legend = "● 주황 ${t.name(1)} · ● 하늘 ${t.name(2)} · 흰 선 이동 경로" +
            (if (isLive) " · 배 = 지금 내 위치" else "") +
            "\n위치가 있는 조과 $withLoc / ${t.catches.size} · 숫자 = 겹친 마릿수 · 점을 누르면 상세"
        if (isLive) show(a, df.format(Date(t.start)) + " 출조 중 (실시간)", emptyList(), legend, live = t, excludeId = t.id)
        else show(a, df.format(Date(t.start)) + " 출조", listOf(layerOf(t)), legend, excludeId = t.id)
    }
}
