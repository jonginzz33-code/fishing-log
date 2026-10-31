package com.fishlog.app

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout

/**
 * 입질 시간 메모 (일출·일몰 기준)
 *  - 출조 하나: 어종마다 30분 안에 가장 많이 잡힌 구간 → "일출 후 40분 ~ 1시간 10분 (12마리)"
 *  - 지난 출조 전체: 어종별 낮·밤 비율 + 가장 잘 잡힌 때 (내 기록으로 계산, 3번 미만이면 기록 부족)
 */
object BiteMemo {

    private const val WIN = 30 * 60_000L
    const val MIN_TRIPS = 3

    class Peak(val species: String, val total: Int, val count: Int, val label: String, val dayPct: Int, val tideNote: String?)

    private fun locOf(t: Store.Trip): Pair<Double, Double>? = t.anyLocation()

    /** 출조 하나의 어종별 입질 피크 (3마리 이상 잡은 어종만) */
    fun forTrip(t: Store.Trip, day: Tide.Day?): List<Peak> {
        val loc = locOf(t)
        val out = mutableListOf<Peak>()
        for (slot in t.slots()) {
            val cs = t.catches.filter { it.slot == slot }.sortedBy { it.time }
            if (cs.size < 3) continue
            var best = 0; var bi = 0; var bj = 0
            var j = 0
            for (i in cs.indices) {
                while (j < cs.size && cs[j].time - cs[i].time <= WIN) j++
                if (j - i > best) { best = j - i; bi = i; bj = j - 1 }
            }
            val a = cs[bi].time; val b = cs[bj].time
            val s = Sun.times((a + b) / 2, loc?.first, loc?.second) ?: continue
            val label = if (b - a < 10 * 60_000L) Sun.relLabel(a, s) ?: Sun.rangeLabel(a, b, s) else Sun.rangeLabel(a, b, s)
            val dayN = cs.count { c -> Sun.times(c.time, loc?.first, loc?.second)?.isDay(c.time) ?: true }
            // 물돌이(만조·간조) 앞뒤 45분이면 한마디
            val mid = (a + b) / 2
            val note = day?.let { d ->
                Tide.extremes(d).firstOrNull { kotlin.math.abs(it.time - mid) <= 45 * 60_000L }?.let {
                    if (it.high) "만조 물돌이 앞뒤" else "간조 물돌이 앞뒤"
                } ?: Tide.rising(d, mid)?.let { r -> if (r) "들물 때" else "날물 때" }
            }
            out.add(Peak(t.name(slot), cs.size, best, label, Math.round(dayN * 100f / cs.size), note))
        }
        return out
    }

    // ---------------- 지난 출조 전체: 어종별 낮·밤 ----------------
    class Tendency(val species: String, val trips: Int, val catches: Int, val dayPct: Int, val best: String?)

    fun tendencies(history: List<Store.Trip>): List<Tendency> {
        class Acc { var trips = 0; var n = 0; var day = 0; val bins = HashMap<Pair<Boolean, Long>, Int>() }
        val map = LinkedHashMap<String, Acc>()
        history.forEach { t ->
            val loc = locOf(t)
            for (slot in t.slots()) {
                val cs = t.catches.filter { it.slot == slot }
                if (cs.isEmpty()) continue
                val acc = map.getOrPut(t.name(slot)) { Acc() }
                acc.trips++
                cs.forEach { c ->
                    val s = Sun.times(c.time, loc?.first, loc?.second) ?: return@forEach
                    acc.n++
                    if (s.isDay(c.time)) acc.day++
                    val (rise, off) = Sun.nearest(c.time, s)
                    if (kotlin.math.abs(off) <= 240) {
                        val bin = Math.floorDiv(off, 30L)
                        acc.bins[rise to bin] = (acc.bins[rise to bin] ?: 0) + 1
                    }
                }
            }
        }
        return map.entries.filter { it.value.n > 0 }.sortedByDescending { it.value.n }.map { (sp, a) ->
            val best = a.bins.maxByOrNull { it.value }?.takeIf { it.value >= 3 }?.key?.let { (rise, bin) -> binLabel(rise, bin) }
            Tendency(sp, a.trips, a.n, Math.round(a.day * 100f / a.n), best)
        }
    }

    private fun dur(min: Long): String {
        val m = kotlin.math.abs(min)
        return when { m == 0L -> "0분"; m < 60 -> "${m}분"; m % 60 == 0L -> "${m / 60}시간"; else -> "${m / 60}시간 ${m % 60}분" }
    }

