package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 출조 때 날씨(출조 기록에 저장된 값)를 보여 주기 — 후기(전체 게시판) · 그룹 출조 기록에서 같이 씀
 *  - 한 줄 요약: "🌡 12° · ⛅ · 북서 3.3m/s(최대 6.1) · 파고 0.4m · 수온 16.1°(실측)"
 *  - 표: 시각 · 하늘 · 기온 · 바람 · 파고 · 수온
 */
object WxView {
    /** 출조 시간대(앞뒤 30분) 값만 */
    private fun inTrip(snap: Weather.Snap, start: Long, end: Long): List<Weather.Hour> {
        val e = if (end > 0) end else start
        val l = snap.hours.filter { it.time >= start - 1_800_000L && it.time <= e + 1_800_000L }
        return l.ifEmpty { snap.hours }
    }

    fun summary(snap: Weather.Snap, start: Long, end: Long): String {
        val hs = inTrip(snap, start, end)
        if (hs.isEmpty()) return ""
        val parts = ArrayList<String>()
        hs.mapNotNull { it.temp }.takeIf { it.isNotEmpty() }?.let { t ->
            val lo = t.minOrNull()!!; val hi = t.maxOrNull()!!
            parts.add("🌡 " + if (Math.round(hi) - Math.round(lo) >= 2) String.format(Locale.KOREA, "%.0f~%.0f°", lo, hi) else String.format(Locale.KOREA, "%.0f°", t.average()))
        }
        hs.map { it.skyIcon() }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key?.let { parts.add(it) }
        hs.filter { it.wind != null }.maxByOrNull { it.wind!! }?.let { h ->
            parts.add((h.dir?.let { Weather.dir16(it) + " " } ?: "") + String.format(Locale.KOREA, "%.1fm/s", h.wind) +
                (hs.mapNotNull { it.gust }.maxOrNull()?.let { String.format(Locale.KOREA, "(최대 %.1f)", it) } ?: ""))
        }
        hs.mapNotNull { it.wave }.maxOrNull()?.let { parts.add(String.format(Locale.KOREA, "파고 %.1fm", it)) }
        val meas = snap.measured.filterKeys { k -> hs.any { kotlin.math.abs(it.time - k) < 1_800_000L } }.values
        if (meas.isNotEmpty()) parts.add(String.format(Locale.KOREA, "수온 %.1f°(실측)", meas.average()))
        else hs.mapNotNull { it.sst }.takeIf { it.isNotEmpty() }?.let { parts.add(String.format(Locale.KOREA, "수온 %.1f°", it.average())) }
        return parts.joinToString(" · ")
    }

    /** 시간별 표 */
    fun table(c: Context, snap: Weather.Snap, start: Long, end: Long): View {
        fun dp(v: Int) = Ui.dp(c, v)
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val hf = SimpleDateFormat("HH시", Locale.KOREA)
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        fun hc(txt: String, w: Float) = Ui.text(c, 11f, Ui.SUB, true, txt).apply { gravity = Gravity.CENTER }.also { head.addView(it, LinearLayout.LayoutParams(0, WRAP, w)) }
        hc("시각", 1f); hc("하늘", 0.8f); hc("기온", 0.9f); hc("바람", 1.5f); hc("파고", 1.3f); hc("수온", 1f)
        box.addView(head)
        snap.hours.forEach { h ->
            val r = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, dp(5)) }
            fun cell(txt: String, w: Float, col: Int = Ui.TEXT) = Ui.text(c, 12.5f, col, false, txt).apply {
                gravity = Gravity.CENTER; maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(8, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }.also { r.addView(it, LinearLayout.LayoutParams(0, dp(20), w)) }
            val e = if (end > 0) end else h.time
            val inT = h.time >= start - 1_800_000L && h.time <= e + 1_800_000L
            cell(hf.format(Date(h.time)), 1f, if (inT) Ui.TEXT else Ui.SUB)
            cell(h.skyIcon().ifBlank { "-" }, 0.8f)
            cell(h.temp?.let { String.format(Locale.KOREA, "%.0f°", it) } ?: "-", 0.9f)
            cell(h.wind?.let { String.format(Locale.KOREA, "%.1fm/s", it) + (h.dir?.let { d -> " " + Weather.dir16(d) } ?: "") } ?: "-", 1.5f, Weather.windColor(h.wind))
            cell(h.wave?.let { String.format(Locale.KOREA, "%.1fm", it) + (h.period?.let { p -> String.format(Locale.KOREA, " %.0fs", p) } ?: "") } ?: "-", 1.3f)
            val ms = snap.measured[h.time]
            cell((ms ?: h.sst)?.let { String.format(Locale.KOREA, "%.1f°", it) + if (ms != null) "실" else "" } ?: "-", 1f, if (ms != null) Ui.GREEN else Ui.TEXT)
            box.addView(r)
        }
        box.addView(Ui.text(c, 11f, Ui.SUB, false,
            "출조할 때 저장된 값 · 수온 '실' = 관측소 실측" + (if (snap.station.isNotBlank()) " (${snap.station})" else "")),
            Ui.lp(c, MATCH, WRAP, top = 6))
        return box
    }

    /** 요약 한 줄 + 누르면 시간별 표 펼치기 (커튼) */
    fun curtain(c: Context, wxJson: String, start: Long, end: Long): View? {
        val snap = Weather.snapFromJson(wxJson) ?: return null
        val sum = summary(snap, start, end).ifBlank { return null }
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(c, 12), Ui.dp(c, 9), Ui.dp(c, 12), Ui.dp(c, 9))
            background = Ui.bg(c, Ui.CARD, 10, Ui.LINE)
        }
        val head = Ui.text(c, 13f, Ui.TEXT, true, "")
        card.addView(head)
        var tbl: View? = null
        var open = false
        fun paint() { head.text = (if (open) "▾ " else "▸ ") + "그때 날씨 · " + sum; tbl?.visibility = if (open) View.VISIBLE else View.GONE }
        card.setOnClickListener {
            open = !open
            if (open && tbl == null) { tbl = table(c, snap, start, end); card.addView(tbl, Ui.lp(c, MATCH, WRAP, top = 8)) }
            paint()
        }
        paint()
        return card
    }

    /** 그룹 출조 기록 메뉴에서: 그때 날씨 창 */
    fun showDialog(a: Activity, title: String, wxJson: String, start: Long, end: Long) {
        val snap = Weather.snapFromJson(wxJson)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(a, 18), Ui.dp(a, 6), Ui.dp(a, 18), Ui.dp(a, 6)) }
        if (snap == null) box.addView(Ui.text(a, 14f, Ui.SUB, false, "이 출조에는 날씨 정보가 없어요 (예전에 올린 글은 올린 사람이 다시 올리면 채워져요)"))
        else {
            box.addView(Ui.text(a, 14f, Ui.TEXT, true, summary(snap, start, end)))
            box.addView(table(a, snap, start, end), Ui.lp(a, MATCH, WRAP, top = 10))
        }
        AlertDialog.Builder(a, R.style.FlAlert)
            .setTitle(title).setView(ScrollView(a).apply { addView(box) }).setPositiveButton("닫기", null).show()
    }
}
