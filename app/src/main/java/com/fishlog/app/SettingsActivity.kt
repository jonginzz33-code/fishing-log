package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP

/** 개인 설정 (⚙): 잠금 화면·AOD 표시, 친구 돌파 알림, 닉네임 */
class SettingsActivity : Activity() {
    /** v3.9.2: 화면 크기 자동 맞춤 (Ui.fit) */
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(Ui.fit(newBase)) }


    private lateinit var box: LinearLayout
    private fun dp(v: Int) = Ui.dp(this, v)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        val col = Ui.column(this)
        val top = Ui.row(this)
        top.addView(Ui.button(this, "← 뒤로") { finish() }.apply { minHeight = dp(44) }, LinearLayout.LayoutParams(WRAP, WRAP))
        top.addView(Ui.text(this, 20f, Ui.TEXT, true, "개인 설정"), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(14) })
        col.addView(top)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(box, Ui.lp(this, MATCH, WRAP, top = 6))
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(col) })
        if (intent.getBooleanExtra("askNick", false)) toast("다른 사람에게 보일 닉네임을 먼저 정해 주세요")
    }

    override fun onResume() { super.onResume(); render() }

    private fun section(t: String) = box.addView(Ui.text(this, 12f, Ui.SUB, false, t), Ui.lp(this, MATCH, WRAP, top = 18))

    /** 켜기/끄기 스위치 줄 */
    private fun switchRow(title: String, sub: String, on: Boolean, toggle: (Boolean) -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Ui.bg(this@SettingsActivity, Ui.CARD, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(Ui.text(this, 15f, Ui.TEXT, true, title))
        t.addView(Ui.text(this, 12f, Ui.SUB, false, sub), Ui.lp(this, MATCH, WRAP, top = 2))
        r.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        val sw = android.widget.Switch(this).apply {
            isChecked = on
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Ui.GREEN, Ui.LINE))
            setOnCheckedChangeListener { _, c -> toggle(c) }
        }
        r.addView(sw, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(10) })
        r.setOnClickListener { sw.isChecked = !sw.isChecked }
        box.addView(r, Ui.lp(this, MATCH, WRAP, top = 6))
    }

    private fun render() {
        box.removeAllViews()
        renderNick()
        section("화면 표시")
        renderScale()
        switchRow("꺼진 화면(AOD)·잠금 화면에 표시", "출조 중 주꾸미 아이콘, 마릿수, +1 버튼을 잠금 해제 없이 보여줘요",
            Store.lockScreenShow) { Store.lockScreenShow = it; if (Store.current != null) RemoteService.start(this) }
        section("알림")
        switchRow("같이 출조 중 친구 돌파 알림", "같이 출조 중인 그룹원이 10·30·50… 마리를 넘으면 알려줘요",
            Store.friendAlerts) { Store.friendAlerts = it }

        section("개인정보 · 위치")
        // 누구에게 알릴지·위치 공유는 출조 시작 창에서 (여기엔 앱 전체 위치 스위치 하나만)
        switchRow("📍 이 앱에서 내 위치 사용", "끄면 경로·조과 위치·지도 내 위치·위치 공유 모두 안 써요",
            Store.appLoc) { on ->
            Store.appLoc = on
            if (!on) { Tracker.forget(); Live.sync(true) }
            else if (Store.current != null) RemoteService.start(this)
        }
        box.addView(Ui.button(this, "🔒 위치·개인정보 이용 동의 내용 보기") { Consent.show(this, readOnly = true) { render() } }
            .apply { minHeight = dp(44); textSize = 14f }, Ui.lp(this, MATCH, WRAP, top = 8))

        section("같이 출조 중 거리")
        val rr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(50 to "50m", 200 to "200m", 1000 to "1km", 0 to "거리 상관없이").forEachIndexed { i, (v, l) ->
            val on = Store.mateRange == v
            rr.addView(Ui.text(this, 13f, if (on) Ui.BG else Ui.TEXT, true, l).apply {
                gravity = Gravity.CENTER; maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(9, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                background = if (on) Ui.bg(this@SettingsActivity, Ui.GREEN, 99) else Ui.bg(this@SettingsActivity, Ui.CARD, 99, Ui.LINE)
                setOnClickListener { Store.mateRange = v; Live.sync(true); render() }
            }, LinearLayout.LayoutParams(0, dp(42), if (v == 0) 1.6f else 1f).apply { if (i > 0) leftMargin = dp(6) })
        }
        box.addView(rr, Ui.lp(this, MATCH, WRAP, top = 6))
        box.addView(Ui.text(this, 12f, Ui.SUB, false, "기본 1km · 해루질은 500m 넘게 떨어질 때도 있어요 · '거리 상관없이' = 같은 그룹에서 출조 중인 사람 모두"),
            Ui.lp(this, MATCH, WRAP, top = 4))
        renderOffline()
        renderData()
    }

    /** v4.1 (185): 📶 이 앱이 쓴 데이터 */
    private fun renderData() {
        section("📶 이 앱이 쓴 데이터")
        fun mb(b: Long) = if (b < 1024 * 1024) "${(b + 1023) / 1024}KB" else String.format(java.util.Locale.KOREA, "%.1fMB", b / 1048576.0)
        val uid = android.os.Process.myUid()
        val total = (android.net.TrafficStats.getUidRxBytes(uid) + android.net.TrafficStats.getUidTxBytes(uid)).coerceAtLeast(0L)
        val zone = Ui.dataOf(this, "zone"); val upd = Ui.dataOf(this, "update")
        val tiles = try {
            val dir = org.osmdroid.config.Configuration.getInstance().osmdroidTileCache
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (_: Exception) { 0L }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = Ui.bg(this@SettingsActivity, Ui.CARD, 14); setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        fun line(k: String, v: String) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(Ui.text(this, 13.5f, Ui.TEXT, false, k), LinearLayout.LayoutParams(0, WRAP, 1f))
            r.addView(Ui.text(this, 13.5f, Color.parseColor("#FFB27A"), true, v))
            card.addView(r, Ui.lp(this, MATCH, WRAP, top = 4))
        }
        line("🦀 해루질 구역 자료 (이번 달)", mb(zone))
        line("⬇ 앱 업데이트 받기 (이번 달)", mb(upd))
        line("🗺 지도 그림 저장 용량 (폰에 쌓인 것)", mb(tiles))
        line("📶 전체 주고받은 양 (폰을 켠 뒤부터)", mb(total))
        card.addView(Ui.text(this, 11.5f, Ui.SUB, false, "• 지도 · 위성 그림, 사진 올리기, 날씨 · 물때, 같이 출조 중 공유는 '전체'에 들어 있어요\n• 정확한 월 사용량은 폰 설정 → 연결 → 데이터 사용에서 볼 수 있어요\n• 아끼는 법: 구역 미리 받기 · 전국 받기는 와이파이에서, 위성 지도는 꼭 필요할 때만"),
            Ui.lp(this, MATCH, WRAP, top = 8))
        box.addView(card, Ui.lp(this, MATCH, WRAP, top = 6))
    }

    /** v3.9.3 (168): 📐 화면 크기 막대 (70~120% · 가운데 100 = 자동) · 손을 떼면 앱 전체에 적용 */
    private fun renderScale() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = Ui.bg(this@SettingsActivity, Ui.CARD, 14); setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val orange = Color.parseColor("#FFB27A")
        val cur = Ui.userScale(this)
        fun label(v: Int) = if (v == 100) "자동 (100%)" else "$v%"
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.text(this, 15f, Ui.TEXT, true, "📐 화면 크기"), LinearLayout.LayoutParams(0, WRAP, 1f))
        val valTv = Ui.text(this, 15f, orange, true, label(cur))
        head.addView(valTv)
        card.addView(head)
        card.addView(Ui.text(this, 12f, Ui.SUB, false, "왼쪽 = 작게 · 가운데 = 자동(폰 화면에 맞춤) · 오른쪽 = 크게\n손을 떼면 앱 전체에 바로 적용돼요"), Ui.lp(this, MATCH, WRAP, top = 2))
        val preview = Ui.text(this, 14f * cur / 100f, Ui.TEXT, false, "🎣 미리보기 · 주꾸미 23 · 갑오징어 4")
        val sb = android.widget.SeekBar(this).apply {
            max = 50; progress = cur - 70
            val h = dp(10)
            val bgD = android.graphics.drawable.GradientDrawable().apply { setColor(Color.parseColor("#3A4E63")); cornerRadius = h.toFloat(); setSize(0, h) }
            val fgD = android.graphics.drawable.ClipDrawable(android.graphics.drawable.GradientDrawable().apply { setColor(orange); cornerRadius = h.toFloat(); setSize(0, h) },
                Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
            progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(bgD, fgD)).apply {
                setId(0, android.R.id.background); setId(1, android.R.id.progress)
                setLayerHeight(0, h); setLayerHeight(1, h); setLayerGravity(0, Gravity.CENTER_VERTICAL); setLayerGravity(1, Gravity.CENTER_VERTICAL)
            }
            thumb = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(orange); setStroke(dp(3), Color.WHITE); setSize(dp(26), dp(26))
            }
            splitTrack = false
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    var v = p + 70
                    if (fromUser && kotlin.math.abs(v - 100) <= 2) { v = 100 }   // 가운데(자동) 근처면 딱 맞춤
                    valTv.text = label(v); preview.textSize = 14f * v / 100f
                }
                override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(s: android.widget.SeekBar?) {
                    var v = (s?.progress ?: 30) + 70
                    if (kotlin.math.abs(v - 100) <= 2) v = 100
                    if (v != Ui.userScale(this@SettingsActivity)) { Ui.setUserScale(this@SettingsActivity, v); recreate() }
                }
            })
        }
        card.addView(sb, Ui.lp(this, MATCH, WRAP, top = 6))
        val marks = android.widget.FrameLayout(this)
        marks.addView(Ui.text(this, 11f, Ui.SUB, false, "70%"), android.widget.FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START))
        marks.addView(Ui.text(this, 11f, orange, true, "▲ 자동"), android.widget.FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL).apply { leftMargin = dp(40) })
        marks.addView(Ui.text(this, 11f, Ui.SUB, false, "120%"), android.widget.FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END))
        card.addView(marks, Ui.lp(this, MATCH, WRAP))
        card.addView(preview, Ui.lp(this, MATCH, WRAP, top = 8))
        card.addView(Ui.button(this, "↺ 자동으로 되돌리기") {
            if (Ui.userScale(this) != 100) { Ui.setUserScale(this, 100); recreate() } else toast("지금 자동이에요")
        }.apply { minHeight = dp(42); textSize = 13f }, Ui.lp(this, MATCH, WRAP, top = 10))
        box.addView(card, Ui.lp(this, MATCH, WRAP, top = 6))
    }

    /** v3.9.2 (164): 📥 오프라인 지역 (해루질 참고용 구역 미리 받은 곳) · 커튼 */
    private var offOpen = false
    private fun renderOffline() {
        section("📥 오프라인 지역")
        val regs = Khoa.regions(this)
        val head = Ui.text(this, 15f, Ui.TEXT, true, "해루질 참고용 미리 받은 지역 ${regs.size}곳 · ${Khoa.sizeText(Khoa.totalSize(this))} " + if (offOpen) "▴" else "▾").apply {
            gravity = Gravity.CENTER; background = Ui.bg(this@SettingsActivity, Ui.CARD, 14); setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { offOpen = !offOpen; render() }
        }
        box.addView(head, Ui.lp(this, MATCH, WRAP, top = 6))
        if (!offOpen) return
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = Ui.bg(this@SettingsActivity, Ui.CARD, 14); setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        box.addView(card, Ui.lp(this, MATCH, WRAP, top = 4))
        card.addView(Ui.text(this, 12f, Ui.SUB, false, "지도 ▸ ☑ 해루질 참고용 ▾ ▸ 📥 이 지역 받기, 또는 🔍 장소로 검색하면 그 주변을 자동으로 받아요\n바다에서 인터넷이 약해도 받아 둔 구역은 보여요 (오래되면 연결될 때 새로 받음)"))
        if (!Cloud.isAdmin) card.addView(Ui.text(this, 12f, Color.parseColor("#F2C14E"), false,
            "오늘 받은 지역 ${Khoa.usedToday(this)} / ${Khoa.dayLimit(this)} · 저장한 지역 ${regs.size} / ${Khoa.maxRegions(this)}"), Ui.lp(this, MATCH, WRAP, top = 4))
        if (regs.isEmpty()) card.addView(Ui.text(this, 14f, Ui.SUB, false, "아직 받은 지역이 없어요"), Ui.lp(this, MATCH, WRAP, top = 10))
        val df = java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.KOREA)
        regs.forEach { r ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = Ui.bg(this@SettingsActivity, Ui.BG, 12, Ui.LINE); setPadding(dp(12), dp(10), dp(12), dp(10)) }
            row.addView(Ui.text(this, 15f, Ui.TEXT, true, "📍 ${r.name}"))
            val info = Ui.text(this, 12f, Ui.SUB, false, "${df.format(java.util.Date(r.at))} 받음 · ${Khoa.sizeText(Khoa.sizeOf(this, r))}")
            row.addView(info, Ui.lp(this, MATCH, WRAP, top = 2))
            val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            btns.addView(Ui.button(this, "🔄 다시 받기") {
                info.text = "📥 받는 중…"
                Khoa.redownload(this, r, { d, t -> info.text = "📥 받는 중… $d / $t" }) { _, fail ->
                    toast(if (fail == 0) "'${r.name}' 새로 받았어요" else "${fail}곳 실패 · 인터넷 확인 후 다시"); render()
                }
            }.apply { minHeight = dp(40); textSize = 13f }, LinearLayout.LayoutParams(0, WRAP, 1f))
            btns.addView(Ui.button(this, "✕ 지우기") {
                AlertDialog.Builder(this, R.style.FlAlert)
                    .setTitle("'${r.name}' 지울까요?").setMessage("폰에 받아 둔 구역만 지워요 (인터넷 되면 다시 볼 수 있어요)")
                    .setPositiveButton("지우기") { _, _ -> Khoa.deleteRegion(this, r); render() }
                    .setNegativeButton("취소", null).show()
            }.apply { minHeight = dp(40); textSize = 13f; setTextColor(Color.parseColor("#FF8A92")) }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
            row.addView(btns, Ui.lp(this, MATCH, WRAP, top = 8))
            card.addView(row, Ui.lp(this, MATCH, WRAP, top = 8))
        }
        card.addView(Ui.text(this, 13f, Ui.TEXT, false, "전체 크기 ${Khoa.sizeText(Khoa.totalSize(this))} (지도에서 본 곳 기억 포함)").apply { gravity = Gravity.CENTER }, Ui.lp(this, MATCH, WRAP, top = 10))
        card.addView(Ui.button(this, "🗑 모두 지우기") {
            AlertDialog.Builder(this, R.style.FlAlert)
                .setTitle("받아 둔 구역을 모두 지울까요?").setMessage("지도에서 본 곳 기억도 같이 지워져요")
                .setPositiveButton("모두 지우기") { _, _ -> Khoa.deleteAll(this); render() }
                .setNegativeButton("취소", null).show()
        }.apply { minHeight = dp(42); textSize = 13f; setTextColor(Color.parseColor("#FF8A92")) }, Ui.lp(this, MATCH, WRAP, top = 8))
    }

    /** 닉네임 (개인 설정 맨 위) */
    private fun renderNick() {
        section("닉네임")
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.bg(this@SettingsActivity, Ui.CARD, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        box.addView(card, Ui.lp(this, MATCH, WRAP, top = 6))
        if (Cloud.user == null) { card.addView(Ui.text(this, 14f, Ui.SUB, false, "로그인하면 닉네임을 정할 수 있어요")); return }
        val n = Cloud.nick
        if (n == null) {
            // 처음 정하기
            val input = EditText(this).apply {
                hint = "닉네임 입력 (2~10자)"; inputType = InputType.TYPE_CLASS_TEXT; setSingleLine()
                setTextColor(Ui.TEXT); setHintTextColor(Ui.SUB)
            }
            card.addView(input)
            card.addView(Ui.text(this, 12f, Color.parseColor("#F2C14E"), false,
                "나중에 언제든 바꿀 수 있어요 · 다른 사람과 같은 이름은 안 돼요\n구글 계정 실명 대신 이 이름이 그룹·게시판·같이 출조 중에 보여요"),
                Ui.lp(this, MATCH, WRAP, top = 6))
            card.addView(Ui.button(this, "이 닉네임으로 정하기", true) {
                val v = input.text.toString().trim()
                AlertDialog.Builder(this, R.style.FlAlert)
                    .setTitle("'$v'(으)로 정할까요?")
                    .setPositiveButton("정하기") { _, _ -> Cloud.setNick(v) { err -> toast(err ?: "닉네임을 '$v'(으)로 정했어요"); render() } }
                    .setNegativeButton("취소", null).show()
            }.apply { minHeight = dp(46) }, Ui.lp(this, MATCH, WRAP, top = 10))
            return
        }
        card.addView(Ui.text(this, 20f, Ui.TEXT, true, n))
        card.addView(Ui.text(this, 12f, Ui.SUB, false, "전체 게시판 · 그룹 · 같이 출조 중에 이 이름이 보여요"), Ui.lp(this, MATCH, WRAP, top = 4))
        card.addView(Ui.button(this, "닉네임 바꾸기") { askNew() }.apply { minHeight = dp(44); textSize = 14f }, Ui.lp(this, MATCH, WRAP, top = 8))
    }

    private fun askNew() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), 0) }
        val nn = EditText(this).apply { hint = "새 닉네임 (2~10자)"; inputType = InputType.TYPE_CLASS_TEXT; setSingleLine() }
        col.addView(nn)
        AlertDialog.Builder(this, R.style.FlAlert)
            .setTitle("닉네임 바꾸기")
            .setMessage("앞으로 올리는 글·그룹·같이 출조 중에 새 이름이 보여요")
            .setView(col)
            .setPositiveButton("바꾸기") { _, _ ->
                val v = nn.text.toString()
                Cloud.setNick(v) { e -> toast(e ?: "닉네임을 '${v.trim()}'(으)로 바꿨어요"); render() }
            }
            .setNegativeButton("취소", null).show()
    }
}
