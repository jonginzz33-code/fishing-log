package com.fishlog.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** 모든 화면이 같이 쓰는 색과 모양 (디자인 시안과 동일) */
object Ui {
    val BG = Color.parseColor("#0B1A26")
    val CARD = Color.parseColor("#12293A")
    val LINE = Color.parseColor("#3A5A70")
    val TEXT = Color.parseColor("#EAF2F6")
    val SUB = Color.parseColor("#9FB4C2")
    val ORANGE = Color.parseColor("#F28C38")
    val BLUE = Color.parseColor("#5BC8DF")
    val GREEN = Color.parseColor("#5FD39A")
    val SLOT = intArrayOf(ORANGE, BLUE)

    const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun bg(c: Context, color: Int, radius: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(c, radius).toFloat()
            if (stroke != 0) setStroke(dp(c, 1), stroke)
        }

    fun text(c: Context, size: Float, color: Int = TEXT, bold: Boolean = false, value: String = ""): TextView =
        TextView(c).apply {
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            text = value
        }

    fun button(c: Context, label: String, filled: Boolean = false, onClick: (() -> Unit)? = null): TextView =
        text(c, 15f, if (filled) BG else TEXT, true, label).apply {
            gravity = Gravity.CENTER
            minHeight = dp(c, 52)
            setPadding(dp(c, 12), 0, dp(c, 12), 0)
            background = if (filled) bg(c, TEXT, 14) else bg(c, Color.TRANSPARENT, 14, LINE)
            isClickable = true
            if (onClick != null) setOnClickListener { onClick() }
        }

    /** 카드 오른쪽 위 작은 칩 (예: 사진 3) */
    fun chip(c: Context, label: String, color: Int = BLUE): TextView =
        text(c, 12f, BG, true, label).apply {
            gravity = Gravity.CENTER
            setPadding(dp(c, 9), dp(c, 3), dp(c, 9), dp(c, 3))
            background = bg(c, color, 99)
        }

    /** 오늘과 비교: 오늘 · 어제 · N일 전 · N개월 전 · N년 · N년 N개월 전 (두 번째 = 몇 년 전 같은 달) */
    fun ago(time: Long): Pair<String, Boolean> {
        val a = java.util.Calendar.getInstance().apply { timeInMillis = time }
        val b = java.util.Calendar.getInstance()
        fun dayNo(c: java.util.Calendar) = (c.timeInMillis + c.get(java.util.Calendar.ZONE_OFFSET) + c.get(java.util.Calendar.DST_OFFSET)) / 86_400_000L
        val days = dayNo(b) - dayNo(a)
        if (days <= 0L) return "오늘" to false
        if (days == 1L) return "어제" to false
        var months = (b.get(java.util.Calendar.YEAR) - a.get(java.util.Calendar.YEAR)) * 12 + (b.get(java.util.Calendar.MONTH) - a.get(java.util.Calendar.MONTH))
        if (b.get(java.util.Calendar.DAY_OF_MONTH) < a.get(java.util.Calendar.DAY_OF_MONTH)) months--
        if (months < 1) return "${days}일 전" to false
        val y = months / 12; val m = months % 12
        val sameMonth = y >= 1 && a.get(java.util.Calendar.MONTH) == b.get(java.util.Calendar.MONTH)
        val s = when { y == 0 -> "${m}개월 전"; m == 0 -> "${y}년 전"; else -> "${y}년 ${m}개월 전" }
        return (if (sameMonth) "$s · 같은 달" else s) to sameMonth
    }

    /** 날짜 옆 작은 표시 (오늘·어제 초록, 몇 년 전 같은 달 노랑, 나머지 회색) */
    fun agoChip(c: Context, time: Long): TextView {
        val (s, same) = ago(time)
        val recent = s == "오늘" || s == "어제"
        val fg = when { recent -> GREEN; same -> Color.parseColor("#F2C14E"); else -> SUB }
        val bgc = when { recent -> Color.parseColor("#16352A"); same -> Color.parseColor("#2A2310"); else -> Color.parseColor("#1E3247") }
        return text(c, 11f, fg, true, s).apply {
            setPadding(dp(c, 8), dp(c, 1), dp(c, 8), dp(c, 1))
            background = bg(c, bgc, 99)
        }
    }