    /** bin(30분 단위) → "일출 후 1시간 ~ 1시간 30분" / "일몰 30분 전 ~ 일몰" */
    private fun binLabel(rise: Boolean, bin: Long): String {
        val w = if (rise) "일출" else "일몰"
        val a = bin * 30; val b = a + 30
        return when {
            a >= 0 -> "$w 후 " + (if (a == 0L) "바로" else dur(a)) + " ~ " + dur(b)
            b <= 0 -> "$w ${dur(a)} 전 ~ " + (if (b == 0L) w else "${dur(b)} 전")
            else -> "$w 앞뒤"
        }
    }

    fun kindLabel(dayPct: Int) = when { dayPct >= 70 -> "낮 어종"; dayPct <= 30 -> "밤 어종"; else -> "낮·밤 모두" }

    // ---------------- 화면 조각 ----------------
    private val GOLD = Color.parseColor("#F2C14E")
    private val VIOLET = Color.parseColor("#B9A6FF")

    /** 출조 기록 화면의 "입질 시간 메모" 카드들 */
    fun tripCards(c: Context, peaks: List<Peak>): List<View> = peaks.map { p ->
        val card = Ui.card(c)
        val r = Ui.row(c)
        r.addView(Ui.text(c, 15f, Ui.TEXT, true, "${p.species} ${p.total}"), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        r.addView(Ui.text(c, 11f, Ui.SUB, false, if (p.dayPct >= 50) "낮 ${p.dayPct}%" else "밤 ${100 - p.dayPct}%"))
        card.addView(r)
        val night = p.dayPct < 50
        val col = if (night) VIOLET else GOLD
        card.addView(Ui.text(c, 12f, col, true, (if (night) "☾ " else "☀ ") + "가장 많이 잡힌 때: ${p.label} (${p.count}마리)").apply {
            setPadding(Ui.dp(c, 8), Ui.dp(c, 3), Ui.dp(c, 8), Ui.dp(c, 3))
            background = Ui.bg(c, Color.argb(50, Color.red(col), Color.green(col), Color.blue(col)), 8)
        }, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = Ui.dp(c, 6) })
        p.tideNote?.let { n ->
            card.addView(Ui.text(c, 12f, Ui.BLUE, true, "≋ $n").apply {
                setPadding(Ui.dp(c, 8), Ui.dp(c, 3), Ui.dp(c, 8), Ui.dp(c, 3))
                background = Ui.bg(c, Color.argb(45, 91, 200, 223), 8)
            }, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = Ui.dp(c, 4) })
        }
        card
    }

    /** 지난 출조 화면의 "어종별 낮·밤" 카드 */
    fun tendencyCard(c: Context, list: List<Tendency>): View? {
        if (list.isEmpty()) return null
        val card = Ui.card(c)
        card.addView(Ui.text(c, 15f, Ui.TEXT, true, "어종별 낮·밤 (내 기록으로 계산)"))
        list.take(8).forEach { t ->
            val head = Ui.row(c)
            head.addView(Ui.text(c, 14f, Ui.TEXT, true, t.species), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            val enough = t.trips >= MIN_TRIPS
            val txt = if (!enough) "기록 부족 (${MIN_TRIPS - t.trips}번 더 출조하면 계산)"
                else kindLabel(t.dayPct) + (t.best?.let { " · $it" } ?: "")
            val tc = when { !enough -> Ui.SUB; t.dayPct >= 70 -> GOLD; t.dayPct <= 30 -> VIOLET; else -> Ui.TEXT }
            head.addView(Ui.text(c, 11f, tc, enough, txt).apply { maxLines = 2; gravity = android.view.Gravity.END },
                LinearLayout.LayoutParams(0, Ui.WRAP, 1.6f))
            card.addView(head, Ui.lp(c, Ui.MATCH, Ui.WRAP, top = 10))
            val bar = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                background = Ui.bg(c, Color.parseColor("#22344A"), 99)
                clipToOutline = true
            }
            if (enough) {
                if (t.dayPct > 0) bar.addView(View(c).apply { setBackgroundColor(GOLD) }, LinearLayout.LayoutParams(0, Ui.MATCH, t.dayPct.toFloat()))
                if (t.dayPct < 100) bar.addView(View(c).apply { setBackgroundColor(Color.parseColor("#7F6FD0")) }, LinearLayout.LayoutParams(0, Ui.MATCH, (100 - t.dayPct).toFloat()))
            }
            card.addView(bar, Ui.lp(c, Ui.MATCH, Ui.dp(c, 8), top = 5))
        }
        card.addView(Ui.text(c, 11f, Ui.SUB, false, "노랑 = 낮(일출~일몰) · 보라 = 밤 · 낮이 70% 넘으면 낮 어종, 밤이 70% 넘으면 밤 어종"),
            Ui.lp(c, Ui.MATCH, Ui.WRAP, top = 10))
        return card
    }
}
