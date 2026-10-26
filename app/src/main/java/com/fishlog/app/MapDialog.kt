package com.fishlog.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
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
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

/** 지도에 그릴 한 사람(또는 한 출조)의 경로와 조과 */
class MapLayer(val color: Int, val track: List<GeoPoint>, val catches: List<MapCatch>)
class MapCatch(val point: GeoPoint, val color: Int, val title: String)

object MapDialog {

    private var configured = false

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

    private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    private fun dot(a: Activity, color: Int, sizeDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(a, 2), Color.parseColor("#0B1A26"))
            setSize(dp(a, sizeDp), dp(a, sizeDp))
        }

    fun show(a: Activity, title: String, layers: List<MapLayer>, legend: String) {
        configure(a)
        val dialog = Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)

        val map = MapView(a).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            setTileSource(if (Store.satellite) satelliteSource else TileSourceFactory.MAPNIK)
            isTilesScaledToDpi = true
        }

        val all = mutableListOf<GeoPoint>()
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
            layer.catches.forEach { c ->
                val m = Marker(map)
                m.position = c.point
                m.icon = dot(a, c.color, 14)
                m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                m.title = c.title
                map.overlays.add(m)
                all.add(c.point)
            }
        }

        // ---- 화면 구성 ----
        val bg = Color.parseColor("#0B1A26")
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val bar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(a, 16), dp(a, 10), dp(a, 8), dp(a, 10))
        }
        bar.addView(TextView(a).apply {
            text = title
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

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
        val toggle = pill(if (Store.satellite) "지도로 보기" else "위성으로 보기")
        toggle.setOnClickListener {
            Store.satellite = !Store.satellite
            map.setTileSource(if (Store.satellite) satelliteSource else TileSourceFactory.MAPNIK)
            toggle.text = if (Store.satellite) "지도로 보기" else "위성으로 보기"
        }
        val close = pill("닫기")
        close.setOnClickListener { dialog.dismiss() }
        bar.addView(toggle)
        bar.addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(a, 8) })
        root.addView(bar)

        val frame = FrameLayout(a)
        frame.addView(map, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (all.isEmpty()) {
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

        root.addView(TextView(a).apply {
            text = legend + "\n지도 © OpenStreetMap · 위성 © Esri"
            textSize = 12f
            setTextColor(Color.parseColor("#9FB4C2"))
            setPadding(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 12))
        })

        dialog.setContentView(root)
        dialog.setOnDismissListener { map.onPause(); map.onDetach() }
        dialog.show()
        map.onResume()

        // 모든 점이 보이게 확대
        map.post {
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
    }

    private fun fit(map: MapView, all: List<GeoPoint>) {
        when {
            all.isEmpty() -> { map.controller.setZoom(7.0); map.controller.setCenter(GeoPoint(36.3, 127.8)) }
            all.size == 1 -> { map.controller.setZoom(15.0); map.controller.setCenter(all[0]) }
            else -> {
                val box = BoundingBox.fromGeoPoints(all)
                if (box.latitudeSpan < 0.002 && box.longitudeSpan < 0.002) {
                    map.controller.setZoom(16.0); map.controller.setCenter(box.centerWithDateLine)
                } else {
                    map.zoomToBoundingBox(box.increaseByScale(1.4f), false)
                }
            }
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
        layer.catches.forEach { c ->
            val m = Marker(map)
            m.position = c.point
            m.icon = dot(a, c.color, 12)
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            m.setOnMarkerClickListener { _, _ -> onTap(); true }
            map.overlays.add(m)
            all.add(c.point)
        }
        map.setOnTouchListener { _, e ->
            if (e.action == android.view.MotionEvent.ACTION_UP) onTap()
            true
        }
        map.addOnFirstLayoutListener { _, _, _, _, _ -> fit(map, all) }
        return map
    }

    // ---- 내 출조를 지도 레이어로 ----
    val SLOT_COLORS = intArrayOf(Color.parseColor("#F28C38"), Color.parseColor("#5BC8DF"))

    fun layerOf(t: Store.Trip, trackColor: Int = Color.parseColor("#EAF2F6")): MapLayer {
        val df = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA)
        val track = t.track.map { GeoPoint(it.lat, it.lon) }
        val catches = t.catches.filter { it.lat != null && it.lon != null }.map {
            MapCatch(GeoPoint(it.lat!!, it.lon!!), SLOT_COLORS[it.slot - 1], df.format(java.util.Date(it.time)) + " " + t.name(it.slot))
        }
        return MapLayer(trackColor, track, catches)
    }

    fun showTrip(a: Activity, t: Store.Trip) {
        val df = java.text.SimpleDateFormat("M월 d일 HH:mm", java.util.Locale.KOREA)
        val withLoc = t.catches.count { it.lat != null }
        show(
            a, df.format(java.util.Date(t.start)) + " 출조",
            listOf(layerOf(t)),
            "● 주황 ${t.name(1)} ${t.count(1)} · ● 하늘 ${t.name(2)} ${t.count(2)} · 흰 선 이동 경로\n" +
                "위치가 있는 조과 $withLoc / ${t.catches.size} · 점을 누르면 시각이 보여요"
        )
    }
}