    fun lp(c: Context, w: Int, h: Int, weight: Float = 0f, top: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(c, top) }

    fun card(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = bg(c, CARD, 14)
        setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
    }

    fun column(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(c, 20), dp(c, 24), dp(c, 20), dp(c, 32))
    }

    /**
     * 카드 오른쪽에 대표 조과 사진 붙이기: 카드 안 내용을 왼쪽 칸으로 옮기고 오른쪽에 사진 (84dp)
     * more = 다른 사진 수 (+N 표시)
     */
    fun withRightPhoto(c: Context, card: LinearLayout, bmp: android.graphics.Bitmap?, more: Int = 0, onClick: (() -> Unit)? = null) {
        if (bmp == null) return
        val kids = (0 until card.childCount).map { card.getChildAt(it) to (card.getChildAt(it).layoutParams) }
        card.removeAllViews()
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val left = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        kids.forEach { (v, lp) -> left.addView(v, lp ?: LinearLayout.LayoutParams(MATCH, WRAP)) }
        row.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))
        val f = android.widget.FrameLayout(c)
        f.addView(android.widget.ImageView(c).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; clipToOutline = true
            background = bg(c, Color.parseColor("#0F2233"), 12)
            setImageBitmap(bmp)
            if (onClick != null) setOnClickListener { onClick() }
        }, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        if (more > 0) f.addView(text(c, 10f, Color.WHITE, true, "+$more").apply {
            setPadding(dp(c, 5), 0, dp(c, 5), 0); background = bg(c, Color.argb(140, 0, 0, 0), 8)
        }, android.widget.FrameLayout.LayoutParams(WRAP, WRAP, android.view.Gravity.BOTTOM or android.view.Gravity.END).apply { rightMargin = dp(c, 4); bottomMargin = dp(c, 4) })
        row.addView(f, LinearLayout.LayoutParams(dp(c, 84), dp(c, 84)).apply { leftMargin = dp(c, 10) })
        card.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    fun row(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** 숫자 타일 (총 조과 / 어종별) */
    fun stat(c: Context, label: String, value: String, labelColor: Int = SUB): LinearLayout = card(c).apply {
        addView(text(c, 12f, labelColor, false, label))
        addView(text(c, 24f, TEXT, true, value).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) })
    }
}

/** 시간대별 입질 막대 그래프 */
class BarView(c: Context) : View(c) {
    private var start = 0
    private var hours = 0
    private var a = IntArray(0)
    private var b = IntArray(0)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.SUB; textSize = Ui.dp(c, 11).toFloat(); textAlign = Paint.Align.CENTER }

    fun set(t: Store.Trip) {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = t.start
        start = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val endTime = if (t.end > 0) t.end else System.currentTimeMillis()
        hours = (((endTime - t.start) / 3600_000L).toInt() + 2).coerceIn(1, 24)
        a = IntArray(hours); b = IntArray(hours)
        val base = t.start - (cal.get(java.util.Calendar.MINUTE) * 60_000L + cal.get(java.util.Calendar.SECOND) * 1000L)
        t.catches.forEach {
            val i = ((it.time - base) / 3600_000L).toInt()
            if (i in 0 until hours) { if (it.slot == 1) a[i]++ else b[i]++ }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (hours == 0) return
        val w = width.toFloat(); val h = height.toFloat()
        val labelH = Ui.dp(context, 16).toFloat()
        val chartH = h - labelH
        val max = (0 until hours).maxOf { a[it] + b[it] }.coerceAtLeast(1)
        val slot = w / hours
        val bw = slot * 0.6f
        for (i in 0 until hours) {
            val x = i * slot + (slot - bw) / 2
            val ha = chartH * a[i] / max
            val hb = chartH * b[i] / max
            p.color = Ui.ORANGE
            canvas.drawRect(x, chartH - ha, x + bw, chartH, p)
            p.color = Ui.BLUE
            canvas.drawRect(x, chartH - ha - hb, x + bw, chartH - ha, p)
            if (hours <= 12 || i % 2 == 0) canvas.drawText("${(start + i) % 24}시", x + bw / 2, h - 2, tp)
        }
    }
}

