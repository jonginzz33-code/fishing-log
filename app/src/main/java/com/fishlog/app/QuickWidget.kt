package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject

/**
 * v6.30 (282): ⚡ 퀵바 위젯 — 앱 퀵바에 넣은 칸을 홈 화면에 그대로
 *  - 칸 목록은 MainActivity.paintQuick 이 바꿀 때마다 저장(prefs "w_quick") → 위젯이 그 순서대로
 *  - 누르면 앱이 켜지며 그 메뉴 바로 (MainActivity "open" = "q:칸이름") · 🔋 절약은 앱을 안 열고 위젯에서 바로 켜고 끔
 *  - 맨 끝 '⚡ 설정' 칸 = 앱의 퀵바 설정 · 세로로 늘리면 두 줄(10칸)
 */
class QuickWidget : AppWidgetProvider() {
    companion object {
        private const val ACT = "com.fishlog.app.QUICK_WIDGET"
        private fun prefs(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)

        /** 앱 퀵바가 바뀔 때마다 (id · 그림 · 이름 · 켜짐(-1 = 켜고 끄는 칸 아님)) · v6.46 (316): 칸 색 */
        fun save(c: Context, items: List<Triple<String, String, String>>, states: Map<String, Boolean>, colors: Map<String, String> = emptyMap()) {
            try {
                val a = JSONArray()
                items.forEach { (id, icon, label) -> a.put(JSONObject().put("id", id).put("i", icon).put("l", label).put("on", states[id]?.let { if (it) 1 else 0 } ?: -1)
                    .put("c", colors[id] ?: "")) }
                prefs(c).edit().putString("w_quick", a.toString()).apply()
            } catch (_: Exception) {}
            updateAll(c)
        }

        /** v6.46 (316): 그룹원 출조 중 몇 명 (메인 '지금 출조 중' 줄이 알려 줌 · −1 = 모름) */
        fun saveLive(c: Context, n: Int) {
            if (prefs(c).getInt("w_live_n", -2) == n) return
            prefs(c).edit().putInt("w_live_n", n).apply()
            updateAll(c)
        }

        fun updateAll(c: Context) {
            try {
                val m = AppWidgetManager.getInstance(c)
                m.getAppWidgetIds(ComponentName(c, QuickWidget::class.java)).forEach { draw(c, m, it) }
            } catch (_: Exception) {}
        }

        private class T(val id: String, val icon: String, val label: String, val on: Int, val color: String = "")

        private fun items(c: Context): List<T> {
            val list = try {
                val a = JSONArray(prefs(c).getString("w_quick", null) ?: "[]")
                (0 until a.length()).map { val o = a.getJSONObject(it); T(o.optString("id"), o.optString("i"), o.optString("l"), o.optInt("on", -1), o.optString("c")) }
            } catch (_: Exception) { emptyList() }
            // 앱을 아직 안 열었으면 기본(추천 · 그룹변경)
            return list.ifEmpty { listOf(T("cal", "🎣", "추천", -1), T("group", "🔄", "그룹변경", -1)) }
        }

        fun draw(c: Context, m: AppWidgetManager, wid: Int) {
            val hDp = m.getAppWidgetOptions(wid).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 70)
            m.updateAppWidget(wid, build(c, hDp))
        }

        private val TILE = intArrayOf(R.id.wq_0, R.id.wq_1, R.id.wq_2, R.id.wq_3, R.id.wq_4, R.id.wq_5, R.id.wq_6, R.id.wq_7, R.id.wq_8, R.id.wq_9, R.id.wq_10, R.id.wq_11)
        private val ICO = intArrayOf(R.id.wqi_0, R.id.wqi_1, R.id.wqi_2, R.id.wqi_3, R.id.wqi_4, R.id.wqi_5, R.id.wqi_6, R.id.wqi_7, R.id.wqi_8, R.id.wqi_9, R.id.wqi_10, R.id.wqi_11)
        private val LBL = intArrayOf(R.id.wql_0, R.id.wql_1, R.id.wql_2, R.id.wql_3, R.id.wql_4, R.id.wql_5, R.id.wql_6, R.id.wql_7, R.id.wql_8, R.id.wql_9, R.id.wql_10, R.id.wql_11)
        private val SUBV = intArrayOf(R.id.wqs_0, R.id.wqs_1, R.id.wqs_2, R.id.wqs_3, R.id.wqs_4, R.id.wqs_5, R.id.wqs_6, R.id.wqs_7, R.id.wqs_8, R.id.wqs_9, R.id.wqs_10, R.id.wqs_11)
        private val ROWS = intArrayOf(R.id.wq_row0, R.id.wq_row1, R.id.wq_row2)

