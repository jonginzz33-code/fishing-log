package com.fishlog.app

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * v6.59 (337): 🌊 시간별 물 가는 방향 (해류 예보 · 대충)
 *  - Open-Meteo 바다 예보 ocean_current_direction / ocean_current_velocity (조류 포함 모델 · 칸 약 8km · 키 없음)
 *  - 방향 = 물이 흘러가는 쪽 (도) · 세기 = km/h
 *  - 바다 칸이 비면(육지) 둘레 몇 칸을 한 번에 물어 가장 가까운 값
 *  - 메모리 3시간 기억 · ⚠ 물때 계산 · 물세기 % · 물흐름 막대와 상관없음 (선수 · 선미 판단 · 표시만)
 */
object Currents {
    class Day(val dayStart: Long, val dir: Array<Double?>, val kmh: Array<Double?>)
    private val cache = ConcurrentHashMap<String, Pair<Long, Day?>>()
    private val main = Handler(Looper.getMainLooper())
    private val dk = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
    private val tf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.KOREA)

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 7_000; c.readTimeout = 9_000
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    fun load(lat: Double, lon: Double, dayStart: Long, done: (Day?) -> Unit) {
        val key = String.format(Locale.US, "%.2f_%.2f_%d", lat, lon, dayStart)
        cache[key]?.takeIf { System.currentTimeMillis() - it.first < 3 * 3_600_000L }?.let { return done(it.second) }
        Thread {
            var out: Day? = null
            try {
                val d = dk.format(dayStart)
                // 가운데 + 둘레 8칸 (0.08° ≈ 8km) — 바다 칸이 빈 곳(육지 · 해안)이면 다음으로 가까운 칸
                val offs = listOf(0.0 to 0.0, 0.08 to 0.0, -0.08 to 0.0, 0.0 to 0.08, 0.0 to -0.08, 0.08 to 0.08, 0.08 to -0.08, -0.08 to 0.08, -0.08 to -0.08)
                val lats = offs.joinToString(",") { String.format(Locale.US, "%.3f", lat + it.first) }
                val lons = offs.joinToString(",") { String.format(Locale.US, "%.3f", lon + it.second) }
                val body = get("https://marine-api.open-meteo.com/v1/marine?latitude=$lats&longitude=$lons" +
                    "&hourly=ocean_current_velocity,ocean_current_direction&timezone=Asia%2FSeoul&start_date=$d&end_date=$d")
                val arr = if (body.trimStart().startsWith("[")) org.json.JSONArray(body) else org.json.JSONArray().put(JSONObject(body))
                for (i in 0 until arr.length()) {
                    val h = arr.getJSONObject(i).optJSONObject("hourly") ?: continue
                    val ta = h.optJSONArray("time") ?: continue
                    val da = h.optJSONArray("ocean_current_direction") ?: continue
                    val va = h.optJSONArray("ocean_current_velocity")
                    val dir = arrayOfNulls<Double>(24); val kmh = arrayOfNulls<Double>(24)
                    var n = 0
                    for (k in 0 until ta.length()) {
                        val t = tf.parse(ta.getString(k))?.time ?: continue
                        val hr = ((t - dayStart) / 3_600_000L).toInt()
                        if (hr !in 0..23 || da.isNull(k)) continue
                        dir[hr] = da.getDouble(k); if (va != null && !va.isNull(k)) kmh[hr] = va.getDouble(k)
                        n++
                    }
                    if (n >= 12) { out = Day(dayStart, dir, kmh); break }
                }
            } catch (_: Exception) {}
            cache[key] = System.currentTimeMillis() to out
            main.post { done(out) }
        }.start()
    }

    /** 방향(도, 흘러가는 쪽) → 화살표 · 16방위 이름 */
    fun arrow(deg: Double): String = arrayOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")[(((deg % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]
    fun name(deg: Double): String = arrayOf("북", "북동", "동", "남동", "남", "남서", "서", "북서")[(((deg % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]
}