/** 조위 곡선 + 1시간 세로선 + 낚시한 시간 + 조과 점 + 지금 */
class TideView(c: Context) : View(c) {
    private var day: Tide.Day? = null
    private var trip: Store.Trip? = null
    private val d = c.resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.BLUE; style = Paint.Style.STROKE; strokeWidth = 2.5f * d
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 30, 106, 134) }
    private val win = Paint().apply { color = Color.parseColor("#1C3A4F") }
    private val gridThin = Paint().apply { color = Color.parseColor("#2A4A60"); strokeWidth = 0.6f * d }
    private val gridThick = Paint().apply { color = Color.parseColor("#3E6580"); strokeWidth = 1.1f * d }
    private val nowLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.GREEN; strokeWidth = 1.3f * d; style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(4 * d, 3 * d), 0f)
    }
    private val nowPill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.GREEN }
    private val nowTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.BG; textSize = 10 * d; textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d; color = Ui.BG }
    private val ext = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.TEXT; textSize = 11 * d; textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val major = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CFE0EA"); textSize = 11 * d; textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val minor = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#6F8797"); textSize = 8 * d; textAlign = Paint.Align.CENTER }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.SUB; textSize = 10 * d; textAlign = Paint.Align.CENTER }
    // 물높이 (노랑): 세로 눈금 · 만조·간조 높이 · 지금 높이
    private val YELLOW = Color.parseColor("#F2C14E")
    private val axisTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = YELLOW; textSize = 9 * d; textAlign = Paint.Align.RIGHT }
    private val axisLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 242, 193, 78); strokeWidth = 0.7f * d
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(3 * d, 4 * d), 0f)
    }
    private val hTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = YELLOW; textSize = 10.5f * d; textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val chipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#12293A") }
    private val chipEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1 * d }
    private val chipTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f * d; textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD }
    private val nowHPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = YELLOW }
    private val nowHTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#412402"); textSize = 9.5f * d; textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    // 일출·일몰: 밤은 어둡게, 해 뜨고 지는 시각에 점선
    private var sun: Sun.Times? = null
    private val nightP = Paint().apply { color = Color.argb(185, 3, 7, 14) }
    /** 낮(일출~일몰)은 밝게 */
    private val dayP = Paint().apply { color = Color.argb(70, 232, 241, 247) }
    private val dayTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F2C14E"); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val nightTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8C84C8"); textSize = 10 * d; textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val riseLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F2C14E"); strokeWidth = 1.2f * d; style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(3 * d, 3 * d), 0f)
    }
    private val setLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B9A6FF"); strokeWidth = 1.2f * d; style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(3 * d, 3 * d), 0f)
    }
    private val sunTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f * d; typeface = android.graphics.Typeface.DEFAULT_BOLD }

    // ---- 보이는 구간: 하루(0~24시) 또는 '지금' 가운데 (앞뒤 12시간, 어제·내일 물때 이어 붙임) ----
    private var days: List<Tide.Day> = emptyList()
    private var centerNow = false
    private var panMs = 0L
    private var sLat: Double? = null; private var sLon: Double? = null
    private val resetPill = RectF()

    /** lat/lon 이 없으면 출조 위치 → 서해 중부 기준으로 일출·일몰 계산 */
    fun set(dd: Tide.Day, t: Store.Trip?, lat: Double? = null, lon: Double? = null) {
        day = dd; trip = t; days = listOf(dd); centerNow = false; panMs = 0L
        val loc = if (lat != null && lon != null) lat to lon else t?.anyLocation()
        sLat = loc?.first; sLon = loc?.second
        sun = Sun.times(dd.dateMillis + 12 * 3_600_000L, sLat, sLon)
        invalidate()
    }

    /** 오늘: '지금'을 가운데 고정 (prev·next = 어제·내일 물때, 없으면 있는 만큼만) */
    fun setCentered(prev: Tide.Day?, cur: Tide.Day, next: Tide.Day?, t: Store.Trip?, lat: Double?, lon: Double?) {
        set(cur, t, lat, lon)
        days = listOfNotNull(prev, cur, next).sortedBy { it.dateMillis }
        centerNow = true
        invalidate()
    }

    private fun startMs(): Long {
        val dy = day ?: return 0L
        if (!centerNow) return dy.dateMillis
        val lo = days.first().dateMillis; val hi = days.last().dateMillis
        return (System.currentTimeMillis() - 12 * 3_600_000L + panMs).coerceIn(lo, hi)
    }
    private fun valueAt(t: Long): Double? = days.firstNotNullOfOrNull { Tide.valueAt(it, t) }

    // 손가락으로 밀어서 앞뒤로 (가운데 고정 모드에서만)
    private var downX = 0f; private var lastX = 0f; private var dragging = false
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (!centerNow) return super.onTouchEvent(e)
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { downX = e.x; lastX = e.x; dragging = false; return true }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (!dragging && kotlin.math.abs(e.x - downX) > 8 * d) { dragging = true; parent?.requestDisallowInterceptTouchEvent(true) }
                if (dragging) {
                    val msPerPx = 24 * 3_600_000.0 / (width - 48 * d)
                    panMs -= ((e.x - lastX) * msPerPx).toLong()
                    panMs = panMs.coerceIn(-36 * 3_600_000L, 36 * 3_600_000L)
                    lastX = e.x; invalidate()
                }
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                if (!dragging && panMs != 0L && resetPill.contains(e.x, e.y)) { panMs = 0L; invalidate() }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    /** 눈금 간격(cm): 범위에 맞춰 4~6칸 */
    private fun niceStep(rangeCm: Double): Int =
        listOf(10, 20, 50, 100, 200, 250, 500).firstOrNull { rangeCm / it <= 6 } ?: 500

    override fun onDraw(canvas: Canvas) {
        val dy = day ?: return
        val start = startMs()
        val end = start + 24 * 3_600_000L
        val now = System.currentTimeMillis()
        // 구간 안을 10분마다
        val samples = (0..144).map { i -> val tt = start + i * 600_000L; tt to valueAt(tt) }
        val known = samples.mapNotNull { it.second }
        if (known.isEmpty()) return
        val exts = days.flatMap { Tide.extremes(it) }.sortedBy { it.time }
            .fold(mutableListOf<Tide.Extreme>()) { acc, e -> if (acc.none { kotlin.math.abs(it.time - e.time) < 40 * 60_000L }) acc.add(e); acc }
        val winExts = exts.filter { it.time in start..end }
        val lo = minOf(known.min(), winExts.minOfOrNull { it.value } ?: known.min())
        val hi = maxOf(known.max(), winExts.maxOfOrNull { it.value } ?: known.max())
        val range = (hi - lo).coerceAtLeast(0.1)
        val w = width.toFloat(); val h = height.toFloat()
        val left = 34 * d; val right = w - 14 * d
        val lay = Store.tideLayers
        val showAxis = lay and 1 != 0; val showExt = lay and 2 != 0; val showDiff = lay and 4 != 0
        val sunOn = sun != null && lay and 8 != 0; val showNow = lay and 16 != 0
        val top = 44 * d; val bottom = h - (if (sunOn) 42 else 26) * d
        fun hourOf(t: Long) = (t - start) / 3600_000.0
        fun x(hour: Double) = (left + (right - left) * hour / 24.0).toFloat()
        fun xt(t: Long) = x(hourOf(t))
        fun y(v: Double) = (top + (1 - (v - lo) / range) * (bottom - top)).toFloat()

        // 해·밤: 구간에 걸친 날마다 일출·일몰
        val suns = if (!sunOn) emptyList() else (-1..1).mapNotNull { k ->
            Sun.times(start + k * 86_400_000L + 12 * 3_600_000L, sLat, sLon)
        }
        if (sunOn && suns.isNotEmpty()) {
            // 밤 = 일몰 ~ 다음 일출
            val marks = suns.flatMap { listOf(it.rise to true, it.set to false) }.sortedBy { it.first }
            // 낮 = 일출 ~ 일몰 (밝게)
            dayTxt.textSize = nightTxt.textSize
            suns.forEach { sd ->
                val a = maxOf(sd.rise, start); val b = minOf(sd.set, end)
                if (b > a) {
                    canvas.drawRect(xt(a), top - 6 * d, xt(b), bottom, dayP)
                    if ((b - a) >= 2.5 * 3_600_000L) canvas.drawText("☀ 낮", xt((a + b) / 2), top + 12 * d, dayTxt)
                }
            }
            var nightStart = start
            var inNight = marks.firstOrNull()?.second == true  // 첫 표시가 일출이면 그 전은 밤
            marks.forEach { (tm, isRise) ->
                if (isRise && inNight) {
                    val a = maxOf(nightStart, start); val b = minOf(tm, end)
                    if (b > a) {
                        canvas.drawRect(xt(a), top - 6 * d, xt(b), bottom, nightP)
                        if ((b - a) >= 2.5 * 3_600_000L) canvas.drawText("☾ 밤", xt((a + b) / 2), top + 12 * d, nightTxt)
                    }
                    inNight = false
                } else if (!isRise) { nightStart = tm; inNight = true }
            }
            if (inNight && nightStart < end) {
                val a = maxOf(nightStart, start)
                canvas.drawRect(xt(a), top - 6 * d, xt(end), bottom, nightP)
                if ((end - a) >= 2.5 * 3_600_000L) canvas.drawText("☾ 밤", xt((a + end) / 2), top + 12 * d, nightTxt)
            }
        }
        // 낚시한 시간
        val t = trip
        if (t != null) {
            val s = maxOf(t.start, start); val e = minOf(if (t.end > 0) t.end else now, end)
            if (e > s) canvas.drawRect(xt(s), top - 6 * d, xt(e), bottom, win)
        }
        // 물높이 눈금
        if (showAxis) run {
            val stepCm = niceStep(range * 100)
            var cm = Math.ceil(lo * 100 / stepCm).toInt() * stepCm
            while (cm <= hi * 100 + 0.01) {
                val yy = y(cm / 100.0)
                canvas.drawLine(left, yy, right, yy, axisLine)
                canvas.drawText(cm.toString(), left - 4 * d, yy + 3 * d, axisTxt)
                cm += stepCm
            }
            canvas.drawText("cm", left - 4 * d, top - 10 * d, axisTxt)
        }
        // 1시간 세로선 (시계 기준 정각, 6시간마다 진하게)
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Seoul"))
        val firstHour = ((start + 3_599_999L) / 3_600_000L) * 3_600_000L
        val hourMarks = generateSequence(firstHour) { it + 3_600_000L }.takeWhile { it <= end }.toList()
        hourMarks.forEach { hm ->
            cal.timeInMillis = hm
            val ch = cal.get(java.util.Calendar.HOUR_OF_DAY)
            canvas.drawLine(xt(hm), top - 6 * d, xt(hm), bottom, if (ch % 6 == 0) gridThick else gridThin)
        }

        // 곡선
        val pts = samples.mapNotNull { (tt, v) -> v?.let { xt(tt) to y(it) } }
        if (pts.size >= 2) {
            val path = Path()
            path.moveTo(pts[0].first, pts[0].second)
            for (i in 1 until pts.size - 1) {
                val mx = (pts[i].first + pts[i + 1].first) / 2
                val my = (pts[i].second + pts[i + 1].second) / 2
                path.quadTo(pts[i].first, pts[i].second, mx, my)
            }
            path.lineTo(pts.last().first, pts.last().second)
            val area = Path(path)
            area.lineTo(pts.last().first, bottom); area.lineTo(pts[0].first, bottom); area.close()
            canvas.drawPath(area, fill)
            canvas.drawPath(path, line)
        }

        // 만조↔간조 높이 차
        if (showDiff) for (i in 0 until exts.size - 1) {
            val a = exts[i]; val b = exts[i + 1]
            val mid = (a.time + b.time) / 2
            val mh = hourOf(mid)
            if (mh < 0.5 || mh > 23.5) continue
            val mv = valueAt(mid) ?: continue
            val up = b.value > a.value
            val txt = (if (up) "↑ " else "↓ ") + Math.round(kotlin.math.abs(b.value - a.value) * 100) + "cm"
            val col = if (up) Ui.GREEN else Color.parseColor("#F07AA0")
            val tw = chipTxt.measureText(txt) + 10 * d
            val cx = x(mh).coerceIn(left + tw / 2, right - tw / 2)
            val cy = y(mv)
            chipEdge.color = col; chipTxt.color = col
            canvas.drawRoundRect(cx - tw / 2, cy - 8 * d, cx + tw / 2, cy + 8 * d, 8 * d, 8 * d, chipBg)
            canvas.drawRoundRect(cx - tw / 2, cy - 8 * d, cx + tw / 2, cy + 8 * d, 8 * d, 8 * d, chipEdge)
            canvas.drawText(txt, cx, cy + 3.5f * d, chipTxt)
        }

        // 날짜가 바뀌는 곳 (자정): 보라 점선 + 날짜
        if (centerNow) hourMarks.forEach { hm ->
            cal.timeInMillis = hm
            if (cal.get(java.util.Calendar.HOUR_OF_DAY) == 0 && hm > start && hm < end) {
                canvas.drawLine(xt(hm), top - 6 * d, xt(hm), bottom, setLine)
                val lbl = java.text.SimpleDateFormat("M/d(E)", java.util.Locale.KOREA).format(java.util.Date(hm)) + " →"
                sunTxt.textAlign = Paint.Align.LEFT; sunTxt.color = setLine.color
                canvas.drawText(lbl, xt(hm) + 3 * d, bottom - 4 * d, sunTxt)
            }
        }

        // 지금: 선 + 맨 위 "지금 08:35" + 지금 물높이
        val nowH = hourOf(now)
        if (showNow && nowH in 0.0..24.0) {
            val nx = x(nowH)
            valueAt(now)?.let { nv ->
                val ny = y(nv)
                canvas.drawCircle(nx, ny, 4 * d, nowHPaint)
                canvas.drawCircle(nx, ny, 4 * d, ring)
                val txt = "${Math.round(nv * 100)}cm"
                val tw = nowHTxt.measureText(txt) + 10 * d
                val cx = (nx - tw / 2 - 6 * d).let { if (it - tw / 2 < left) nx + tw / 2 + 6 * d else it }
                canvas.drawRoundRect(cx - tw / 2, ny - 8 * d, cx + tw / 2, ny + 8 * d, 8 * d, 8 * d, nowHPaint)
                canvas.drawText(txt, cx, ny + 3.5f * d, nowHTxt)
            }
            nowLine.pathEffect = null; nowLine.strokeWidth = 1.8f * d
            canvas.drawLine(nx, top - 6 * d, nx, bottom, nowLine)
            val label = "지금 " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date(now))
            nowTxt.textSize = 11 * d
            val pw = nowTxt.measureText(label) + 14 * d
            val px = nx.coerceIn(left + pw / 2, right - pw / 2)
            canvas.drawRoundRect(px - pw / 2, 1 * d, px + pw / 2, 17 * d, 8 * d, 8 * d, nowPill)
            canvas.drawText(label, px, 13 * d, nowTxt)
        }
        // 밀어서 옮겼으면 오른쪽 위에 '지금으로'
        if (centerNow && panMs != 0L) {
            val lbl = "↺ 지금으로"
            chipTxt.color = Ui.GREEN
            val pw = chipTxt.measureText(lbl) + 14 * d
            resetPill.set(right - pw, 1 * d, right, 18 * d)
            chipEdge.color = Ui.GREEN
            canvas.drawRoundRect(resetPill, 8 * d, 8 * d, chipBg)
            canvas.drawRoundRect(resetPill, 8 * d, 8 * d, chipEdge)
            canvas.drawText(lbl, resetPill.centerX(), 13 * d, chipTxt)
        } else resetPill.setEmpty()

        // 조과 점
        t?.catches?.forEach { c ->
            if (c.time !in start..end) return@forEach
            val v = valueAt(c.time) ?: return@forEach
            dot.color = if (c.slot in 1..Ui.SLOT.size) Ui.SLOT[c.slot - 1] else Store.slotColor(c.slot)
            canvas.drawCircle(xt(c.time), y(v), 4 * d, dot)
            canvas.drawCircle(xt(c.time), y(v), 4 * d, ring)
        }

        // 만조·간조
        val tf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA)
        if (showExt) winExts.forEach { e ->
            val hx = xt(e.time); val hy = y(e.value)
            dot.color = Ui.TEXT
            canvas.drawCircle(hx, hy, 3.5f * d, dot)
            val label = (if (e.high) "만조 " else "간조 ") + tf.format(java.util.Date(e.time))
            val hLabel = "${Math.round(e.value * 100)}cm"
            val half = ext.measureText(label) / 2
            val lx = hx.coerceIn(left + half, right - half)
            val ly = if (e.high) (hy - 20 * d).coerceAtLeast(30 * d) else (hy - 22 * d)
            canvas.drawText(label, lx, ly, ext)
            canvas.drawText(hLabel, lx, ly + 11 * d, hTxt)
        }

        // 일출·일몰 점선 + 시각
        if (sunOn) {
            val tf2 = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA)
            val sy = bottom + 32 * d
            sunTxt.textAlign = Paint.Align.CENTER
            suns.forEach { s ->
                if (s.rise in start..end) {
                    val rx = xt(s.rise)
                    canvas.drawLine(rx, top - 6 * d, rx, bottom, riseLine)
                    sunTxt.color = riseLine.color
                    val t1 = "☀ 일출 " + tf2.format(java.util.Date(s.rise))
                    val hw = sunTxt.measureText(t1) / 2
                    canvas.drawText(t1, rx.coerceIn(left + hw, right - hw), sy, sunTxt)
                }
                if (s.set in start..end) {
                    val sx = xt(s.set)
                    canvas.drawLine(sx, top - 6 * d, sx, bottom, setLine)
                    sunTxt.color = setLine.color
                    val t2 = "☾ 일몰 " + tf2.format(java.util.Date(s.set))
                    val hw = sunTxt.measureText(t2) / 2
                    canvas.drawText(t2, sx.coerceIn(left + hw, right - hw), sy, sunTxt)
                }
            }
        }

        // 시간 숫자: 3시간마다 크게, 사이 매시간 작게
        val ty = bottom + 14 * d
        hourMarks.forEach { hm ->
            cal.timeInMillis = hm
            val ch = cal.get(java.util.Calendar.HOUR_OF_DAY)
            val shown = if (!centerNow && hm == end) 24 else ch
            if (ch % 3 == 0) canvas.drawText(shown.toString(), xt(hm), ty, major)
            else canvas.drawText(shown.toString(), xt(hm), ty - 1 * d, minor)
        }
        canvas.drawText("시", (right + w) / 2 + 2 * d, ty + 11 * d, small)
    }
}

