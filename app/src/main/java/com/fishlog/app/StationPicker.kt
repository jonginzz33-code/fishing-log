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
 */
object StationPicker {

    private val main = Handler(Looper.getMainLooper())

    fun show(a: Activity, lat: Double?, lon: Double?, onPicked: () -> Unit) {
        fun dp(v: Int) = Ui.dp(a, v)
        val all = Tide.stationsNear(a, lat, lon)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(4)) }
        val search = EditText(a).apply {
            hint = "검색: 관측소 이름이나 지명 (예: 무창포)"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setTextColor(Ui.TEXT); setHintTextColor(Ui.SUB)
        }
        box.addView(search, Ui.lp(a, MATCH, WRAP))
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list, Ui.lp(a, MATCH, WRAP))

        val dlg = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("물때 지역 (조위관측소)")
            .setView(ScrollView(a).apply { addView(box) })
            .setNegativeButton("닫기", null)
            .create()

        fun pick(code: String?, name: String) {
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
                add(row((if (Store.tideStation == null) "✓ " else "") + "가장 가까운 곳 자동 (추천)", strong = true) { pick(null, "") })
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
                    val found = try {
                        if (!Geocoder.isPresent()) null
                        else {
                            @Suppress("DEPRECATION")
                            Geocoder(a, Locale.KOREA).getFromLocationName(q, 4, 33.0, 124.5, 38.7, 131.0)
                        }
                    } catch (_: Exception) { null }
                    main.post {
                        if (id != searchId || !dlg.isShowing) return@post
                        placeBox.removeAllViews()
                        if (found == null) {
                            placeBox.addView(row("지명 검색은 인터넷이 필요해요", onClick = null), Ui.lp(a, MATCH, WRAP, top = 8))
                            return@post
                        }
                        if (found.isEmpty()) {
                            placeBox.addView(row("'$q' 지명을 찾지 못했어요", onClick = null), Ui.lp(a, MATCH, WRAP, top = 8))
                            return@post
                        }
                        found.forEach { ad ->
                            val name = listOfNotNull(ad.featureName, ad.locality ?: ad.subAdminArea, ad.adminArea)
                                .distinct().joinToString(" · ").ifBlank { q }
                            placeBox.addView(row("$name 근처 관측소", onClick = null), Ui.lp(a, MATCH, WRAP, top = 10))
                            Tide.stationsNear(a, ad.latitude, ad.longitude).take(3).forEach { s ->
                                placeBox.addView(row("${s.name}  약 ${km(s, ad.latitude, ad.longitude)}",
                                    "누르면 이 관측소로 물때를 봐요") { pick(s.code, s.name) }, Ui.lp(a, MATCH, WRAP, top = 6))
                            }
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
        render("")
        dlg.show()
    }
}
