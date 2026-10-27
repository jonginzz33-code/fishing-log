package com.fishlog.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/** "내 위치" 버튼: 누를 때마다 GPS로 지금 위치를 한 번 잡음 (실시간 추적 아님) */
object LocOnce {

    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    fun get(a: Activity, done: (Location?, String?) -> Unit) {
        if (!Tracker.hasPermission(a)) {
            a.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 9)
            done(null, "위치 권한을 허용한 뒤 다시 눌러주세요")
            return
        }
        val lm = a.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsOn = try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (_: Exception) { false }
        val netOn = try { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { false }
        if (!gpsOn && !netOn) {
            AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("위치(GPS)가 꺼져 있어요")
                .setMessage("내 위치를 보려면 위치를 켜 주세요")
                .setPositiveButton("설정 열기") { _, _ -> a.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                .setNegativeButton("취소", null)
                .show()
            done(null, "위치가 꺼져 있어요")
            return
        }
        val provider = if (gpsOn) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        var finished = false
        fun finish(l: Location?) {
            if (finished) return
            finished = true
            val fallback = l ?: listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
                .maxByOrNull { it.time }
            main.post { done(fallback, if (fallback == null) "위치를 찾지 못했어요. 하늘이 보이는 곳에서 다시 눌러주세요" else null) }
        }
        // 15초 안에 못 잡으면 마지막으로 알던 위치
        main.postDelayed({ finish(null) }, 15_000L)
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, null, a.mainExecutor) { l -> finish(l) }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(l: Location) { lm.removeUpdates(this); finish(l) }
                    @Deprecated("old") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        } catch (_: Exception) { finish(null) }
    }
}
