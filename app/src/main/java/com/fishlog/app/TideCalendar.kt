package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 📅 일정 · 물때 달력 (v3.9.4 · 180)
 *  - 날짜 칸: 어종 그림 = 그 어종이 잘 잡히는 물때인 날 · 뜰채 = 물 많이 빠지는 해루질 좋은 날
 *  - 어종별 좋은 물때: 앱 조과 기록(내 출조 5번 이상)으로 계산 · 모자라면 낚시인들이 흔히 말하는 기본값
 *  - 칸 아래: 달 모양 · 몇 물 · 물세기 막대 · ⚠ 오늘 기상특보
 *  - 날짜를 누르면 아래에 간조 · 만조 · 해 · 달 · 해루질 시간 · 안개 · 갯벌체험지수 · 바다낚시지수
 */
object TideCalendar {
    const val HAERU = "해루질"

    /** 🥅 뜰채 그림 (해루질) */
    class NetIcon(c: Context, sizeDp: Int) : Drawable() {
        private val px = (sizeDp * c.resources.displayMetrics.density).toInt()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        init { setBounds(0, 0, px, px) }
        override fun draw(cv: Canvas) {
            val b = bounds; val w = b.width().toFloat(); val x0 = b.left.toFloat(); val y0 = b.top.toFloat()
            // 손잡이
            p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
            p.color = Color.parseColor("#C98A4B"); p.strokeWidth = w * 0.09f
            cv.drawLine(x0 + w * 0.12f, y0 + w * 0.92f, x0 + w * 0.45f, y0 + w * 0.52f, p)
            // 그물 주머니
            val ring = RectF(x0 + w * 0.36f, y0 + w * 0.08f, x0 + w * 0.96f, y0 + w * 0.5f)
            val bag = Path().apply {
                moveTo(ring.left + w * 0.02f, ring.centerY()); quadTo(ring.centerX(), y0 + w * 0.98f, ring.right - w * 0.02f, ring.centerY()); close()
            }
            p.style = Paint.Style.FILL; p.color = Color.argb(70, 207, 230, 245); cv.drawPath(bag, p)
            cv.save(); cv.clipPath(bag)
            p.style = Paint.Style.STROKE; p.color = Color.argb(200, 207, 230, 245); p.strokeWidth = w * 0.035f
            var t = -w; while (t < w * 2) { cv.drawLine(x0 + t, y0, x0 + t + w, y0 + w, p); cv.drawLine(x0 + t + w, y0, x0 + t, y0 + w, p); t += w * 0.14f }
            cv.restore()
            p.color = Color.parseColor("#FFB27A"); p.strokeWidth = w * 0.08f
            cv.drawOval(ring, p)
        }
        override fun getIntrinsicWidth() = px
        override fun getIntrinsicHeight() = px
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** 좋은 물세기 범위 (%) · 철(달) · 이유 · 앱 기록 수(0 = 기본값) */
    class Rule(val min: Int, val max: Int, val months: Set<Int>, val why: String, val trips: Int = 0) {
        fun ok(pct: Int) = pct in min..max
        fun inSeason(month: Int) = months.isEmpty() || month in months
    }

    private val ALL = emptySet<Int>()
    /** 기본값: 낚시인들이 흔히 말하는 것 (근거가 확실한 자료는 아님 · 앱 기록이 쌓이면 그걸로 바뀜) */
    fun defaultRule(name: String): Rule? {
        fun has(vararg k: String) = k.any { name.contains(it) }
        return when {
            name == HAERU || has("낙지", "소라", "박하지", "꽃게", "조개", "해삼", "골뱅이", "바지락", "맛조개", "백합", "칠게", "고둥") ->
                Rule(85, 100, ALL, "물 많이 빠지는 날 (사리 무렵) · 물때로 계산")
            has("주꾸미", "쭈꾸미") -> Rule(0, 58, setOf(3, 4, 9, 10, 11), "물살 약한 날 (조금 · 무시 전후)")
            has("갑오징어") -> Rule(0, 68, setOf(4, 5, 6, 9, 10, 11), "물살 약~중간 (조금 앞뒤)")
            has("우럭", "조피볼락") -> Rule(0, 72, ALL, "사리는 피하고 물살 약~중간")
            has("광어", "넙치") -> Rule(55, 86, ALL, "물이 적당히 흐르는 중간 물")
            has("갈치") -> Rule(45, 82, setOf(7, 8, 9, 10, 11), "중간 물 (센 사리는 피함)")
            has("감성돔", "참돔", "농어") -> Rule(75, 100, ALL, "물이 잘 움직이는 사리 전후")
            has("볼락", "문어") -> Rule(0, 62, ALL, "물살 약한 날")
            else -> null
        }
    }

