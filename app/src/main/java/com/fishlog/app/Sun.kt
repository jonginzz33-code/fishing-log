package com.fishlog.app

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * 일출·일몰 (인터넷 없이 위치·날짜로 계산, 오차 1~2분)
 */
object Sun {

    /** 위치를 모를 때 쓰는 서해 중부 기준 */
    const val DEF_LAT = 36.4
    const val DEF_LON = 126.5

    private val KST = TimeZone.getTimeZone("Asia/Seoul")

    class Times(val rise: Long, val set: Long) {
        fun isDay(t: Long) = t in rise..set
    }

    fun dayStart(time: Long): Long = Calendar.getInstance(KST).apply {
        timeInMillis = time
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private val cache = HashMap<String, Times?>()

    /** time 이 속한 날(한국 시간)의 일출·일몰 */
    fun times(time: Long, lat: Double?, lon: Double?): Times? {
        val la = lat ?: DEF_LAT; val lo = lon ?: DEF_LON
        val day = dayStart(time)
        val key = "$day|${Math.round(la * 10)}|${Math.round(lo * 10)}"
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val r = calc(day, la, lo)
        synchronized(cache) { if (cache.size > 400) cache.clear(); cache[key] = r }
        return r
    }

    private fun rad(d: Double) = Math.toRadians(d)

    private fun calc(dayMillis: Long, lat: Double, lon: Double): Times? {
        val jdate = (dayMillis + 12 * 3_600_000L) / 86_400_000.0 + 2440587.5
        val n = Math.round(jdate - 2451545.0 + 0.0008).toDouble()
        val js = n - lon / 360.0
        val m = (357.5291 + 0.98560028 * js) % 360.0
        val mr = rad(m)
        val c = 1.9148 * sin(mr) + 0.02 * sin(2 * mr) + 0.0003 * sin(3 * mr)
        val l = (m + c + 180 + 102.9372) % 360.0
        val lr = rad(l)
        val jt = 2451545.0 + js + 0.0053 * sin(mr) - 0.0069 * sin(2 * lr)
        val sd = sin(lr) * sin(rad(23.4397))
        val cd = cos(asin(sd))
        val cw = (sin(rad(-0.833)) - sin(rad(lat)) * sd) / (cos(rad(lat)) * cd)
        if (cw < -1 || cw > 1) return null
        val w = Math.toDegrees(acos(cw))
        fun ms(j: Double) = ((j - 2440587.5) * 86_400_000.0).roundToLong()
        return Times(ms(jt - w / 360.0), ms(jt + w / 360.0))
    }

    private fun dur(min: Long): String {
        val m = kotlin.math.abs(min)
        return when {
            m < 60 -> "${m}분"
            m % 60 == 0L -> "${m / 60}시간"
            else -> "${m / 60}시간 ${m % 60}분"
        }
    }

    /** 일출·일몰 중 가까운 쪽 기준 거리(분). 음수 = 전. first = true 면 일출 기준 */
    fun nearest(t: Long, s: Times): Pair<Boolean, Long> {
        val dr = (t - s.rise) / 60_000L
        val ds = (t - s.set) / 60_000L
        return if (kotlin.math.abs(dr) <= kotlin.math.abs(ds)) true to dr else false to ds
    }

    /** "일출 후 40분" / "일몰 30분 전" / 둘 다 3시간 넘게 멀면 null */
    fun relLabel(t: Long, s: Times): String? {
        val (rise, d) = nearest(t, s)
        if (kotlin.math.abs(d) > 180) return null
        val w = if (rise) "일출" else "일몰"
        return when {
            kotlin.math.abs(d) < 5 -> "$w 무렵"
            d > 0 -> "$w 후 ${dur(d)}"
            else -> "$w ${dur(d)} 전"
        }
    }

    /** 구간 이름: "일출 후 40분 ~ 1시간 10분" (같은 기준이면 묶어서), 멀면 "한낮 12:30 ~ 13:00" */
    fun rangeLabel(a: Long, b: Long, s: Times): String {
        val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA)
        val (ra, da) = nearest(a, s)
        val (rb, db) = nearest(b, s)
        if (ra == rb && kotlin.math.abs(da) <= 180 && kotlin.math.abs(db) <= 180) {
            val w = if (ra) "일출" else "일몰"
            return when {
                da >= 0 && db >= 0 -> "$w 후 ${if (da < 5) "바로" else dur(da)} ~ ${dur(db)}"
                da < 0 && db <= 0 -> "$w ${dur(da)} 전 ~ " + (if (db > -5) "$w 무렵" else "${dur(db)} 전")
                else -> "$w ${dur(da)} 전 ~ ${dur(db)} 후"
            }
        }
        val mid = (a + b) / 2
        val name = if (s.isDay(mid)) "한낮" else "한밤"
        return "$name ${hm.format(java.util.Date(a))} ~ ${hm.format(java.util.Date(b))}"
    }
}