/**
 * 물때 그래프 위 "그래프에 보기" 체크 칩: 켜고 끄면 바로 그래프에 반영, 고른 건 저장
 */
object TideLayers {
    private val ITEMS = listOf(Triple(1, "cm 눈금", Color.parseColor("#F2C14E")), Triple(2, "만조·간조", Color.parseColor("#EAF1F7")),
        Triple(4, "높이 차 ↑↓", Color.parseColor("#F07AA0")), Triple(8, "일출·일몰·밤", Color.parseColor("#B9A6FF")),
        Triple(16, "지금 (오늘만)", Ui.GREEN))

    fun view(c: Context, onChange: () -> Unit): View {
        val box = android.widget.LinearLayout(c).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val head = android.widget.LinearLayout(c).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        head.addView(Ui.text(c, 13f, Ui.TEXT, true, "그래프에 보기"), android.widget.LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        val chips = android.widget.LinearLayout(c).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        fun small(label: String, click: () -> Unit) = Ui.text(c, 12f, Ui.TEXT, true, label).apply {
            gravity = android.view.Gravity.CENTER; setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 10), 0)
            background = Ui.bg(c, Color.TRANSPARENT, 99, Ui.LINE)
            setOnClickListener { click() }
        }
        lateinit var paint: () -> Unit
        head.addView(small("모두 보기") { Store.tideLayers = 31; paint(); onChange() }, android.widget.LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(c, 30)))
        head.addView(small("물높이만") { Store.tideLayers = 0; paint(); onChange() },
            android.widget.LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(c, 30)).apply { leftMargin = Ui.dp(c, 6) })
        box.addView(head)
        paint = {
            chips.removeAllViews()
            val lay = Store.tideLayers
            ITEMS.forEach { (bit, label, col) ->
                val on = lay and bit != 0
                chips.addView(Ui.text(c, 12f, if (on) Color.parseColor("#0F1B2A") else Ui.SUB, true, (if (on) "✓ " else "□ ") + label).apply {
                    gravity = android.view.Gravity.CENTER; setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 10), 0)
                    background = if (on) Ui.bg(c, col, 99) else Ui.bg(c, Color.TRANSPARENT, 99, Ui.LINE)
                    setOnClickListener { Store.tideLayers = Store.tideLayers xor bit; paint(); onChange() }
                }, android.widget.LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(c, 32)).apply { rightMargin = Ui.dp(c, 6) })
            }
        }
        paint()
        box.addView(android.widget.HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(chips) },
            android.widget.LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(c, 6) })
        return box
    }
}

