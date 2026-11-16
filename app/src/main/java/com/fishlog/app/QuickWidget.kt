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
        private val TILES = intArrayOf(R.id.wq_0, R.id.wq_1, R.id.wq_2, R.id.wq_3, R.id.wq_4, R.id.wq_5, R.id.wq_6, R.id.wq_7, R.id.wq_8, R.id.wq_9)
        private fun prefs(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)

        /** 앱 퀵바가 바뀔 때마다 (id · 그림 · 이름 · 켜짐(-1 = 켜고 끄는 칸 아님)) */
        fun save(c: Context, items: List<Triple<String, String, String>>, states: Map<String, Boolean>) {
            try {
                val a = JSONArray()
                items.forEach { (id, icon, label) -> a.put(JSONObject().put("id", id).put("i", icon).put("l", label).put("on", states[id]?.let { if (it) 1 else 0 } ?: -1)) }
                prefs(c).edit().putString("w_quick", a.toString()).apply()
            } catch (_: Exception) {}
            updateAll(c)
        }

        fun updateAll(c: Context) {
            try {
                val m = AppWidgetManager.getInstance(c)
                m.getAppWidgetIds(ComponentName(c, QuickWidget::class.java)).forEach { draw(c, m, it) }
            } catch (_: Exception) {}
        }

        private class T(val id: String, val icon: String, val label: String, val on: Int)

        private fun items(c: Context): List<T> {
            val list = try {
                val a = JSONArray(prefs(c).getString("w_quick", null) ?: "[]")
                (0 until a.length()).map { val o = a.getJSONObject(it); T(o.optString("id"), o.optString("i"), o.optString("l"), o.optInt("on", -1)) }
            } catch (_: Exception) { emptyList() }
            // 앱을 아직 안 열었으면 기본(추천 · 그룹변경)
            return list.ifEmpty { listOf(T("cal", "🎣", "추천", -1), T("group", "🔄", "그룹변경", -1)) }
        }

        fun draw(c: Context, m: AppWidgetManager, wid: Int) {
            val opt: Bundle = m.getAppWidgetOptions(wid)
            val hDp = opt.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 70)
            val all = items(c)
            val two = hDp >= 110 && all.size + 1 > 5
            val slots = if (two) 10 else 5
            val shown = all.take(slots - 1) + T("qset", "⚡", "설정", -1)
            val rv = RemoteViews(c.packageName, R.layout.widget_quick)
            rv.setViewVisibility(R.id.wq_row1, if (two) View.VISIBLE else View.GONE)
            TILES.forEachIndexed { i, tid ->
                val t = shown.getOrNull(i)
                if (t == null) { rv.setViewVisibility(tid, View.INVISIBLE); return@forEachIndexed }
                rv.setViewVisibility(tid, View.VISIBLE)
                val sb = SpannableStringBuilder(t.icon).apply { setSpan(RelativeSizeSpan(1.6f), 0, length, 0) }
                sb.append("\n").append(t.label + if (t.on == 1) " ●" else "")
                rv.setTextViewText(tid, sb)
                rv.setInt(tid, "setBackgroundResource", if (t.on == 1) R.drawable.widget_tile_on else R.drawable.widget_tile)
                val pi = if (t.id == "saver")
                    PendingIntent.getBroadcast(c, 7400 + i, Intent(c, QuickWidget::class.java).setAction(ACT).putExtra("cmd", "saver"),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                else WidgetData.openIntent(c, "q:" + t.id, 7300 + i)
                rv.setOnClickPendingIntent(tid, pi)
            }
            m.updateAppWidget(wid, rv)
        }

        /**
         * 앱에서 '홈 화면에 위젯 추가' (안드로이드 8+ · 홈 화면 앱이 지원할 때)
         *  - 지원 안 하면 손으로 넣는 법 안내
         */
        fun pin(a: Activity, cls: Class<out AppWidgetProvider>, name: String) {
            val m = a.getSystemService(AppWidgetManager::class.java)
            val ok = try { Build.VERSION.SDK_INT >= 26 && m != null && m.isRequestPinAppWidgetSupported } catch (_: Exception) { false }
            if (ok && try { m!!.requestPinAppWidget(ComponentName(a, cls), null, null) } catch (_: Exception) { false }) {
                android.widget.Toast.makeText(a, "'추가'를 누르면 홈 화면에 $name 위젯이 놓여요 (꾹 눌러 옮기기)", android.widget.Toast.LENGTH_LONG).show()
                return
            }
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
