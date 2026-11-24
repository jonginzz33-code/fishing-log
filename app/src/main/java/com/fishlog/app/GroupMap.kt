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
        root.addView(Ui.text(a, 12f, Ui.SUB, false, "이 그룹 포인트(하늘 별) · 그룹 출조 조과 점(출조마다 색)").apply { setPadding(dp(16), 0, dp(16), dp(6)) })

        // 버튼 줄
        val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), dp(8)) }
        val putBtn = Ui.pill(a, "📌 포인트 꽂기", false, Ui.GROUP)
        val listBtn = Ui.pill(a, "☰ 포인트 …", false, Ui.GROUP)
        val catchBtn = Ui.pill(a, "🐟 조과 …", false, Ui.GROUP)
        bar.addView(putBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        bar.addView(listBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        bar.addView(catchBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        listOf(putBtn, listBtn, catchBtn).forEach { it.textSize = 13f; it.setPadding(dp(6), 0, dp(6), 0) }
        root.addView(bar)

        val frame = FrameLayout(a)
        frame.addView(map, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val note = Ui.text(a, 12f, Ui.SUB, false, "").apply { setPadding(dp(16), dp(6), dp(16), dp(10)) }
        root.addView(note)

        var pins: List<Pins.Pin> = emptyList()
        lateinit var ov: Pins.PinOverlay
        // v6.80 (383): 그룹 조과 점
        val gp = a.getSharedPreferences("fishlog", android.content.Context.MODE_PRIVATE)
        var catchOn = gp.getBoolean("gm_catch", true)
        var catchDays = gp.getInt("gm_days", 0)
        val catchSp = HashSet(gp.getStringSet("gm_sp", emptySet()) ?: emptySet())
        var catchOv: org.osmdroid.views.overlay.Overlay? = null
        var catchPts: List<GeoPoint> = emptyList()
        var catchInfo = ""; var spCount: Map<String, Int> = emptyMap()
        var fitted = false
        var catchFirst = true
        lateinit var paintNote: () -> Unit
        fun fitAll() {
            val pts = pins.map { it.point } + (if (catchOn) catchPts else emptyList())
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
                paintNote()
                if (l != null) GroupNews.putPins(g.id, pins)
                // 열어서 다 봄 → 이 그룹 새 포인트 소식 지움
                GroupNews.markKindSeen(g.id, "pin")
                if (first) {
                    val f = pins.firstOrNull { it.id == focusId }
                    MapDialog.whenSized(map) {
                        if (f != null) { map.controller.setZoom(14.0); map.controller.setCenter(f.point); Pins.card(a, map, ov, f, emptyList()) { load(false) } }
                        else if (!fitted) { fitted = pins.isNotEmpty(); fitAll() }
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
        portOv.isEnabled = gp.getBoolean("gm_ports", false)   // v6.80 (383): 그룹 지도는 항구 기본 꺼짐 (조과 점과 안 섞이게)
        map.overlays.add(0, portOv)
        paintNote = {
            note.text = listOf(
                if (catchOn) catchInfo.ifBlank { "🐟 조과 불러오는 중…" } else "🐟 조과 꺼짐",
                "📌 포인트 ${pins.size}개").joinToString(" · ") + " · 점 · 별을 누르면 자세히"
            catchBtn.text = if (!catchOn) "🐟 조과 끔" else "🐟 조과 ▾"
        }
        fun loadCatches() {
            if (!catchOn) { catchOv?.isEnabled = false; paintNote(); map.invalidate(); return }
            MapDialog.groupCatches(a, g, catchDays, catchSp, catchOv) { o, nT, nD, pts, cnt ->
                if (a.isFinishing) return@groupCatches
                if (catchOv == null) { catchOv = o; map.overlays.add(minOf(1, map.overlays.size), o) }
                o.isEnabled = true
                catchPts = pts; spCount = cnt
                catchInfo = "🐟 출조 ${nT}번 · 점 ${nD}개" + (if (catchSp.isNotEmpty()) " (" + catchSp.joinToString(" · ") + ")" else "") +
                    (if (catchDays > 0) " · 최근 ${if (catchDays >= 365) "1년" else "${catchDays / 30}개월"}" else "")
                paintNote()
                if (catchFirst && pts.isNotEmpty()) { catchFirst = false; MapDialog.whenSized(map) { fitted = true; fitAll() } }
                map.invalidate()
            }
        }
        catchBtn.setOnClickListener {
            val sh = Sheet(a, 0.62f)
            sh.head.addView(Ui.text(a, 17f, Ui.TEXT, true, "🐟 그룹 조과 점"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            fun sw(t: String, on: Boolean, f: (Boolean) -> Unit) {
                val r = Ui.row(a).apply { setPadding(0, dp(6), 0, dp(6)) }
                r.addView(Ui.text(a, 14.5f, Ui.TEXT, true, t), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                r.addView(android.widget.Switch(a).apply { isChecked = on; setOnCheckedChangeListener { _, v -> f(v) } })
                sh.body.addView(r)
            }
            sw("🐟 조과 점 보이기", catchOn) { catchOn = it; gp.edit().putBoolean("gm_catch", it).apply(); loadCatches() }
            sw("⚓ 항구 보이기", portOv.isEnabled) { portOv.isEnabled = it; gp.edit().putBoolean("gm_ports", it).apply(); map.invalidate() }
            sh.body.addView(Ui.text(a, 13.5f, Ui.TEXT, true, "기간").apply { setPadding(0, dp(10), 0, dp(6)) })
            val pw = FlowLayout(a)
            listOf(0 to "전체", 30 to "1개월", 90 to "3개월", 365 to "1년").forEach { (d, t) ->
                pw.addView(Ui.pill(a, t, d == catchDays, Ui.GROUP, small = true) { catchDays = d; gp.edit().putInt("gm_days", d).apply(); sh.close(); loadCatches() })
            }
            sh.body.addView(pw)
            if (spCount.isNotEmpty()) {
                sh.body.addView(Ui.text(a, 13.5f, Ui.TEXT, true, "어종 (여러 개)").apply { setPadding(0, dp(12), 0, dp(6)) })
                val sw2 = FlowLayout(a)
                sw2.addView(Ui.pill(a, "전체", catchSp.isEmpty(), Ui.GROUP, small = true) { catchSp.clear(); gp.edit().putStringSet("gm_sp", HashSet(catchSp)).apply(); sh.close(); loadCatches() })
                spCount.toList().sortedByDescending { it.second }.forEach { (n, c) ->
                    sw2.addView(Ui.pill(a, "$n $c", n in catchSp, Ui.GROUP, small = true) {
                        if (n in catchSp) catchSp.remove(n) else catchSp.add(n); gp.edit().putStringSet("gm_sp", HashSet(catchSp)).apply(); sh.close(); loadCatches() })
                }
                sh.body.addView(sw2)
            }
            sh.body.addView(Ui.text(a, 11.5f, Ui.SUB, false, "점 색 = 출조마다 (최근 6번) · 점을 누르면 그날 조과 · [상세 ›] · 그룹 출조 기록에 위치가 남은 조과만 보여요").apply { setPadding(0, dp(12), 0, 0) })
            sh.show()
        }
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
        loadCatches()
    }
}
