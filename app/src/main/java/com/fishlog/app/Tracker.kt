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
 * 출조 중 GPS
 *  - 움직일 땐 20초, 멈춰 있으면 90초, 절약 모드 3분, 지도 실시간 보기 중엔 3초 간격
 *  - 이동 경로 점 저장, 조과에 붙일 최근 위치 보관
 */
object Tracker {

    var last: Location? = null
        private set
    private var lastSaved: Location? = null
    private var lm: LocationManager? = null
    private var appCtx: Context? = null
    var running = false
        private set
    private var interval = 0L

    /** 지도 실시간 보기가 열려 있는 동안 true */
    var live = false
        set(v) { field = v; reapply() }

    /** 지도에 새 위치 알려주기 */
    var onFix: ((Location) -> Unit)? = null

    var moving = true
        private set
    private var anchor: Location? = null
    private var anchorTime = 0L

    private const val RECENT_MS = 10 * 60_000L

    /** 위치를 써도 되는지 = 폰 권한 허용 + 앱 설정 '이 앱에서 내 위치 사용' 켜짐 */
    fun hasPermission(ctx: Context) = granted(ctx) && Store.appLoc

    /** 폰 위치 권한만 (앱 설정과 상관없이) */
    fun granted(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** 폰 위치(GPS)가 켜져 있는지 */
    fun gpsOn(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager).isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
    } catch (_: Exception) { false }

    fun recent(now: Long): Location? = if (!Store.appLoc) null else last?.takeIf { now - it.time < RECENT_MS }

    private fun desiredInterval(): Long = when {
        live -> 3_000L
        Store.saver -> 180_000L
        moving -> 20_000L
        else -> 90_000L
    }

    private fun saveEvery(): Long = when {
        live -> 20_000L
        Store.saver -> 180_000L
        moving -> 50_000L
        else -> 120_000L
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = onLocation(loc)
        @Deprecated("old api")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun onLocation(loc: Location) {
        val prev = last
        if (prev != null && loc.provider != LocationManager.GPS_PROVIDER &&
            prev.provider == LocationManager.GPS_PROVIDER && loc.time - prev.time < 120_000L
        ) return
        last = loc

        // 움직이는지 판단: 40m 넘게 벗어나거나 속도가 1m/s 이상이면 이동 중, 3분 넘게 제자리면 정지
        val a = anchor
        val wasMoving = moving
        if (a == null) { anchor = loc; anchorTime = loc.time }
        else if (loc.distanceTo(a) > 40f || (loc.hasSpeed() && loc.speed > 1.0f)) {
            moving = true; anchor = loc; anchorTime = loc.time
        } else if (loc.time - anchorTime > 3 * 60_000L) {
            moving = false
        }
        if (wasMoving != moving) reapply()

        val saved = lastSaved
        if (saved == null || loc.time - saved.time >= saveEvery() || loc.distanceTo(saved) >= 30f) {
            lastSaved = loc
            Store.addTrackPoint(loc.time, loc.latitude, loc.longitude)
        }
        onFix?.invoke(loc)
    }

    @SuppressLint("MissingPermission")
    private fun request() {
        val m = lm ?: return
        try { m.removeUpdates(listener) } catch (_: Exception) {}
        interval = desiredInterval()
        try {
            if (m.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                m.requestLocationUpdates(LocationManager.GPS_PROVIDER, interval, if (live) 0f else 10f, listener, Looper.getMainLooper())
            } else if (m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, maxOf(interval, 60_000L), 30f, listener, Looper.getMainLooper())
            }
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    /** 간격 다시 계산 (절약 모드 변경, 지도 열기/닫기, 이동/정지 바뀔 때) */
    fun reapply() {
        if (!running) return
        if (desiredInterval() != interval) request()
    }

    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        if (running || !hasPermission(ctx)) return
        appCtx = ctx.applicationContext
        val m = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = m
        try {
            m.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { if (System.currentTimeMillis() - it.time < 120_000L) last = it }
        } catch (_: Exception) {}
        moving = true
        anchor = null
        running = true
        request()
    }

    /** 앱 설정에서 '내 위치 사용'을 껐을 때: 기억하던 위치도 지움 */
    fun forget() { stop(); last = null }

    fun stop() {
        try { lm?.removeUpdates(listener) } catch (_: Exception) {}
        running = false
        interval = 0L
        lastSaved = null
    }
}
