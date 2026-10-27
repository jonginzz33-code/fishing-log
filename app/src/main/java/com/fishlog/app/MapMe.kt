package com.fishlog.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.view.View
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.util.Locale

/**
 * 모든 지도에 "지금 내 위치"를 보여주기
 *  - 지도를 여는 순간부터 GPS로 계속 따라감 (파란 점 + 폰이 향한 방향)
 *  - 목적지(포인트)가 있으면 점선 + "북동쪽 320m"
 *  - 처음 위치가 잡히면 내 위치와 포인트가 한 화면에 들어오게 맞춤
 *  - 지도가 화면에서 사라지면 자동으로 GPS를 끔
 */
class LiveMe(private val a: Activity, private val map: MapView, val me: MyLocOverlay, val nav: NavOverlay?) {

    /** null = 잘 잡히는 중, 글자 = 위치를 못 쓰는 이유 */
    var onStatus: ((String?) -> Unit)? = null
        set(v) { field = v; v?.invoke(lastStatus) }
    /** 마지막 안내 (나중에 붙인 화면에도 보여주려고) */
    var lastStatus: String? = null
        private set

    private fun status(msg: String?) { lastStatus = msg; onStatus?.invoke(msg) }

    /** "내 위치" 한 번 잡은 결과를 쓰고 계속 따라가기 시작 */
    fun accept(l: Location) { onLoc(l); start() }
    /** 처음 위치가 잡혔을 때 */
    var onFirstFix: ((GeoPoint) -> Unit)? = null

    var position: GeoPoint? = null
        private set
    private var lm: LocationManager? = null
    private var sm: SensorManager? = null
    private var running = false
    private var gotFix = false
    private var lastGpsTime = 0L

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = onLoc(loc)
        @Deprecated("old api")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val rot = FloatArray(9)
    private val ori = FloatArray(3)
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rot, e.values)
            SensorManager.getOrientation(rot, ori)
            val deg = ((Math.toDegrees(ori[0].toDouble()) + 360) % 360).toFloat()
            val prev = me.heading
            if (prev == null || angleDiff(prev, deg) > 4f) { me.heading = deg; map.invalidate() }
        }
        override fun onAccuracyChanged(s: Sensor?, acc: Int) {}
    }

    private fun angleDiff(x: Float, y: Float): Float { val d = ((x - y) % 360 + 360) % 360; return if (d > 180) 360 - d else d }

    private fun onLoc(loc: Location) {
        // GPS가 잘 잡히는 동안엔 덜 정확한 네트워크 위치는 무시
        if (loc.provider == LocationManager.GPS_PROVIDER) lastGpsTime = loc.time
        else if (loc.time - lastGpsTime < 20_000L) return
        val p = GeoPoint(loc.latitude, loc.longitude)
        position = p
        me.position = p
        me.accuracy = if (loc.hasAccuracy()) loc.accuracy else 0f
        nav?.from = p
        status(null)
        map.invalidate()
        if (!gotFix) { gotFix = true; onFirstFix?.invoke(p) }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        if (!Tracker.hasPermission(a)) { status("내 위치를 보려면 위치 권한을 허용해 주세요 (눌러서 허용)"); return }
        val m = a.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = m
        val gpsOn = try { m.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (_: Exception) { false }
        val netOn = try { m.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { false }
        if (!gpsOn && !netOn) { status("위치(GPS)가 꺼져 있어요 · 눌러서 켜기"); return }
        running = true
        // 바로 보이게: 최근(10분 안)에 알던 위치 먼저
        val now = System.currentTimeMillis()
        val known = (listOfNotNull(Tracker.last) + listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { p -> try { m.getLastKnownLocation(p) } catch (_: Exception) { null } })
            .filter { now - it.time < 10 * 60_000L }.maxByOrNull { it.time }
        known?.let { onLoc(it) }
        if (known == null) status("내 위치 찾는 중…")
        try {
            if (gpsOn) m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 0f, listener, Looper.getMainLooper())
            if (netOn) m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5_000L, 0f, listener, Looper.getMainLooper())
        } catch (_: Exception) {}
        val s = a.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm = s
        s.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { s.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        if (!running) return
        running = false
        try { lm?.removeUpdates(listener) } catch (_: Exception) {}
        try { sm?.unregisterListener(sensorListener) } catch (_: Exception) {}
    }

    /** 내 위치로 지도 옮기기. 아직 못 잡았으면 한 번 새로 잡기 */
    fun goToMe(btn: android.widget.TextView?) {
        val p = position
        if (p != null) {
            if (map.zoomLevelDouble < 14.0) map.controller.setZoom(15.0)
            map.controller.animateTo(p)
            return
        }
        val label = btn?.text
        btn?.text = "위치 찾는 중…"
        LocOnce.get(a) { l, err ->
            btn?.text = label
            if (l == null) { android.widget.Toast.makeText(a, err ?: "위치를 찾지 못했어요", android.widget.Toast.LENGTH_LONG).show(); return@get }
            accept(l)
            if (map.zoomLevelDouble < 14.0) map.controller.setZoom(15.0)
            map.controller.animateTo(GeoPoint(l.latitude, l.longitude))
        }
    }

    companion object {
        /**
         * 지도에 내 위치 붙이기. targets = 길 안내할 자리들 (가장 가까운 곳으로 점선)
         * fitWith = 처음 위치가 잡혔을 때 같이 보이게 할 점들 (비우면 targets)
         */
        fun attach(a: Activity, map: MapView, targets: List<GeoPoint>, targetName: String = "포인트",
                   fitWith: List<GeoPoint> = targets, autoFit: Boolean = true): LiveMe {
            val me = MyLocOverlay(a)
            val nav = if (targets.isNotEmpty()) NavOverlay(a, targets, targetName) else null
            nav?.let { map.overlays.add(it) }
            map.overlays.add(me)
            val live = LiveMe(a, map, me, nav)
            if (autoFit) live.onFirstFix = { p -> fitMeAnd(map, p, fitWith) }
            map.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) { live.stop() }
            })
            live.start()
            return live
        }

        /** 내 위치와 포인트가 한 화면에 (30km 넘게 떨어져 있으면 포인트만) */
        fun fitMeAnd(map: MapView, me: GeoPoint, pts: List<GeoPoint>) {
            if (pts.isEmpty()) return
            val nearest = pts.minOf { it.distanceToAsDouble(me) }
            if (nearest > 30_000) return
            val all = pts + me
            // 지도가 먼저 포인트를 보여준 다음에 (화면 크기가 정해진 뒤) 나까지 넓혀 보기
            map.postDelayed({
                if (map.width == 0 || map.height == 0) return@postDelayed
                val box = BoundingBox.fromGeoPoints(all)
                if (box.latitudeSpan < 0.002 && box.longitudeSpan < 0.002) {
                    map.controller.setZoom(17.0); map.controller.setCenter(box.centerWithDateLine)
                } else {
                    map.zoomToBoundingBox(box.increaseByScale(1.4f), true)
                }
            }, 700L)
        }

        fun directionName(deg: Double): String {
            val names = arrayOf("북", "북동", "동", "남동", "남", "남서", "서", "북서")
            return names[((((deg % 360) + 360) % 360 + 22.5) / 45.0).toInt() % 8]
        }

        fun distText(m: Double): String =
            if (m < 1000) String.format(Locale.KOREA, "%.0fm", m) else String.format(Locale.KOREA, "%.1fkm", m / 1000)
    }
}

