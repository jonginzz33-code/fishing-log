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
                .put("temp", h.temp ?: JSONObject.NULL).put("wind", h.wind ?: JSONObject.NULL).put("wave", h.wave ?: JSONObject.NULL)
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
            val dens = c.resources.displayMetrics.density
            val wDp = opt.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).coerceAtLeast(180)
            val hDp = opt.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 70).coerceAtLeast(56)
            val scale = minOf(dens, 2.5f)
            val w = (wDp * scale).toInt(); val h = (hDp * scale).toInt()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            render(c, Canvas(bmp), w.toFloat(), h.toFloat(), scale, hDp >= 110)
            val rv = RemoteViews(c.packageName, R.layout.widget_image)
            rv.setImageViewBitmap(R.id.w_img, bmp)
            rv.setOnClickPendingIntent(R.id.w_img, WidgetData.openIntent(c, "tide", 7101))
            m.updateAppWidget(id, rv)
        }

        private fun render(c: Context, cv: Canvas, w: Float, h: Float, s: Float, big: Boolean) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = Color.argb(235, 15, 28, 38)
            cv.drawRoundRect(RectF(0f, 0f, w, h), 16 * s, 16 * s, p)
            val now = System.currentTimeMillis()
            val t = WidgetData.tide(c)
            val today = t != null && dayStart(t.optLong("day")) == dayStart(now)
            val mul = Tide.mulName(now).substringBefore(" (")
            val pct = if (today) t!!.optInt("pct", -1) else Tide.estimatePct(now)
            val hm = SimpleDateFormat("HH:mm", Locale.KOREA)
            // 다음 만조/간조 · 지금 들물/날물
            var next: JSONObject? = null; var prev: JSONObject? = null
            if (today) {
                val ex = t!!.optJSONArray("ex") ?: JSONArray()
                for (i in 0 until ex.length()) { val e = ex.getJSONObject(i); if (e.optLong("t") > now) { next = e; break } else prev = e }
            }
            val rising = next?.optBoolean("h")
            val pad = 12 * s
            fun txt(str: String, x: Float, y: Float, size: Float, col: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) {
                p.color = col; p.textSize = size * s; p.isFakeBoldText = bold; p.textAlign = align
                cv.drawText(str, x, y, p)
            }
            val place = t?.optString("place").orEmpty()
            val wx = WidgetData.wx(c)?.takeIf { now - it.optLong("at") < 6 * 3_600_000L }
            if (!big) {
                // 작게: 🌊 | 5물 · 사리 [날물] / 간조 22:33 · 물세기 83% · 안양 | 🌧 9°
                txt("🌊", pad, h / 2 + 8 * s, 22f, Color.WHITE)
                val x0 = pad + 34 * s
                txt(mul, x0, h / 2 - 3 * s, 15f, Color.parseColor("#FFD166"), true)
                p.textSize = 15 * s; val mw = p.measureText(mul)
                if (rising != null) {
                    val tag = if (rising) "들물" else "날물"
                    p.textSize = 11 * s; val tw = p.measureText(tag)
                    val col = if (rising) Color.parseColor("#5FC4E0") else Color.parseColor("#F2A65A")
                    p.color = Color.argb(70, Color.red(col), Color.green(col), Color.blue(col))
                    cv.drawRoundRect(RectF(x0 + mw + 6 * s, h / 2 - 16 * s, x0 + mw + 14 * s + tw, h / 2 - 1 * s), 5 * s, 5 * s, p)
                    txt(tag, x0 + mw + 10 * s, h / 2 - 4.5f * s, 11f, col, true)
                }
                val line2 = listOfNotNull(
                    next?.let { (if (it.optBoolean("h")) "만조 " else "간조 ") + hm.format(Date(it.optLong("t"))) },
                    if (pct >= 0) "물세기 $pct%" else null, place.ifBlank { null }).joinToString(" · ")
                txt(line2.ifBlank { "앱을 열면 오늘 만조 · 간조가 보여요" }, x0, h / 2 + 15 * s, 11.5f, Color.parseColor("#C9D6DD"))
                if (wx != null) {
                    txt(wx.optString("icon"), w - pad, h / 2 - 2 * s, 18f, Color.WHITE, align = Paint.Align.RIGHT)
                    val tmp = wx.optDouble("temp", Double.NaN)
                    if (!tmp.isNaN()) txt(String.format(Locale.KOREA, "%.0f°", tmp), w - pad, h / 2 + 16 * s, 11.5f, Color.parseColor("#C9D6DD"), align = Paint.Align.RIGHT)
                }
                return
            }
            // 크게: 위 두 줄 + 물때 그래프 + 아래 한 줄
            txt("📍 " + place.ifBlank { "내 위치" } + " · 오늘 " + SimpleDateFormat("M/d(E)", Locale.KOREA).format(Date(now)), pad, pad + 10 * s, 11f, Color.parseColor("#9FB4BF"))
            txt(mul, pad, pad + 30 * s, 17f, Color.parseColor("#FFD166"), true)
            p.textSize = 17 * s; val mw = p.measureText(mul)
            if (rising != null) txt(if (rising) "지금 들물" else "지금 날물", pad + mw + 8 * s, pad + 29 * s, 12f,
                if (rising) Color.parseColor("#5FC4E0") else Color.parseColor("#F2A65A"), true)
            if (wx != null) {
                val parts = listOfNotNull(wx.optString("icon"),
                    wx.optDouble("temp", Double.NaN).takeIf { !it.isNaN() }?.let { String.format(Locale.KOREA, "%.0f°", it) },
                    wx.optDouble("wind", Double.NaN).takeIf { !it.isNaN() }?.let { String.format(Locale.KOREA, "바람 %.1f", it) })
                txt(parts.joinToString(" "), w - pad, pad + 12 * s, 12f, Color.parseColor("#C9D6DD"), align = Paint.Align.RIGHT)
                wx.optDouble("wave", Double.NaN).takeIf { !it.isNaN() }?.let {
                    txt(String.format(Locale.KOREA, "파고 %.1fm", it), w - pad, pad + 28 * s, 12f, Color.parseColor("#C9D6DD"), align = Paint.Align.RIGHT)
                }
            }
            val gTop = pad + 40 * s; val gBot = h - pad - 20 * s
            val vals = t?.optJSONArray("vals")
            if (today && vals != null && vals.length() > 2 && gBot - gTop > 20 * s) {
                val nums: List<Double?> = (0 until vals.length()).map { i -> if (vals.isNull(i)) null else vals.optDouble(i) }
                val known = nums.filterNotNull()
                val lo = known.minOrNull() ?: 0.0; val hi = known.maxOrNull() ?: 1.0
                val rng = (hi - lo).takeIf { it > 0.01 } ?: 1.0
                fun xAt(i: Int) = pad + (w - 2 * pad) * i / (nums.size - 1).toFloat()
                fun yAt(v: Double) = (gBot - (gBot - gTop) * ((v - lo) / rng)).toFloat()
                val line = Path(); val fill = Path(); var started = false
                nums.forEachIndexed { i, v -> if (v != null) { if (!started) { line.moveTo(xAt(i), yAt(v)); fill.moveTo(xAt(i), gBot); fill.lineTo(xAt(i), yAt(v)); started = true } else { line.lineTo(xAt(i), yAt(v)); fill.lineTo(xAt(i), yAt(v)) } } }
                fill.lineTo(w - pad, gBot); fill.close()
                p.style = Paint.Style.FILL; p.color = Color.argb(80, 44, 143, 200); cv.drawPath(fill, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 2 * s; p.color = Color.parseColor("#5FC4E0"); cv.drawPath(line, p)
                p.style = Paint.Style.FILL
                // 지금 선
                val frac = ((now - dayStart(now)) / 86_400_000.0).toFloat()
                val nx = pad + (w - 2 * pad) * frac
                p.color = Color.parseColor("#F2A65A"); p.strokeWidth = 1.5f * s
                cv.drawLine(nx, gTop, nx, gBot, p)
                txt("지금", nx, gTop - 2 * s, 9f, Color.parseColor("#F2A65A"), true, Paint.Align.CENTER)
                // 만조 · 간조 시각
                val ex = t!!.optJSONArray("ex") ?: JSONArray()
                for (i in 0 until ex.length()) {
                    val e = ex.getJSONObject(i)
                    val ef = ((e.optLong("t") - dayStart(now)) / 86_400_000.0).toFloat()
                    if (ef < 0f || ef > 1f) continue
                    val ex0 = pad + (w - 2 * pad) * ef
                    val hiE = e.optBoolean("h")
                    txt((if (hiE) "만조 " else "간조 ") + hm.format(Date(e.optLong("t"))), ex0.coerceIn(pad + 26 * s, w - pad - 26 * s),
                        if (hiE) gTop + 10 * s else gBot - 3 * s, 9f, if (e.optLong("t") == next?.optLong("t")) Color.WHITE else Color.parseColor("#9FB4BF"), e.optLong("t") == next?.optLong("t"), Paint.Align.CENTER)
                }
            } else {
                txt(if (today) "물때 그래프를 그릴 자료가 없어요" else "앱을 한 번 열면 오늘 물때 그래프가 보여요", w / 2, (gTop + gBot) / 2, 12f, Color.parseColor("#9FB4BF"), align = Paint.Align.CENTER)
            }
            val sun = Sun.times(now, t?.optDouble("lat"), t?.optDouble("lon"))
            val bottom = listOfNotNull(if (pct >= 0) "물세기 $pct%" else null,
                next?.let { (if (it.optBoolean("h")) "다음 만조 " else "다음 간조 ") + hm.format(Date(it.optLong("t"))) },
                sun?.let { "☀ " + hm.format(Date(it.rise)) + " · ☾ " + hm.format(Date(it.set)) }).joinToString("   ")
            txt(bottom, pad, h - pad, 11.5f, Color.parseColor("#C9D6DD"))
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

        fun draw(c: Context, m: AppWidgetManager, id: Int) {
            val cats = Inventory.load(c)
            val rv = RemoteViews(c.packageName, R.layout.widget_bag)
            rv.setOnClickPendingIntent(R.id.wb_title, WidgetData.openIntent(c, "bag", 7201))
            if (cats.isEmpty()) {
                rv.setTextViewText(R.id.wb_title, "🎒 내 가방")
                rv.setTextViewText(R.id.wb_low, "")
                rv.setTextViewText(R.id.wb_empty, "앱 → 🎒 가방에서 카테고리를 만들어 주세요")
                ROWS.forEach { rv.setViewVisibility(it, android.view.View.GONE) }
                m.updateAppWidget(id, rv); return
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
            m.updateAppWidget(id, rv)
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
