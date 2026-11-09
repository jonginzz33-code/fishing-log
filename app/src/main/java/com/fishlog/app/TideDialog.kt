package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 물때 팝업 (메인 화면 "물때" 버튼): 날짜를 골라서 물때·물세기·바람·파도·수온 */
object TideDialog {

    private val titleFmt = SimpleDateFormat("M월 d일 (E)", Locale.KOREA)
    private val hFmt = SimpleDateFormat("HH:mm", Locale.KOREA)

    private fun dayStart(time: Long): Long = Calendar.getInstance().apply {
        timeInMillis = time; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 오늘 기준 며칠 차이: 0 → "오늘", 1 → "+1일", -1 → "−1일" */
    fun dayOffsetLabel(time: Long): String {
        val d = Math.round((dayStart(time) - dayStart(System.currentTimeMillis())) / 86_400_000.0).toInt()
        return when { d == 0 -> "오늘"; d > 0 -> "+${d}일"; else -> "−${-d}일" }
    }

    private fun offsetColor(time: Long): Int {
        val l = dayOffsetLabel(time)
        return when { l == "오늘" -> Ui.GREEN; l.startsWith("+") -> android.graphics.Color.parseColor("#F2C14E"); else -> Ui.SUB }
    }

    /** "8물 · 사리 · 10/27(화) 오늘" (날짜·오늘 부분은 같은 크기, 색만 다르게) */
    fun dayTitle(mul: String, time: Long, pct: Int? = null, short: Boolean = false): CharSequence {
        val sb = android.text.SpannableStringBuilder(mul)
        val st = sb.length
        sb.append(" · ").append(SimpleDateFormat("M/d(E)", Locale.KOREA).format(Date(time))).append(" ").append(dayOffsetLabel(time))
        sb.setSpan(android.text.style.ForegroundColorSpan(offsetColor(time)), st, sb.length, 0)
        // 날짜 바로 옆 작은 글씨: 물 많이/중간쯤/덜 빠지는 날 (물세기 기준이라 서해·남해·동해 모두 맞음)
        if (pct != null) {
            val (txt, col) = drainLabel(pct, short)
            sb.append(" ")
            val s2 = sb.length
            sb.append(txt)
            sb.setSpan(android.text.style.ForegroundColorSpan(col), s2, sb.length, 0)
            sb.setSpan(android.text.style.AbsoluteSizeSpan(12, true), s2, sb.length, 0)
        }
        return sb
    }

    /** 물세기 85% 이상 = 많이, 55~85 = 중간쯤, 55 미만 = 덜 */
    fun drainLabel(pct: Int, short: Boolean = false): Pair<String, Int> = when {
        pct >= 85 -> (if (short) "물 많이 빠짐" else "물 많이 빠지는 날") to Ui.GREEN
        pct >= 55 -> (if (short) "물 중간쯤 빠짐" else "물 중간쯤 빠지는 날") to android.graphics.Color.parseColor("#F2C14E")
        else -> (if (short) "물 덜 빠짐" else "물 덜 빠지는 날") to Ui.SUB
    }

    /** 만조·간조 알람 고르기 (출조 중에만 울림) */
    fun pickAlarm(a: Activity, done: () -> Unit) {
        val mins = listOf(0, 10, 20, 30, 40, 50, 60)
        val labels = mins.map { (if (it == Store.tideAlarmMin) "✓ " else "") + if (it == 0) "끄기" else "만조·간조 ${it}분 전" } +
            listOf((if (Store.floodAlert) "✓ " else "") + "🌊 밀물 시작 알림 (간조 지나 물 들어올 때) · " + (if (Store.floodAlert) "켜짐 (누르면 끄기)" else "꺼짐 (누르면 켜기)"),
                (if (Store.sunAlert) "✓ " else "") + "🌅 일출 · 일몰 30분 전 알림 · " + (if (Store.sunAlert) "켜짐 (누르면 끄기)" else "꺼짐 (누르면 켜기)"))
        BigList.show(a, "만조·간조 알람 (출조 중에만 울려요)", labels, emptyList()) { i, _ ->
            // v3.9.4 (172 · 173)
            if (i == mins.size) { Store.floodAlert = !Store.floodAlert; Ui.toast(a, if (Store.floodAlert) "출조 중 간조가 지나 물이 들어오기 시작하면 알려드려요" else "밀물 시작 알림을 껐어요"); done(); return@show }
            if (i == mins.size + 1) { Store.sunAlert = !Store.sunAlert; Ui.toast(a, if (Store.sunAlert) "출조 중 일출 · 일몰 30분 전에 알려드려요" else "일출 · 일몰 알림을 껐어요"); done(); return@show }
            Store.tideAlarmMin = mins[i]
            android.widget.Toast.makeText(a, if (mins[i] == 0) "알람을 껐어요" else "출조 중 만조·간조 ${mins[i]}분 전에 알려드려요",
                android.widget.Toast.LENGTH_SHORT).show()
            done()
        }
    }

    fun alarmLabel(): String = Store.tideAlarmMin.let { if (it <= 0) "만조·간조 알람: 끔" else "알람: 만조·간조 ${it}분 전 ⏰" }

    fun stationLabel(a: Activity, lat: Double?, lon: Double?): String {
        val st = Tide.stationFor(a, lat, lon) ?: return "물때 기준: 위치 확인 중 · 눌러서 고르기 ▾"
        val km = Tide.stationKm(st, lat, lon)?.let { String.format(Locale.KOREA, " · 내 위치에서 %.0fkm", it) } ?: ""
        return "물때 기준: ${st.name} 관측소" + (if (Store.tideStation != null) " (직접 고름)" else "") + km + " ▾"
    }

    /** 제목 줄: "물때 · 바람 · 수온 · 인천송도" */
    fun titleText(a: Activity, lat: Double?, lon: Double?, day: Long? = null): CharSequence {
        val st = Tide.stationFor(a, lat, lon)
        val sb = android.text.SpannableStringBuilder("물때 · 바람 · 수온")
        // v5.1 (232): 지점을 골랐으면 지점 이름 (예: 신도)
        val place = Spot.current
        if (st != null || place != null) {
            sb.append(" · ")
            val s0 = sb.length
            sb.append(place?.name ?: st!!.name)
            sb.setSpan(android.text.style.ForegroundColorSpan(Ui.BLUE), s0, sb.length, 0)
        }
        // v3.8: 지금 보고 있는 날짜 (오늘 = '오늘', 다른 날 = '11/7(토)' 노랑)
        if (day != null) {
            sb.append(" · ")
            val s0 = sb.length
            sb.append(dayTag(day))
            sb.setSpan(android.text.style.ForegroundColorSpan(if (isToday(day)) Ui.GREEN else Color.parseColor("#F2C14E")), s0, sb.length, 0)
        }
        return sb
    }

    private val tagFmt = SimpleDateFormat("M/d(E)", Locale.KOREA)
    private fun isToday(day: Long): Boolean = dayStart(day) == dayStart(System.currentTimeMillis())
    /** '오늘' 또는 '11/7(토)' */
    fun dayTag(day: Long): String = if (isToday(day)) "오늘" else tagFmt.format(Date(day))

    /** 제목 아래 작은 줄: "인천송도 관측소 기준 · 내 위치에서 32km · 바꾸기 ▾" */
    fun stationSub(a: Activity, lat: Double?, lon: Double?): String {
        val st = Tide.stationFor(a, lat, lon) ?: return "위치 확인 중 · 눌러서 관측소 고르기 ▾"
        // v5.1 (232): 고른 지점 기준
        Spot.current?.let { p ->
            val km = Tide.stationKm(st, p.lat, p.lon)?.let { String.format(Locale.KOREA, " (%.0fkm)", it) } ?: ""
            return "📍 ${p.name} 기준 · 물때: ${st.name} 관측소$km · 바꾸기 ▾"
        }
        val km = Tide.stationKm(st, lat, lon)?.let { String.format(Locale.KOREA, " · 내 위치에서 %.0fkm", it) } ?: ""
        return "${st.name} 관측소 기준" + (if (Store.tideStation != null) " (직접 고름)" else "") + km + " · 바꾸기 ▾"
    }

    fun show(a: Activity, gpsLat: Double, gpsLon: Double, time: Long, trip: Store.Trip?, onChanged: (() -> Unit)? = null) {
        fun dp(v: Int) = Ui.dp(a, v)
        // v5.1 (231 · 232): 기준 위치 = 고른 지점 → 직접 고른 관측소 자리 → 내 위치 (날씨·바람도 여기 기준)
        val (lat, lon) = Spot.base(a, gpsLat, gpsLon) ?: (gpsLat to gpsLon)
        var cur = dayStart(time)
        val today = dayStart(System.currentTimeMillis())
        var loadId = 0

        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }

        // ---- 날짜 고르기: ◀ 날짜 ▶ 오늘 ----
        val nav = Ui.row(a)
        fun navBtn(label: String) = Ui.text(a, 16f, Ui.TEXT, true, label).apply {
            gravity = Gravity.CENTER; background = Ui.bg(a, Ui.CARD, 12, Ui.LINE); isClickable = true
        }
        val prev = navBtn("◀")
        val dateText = navBtn("")
        val next = navBtn("▶")
        val todayBtn = navBtn("오늘").apply { textSize = 13f }
        nav.addView(prev, LinearLayout.LayoutParams(dp(44), dp(44)))
        nav.addView(dateText, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(6) })
        nav.addView(next, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(6) })
        nav.addView(todayBtn, LinearLayout.LayoutParams(dp(56), dp(44)).apply { leftMargin = dp(6) })
        col.addView(nav)
        // 만조·간조 알람 · 관측소 바꾸기 (맨 위로)
        val setRow = Ui.row(a)
        val alarmBtn = Ui.button(a, alarmLabel()).apply { minHeight = dp(44); textSize = 13f }
        val changeBtn = Ui.button(a, "📍 지점 · 관측소 검색").apply { minHeight = dp(44); textSize = 13f }
        setRow.addView(alarmBtn, LinearLayout.LayoutParams(0, WRAP, 1f))
        setRow.addView(changeBtn, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        col.addView(setRow, Ui.lp(a, MATCH, WRAP, top = 8))

        val head = Ui.text(a, 16f, Ui.TEXT, true, "")
        // 관리자(나)에게만: 물때 새로고침 (폰·서버에 저장된 물때 무시하고 국립해양조사원에서 바로 다시 받아 서버도 덮어씀)
        var reloadTide: () -> Unit = {}
        if (Cloud.isAdmin) {
            val hr = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            hr.addView(head, LinearLayout.LayoutParams(0, WRAP, 1f))
            hr.addView(Ui.text(a, 12f, Color.parseColor("#F2C14E"), true, "↻ 물때 새로고침").apply {
                gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0)
                background = Ui.bg(a, Color.parseColor("#2A2310"), 99, Color.parseColor("#F2C14E"))
                setOnClickListener {
                    android.widget.Toast.makeText(a, "물때를 지금 다시 받는 중… (서버에도 저장)", android.widget.Toast.LENGTH_SHORT).show()
                    Tide.forceUntil = System.currentTimeMillis() + 20_000L
                    reloadTide()
                }
            }, LinearLayout.LayoutParams(WRAP, dp(32)).apply { leftMargin = dp(6) })
            col.addView(hr, Ui.lp(a, MATCH, WRAP, top = 10))
        } else col.addView(head, Ui.lp(a, MATCH, WRAP, top = 10))
        val graph = TideView(a)

        col.addView(TideLayers.view(a) { graph.invalidate() }, Ui.lp(a, MATCH, WRAP, top = 8))
        col.addView(graph, Ui.lp(a, MATCH, dp(306), top = 8))
        // 물세기: 그날 % + 시간별 물흐름 세기
        val strength = Ui.text(a, 15f, Ui.TEXT, true, "")
        col.addView(strength, Ui.lp(a, MATCH, WRAP, top = 10))
        val flow = FlowBarView(a)
        col.addView(flow, Ui.lp(a, MATCH, dp(70), top = 4))
        val flowNote = Ui.text(a, 11f, Ui.SUB, false, "")
        col.addView(flowNote, Ui.lp(a, MATCH, WRAP, top = 2))
        val info = Ui.text(a, 13f, Ui.SUB, false, "")
        col.addView(info, Ui.lp(a, MATCH, WRAP, top = 8))
        // 실측 조류 (가까운 관측소 지금 유향·유속)
        val curView = Ui.text(a, 12.5f, Ui.SUB, false, "🌊 실측 조류 불러오는 중…").apply {
            setPadding(dp(12), dp(8), dp(12), dp(8)); background = Ui.bg(a, Color.parseColor("#0F2A33"), 12, Color.parseColor("#1F4E5C"))
        }
        col.addView(curView, Ui.lp(a, MATCH, WRAP, top = 8))
        Weather.current(a, lat, lon) { c, note ->
            if (c == null) { curView.text = "🌊 실측 조류: " + note.ifBlank { "값이 없어요" }; return@current }
            val lvl = Weather.currentLevel(c.speed)
            val col0 = when (lvl) { "셈" -> Color.parseColor("#FF8A65"); "보통" -> Color.parseColor("#F2C14E"); else -> Color.parseColor("#7FE0D0") }
            val sb = android.text.SpannableStringBuilder("🌊 실측 조류 (지금)  ")
            val st = sb.length
            val arrow = c.dir?.let { d -> arrayOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")[(((d + 22.5) % 360) / 45).toInt()] } ?: ""
            sb.append((c.dir?.let { "${Weather.dir16(it)} $arrow  " } ?: "") +
                String.format(Locale.KOREA, "%.1fcm/s · %.1f노트 · %s", c.speed, Weather.cmsKnots(c.speed), lvl))
            sb.setSpan(android.text.style.ForegroundColorSpan(col0), st, sb.length, 0)
            sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), st, sb.length, 0)
            val hm = Regex("\\d{1,2}:\\d{2}").find(c.at)?.value ?: c.at
            sb.append("\n${c.station} 관측소 ${hm} · ${String.format(Locale.KOREA, "%.0f", c.km)}km · 유향 = 물이 흘러가는 방향")
            curView.text = sb
        }

        // ---- 바람 · 파도 · 수온 ----
        // 제목 + (관리자·권한 받은 사람만) 새로고침: 기억해 둔 값 무시하고 지금 바로 다시 받기
        var reloadWx: () -> Unit = {}
        val wxHead = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val wxTitle = Ui.text(a, 15f, Ui.TEXT, true, "날씨 · 바람 · 파도 · 수온")
        wxHead.addView(wxTitle, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (Cloud.can("wx")) wxHead.addView(Ui.text(a, 12f, Color.parseColor("#F2C14E"), true, "↻ 새로고침").apply {
            gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0)
            background = Ui.bg(a, Color.parseColor("#2A2310"), 99, Color.parseColor("#F2C14E"))
            setOnClickListener {
                android.widget.Toast.makeText(a, "날씨를 지금 다시 받는 중…", android.widget.Toast.LENGTH_SHORT).show()
                reloadWx()
            }
        }, LinearLayout.LayoutParams(WRAP, dp(32)))
        col.addView(wxHead, Ui.lp(a, MATCH, WRAP, top = 18))
        // ☀ 자외선지수 (v3.8 · 기상청 생활기상지수 · 시·도 단위 · 받을 수 있는 날만)
        val uvLine = Ui.text(a, 13f, Ui.TEXT, true, "").apply { visibility = View.GONE }
        col.addView(uvLine, Ui.lp(a, MATCH, WRAP, top = 4))
        // v3.9.4: ⚠ 특보 · 🌙 달 · 🌫 안개 · 🦶 갯벌체험지수 · 🎣 바다낚시지수
        val extraBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(extraBox, Ui.lp(a, MATCH, WRAP, top = 2))
        // [오늘 시간별] [주간 (7일)] 탭
        val tabRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(tabRow, Ui.lp(a, MATCH, WRAP, top = 8))
        // v5.0 (230): 바람 기준 [육상 | 해상] (기본 해상 = 가까운 바다 칸)
        var onBasis: () -> Unit = {}
        val basisRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        col.addView(basisRow, Ui.lp(a, MATCH, WRAP, top = 8))
        fun paintBasis() {
            basisRow.removeAllViews()
            basisRow.addView(Ui.text(a, 12.5f, Ui.SUB, true, "바람 기준"), LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(10) })
            listOf(false to "육상", true to "해상").forEach { (sea, label) ->
                val on = Store.windSea == sea
                basisRow.addView(Ui.text(a, 12.5f, if (on) Color.parseColor("#06303A") else Ui.TEXT, true, label).apply {
                    gravity = Gravity.CENTER
                    background = if (on) Ui.bg(a, Ui.BLUE, 99) else Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE)
                    setOnClickListener {
                        if (Store.windSea == sea) return@setOnClickListener
                        Store.windSea = sea; paintBasis(); onBasis()
                        android.widget.Toast.makeText(a, if (sea) "해상 바람: 가까운 바다 위 값으로 보여요" else "육상 바람: 내 위치 칸 값으로 보여요", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }, LinearLayout.LayoutParams(dp(64), dp(30)).apply { rightMargin = dp(6) })
            }
            basisRow.addView(Ui.text(a, 10.5f, Ui.SUB, false, if (Store.windSea) "바다 위 바람 (낚시·해루질)" else "기준 위치 칸 바람").apply {
                maxLines = 2
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(4) })
            // v5.1: 🌬 윈디 지도 (관리자 테스트)
            if (Cloud.isAdmin) basisRow.addView(Ui.text(a, 11.5f, Color.parseColor("#F2C14E"), true, "🌬 윈디\n테스트").apply {
                gravity = Gravity.CENTER
                background = Ui.bg(a, Color.parseColor("#2A2310"), 12, Color.parseColor("#F2C14E"))
                setOnClickListener {
                    val sea = if (Store.windSea) Weather.seaPoint(lat, lon) else null
                    Windy.show(a, sea?.lat ?: lat, sea?.lon ?: lon, Spot.current?.name ?: Tide.stationFor(a, lat, lon)?.name ?: "")
                }
            }, LinearLayout.LayoutParams(dp(64), dp(38)).apply { leftMargin = dp(4) })
        }
        paintBasis()
        val hourBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val weekBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        col.addView(hourBox, Ui.lp(a, MATCH, WRAP))
        col.addView(weekBox, Ui.lp(a, MATCH, WRAP))
        val wxSummary = Ui.text(a, 14f, Ui.TEXT, false, "")
        hourBox.addView(wxSummary, Ui.lp(a, MATCH, WRAP, top = 8))
        val wxGrid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        hourBox.addView(wxGrid, Ui.lp(a, MATCH, WRAP, top = 6))
        val wxNote = Ui.text(a, 11f, Ui.SUB, false, "")
        hourBox.addView(wxNote, Ui.lp(a, MATCH, WRAP, top = 4))
        var weekMode = false
        var todayMeasured: Weather.Measured? = null
        /** 오늘 (실측 − 예상) 수온 차이: 주간 예상 수온 보정용 */
        var todaySstOff: Double? = null

        val src = Ui.text(a, 11f, Ui.SUB, false, "")
        col.addView(src, Ui.lp(a, MATCH, WRAP, top = 12))

        // 제목: 어느 지역 물때인지 (관측소 이름을 눌러도 바꾸기)
        val titleBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(18), dp(22), dp(4)) }
        val titleView = Ui.text(a, 19f, Ui.TEXT, true, "").apply { text = titleText(a, lat, lon) }
        val stationBtn = Ui.text(a, 12f, Ui.BLUE, true, stationSub(a, lat, lon))
        titleBox.addView(titleView)
        titleBox.addView(stationBtn, Ui.lp(a, MATCH, WRAP, top = 3))
        titleBox.isClickable = true

        val dlg = AlertDialog.Builder(a, R.style.FlAlertRight)
            .setCustomTitle(titleBox)
            .setView(ScrollView(a).apply { addView(col) })
            .setPositiveButton("닫기", null)
            .show()
        // 상세 페이지처럼 화면을 거의 꽉 채우게
        dlg.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            (a.resources.displayMetrics.heightPixels * 0.92).toInt())
        // 1분마다 다시 그림 ('지금' 선·시각)
        val tick = object : Runnable { override fun run() { if (dlg.isShowing) { graph.invalidate(); graph.postDelayed(this, 60_000L) } } }
        graph.postDelayed(tick, 60_000L)

        fun renderWeather(w: Weather.Day) {
            val sstOff = Weather.sstOffset(w)
            if (w.dayStart == today && sstOff != null) todaySstOff = sstOff
            if (w.dayStart == today && w.measured != null) todayMeasured = w.measured
            wxGrid.removeAllViews()
            val hrs = w.hours
            if (hrs.isEmpty()) {
                wxSummary.text = w.windNote.ifBlank { w.seaNote }.ifBlank { "바람·파도 정보가 없어요" }
            } else {
                val parts = mutableListOf<CharSequence>()
                hrs.filter { it.wind != null }.maxByOrNull { it.wind!! }?.let {
                    // v5.0: 바람 최대도 색 (초록 · 노랑 · 빨강)
                    parts.add(android.text.SpannableString(String.format(Locale.KOREA, "바람 최대 %.1fm/s (%s시)", it.wind, hFmt.format(Date(it.time)).take(2))).apply {
                        setSpan(android.text.style.ForegroundColorSpan(Weather.windColor(it.wind)), 0, length, 0)
                        setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, length, 0)
                    })
                }
                hrs.mapNotNull { it.temp }.takeIf { it.isNotEmpty() }?.let { parts.add(0, String.format(Locale.KOREA, "기온 %.0f~%.0f℃", it.min(), it.max())) }
                hrs.mapNotNull { it.pop }.maxOrNull()?.takeIf { it > 0 }?.let { parts.add("비 확률 최대 $it%") }
                hrs.mapNotNull { it.wave }.maxOrNull()?.let { parts.add(String.format(Locale.KOREA, "파고 최대 %.1fm", it)) }
                hrs.mapNotNull { it.sst }.takeIf { it.isNotEmpty() }?.let { parts.add(String.format(Locale.KOREA, "수온 약 %.0f℃ (예측)", it.average())) }
                wxSummary.text = android.text.SpannableStringBuilder().apply {
                    parts.forEachIndexed { i, cs -> if (i > 0) append(" · "); append(cs) }
                }
                // 실측 수온 (가장 최근) 칩
                w.measured?.let { m ->
                    wxGrid.addView(Ui.text(a, 12f, Color.parseColor("#7FE0D0"), true,
                        String.format(Locale.KOREA, "🌡 실측 수온 %.1f℃ · %s 관측소 %s", m.temp, m.station, m.at)).apply {
                        setPadding(dp(10), dp(4), dp(10), dp(4))
                        background = Ui.bg(a, Color.parseColor("#12332F"), 99, Color.parseColor("#7FE0D0"))
                    }, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(8) })
                }
                // 세로 목록: 3시간마다 한 줄 (시각 · 하늘 · 기온 · 바람 · 파고 · 수온 · 비)
                val weights = floatArrayOf(0.8f, 0.75f, 0.8f, 1.9f, 1.15f, 1.1f, 0.7f)
                fun cellRow(views: List<View>, bg: android.graphics.drawable.Drawable?, padV: Int): LinearLayout =
                    LinearLayout(a).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(8), dp(padV), dp(6), dp(padV))
                        background = bg
                        views.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, WRAP, weights[i])) }
                    }
                wxGrid.addView(cellRow(listOf("시각", "하늘", "기온", "바람", "파도", "수온", "비").map { Ui.text(a, 10.5f, Ui.SUB, false, it) }, null, 2))
                val now = System.currentTimeMillis()
                for (h in 0 until 24 step 3) {
                    val x = w.at(h)
                    val rowStart = w.dayStart + h * 3_600_000L
                    val isNow = now >= rowStart && now < rowStart + 3 * 3_600_000L
                    // 시각
                    val isPast = rowStart + 3 * 3_600_000L <= now
                    val tcell = Ui.text(a, 13f, Ui.TEXT, true, "${h}시").apply {
                        if (isNow) append("\n지금")
                        // 지나간 시간: 예보가 아니라 그때 값(기록)
                        else if (isPast) append(android.text.SpannableStringBuilder("\n기록").apply {
                            setSpan(android.text.style.AbsoluteSizeSpan(9, true), 0, length, 0)
                            setSpan(android.text.style.ForegroundColorSpan(Ui.SUB), 0, length, 0)
                        })
                    }
                    // 하늘: 그림 뒤 색 동그라미 (☀ 노랑 · ⛅ 그대로 · ☁ 파랑 · 🌧 진한 파랑)
                    val icon = x?.skyIcon().orEmpty()
                    val skyBg = Weather.iconBg(icon)
                    val skyBox = android.widget.FrameLayout(a)
                    skyBox.addView(Ui.text(a, 14f, Ui.TEXT, false, icon.ifBlank { "·" }).apply {
                        gravity = Gravity.CENTER
                        background = if (skyBg == Color.TRANSPARENT) null else Ui.bg(a, skyBg, 99)
                    }, android.widget.FrameLayout.LayoutParams(dp(26), dp(26), Gravity.START or Gravity.CENTER_VERTICAL))
                    // 기온
                    val tmp = Ui.text(a, 13f, Color.parseColor("#F2C14E"), true, x?.temp?.let { String.format(Locale.KOREA, "%.0f°", it) } ?: "-")
                    // 바람: 화살표 + 풍속 + 방향
                    // 바람 ('물때와 날씨'처럼): 화살표 + 풍속 m/s (10노트↑면 노트) / 16방위 · 최대(돌풍)
                    val ws = x?.wind
                    // v5.0: 초록 괜찮음(5 아래) · 노랑 주의(5~9) · 빨강 셈(9 이상)
                    val lvColor = Weather.windColor(ws)
                    val wind = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                    val wTop = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    wTop.addView(Ui.text(a, 15f, lvColor, true, if (x?.dir != null) "↑" else "·").apply {
                        gravity = Gravity.CENTER
                        x?.dir?.let { rotation = (it.toFloat() + 180f) % 360f }
                    }, LinearLayout.LayoutParams(dp(16), WRAP))
                    wTop.addView(Ui.text(a, 13f, lvColor, true, "").apply {
                        text = android.text.SpannableStringBuilder(ws?.let { String.format(Locale.KOREA, "%.1f", it) } ?: "-").apply {
                            if (ws != null) { val s0 = length; append("m/s")
                                setSpan(android.text.style.AbsoluteSizeSpan(9, true), s0, length, 0) }
                            if (ws != null && ws >= Weather.KT10) { val s1 = length; append(String.format(Locale.KOREA, " %.0f노트", Weather.knots(ws)))
                                setSpan(android.text.style.AbsoluteSizeSpan(9, true), s1, length, 0) }
                        }
                    }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(1) })
                    wind.addView(wTop)
                    val wSub = android.text.SpannableStringBuilder(x?.dir?.let { Weather.dir16(it) } ?: "")
                    x?.gust?.let { g -> if (wSub.isNotEmpty()) wSub.append(" · ")
                        val g0 = wSub.length; wSub.append(String.format(Locale.KOREA, "최대 %.1f", g))
                        if (g >= Weather.WIND_OK) wSub.setSpan(android.text.style.ForegroundColorSpan(Weather.windColor(g)), g0, wSub.length, 0) }
                    if (wSub.isNotBlank()) wind.addView(Ui.text(a, 9.5f, Ui.SUB, false, "").apply { text = wSub
                        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                        setAutoSizeTextTypeUniformWithConfiguration(7, 10, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                    }, LinearLayout.LayoutParams(MATCH, Ui.dp(a, 14)))
                    // 파도: 파향 화살표 + 파고 m / 주기 s
                    val wave = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                    val wvTop = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    if (x?.waveDir != null) wvTop.addView(Ui.text(a, 13f, Ui.BLUE, true, "↑").apply {
                        gravity = Gravity.CENTER; rotation = (x.waveDir.toFloat() + 180f) % 360f
                    }, LinearLayout.LayoutParams(dp(13), WRAP))
                    wvTop.addView(Ui.text(a, 12.5f, Ui.BLUE, true, x?.wave?.let { String.format(Locale.KOREA, "%.1fm", it) } ?: "-"))
                    wave.addView(wvTop)
                    x?.period?.let { wave.addView(Ui.text(a, 9.5f, Ui.SUB, false, String.format(Locale.KOREA, "주기 %.1fs", it))) }
                    // 수온: 지나간 시간은 실측(청록 꽉 찬 표시), 나머지는 예상(회색 테두리)
                    val meas = if (rowStart <= now) w.measured?.series?.get(rowStart) else null
                    // 실측이 없는 시간: 예상에 그날 (실측 − 예상) 차이를 더한 보정값
                    val corr = if (meas == null && x?.sst != null && sstOff != null) x.sst + sstOff else null
                    val seaVal = meas ?: corr ?: x?.sst
                    val sea = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                    sea.addView(Ui.text(a, 12.5f, Color.parseColor("#7FE0D0"), true, seaVal?.let { String.format(Locale.KOREA, "%.1f°", it) } ?: "-"))
                    if (seaVal != null) sea.addView(Ui.text(a, 9.5f, if (meas != null) Color.parseColor("#04342C") else if (corr != null) Color.parseColor("#7FE0D0") else Ui.SUB, true,
                        if (meas != null) "실측" else "예상").apply {
                        setPadding(dp(5), 0, dp(5), 0)
                        background = if (meas != null) Ui.bg(a, Color.parseColor("#7FE0D0"), 99)
                            else Ui.bg(a, Color.TRANSPARENT, 99, if (corr != null) Color.parseColor("#7FE0D0") else Ui.SUB)
                    }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(1) })
                    // 실측이면 어느 관측소 몇 도인지 작게 (가장 가까운 곳이 끊겨 다른 관측소면 노란 글씨)
                    if (meas != null) w.measured?.stationAt?.get(rowStart)?.let { stn ->
                        val other = stn != w.measured?.home
                        sea.addView(Ui.text(a, 9f, if (other) Color.parseColor("#F2C14E") else Ui.SUB, false,
                            stn.removeSuffix(" 관측소") + String.format(Locale.KOREA, " %.1f°", meas)).apply {
                            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                            setAutoSizeTextTypeUniformWithConfiguration(7, 9, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                        }, LinearLayout.LayoutParams(MATCH, Ui.dp(a, 13)))
                    }
                    // 비 확률
                    val pop = x?.pop
                    val popV = Ui.text(a, 12f, if ((pop ?: 0) >= 50) Ui.BLUE else Ui.SUB, (pop ?: 0) >= 50, pop?.let { "$it%" } ?: "-")
                    val bg = Ui.bg(a, Ui.CARD, 12, if (isNow) Ui.GREEN else 0)
                    wxGrid.addView(cellRow(listOf(tcell, skyBox, tmp, wind, wave, sea, popV), bg, 8), Ui.lp(a, MATCH, WRAP, top = 6))
                }
            }
            val notes = mutableListOf<String>()
            // 파고가 비었거나 먼 바다 값이면 그 이유를 맨 위에
            if (w.seaNote.startsWith("파고") || w.seaNote.startsWith("근처") || w.seaNote.startsWith("이 위치")) notes.add("ⓘ " + w.seaNote)
            if (w.measuredNote.isNotBlank()) notes.add(w.measuredNote)
            if (w.kmaNote.isNotBlank()) notes.add(w.kmaNote)
            if (hrs.isNotEmpty()) {
                if (w.windBasis.isNotBlank()) notes.add("ⓘ " + w.windBasis)
                notes.add("한 줄 = 3시간 · 화살표 = 바람·파도가 가는 쪽 · 풍속 m/s (초록 괜찮음 5 아래 · 노랑 주의 5~9 · 빨강 셈 9 이상) · 최대 = 돌풍 · 초록 테두리 = 지금\n" +
                    "하늘: ☀ 맑음 · ⛅ 구름 조금 · ☁ 흐림 · 🌧 비 · ⚡ 뇌우(낙뢰) · 💨 강풍 10노트↑ · 🌀 돌풍 20노트↑ · 수온: 실측 = 관측소에서 잰 값(아래 작은 글씨 = 관측소, 노란색 = 가장 가까운 곳이 끊겨 다음 관측소 값), 예상 = 그날 실측에 맞춘 예측")
                if (sstOff != null) notes.add(String.format(Locale.KOREA, "수온 예상: 오늘 실측이 먼 바다 예측보다 %+.1f° 라서 실측이 없는 시간은 그만큼 맞춰서 보여요", sstOff))
                if (w.windNote.isNotBlank()) notes.add(w.windNote)
                if (w.seaNote.isNotBlank() && notes.none { it.endsWith(w.seaNote) }) notes.add(w.seaNote)
                notes.add(if (w.fromKma) "기온·하늘·비·바람·파고: 기상청 단기예보 (내 칸이 육지면 가까운 바다 칸) · 낙뢰: 기상청 초단기예보 · 돌풍·파도 주기·파향·수온 예측: Open-Meteo" else "바람·파도·수온 예측: Open-Meteo")
            }
            wxNote.text = notes.joinToString("\n")
            if (w.measured != null) {
                if (wxSummary.text.isNotBlank()) wxSummary.append(" · ")
                wxSummary.append(String.format(Locale.KOREA, "실측 수온 %.1f℃", w.measured.temp))
            }
        }

        fun load() {
            val id = ++loadId
            val noon = cur + 12 * 3_600_000L
            // v3.8: 맨 위 제목과 날씨 칸 제목에도 지금 보는 날짜
            titleView.text = titleText(a, lat, lon, cur)
            uvLine.visibility = View.GONE
            extraBox.removeAllViews()
            fun ex(t: CharSequence, color: Int = Ui.TEXT, bold: Boolean = false) =
                extraBox.addView(Ui.text(a, 12.5f, color, bold, "").apply { text = t }, Ui.lp(a, MATCH, WRAP, top = 3))
            if (isToday(cur)) Warn.forPlace(lat, lon) { w -> if (id == loadId && !w.isNullOrBlank()) ex("⚠ ${Warn.seaArea(lat, lon)} $w (기상청 특보)", Color.parseColor("#FF8A92"), true) }
            ex(Moon.line(cur, lat, lon), Ui.SUB)
            Fog.fetch(lat, lon, cur) { f -> if (id == loadId && f != null) Fog.line(f).takeIf { it.isNotBlank() }?.let { ex(it, if (f.badHours.isEmpty()) Ui.SUB else Color.parseColor("#F2C14E"), f.badHours.isNotEmpty()) } }
            val ahead = ((cur - Sun.dayStart(System.currentTimeMillis())) / 86_400_000L).toInt()
            if (ahead in 0..6) listOf(true, false).forEach { mud ->
                Idx.near(a, mud, cur, lat, lon) { items ->
                    if (id != loadId || items.isNullOrEmpty()) return@near
                    val sb = android.text.SpannableStringBuilder((if (mud) "🦶 갯벌체험지수" else "🎣 바다낚시지수") + " · ${items.first().name}: ")
                    items.take(4).forEachIndexed { i, it2 ->
                        if (i > 0) sb.append(" · ")
                        val s0 = sb.length
                        sb.append(listOf(it2.extra, it2.index).filter { x -> x.isNotBlank() }.joinToString(" "))
                        sb.setSpan(android.text.style.ForegroundColorSpan(Idx.color(it2.index)), s0, sb.length, 0)
                    }
                    ex(sb)
                }
            }
            Uv.fetch(lat, lon) { uv ->
                if (id != loadId || uv == null) return@fetch
                val mx = uv.maxOn(cur) ?: return@fetch
                val sb = android.text.SpannableStringBuilder("☀ 자외선 ")
                fun put(v: Int, label: String) {
                    val s0 = sb.length
                    sb.append("$label $v ${Uv.level(v)}")
                    sb.setSpan(android.text.style.ForegroundColorSpan(Uv.color(v)), s0, sb.length, 0)
                }
                put(mx, "최고")
                if (isToday(cur)) uv.at(System.currentTimeMillis())?.let { sb.append(" · "); put(it, "지금") }
                val s1 = sb.length
                sb.append("  (${Uv.sidoName(lat, lon)} · 기상청)")
                sb.setSpan(android.text.style.ForegroundColorSpan(Ui.SUB), s1, sb.length, 0)
                sb.setSpan(android.text.style.AbsoluteSizeSpan(11, true), s1, sb.length, 0)
                uvLine.text = sb; uvLine.visibility = View.VISIBLE
            }
            wxTitle.text = android.text.SpannableStringBuilder().apply {
                val s0 = length
                append(dayTag(cur) + " ")
                setSpan(android.text.style.ForegroundColorSpan(if (isToday(cur)) Ui.GREEN else Color.parseColor("#F2C14E")), s0, length, 0)
                append("날씨 · 바람 · 파도 · 수온")
            }
            dateText.text = android.text.SpannableStringBuilder(titleFmt.format(Date(cur)) + " · ").apply {
                val st = length
                append(dayOffsetLabel(cur))
                setSpan(android.text.style.ForegroundColorSpan(offsetColor(cur)), st, length, 0)
                append(" ▾")
            }
            head.text = android.text.SpannableStringBuilder(dayTitle(Tide.mulName(noon).substringBefore(" ("), noon)).apply {
                val st = length
                append("  (" + Tide.mulName(noon).substringAfter(" (", ""))
                if (length - st <= 3) delete(st, length)
                else setSpan(android.text.style.AbsoluteSizeSpan(12, true), st, length, 0)
            }
            info.text = "물때 불러오는 중…"
            strength.text = "물세기 계산 중…"
            flowNote.text = ""; src.text = ""
            wxSummary.text = "바람·파도·수온 불러오는 중…"; wxGrid.removeAllViews(); wxNote.text = ""
            // 그날 출조한 기록이면 그래프에 조과 점
            val t = trip?.takeIf { dayStart(it.start) == cur }
            Tide.fetch(a, lat, lon, noon) { day, err ->
                if (!dlg.isShowing || id != loadId) return@fetch
                if (day == null) { info.text = err ?: "불러오기 실패"; strength.text = ""; return@fetch }
                graph.set(day, t, lat, lon)
                // 오늘: '지금'을 가운데 고정 (어제·내일 물때를 이어 붙임)
                if (cur == today) {
                    var prev: Tide.Day? = null; var next: Tide.Day? = null; var got = 0
                    fun done() { if (++got == 2 && dlg.isShowing && id == loadId) graph.setCentered(Tide.align(day, prev), day, Tide.align(day, next), t, lat, lon) }
                    Tide.fetch(a, lat, lon, noon - 86_400_000L) { d2, _ -> prev = d2; done() }
                    Tide.fetch(a, lat, lon, noon + 86_400_000L) { d2, _ -> next = d2; done() }
                }
                info.text = describe(day, t)
                src.text = day.source + "\n물때 단계: 실제 그믐 시각으로 계산 (서해 7물때식)"
                Tide.strength(a, day) { st ->
                    if (!dlg.isShowing || id != loadId) return@strength
                    head.text = android.text.SpannableStringBuilder(dayTitle(Tide.mulName(noon).substringBefore(" ("), noon, st.pct)).apply {
                        val s0 = length
                        append("  (" + Tide.mulName(noon).substringAfter(" (", ""))
                        if (length - s0 <= 3) delete(s0, length)
                        else setSpan(android.text.style.AbsoluteSizeSpan(11, true), s0, length, 0)
                    }
                    strength.text = strengthLine(day, st)
                    flow.set(day, st)
                    flowNote.text = "막대 = 시간별 물흐름 세기 (물이 빨리 들고 나는 때일수록 높고 주황)" +
                        (if (cur == today) " · 초록 테두리 = 지금" else "") +
                        (if (st.estimated) "\n물세기는 물때(달 나이)로 짐작한 값이에요" else if (st.annual) "\n물세기 = 그날 조차 ÷ 이 관측소 1년 중 가장 큰 사리 때 조차 (100%)" else "\n물세기 = 그날 조차 ÷ 가까운 사리(7물) 때 조차 (1년 기준을 아직 못 받음)")
                }
            }
            Weather.fetch(a, lat, lon, cur) { w -> if (dlg.isShowing && id == loadId) renderWeather(w) }
        }

        // ---- 주간 (7일): 날짜 · 하늘 · 기온(+수온) · 비 · 바람 · 파고 · 물때 · 물세기 ----
        lateinit var paintTabs: () -> Unit
        // v4.9 (222): 주간 = 처음 7일 → '▼ 14일까지' (날씨는 참고) → '▼ 21일까지' (물때만)
        var weekShown = 7
        var week21 = false
        var weekList: List<Weather.WeekDay>? = null
        lateinit var renderWeekRef: (List<Weather.WeekDay>?) -> Unit
        fun sectionLine(t: String) = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(View(a).apply { setBackgroundColor(Ui.LINE) }, LinearLayout.LayoutParams(0, dp(1), 1f))
            addView(Ui.text(a, 11f, Ui.SUB, true, t).apply { setPadding(dp(8), 0, dp(8), 0) })
            addView(View(a).apply { setBackgroundColor(Ui.LINE) }, LinearLayout.LayoutParams(0, dp(1), 1f))
        }
        fun moreBtn(t: String, click: () -> Unit) = Ui.text(a, 14f, Ui.BLUE, true, t).apply {
            gravity = Gravity.CENTER; minHeight = dp(44)
            background = Ui.bg(a, Color.TRANSPARENT, 12, Color.parseColor("#2B5468"))
            setOnClickListener { click() }
        }
        /** 15~21일: 날씨 예보가 없어 물때만 자세히 (물때 · 물세기 · 가장 낮은 간조 시각·높이 · 만조 · 일출 · ⭐ 해루질 좋은 날) */
        fun renderTideOnly() {
            weekBox.addView(sectionLine("15~21일 · 물때만 (날씨 예보 없음)"), Ui.lp(a, MATCH, WRAP, top = 12))
            val dfmt = SimpleDateFormat("M/d", Locale.KOREA); val wfmt = SimpleDateFormat("E", Locale.KOREA); val hm = SimpleDateFormat("HH:mm", Locale.KOREA)
            for (k in 14 until 21) {
                val ds = dayStart(today + k * 86_400_000L + 3_600_000L)
                val noon = ds + 12 * 3_600_000L
                val sun = Calendar.getInstance().apply { timeInMillis = ds }.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
                val row = LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(8), dp(10), dp(8)); background = Ui.bg(a, Ui.CARD, 12)
                    isClickable = true
                    setOnClickListener { cur = ds; weekMode = false; paintTabs(); load() }
                }
                row.addView(Ui.text(a, 13f, Ui.TEXT, true, dfmt.format(Date(ds))).apply {
                    append(android.text.SpannableStringBuilder("\n" + wfmt.format(Date(ds))).apply {
                        setSpan(android.text.style.AbsoluteSizeSpan(10, true), 0, length, 0)
                        setSpan(android.text.style.ForegroundColorSpan(if (sun) Color.parseColor("#F07AA0") else Ui.SUB), 0, length, 0)
                    })
                }, LinearLayout.LayoutParams(dp(52), WRAP))
                val est = Tide.estimatePct(noon)
                val info = Ui.text(a, 12f, Color.parseColor("#C9D6DD"), false, "")
                val star = Ui.text(a, 12f, Color.parseColor("#F2C14E"), true, "").apply { gravity = Gravity.END }
                fun paint(pct: Int, low: Tide.Extreme?, high: Tide.Extreme?) {
                    val sb = android.text.SpannableStringBuilder()
                    val m0 = sb.length; sb.append(Tide.mulName(noon).substringBefore(" ("))
                    sb.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#FFD166")), m0, sb.length, 0)
                    sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), m0, sb.length, 0)
                    sb.append(" · 물세기 ")
                    val p0 = sb.length; sb.append("$pct%")
                    sb.setSpan(android.text.style.ForegroundColorSpan(if (pct >= 80) Ui.ORANGE else if (pct >= 55) Color.parseColor("#F2C14E") else Ui.SUB), p0, sb.length, 0)
                    sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), p0, sb.length, 0)
                    if (low != null) {
                        sb.append("\n간조 ")
                        val l0 = sb.length; sb.append(hm.format(Date(low.time)))
                        sb.setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), l0, sb.length, 0)
                        sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), l0, sb.length, 0)
                        sb.append(String.format(Locale.KOREA, " (%.0fcm)", low.value * 100))
                    }
                    if (high != null) sb.append(" · 만조 ").append(hm.format(Date(high.time)))
                    Sun.times(noon, lat, lon)?.let { st -> sb.append("\n일출 ").append(hm.format(Date(st.rise))) }
                    info.text = sb
                    val ok = low != null && low.value * 100.0 <= TideCalendar.haeruCm(a).toDouble()
                    star.text = if (ok && pct >= 80) "⭐⭐" else if (ok) "⭐ 해루질" else ""
                }
                paint(est, null, null)
                Tide.fetch(a, lat, lon, noon) { day, _ ->
                    if (day == null || !dlg.isShowing) return@fetch
                    val ex = Tide.extremes(day)
                    val low = ex.filter { !it.high }.minByOrNull { it.value }
                    val high = ex.firstOrNull { it.high }
                    paint(est, low, high)
                    Tide.strength(a, day) { st -> if (dlg.isShowing) paint(st.pct, low, high) }
                }
                row.addView(info, LinearLayout.LayoutParams(0, WRAP, 1f))
                row.addView(star, LinearLayout.LayoutParams(WRAP, WRAP))
                weekBox.addView(row, Ui.lp(a, MATCH, WRAP, top = 6))
            }
        }
        fun renderWeek(list: List<Weather.WeekDay>?) {
            weekList = list
            weekBox.removeAllViews()
            if (list.isNullOrEmpty()) { weekBox.addView(Ui.text(a, 13f, Ui.SUB, false, "주간 날씨를 못 받았어요 (인터넷 확인)"), Ui.lp(a, MATCH, WRAP, top = 8)); return }
            val weights = floatArrayOf(1.05f, 0.75f, 1.35f, 0.75f, 0.65f, 0.7f, 0.9f, 0.85f)
            fun cellRow(views: List<View>, bg: android.graphics.drawable.Drawable?, padV: Int) = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(padV), dp(6), dp(padV)); background = bg
                views.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, WRAP, weights[i])) }
            }
            weekBox.addView(cellRow(listOf("날짜", "하늘", "기온", "비", "바람", "파고", "물때", "물세기").map { Ui.text(a, 10f, Ui.SUB, false, it) }, null, 2),
                Ui.lp(a, MATCH, WRAP, top = 8))
            val dfmt = SimpleDateFormat("M/d", Locale.KOREA); val wfmt = SimpleDateFormat("E", Locale.KOREA)
            fun pctColor(p: Int) = when { p >= 80 -> Ui.ORANGE; p >= 55 -> Color.parseColor("#F2C14E"); else -> Ui.SUB }
            list.take(weekShown).forEachIndexed { idx, wd ->
                if (idx == 7) weekBox.addView(sectionLine("8~14일 · 날씨는 참고"), Ui.lp(a, MATCH, WRAP, top = 12))
                val ds = dayStart(wd.dayStart)
                val noon = ds + 12 * 3_600_000L
                val off = Math.round((ds - today) / 86_400_000.0).toInt()
                val dayName = when (off) { 0 -> "오늘"; 1 -> "내일"; else -> dfmt.format(Date(ds)) }
                val sub = if (off <= 1) dfmt.format(Date(ds)) + " " + wfmt.format(Date(ds)) else wfmt.format(Date(ds))
                val sun = Calendar.getInstance().apply { timeInMillis = ds }.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
                val dcell = Ui.text(a, 13f, Ui.TEXT, true, dayName).apply {
                    append(android.text.SpannableStringBuilder("\n" + sub).apply {
                        setSpan(android.text.style.AbsoluteSizeSpan(10, true), 0, length, 0)
                        setSpan(android.text.style.ForegroundColorSpan(if (sun) Color.parseColor("#F07AA0") else Ui.SUB), 0, length, 0)
                    })
                    // 어디 예보인지 (기상청이면 초록 작은 글씨)
                    if (wd.src.isNotBlank()) append(android.text.SpannableStringBuilder("\n기상청").apply {
                        setSpan(android.text.style.AbsoluteSizeSpan(9, true), 0, length, 0)
                        setSpan(android.text.style.ForegroundColorSpan(Ui.GREEN), 0, length, 0)
                    })
                }
                val icon = wd.skyIcon()
                val skyBg = Weather.iconBg(icon)
                val skyBox = android.widget.FrameLayout(a)
                skyBox.addView(Ui.text(a, 13f, Ui.TEXT, false, icon.ifBlank { "·" }).apply {
                    gravity = Gravity.CENTER
                    background = if (skyBg == Color.TRANSPARENT) null else Ui.bg(a, skyBg, 99)
                }, android.widget.FrameLayout.LayoutParams(dp(24), dp(24), Gravity.START or Gravity.CENTER_VERTICAL))
                // 기온 + 아래 수온 (오늘은 실측이 있으면 실측)
                val temp = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                temp.addView(Ui.text(a, 12.5f, Ui.TEXT, true, "").apply {
                    text = android.text.SpannableStringBuilder().apply {
                        val s0 = length; append(wd.tmin?.let { String.format(Locale.KOREA, "%.0f°", it) } ?: "-")
                        setSpan(android.text.style.ForegroundColorSpan(Ui.BLUE), s0, length, 0)
                        append("/")
                        val s1 = length; append(wd.tmax?.let { String.format(Locale.KOREA, "%.0f°", it) } ?: "-")
                        setSpan(android.text.style.ForegroundColorSpan(Ui.ORANGE), s1, length, 0)
                    }
                })
                val meas = if (off == 0) todayMeasured?.temp else null
                val wcorr = if (meas == null && wd.sst != null && todaySstOff != null) wd.sst + todaySstOff!! else null
                val seaV = meas ?: wcorr ?: wd.sst
                if (seaV != null) {
                    val sr = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    sr.addView(Ui.text(a, 10.5f, Color.parseColor("#7FE0D0"), true, String.format(Locale.KOREA, "수온 %.0f°", seaV)))
                    sr.addView(Ui.text(a, 8.5f, if (meas != null) Color.parseColor("#04342C") else if (wcorr != null) Color.parseColor("#7FE0D0") else Ui.SUB, true,
                        if (meas != null) "실측" else "예상").apply {
                        setPadding(dp(3), 0, dp(3), 0)
                        background = if (meas != null) Ui.bg(a, Color.parseColor("#7FE0D0"), 99)
                            else Ui.bg(a, Color.TRANSPARENT, 99, if (wcorr != null) Color.parseColor("#7FE0D0") else Ui.SUB)
                    }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(2) })
                    temp.addView(sr)
                }
                val popV = Ui.text(a, 12f, if ((wd.pop ?: 0) >= 50) Ui.BLUE else Ui.SUB, (wd.pop ?: 0) >= 50, wd.pop?.let { "$it%" } ?: "-")
                val windV = Ui.text(a, 12.5f, Weather.windColor(wd.wind), true,
                    wd.wind?.let { String.format(Locale.KOREA, "%.1f", it) } ?: "-")
                val waveV = Ui.text(a, 12f, Ui.BLUE, false, wd.wave?.let { String.format(Locale.KOREA, "%.1f", it) } ?: "-").apply {
                    // 기상청 해상예보 범위가 있으면 아래에 작게 (예: 1.0~2.0)
                    wd.waveRange?.let { r -> append(android.text.SpannableStringBuilder("\n" + r).apply {
                        setSpan(android.text.style.AbsoluteSizeSpan(8, true), 0, length, 0)
                        setSpan(android.text.style.ForegroundColorSpan(Ui.SUB), 0, length, 0)
                    }) }
                }
                // 물때 · 물세기 (먼저 달 나이로 짐작, 물때 받아오면 정확한 값으로)
                val est = Tide.estimatePct(noon)
                val mulV = Ui.text(a, 10.5f, Ui.TEXT, true, Tide.mulName(noon).substringBefore(" (")).apply {
                    gravity = Gravity.CENTER; maxLines = 1; setPadding(dp(2), dp(2), dp(2), dp(2))
                }
                val pctV = Ui.text(a, 12.5f, pctColor(est), true, "$est%").apply { gravity = Gravity.END }
                fun paintPct(p: Int) {
                    pctV.text = "$p%"; pctV.setTextColor(pctColor(p))
                    if (p >= 80) { mulV.setTextColor(Color.parseColor("#3A1A04")); mulV.background = Ui.bg(a, Ui.ORANGE, 99) }
                    else { mulV.setTextColor(Ui.TEXT); mulV.background = Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE) }
                }
                paintPct(est)
                Tide.fetch(a, lat, lon, noon) { day, _ ->
                    if (day != null && dlg.isShowing) Tide.strength(a, day) { st -> if (dlg.isShowing) paintPct(st.pct) }
                }
                // 8~14일은 날씨가 덜 정확해서 흐리게 (물때 · 물세기는 진하게)
                if (idx >= 7) listOf(skyBox, temp, popV, windV, waveV).forEach { it.alpha = 0.45f }
                val row = cellRow(listOf(dcell, skyBox, temp, popV, windV, waveV, mulV, pctV),
                    Ui.bg(a, Ui.CARD, 12, if (ds == cur) Ui.BLUE else 0), 8).apply {
                    isClickable = true
                    setOnClickListener { cur = ds; weekMode = false; paintTabs(); load() }
                }
                weekBox.addView(row, Ui.lp(a, MATCH, WRAP, top = 6))
            }
            when {
                weekShown < 14 && list.size > 7 -> weekBox.addView(moreBtn("▼ 14일까지 더 보기") { weekShown = 14; renderWeekRef(weekList) }, Ui.lp(a, MATCH, WRAP, top = 8))
                !week21 -> weekBox.addView(moreBtn("▼ 21일까지 더 보기") { week21 = true; renderWeekRef(weekList) }, Ui.lp(a, MATCH, WRAP, top = 8))
                else -> renderTideOnly()
            }
            weekBox.addView(Ui.text(a, 11f, Ui.SUB, false,
                "줄을 누르면 그날 물때·시간별 날씨로 이동 · 바람 = 최대 풍속 m/s (초록 5 아래 · 노랑 5~9 · 빨강 9 이상) · 파고 = 최대 m\n" +
                    "물세기: 주황 80% 이상(물 많이 빠짐) · 노랑 55~80% · 회색 55% 아래 · 수온 예상: Open-Meteo"),
                Ui.lp(a, MATCH, WRAP, top = 4))
            if (Weather.weekNote.isNotBlank()) weekBox.addView(Ui.text(a, 11f, Ui.SUB, false, Weather.weekNote).apply {
                setLineSpacing(0f, 1.2f)
            }, Ui.lp(a, MATCH, WRAP, top = 4))
        }
        fun tab(label: String, on: Boolean, click: () -> Unit) = Ui.text(a, 13f, if (on) Color.parseColor("#06303A") else Ui.TEXT, true, label).apply {
            gravity = Gravity.CENTER
            background = if (on) Ui.bg(a, Ui.BLUE, 99) else Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE)
            setOnClickListener { click() }
        }
        paintTabs = {
            tabRow.removeAllViews()
            tabRow.addView(tab("시간별", !weekMode) { weekMode = false; paintTabs() }, LinearLayout.LayoutParams(0, dp(38), 1f))
            tabRow.addView(tab("주간", weekMode) {
                weekMode = true; weekShown = 7; week21 = false; paintTabs()
                weekBox.removeAllViews(); weekBox.addView(Ui.text(a, 13f, Ui.SUB, false, "주간 날씨 불러오는 중…"), Ui.lp(a, MATCH, WRAP, top = 8))
                Weather.fetchWeek(lat, lon) { l -> if (dlg.isShowing && weekMode) renderWeek(l) }
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { leftMargin = dp(8) })
            hourBox.visibility = if (weekMode) View.GONE else View.VISIBLE
            weekBox.visibility = if (weekMode) View.VISIBLE else View.GONE
        }
        renderWeekRef = { l -> renderWeek(l) }
        onBasis = {
            load()
            if (weekMode) {
                weekBox.removeAllViews(); weekBox.addView(Ui.text(a, 13f, Ui.SUB, false, "주간 날씨 불러오는 중…"), Ui.lp(a, MATCH, WRAP, top = 8))
                Weather.fetchWeek(lat, lon) { l -> if (dlg.isShowing && weekMode) renderWeek(l) }
            }
        }
        paintTabs()
        reloadWx = {
            Weather.clearCache()
            load()
            if (weekMode) Weather.fetchWeek(lat, lon) { l -> if (dlg.isShowing && weekMode) renderWeek(l) }
        }

        // v5.1 (232): 지점·관측소를 바꾸면 기준 위치가 바뀌니 창을 새로 열기
        val pickStation = {
            StationPicker.show(a, lat, lon) {
                onChanged?.invoke()
                dlg.dismiss()
                show(a, gpsLat, gpsLon, cur, trip, onChanged)
            }
        }
        titleBox.setOnClickListener { pickStation() }
        changeBtn.setOnClickListener { pickStation() }
        alarmBtn.setOnClickListener { pickAlarm(a) { alarmBtn.text = alarmLabel(); onChanged?.invoke() } }

        prev.setOnClickListener { cur = dayStart(cur - 12 * 3_600_000L); load() }
        next.setOnClickListener { cur = dayStart(cur + 36 * 3_600_000L); load() }
        todayBtn.setOnClickListener { cur = today; load() }
        dateText.setOnClickListener {
            val c = Calendar.getInstance().apply { timeInMillis = cur }
            DatePickerDialog(a, R.style.FlAlert, { _, y, m, d ->
                cur = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                load()
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }
        reloadTide = { load() }
        load()
    }

    /** "물세기 85% ▰▰▰▰▰▰▰▰▱▱ · 지금 물살 셈 (들물)" */
    fun strengthLine(day: Tide.Day, st: Tide.Strength, time: Long = System.currentTimeMillis()): String {
        val sb = StringBuilder("물세기 ${st.pct}%")
        if (st.estimated) sb.append("(추정)")
        sb.append(" ").append(Tide.strengthBar(st.pct))
        val now = System.currentTimeMillis()
        if (now >= day.dateMillis && now < day.dateMillis + 86_400_000L) {
            val lv = st.levelAt(day, now)
            if (lv >= 0) {
                sb.append(" · 지금 물살 ").append(Tide.levelName(lv))
                Tide.rising(day, now)?.let { sb.append(if (it) " (들물)" else " (날물)") }
            }
        }
        return sb.toString()
    }

    fun describe(day: Tide.Day, trip: Store.Trip?): String {
        val tf = SimpleDateFormat("HH:mm", Locale.KOREA)
        val sb = StringBuilder()
        Tide.extremes(day).forEach { e ->
            sb.append(if (e.high) "만조 " else "간조 ").append(tf.format(Date(e.time)))
                .append(String.format(Locale.KOREA, " (%.0fcm%s)", e.value * 100, if (e.est) " 추정" else "")).append("   ")
        }
        if (trip != null && trip.catches.isNotEmpty()) {
            var up1 = 0; var up2 = 0; var dn1 = 0; var dn2 = 0
            trip.catches.forEach { c ->
                when (Tide.rising(day, c.time)) {
                    true -> if (c.slot == 1) up1++ else up2++
                    false -> if (c.slot == 1) dn1++ else dn2++
                    null -> {}
                }
            }
            sb.append("\n들물(들어오는 물): ${trip.name(1)} $up1 · ${trip.name(2)} $up2")
            sb.append("\n날물(빠지는 물): ${trip.name(1)} $dn1 · ${trip.name(2)} $dn2")
        }
        return sb.toString().trim()
    }
}
