package com.fishlog.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v4.9 (224): 홈 화면 위젯
 *  - 🌊 물때 위젯: 앱이 받아 둔 오늘 물때 · 날씨(폰에 저장된 값)를 그림으로 · 크기를 키우면 물때 그래프까지
 *    날짜가 바뀌었는데 앱을 안 열었으면 물때만 직접 한 번 받음 (3시간에 한 번까지)
 *  - 🎒 가방 위젯: 폰에 저장된 가방 · 카테고리 넘기기(◀ ▶) · 항목 −/＋ · '부족'은 사용자가 정한 기준이 있을 때만
 *  - 누르면 앱의 그 화면 (MainActivity 에 "open" = "tide" / "bag")
 */
object WidgetData {
    private fun prefs(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)

    /** 메인 물때 카드가 계산할 때마다 저장 (위젯은 이걸 그림) */
    fun saveTide(c: Context, lat: Double, lon: Double, place: String, day: Tide.Day, pct: Int?) {
        try {
            val ex = JSONArray()
            Tide.extremes(day).forEach { e -> ex.put(JSONObject().put("t", e.time).put("h", e.high).put("v", e.value)) }
            val o = JSONObject().put("at", System.currentTimeMillis()).put("day", day.dateMillis).put("lat", lat).put("lon", lon)
                .put("place", place).put("ex", ex).put("pct", pct ?: -1)
            // 하루 높이 곡선 (그래프용 · 30분 간격으로 줄여서)
            val vals = JSONArray(); val step = maxOf(1, 30 / maxOf(1, day.stepMin))
            var i = 0
            while (i < day.values.size) { vals.put(day.values[i] ?: JSONObject.NULL); i += step }
            o.put("vals", vals).put("valStep", step * day.stepMin)
            prefs(c).edit().putString("w_tide", o.toString()).apply()
        } catch (_: Exception) {}
        TideWidget.updateAll(c)
    }

    /** 지금 날씨 (메인 물때 카드 배경 받을 때) */
    fun saveWx(c: Context, h: Weather.Hour?) {
        if (h == null) return
        try {
            val o = JSONObject().put("at", System.currentTimeMillis()).put("icon", h.skyIcon())
                .put("temp", h.temp ?: JSONObject.NULL).put("wind", h.wind ?: JSONObject.NULL).put("dir", h.dir ?: JSONObject.NULL).put("wave", h.wave ?: JSONObject.NULL)
            prefs(c).edit().putString("w_wx", o.toString()).apply()
        } catch (_: Exception) {}
        TideWidget.updateAll(c)
    }

    fun tide(c: Context): JSONObject? = try { prefs(c).getString("w_tide", null)?.let { JSONObject(it) } } catch (_: Exception) { null }
    fun wx(c: Context): JSONObject? = try { prefs(c).getString("w_wx", null)?.let { JSONObject(it) } } catch (_: Exception) { null }
    fun lastSelfFetch(c: Context) = prefs(c).getLong("w_tide_self", 0L)
    fun markSelfFetch(c: Context) = prefs(c).edit().putLong("w_tide_self", System.currentTimeMillis()).apply()

