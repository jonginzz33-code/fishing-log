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

    /** 10분 넘게 제자리 (닻 내리고 오래 있을 때) → 위치 재는 간격을 더 늘림 */
    private fun longStill(): Boolean = !moving && anchorTime > 0 && (last?.time ?: 0L) - anchorTime > 10 * 60_000L

    /** 조과 버튼을 누른 뒤 1분 동안은 GPS를 2초마다 (그 조과 위치를 정확하게) */
    private var burstUntil = 0L
    private val main = android.os.Handler(Looper.getMainLooper())

    private fun desiredInterval(): Long = when {
        System.currentTimeMillis() < burstUntil -> 2_000L
        live -> 3_000L
        Store.saver -> 180_000L
        moving -> 20_000L
        longStill() -> 180_000L
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

    /** 정확한 위치로 쓸 기준: GPS + 오차 50m 이내 */
    private const val GOOD_ACC = 50f
    /** 이보다 빠르게 움직인 것처럼 튄 점은 버림 (약 50노트) */
    private const val MAX_SPEED_MS = 25f
    /** GPS를 못 잡을 때의 대략 위치 (와이파이·기지국) — 경로·지도엔 안 씀, 조과 '대략'에만 */
    var rough: Location? = null
        private set

    private fun isGood(loc: Location): Boolean =
        loc.provider == LocationManager.GPS_PROVIDER && (!loc.hasAccuracy() || loc.accuracy <= GOOD_ACC)

    private fun onLocation(loc: Location) {
        if (!isGood(loc)) {
            // 와이파이·기지국·오차 큰 위치: 경로·내 위치·보정에 안 씀 → 위치가 아예 없던 조과에만 '대략'으로
            rough = loc
            Store.fillCatchLoc(loc.time, loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else -1f, false)
            return
        }
        // 순간이동(말이 안 되는 속도로 튄 점) 버리기
        val prev = last
        if (prev != null && isGood(prev)) {
            val dt = (loc.time - prev.time) / 1000f
            if (dt > 0f && dt < 600f && loc.distanceTo(prev) / dt > MAX_SPEED_MS) return
        }
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
        if (wasMoving != moving || longStill()) reapply()
        // 조과 버튼을 누른 뒤 1분 안에 잡힌 정확한 GPS → 그 조과 위치로
        Store.fillCatchLoc(loc.time, loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else -1f, true)

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
                val fast = interval <= 3_000L
                m.requestLocationUpdates(LocationManager.GPS_PROVIDER, interval, if (fast) 0f else 10f, listener, Looper.getMainLooper())
                // 조과를 누른 뒤엔 실내·약한 신호 대비 와이파이·기지국 위치도 같이 (GPS가 오면 GPS 우선)
                if (System.currentTimeMillis() < burstUntil && m.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                    m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5_000L, 0f, listener, Looper.getMainLooper())
            } else if (m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, maxOf(interval, 60_000L), 30f, listener, Looper.getMainLooper())
            }
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    /** 조과 버튼을 눌렀을 때: 1분 동안 GPS 촘촘히 (끝나면 원래 간격으로) */
    fun burst() {
        if (!running) return
        val first = System.currentTimeMillis() >= burstUntil
        burstUntil = System.currentTimeMillis() + 60_000L
        if (first) request()
        main.removeCallbacks(burstEnd); main.postDelayed(burstEnd, 61_000L)
    }
    private val burstEnd = Runnable { reapply() }

    /** GPS가 없을 때 쓸 폰 위치 (와이파이·기지국 · 30분 안) — 대략 */
    @SuppressLint("MissingPermission")
    fun approx(now: Long): Location? {
        if (!Store.appLoc) return null
        val m = lm ?: return null
        val r0 = rough
        if (r0 != null && now - r0.time < 10 * 60_000L) return r0
        return try {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
                .mapNotNull { p: String -> try { m.getLastKnownLocation(p) } catch (_: Exception) { null } }
                .filter { now - it.time < 30 * 60_000L }.maxByOrNull { it.time }
        } catch (_: Exception) { null }
    }

    /**
     * 조과 버튼을 눌렀는데 최근 위치가 없으면(오래 멈춰 있어 간격이 길 때 등) GPS 를 한 번 바로 재서 그 조과에 붙임
     *  - 조과 기록(시각·마릿수)은 그대로 바로 저장 · 위치만 나중에 채움
     */
    @SuppressLint("MissingPermission")
    fun freshFix() {
        val m = lm ?: return
        if (!running) return
        val l = last
        if (l != null && System.currentTimeMillis() - l.time < 45_000L) return
        try {
            @Suppress("DEPRECATION")
            m.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
        } catch (_: Exception) {}
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
    fun forget() { stop(); last = null; rough = null }

    fun stop() {
        try { lm?.removeUpdates(listener) } catch (_: Exception) {}
        running = false
        interval = 0L
        lastSaved = null
    }
}
