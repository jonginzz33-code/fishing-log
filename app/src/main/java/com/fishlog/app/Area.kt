package com.fishlog.app

import android.content.Context
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import java.util.Locale

/**
 * 좌표 → 실제 동네 이름 ("화성시 동탄", "옹진군 영흥면")
 *  - 예전엔 가장 가까운 물때 관측소 이름(평택 등)을 써서 내륙·먼 곳이 틀리게 나왔음
 *  - 폰의 주소 찾기(Geocoder)로 받고, 약 1km 칸마다 폰에 기억 (같은 곳은 다시 안 물어봄)
 *  - 못 찾으면(바다 한가운데·인터넷 없음) 가까운 관측소 이름으로
 */
object Area {
    private val main = Handler(Looper.getMainLooper())
    private val mem = HashMap<String, String>()
    /** 찾는 중인 칸 → 찾으면 알려 줄 곳들 */
    private val asking = HashMap<String, MutableList<() -> Unit>>()
    private fun prefs(c: Context) = c.getSharedPreferences("area_names", Context.MODE_PRIVATE)
    private fun key(lat: Double, lon: Double) = String.format(Locale.US, "%.2f_%.2f", lat, lon)

    /** 기억해 둔 이름 ("" = 찾아봤는데 없음, null = 아직 모름) */
    fun cached(c: Context, lat: Double, lon: Double): String? {
        val k = key(lat, lon)
        mem[k]?.let { return it }
        val v = prefs(c).getString(k, null) ?: return null
        mem[k] = v; return v
    }

    /** 관측소 이름으로 대신 */
    fun station(c: Context, lat: Double, lon: Double): String =
        Tide.stationsNear(c, lat, lon).firstOrNull()?.name ?: ""

    /** 지금 바로 쓸 이름: 기억해 둔 동네 → 없으면 관측소 (그동안 뒤에서 찾아 둠, 찾으면 done) */
    fun name(c: Context, lat: Double, lon: Double, done: (() -> Unit)? = null): String {
        cached(c, lat, lon)?.let { if (it.isNotBlank()) return it }
        if (cached(c, lat, lon) == null) fetch(c, lat, lon, done)
        return station(c, lat, lon)
    }

    fun fetch(c: Context, lat: Double, lon: Double, done: (() -> Unit)?) {
        val k = key(lat, lon)
        if (!Geocoder.isPresent()) return
        asking[k]?.let { l -> done?.let { l.add(it) }; return }
        asking[k] = mutableListOf<() -> Unit>().apply { done?.let { add(it) } }
        val app = c.applicationContext
        Thread {
            var out = ""
            var ok = true
            try {
                @Suppress("DEPRECATION")
                val a = Geocoder(app, Locale.KOREA).getFromLocation(lat, lon, 1)?.firstOrNull()
                if (a != null) {
                    val big = a.locality ?: a.subAdminArea ?: a.adminArea
                    val small = a.subLocality ?: a.thoroughfare?.takeIf { it.endsWith("동") || it.endsWith("읍") || it.endsWith("면") || it.endsWith("리") }
                    out = listOfNotNull(big, small).distinct().joinToString(" ").trim()
                }
            } catch (_: Exception) { ok = false }   // 인터넷 없음 등 → 기억하지 않고 다음에 다시
            main.post {
                val waiters = asking.remove(k) ?: mutableListOf()
                if (!ok) return@post
                mem[k] = out
                prefs(app).edit().putString(k, out).apply()
                if (out.isNotBlank()) waiters.forEach { it() }
            }
        }.start()
    }
}
