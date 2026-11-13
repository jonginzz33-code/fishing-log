package com.fishlog.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v6.9 (254): 🏞 민물 출조는 물때 대신 해 기준 — 배스 · 붕어는 일출 · 일몰 앞뒤(골든타임)가 중요
 *  골든타임 = 일출 전후 1시간 · 일몰 전후 1시간
 */
object SunPhase {
    private const val H = 3_600_000L
    val ORDER = listOf("🌅 일출 전 1시간", "🌅 일출 후 1시간", "☀ 한낮", "🌇 일몰 전 1시간", "🌇 일몰 후 1시간", "🌙 밤 · 새벽")
    /** 골든타임 칸 (ORDER 의 0 · 1 · 3 · 4) */
    fun golden(label: String) = label != ORDER[2] && label != ORDER[5]

    /** 그 시각이 어느 칸인지 */
    fun of(time: Long, lat: Double?, lon: Double?): String {
        val s = Sun.times(time, lat, lon) ?: return ORDER[2]
        return when {
            time < s.rise - H -> ORDER[5]
            time < s.rise -> ORDER[0]
            time < s.rise + H -> ORDER[1]
            time < s.set - H -> ORDER[2]
            time < s.set -> ORDER[3]
            time < s.set + H -> ORDER[4]
            else -> ORDER[5]
        }
    }

    /** 칸별 마릿수 (ORDER 순서 · 0마리 칸 포함) */
    fun counts(times: List<Long>, lat: Double?, lon: Double?): List<Pair<String, Int>> {
        val m = times.groupingBy { of(it, lat, lon) }.eachCount()
        return ORDER.map { it to (m[it] ?: 0) }
    }

    private val hm = SimpleDateFormat("HH:mm", Locale.KOREA)
    /** "일출 06:58 · 일몰 17:22" */
    fun riseSet(time: Long, lat: Double?, lon: Double?): String {
        val s = Sun.times(time, lat, lon) ?: return ""
        return "일출 ${hm.format(Date(s.rise))} · 일몰 ${hm.format(Date(s.set))}"
    }
    /** "🌅 아침 골든타임 05:58~07:58 · 🌇 저녁 골든타임 16:22~18:22" */
    fun goldenText(time: Long, lat: Double?, lon: Double?): String {
        val s = Sun.times(time, lat, lon) ?: return ""
        return "🌅 아침 골든타임 ${hm.format(Date(s.rise - H))}~${hm.format(Date(s.rise + H))} · " +
            "🌇 저녁 골든타임 ${hm.format(Date(s.set - H))}~${hm.format(Date(s.set + H))}"
    }
    /** 출조 중 메인 카드: "일몰까지 1시간 20분 · 🌇 저녁 골든타임 16:22부터" */
    fun liveText(now: Long, lat: Double?, lon: Double?): String {
        val s = Sun.times(now, lat, lon) ?: return ""
        fun left(t: Long): String { val m = ((t - now) / 60_000L).coerceAtLeast(0); return if (m >= 60) "${m / 60}시간 ${m % 60}분" else "${m}분" }
        return when {
            now < s.rise - H -> "일출까지 ${left(s.rise)} · 🌅 아침 골든타임 ${hm.format(Date(s.rise - H))}부터"
            now < s.rise + H -> "🌅 지금 아침 골든타임 (${hm.format(Date(s.rise + H))}까지) · 일출 ${hm.format(Date(s.rise))}"
            now < s.set - H -> "일몰까지 ${left(s.set)} · 🌇 저녁 골든타임 ${hm.format(Date(s.set - H))}부터"
            now < s.set + H -> "🌇 지금 저녁 골든타임 (${hm.format(Date(s.set + H))}까지) · 일몰 ${hm.format(Date(s.set))}"
            else -> "🌙 밤 · " + riseSet(now, lat, lon)
        }
    }

    /** 시간대별 한 줄 요약 텍스트 (지도 하루 상세 등) */
    fun summary(times: List<Long>, lat: Double?, lon: Double?): String {
        val cs = counts(times, lat, lon).filter { it.second > 0 }
        if (cs.isEmpty()) return ""
        val g = cs.filter { golden(it.first) }.sumOf { it.second }
        return cs.joinToString("\n") { "  ${it.first} ${it.second}마리" } +
            if (g > 0) "\n  → 골든타임(일출 · 일몰 전후 1시간)에 ${g}마리 (${g * 100 / times.size}%)" else ""
    }
}