/** 시간별 물흐름 세기 막대 (0~23시). 약함 = 어두운 파랑, 보통 = 하늘, 셈 = 주황, 지금 = 초록 테두리 */
class FlowBarView(c: Context) : View(c) {
    private var day: Tide.Day? = null
    private var st: Tide.Strength? = null
    private val d = c.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; color = Ui.GREEN }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.SUB; textSize = 10 * d; textAlign = Paint.Align.CENTER }
    private val WEAK = Color.parseColor("#3E6580")

    fun set(dd: Tide.Day, s: Tide.Strength) { day = dd; st = s; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val dy = day ?: return
        val s = st ?: return
        val max = s.maxRate
        if (max <= 0) return
        val w = width.toFloat(); val h = height.toFloat()
        val left = 12 * d; val right = w - 14 * d
        val bottom = h - 14 * d
        val slot = (right - left) / 24
        val nowH = ((System.currentTimeMillis() - dy.dateMillis) / 3_600_000L).toInt()
        for (i in 0 until 24) {
            val r = s.hourly.getOrNull(i) ?: continue
            val f = (r / max).toFloat()
            p.color = when { f < 0.35f -> WEAK; f < 0.7f -> Ui.BLUE; else -> Ui.ORANGE }
            val x0 = left + i * slot + slot * 0.15f
            val x1 = left + (i + 1) * slot - slot * 0.15f
            val top = bottom - (bottom - 4 * d) * f.coerceAtLeast(0.05f)
            canvas.drawRoundRect(x0, top, x1, bottom, 2 * d, 2 * d, p)
            if (i == nowH) canvas.drawRoundRect(x0 - 1.5f * d, top - 1.5f * d, x1 + 1.5f * d, bottom + 1.5f * d, 3 * d, 3 * d, nowP)
        }
        for (hh in 0..24 step 3) canvas.drawText(hh.toString(), left + hh * slot, h - 2 * d, tp)
    }
}

