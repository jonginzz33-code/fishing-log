package com.fishlog.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

/**
 * 지도 '내 위치 기록 확인' (테스트용, 출조와 상관없음)
 *  - 버튼을 눌러야만 켜짐 · 앱이 화면에 켜져 있는 동안만 기록
 *  - 앱을 최소화하거나 닫으면 바로 꺼지고 그린 길도 지움 (다시 켜면 처음부터)
 *  - 폰에만, 출조 기록·그룹·서버엔 안 올라감
 */
object TestTrack {
    class P(val time: Long, val lat: Double, val lon: Double)

    val points = ArrayList<P>()
    var on = false
        private set
    var startedAt = 0L
        private set
    private var lm: LocationManager? = null
    private val listeners = ArrayList<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun fire() = listeners.toList().forEach { it() }

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (!on) return
            if (loc.hasAccuracy() && loc.accuracy > 60f) return
            val last = points.lastOrNull()
            if (last != null) {
                val d = FloatArray(1)
                Location.distanceBetween(last.lat, last.lon, loc.latitude, loc.longitude, d)
                if (d[0] < 3f) return
            }
            points.add(P(loc.time, loc.latitude, loc.longitude))
            fire()
        }
        @Deprecated("old") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    fun start(c: Context): String? {
        if (on) return null
        if (!Store.appLoc) return "앱에서 내 위치 사용이 꺼져 있어요 (⚙ 개인 설정에서 켜기)"
        if (!Tracker.hasPermission(c)) return "위치 권한을 허용해 주세요"
        val m = c.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsOn = try { m.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (_: Exception) { false }
        if (!gpsOn) return "위치(GPS)가 꺼져 있어요"
        lm = m; on = true; startedAt = System.currentTimeMillis(); points.clear()
        try { m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 0f, listener, Looper.getMainLooper()) } catch (_: Exception) {}
        fire()
        return null
    }

    fun stop() {
        if (!on) return
        on = false
        try { lm?.removeUpdates(listener) } catch (_: Exception) {}
        fire()
    }

    fun clear() { points.clear(); if (on) startedAt = System.currentTimeMillis(); fire() }

    /** 거리(m) */
    fun meters(): Double {
        var s = 0.0
        val d = FloatArray(1)
        for (i in 1 until points.size) {
            Location.distanceBetween(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon, d); s += d[0]
        }
        return s
    }

    /** 앱이 화면에서 사라지면(최소화·종료) 자동으로 끄고 지움 */
    private var started = 0
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { started++ }
            override fun onActivityStopped(a: Activity) {
                started--
                if (started <= 0 && !a.isChangingConfigurations) { started = 0; stop(); points.clear() }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
