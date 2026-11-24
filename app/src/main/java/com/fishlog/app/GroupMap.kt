package com.fishlog.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v6.74 (372): 🗺 그룹 지도 — 그룹 창 버튼으로 열기 · 그 그룹 포인트(groups/{gid}/pins)만 전부 하늘 별
 *  위 [📌 포인트 꽂기](처음부터 이 그룹 공유) · [☰ 포인트 N개](목록 · 누르면 그 자리로) · ⚓ 항구는 메인 지도 설정 그대로
 *  열면 이 그룹 새 포인트 소식(362)은 읽음
 */
object GroupMap {
    fun show(a: Activity, g: Cloud.Group, focusId: String? = null) {
        fun dp(v: Int) = Ui.dp(a, v)
        val dlg = Dialog(a, R.style.FlFull)
        val map = MapDialog.newMap(a)
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0B161E")) }

        // 제목 줄: 그룹 지도 그림 + 이름 · 닫기
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(12), dp(4)) }
        head.addView(TextView(a).apply {
            text = Ui.mapIcon(a, "🗺 " + g.title, 24, Ui.MAP_GROUP)
            textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Ui.pill(a, "닫기", false, Ui.TEXT, small = true) { dlg.dismiss() })
        root.addView(head)
        root.addView(Ui.text(a, 12f, Ui.SUB, false, "이 그룹에 공유한 포인트만 보여요 · 하늘색 별 = 그룹").apply { setPadding(dp(16), 0, dp(16), dp(6)) })

        // 버튼 줄
        val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), dp(8)) }
        val putBtn = Ui.pill(a, "📌 포인트 꽂기", false, Ui.GROUP)
        val listBtn = Ui.pill(a, "☰ 포인트 불러오는 중…", false, Ui.GROUP)
        bar.addView(putBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        bar.addView(listBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)

        val frame = FrameLayout(a)
        frame.addView(map, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val note = Ui.text(a, 12f, Ui.SUB, false, "").apply { setPadding(dp(16), dp(6), dp(16), dp(10)) }
        root.addView(note)

        var pins: List<Pins.Pin> = emptyList()
        lateinit var ov: Pins.PinOverlay
        fun fitAll() {
            val pts = pins.map { it.point }
            when {
                pts.isEmpty() -> {
                    val me = Tracker.last
                    map.controller.setZoom(if (me != null) 12.0 else 7.0)
                    map.controller.setCenter(me?.let { GeoPoint(it.latitude, it.longitude) } ?: GeoPoint(36.3, 127.8))
                }
                pts.size == 1 -> { map.controller.setZoom(14.0); map.controller.setCenter(pts[0]) }
                else -> map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts).increaseByScale(1.4f), false)
            }
        }
        fun load(first: Boolean) {
            Pins.loadGroup(g.id) { l ->
                if (a.isFinishing) return@loadGroup
                pins = (l ?: emptyList()).sortedByDescending { it.t }
                ov.pins = pins
                listBtn.text = if (l == null) "☰ 못 불러왔어요" else "☰ 포인트 ${pins.size}개"
                note.text = if (l == null) "포인트를 못 불러왔어요 (인터넷 · 서버 규칙 게시를 확인해 주세요)"
                    else if (pins.isEmpty()) "아직 이 그룹 포인트가 없어요 · [📌 포인트 꽂기]로 처음 올려 보세요"
                    else "포인트를 누르면 설명 · 범위 안 지난 조과 · 물때를 봐요"
                if (l != null) GroupNews.putPins(g.id, pins)
                // 열어서 다 봄 → 이 그룹 새 포인트 소식 지움
                GroupNews.markKindSeen(g.id, "pin")
                if (first) {
                    val f = pins.firstOrNull { it.id == focusId }
                    MapDialog.whenSized(map) {
                        if (f != null) { map.controller.setZoom(14.0); map.controller.setCenter(f.point); Pins.card(a, map, ov, f, emptyList()) { load(false) } }
                        else fitAll()
                    }
                }
                map.invalidate()
            }
        }
        ov = Pins.PinOverlay(a) { p -> Pins.card(a, map, ov, p, emptyList()) { load(false) } }
        map.overlays.add(ov)
        // 항구 (메인 지도 켜고 끄기 그대로 · 핀 아래)
        var portOv: Ports.PortOverlay? = null
        portOv = Ports.PortOverlay(a) { pt ->
            Ports.card(a, map, portOv!!, pt) { pp ->
                map.controller.animateTo(GeoPoint(pp.lat, pp.lon))
                map.postDelayed({ Pins.place(a, map, ov, null, Pins.GROUP, g.id) { load(false) } }, 400)
            }
        }
        portOv.isEnabled = Ports.on(a)
        map.overlays.add(0, portOv)
        putBtn.setOnClickListener {
            if (ov.placing) { ov.onCancel?.invoke(); return@setOnClickListener }
            Pins.place(a, map, ov, null, Pins.GROUP, g.id) { load(false) }
        }
        listBtn.setOnClickListener {
            if (pins.isEmpty()) { Ui.toast(a, "아직 포인트가 없어요"); return@setOnClickListener }
            val sh = Sheet(a, 0.6f)
            sh.head.addView(Ui.text(a, 17f, Ui.TEXT, true, "📌 ${g.title} 포인트 ${pins.size}개"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val df = SimpleDateFormat("M/d", Locale.KOREA)
            pins.forEach { p ->
                val row = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(9), dp(12), dp(9))
                    background = Ui.bg(a, Color.parseColor("#12283A"), 12, Color.parseColor("#2C4F66"))
                    setOnClickListener {
                        sh.close()
                        map.controller.animateTo(p.point); if (map.zoomLevelDouble < 13.0) map.controller.setZoom(14.0)
                        Pins.card(a, map, ov, p, emptyList()) { load(false) }
                    }
                }
                row.addView(Ui.text(a, 15f, Ui.TEXT, true, "").apply {
                    val sb = android.text.SpannableStringBuilder()
                    sb.append("★ "); sb.setSpan(android.text.style.ForegroundColorSpan(Ui.GROUP), 0, 1, 0); sb.append(p.name)
                    text = sb; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                if (p.memo.isNotBlank()) row.addView(Ui.text(a, 12.5f, Color.parseColor("#C9D8E2"), false, p.memo).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                row.addView(Ui.text(a, 11.5f, Ui.SUB, false, listOf(p.ownerName, df.format(Date(p.t)), "반경 " + Pins.rLabel(p.r),
                    p.sp.take(3).joinToString(" · ")).filter { it.isNotBlank() }.joinToString(" · ")))
                sh.body.addView(row, Ui.lp(a, -1, -2, top = 6))
            }
            sh.show()
        }

        MapDialog.zoomButtons(a, frame, map, { _ ->
            Tracker.last?.let { map.controller.animateTo(GeoPoint(it.latitude, it.longitude)) } ?: Ui.toast(a, "내 위치를 아직 몰라요")
        })
        dlg.setContentView(root)
        Ui.edge(dlg.window)
        dlg.setOnDismissListener { map.onPause(); map.onDetach() }
        dlg.show()
        map.onResume()
        load(true)
    }
}
