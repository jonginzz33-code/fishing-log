package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import java.util.Locale

/**
 * 물때 지역(조위관측소) 고르기 + 검색
 *  - 관측소 이름 일부만 쳐도 걸러짐 ("송" → 인천송도)
 *  - 관측소가 없는 지명(무창포, 오천항 …)은 지도 검색으로 위치를 찾아 가까운 관측소를 보여줌 (인터넷 필요)
 *  - 최근에 고른 관측소 3곳은 위쪽에
 *  - v5.1 (232): 카카오 장소 검색으로 지점 고르기 (예: 신도) → 물때는 가까운 관측소, 날씨·바람은 그 지점 기준
 *                ⭐ 즐겨찾기 지점 (추가 · 이름 바꾸기 · 삭제)
 */
object StationPicker {

    private val main = Handler(Looper.getMainLooper())

    fun show(a: Activity, lat: Double?, lon: Double?, onPicked: () -> Unit) {
        fun dp(v: Int) = Ui.dp(a, v)
        val all = Tide.stationsNear(a, lat, lon)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(4)) }
        val search = EditText(a).apply {
            hint = "검색: 지명 · 섬 · 항구 · 관측소 (예: 신도)"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setTextColor(Ui.TEXT); setHintTextColor(Ui.SUB)
        }
        box.addView(search, Ui.lp(a, MATCH, WRAP))
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list, Ui.lp(a, MATCH, WRAP))

        val dlg = AlertDialog.Builder(a, R.style.FlAlert)
            .setTitle("물때 · 날씨 지점")
            .setView(ScrollView(a).apply { addView(box) })
            .setNegativeButton("닫기", null)
            .create()

        fun pick(code: String?, name: String) {
            Spot.current = null   // 관측소를 고르면 지점은 해제 (날씨는 관측소 자리 기준)
            Store.tideStation = code
            if (code != null) Store.rememberStation(code)
            dlg.dismiss()
            android.widget.Toast.makeText(a, if (code == null) "가장 가까운 관측소로 물때를 봐요" else "$name 관측소로 물때를 봐요",
                android.widget.Toast.LENGTH_SHORT).show()
            onPicked()
        }

        fun row(label: String, sub: String = "", strong: Boolean = false, onClick: (() -> Unit)?) =
            LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                minimumHeight = dp(52)
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.bg(a, if (onClick == null) android.graphics.Color.TRANSPARENT else Ui.CARD, 12, if (onClick == null) 0 else Ui.LINE)
                addView(Ui.text(a, if (strong) 17f else 16f, if (onClick == null) Ui.SUB else Ui.TEXT, onClick != null, label))
                if (sub.isNotBlank()) addView(Ui.text(a, 12f, Ui.SUB, false, sub))
                if (onClick != null) { isClickable = true; setOnClickListener { onClick() } }
            }

        fun km(s: Tide.Station, la: Double?, lo: Double?): String =
            Tide.stationKm(s, la, lo)?.let { String.format(Locale.KOREA, "%.0fkm", it) } ?: ""

        // ---- v5.1 (232): 지점 ----
        fun pickPlace(p: Spot.Place) {
            Spot.current = p
            Store.tideStation = null   // 물때는 지점에서 가장 가까운 관측소
            dlg.dismiss()
            val st = Tide.stationFor(a, p.lat, p.lon)
            android.widget.Toast.makeText(a, "📍 ${p.name} 기준으로 봐요" + (st?.let { " · 물때: ${it.name} 관측소" } ?: ""),
                android.widget.Toast.LENGTH_SHORT).show()
            onPicked()
        }
        fun smallBtn(label: String, color: Int, click: () -> Unit) = Ui.text(a, 13f, color, true, label).apply {
            gravity = Gravity.CENTER
            background = Ui.bg(a, android.graphics.Color.TRANSPARENT, 99, Ui.LINE)
            setOnClickListener { click() }
        }
        var rerender: () -> Unit = {}
        /** 지점 한 줄: 이름 · 주소 / 물때 관측소 · [☆ 또는 ✎ 🗑] */
        fun placeRow(p: Spot.Place, favIndex: Int?): LinearLayout {
            val on = Spot.current?.let { Spot.same(it, p) } == true
            val near = Tide.stationsNear(a, p.lat, p.lon).firstOrNull()
            val line = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(8), dp(10)); minimumHeight = dp(52)
                background = Ui.bg(a, Ui.CARD, 12, if (on) Ui.GREEN else Ui.LINE)
            }
            val txt = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; isClickable = true; setOnClickListener { pickPlace(p) } }
            txt.addView(Ui.text(a, 16f, Ui.TEXT, true, (if (on) "✓ " else "") + (if (favIndex != null) "⭐ " else "📍 ") + p.name))
            val sub = listOfNotNull(p.sub.ifBlank { null },
                near?.let { "물때: ${it.name} 관측소 " + km(it, p.lat, p.lon) }).joinToString("\n")
            if (sub.isNotBlank()) txt.addView(Ui.text(a, 12f, Ui.SUB, false, sub))
            line.addView(txt, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (favIndex == null) {
                val fav = Spot.isFav(p)
                line.addView(smallBtn(if (fav) "★" else "☆", if (fav) android.graphics.Color.parseColor("#F2C14E") else Ui.TEXT) {
                    if (Spot.addFav(p)) android.widget.Toast.makeText(a, "⭐ 즐겨찾기에 넣었어요", android.widget.Toast.LENGTH_SHORT).show()
                    else android.widget.Toast.makeText(a, "이미 즐겨찾기에 있어요", android.widget.Toast.LENGTH_SHORT).show()
                    rerender()
                }, LinearLayout.LayoutParams(dp(42), dp(36)).apply { leftMargin = dp(6) })
            } else {
                line.addView(smallBtn("✎", Ui.TEXT) {
                    val et = EditText(a).apply { setText(p.name); setSingleLine(); setTextColor(Ui.TEXT); setSelection(text.length) }
                    AlertDialog.Builder(a, R.style.FlAlert).setTitle("즐겨찾기 이름 바꾸기")
                        .setView(LinearLayout(a).apply { setPadding(dp(20), dp(4), dp(20), 0); addView(et, Ui.lp(a, MATCH, WRAP)) })
                        .setPositiveButton("저장") { _, _ -> Spot.renameFav(favIndex, et.text.toString()); rerender() }
                        .setNegativeButton("취소", null).show()
                }, LinearLayout.LayoutParams(dp(42), dp(36)).apply { leftMargin = dp(6) })
                line.addView(smallBtn("🗑", android.graphics.Color.parseColor("#FF8A92")) {
                    AlertDialog.Builder(a, R.style.FlAlert).setTitle("즐겨찾기에서 지울까요?").setMessage(p.name)
                        .setPositiveButton("지우기") { _, _ -> Spot.deleteFav(favIndex); rerender() }
                        .setNegativeButton("취소", null).show()
                }, LinearLayout.LayoutParams(dp(42), dp(36)).apply { leftMargin = dp(6) })
            }
            return line
        }

        fun stationRow(s: Tide.Station): LinearLayout {
            val on = s.code == Store.tideStation
            return row((if (on) "✓ " else "") + s.name + "  " + km(s, lat, lon).let { if (it.isBlank()) "" else it }, onClick = { pick(s.code, s.name) })
        }

        var searchId = 0
        fun render(q: String) {
            list.removeAllViews()
            val key = q.replace(" ", "")
            fun add(v: LinearLayout) = list.addView(v, Ui.lp(a, MATCH, WRAP, top = 8))
            if (key.isEmpty()) {
                // v5.1: 지금 고른 지점 · ⭐ 즐겨찾기
                Spot.current?.let { c ->
                    add(row("지금 지점", onClick = null))
                    add(placeRow(c, null))
                    add(row("📍 지점 해제 (내 위치 · 관측소 기준으로)", "날씨 · 바람이 다시 내 위치 기준이 돼요") { pick(null, "") })
                }
                val favs = Spot.favorites()
                add(row("⭐ 즐겨찾기 지점", if (favs.isEmpty()) "검색 결과의 ☆를 누르면 여기 들어와요" else "누르면 그 지점으로 · ✎ 이름 바꾸기 · 🗑 지우기", onClick = null))
                favs.forEachIndexed { i, p -> add(placeRow(p, i)) }
                add(row("물때 관측소", onClick = null))
                add(row((if (Store.tideStation == null && Spot.current == null) "✓ " else "") + "가장 가까운 곳 자동 (추천)", strong = true) { pick(null, "") })
                val recent = Store.recentStations().mapNotNull { c -> all.firstOrNull { it.code == c } }
                if (recent.isNotEmpty()) {
                    add(row("최근에 고른 곳", onClick = null))
                    recent.forEach { add(stationRow(it)) }
                    add(row("가까운 순", onClick = null))
                }
                all.forEach { add(stationRow(it)) }
                return
            }
            val hits = all.filter { it.name.replace(" ", "").contains(key) }
            hits.forEach { add(stationRow(it)) }
            if (hits.isEmpty()) add(row("'$q' 이름의 관측소는 없어요", "아래에서 지명으로 근처 관측소를 찾아요", onClick = null))
            // 지명으로 찾기 (관측소가 없는 곳: 무창포, 오천항 …)
            if (key.length >= 2) {
                val placeBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                list.addView(placeBox, Ui.lp(a, MATCH, WRAP, top = 4))
                placeBox.addView(row("'$q' 지명 찾는 중…", onClick = null), Ui.lp(a, MATCH, WRAP, top = 8))
                val id = ++searchId
                Thread {
                    // v5.1: 카카오 장소 검색 먼저 (섬 · 항구 · 낚시터 이름까지)
                    val kr = Spot.kakao(q)
                    if (kr.places.isNotEmpty()) {
                        main.post {
                            if (id != searchId || !dlg.isShowing) return@post
                            placeBox.removeAllViews()
                            placeBox.addView(row("📍 '$q' 지점 (누르면 이 지점 기준 · ☆ 즐겨찾기)", onClick = null), Ui.lp(a, MATCH, WRAP, top = 8))
                            kr.places.forEach { p -> placeBox.addView(placeRow(p, null), Ui.lp(a, MATCH, WRAP, top = 6)) }
                        }
                        return@Thread
                    }
                    // 1) 폰 주소 찾기 (주소 · 동네)  2) 오픈스트리트맵 지명 (호수 · 섬 · 방파제 등, 예: 시화호) — v4.2
                    var netOk = false
                    val found = ArrayList<Triple<String, Double, Double>>()
                    try {
                        if (Geocoder.isPresent()) {
                            @Suppress("DEPRECATION")
                            val r = Geocoder(a, Locale.KOREA).getFromLocationName(q, 4, 33.0, 124.5, 38.7, 131.0) ?: emptyList()
                            netOk = true
                            r.forEach { ad ->
                                val name = listOfNotNull(ad.featureName, ad.locality ?: ad.subAdminArea, ad.adminArea)
                                    .distinct().joinToString(" · ").ifBlank { q }
                                found.add(Triple(name, ad.latitude, ad.longitude))
                            }
                        }
                    } catch (_: Exception) {}
                    if (id == searchId) MapSearch.osm(q, wait = true)?.let { r ->
                        netOk = true
                        r.take(4).forEach { (p, full) ->
                            if (found.none { Math.abs(it.second - p.lat) < 0.01 && Math.abs(it.third - p.lon) < 0.01 })
                                found.add(0, Triple(if (full.isBlank()) p.name else "${p.name} · ${full.split(" ").take(2).joinToString(" ")}", p.lat, p.lon))
                        }
                    }
                    main.post {
                        if (id != searchId || !dlg.isShowing) return@post
                        placeBox.removeAllViews()
                        if (!netOk && found.isEmpty()) {
                            placeBox.addView(row("지명 검색은 인터넷이 필요해요", onClick = null), Ui.lp(a, MATCH, WRAP, top = 8))
                            return@post
                        }
                        if (kr.error.isNotBlank()) placeBox.addView(row("ⓘ " + kr.error, onClick = null), Ui.lp(a, MATCH, WRAP, top = 8))
                        if (found.isEmpty()) {
                            placeBox.addView(row("'$q' 지명을 찾지 못했어요", "시 · 군 이름을 같이 써 보세요 (예: 시흥 시화호)", onClick = null), Ui.lp(a, MATCH, WRAP, top = 8))
                            return@post
                        }
                        placeBox.addView(row("📍 '$q' 지점 (누르면 이 지점 기준 · ☆ 즐겨찾기)", onClick = null), Ui.lp(a, MATCH, WRAP, top = 8))
                        found.take(5).forEach { (name, la, lo) ->
                            placeBox.addView(placeRow(Spot.Place(name.substringBefore(" · "), la, lo, name.substringAfter(" · ", "")), null), Ui.lp(a, MATCH, WRAP, top = 6))
                        }
                    }
                }.start()
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            private var pending: Runnable? = null
            override fun afterTextChanged(s: Editable?) {
                pending?.let { main.removeCallbacks(it) }
                val q = s?.toString()?.trim() ?: ""
                // 글자 칠 때마다 바로 거르되, 지명 검색은 0.5초 멈췄을 때만
                pending = Runnable { render(q) }.also { main.postDelayed(it, if (q.length >= 2) 500L else 0L) }
            }
        })
        rerender = { render(search.text?.toString()?.trim() ?: "") }
        render("")
        dlg.show()
    }
}