/** 그룹장 표시용 작은 왕관 */
class CrownView(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F2C14E") }
    private val gem = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F07AA0") }
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val path = Path().apply {
            moveTo(w * 0.08f, h * 0.82f)
            lineTo(w * 0.04f, h * 0.30f)
            lineTo(w * 0.30f, h * 0.55f)
            lineTo(w * 0.50f, h * 0.12f)
            lineTo(w * 0.70f, h * 0.55f)
            lineTo(w * 0.96f, h * 0.30f)
            lineTo(w * 0.92f, h * 0.82f)
            close()
        }
        canvas.drawPath(path, p)
        canvas.drawRect(w * 0.08f, h * 0.84f, w * 0.92f, h * 0.96f, p)
        canvas.drawCircle(w * 0.50f, h * 0.62f, h * 0.09f, gem)
    }
}

/** 장갑 끼고도 누르기 쉬운 큰 목록 창 */
object BigList {
    fun show(a: android.app.Activity, title: String, items: List<String>, tail: List<String> = emptyList(),
             onPick: (index: Int, label: String) -> Unit): android.app.AlertDialog {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(a, 16), Ui.dp(a, 4), Ui.dp(a, 16), Ui.dp(a, 4))
        }
        val dlg = android.app.AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setView(android.widget.ScrollView(a).apply { addView(col) })
            .setNegativeButton("닫기", null)
            .create()
        (items + tail).forEachIndexed { i, label ->
            val isTail = i >= items.size
            col.addView(Ui.text(a, if (isTail) 16f else 19f, if (isTail) Ui.BLUE else Ui.TEXT, !isTail, label).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                minHeight = Ui.dp(a, 54)
                setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), 0)
                background = Ui.bg(a, Ui.CARD, 12, Ui.LINE)
                setOnClickListener { dlg.dismiss(); onPick(i, label) }
            }, Ui.lp(a, Ui.MATCH, Ui.WRAP, top = 8))
        }
        dlg.show()
        return dlg
    }
}
