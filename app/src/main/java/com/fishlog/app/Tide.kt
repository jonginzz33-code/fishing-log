package com.fishlog.app

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.floor

/**
 * 물때
 *  - 물때 단계(몇 물, 사리·조금): 달의 나이로 계산 (인터넷 필요 없음, 7물때식, 하루 정도 차이날 수 있음)
 *  - 조위 그래프: Open-Meteo 해양 API (키 없음, 해안 근처는 정확도가 떨어질 수 있음)
 */
object Tide {

    private val KST: TimeZone = TimeZone.getTimeZone("Asia/Seoul")
    private const val SYNODIC = 29.530588853
    private const val NEW_MOON_2000 = 947182440000.0 // 2000-01-06 18:14 UTC

    /** 음력 날짜(1~30) 근사값 */
    fun lunarDay(time: Long): Int {
        val cal = Calendar.getInstance(KST).apply { timeInMillis = time; set(Calendar.HOUR_OF_DAY, 12) }
        val days = (cal.timeInMillis - NEW_MOON_2000) / 86_400_000.0
        val age = ((days % SYNODIC) + SYNODIC) % SYNODIC
        return floor(age).toInt() + 1
    }

    /** "7물 · 사리" 같은 물때 이름 */
    fun mulName(time: Long): String {
        val d = lunarDay(time)
        val idx = (((d - 9) % 15) + 15) % 15
        val name = when (idx) {
            0 -> "무시"
            14 -> "조금"
            else -> "${idx}물"
        }
        val kind = when (idx) {
            in 5..9 -> "사리"
            0, 12, 13, 14 -> "조금 무렵"
            else -> "중간"
        }
        return "$name · $kind (음력 ${d}일 무렵)"
    }

    class Day(val dateMillis: Long, val hourly: List<Double?>)

    private val main = Handler(Looper.getMainLooper())
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = KST }

    /** 그날 0~23시 조위(m). 해안선 격자에 걸려 비어 있으면 주변 바다 쪽으로 옮겨 다시 시도 */
    fun fetch(lat: Double, lon: Double, time: Long, done: (Day?, String?) -> Unit) {
        val date = dayFmt.format(Date(time))
        Thread {
            var result: Day? = null
            var err: String? = null
            val offsets = listOf(0.0 to 0.0, 0.0 to -0.08, 0.0 to 0.08, -0.08 to 0.0, 0.08 to 0.0, 0.0 to -0.16, -0.08 to -0.08)
            for ((dla, dlo) in offsets) {
                try {
                    val url = "https://marine-api.open-meteo.com/v1/marine?latitude=${lat + dla}&longitude=${lon + dlo}" +
                        "&hourly=sea_level_height_msl&timezone=Asia%2FSeoul&start_date=$date&end_date=$date"
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 10_000; c.readTimeout = 10_000
                    val body = c.inputStream.bufferedReader().use { it.readText() }
                    c.disconnect()
                    val arr = JSONObject(body).getJSONObject("hourly").getJSONArray("sea_level_height_msl")
                    val vals = (0 until arr.length()).map { if (arr.isNull(it)) null else arr.getDouble(it) }
                    if (vals.count { it != null } >= 12) {
                        result = Day(dayFmt.parse(date)!!.time, vals.take(24)); break
                    }
                    err = "이 위치는 조위 데이터가 없어요"
                } catch (e: Exception) {
                    err = "물때 정보를 불러오지 못했어요 (인터넷 확인)"
                }
            }
            val r = result
            main.post { if (r != null) done(r, null) else done(null, err) }
        }.start()
    }

    /** 만조·간조 시각과 높이 (시간별 값 사이를 포물선으로 보정) */
    class Extreme(val time: Long, val high: Boolean, val value: Double)

    fun extremes(d: Day): List<Extreme> {
        val v = d.hourly
        val out = mutableListOf<Extreme>()
        for (i in 1 until v.size - 1) {
            val a = v[i - 1] ?: continue; val b = v[i] ?: continue; val c = v[i + 1] ?: continue
            val high = b >= a && b > c
            val low = b <= a && b < c
            if (!high && !low) continue
            val denom = a - 2 * b + c
            val off = if (denom != 0.0) 0.5 * (a - c) / denom else 0.0
            val t = d.dateMillis + ((i + off) * 3600_000L).toLong()
            out.add(Extreme(t, high, b))
        }
        return out
    }

    /** 특정 시각의 조위 (선형 보간) */
    fun valueAt(d: Day, time: Long): Double? {
        val h = (time - d.dateMillis) / 3600_000.0
        if (h < 0 || h > d.hourly.size - 1) return null
        val i = floor(h).toInt()
        val a = d.hourly.getOrNull(i) ?: return null
        val b = d.hourly.getOrNull(i + 1) ?: return a
        return a + (b - a) * (h - i)
    }

    /** 이 시각이 들물(올라가는 중)인지 */
    fun rising(d: Day, time: Long): Boolean? {
        val a = valueAt(d, time - 600_000L) ?: return null
        val b = valueAt(d, time + 600_000L) ?: return null
        return b > a
    }
}