/** 지금 내 위치: 파란 점 + 정확도 범위 + 폰이 향한 방향 */
class MyLocOverlay(ctx: Context) : Overlay() {
    var position: GeoPoint? = null
    var accuracy = 0f
    /** 폰이 향한 방향 (북쪽 0°, 시계방향). null = 모름 */
    var heading: Float? = null
    private val d = ctx.resources.displayMetrics.density
    private val acc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(55, 66, 133, 244) }
    private val accEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1 * d; color = Color.argb(140, 66, 133, 244) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4285F4") }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val cone = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 66, 133, 244) }
    private val pt = Point()

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val p = position ?: return
        map.projection.toPixels(p, pt)
        val x = pt.x.toFloat(); val y = pt.y.toFloat()
        if (accuracy > 0) {
            val ar = map.projection.metersToPixels(accuracy)
            if (ar > 10 * d) { c.drawCircle(x, y, ar, acc); c.drawCircle(x, y, ar, accEdge) }
        }
        heading?.let { h ->
            c.save()
            c.rotate(h, x, y)
            val path = android.graphics.Path().apply {
                moveTo(x, y - 26 * d)
                lineTo(x + 11 * d, y - 6 * d)
                lineTo(x - 11 * d, y - 6 * d)
                close()
            }
            c.drawPath(path, cone)
            c.restore()
        }
        c.drawCircle(x, y, 10 * d, white)
        c.drawCircle(x, y, 7 * d, dot)
    }
}

/** 내 위치 → 가장 가까운 포인트까지 점선 + "북동쪽 320m" 글씨 (지도 위쪽) */
class NavOverlay(ctx: Context, private val targets: List<GeoPoint>, private val name: String) : Overlay() {
    var from: GeoPoint? = null
    private val d = ctx.resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * d; color = Color.parseColor("#FFFFFF")
        pathEffect = DashPathEffect(floatArrayOf(10 * d, 7 * d), 0f); strokeCap = Paint.Cap.ROUND
    }
    private val lineEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5 * d; color = Color.argb(150, 11, 26, 38)
        pathEffect = DashPathEffect(floatArrayOf(10 * d, 7 * d), 0f); strokeCap = Paint.Cap.ROUND
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 11, 26, 38) }
    private val boxEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Color.parseColor("#4285F4") }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 16 * d; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val p1 = Point(); private val p2 = Point()

    /** 가장 가까운 포인트, 방향(도), 거리(m) */
    fun nearest(): Triple<GeoPoint, Double, Double>? {
        val f = from ?: return null
        val t = targets.minByOrNull { it.distanceToAsDouble(f) } ?: return null
        return Triple(t, f.bearingTo(t), f.distanceToAsDouble(t))
    }

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val (t, bearing, dist) = nearest() ?: return
        val f = from ?: return
        map.projection.toPixels(f, p1)
        map.projection.toPixels(t, p2)
        c.drawLine(p1.x.toFloat(), p1.y.toFloat(), p2.x.toFloat(), p2.y.toFloat(), lineEdge)
        c.drawLine(p1.x.toFloat(), p1.y.toFloat(), p2.x.toFloat(), p2.y.toFloat(), line)
        // 지도 위쪽 가운데에 글씨 (화면 좌표)
        val label = if (dist < 15) "$name 도착 (${LiveMe.distText(dist)} 안)"
            else "${name}까지 ${LiveMe.directionName(bearing)}쪽 ${LiveMe.distText(dist)}"
        val w = txt.measureText(label) + 28 * d
        val cx = map.width / 2f
        val top = 10 * d
        val r = RectF(cx - w / 2, top, cx + w / 2, top + 36 * d)
        c.drawRoundRect(r, 18 * d, 18 * d, boxPaint)
        c.drawRoundRect(r, 18 * d, 18 * d, boxEdge)
        c.drawText(label, cx, r.centerY() - (txt.descent() + txt.ascent()) / 2, txt)
    }
}