        /** 칸 그림: 색 동그라미 안에 그림 (칸마다 색이 달라 RemoteViews 대신 작은 그림으로) */
        private fun circle(c: Context, icon: String, color: String, dim: Boolean): android.graphics.Bitmap {
            val px = (36 * minOf(c.resources.displayMetrics.density, 3f)).toInt()
            val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(bmp)
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            val base = try { android.graphics.Color.parseColor(color.ifBlank { "#2E5A7A" }) } catch (_: Exception) { android.graphics.Color.parseColor("#2E5A7A") }
            // 테두리 색을 어둡게 섞어 동그라미 바탕으로
            val r = android.graphics.Color.red(base); val g = android.graphics.Color.green(base); val b = android.graphics.Color.blue(base)
            p.color = android.graphics.Color.rgb((r * .55f + 20).toInt().coerceAtMost(255), (g * .55f + 30).toInt().coerceAtMost(255), (b * .55f + 40).toInt().coerceAtMost(255))
            if (dim) p.alpha = 120
            cv.drawCircle(px / 2f, px / 2f, px / 2f, p)
            p.alpha = 255; p.color = android.graphics.Color.WHITE
            p.textSize = px * .52f; p.textAlign = android.graphics.Paint.Align.CENTER
            val fm = p.fontMetrics
            cv.drawText(icon, px / 2f, px / 2f - (fm.ascent + fm.descent) / 2f, p)
            return bmp
        }

