package com.fishlog.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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

    fun set(dd: Tide.Day, t: Store.Trip?) { day = dd; trip = t; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val dy = day ?: return
        val vals = dy.values
        val stepH = dy.stepMin / 60.0
        val known = vals.filterNotNull()
        if (known.isEmpty()) return
        val exts = Tide.extremes(dy)
        val lo = minOf(known.min(), exts.minOfOrNull { it.value } ?: known.min())
        val hi = maxOf(known.max(), exts.maxOfOrNull { it.value } ?: known.max())
        val range = (hi - lo).coerceAtLeast(0.1)
        val w = width.toFloat(); val h = height.toFloat()
        val left = 12 * d; val right = w - 14 * d
        val top = 34 * d; val bottom = h - 26 * d
        fun x(hour: Double) = (left + (right - left) * hour / 24.0).toFloat()
        fun y(v: Double) = (top + (1 - (v - lo) / range) * (bottom - top)).toFloat()
        fun hourOf(t: Long) = (t - dy.dateMillis) / 3600_000.0

        // 낚시한 시간
        val t = trip
        if (t != null) {
            val s = hourOf(t.start).coerceIn(0.0, 24.0)
            val e = hourOf(if (t.end > 0) t.end else System.currentTimeMillis()).coerceIn(0.0, 24.0)
            if (e > s) canvas.drawRect(x(s), top - 6 * d, x(e), bottom, win)
        }
        // 1시간 세로선 (6시간마다 진하게)
        for (hh in 0..24) canvas.drawLine(x(hh.toDouble()), top - 6 * d, x(hh.toDouble()), bottom, if (hh % 6 == 0) gridThick else gridThin)

        // 부드러운 곡선 (점 사이를 2차 곡선으로 이음)
        val pts = vals.mapIndexedNotNull { i, v -> v?.let { x(i * stepH) to y(it) } }
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

        // 지금
        val nowH = hourOf(System.currentTimeMillis())
        if (nowH in 0.0..24.0) {
            val nx = x(nowH)
            canvas.drawLine(nx, top - 6 * d, nx, bottom, nowLine)
            val pw = 30 * d
            val px = nx.coerceIn(left + pw / 2, right - pw / 2)
            canvas.drawRoundRect(px - pw / 2, 2 * d, px + pw / 2, 16 * d, 7 * d, 7 * d, nowPill)
            canvas.drawText("지금", px, 13 * d, nowTxt)
        }

        // 조과 점
        t?.catches?.forEach { c ->
            val v = Tide.valueAt(dy, c.time) ?: return@forEach
            val cx = x(hourOf(c.time)); val cy = y(v)
            dot.color = Ui.SLOT[c.slot - 1]
            canvas.drawCircle(cx, cy, 4 * d, dot)
            canvas.drawCircle(cx, cy, 4 * d, ring)
        }

        // 만조·간조 (글씨는 그래프 안쪽, 화면 밖으로 안 나가게)
        val tf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA)
        exts.forEach { e ->
            val hx = x(hourOf(e.time)); val hy = y(e.value)
            if (hourOf(e.time) < 0 || hourOf(e.time) > 24) return@forEach
            dot.color = Ui.TEXT
            canvas.drawCircle(hx, hy, 3.5f * d, dot)
            val label = (if (e.high) "만조 " else "간조 ") + tf.format(java.util.Date(e.time))
            val half = ext.measureText(label) / 2
            val lx = hx.coerceIn(left + half, right - half)
            val ly = if (e.high) (hy - 9 * d).coerceAtLeast(30 * d) else (hy - 12 * d)
            canvas.drawText(label, lx, ly, ext)
        }

        // 시간 숫자: 3시간마다 크게, 사이 매시간 작게
        val ty = bottom + 14 * d
        for (hh in 0..24) {
            if (hh % 3 == 0) canvas.drawText(hh.toString(), x(hh.toDouble()), ty, major)
            else canvas.drawText(hh.toString(), x(hh.toDouble()), ty - 1 * d, minor)
        }
        canvas.drawText("시", (right + w) / 2 + 2 * d, ty + 11 * d, small)
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