    fun openIntent(c: Context, what: String, req: Int): PendingIntent =
        PendingIntent.getActivity(c, req, Intent(c, MainActivity::class.java).putExtra("open", what)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

class TideWidget : AppWidgetProvider() {
    companion object {
        fun updateAll(c: Context) {
            try {
                val m = AppWidgetManager.getInstance(c)
                val ids = m.getAppWidgetIds(ComponentName(c, TideWidget::class.java))
                if (ids.isNotEmpty()) ids.forEach { draw(c, m, it) }
            } catch (_: Exception) {}
        }

        private fun dayStart(t: Long): Long {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = t
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
            return cal.timeInMillis
        }

        fun draw(c: Context, m: AppWidgetManager, id: Int) {
            val opt: Bundle = m.getAppWidgetOptions(id)
            val wDp = opt.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).coerceAtLeast(180)
            val hDp = opt.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 70).coerceAtLeast(56)
            m.updateAppWidget(id, build(c, wDp, hDp))
        }

        /** v6.31 (283): 위젯 모양 (홈 화면 추가 창 미리보기에도 씀) */
        fun build(c: Context, wDp: Int, hDp: Int): RemoteViews {
            val dens = c.resources.displayMetrics.density
            val scale = minOf(dens, 2.5f)
            val w = (wDp * scale).toInt(); val h = (hDp * scale).toInt()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            render(c, Canvas(bmp), w.toFloat(), h.toFloat(), scale, hDp >= 150)
            val rv = RemoteViews(c.packageName, R.layout.widget_image)
            rv.setImageViewBitmap(R.id.w_img, bmp)
            rv.setOnClickPendingIntent(R.id.w_img, WidgetData.openIntent(c, "tide", 7101))
            return rv
        }

        /** v6.46 (316): 새 디자인 — 왼쪽 날짜 칸 · 물세기 막대 · 다음 만조/간조 남은 시간 · 날씨 · (크게) 하루 물때 그래프 */
        private fun render(c: Context, cv: Canvas, w: Float, h: Float, s: Float, big: Boolean) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            // 바탕: 짙은 남색 그라데이션
            p.shader = android.graphics.LinearGradient(0f, 0f, w, h, intArrayOf(Color.parseColor("#14324A"), Color.parseColor("#0F2233"), Color.parseColor("#0B1A28")),
                floatArrayOf(0f, .6f, 1f), android.graphics.Shader.TileMode.CLAMP)
            cv.drawRoundRect(RectF(0f, 0f, w, h), 22 * s, 22 * s, p)
            p.shader = null
            val now = System.currentTimeMillis()
            val t = WidgetData.tide(c)
            val today = t != null && dayStart(t.optLong("day")) == dayStart(now)
            val mulFull = Tide.mulName(now).substringBefore(" (")
            val mul = mulFull.substringBefore(" · "); val kind = mulFull.substringAfter(" · ", "")
            val pct = if (today) t!!.optInt("pct", -1) else Tide.estimatePct(now)
            val hm = SimpleDateFormat("HH:mm", Locale.KOREA)
            var next: JSONObject? = null
            if (today) {
                val ex = t!!.optJSONArray("ex") ?: JSONArray()
                for (i in 0 until ex.length()) { val e = ex.getJSONObject(i); if (e.optLong("t") > now) { next = e; break } }
            }
            val rising = next?.optBoolean("h")
            val pad = 14 * s
            val SUB = Color.parseColor("#9FC3DA"); val TXT = Color.parseColor("#C9D9E5")
            fun txt(str: String, x: Float, y: Float, size: Float, col: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT): Float {
                p.color = col; p.textSize = size * s; p.isFakeBoldText = bold; p.textAlign = align
                cv.drawText(str, x, y, p)
                return p.measureText(str)
            }
            fun chip(str: String, x: Float, base: Float, col: Int, bg: Int): Float {
                p.textSize = 10.5f * s; p.isFakeBoldText = true; val tw = p.measureText(str)
                p.color = bg; p.textAlign = Paint.Align.LEFT
                cv.drawRoundRect(RectF(x, base - 12 * s, x + tw + 10 * s, base + 3.5f * s), 6 * s, 6 * s, p)
                txt(str, x + 5 * s, base, 10.5f, col, true)
                return tw + 10 * s
            }
            val place = t?.optString("place").orEmpty()
            val wx = WidgetData.wx(c)?.takeIf { now - it.optLong("at") < 6 * 3_600_000L }
            // 윗부분 높이 (작게 = 전체 · 크게 = 위 줄만)
            val topH = if (big) minOf(h, 92 * s) else h
            val cy = topH / 2
            // ① 왼쪽 날짜 칸
            val dw = 56 * s
            val cal = java.util.Calendar.getInstance()
            txt("${cal.get(java.util.Calendar.MONTH) + 1}월", pad + dw / 2, cy - 17 * s, 10.5f, SUB, align = Paint.Align.CENTER)
            txt("${cal.get(java.util.Calendar.DAY_OF_MONTH)}", pad + dw / 2, cy + 8 * s, 26f, Color.WHITE, true, Paint.Align.CENTER)
            val wd = SimpleDateFormat("E", Locale.KOREA).format(Date(now))
            txt("$wd · 오늘", pad + dw / 2, cy + 22 * s, 10.5f,
                if (cal.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.SUNDAY) Color.parseColor("#FF8A8A") else Color.parseColor("#7FD6FF"), true, Paint.Align.CENTER)
            p.color = Color.parseColor("#2C4B63"); p.strokeWidth = 1 * s
            cv.drawLine(pad + dw + 4 * s, cy - 26 * s, pad + dw + 4 * s, cy + 26 * s, p)
            // ③ 오른쪽 날씨
            val rw = if (wx != null) 70 * s else 0f
            if (wx != null) {
                val rx = w - pad - rw / 2
                txt(wx.optString("icon").ifBlank { "🌤" }, rx, cy - 6 * s, 20f, Color.WHITE, align = Paint.Align.CENTER)
                wx.optDouble("temp", Double.NaN).takeIf { !it.isNaN() }?.let { txt(String.format(Locale.KOREA, "%.0f°", it), rx, cy + 13 * s, 16f, Color.WHITE, true, Paint.Align.CENTER) }
                val wv = wx.optDouble("wind", Double.NaN).takeIf { !it.isNaN() }
                if (wv != null) {
                    val dir = wx.optDouble("dir", Double.NaN).takeIf { !it.isNaN() }?.let { Weather.dir16(it) + " " }.orEmpty()
                    txt("💨 $dir" + String.format(Locale.KOREA, "%.0fm/s", wv), rx, cy + 27 * s, 9.5f, Weather.windColor(wv), true, Paint.Align.CENTER)
                }
            }
            // ② 가운데: 물때 · 막대 · 다음 만조/간조
            val x0 = pad + dw + 14 * s
            val x1 = w - pad - rw - 6 * s
            var x = x0
            x += txt(mul, x, cy - 9 * s, 18f, Color.parseColor("#FFD27A"), true) + 5 * s
            if (kind.isNotBlank() && x1 - x > 120 * s) x += txt("· $kind", x, cy - 9 * s, 12f, TXT) + 6 * s
            if (rising != null && x1 - x > 40 * s) x += chip(if (rising) "↗ 들물" else "↘ 날물", x, cy - 10 * s,
                if (rising) Color.parseColor("#7FD6FF") else Color.parseColor("#FFC163"), if (rising) Color.parseColor("#123F5C") else Color.parseColor("#5A3D14")) + 5 * s
            if (big) {
                val idx = Tide.mulIndex(now)
                val life = when { idx in 0..6 -> Triple("↗ 사는 물때", "#5BE3A0", "#163D2A"); idx in 8..13 -> Triple("↘ 죽는 물때", "#C9A7FF", "#2C2236"); else -> null }
                if (life != null && x1 - x > 70 * s) chip(life.first, x, cy - 10 * s, Color.parseColor(life.second), Color.parseColor(life.third))
            }
            // 물세기 막대
            val by = cy + 1 * s
            p.color = Color.parseColor("#24394B")
            cv.drawRoundRect(RectF(x0, by, x1, by + 5 * s), 3 * s, 3 * s, p)
            if (pct >= 0) {
                p.shader = android.graphics.LinearGradient(x0, 0f, x1, 0f, Color.parseColor("#4FB3E8"), Color.parseColor("#7FD6FF"), android.graphics.Shader.TileMode.CLAMP)
                cv.drawRoundRect(RectF(x0, by, x0 + (x1 - x0) * pct.coerceIn(3, 100) / 100f, by + 5 * s), 3 * s, 3 * s, p)
                p.shader = null
            }
            // 셋째 줄: 물세기 · 다음 만조 (남은 시간)
            var x2 = x0
            val ly = cy + 20 * s
            if (pct >= 0) x2 += txt("물세기 ", x2, ly, 11f, TXT) .let { it } + txt("$pct%", x2 + p.measureText("물세기 "), ly, 11f, Color.WHITE, true)
            val n = next
            if (n != null) {
                val nt = n.optLong("t")
                val left = ((nt - now) / 60_000L).toInt()
                val leftS = if (left >= 60) "${left / 60}시간 ${left % 60}분 뒤" else "${left}분 뒤"
                val sep = if (pct >= 0) " · " else ""
                x2 += txt(sep, x2, ly, 11f, TXT)
                x2 += txt((if (n.optBoolean("h")) "▲만조 " else "▼간조 ") + hm.format(Date(nt)), x2, ly, 11f, Color.parseColor("#7CE39A"), true)
                if (x1 - x2 > 60 * s) txt(" ($leftS)", x2, ly, 10.5f, SUB)
            } else if (!today) txt("  앱을 열면 오늘 만조 · 간조가 보여요", x2, ly, 10.5f, SUB)
            // 아래 작은 줄 (장소 · 갱신)
            if (!big && h >= 96 * s) {
                txt("📍 " + place.ifBlank { "내 위치" }, x0, h - 9 * s, 9.5f, Color.parseColor("#7F9BB0"))
                t?.optLong("at")?.takeIf { it > 0 }?.let { txt(hm.format(Date(it)) + " 갱신", w - pad, h - 9 * s, 9.5f, Color.parseColor("#7F9BB0"), align = Paint.Align.RIGHT) }
            }
            if (!big) return
            // ---- 크게: 하루 물때 그래프 ----
            val gTop = topH + 14 * s; val gBot = h - 34 * s
            val vals = t?.optJSONArray("vals")
            val gl = pad; val gr = w - pad
            fun xf(f: Float) = gl + (gr - gl) * f
            val sun = Sun.times(now, t?.optDouble("lat"), t?.optDouble("lon"))
            val d0 = dayStart(now)
            if (today && vals != null && vals.length() > 2 && gBot - gTop > 24 * s) {
                // 밤은 어둡게
                if (sun != null) {
                    p.color = Color.argb(46, 0, 0, 0)
                    cv.drawRect(xf(0f), gTop, xf(((sun.rise - d0) / 86_400_000f).coerceIn(0f, 1f)), gBot, p)
                    cv.drawRect(xf(((sun.set - d0) / 86_400_000f).coerceIn(0f, 1f)), gTop, xf(1f), gBot, p)
                }
                val nums: List<Double?> = (0 until vals.length()).map { i -> if (vals.isNull(i)) null else vals.optDouble(i) }
                val known = nums.filterNotNull()
                val lo = known.minOrNull() ?: 0.0; val hi = known.maxOrNull() ?: 1.0
                val rng = (hi - lo).takeIf { it > 0.01 } ?: 1.0
                val yT = gTop + 12 * s
                fun xAt(i: Int) = xf(i / (nums.size - 1).toFloat())
                fun yAt(v: Double) = (gBot - (gBot - yT) * ((v - lo) / rng)).toFloat()
                val line = Path(); val fill = Path(); var started = false; var lastX = gl
                nums.forEachIndexed { i, v -> if (v != null) { lastX = xAt(i); if (!started) { line.moveTo(xAt(i), yAt(v)); fill.moveTo(xAt(i), gBot); fill.lineTo(xAt(i), yAt(v)); started = true } else { line.lineTo(xAt(i), yAt(v)); fill.lineTo(xAt(i), yAt(v)) } } }
                fill.lineTo(lastX, gBot); fill.close()
                p.shader = android.graphics.LinearGradient(0f, yT, 0f, gBot, Color.argb(140, 44, 143, 200), Color.argb(12, 44, 143, 200), android.graphics.Shader.TileMode.CLAMP)
                p.style = Paint.Style.FILL; cv.drawPath(fill, p); p.shader = null
                p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * s; p.color = Color.parseColor("#5FC4E0"); cv.drawPath(line, p)
                p.style = Paint.Style.FILL
                // 지금 = 노란 점선 + 점
                val nf = ((now - d0) / 86_400_000.0).toFloat()
                val nx = xf(nf)
                p.color = Color.parseColor("#FFD27A"); p.strokeWidth = 1.6f * s
                p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(3 * s, 3 * s), 0f)
                cv.drawLine(nx, gTop, nx, gBot, p); p.pathEffect = null
                val ni = (nf * (nums.size - 1)).toInt().coerceIn(0, nums.size - 1)
                nums[ni]?.let { cv.drawCircle(nx, yAt(it), 4.5f * s, p) }
                txt("지금 " + hm.format(Date(now)), (nx + 4 * s).coerceAtMost(gr - 52 * s), gTop + 4 * s, 9.5f, Color.parseColor("#FFD27A"), true)
                // 만조 ▲ 위 · 간조 ▼ 아래
                val ex = t!!.optJSONArray("ex") ?: JSONArray()
                for (i in 0 until ex.length()) {
                    val e = ex.getJSONObject(i)
                    val ef = ((e.optLong("t") - d0) / 86_400_000.0).toFloat()
                    if (ef < 0f || ef > 1f) continue
                    val hiE = e.optBoolean("h"); val isNext = e.optLong("t") == next?.optLong("t")
                    val ex0 = xf(ef).coerceIn(gl + 24 * s, gr - 24 * s)
                    txt((if (hiE) "▲ " else "▼ ") + hm.format(Date(e.optLong("t"))), ex0, if (hiE) yT - 2 * s else gBot + 11 * s, 9.5f,
                        if (isNext) Color.WHITE else SUB, isNext, Paint.Align.CENTER)
                }
            } else {
                txt(if (today) "물때 그래프를 그릴 자료가 없어요" else "앱을 한 번 열면 오늘 물때 그래프가 보여요", w / 2, (gTop + gBot) / 2, 11.5f, SUB, align = Paint.Align.CENTER)
            }
            // 시간 눈금 · 해 뜨고 지는 시각
            val ty = h - 18 * s
            txt("0시", gl, ty, 9f, Color.parseColor("#7F9BB0"))
            txt("12시", xf(.5f), ty, 9f, Color.parseColor("#7F9BB0"), align = Paint.Align.CENTER)
            txt("24시", gr, ty, 9f, Color.parseColor("#7F9BB0"), align = Paint.Align.RIGHT)
            if (sun != null) {
                txt("🌅" + hm.format(Date(sun.rise)), xf(((sun.rise - d0) / 86_400_000f).coerceIn(.12f, .4f)), ty, 9f, Color.parseColor("#FFC163"), align = Paint.Align.CENTER)
                txt("🌇" + hm.format(Date(sun.set)), xf(((sun.set - d0) / 86_400_000f).coerceIn(.6f, .88f)), ty, 9f, Color.parseColor("#FFC163"), align = Paint.Align.CENTER)
            }
            txt("📍 " + place.ifBlank { "내 위치" }, gl, h - 5 * s, 9.5f, Color.parseColor("#7F9BB0"))
            txt((t?.optLong("at")?.takeIf { it > 0 }?.let { hm.format(Date(it)) + " 갱신 · " } ?: "") + "누르면 물때 창", gr, h - 5 * s, 9.5f, Color.parseColor("#7F9BB0"), align = Paint.Align.RIGHT)
        }
    }

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        ids.forEach { draw(c, m, it) }
        // 날짜가 바뀌었는데 앱을 안 열었으면: 물때만 직접 받기 (3시간에 한 번까지)
        val t = WidgetData.tide(c) ?: return
        val now = System.currentTimeMillis()
        if (dayStart(t.optLong("day")) == dayStart(now) || now - WidgetData.lastSelfFetch(c) < 3 * 3_600_000L) return
        WidgetData.markSelfFetch(c)
        val lat = t.optDouble("lat"); val lon = t.optDouble("lon")
        if (lat.isNaN() || lon.isNaN()) return
        val pr = goAsync()
        try {
            Tide.strengthAt(c.applicationContext, lat, lon, now) { day, st ->
                try { if (day != null) WidgetData.saveTide(c, lat, lon, t.optString("place"), day, st?.pct) } catch (_: Exception) {}
                try { pr.finish() } catch (_: Exception) {}
            }
        } catch (_: Exception) { try { pr.finish() } catch (_: Exception) {} }
    }

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, newOptions: Bundle) { draw(c, m, id) }
}