    /** 앱 기록으로 계산 (내 폰 출조 · 그 어종 출조 5번 이상 · 물세기 두 구간 이상) */
    private val BUCKETS = listOf(0 to 60, 61 to 84, 85 to 100)
    fun appRule(name: String): Rule? {
        if (name == HAERU) return null
        val trips = Store.history.filter { t -> t.names.any { it == name } && t.end > t.start }
        if (trips.size < 5) return null
        val catches = DoubleArray(3); val hours = DoubleArray(3)
        trips.forEach { t ->
            val pct = Tide.estimatePct(t.start)
            val b = BUCKETS.indexOfFirst { pct in it.first..it.second }.coerceAtLeast(0)
            hours[b] += ((t.end - t.start) / 3_600_000.0).coerceAtLeast(0.25)
            catches[b] += t.catches.count { t.name(it.slot) == name }.toDouble()
        }
        val rates = (0..2).map { if (hours[it] > 0) catches[it] / hours[it] else null }
        if (rates.count { it != null } < 2) return null
        val mx = rates.filterNotNull().maxOrNull() ?: return null
        if (mx <= 0) return null
        val good = (0..2).filter { (rates[it] ?: -1.0) >= mx * 0.8 }
        val min = BUCKETS[good.first()].first; val max = BUCKETS[good.last()].second
        val label = listOf("물살 약한 날", "중간 물", "사리 무렵")
        val months = trips.map { Calendar.getInstance().apply { timeInMillis = it.start }.get(Calendar.MONTH) + 1 }.toSet()
        return Rule(min, max, if (months.size >= 2) months.flatMap { listOf(it - 1, it, it + 1) }.map { (it + 11) % 12 + 1 }.toSet() else ALL,
            good.joinToString(" · ") { label[it] } + String.format(Locale.KOREA, " (1시간 평균 %.1f마리)", mx), trips.size)
    }

    fun ruleOf(name: String): Rule? = appRule(name) ?: defaultRule(name)

    // ---------------- 어종 고르기 (기억) ----------------
    private fun prefs(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)
    fun chosen(c: Context): MutableList<String> {
        val s = prefs(c).getString("cal_species", null)
        if (s != null) return try { val a = JSONArray(s); (0 until a.length()).map { a.getString(it) }.toMutableList() } catch (_: Exception) { mutableListOf() }
        // 처음: 최근 출조 어종 3개 + 해루질
        val recent = Store.history.sortedByDescending { it.start }.flatMap { t -> t.catches.map { t.name(it.slot) } }
            .filter { !it.endsWith("번") }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(3)
        return (recent.ifEmpty { listOf("주꾸미", "갑오징어") } + HAERU).distinct().toMutableList()
    }
    private fun saveChosen(c: Context, l: List<String>) = prefs(c).edit().putString("cal_species", JSONArray(l).toString()).apply()
    private fun offSet(c: Context): MutableSet<String> = (prefs(c).getStringSet("cal_off", emptySet()) ?: emptySet()).toMutableSet()
    private fun saveOff(c: Context, s: Set<String>) = prefs(c).edit().putStringSet("cal_off", s).apply()

