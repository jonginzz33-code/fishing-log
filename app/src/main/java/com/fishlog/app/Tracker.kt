package com.fishlog.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

/**
 * 출조 중 GPS 위치를 받아서
 *  - 1분 또는 30m 이동마다 이동 경로에 점을 저장하고
 *  - 조과를 기록할 때 쓸 "가장 최근 위치"를 들고 있음
 */
object Tracker {

    var last: Location? = null
        private set
    private var lastSaved: Location? = null
    private var lm: LocationManager? = null
    var running = false
        private set

    private const val SAVE_EVERY_MS = 50_000L
    private const val SAVE_EVERY_M = 30f
    private const val RECENT_MS = 10 * 60_000L

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** 조과 기록에 붙일 위치 (10분 넘게 지난 위치는 안 씀) */
    fun recent(now: Long): Location? = last?.takeIf { now - it.time < RECENT_MS }

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = onLocation(loc)
        @Deprecated("old api")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun onLocation(loc: Location) {
        // 기지국 위치(정확도 나쁨)가 GPS 위치를 덮어쓰지 않게
        val prev = last
        if (prev != null && loc.provider != LocationManager.GPS_PROVIDER &&
            prev.provider == LocationManager.GPS_PROVIDER && loc.time - prev.time < 120_000L
        ) return
        last = loc
        val saved = lastSaved
        if (saved == null || loc.time - saved.time >= SAVE_EVERY_MS || loc.distanceTo(saved) >= SAVE_EVERY_M) {
            lastSaved = loc
            Store.addTrackPoint(loc.time, loc.latitude, loc.longitude)
        }
    }

    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        if (running || !hasPermission(ctx)) return
        val m = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = m
        try {
            if (m.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 20_000L, 10f, listener, Looper.getMainLooper())
                m.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { if (System.currentTimeMillis() - it.time < 120_000L) last = it }
            }
            if (m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60_000L, 30f, listener, Looper.getMainLooper())
            }
            running = true
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    fun stop() {
        try { lm?.removeUpdates(listener) } catch (_: Exception) {}
        running = false
        lastSaved = null
    }
}