class BagWidget : AppWidgetProvider() {
    companion object {
        private const val ACT = "com.fishlog.app.BAG_WIDGET"
        private val ROWS = intArrayOf(R.id.wb_r0, R.id.wb_r1, R.id.wb_r2, R.id.wb_r3, R.id.wb_r4)
        private val NAMES = intArrayOf(R.id.wb_n0, R.id.wb_n1, R.id.wb_n2, R.id.wb_n3, R.id.wb_n4)
        private val QTYS = intArrayOf(R.id.wb_q0, R.id.wb_q1, R.id.wb_q2, R.id.wb_q3, R.id.wb_q4)
        private val MINUS = intArrayOf(R.id.wb_m0, R.id.wb_m1, R.id.wb_m2, R.id.wb_m3, R.id.wb_m4)
        private val PLUS = intArrayOf(R.id.wb_p0, R.id.wb_p1, R.id.wb_p2, R.id.wb_p3, R.id.wb_p4)
        private fun prefs(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)

        fun updateAll(c: Context) {
            try {
                val m = AppWidgetManager.getInstance(c)
                m.getAppWidgetIds(ComponentName(c, BagWidget::class.java)).forEach { draw(c, m, it) }
            } catch (_: Exception) {}
        }

        private fun pi(c: Context, cmd: String, idx: Int, req: Int): PendingIntent =
            PendingIntent.getBroadcast(c, req, Intent(c, BagWidget::class.java).setAction(ACT).putExtra("cmd", cmd).putExtra("i", idx),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        fun draw(c: Context, m: AppWidgetManager, id: Int) = m.updateAppWidget(id, build(c))

        /** v6.31 (283): 위젯 모양 (홈 화면 추가 창 미리보기에도 씀) */
        fun build(c: Context): RemoteViews {
            val cats = Inventory.load(c)
            val rv = RemoteViews(c.packageName, R.layout.widget_bag)
            rv.setOnClickPendingIntent(R.id.wb_title, WidgetData.openIntent(c, "bag", 7201))
            if (cats.isEmpty()) {
                rv.setTextViewText(R.id.wb_title, "🎒 내 가방")
                rv.setTextViewText(R.id.wb_low, "")
                rv.setTextViewText(R.id.wb_empty, "앱 → 🎒 가방에서 카테고리를 만들어 주세요")
                ROWS.forEach { rv.setViewVisibility(it, android.view.View.GONE) }
                return rv
            }
            val ci = prefs(c).getInt("w_bag_cat", 0).let { if (it in cats.indices) it else 0 }
            val cat = cats[ci]
            rv.setTextViewText(R.id.wb_title, "🎒 ${cat.name}  (${ci + 1}/${cats.size})")
            // '부족'은 사용자가 직접 정한 기준이 있을 때만
            val low = cat.items.count { it.isLow() }
            rv.setTextViewText(R.id.wb_low, if (low > 0) "⚠ 부족 $low" else "")
            rv.setTextViewText(R.id.wb_empty, if (cat.items.isEmpty()) "항목이 없어요 · 눌러서 추가" else "")
            rv.setOnClickPendingIntent(R.id.wb_prev, pi(c, "prev", 0, 7290))
            rv.setOnClickPendingIntent(R.id.wb_next, pi(c, "next", 0, 7291))
            rv.setOnClickPendingIntent(R.id.wb_empty, WidgetData.openIntent(c, "bag", 7202))
            for (r in 0 until 5) {
                val it = cat.items.getOrNull(r)
                if (it == null) { rv.setViewVisibility(ROWS[r], android.view.View.GONE); continue }
                rv.setViewVisibility(ROWS[r], android.view.View.VISIBLE)
                val sub = if (it.subs.isEmpty()) "" else "  " + it.subs.take(2).joinToString(" · ") { s -> s.name }
                rv.setTextViewText(NAMES[r], it.name + sub)
                rv.setTextViewText(QTYS[r], it.total().toString())
                rv.setTextColor(QTYS[r], if (it.isLow()) Color.parseColor("#FF8A92") else Color.WHITE)
                val canPm = it.subs.isEmpty()
                rv.setViewVisibility(MINUS[r], if (canPm) android.view.View.VISIBLE else android.view.View.INVISIBLE)
                rv.setViewVisibility(PLUS[r], if (canPm) android.view.View.VISIBLE else android.view.View.INVISIBLE)
                rv.setOnClickPendingIntent(MINUS[r], pi(c, "minus", r, 7300 + r))
                rv.setOnClickPendingIntent(PLUS[r], pi(c, "plus", r, 7310 + r))
                rv.setOnClickPendingIntent(NAMES[r], WidgetData.openIntent(c, "bag", 7203))
            }
            return rv
        }
    }

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) { ids.forEach { draw(c, m, it) } }

    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action == ACT) {
            val cats = Inventory.load(c)
            if (cats.isNotEmpty()) {
                val ci = prefs(c).getInt("w_bag_cat", 0).let { if (it in cats.indices) it else 0 }
                when (intent.getStringExtra("cmd")) {
                    "prev" -> prefs(c).edit().putInt("w_bag_cat", (ci - 1 + cats.size) % cats.size).apply()
                    "next" -> prefs(c).edit().putInt("w_bag_cat", (ci + 1) % cats.size).apply()
                    "minus", "plus" -> Inventory.adjust(c, ci, intent.getIntExtra("i", -1), if (intent.getStringExtra("cmd") == "plus") 1 else -1)
                }
            }
            updateAll(c)
            return
        }
        super.onReceive(c, intent)
    }
}
