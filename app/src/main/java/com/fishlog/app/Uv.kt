package com.fishlog.app

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * ☀ 자외선지수 (기상청_생활기상지수 조회서비스 · 자외선지수) — v3.8
 *  - 시·도 단위 (가까운 시·도 가운데 지점으로 고름) · 발표 시각부터 3시간 간격 h0, h3, h6 …
 *  - 인증키: 앱에 들어 있는 공공데이터포털 키 (KHOA_KEY 와 같은 계정 키)
 *  - 폰에 3시간 기억 (같은 시·도는 다시 안 물어봄)
 */
object Uv {
    /** 발표 시각(그 시각 0시간) + 시간 → 지수 */
    class Data(val base: Long, val values: Map<Int, Int>) {
        fun at(time: Long): Int? {
            val h = ((time - base) / 3_600_000L).toInt()
            if (h < 0) return null
            val k = values.keys.filter { it <= h }.maxOrNull() ?: return null
            return if (h - k <= 3) values[k] else null
        }
        /** 그날(0~24시) 중 최고 */
        fun maxOn(dayStart: Long): Int? = values.entries
            .filter { val t = base + it.key * 3_600_000L; t >= dayStart && t < dayStart + 86_400_000L }
            .maxOfOrNull { it.value }
    }

    fun level(v: Int): String = when {
        v <= 2 -> "낮음"; v <= 5 -> "보통"; v <= 7 -> "높음"; v <= 10 -> "매우높음"; else -> "위험"
    }
    fun color(v: Int): Int = android.graphics.Color.parseColor(when {
        v <= 2 -> "#5FD39A"; v <= 5 -> "#F2C14E"; v <= 7 -> "#F28C38"; v <= 10 -> "#E8505B"; else -> "#B46BE0"
    })

    /** 시·도: 이름, 가운데 위도·경도, 행정구역 코드들 (새 코드 → 예전 코드) */
    private class Sido(val name: String, val lat: Double, val lon: Double, val codes: List<String>)
    private val SIDO = listOf(
        Sido("서울", 37.56, 126.98, listOf("1100000000")),
        Sido("부산", 35.18, 129.08, listOf("2600000000")),
        Sido("대구", 35.87, 128.60, listOf("2700000000")),
        Sido("인천", 37.46, 126.60, listOf("2800000000")),
        Sido("광주", 35.16, 126.85, listOf("2900000000")),
        Sido("대전", 36.35, 127.38, listOf("3000000000")),
        Sido("울산", 35.54, 129.31, listOf("3100000000")),
        Sido("세종", 36.48, 127.29, listOf("3611000000", "3600000000")),
        Sido("경기", 37.27, 127.01, listOf("4100000000")),
        Sido("강원", 37.80, 128.30, listOf("5100000000", "4200000000")),
        Sido("충북", 36.64, 127.49, listOf("4300000000")),
        Sido("충남", 36.60, 126.80, listOf("4400000000")),
        Sido("전북", 35.82, 127.11, listOf("5200000000", "4500000000")),
        Sido("전남", 34.80, 126.70, listOf("4600000000")),
        Sido("경북", 36.40, 128.80, listOf("4700000000")),
        Sido("경남", 35.25, 128.30, listOf("4800000000")),
        Sido("제주", 33.40, 126.55, listOf("5000000000"))
    )
    private fun nearest(lat: Double, lon: Double) = SIDO.minByOrNull {
        val dy = it.lat - lat; val dx = (it.lon - lon) * 0.82; dy * dy + dx * dx
    }!!

    fun sidoName(lat: Double, lon: Double) = nearest(lat, lon).name

    private val main = Handler(Looper.getMainLooper())
    private val cache = HashMap<String, Pair<Long, Data>>()
    private val fmt = SimpleDateFormat("yyyyMMddHH", Locale.KOREA)

    fun fetch(lat: Double, lon: Double, done: (Data?) -> Unit) {
        val s = nearest(lat, lon)
        cache[s.name]?.let { (at, d) -> if (System.currentTimeMillis() - at < 3 * 3_600_000L) return done(d) }
        val key = BuildConfig.KHOA_KEY
        if (key.isBlank()) return done(null)
        Thread {
            var out: Data? = null
            // 발표 시각: 지금 시각부터 3시간씩 거슬러 올라가며 (최대 하루)
            val cal = Calendar.getInstance().apply { set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            loop@ for (back in 0..8) {
                val t = cal.timeInMillis - back * 3 * 3_600_000L
                for (code in s.codes) {
                    try {
                        val url = "https://apis.data.go.kr/1360000/LivingWthrIdxServiceV4/getUVIdxV4?serviceKey=" +
                            java.net.URLEncoder.encode(key, "UTF-8") + "&pageNo=1&numOfRows=10&dataType=JSON&areaNo=$code&time=" + fmt.format(Date(t))
                        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                        c.connectTimeout = 10_000; c.readTimeout = 15_000
                        if (c.responseCode !in 200..299) continue
                        val body = c.inputStream.bufferedReader().use { it.readText() }
                        val item = JSONObject(body).optJSONObject("response")?.optJSONObject("body")
                            ?.optJSONObject("items")?.optJSONArray("item")?.optJSONObject(0) ?: continue
                        val base = fmt.parse(item.optString("date"))?.time ?: t
                        val vals = HashMap<Int, Int>()
                        item.keys().forEach { k ->
                            if (k.length >= 2 && k[0] == 'h' && k.substring(1).all { it.isDigit() }) {
                                val v = item.optString(k).trim().toIntOrNull()
                                if (v != null) vals[k.substring(1).toInt()] = v
                            }
                        }
                        if (vals.isNotEmpty()) { out = Data(base, vals); break@loop }
                    } catch (_: Exception) {}
                }
            }
            val r = out
            main.post {
                if (r != null) cache[s.name] = System.currentTimeMillis() to r
                done(r)
            }
        }.start()
    }
}
