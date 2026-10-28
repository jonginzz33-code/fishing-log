package com.fishlog.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    private lateinit var status: TextView
    private lateinit var placeText: TextView
    private lateinit var tideTitle: TextView
    private lateinit var liveBox: LinearLayout
    private val liveListener: () -> Unit = { renderLive() }
    /** 그룹 일정·공지가 바뀌면 바로 (실시간) */
    private val newsLive: () -> Unit = { if (!isFinishing) renderGroupPanel() }
    private lateinit var tideSub: TextView
    private var strengthAt = 0L
    private var strengthKey = ""
    private lateinit var tideSun: TextView
    private lateinit var adminBtn: TextView
    private lateinit var speciesBtn: TextView
    // 맨 아래 그룹 칸 (새 소식 빨간 숫자)
    private lateinit var groupBtn: TextView
    private lateinit var groupDot: TextView
    private lateinit var groupBox: LinearLayout
    private var groupOpen = false
    private var groupsAt = 0L
    private lateinit var updateBar: TextView
    private lateinit var message: TextView
    private lateinit var menuBtn: TextView
    private lateinit var menuBox: LinearLayout
    private lateinit var recentBox: LinearLayout
    private lateinit var outerRecent: LinearLayout
    private lateinit var innerRecent: LinearLayout
    private lateinit var catRow: LinearLayout
    private lateinit var methodBox: LinearLayout
    private var methodsOpen = false
    private lateinit var card1: android.widget.FrameLayout
    private lateinit var card2: android.widget.FrameLayout
    private lateinit var tier1: TextView
    private lateinit var tier2: TextView
    private lateinit var confetti1: ConfettiView
    private lateinit var confetti2: ConfettiView
    private lateinit var banner1: TextView
    private lateinit var banner2: TextView
    private lateinit var art1: SpeciesArtView
    private lateinit var art2: SpeciesArtView
    private lateinit var name1: TextView
    private lateinit var name2: TextView
    private lateinit var count1: TextView
    private lateinit var count2: TextView
    private lateinit var last1: TextView
    private lateinit var last2: TextView
    private lateinit var tripBtn: TextView
    private lateinit var undoBtn: TextView
    private lateinit var recent: TextView
    private lateinit var historyBtn: TextView
    private lateinit var accountInfo: TextView
    private lateinit var accountBtn: TextView
    private lateinit var hideRouteBox: CheckBox
    private lateinit var account: AccountUi

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { updateStatus(); handler.postDelayed(this, 1000) }
    }
    private val storeListener: () -> Unit = { refresh() }
    private val cloudListener: () -> Unit = { refreshAccount(); maybeAskNick() }

    /** 닉네임을 아직 안 정했으면 한 번 안내 (실명 대신 보일 이름) */
    private fun maybeAskNick() {
        if (Cloud.user != null && Cloud.nickLoaded && Cloud.nick == null && !nickAsked && !isFinishing) {
            nickAsked = true
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("askNick", true))
        }
    }
    private var crashShown = false
    /** 로그인 전: 로그인 화면만 */
    private var gated = false
    private var nickAsked = false
    private var cloudLoaded = false

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crashPrefs = getSharedPreferences("crash", MODE_PRIVATE)
        val trace = crashPrefs.getString("trace", null)
        if (trace != null) {
            crashPrefs.edit().remove("trace").commit()
            crashShown = true
            setContentView(ScrollView(this).apply {
                setBackgroundColor(Color.WHITE)
                addView(TextView(this@MainActivity).apply {
                    setTextColor(Color.BLACK)
                    textSize = 12f
                    setPadding(24, 48, 24, 48)
                    setTextIsSelectable(true)
                    text = "앱 오류 내용 (이 화면을 캡처해서 보내주세요)\n버전 ${BuildConfigInfo.VERSION}\n\n$trace"
                })
            })
            return
        }
        Store.init(this)
        // 앱을 새로 켜면 메뉴는 항상 접힌 상태로 시작
        if (savedInstanceState == null) Store.menuOpen = false
        account = AccountUi(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        if (Cloud.user == null) { showLoginGate(); return }
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        if (crashShown || gated) return
        // 관리자가 이용 정지한 계정·폰이면 막는 화면만
        if (Moderation.bannedCached) { crashShown = true; Moderation.showBlockedScreen(this); return }
        Moderation.checkBan(this) { banned ->
            if (banned && !crashShown && !isFinishing) {
                crashShown = true
                Store.removeListener(storeListener); Cloud.removeListener(cloudListener)
                handler.removeCallbacks(ticker)
                Moderation.showBlockedScreen(this)
            }
        }
        Store.addListener(storeListener)
        Cloud.addListener(cloudListener)
        Live.addListener(liveListener)
        GroupNews.startLive(newsLive)
        Milestone.addListener(milestoneListener)
        if (Store.current != null) Live.sync()
        ButtonRouter.onMessage = { showMessage(it) }
        if (Store.current != null) RemoteService.start(this)
        maybeAskNick()
        if (!cloudLoaded && Cloud.user != null) {
            cloudLoaded = true
            Cloud.refresh()
            Cloud.backupPending()
        }
        Cloud.loadSpecies()
        Tide.prune(this)
        Photos.cleanOldShareCopies(this)
        GroupNews.load(false) { if (!isFinishing) renderGroupPanel() }
        if (!Updater.checkedThisRun) {
            Updater.checkedThisRun = true
            Updater.check(this) { v -> if (v != null) updateBar.visibility = View.VISIBLE }
        }
        refresh()
        refreshAccount()
        handler.post(ticker)
    }

    override fun onPause() {
        if (crashShown || gated) { super.onPause(); return }
        Store.removeListener(storeListener)
        Cloud.removeListener(cloudListener)
        Live.removeListener(liveListener)
        GroupNews.stopLive(newsLive)
        Milestone.removeListener(milestoneListener)
        ButtonRouter.onMessage = null
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (ButtonRouter.wants(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_UP) {
                ButtonRouter.onKeyUp(this, event.keyCode, event.eventTime - event.downTime)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------- 로그인 화면 (처음 실행하면 무조건 여기부터) ----------------
    private fun showLoginGate() {
        gated = true
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(40), dp(28), dp(40))
            setBackgroundColor(Ui.BG)
        }
        col.addView(android.widget.ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) },
            LinearLayout.LayoutParams(dp(96), dp(96)))
        col.addView(Ui.text(this, 26f, Ui.TEXT, true, "조과기록").apply { gravity = Gravity.CENTER },
            Ui.lp(this, MATCH, WRAP, top = 16))
        col.addView(Ui.text(this, 14f, Ui.SUB, false,
            "리모컨·터치·워치로 조과를 기록하고\n그룹 친구들과 함께 봐요").apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.3f) },
            Ui.lp(this, MATCH, WRAP, top = 8))
        val msg = Ui.text(this, 13f, Ui.ORANGE, false, "").apply { gravity = Gravity.CENTER }
        val btn = Ui.button(this, "구글로 로그인", filled = true) {}
        btn.setOnClickListener {
            btn.text = "로그인 중…"
            account.signIn {
                if (Cloud.user != null) {
                    gated = false
                    recreate()
                } else btn.text = "구글로 로그인"
            }
            // 취소하면 버튼 글자 되돌리기
            handler.postDelayed({ if (gated && Cloud.user == null) btn.text = "구글로 로그인" }, 15_000L)
        }
        col.addView(btn, Ui.lp(this, MATCH, WRAP, top = 36))
        col.addView(msg, Ui.lp(this, MATCH, WRAP, top = 10))
        col.addView(Ui.text(this, 12f, Ui.SUB, false,
            "처음 한 번만 로그인하면 돼요. 그다음부터는 바다에서 인터넷이 안 돼도 그대로 쓸 수 있어요.\n폰에 있던 기록은 로그인 후에도 그대로 남아요.").apply {
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.3f)
        }, Ui.lp(this, MATCH, WRAP, top = 24))
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(col) })
    }

    // ---------------- 화면 구성 ----------------

    private fun smallBtn(label: String, onClick: () -> Unit): TextView =
        Ui.text(this, 14f, Ui.TEXT, true, label).apply {
            gravity = Gravity.CENTER
            background = Ui.bg(this@MainActivity, Ui.CARD, 12, Ui.LINE)
            setOnClickListener { onClick() }
        }

    private fun buildUi(): View {
        val col = Ui.column(this)

        // 새 버전 알림
        updateBar = Ui.text(this, 14f, Ui.BG, true, "새 버전이 있어요 · 눌러서 업데이트").apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            background = Ui.bg(this@MainActivity, Ui.GREEN, 12)
            visibility = View.GONE
            setOnClickListener { Updater.downloadAndInstall(this@MainActivity) { msg -> text = msg } }
        }
        col.addView(updateBar, Ui.lp(this, MATCH, WRAP).apply { bottomMargin = dp(12) })

        // 상단: 왼쪽 지도·물때 버튼 + 제목
        val header = Ui.row(this)
        header.addView(smallBtn("지도") { openMap() }, LinearLayout.LayoutParams(dp(56), dp(48)))
        header.addView(Ui.text(this, 20f, Ui.TEXT, true, "조과기록"),
            LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(10) })
        // (그룹은 맨 아래 '그룹' 칸으로 옮김)
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(Ui.text(this, 18f, Ui.TEXT, true, "?").apply {
            gravity = Gravity.CENTER
            background = Ui.bg(this@MainActivity, Ui.CARD, 99, Ui.LINE)
            setOnClickListener { startActivity(Intent(this@MainActivity, HelpActivity::class.java)) }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        // 개인 설정 (톱니바퀴)
        header.addView(Ui.text(this, 19f, Ui.BLUE, true, "⚙").apply {
            gravity = Gravity.CENTER
            background = Ui.bg(this@MainActivity, Ui.CARD, 99, Ui.BLUE)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(6) })
        col.addView(header)

        // 낚시 종류 칩은 맨 아래 '그룹' 칸 안에서 고름 (renderGroupPanel)
        catRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 물때 한 줄 + "물때 보기 ›" (누르면 날짜를 바꿀 수 있는 상세 창)
        val tideCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.bg(this@MainActivity, Ui.CARD, 14)
            setPadding(dp(14), dp(10), dp(10), dp(10))
            isClickable = true
            setOnClickListener { openTodayTide() }
        }
        val tideTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tideTitle = Ui.text(this, 17f, Ui.TEXT, true, "")
        tideSub = Ui.text(this, 12f, Ui.SUB, false, "물때 불러오는 중…")
        tideSun = Ui.text(this, 12f, Ui.SUB, false, "")
        tideTexts.addView(tideTitle); tideTexts.addView(tideSub, Ui.lp(this, MATCH, WRAP, top = 2))
        tideTexts.addView(tideSun, Ui.lp(this, MATCH, WRAP, top = 4))
        tideCard.addView(tideTexts, LinearLayout.LayoutParams(0, WRAP, 1f))
        tideCard.addView(Ui.text(this, 13f, Ui.BG, true, "물때 보기 ›").apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            background = Ui.bg(this@MainActivity, Ui.BLUE, 99)
            setOnClickListener { openTodayTide() }
        }, LinearLayout.LayoutParams(WRAP, dp(38)).apply { leftMargin = dp(8) })
        col.addView(tideCard, Ui.lp(this, MATCH, WRAP, top = 12))
        // 출조 중: 배·항구 (눌러서 수정)
        placeText = Ui.text(this, 16f, Ui.TEXT, true).apply {
            setOnClickListener { Store.current?.let { askPlace(it.id) } }
        }
        col.addView(placeText, Ui.lp(this, MATCH, WRAP, top = 10))
        status = Ui.text(this, 14f, Ui.SUB)
        col.addView(status, Ui.lp(this, MATCH, WRAP, top = 2))

        // 기록 방식 카드: 처음엔 네 가지 모두, 고른 뒤엔 한 줄 칩 + "바꾸기 ▼"
        methodBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.bg(this@MainActivity, Ui.CARD, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        col.addView(methodBox, Ui.lp(this, MATCH, WRAP, top = 12))
        message = Ui.text(this, 13f, Ui.GREEN).apply { visibility = View.GONE }
        col.addView(message, Ui.lp(this, MATCH, WRAP, top = 6))

        // 출조 시작 / 종료: 카드 바로 위 (앱 켜면 바로 누를 수 있게)
        tripBtn = Ui.button(this, "출조 시작", filled = true) { onTripButton() }
        col.addView(tripBtn, Ui.lp(this, MATCH, WRAP, top = 14))

        // 카운터
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(counterCard(1, Ui.ORANGE, Color.parseColor("#1B0E02")), Ui.lp(this, 0, dp(285), 1f))
        row.addView(View(this), Ui.lp(this, dp(12), 1))
        row.addView(counterCard(2, Ui.BLUE, Color.parseColor("#04222A")), Ui.lp(this, 0, dp(285), 1f))
        col.addView(row, Ui.lp(this, MATCH, WRAP, top = 14))
        // 같이 낚시 중인 그룹원 (50m 안, 5분마다)
        liveBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.bg(this@MainActivity, Ui.CARD, 12, Ui.LINE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
        }
        col.addView(liveBox, Ui.lp(this, MATCH, WRAP, top = 8))
        col.addView(Ui.text(this, 12f, Ui.SUB, false,
            "✎를 누르면 어종 변경 · 리모컨 버튼을 길게(1초) 누르면 마지막 기록 취소"),
            Ui.lp(this, MATCH, WRAP, top = 8))

        undoBtn = Ui.button(this, "마지막 기록 취소") {
            if (Store.undoLast() != 0) { Haptics.undo(this); showMessage("마지막 기록을 취소했어요") }
        }
        col.addView(undoBtn, Ui.lp(this, MATCH, WRAP, top = 12))

        // 최근 기록: 출조 중엔 메뉴 밖, 대기 중엔 메뉴 안
        recentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        recentBox.addView(Ui.text(this, 16f, Ui.TEXT, true, "최근 기록"), Ui.lp(this, MATCH, WRAP, top = 16))
        recent = Ui.text(this, 15f, Color.parseColor("#CFE0EA")).apply {
            background = Ui.bg(this@MainActivity, Ui.CARD, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.3f)
        }
        recentBox.addView(recent, Ui.lp(this, MATCH, WRAP, top = 8))
        outerRecent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(outerRecent, Ui.lp(this, MATCH, WRAP))

        // ---- 그룹 (가로줄로 메뉴와 따로) ----
        col.addView(divider(), Ui.lp(this, MATCH, dp(1), top = 20))
        val gFrame = android.widget.FrameLayout(this).apply { clipChildren = false; clipToPadding = false; setPadding(0, dp(8), dp(8), 0) }
        groupBtn = Ui.button(this, "그룹 ▼") {
            groupOpen = !groupOpen
            renderGroupPanel()
            if (groupOpen) {
                // 펼칠 때 그룹 목록·새 소식 다시 받기 (30초에 한 번)
                if (Cloud.user != null && System.currentTimeMillis() - groupsAt > 30_000L) {
                    groupsAt = System.currentTimeMillis()
                    Cloud.refresh { GroupNews.load(true) { if (!isFinishing) renderGroupPanel() } }
                } else GroupNews.load(false) { renderGroupPanel() }
            }
        }
        gFrame.addView(groupBtn, android.widget.FrameLayout.LayoutParams(MATCH, WRAP))
        groupDot = redDot(0)
        gFrame.addView(groupDot, android.widget.FrameLayout.LayoutParams(WRAP, dp(22), Gravity.TOP or Gravity.END).apply { topMargin = -dp(8); rightMargin = -dp(8) })
        col.addView(gFrame, Ui.lp(this, MATCH, WRAP, top = 8))
        groupBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        col.addView(groupBox, Ui.lp(this, MATCH, WRAP))
        col.addView(divider(), Ui.lp(this, MATCH, dp(1), top = 16))

        // 메뉴 ▼ : 아래 항목들을 접고 펼치기
        menuBtn = Ui.button(this, "메뉴 ▼") {
            Store.menuOpen = !Store.menuOpen
            paintMenu()
        }
        col.addView(menuBtn, Ui.lp(this, MATCH, WRAP, top = 16))
        menuBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(menuBox, Ui.lp(this, MATCH, WRAP))
        innerRecent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        menuBox.addView(innerRecent, Ui.lp(this, MATCH, WRAP))

        historyBtn = Ui.button(this, "지난 출조 · 내 기록 보관함") {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        menuBox.addView(historyBtn, Ui.lp(this, MATCH, WRAP, top = 12))
        menuBox.addView(Ui.button(this, "전체 조황 · 포인트 도감") {
            if (Cloud.user == null) showMessage("구글 로그인을 하면 볼 수 있어요")
            else startActivity(Intent(this, BoardActivity::class.java))
        }, Ui.lp(this, MATCH, WRAP, top = 10))

        // 친구 공유 카드
        val acc = Ui.card(this)
        accountInfo = Ui.text(this, 14f)
        accountInfo.setOnClickListener { if (Cloud.active != null) startActivity(Intent(this, GroupActivity::class.java)) }
        accountBtn = Ui.button(this, "구글 로그인") {
            when {
                Cloud.user == null -> account.signIn { refreshAccount() }
                Cloud.active == null -> account.openGroups()
                else -> startActivity(Intent(this, GroupActivity::class.java))
            }
        }.apply { minHeight = dp(44); textSize = 14f }
        hideRouteBox = CheckBox(this).apply {
            text = "그룹에 올릴 때 이동 경로 숨기기"
            setTextColor(Ui.TEXT); textSize = 14f
            buttonTintList = ColorStateList.valueOf(Ui.BLUE)
            minHeight = dp(44)
            setOnCheckedChangeListener { _, checked -> if (checked != Store.hideRoute) Store.hideRoute = checked }
        }
        acc.addView(accountInfo)
        acc.addView(accountBtn, Ui.lp(this, MATCH, WRAP, top = 10))
        acc.addView(hideRouteBox, Ui.lp(this, MATCH, WRAP, top = 4))
        menuBox.addView(acc, Ui.lp(this, MATCH, WRAP, top = 16))
        speciesBtn = Ui.button(this, "어종 관리 (관리자)") {
            val cats = Store.CATS
            BigList.show(this, "어느 종류의 어종을 관리할까요?", cats.map { Store.catLabel(it) }, emptyList()) { i, _ ->
                cats.getOrNull(i)?.let { manageSpecies(it) }
            }
        }.apply { minHeight = dp(44); textSize = 14f; visibility = View.GONE }
        menuBox.addView(speciesBtn, Ui.lp(this, MATCH, WRAP, top = 12))
        adminBtn = Ui.button(this, "관리자 · 사용자 목록") {
            startActivity(Intent(this, AdminActivity::class.java))
        }.apply { minHeight = dp(44); textSize = 14f; visibility = View.GONE }
        menuBox.addView(adminBtn, Ui.lp(this, MATCH, WRAP, top = 12))
        menuBox.addView(Ui.text(this, 11f, Ui.SUB, false, "버전 ${BuildConfigInfo.VERSION}"), Ui.lp(this, MATCH, WRAP, top = 12))
        col.addView(View(this), Ui.lp(this, MATCH, dp(24)))
        paintMenu()

        return ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(col)
        }
    }

    private fun counterCard(slot: Int, color: Int, ink: Int): View {
        val card = android.widget.FrameLayout(this).apply {
            background = Milestone.cardBackground(this@MainActivity, color, -1)
            isClickable = true
            clipToOutline = true
            setOnClickListener {
                if (!Store.touchMode) { showMessage("터치 기록이 꺼져 있어요 (기록 방식에서 '터치' 켜기)"); return@setOnClickListener }
                if (Store.current == null) { showMessage("먼저 '출조 시작'을 눌러주세요"); return@setOnClickListener }
                Store.addCatch(slot)
                Haptics.slot(this@MainActivity, slot)
            }
        }
        // 오른쪽 아래 구석: 작고 연한 어종 그림
        val art = SpeciesArtView(this).apply { this.ink = ink; alpha = 0.3f }
        card.addView(art, android.widget.FrameLayout.LayoutParams(dp(92), dp(92), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = -dp(6); bottomMargin = -dp(6)
        })
        // 어종 이름: 가운데, 카드 폭에 꽉 차게 (흰색 반투명)
        val name = TextView(this).apply {
            setTextColor(Color.argb(215, 255, 255, 255))
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            gravity = Gravity.CENTER
            setAutoSizeTextTypeUniformWithConfiguration(16, 64, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        card.addView(name, android.widget.FrameLayout.LayoutParams(MATCH, dp(56), Gravity.TOP).apply {
            topMargin = dp(48); leftMargin = dp(8); rightMargin = dp(8)
        })
        // 가운데: 아주 크고 굵은 숫자 (세 자리까지 자동 크기, 흰 테두리 빛)
        val count = TextView(this).apply {
            setTextColor(ink)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            maxLines = 1
            gravity = Gravity.CENTER
            textScaleX = 0.86f
            includeFontPadding = false
            setShadowLayer(6 * resources.displayMetrics.density, 0f, 0f, Color.argb(170, 255, 255, 255))
            setAutoSizeTextTypeUniformWithConfiguration(40, 150, 2, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        card.addView(count, android.widget.FrameLayout.LayoutParams(MATCH, dp(126), Gravity.TOP).apply {
            topMargin = dp(104); leftMargin = dp(2); rightMargin = dp(2)
        })
        // 위: "버튼 1" + 작은 연필 (연필을 눌러야 어종 바꾸기)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.text(this, 12f, ink, true, "버튼 $slot").apply {
            background = Ui.bg(this@MainActivity, Color.argb(36, 0, 0, 0), 99)
            setPadding(dp(10), dp(3), dp(10), dp(3))
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(Ui.text(this, 15f, ink, true, "✎").apply {
            gravity = Gravity.CENTER
            background = Ui.bg(this@MainActivity, Color.argb(80, 255, 255, 255), 10)
            setOnClickListener { askRename(slot) }
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        card.addView(top, android.widget.FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply {
            topMargin = dp(10); leftMargin = dp(12); rightMargin = dp(10)
        })
        // 아래: 칭호 + 마지막 시각 · 경과
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tierText = Ui.text(this, 13f, Color.WHITE, true, "").apply { visibility = View.GONE; setShadowLayer(3f, 0f, 1f, Color.argb(120, 0, 0, 0)) }
        bottom.addView(tierText)
        val last = Ui.text(this, 12f, ink).apply { maxLines = 1 }
        bottom.addView(last, Ui.lp(this, WRAP, WRAP, top = 2))
        card.addView(bottom, android.widget.FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(14); bottomMargin = dp(12)
        })
        // 돌파 순간: 색종이 + 금색 띠
        val confetti = ConfettiView(this)
        card.addView(confetti, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        val banner = Ui.text(this, 13f, Color.parseColor("#412402"), true, "").apply {
            gravity = Gravity.CENTER; visibility = View.GONE
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = Ui.bg(this@MainActivity, Color.parseColor("#F2C14E"), 99)
        }
        card.addView(banner, android.widget.FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(52) })
        if (slot == 1) { name1 = name; count1 = count; last1 = last; art1 = art; card1 = card; tier1 = tierText; confetti1 = confetti; banner1 = banner }
        else { name2 = name; count2 = count; last2 = last; art2 = art; card2 = card; tier2 = tierText; confetti2 = confetti; banner2 = banner }
        return card
    }

    private val SLOT_BG = intArrayOf(Ui.ORANGE, Ui.BLUE)
    private val lastTier = intArrayOf(-2, -2)

    /** 카드 테두리·칭호를 지금 마릿수 단계에 맞추기 */
    private fun paintTier(slot: Int, count: Int) {
        val t = Milestone.tier(count)
        if (lastTier[slot - 1] == t) return
        lastTier[slot - 1] = t
        val card = if (slot == 1) card1 else card2
        val tv = if (slot == 1) tier1 else tier2
        card.background = Milestone.cardBackground(this, SLOT_BG[slot - 1], t)
        tv.text = Milestone.titleWithCrown(t)
        tv.visibility = if (t < 0) View.GONE else View.VISIBLE
    }

    /** 돌파하는 순간: 금색 띠 + 색종이 (2~3초) */
    private val milestoneListener: (Int, Int, Int) -> Unit = { slot, count, tier ->
        val banner = if (slot == 1) banner1 else banner2
        val conf = if (slot == 1) confetti1 else confetti2
        banner.text = "${count}마리 돌파! ${Milestone.titleWithCrown(tier)}"
        banner.visibility = View.VISIBLE
        conf.start(if (tier >= 7) 90 else if (tier >= 4) 60 else 30)
        handler.postDelayed({ banner.visibility = View.GONE }, 3000)
    }

    /** 마지막 시각 + 경과: "마지막 20:00 · 26분 전" */
    private fun lastText(t: Store.Trip?, slot: Int): CharSequence {
        val c = t?.catches?.lastOrNull { it.slot == slot } ?: return " "
        val min = ((System.currentTimeMillis() - c.time) / 60_000L).coerceAtLeast(0)
        val ago = when {
            min < 1 -> "방금"
            min < 60 -> "${min}분 전"
            else -> "${min / 60}시간 ${min % 60}분 전"
        }
        val sb = android.text.SpannableStringBuilder("마지막 " + SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(c.time)) + "  ")
        val st = sb.length
        sb.append(" $ago ")
        sb.setSpan(android.text.style.BackgroundColorSpan(Color.argb(40, 0, 0, 0)), st, sb.length, 0)
        sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), st, sb.length, 0)
        return sb
    }

    // ---------------- 기록 방식 (리모컨 · 터치 · 워치 · 배터리 절약) ----------------
    private fun remoteOn() = Store.key1 != null && Store.key2 != null

    /** 한 번이라도 골랐는지 (예전부터 쓰던 사람은 고른 걸로) */
    private fun methodsChosen(): Boolean = Store.methodsChosen || Store.history.isNotEmpty() || remoteOn() || Store.watchMode

    private fun chip(label: String, on: Boolean) = Ui.text(this, 12f, if (on) Color.parseColor("#04222A") else Ui.SUB, on, label).apply {
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = if (on) Ui.bg(this@MainActivity, Ui.BLUE, 99) else Ui.bg(this@MainActivity, Color.TRANSPARENT, 99, Ui.LINE)
    }

    private fun renderMethods() {
        if (!::methodBox.isInitialized) return
        methodBox.removeAllViews()
        val learning = ButtonRouter.learningStep != 0
        val open = methodsOpen || learning || !methodsChosen()
        if (!open) {
            // 한 줄: 기록 방식 칩 + 바꾸기 ▼
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                isClickable = true; setOnClickListener { methodsOpen = true; renderMethods() }
            }
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            left.addView(Ui.text(this, 12f, Ui.SUB, false, "기록 방식"))
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val on = mutableListOf<String>()
            if (remoteOn()) on.add("리모컨")
            if (Store.touchMode) on.add("터치")
            if (Store.watchMode) on.add("워치 ⏮1 ⏭2")
            if (on.isEmpty()) on.add("선택 안 됨")
            on.forEach { chips.addView(chip(it, it != "선택 안 됨"), LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) }) }
            chips.addView(chip(if (Store.saver) "배터리 절약" else "배터리 절약 꺼짐", Store.saver))
            left.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) },
                Ui.lp(this, MATCH, WRAP, top = 6))
            row.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(Ui.text(this, 13f, Ui.TEXT, true, "바꾸기 ▼").apply {
                gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0)
                background = Ui.bg(this@MainActivity, Color.TRANSPARENT, 99, Ui.LINE)
                setOnClickListener { methodsOpen = true; renderMethods() }
            }, LinearLayout.LayoutParams(WRAP, dp(36)).apply { leftMargin = dp(8) })
            methodBox.addView(row)
            return
        }
        // 펼친 상태
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.text(this, 14f, Ui.TEXT, true,
            if (methodsChosen()) "기록 방식 바꾸기" else "기록 방식을 골라 주세요 (여러 개 가능)"), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (methodsChosen() && !learning) head.addView(Ui.text(this, 13f, Color.parseColor("#04222A"), true, "접기 ▲").apply {
            gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0)
            background = Ui.bg(this@MainActivity, Ui.BLUE, 99)
            setOnClickListener { methodsOpen = false; renderMethods() }
        }, LinearLayout.LayoutParams(WRAP, dp(34)))
        methodBox.addView(head)

        fun option(title: String, sub: String, on: Boolean, action: (() -> Unit)?, onToggle: () -> Unit) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = Ui.bg(this@MainActivity, Color.TRANSPARENT, 12, if (on) Ui.BLUE else Ui.LINE)
                isClickable = true
                setOnClickListener { Store.methodsChosen = true; onToggle(); renderMethods() }
            }
            r.addView(Ui.text(this, 14f, Color.parseColor("#04222A"), true, if (on) "✓" else "").apply {
                gravity = Gravity.CENTER
                background = if (on) Ui.bg(this@MainActivity, Ui.BLUE, 5) else Ui.bg(this@MainActivity, Color.TRANSPARENT, 5, Ui.BLUE)
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(Ui.text(this, 15f, Ui.TEXT, true, title))
            txt.addView(Ui.text(this, 12f, Ui.SUB, false, sub))
            r.addView(txt, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(10) })
            if (action != null) r.addView(Ui.text(this, 12f, Ui.TEXT, true, if (learning) "등록 취소" else "버튼 등록").apply {
                gravity = Gravity.CENTER; setPadding(dp(10), 0, dp(10), 0)
                background = Ui.bg(this@MainActivity, Color.TRANSPARENT, 8, Ui.LINE)
                setOnClickListener { Store.methodsChosen = true; action(); renderMethods() }
            }, LinearLayout.LayoutParams(WRAP, dp(34)))
            methodBox.addView(r, Ui.lp(this, MATCH, WRAP, top = 8))
        }
        val learnAction = {
            if (ButtonRouter.learningStep == 0) ButtonRouter.startLearning() else ButtonRouter.cancelLearning()
            refresh()
        }
        option("리모컨", "1번: ${ButtonRouter.label(Store.key1)} · 2번: ${ButtonRouter.label(Store.key2)}", remoteOn(), learnAction) {
            if (remoteOn()) {
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("리모컨 등록을 지울까요?")
                    .setPositiveButton("지우기") { _, _ -> Store.key1 = null; Store.key2 = null; renderMethods() }
                    .setNegativeButton("취소", null).show()
            } else learnAction()
        }
        option("터치", "카드를 눌러 +1", Store.touchMode, null) { Store.touchMode = !Store.touchMode }
        option("갤럭시 워치", "워치 음악 화면 ⏮ = 1번 · ⏭ = 2번", Store.watchMode, null) {
            Store.watchMode = !Store.watchMode
            if (Store.watchMode) showWatchGuide()
        }
        option("배터리 절약", "이동 경로를 3분 간격으로", Store.saver, null) { Store.saver = !Store.saver }
    }

    // ---------------- 동작 ----------------

    private fun onTripButton() {
        val t = Store.current
        if (t == null) {
            askPlace(null)
        } else {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), 0) }
            box.addView(Ui.text(this, 15f, Ui.TEXT, false, "${t.name(1)} ${t.count(1)}마리 · ${t.name(2)} ${t.count(2)}마리"))
            val canShare = Cloud.user != null && t.catches.isNotEmpty()
            val groups = if (Cloud.user != null) Cloud.groups else emptyList()
            val catName = Store.catLabel(t.cat.ifBlank { Store.category })
            fun groupLabel(g: Cloud.Group) = if (g.name.trim().endsWith("그룹")) g.name else "${g.name} 그룹"
            val allBox = CheckBox(this).apply {
                text = "가입한 그룹 전체에 올리기 (" + groups.joinToString(", ") { groupLabel(it) } + ")"
                setTextColor(Ui.TEXT); textSize = 14f
                buttonTintList = ColorStateList.valueOf(Ui.BLUE)
                isChecked = false
                visibility = if (canShare && groups.size > 1) View.VISIBLE else View.GONE
            }
            box.addView(allBox, Ui.lp(this, MATCH, WRAP, top = 10))
            val pubBox = CheckBox(this).apply {
                text = "전체 게시판에도 공개 (위치는 다음 창에서 정확/대략 선택, 경로·배 이름은 안 올라감)"
                setTextColor(Ui.TEXT); textSize = 14f
                buttonTintList = ColorStateList.valueOf(Ui.BLUE)
                isChecked = canShare
                visibility = if (canShare) View.VISIBLE else View.GONE
            }
            box.addView(pubBox, Ui.lp(this, MATCH, WRAP, top = 4))
            // 이용료 · 꼭 필요한 물건 (같은 장소면 지난번 것으로 미리 채움)
            val tripCat = t.cat.ifBlank { Store.category }
            val cost = CostUi.editor(this, tripCat, Store.lastCost(t.place(), tripCat))
            box.addView(cost.view)
            fun titleText(): String {
                val where = when {
                    Cloud.user == null || Cloud.active == null -> "그룹 없음 · $catName · 내 폰에만 저장"
                    allBox.isChecked -> "가입한 그룹 전체 · $catName"
                    else -> "${groupLabel(Cloud.active!!)} · $catName"
                }
                return "출조를 종료할까요? ($where)"
            }
            // 제목이 길어도 잘리지 않게 직접 만든 제목
            val titleView = Ui.text(this, 19f, Ui.TEXT, true, titleText()).apply { setPadding(dp(22), dp(20), dp(22), dp(4)) }
            val dlg = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setCustomTitle(titleView)
                .setView(ScrollView(this).apply { addView(box) })
                .setPositiveButton("종료") { _, _ ->
                    val ended = Store.endTrip()
                    RemoteService.stop(this)
                    // 이용료·준비물은 그룹에 올리기 전에 저장
                    if (ended != null) Store.setCost(ended.id, cost.read())
                    if (ended != null && Cloud.user != null) {
                        if (ended.catches.isNotEmpty()) {
                            if (allBox.isChecked && groups.size > 1) account.uploadAll(ended)
                            else if (Cloud.active != null) account.upload(ended)
                        }
                        Cloud.backupPending()
                    }
                    if (ended != null) startActivity(Intent(this, TripActivity::class.java)
                        .putExtra("id", ended.id).putExtra("publish", pubBox.isChecked && pubBox.visibility == View.VISIBLE))
                }
                .setNegativeButton("계속", null)
                .create()
            allBox.setOnCheckedChangeListener { _, _ -> titleView.text = titleText() }
            dlg.show()
        }
    }

    private fun startTrip(boat: String, port: String) {
        if (!Tracker.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 2)
        }
        Store.startTrip(boat, port)
        RemoteService.start(this)
        bestLocation()?.let { Tide.prefetch(this, it.first, it.second) }
        showMessage("출조 시작! 화면을 꺼도 리모컨으로 기록돼요")
    }

    private fun suggestField(hint: String, items: List<String>, value: String): AutoCompleteTextView =
        AutoCompleteTextView(this).apply {
            this.hint = hint
            setText(value)
            inputType = InputType.TYPE_CLASS_TEXT
            threshold = 0
            setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, items))
            setOnClickListener { if (items.isNotEmpty()) showDropDown() }
            setOnFocusChangeListener { _, has -> if (has && items.isNotEmpty()) post { showDropDown() } }
        }

    /** 배·항구 입력. tripId == null 이면 새 출조 시작 */
    private fun askPlace(tripId: Long?) {
        val t = tripId?.let { Store.find(it) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val boat = suggestField("배 이름 (예: ○○호)", Store.boats(), t?.boat ?: Store.boats().firstOrNull() ?: "")
        val port = suggestField("출항지 (예: ○○항)", Store.ports(), t?.port ?: Store.ports().firstOrNull() ?: "")
        box.addView(boat)
        box.addView(port)
        val b = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (tripId == null) "출조 시작 · 배와 항구" else "배와 항구 수정")
            .setMessage("한 번 적은 이름은 목록에 저장돼서 다음부터 고를 수 있어요")
            .setView(box)
        if (tripId == null) {
            b.setPositiveButton("출조 시작") { _, _ -> startTrip(boat.text.toString(), port.text.toString()) }
            b.setNeutralButton("건너뛰기") { _, _ -> startTrip("", "") }
            b.setNegativeButton("취소", null)
        } else {
            b.setPositiveButton("저장") { _, _ -> Store.setPlace(tripId, boat.text.toString(), port.text.toString()) }
            b.setNegativeButton("취소", null)
        }
        b.show()
    }

    /** 버튼 어종: 지금 낚시 종류의 어종 목록에서 고르기 */
    private fun askRename(slot: Int) {
        val cat = Store.current?.cat?.ifBlank { null } ?: Store.category
        val list = Store.species(cat)
        val tail = if (Cloud.isAdmin) listOf("+ 새 어종 추가") else listOf("목록에 없는 어종은 관리자에게 요청")
        BigList.show(this, "버튼 $slot 어종 (${Store.catLabel(cat)})", list, tail) { i, label ->
            if (i < list.size) Store.rename(slot, label)
            else if (Cloud.isAdmin) askNewSpecies(cat) { name -> Store.rename(slot, name) }
            else showMessage("어종 추가는 관리자만 할 수 있어요")
        }
    }

    private fun askNewSpecies(cat: String, then: ((String) -> Unit)? = null) {
        val input = EditText(this).apply { hint = "어종 이름"; inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("${Store.catLabel(cat)} 어종 추가")
            .setView(input)
            .setPositiveButton("추가") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) { Store.addSpecies(cat, n); saveSpeciesToServer(); then?.invoke(n) }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 종류별 어종 관리: 추가, 순서, 삭제 */
    private fun manageSpecies(cat: String) {
        val list = Store.species(cat)
        BigList.show(this, "${Store.catLabel(cat)} 어종 (눌러서 순서·삭제)", list, listOf("+ 새 어종 추가")) { i, label ->
            if (i >= list.size) { askNewSpecies(cat) { manageSpecies(cat) }; return@show }
            val opts = arrayOf("위로 올리기", "아래로 내리기", "삭제")
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(label)
                .setItems(opts) { _, w ->
                    val l = Store.species(cat).toMutableList()
                    val k = l.indexOf(label)
                    if (k >= 0) when (w) {
                        0 -> if (k > 0) { l.removeAt(k); l.add(k - 1, label) }
                        1 -> if (k < l.size - 1) { l.removeAt(k); l.add(k + 1, label) }
                        2 -> l.removeAt(k)
                    }
                    Store.setSpecies(cat, l)
                    saveSpeciesToServer()
                    manageSpecies(cat)
                }
                .show()
        }
    }

    private fun saveSpeciesToServer() {
        Cloud.saveSpecies { err -> showMessage(err ?: "어종 목록을 모두에게 저장했어요") }
    }

    /** 그룹에 정해진 낚시 종류만 보여줌 (그룹·로그인 없으면 전부) */
    private fun visibleCats(): List<String> {
        val types = Cloud.active?.types.orEmpty().filter { it in Store.CATS }
        return if (Cloud.user == null || types.isEmpty()) Store.CATS else Store.CATS.filter { it in types }
    }

    private fun renderCats() {
        val tripCat = Store.current?.cat?.ifBlank { null }
        val cats = visibleCats()
        if (tripCat == null && Store.category !in cats) { Store.category = cats.first(); return } // 바꾸면 화면이 다시 그려짐
        catRow.removeAllViews()
        val sel = tripCat ?: Store.category
        val shown = if (tripCat != null && tripCat !in cats) cats + tripCat else cats
        var line: LinearLayout? = null
        shown.forEachIndexed { i, c ->
            if (i % 3 == 0) {
                line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                catRow.addView(line, Ui.lp(this, MATCH, WRAP, top = if (i == 0) 0 else 8))
            }
            val on = c == sel
            line!!.addView(Ui.text(this, 14f, if (on) Ui.BG else Ui.TEXT, true, (if (on) "✓ " else "") + Store.catLabel(c)).apply {
                gravity = Gravity.CENTER; maxLines = 1
                minHeight = dp(42)
                background = if (on) Ui.bg(this@MainActivity, Ui.TEXT, 22) else Ui.bg(this@MainActivity, Ui.CARD, 22, Ui.LINE)
                setOnClickListener {
                    if (on) showMessage("지금 낚시 종류: ${Store.catLabel(c)}")
                    else if (Store.current != null) showMessage("출조 중에는 종류를 바꿀 수 없어요 (종료 후 변경)")
                    else { Store.category = c; showMessage("${Store.catLabel(c)}(으)로 바꿨어요") }
                }
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i % 3 > 0) leftMargin = dp(8) })
        }
        line?.let { l -> while (l.childCount < 3) l.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(8) }) }
    }

    /** 메인 물때 한 줄: "8물 · 사리" / "지금 날물 · 다음 간조 23:52 · 물세기 100%" */
    private var mainPct: Int? = null
    private fun refreshTideRow() {
        val now = System.currentTimeMillis()
        tideTitle.text = TideDialog.dayTitle(Tide.mulName(now).substringBefore(" ("), now, mainPct, true)
        val loc = bestLocation()
        paintSun(now, loc)
        if (loc == null) { tideSub.text = "위치를 알면 만조·간조가 보여요 · 눌러서 보기"; return }
        val st = Tide.stationFor(this, loc.first, loc.second)
        val key = (st?.code ?: "") + "|" + SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(Date(now))
        if (key == strengthKey && now - strengthAt < 10 * 60_000L) return
        strengthKey = key; strengthAt = now
        Tide.strengthAt(this, loc.first, loc.second, now) { day, stg ->
            if (isFinishing) return@strengthAt
            if (day == null) { tideSub.text = "물때를 불러오지 못했어요 · 눌러서 다시 보기"; return@strengthAt }
            val t = System.currentTimeMillis()
            val parts = mutableListOf<String>()
            Tide.rising(day, t)?.let { parts.add(if (it) "지금 들물" else "지금 날물") }
            Tide.extremes(day).firstOrNull { it.time > t }?.let {
                parts.add((if (it.high) "다음 만조 " else "다음 간조 ") + SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(it.time)))
            }
            stg?.let { parts.add("물세기 ${it.pct}%" + if (it.estimated) "(추정)" else "") }
            mainPct = stg?.pct
            tideTitle.text = TideDialog.dayTitle(Tide.mulName(t).substringBefore(" ("), t, mainPct, true)
            tideSub.text = parts.joinToString(" · ").ifBlank { "눌러서 물때 보기" }
            tideSub.setTextColor(if ((stg?.pct ?: 0) >= 80) Ui.ORANGE else Ui.SUB)
        }
    }

    /** 물때 칸 셋째 줄: "☀ 일출 06:52 · ☾ 일몰 17:41" */
    private fun paintSun(now: Long, loc: Pair<Double, Double>?) {
        val st = Sun.times(now, loc?.first, loc?.second)
        if (st == null) { tideSun.visibility = View.GONE; return }
        tideSun.visibility = View.VISIBLE
        val f = SimpleDateFormat("HH:mm", Locale.KOREA)
        val sb = android.text.SpannableStringBuilder()
        fun part(label: String, color: Int) {
            val s0 = sb.length
            sb.append(" $label ")
            sb.setSpan(android.text.style.ForegroundColorSpan(color), s0, sb.length, 0)
            sb.setSpan(android.text.style.BackgroundColorSpan(Color.argb(60, Color.red(color), Color.green(color), Color.blue(color))), s0, sb.length, 0)
            sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), s0, sb.length, 0)
        }
        part("☀ 일출 " + f.format(Date(st.rise)), Color.parseColor("#F2C14E"))
        sb.append("  ")
        part("☾ 일몰 " + f.format(Date(st.set)), Color.parseColor("#B9A6FF"))
        if (loc == null) sb.append("  (서해 중부 기준)")
        tideSun.text = sb
    }

    private fun openMap() {
        val t = Store.current ?: Store.history.firstOrNull()
        if (t == null) { showMessage("출조를 시작하면 지도에 경로와 조과 위치가 표시돼요"); return }
        MapDialog.showTrip(this, t)
    }

    /** 지금 위치(30분 안)를 알고 있는지 */
    @SuppressLint("MissingPermission")
    private fun freshLocation(): Boolean {
        if (Tracker.last != null) return true
        if (!Tracker.hasPermission(this)) return false
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).any { p ->
            try { lm.getLastKnownLocation(p)?.let { System.currentTimeMillis() - it.time < 30 * 60_000L } ?: false } catch (_: Exception) { false }
        }
    }

    @SuppressLint("MissingPermission")
    private fun bestLocation(): Pair<Double, Double>? {
        Tracker.last?.let { return it.latitude to it.longitude }
        Store.current?.anyLocation()?.let { return it }
        if (Tracker.hasPermission(this)) {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try { lm.getLastKnownLocation(p)?.let { return it.latitude to it.longitude } } catch (_: Exception) {}
            }
        }
        return Store.history.firstNotNullOfOrNull { it.anyLocation() }
    }

    private fun openTodayTide() {
        val loc = bestLocation()
        if (loc == null) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 3)
            showMessage("위치를 알아야 물때를 볼 수 있어요. 위치 허용 후 다시 눌러주세요")
            return
        }
        TideDialog.show(this, loc.first, loc.second, System.currentTimeMillis(), Store.current) {
            strengthKey = ""; refreshTideRow()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            if (Tracker.hasPermission(this)) {
                if (Store.current != null) RemoteService.start(this)
            } else {
                showMessage("위치 권한이 없으면 지도에 경로가 표시되지 않아요 (조과 기록은 정상)")
            }
        }
    }

    private fun placeRecent() {
        val want = if (Store.current != null) outerRecent else innerRecent
        if (recentBox.parent !== want) {
            (recentBox.parent as? LinearLayout)?.removeView(recentBox)
            want.addView(recentBox)
        }
    }

    private fun paintMenu() {
        if (!::menuBtn.isInitialized) return
        menuBox.visibility = if (Store.menuOpen) View.VISIBLE else View.GONE
        menuBtn.text = if (Store.menuOpen) "메뉴 접기 ▲" else "메뉴 ▼"
        menuBtn.setTextColor(Ui.TEXT); menuBtn.background = Ui.bg(this, Color.TRANSPARENT, 14, Ui.LINE)
    }

    private fun divider() = View(this).apply { setBackgroundColor(Color.parseColor("#2E4256")) }

    private val RED = Color.parseColor("#E8505B")

    /** 빨간 숫자 동그라미 (0 이면 숨김) */
    private fun redDot(n: Int): TextView = Ui.text(this, 11f, Color.WHITE, true, n.toString()).apply {
        gravity = Gravity.CENTER
        minWidth = dp(22)
        setPadding(dp(5), 0, dp(5), 0)
        background = Ui.bg(this@MainActivity, RED, 99, Ui.BG)
        visibility = if (n > 0) View.VISIBLE else View.GONE
    }

    private fun groupLabel(g: Cloud.Group) = if (g.name.trim().endsWith("그룹")) g.name else "${g.name} 그룹"

    /** 맨 아래 그룹 칸: 버튼(빨간 숫자) + 펼치면 그룹 바꾸기 · 새 소식 · 그룹 기능 */
    /** 그룹 칸 안 '낚시 종류' 칩 (그룹에 종류가 하나면 그 하나만) */
    private fun addCatSection() {
        val cats = visibleCats()
        groupBox.addView(Ui.text(this, 12f, Ui.SUB, false,
            if (cats.size <= 1) "낚시 종류 (이 그룹은 ${Store.catLabel(cats.firstOrNull() ?: Store.category)})" else "낚시 종류 (눌러서 바꾸기)"),
            Ui.lp(this, MATCH, WRAP, top = 14))
        (catRow.parent as? LinearLayout)?.removeView(catRow)
        groupBox.addView(catRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6); rightMargin = dp(8) })
        renderCats()
    }

    private fun renderGroupPanel() {
        if (!::groupBtn.isInitialized) return
        val g = if (Cloud.user == null) null else Cloud.active
        val total = GroupNews.total()
        val todayN = GroupNews.today(g?.id)
        val tomN = GroupNews.tomorrow(g?.id)
        // 강조: 안 본 새 글(빨강) → 내일 출조(초록) · 확인한 일정은 강조 없음
        val sched = when { tomN > 0 -> " · 내일 출조!"; todayN > 0 -> " · 오늘 출조"; else -> "" }
        val base = (if (groupOpen) "그룹 접기 ▲" else "그룹 ▼") + (g?.let { " · " + groupLabel(it) } ?: "")
        groupBtn.text = android.text.SpannableStringBuilder(base).apply {
            if (!groupOpen && sched.isNotEmpty()) {
                val s0 = length; append(sched)
                // 새 소식(빨강)이 있을 때도 일정은 초록 글씨로
                if (total > 0) setSpan(android.text.style.ForegroundColorSpan(Ui.GREEN), s0, length, 0)
            }
        }
        when {
            total > 0 -> { groupBtn.setTextColor(Color.parseColor("#FF8A92")); groupBtn.background = Ui.bg(this, Color.TRANSPARENT, 14, RED) }
            tomN > 0 -> { groupBtn.setTextColor(Ui.BG); groupBtn.background = Ui.bg(this, Ui.GREEN, 14) }
            else -> { groupBtn.setTextColor(Ui.TEXT); groupBtn.background = Ui.bg(this, Color.TRANSPARENT, 14, Ui.LINE) }
        }
        groupDot.text = total.toString()
        groupDot.visibility = if (total > 0) View.VISIBLE else View.GONE
        groupBox.visibility = if (groupOpen) View.VISIBLE else View.GONE
        if (!groupOpen) return
        groupBox.removeAllViews()
        val gs = if (Cloud.user == null) emptyList() else Cloud.groups
        if (gs.isEmpty()) {
            groupBox.addView(Ui.text(this, 13f, Ui.SUB, false,
                if (Cloud.user == null) "로그인하면 친구들과 그룹을 만들 수 있어요" else "아직 그룹이 없어요 · 그룹을 만들거나 초대 코드로 가입해 보세요"),
                Ui.lp(this, MATCH, WRAP, top = 12))
            addCatSection()
            groupBox.addView(Ui.button(this, "그룹 만들기 / 가입", true) {
                if (Cloud.user == null) account.signIn { refreshAccount() } else account.openGroups()
            }, Ui.lp(this, MATCH, WRAP, top = 8))
            return
        }
        // 내 그룹 (눌러서 바꾸기) + 그룹마다 빨간 숫자
        groupBox.addView(Ui.text(this, 12f, Ui.SUB, false, "내 그룹 (눌러서 바꾸기)"), Ui.lp(this, MATCH, WRAP, top = 12))
        // 가입한 그룹 모두: 두 칸씩 여러 줄 (옆으로 밀지 않아도 다 보이게)
        val activeId = g?.id
        var rowL: LinearLayout? = null
        gs.forEachIndexed { i, gr ->
            if (i % 2 == 0) {
                rowL = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
                groupBox.addView(rowL, Ui.lp(this, MATCH, WRAP, top = 2))
            }
            val on = gr.id == activeId
            val f = android.widget.FrameLayout(this).apply { clipChildren = false; clipToPadding = false; setPadding(0, dp(8), dp(8), 0) }
            f.addView(Ui.text(this, 14f, if (on) Color.parseColor("#08311F") else Ui.TEXT, true, (if (on) "✓ " else "") + groupLabel(gr)).apply {
                gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(10), 0, dp(10), 0)
                background = if (on) Ui.bg(this@MainActivity, Ui.GREEN, 99) else Ui.bg(this@MainActivity, Ui.CARD, 99, Ui.LINE)
                setOnClickListener {
                    if (on) return@setOnClickListener
                    if (Store.current != null && Store.current?.catches?.isNotEmpty() == true)
                        AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("'${gr.name}' 그룹으로 바꿀까요?")
                            .setMessage("지금 출조는 종료할 때 바뀐 그룹에 올라가요")
                            .setPositiveButton("바꾸기") { _, _ -> Cloud.setActive(gr.id); showMessage("'${gr.name}' 그룹으로 바꿨어요") }
                            .setNegativeButton("취소", null)
                            .show()
                    else { Cloud.setActive(gr.id); showMessage("'${gr.name}' 그룹으로 바꿨어요") }
                }
            }, android.widget.FrameLayout.LayoutParams(MATCH, dp(42)))
            val n = GroupNews.count(gr.id)
            f.addView(redDot(n), android.widget.FrameLayout.LayoutParams(WRAP, dp(20), Gravity.TOP or Gravity.END).apply { topMargin = -dp(8); rightMargin = -dp(8) })
            rowL!!.addView(f, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i % 2 == 1) leftMargin = dp(4) })
        }
        if (gs.size % 2 == 1) rowL?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(4) })
        // 그룹 만들기·가입: 한 줄 전체 폭 (흐린 테두리)
        groupBox.addView(Ui.text(this, 13f, Ui.SUB, true, "+ 그룹 만들기 · 가입").apply {
            gravity = Gravity.CENTER; minHeight = dp(40)
            background = Ui.bg(this@MainActivity, Color.TRANSPARENT, 99, Color.parseColor("#2E4256"))
            setOnClickListener { account.openGroups() }
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10); rightMargin = dp(8) })
        // 낚시 종류 (그룹에 정해진 종류만)
        addCatSection()

        // 새 소식 (지금 그룹)
        val news = GroupNews.items(activeId)
        if (news.isNotEmpty() && g != null) {
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(Ui.text(this, 15f, Ui.TEXT, true, "${groupLabel(g)} 새 소식 ${news.size}"), LinearLayout.LayoutParams(0, WRAP, 1f))
            head.addView(Ui.text(this, 12f, Ui.TEXT, true, "모두 확인").apply {
                gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0)
                background = Ui.bg(this@MainActivity, Color.TRANSPARENT, 99, Ui.LINE)
                setOnClickListener { GroupNews.markSeen(news); renderGroupPanel() }
            }, LinearLayout.LayoutParams(WRAP, dp(34)))
            groupBox.addView(head, Ui.lp(this, MATCH, WRAP, top = 14))
            news.take(8).forEach { it -> groupBox.addView(newsCard(it), Ui.lp(this, MATCH, WRAP, top = 8)) }
            if (news.size > 8) groupBox.addView(Ui.text(this, 12f, Ui.SUB, false, "그 밖에 ${news.size - 8}개 · 모두 확인으로 정리"), Ui.lp(this, MATCH, WRAP, top = 6))
        } else {
            groupBox.addView(Ui.text(this, 12f, Ui.SUB, false, "새 소식이 없어요 · 새 일정·공지가 생기면 빨간 숫자로 알려줘요"), Ui.lp(this, MATCH, WRAP, top = 12))
        }

        // 그룹 기능 버튼 (두 칸씩)
        fun gbtn(label: String, strong: Boolean = false, onClick: () -> Unit) = Ui.text(this, 13f, if (strong) Ui.GREEN else Ui.TEXT, true, label).apply {
            gravity = Gravity.CENTER; minHeight = dp(44); setPadding(dp(6), 0, dp(6), 0)
            background = Ui.bg(this@MainActivity, Color.TRANSPARENT, 99, if (strong) Ui.GREEN else Ui.LINE)
            setOnClickListener { onClick() }
        }
        fun pair(a: TextView, b: TextView?) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(a, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (b != null) r.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
            groupBox.addView(r, Ui.lp(this, MATCH, WRAP, top = 8))
        }
        val up = GroupNews.upcoming(activeId)
        val newEv = GroupNews.newEvents(activeId)
        val newNo = GroupNews.newNotices(activeId)
        val noCount = GroupNews.notices(activeId)
        groupBox.addView(View(this), Ui.lp(this, MATCH, dp(6)))
        // 공지: 새 공지가 있을 때만 노랑 강조 (확인하면 꺼짐)
        val noticeBtn = gbtn(if (noCount > 0) "공지 ($noCount)" else "공지") { startActivity(Intent(this, NoticeActivity::class.java)) }
        if (newNo > 0) { noticeBtn.setTextColor(Color.parseColor("#F2C14E")); noticeBtn.background = Ui.bg(this, Color.TRANSPARENT, 99, Color.parseColor("#F2C14E")) }
        // 일정: 내일 출조면 초록 꽉 채움, 안 본 새 일정이면 초록 테두리, 확인했으면 기본 색
        val schedLabel = when { tomN > 0 -> "일정 · 내일 출조!"; todayN > 0 -> "일정 · 오늘 출조"; up > 0 -> "일정 ($up)"; else -> "일정" }
        val schedBtn = gbtn(schedLabel, newEv > 0) { startActivity(Intent(this, ScheduleActivity::class.java)) }
        if (tomN > 0) { schedBtn.setTextColor(Ui.BG); schedBtn.background = Ui.bg(this, Ui.GREEN, 99) }
        pair(noticeBtn, schedBtn)
        pair(gbtn("그룹 출조 기록") { startActivity(Intent(this, GroupTripsActivity::class.java)) },
            gbtn("같이 낚시 중") { showMates() })
        pair(gbtn("출조 달력 · 멤버 · 그룹 관리·초대") { startActivity(Intent(this, GroupActivity::class.java)) }, null)
    }

    /** 새 소식 카드: 일정(하늘) / 공지(노랑) + 확인 */
    private fun newsCard(it: GroupNews.Item): View {
        val col = if (it.isNotice) Color.parseColor("#F2C14E") else Ui.BLUE
        val ink = if (it.isNotice) Color.parseColor("#3A2A02") else Color.parseColor("#06303A")
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(10), dp(10))
            background = Ui.bg(this@MainActivity, Ui.CARD, 14, col)
            isClickable = true
            setOnClickListener { _ ->
                startActivity(Intent(this@MainActivity, if (it.isNotice) NoticeActivity::class.java else ScheduleActivity::class.java))
            }
        }
        val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line.addView(Ui.text(this, 11f, ink, true, if (it.isNotice) "공지" else "일정").apply {
            setPadding(dp(6), dp(1), dp(6), dp(1)); background = Ui.bg(this@MainActivity, col, 6)
        })
        line.addView(Ui.text(this, 14f, Ui.TEXT, true, it.title).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(6) })
        txt.addView(line)
        txt.addView(Ui.text(this, 11f, Ui.SUB, false, it.sub), Ui.lp(this, MATCH, WRAP, top = 3))
        card.addView(txt, LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(Ui.text(this, 12f, ink, true, "확인").apply {
            gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0)
            background = Ui.bg(this@MainActivity, col, 99)
            setOnClickListener { _ -> GroupNews.markSeen(listOf(it)); renderGroupPanel() }
        }, LinearLayout.LayoutParams(WRAP, dp(34)).apply { leftMargin = dp(8) })
        return card
    }

    /** 같이 낚시 중 (출조 중일 때 50m 안 그룹원) */
    private fun showMates() {
        if (Store.current == null) { showMessage("출조 중일 때 50m 안에서 같이 출조 중인 그룹원이 보여요"); return }
        Live.sync(true)
        val ms = Live.mates
        val msg = if (ms.isEmpty()) "지금 50m 안에 같이 출조 중인 그룹원이 없어요 (5분마다 확인)"
        else ms.joinToString("\n") { m ->
            String.format(Locale.KOREA, "%s · %s %d · %s %d · 약 %.0fm", m.name,
                m.names.getOrElse(0) { "1번" }, m.c1, m.names.getOrElse(1) { "2번" }, m.c2, m.meters)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("같이 낚시 중")
            .setMessage(msg)
            .setPositiveButton("확인", null).show()
    }

    /** 워치 기록을 켰을 때: 워치 음악 화면 자동 실행 설정 안내 */
    private fun showWatchGuide() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("갤럭시 워치로 기록")
            .setMessage("출조 중에 워치의 음악(미디어 컨트롤러) 화면에서\n⏮ (왼쪽) = 1번 어종 +1, ⏭ (오른쪽) = 2번 어종 +1\n\n" +
                "출조를 시작하면 워치에 음악 화면이 자동으로 뜨게 하려면:\n" +
                "Galaxy Wearable 앱 → 워치 설정 → 앱 → 미디어 컨트롤러 → '미디어 컨트롤러 자동 실행' 켜기\n" +
                "(메뉴 이름은 버전마다 조금 다를 수 있어요)")
            .setPositiveButton("Galaxy Wearable 열기") { _, _ ->
                val i = packageManager.getLaunchIntentForPackage("com.samsung.android.app.watchmanager")
                if (i != null) startActivity(i) else showMessage("Galaxy Wearable 앱을 찾지 못했어요")
            }
            .setNegativeButton("확인", null)
            .show()
    }

    private fun refreshAccount() {
        if (!::accountInfo.isInitialized) return
        renderGroupPanel()
        GroupNews.load(false) { if (!isFinishing) renderGroupPanel() }
        val u = Cloud.user
        if (u == null) {
            accountInfo.text = "친구와 조과 공유 · 로그인하면 그룹에 올리고, 내 기록도 서버에 백업돼요"
            accountBtn.text = "구글 로그인"
        } else {
            val g = Cloud.active
            accountInfo.text = if (g == null) "${Cloud.displayName} · 아직 그룹이 없어요"
            else "그룹 '${g.name}' · 초대 코드 ${g.code}  ›\n눌러서 멤버 · 출조 달력 보기"
            accountInfo.background = if (g == null) null else Ui.bg(this, Color.parseColor("#1C3A4F"), 10, Ui.LINE)
            accountInfo.setPadding(dp(12), dp(10), dp(12), dp(10))
            accountBtn.text = if (g == null) "그룹 만들기 / 가입" else "그룹 · 친구 출조 달력"
        }
        if (hideRouteBox.isChecked != Store.hideRoute) hideRouteBox.isChecked = Store.hideRoute
        adminBtn.visibility = if (Cloud.isAdmin) View.VISIBLE else View.GONE
        speciesBtn.visibility = if (Cloud.isAdmin) View.VISIBLE else View.GONE
        GroupNews.startLive(newsLive)
        renderCats()
    }

    /** 카운터 아래: 같이 낚시 중인 그룹원 마릿수 */
    private fun renderLive() {
        if (!::liveBox.isInitialized) return
        liveBox.removeAllViews()
        val ms = Live.mates
        if (Store.current == null || ms.isEmpty()) { liveBox.visibility = View.GONE; return }
        liveBox.visibility = View.VISIBLE
        val ago = ((System.currentTimeMillis() - Live.updatedAt) / 60_000L).coerceAtLeast(0)
        liveBox.addView(Ui.text(this, 12f, Ui.GREEN, true,
            "같이 낚시 중 (50m 안) · " + (if (ago == 0L) "방금 갱신" else "${ago}분 전 갱신")))
        // 친구 돌파 축하 띠 (1분 동안)
        Live.cheer?.takeIf { System.currentTimeMillis() - it.time < 60_000L }?.let { ch ->
            liveBox.addView(Ui.text(this, 13f, Color.parseColor("#412402"), true, "🎉 ${ch.text}").apply {
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = Ui.bg(this@MainActivity, Color.parseColor("#F2C14E"), 10)
            }, Ui.lp(this, MATCH, WRAP, top = 6))
            handler.removeCallbacks(liveRefresh)
            handler.postDelayed(liveRefresh, 61_000L - (System.currentTimeMillis() - ch.time))
        }
        ms.forEach { m ->
            val old = (System.currentTimeMillis() - m.time) > 15 * 60_000L
            val best = maxOf(Milestone.tier(m.c1), Milestone.tier(m.c2))
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
                setOnClickListener {
                    showMessage(String.format(Locale.KOREA, "%s · 약 %.0fm 거리 · %s 기준",
                        m.name, m.meters, SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(m.time))))
                }
            }
            // 이름표: 그 친구의 지금 단계 테두리
            row.addView(Ui.text(this, 13f, if (old) Ui.SUB else Ui.TEXT, true, m.name).apply {
                setPadding(dp(8), dp(1), dp(8), dp(1))
                background = if (best >= 0) Ui.bg(this@MainActivity, Color.TRANSPARENT, 99, Milestone.tierColor(best)) else null
            })
            row.addView(Ui.text(this, 13f, if (old) Ui.SUB else Ui.TEXT, false,
                "  " + listOf(m.names.getOrElse(0) { "1번" } to m.c1, m.names.getOrElse(1) { "2번" } to m.c2)
                    .joinToString(" · ") { "${it.first} ${it.second}" } +
                    (if (old) "  (${(System.currentTimeMillis() - m.time) / 60_000L}분 전)" else "")), LinearLayout.LayoutParams(0, WRAP, 1f))
            if (best >= 0) row.addView(Ui.text(this, 11f, Milestone.tierColor(best), true, Milestone.titleWithCrown(best)))
            liveBox.addView(row)
        }
    }

    private val liveRefresh = Runnable { renderLive() }

    private fun showMessage(msg: String) {
        message.text = msg
        message.visibility = View.VISIBLE
        handler.removeCallbacks(hideMessage)
        if (ButtonRouter.learningStep == 0) handler.postDelayed(hideMessage, 4000)
        refreshRemote()
    }

    private val hideMessage = Runnable { message.visibility = View.GONE }

    // ---------------- 화면 갱신 ----------------

    private var lastMinute = -1L
    private fun updateStatus() {
        val t = Store.current
        val minute = System.currentTimeMillis() / 60_000L
        if (minute != lastMinute && ::last1.isInitialized) { lastMinute = minute; last1.text = lastText(t, 1); last2.text = lastText(t, 2) }
        status.text = if (t == null) {
            "대기 중 · ${Store.catLabel(Store.category)} · '출조 시작'을 누르면 기록을 시작해요"
        } else {
            val sec = (System.currentTimeMillis() - t.start) / 1000
            String.format(Locale.KOREA, "출조 중 · %s · %s 시작 · 경과 %02d:%02d:%02d%s",
                Store.catShort(t.cat.ifBlank { Store.category }), timeFmt.format(Date(t.start)).substring(0, 5), sec / 3600, (sec / 60) % 60, sec % 60,
                if (Tracker.running) " · 위치 기록 중" else "")
        }
    }

    private fun refreshRemote() {
        if (lastLearning != ButtonRouter.learningStep) { lastLearning = ButtonRouter.learningStep; renderMethods() }
    }
    private var lastLearning = -1

    private fun refresh() {
        updateStatus()
        refreshRemote()
        renderCats()
        refreshTideRow()
        val t = Store.current
        placeText.visibility = if (t == null) View.GONE else View.VISIBLE
        placeText.text = when {
            t == null -> ""
            t.place().isBlank() -> "배·항구 입력하기 ✎"
            else -> t.place() + "  ✎"
        }
        name1.text = t?.name(1) ?: Store.defaultName(1)
        name2.text = t?.name(2) ?: Store.defaultName(2)
        art1.species = name1.text.toString(); art2.species = name2.text.toString()
        renderMethods()
        count1.text = (t?.count(1) ?: 0).toString()
        count2.text = (t?.count(2) ?: 0).toString()
        paintTier(1, t?.count(1) ?: 0); paintTier(2, t?.count(2) ?: 0)
        last1.text = lastText(t, 1)
        last2.text = lastText(t, 2)
        tripBtn.text = if (t == null) "출조 시작" else "출조 종료"
        undoBtn.visibility = if (t == null) View.GONE else View.VISIBLE
        placeRecent()
        renderLive()

        recent.text = if (t == null || t.catches.isEmpty()) {
            "아직 기록이 없어요"
        } else {
            val sb = StringBuilder("총 ${t.catches.size}마리\n")
            t.catches.asReversed().take(30).forEachIndexed { i, c ->
                val nth = t.catches.subList(0, t.catches.size - i).count { it.slot == c.slot }
                sb.append(timeFmt.format(Date(c.time))).append("  ")
                    .append(if (c.slot == 1) "●" else "○").append(" ")
                    .append(t.name(c.slot)).append("  ").append(nth).append("번째\n")
            }
            sb.toString().trimEnd()
        }
        historyBtn.text = "지난 출조 · 내 기록 보관함 (${Store.history.size})"
    }
}