    // ---------------- 창 ----------------
    fun show(a: Activity, lat0: Double?, lon0: Double?) {
        fun dp(v: Int) = Ui.dp(a, v)
        val lat = lat0 ?: Sun.DEF_LAT; val lon = lon0 ?: Sun.DEF_LON
        val dlg = Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG); setPadding(dp(12), dp(10), dp(12), dp(8)) }
        val month = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        var sel = Sun.dayStart(System.currentTimeMillis())
        var warnToday = ""
        val station = Tide.stationFor(a, lat, lon)?.name ?: ""

        // 맨 위: ‹ 2026년 11월 · 인천송도 › · 닫기
        val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun nav(t: String) = Ui.text(a, 20f, Color.parseColor("#FFB27A"), true, t).apply { gravity = Gravity.CENTER; setPadding(dp(10), dp(4), dp(10), dp(4)) }
        val prev = nav("‹"); val next = nav("›")
        val title = Ui.text(a, 16f, Ui.TEXT, true, "").apply { gravity = Gravity.CENTER; maxLines = 1 }
        top.addView(prev); top.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f)); top.addView(next)
        top.addView(Ui.text(a, 13f, Ui.TEXT, true, "닫기").apply {
            gravity = Gravity.CENTER; setPadding(dp(12), dp(6), dp(12), dp(6)); background = Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE)
            setOnClickListener { dlg.dismiss() }
        })
        root.addView(top)

        val chipRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(Ui.hscroll(a, chipRow), Ui.lp(a, MATCH, WRAP, top = 8))
        val warnView = Ui.text(a, 12.5f, Color.WHITE, true, "").apply {
            visibility = View.GONE; setPadding(dp(10), dp(5), dp(10), dp(5)); background = Ui.bg(a, Color.parseColor("#8A1F28"), 10)
        }
        root.addView(warnView, Ui.lp(a, MATCH, WRAP, top = 6))
        root.addView(Ui.text(a, 11f, Ui.SUB, false, "그림 = 그 어종이 잘 잡히는 물때인 날 · 뜰채 = 물 많이 빠지는 해루질 좋은 날 · 칸 아래 막대 = 물세기"),
            Ui.lp(a, MATCH, WRAP, top = 4))

        val scroll = ScrollView(a)
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(6) })
        val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        body.addView(grid)
        val det = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(12)); background = Ui.bg(a, Ui.CARD, 14)
        }
        body.addView(det, Ui.lp(a, MATCH, WRAP, top = 10))

        val list = chosen(a)
        val off = offSet(a)
        fun active() = list.filter { it !in off }
        fun iconView(name: String, size: Int): View {
            val iv = ImageView(a)
            if (name == HAERU) iv.setImageDrawable(NetIcon(a, size))
            else { val b = SpeciesArt.bitmap(a, name); if (b != null) iv.setImageBitmap(b) else return Ui.text(a, (size * 0.6f), Ui.TEXT, true, name.take(1)).apply { gravity = Gravity.CENTER } }
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            return iv
        }

        var redraw: () -> Unit = {}
        fun paintChips() {
            chipRow.removeAllViews()
            list.forEach { n ->
                val on = n !in off
                val chip = LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(4), dp(12), dp(4))
                    background = if (on) Ui.bg(a, Color.parseColor("#FFB27A"), 99) else Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE)
                    alpha = if (on) 1f else 0.6f
                    addView(iconView(n, 20), LinearLayout.LayoutParams(dp(20), dp(20)))
                    addView(Ui.text(a, 13f, if (on) Color.parseColor("#1B0E02") else Ui.TEXT, true, n).apply { setPadding(dp(4), 0, 0, 0) })
                    setOnClickListener { if (on) off.add(n) else off.remove(n); saveOff(a, off); paintChips(); redraw() }
                    setOnLongClickListener {
                        AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("'$n' 달력에서 뺄까요?")
                            .setPositiveButton("빼기") { _, _ -> list.remove(n); off.remove(n); saveChosen(a, list); saveOff(a, off); paintChips(); redraw() }
                            .setNegativeButton("취소", null).show(); true
                    }
                }
                chipRow.addView(chip, LinearLayout.LayoutParams(WRAP, dp(34)).apply { rightMargin = dp(6) })
            }
            chipRow.addView(Ui.text(a, 13f, Ui.TEXT, true, "＋ 어종").apply {
                gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0); background = Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE)
                setOnClickListener {
                    val pool = (listOf(HAERU) + Store.species()).distinct().filter { it !in list }
                    if (pool.isEmpty()) { Ui.toast(a, "더 넣을 어종이 없어요"); return@setOnClickListener }
                    BigList.show(a, "달력에 넣을 어종", pool) { _, label -> list.add(label); saveChosen(a, list); paintChips(); redraw() }
                }
            }, LinearLayout.LayoutParams(WRAP, dp(34)))
            chipRow.addView(Ui.text(a, 11f, Ui.SUB, false, "  꾹 누르면 빼기").apply { gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(WRAP, dp(34)))
        }

        val hm = SimpleDateFormat("HH:mm", Locale.KOREA)
        val mdFmt = SimpleDateFormat("M월 d일 (E)", Locale.KOREA)
        var detId = 0
        fun line(t: CharSequence, size: Float = 13f, color: Int = Ui.TEXT, bold: Boolean = false) =
            det.addView(Ui.text(a, size, color, bold, "").apply { text = t; setLineSpacing(0f, 1.15f) }, Ui.lp(a, MATCH, WRAP, top = 4))

        fun showDetail(day: Long) {
            val id = ++detId
            det.removeAllViews()
            val noon = day + 12 * 3_600_000L
            val pct = Tide.estimatePct(noon)
            val mi = Tide.mulIndex(noon)
            val mul = when (mi) { 0 -> "무시"; 14 -> "조금"; else -> "${mi}물" }
            val ph = Moon.phase(day + 21 * 3_600_000L)
            line("${mdFmt.format(Date(day))} · $mul · 물세기 약 $pct% ${ph.emoji}", 15f, Color.parseColor("#FFB27A"), true)
            if (Sun.dayStart(System.currentTimeMillis()) == day && warnToday.isNotBlank()) line("⚠ $warnToday (${Warn.seaArea(lat, lon)})", 13f, Color.parseColor("#FF8A92"), true)
            val mo = Calendar.getInstance().apply { timeInMillis = day }.get(Calendar.MONTH) + 1
            active().forEach { n ->
                val r = ruleOf(n)
                val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(iconView(n, 22), LinearLayout.LayoutParams(dp(22), dp(22)))
                val txt = when {
                    r == null -> "$n — 아직 기록이 없어 알 수 없어요 (출조 5번 쌓이면 계산)"
                    else -> (if (r.ok(pct) && r.inSeason(mo)) "✅ " else "▫ ") + "$n — ${r.why}" +
                        (if (!r.inSeason(mo)) " · 철 아님" else "") +
                        (if (n == HAERU) "" else if (r.trips > 0) "  📊 앱 기록 ${r.trips}번" else "  📖 기본값")
                }
                row.addView(Ui.text(a, 12.5f, if (r != null && r.ok(pct) && r.inSeason(mo)) Ui.TEXT else Ui.SUB, false, txt).apply { setPadding(dp(6), 0, 0, 0) },
                    LinearLayout.LayoutParams(0, WRAP, 1f))
                det.addView(row, Ui.lp(a, MATCH, WRAP, top = 4))
            }
            Sun.times(noon, lat, lon)?.let { s -> line("🌅 일출 ${hm.format(Date(s.rise))} · 일몰 ${hm.format(Date(s.set))}", 12.5f, Ui.SUB) }
            line(Moon.line(day, lat, lon), 12.5f, Ui.SUB)
            val tideLine = Ui.text(a, 12.5f, Ui.TEXT, false, "🌊 간조 · 만조 받는 중…")
            det.addView(tideLine, Ui.lp(a, MATCH, WRAP, top = 4))
            val extra = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            det.addView(extra)
            det.addView(Ui.button(a, "이날 물때 창 열기 ›") { TideDialog.show(a, lat, lon, noon, Store.current) }
                .apply { textSize = 13f; minHeight = dp(42) }, Ui.lp(a, MATCH, WRAP, top = 8))
            Tide.fetch(a, lat, lon, noon) { d, err ->
                if (id != detId) return@fetch
                if (d == null) { tideLine.text = "🌊 ${err ?: "물때를 못 받았어요"}"; return@fetch }
                val ex = Tide.extremes(d).filter { it.time >= day && it.time < day + 86_400_000L }
                val lows = ex.filter { !it.high }; val highs = ex.filter { it.high }
                val sb = StringBuilder("🌊 간조 ")
                sb.append(lows.joinToString(" · ") { String.format(Locale.KOREA, "%s (%.0fcm)", hm.format(Date(it.time)), it.value * 100) }.ifBlank { "없음" })
                sb.append("\n    만조 ").append(highs.joinToString(" · ") { hm.format(Date(it.time)) }.ifBlank { "없음" })
                if (pct >= 85 && lows.isNotEmpty()) {
                    sb.append("\n🥅 해루질 시간: ").append(lows.joinToString(" · ") { "${hm.format(Date(it.time - 2 * 3_600_000L))}~${hm.format(Date(it.time + 30 * 60_000L))}" })
                    sb.append(" (간조 2시간 전~간조 30분 뒤 · 밀물 시작 전에 나오기)")
                }
                tideLine.text = sb
            }
            // 안개 (2주 안) · 지수 (일주일 안)
            val daysAhead = ((day - Sun.dayStart(System.currentTimeMillis())) / 86_400_000L).toInt()
            if (daysAhead in -1..14) Fog.fetch(lat, lon, day) { f ->
                if (id != detId || f == null) return@fetch
                val t = Fog.line(f); if (t.isNotBlank()) extra.addView(Ui.text(a, 12.5f, if (f.badHours.isEmpty()) Ui.SUB else Color.parseColor("#F2C14E"), f.badHours.isNotEmpty(), t), Ui.lp(a, MATCH, WRAP, top = 4))
            }
            if (daysAhead in 0..6) {
                fun idxLine(mud: Boolean) = Idx.near(a, mud, day, lat, lon) { items ->
                    if (id != detId) return@near
                    val label = if (mud) "🦶 갯벌체험지수" else "🎣 바다낚시지수"
                    val tv = Ui.text(a, 12.5f, Ui.TEXT, false, "")
                    if (items == null) { tv.text = "$label: 자료 준비 중 (공공데이터 활용신청 승인 뒤 나와요)"; tv.setTextColor(Ui.SUB) }
                    else if (items.isEmpty()) { tv.text = "$label: 가까운(50km 안) 지점이 없어요"; tv.setTextColor(Ui.SUB) }
                    else {
                        val sb = android.text.SpannableStringBuilder("$label · ${items.first().name}\n")
                        items.forEach { it2 ->
                            val s0 = sb.length
                            sb.append("   ${it2.extra.ifBlank { "" }} ${it2.index}${if (it2.score.isNotBlank()) " (${it2.score}점)" else ""}\n")
                            sb.setSpan(android.text.style.ForegroundColorSpan(Idx.color(it2.index)), s0, sb.length, 0)
                        }
                        tv.text = sb.trimEnd()
                    }
                    extra.addView(tv, Ui.lp(a, MATCH, WRAP, top = 4))
                }
                idxLine(true); idxLine(false)
            }
        }

        fun paintGrid() {
            grid.removeAllViews()
            title.text = "📅 ${month.get(Calendar.YEAR)}년 ${month.get(Calendar.MONTH) + 1}월" + if (station.isNotBlank()) " · $station" else ""
            val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { i, w ->
                head.addView(Ui.text(a, 11f, when (i) { 0 -> Color.parseColor("#FF8A92"); 6 -> Color.parseColor("#7FB8FF"); else -> Ui.SUB }, false, w).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            grid.addView(head)
            val first = month.clone() as Calendar
            val offset = first.get(Calendar.DAY_OF_WEEK) - 1
            val days = first.getActualMaximum(Calendar.DAY_OF_MONTH)
            val m = month.get(Calendar.MONTH) + 1
            val today = Sun.dayStart(System.currentTimeMillis())
            val rules = active().associateWith { ruleOf(it) }
            var row: LinearLayout? = null
            for (cell in 0 until ((offset + days + 6) / 7) * 7) {
                if (cell % 7 == 0) { row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }; grid.addView(row, Ui.lp(a, MATCH, WRAP, top = 4)) }
                val d = cell - offset + 1
                val lp = LinearLayout.LayoutParams(0, dp(62), 1f).apply { if (cell % 7 > 0) leftMargin = dp(4) }
                if (d < 1 || d > days) { row!!.addView(View(a), lp); continue }
                val day = Sun.dayStart((first.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, d); set(Calendar.HOUR_OF_DAY, 12) }.timeInMillis)
                val noon = day + 12 * 3_600_000L
                val pct = Tide.estimatePct(noon)
                val mi = Tide.mulIndex(noon)
                val good = rules.filter { (_, r) -> r != null && r.ok(pct) && r.inSeason(m) }.keys.toList()
                val f = FrameLayout(a).apply {
                    background = Ui.bg(a, if (pct >= 85) Color.parseColor("#123E31") else Color.parseColor("#0F2433"), 9,
                        when { day == today && warnToday.isNotBlank() -> Color.parseColor("#E8505B"); day == sel -> Color.parseColor("#FFB27A"); day == today -> Color.WHITE; else -> Color.TRANSPARENT })
                    setOnClickListener { sel = day; paintGrid(); showDetail(day) }
                }
                f.addView(Ui.text(a, 12f, when (cell % 7) { 0 -> Color.parseColor("#FF8A92"); 6 -> Color.parseColor("#7FB8FF"); else -> Ui.TEXT }, true,
                    "$d" + if (day == today && warnToday.isNotBlank()) " ⚠" else "").apply { setPadding(dp(4), dp(2), 0, 0) }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))
                val icons = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                good.take(3).forEach { n -> icons.addView(iconView(n, 17), LinearLayout.LayoutParams(dp(17), dp(17))) }
                f.addView(icons, FrameLayout.LayoutParams(MATCH, dp(18), Gravity.CENTER).apply { topMargin = dp(2) })
                val mul = when (mi) { 0 -> "무시"; 14 -> "조금"; else -> "${mi}물" }
                f.addView(Ui.text(a, 9f, Ui.SUB, false, Moon.phase(day + 21 * 3_600_000L).emoji + mul).apply { setPadding(dp(3), 0, 0, dp(4)); maxLines = 1 },
                    FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START))
                val bar = View(a).apply {
                    background = Ui.bg(a, when { pct >= 85 -> Ui.GREEN; pct <= 55 -> Color.parseColor("#7FB8FF"); else -> Color.parseColor("#3A5A70") }, 2)
                }
                f.addView(bar, FrameLayout.LayoutParams(0, dp(3), Gravity.BOTTOM or Gravity.START))
                f.post { bar.layoutParams = (bar.layoutParams as FrameLayout.LayoutParams).apply { width = (f.width * pct / 100f).toInt() }; bar.requestLayout() }
                row!!.addView(f, lp)
            }
        }
        redraw = { paintGrid(); showDetail(sel) }
        prev.setOnClickListener { month.add(Calendar.MONTH, -1); paintGrid() }
        next.setOnClickListener { month.add(Calendar.MONTH, 1); paintGrid() }
        paintChips(); redraw()
        // 오늘 기상특보 (내 바다 구역)
        Warn.forPlace(lat, lon) { w ->
            if (w.isNullOrBlank()) return@forPlace
            warnToday = w
            warnView.text = "⚠ 오늘 ${Warn.seaArea(lat, lon)}: $w (기상청)"; warnView.visibility = View.VISIBLE
            redraw()
        }
        dlg.setContentView(root)
        dlg.show()
    }
}