        /** v6.31 (283) · v6.46 (316) 새 디자인: 위 줄(조과일지 · 날짜 · 물때 + ⚡) · 한 줄 4칸 · 높이에 따라 1~3줄 */
        fun build(c: Context, hDp: Int): RemoteViews {
            try { Store.init(c) } catch (_: Exception) {}
            val all = items(c)
            val rows = when { hDp >= 200 -> 3; hDp >= 110 -> 2; else -> 1 }
            val head = rows >= 2
            // 위 줄이 있으면 ⚡ 는 위 줄 동그라미 · 한 줄짜리는 마지막 칸
            val needRows = if (head) ((all.size + 3) / 4).coerceIn(1, rows) else 1
            val slots = needRows * 4
            val shown = if (head) all.take(slots) else all.take(slots - 1) + T("qset", "⚡", "설정", -1, "#C9E26A")
            val rv = RemoteViews(c.packageName, R.layout.widget_quick)
            rv.setViewVisibility(R.id.wq_head, if (head) View.VISIBLE else View.GONE)
            if (head) {
                val now = System.currentTimeMillis()
                val day = java.text.SimpleDateFormat("M월 d일(E)", java.util.Locale.KOREA).format(java.util.Date(now))
                val mul = Tide.mulName(now).substringBefore(" (").substringBefore(" · ")
                val sb = SpannableStringBuilder("🎣 조과일지")
                sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length, 0)
                val st = sb.length
                sb.append("  ·  $day · $mul")
                sb.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#9FC3DA")), st, sb.length, 0)
                sb.setSpan(RelativeSizeSpan(0.9f), st, sb.length, 0)
                rv.setTextViewText(R.id.wq_title, sb)
                rv.setOnClickPendingIntent(R.id.wq_title, WidgetData.openIntent(c, "tide", 7290))
                rv.setOnClickPendingIntent(R.id.wq_gear, WidgetData.openIntent(c, "q:qset", 7291))
                // v6.47: 🔄 새로고침 = 그룹 소식(출조 중 · 채팅 · 공지 · 일정 · 참석) 바로 확인 (1시간마다 자동 확인은 그대로)
                val busy = prefs(c).getLong("w_refreshing", 0L).let { System.currentTimeMillis() - it < 60_000L }
                val at = prefs(c).getLong("w_check_at", 0L)
                rv.setTextViewText(R.id.wq_refresh, if (busy) "⏳ 확인 중" else "🔄 " + (if (at > 0) java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date(at)) else "새로고침"))
                rv.setOnClickPendingIntent(R.id.wq_refresh, PendingIntent.getBroadcast(c, 7292, Intent(c, QuickWidget::class.java).setAction(ACT).putExtra("cmd", "refresh"),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                // v6.50 (325): ⬆ 앱 업데이트 있음 (지금 버전에서 확인한 것만) · ✉ 1:1 문의 답장
                val upd = prefs(c).getInt("w_upd", 0) == BuildConfig.VERSION_CODE
                rv.setViewVisibility(R.id.wq_upd, if (upd) View.VISIBLE else View.GONE)
                if (upd) rv.setOnClickPendingIntent(R.id.wq_upd, PendingIntent.getActivity(c, 7293,
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                val inqTop = prefs(c).getInt("w_inq_n", 0)
                rv.setViewVisibility(R.id.wq_inq, if (inqTop > 0) View.VISIBLE else View.GONE)
                if (inqTop > 0) {
                    rv.setTextViewText(R.id.wq_inq, (if (prefs(c).getInt("w_inq_admin", 0) == 1) "✉ 새 문의 " else "✉ 답장 ") + inqTop)
                    rv.setOnClickPendingIntent(R.id.wq_inq, WidgetData.openIntent(c, "q:inq", 7294))
                }
            }
            ROWS.forEachIndexed { r, id -> rv.setViewVisibility(id, if (r < needRows) View.VISIBLE else View.GONE) }
            val liveN = prefs(c).getInt("w_live_n", -1)
            TILE.forEachIndexed { i, tid ->
                val t = shown.getOrNull(i)
                if (t == null || i >= slots) { rv.setViewVisibility(tid, View.INVISIBLE); return@forEachIndexed }
                rv.setViewVisibility(tid, View.VISIBLE)
                // v6.50 (323): 출조중 칸 = 나 또는 그룹원 누구라도 출조 중이면 🟢 (위젯 그릴 때 바로 계산)
                val meOn = Store.current != null
                val others = liveN.coerceAtLeast(0)
                val anyOn = meOn || others > 0
                val icon = if (t.id == "now") (if (anyOn) "🟢" else "❌") else t.icon
                rv.setImageViewBitmap(ICO[i], circle(c, icon, t.color, t.on == 0))
                rv.setTextViewText(LBL[i], t.label + if (t.on == 1) " 켬" else if (t.on == 0) " 끔" else "")
                // 칸 아래 작은 글자 (빨강 = 확인할 것 · 초록 = 알림 · 회색 = 안내) — v6.48 채팅 · v6.50 (324 · 325) 그룹 · 문의 · 관리 · 물때 특보
                val RED = android.graphics.Color.parseColor("#FF6B6B"); val GRN = android.graphics.Color.parseColor("#5FD39A"); val GRY = android.graphics.Color.parseColor("#9FC3DA")
                fun n99(n: Int) = if (n > 99) "99+" else "$n"
                val gN = prefs(c).getInt("w_group_n", 0); val gTom = prefs(c).getInt("w_group_tom", 0); val gToday = prefs(c).getInt("w_group_today", 0)
                val inqN = prefs(c).getInt("w_inq_n", 0); val inqAdmin = prefs(c).getInt("w_inq_admin", 0) == 1
                val sub: Pair<String, Int>? = when (t.id) {
                    "now" -> when {
                        liveN < 0 && !meOn -> null
                        meOn && others > 0 -> "나 + ${others}명" to GRN
                        meOn -> "나 출조 중" to GRN
                        others > 0 -> "${others}명 출조 중" to GRN
                        else -> "아무도 없음" to GRY
                    }
                    "chat" -> prefs(c).getInt("w_chat_n", 0).takeIf { it > 0 }?.let { "● 새 글 " + n99(it) to RED }
                    "group", "groupwin" -> when {
                        gN > 0 -> "● 새 소식 " + n99(gN) to RED
                        gToday > 0 -> "📅 오늘 출조" to GRN
                        gTom > 0 -> "📅 내일 출조!" to GRN
                        else -> null
                    }
                    "inq" -> inqN.takeIf { it > 0 && !inqAdmin }?.let { "● 답장 " + n99(it) to RED }
                    "admin" -> inqN.takeIf { it > 0 && inqAdmin }?.let { "● 새 문의 " + n99(it) to RED }
                    "tide" -> WidgetData.warn(c)?.let { w -> "⚠ " + (Regex("\\S*(주의보|경보)").find(w)?.value ?: "특보") to RED }
                    else -> null
                }
                val alert = sub != null && sub.second != GRY
                rv.setViewVisibility(SUBV[i], if (sub != null && (rows >= 2 || alert)) View.VISIBLE else View.GONE)
                if (sub != null) { rv.setTextViewText(SUBV[i], sub.first); rv.setTextColor(SUBV[i], sub.second) }
                rv.setInt(tid, "setBackgroundResource", when {
                    t.on == 1 -> R.drawable.widget_qtile_on
                    t.id == "now" -> if (anyOn) R.drawable.widget_qtile_green else R.drawable.widget_qtile_red
                    sub?.second == RED -> R.drawable.widget_qtile_red
                    else -> R.drawable.widget_qtile })
                val pi = if (t.id == "saver")
                    PendingIntent.getBroadcast(c, 7400 + i, Intent(c, QuickWidget::class.java).setAction(ACT).putExtra("cmd", "saver"),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                else WidgetData.openIntent(c, "q:" + t.id, 7300 + i)
                rv.setOnClickPendingIntent(tid, pi)
            }
            return rv
        }

        /**
         * 앱에서 '홈 화면에 위젯 추가' (안드로이드 8+ · 홈 화면 앱이 지원할 때)
         *  - 지원 안 하면 손으로 넣는 법 안내
         */
        fun pin(a: Activity, cls: Class<out AppWidgetProvider>, name: String) {
            val m = a.getSystemService(AppWidgetManager::class.java)
            val ok = try { Build.VERSION.SDK_INT >= 26 && m != null && m.isRequestPinAppWidgetSupported } catch (_: Exception) { false }
            // v6.31 (283): '홈 화면에 추가' 창에 진짜 위젯 모양 미리보기 (빈 기본 모양 → '위젯을 추가할 수 없습니다' 대신) · 가리던 안내는 없앰
            val preview: RemoteViews? = try {
                when (cls) {
                    QuickWidget::class.java -> build(a, 130)
                    TideWidget::class.java -> TideWidget.build(a, 250, 70)
                    BagWidget::class.java -> BagWidget.build(a)
                    else -> null
                }
            } catch (_: Exception) { null }
            val extras = preview?.let { Bundle().apply { putParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW, it) } }
            if (ok && try { m!!.requestPinAppWidget(ComponentName(a, cls), extras, null) } catch (_: Exception) { false }) return
            AlertDialog.Builder(a, R.style.FlAlert).setTitle("📱 $name 위젯 넣는 법")
                .setMessage("이 폰의 홈 화면 앱은 바로 넣기를 지원하지 않아요.\n\n① 홈 화면 빈 곳을 꾹 누르기\n② '위젯' 누르기\n③ '조과일지' 찾기 → '$name' 을 꾹 눌러 홈 화면에 놓기")
                .setPositiveButton("확인", null).show()
        }

        /** 더보기: 위젯 고르기 → 홈 화면에 추가 */
        fun pinMenu(a: Activity) {
            val opts = listOf(
                Triple("⚡ 퀵바 위젯 — 퀵바 칸을 홈 화면에", QuickWidget::class.java, "⚡ 조과일지 퀵바"),
                Triple("🌊 물때 위젯 — 오늘 물때 · 날씨 · 그래프", TideWidget::class.java, "🌊 조과일지 물때"),
                Triple("🎒 가방 위젯 — 카테고리별 수량 −/＋", BagWidget::class.java, "🎒 조과일지 가방"))
            AlertDialog.Builder(a, R.style.FlAlert).setTitle("📱 홈 화면에 위젯 추가")
                .setItems(opts.map { it.first }.toTypedArray()) { _, w -> pin(a, opts[w].second, opts[w].third) }
                .setNegativeButton("닫기", null).show()
        }
    }

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) { ids.forEach { draw(c, m, it) } }

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) { draw(c, m, id) }

    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action == ACT && intent.getStringExtra("cmd") == "refresh") {
            val app = c.applicationContext
            if (System.currentTimeMillis() - prefs(app).getLong("w_refreshing", 0L) < 60_000L) return   // 이미 확인 중
            prefs(app).edit().putLong("w_refreshing", System.currentTimeMillis()).apply()
            updateAll(app)
            val pr = goAsync()
            Thread {
                val n = try { GroupAlert.check(app, force = true) } catch (_: Exception) { -1 }
                prefs(app).edit().putLong("w_refreshing", 0L).apply()
                updateAll(app)
                try { TideWidget.updateAll(app) } catch (_: Exception) {}
                val live = prefs(app).getInt("w_live_n", -1)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(app, when {
                        n < 0 -> "확인을 못 했어요 (인터넷 · 로그인 확인)"
                        n == 0 -> "새 소식 없어요" + if (live > 0) " · 출조 중 ${live}명" else ""
                        else -> "새 소식 ${n}개 · 위쪽 알림을 확인하세요" + if (live > 0) " · 출조 중 ${live}명" else ""
                    }, android.widget.Toast.LENGTH_SHORT).show()
                    try { pr.finish() } catch (_: Exception) {}
                }
            }.start()
            return
        }
        if (intent.action == ACT && intent.getStringExtra("cmd") == "saver") {
            try {
                Store.init(c)
                Store.saver = !Store.saver
                // 위젯 칸 켜짐 표시도 바로
                val a = JSONArray(prefs(c).getString("w_quick", null) ?: "[]")
                for (i in 0 until a.length()) a.getJSONObject(i).let { if (it.optString("id") == "saver") it.put("on", if (Store.saver) 1 else 0) }
                prefs(c).edit().putString("w_quick", a.toString()).apply()
                android.widget.Toast.makeText(c, if (Store.saver) "🔋 배터리 절약 켬" else "배터리 절약 끔", android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
            updateAll(c)
            return
        }
        super.onReceive(c, intent)
    }
}
