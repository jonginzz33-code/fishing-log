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

/** 조위 곡선 + 낚시한 시간 + 조과 점 */
class TideView(c: Context) : View(c) {
    private var day: Tide.Day? = null
    private var trip: Store.Trip? = null
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.BLUE; style = Paint.Style.STROKE; strokeWidth = Ui.dp(c, 2).toFloat() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 30, 106, 134) }
    private val win = Paint().apply { color = Color.parseColor("#1C3A4F") }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TEXT; textSize = Ui.dp(c, 11).toFloat(); textAlign = Paint.Align.CENTER }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.SUB; textSize = Ui.dp(c, 10).toFloat(); textAlign = Paint.Align.CENTER }

    fun set(d: Tide.Day, t: Store.Trip?) { day = d; trip = t; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val d = day ?: return
        val vals = d.values
        val hr = d.stepMin / 60.0
        val known = vals.filterNotNull()
        if (known.isEmpty()) return
        val lo = known.min(); val hi = known.max()
        val range = (hi - lo).coerceAtLeast(0.1)
        val padTop = Ui.dp(context, 30).toFloat(); val padBot = Ui.dp(context, 30).toFloat()
        val w = width.toFloat(); val h = height.toFloat()
        val n = (vals.size - 1) * hr
        fun x(hour: Double) = (hour / n * w).toFloat()
        fun y(v: Double) = (padTop + (1 - (v - lo) / range) * (h - padTop - padBot)).toFloat()

        val t = trip
        if (t != null) {
            val s = (t.start - d.dateMillis) / 3600_000.0
            val e = ((if (t.end > 0) t.end else System.currentTimeMillis()) - d.dateMillis) / 3600_000.0
            canvas.drawRect(x(s.coerceIn(0.0, n)), 0f, x(e.coerceIn(0.0, n)), h - padBot + 4, win)
        }
        val path = Path()
        var started = false
        vals.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            if (!started) { path.moveTo(x(i * hr), y(v)); started = true } else path.lineTo(x(i * hr), y(v))
        }
        val area = Path(path)
        area.lineTo(w, h - padBot); area.lineTo(0f, h - padBot); area.close()
        canvas.drawPath(area, fill)
        canvas.drawPath(path, line)

        val tf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA)
        Tide.extremes(d).forEach { e ->
            val hx = x((e.time - d.dateMillis) / 3600_000.0)
            val hy = y(e.value)
            dot.color = Ui.TEXT
            canvas.drawCircle(hx, hy, Ui.dp(context, 3).toFloat(), dot)
            val ly = if (e.high) hy - Ui.dp(context, 14) else hy + Ui.dp(context, 16)
            canvas.drawText(tf.format(java.util.Date(e.time)), hx.coerceIn(30f, w - 30f), ly, txt)
            canvas.drawText(if (e.high) "만조" else "간조", hx.coerceIn(30f, w - 30f), ly + (if (e.high) -Ui.dp(context, 12) else Ui.dp(context, 12)), sub)
        }
        t?.catches?.forEach { c ->
            val v = Tide.valueAt(d, c.time) ?: return@forEach
            dot.color = Ui.SLOT[c.slot - 1]
            canvas.drawCircle(x((c.time - d.dateMillis) / 3600_000.0), y(v), Ui.dp(context, 4).toFloat(), dot)
        }
        for (hr in listOf(0, 6, 12, 18)) canvas.drawText("${hr}시", x(hr.toDouble()).coerceAtLeast(14f), h - 4, sub)
    }
}
