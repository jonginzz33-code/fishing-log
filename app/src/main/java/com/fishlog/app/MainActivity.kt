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
    /** 그룹 창 '현재 그룹 변경' 펼침 */
    private var groupSwitchOpen = false
    private var groupsAt = 0L
    private lateinit var message: TextView
    private lateinit var menuBtn: TextView
    private lateinit var menuBox: LinearLayout
    private lateinit var recentBox: LinearLayout
    private lateinit var outerRecent: LinearLayout
    private lateinit var innerRecent: LinearLayout
    private lateinit var catRow: LinearLayout
    private lateinit var methodBox: LinearLayout
    private var methodsOpen = false
    private var quick: QuickButtons? = null
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
    private lateinit var recentAllBtn: TextView
    private var allDialog: AlertDialog? = null
    private var allBox: LinearLayout? = null
    private lateinit var historyBtn: TextView
    private lateinit var accountInfo: TextView
    private lateinit var accountBtn: TextView
    private lateinit var bandBox: LinearLayout
    private lateinit var scroller: ScrollView
    private lateinit var groupFrame: View
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
    /** 관리자가 막은 옛날 버전인지 (출조 중엔 막지 않고, 끝난 뒤에) */
    private var forceUpdate = false
    private lateinit var chatBtn: TextView
    private lateinit var chatBadge: TextView
    private var chatOnline = 0L
    private lateinit var inqBadge: TextView
    private lateinit var headIcon: android.widget.FrameLayout
    private lateinit var headGroupName: TextView
    /** 맨 위 제목 왼쪽: 지금 그룹 대표 이미지 + 그룹 이름 */
    private fun paintHeadGroup() {
        if (!::headIcon.isInitialized) return
        headIcon.removeAllViews()
        val g = if (Cloud.user == null) null else Cloud.active
        if (g == null) { headIcon.visibility = View.GONE; headGroupName.visibility = View.GONE; return }
        headIcon.visibility = View.VISIBLE
        headIcon.addView(GroupIcon.view(this, g, 36, GroupBand.catIcon(GroupBand.currentCat(g))), android.widget.FrameLayout.LayoutParams(dp(36), dp(36)))
        headGroupName.text = g.title; headGroupName.visibility = View.VISIBLE
    }
    private fun paintInquiry() {
        if (!::inqBadge.isInitialized || Cloud.user == null) return
        Inquiry.unread { n -> if (!isFinishing) { inqBadge.text = "$n"; inqBadge.visibility = if (n > 0) View.VISIBLE else View.GONE } }
    }
    private val chatListener: () -> Unit = { paintChat(); renderGroupPanel() }

    /** 채팅 버튼: 접속 인원 + 안 읽은 글 수 */
    private fun paintChat() {
        if (!::chatBtn.isInitialized) return
        val gold = Color.parseColor("#F2C14E")
        chatBtn.text = android.text.SpannableStringBuilder("💬 채팅\n").apply {
            setSpan(android.text.style.ForegroundColorSpan(gold), 0, length, 0)
            val s0 = length; append(if (Cloud.user == null) "로그인" else if (chatOnline > 0) "접속 ${chatOnline}명" else "실시간")
            setSpan(android.text.style.AbsoluteSizeSpan(10, true), s0, length, 0)
            setSpan(android.text.style.ForegroundColorSpan(gold), s0, length, 0)
        }
        val n = if (Cloud.user == null) 0 else Chat.totalUnread()
        chatBadge.text = if (n > 99) "99+" else "$n"
        chatBadge.visibility = if (n > 0) View.VISIBLE else View.GONE
    }
    private var guideOpen = false
    private lateinit var versionText: TextView

    private fun checkForced() {
        if (!forceUpdate || isFinishing || Store.current != null || guideOpen) return
        if (Updater.latestCode == null) Updater.check(this) { if (!isFinishing) Updater.showDialog(this, true) }
        else Updater.showDialog(this, true)
    }

    private fun paintVersion() {
        if (!::versionText.isInitialized) return
        versionText.text = "버전 ${BuildConfigInfo.VERSION}" + if (Updater.latestCode != null) "  ·  새 버전 있음 (눌러서 업데이트)" else ""
        versionText.setTextColor(if (Updater.latestCode != null) Ui.GREEN else Ui.SUB)
    }

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

    private var consentOpen = false
    override fun onResume() {
        super.onResume()
        // 처음 한 번: 위치·개인정보 동의 (동의해야 사용)
        if (!Consent.ok(this)) {
            if (!consentOpen) { consentOpen = true; Consent.show(this) { consentOpen = false; recreate() } }
            return
        }
        paintBoardSub(); paintNowBtn()
        resumeAfterLoc()
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
        paintInquiry()
        if (Cloud.user != null) {
            Chat.start(chatListener)
            Chat.startPresence { n -> chatOnline = n; paintChat() }
        }
        Milestone.addListener(milestoneListener)
        if (Store.current != null) { Live.sync(); Live.watch() }
        ButtonRouter.onMessage = { showMessage(it) }
        if (Store.current != null) RemoteService.start(this)
        maybeAskNick()
        if (!cloudLoaded && Cloud.user != null) {
            cloudLoaded = true
            Cloud.refresh()
            Cloud.loadMyPerms()
            Trash.checkNotices(this)
            Cloud.backupPending()
            Cloud.flushDeletes()
        }
        Cloud.loadSpecies()
        Tide.prune(this)
        Photos.cleanOldShareCopies(this)
        GroupNews.load(false) { if (!isFinishing) renderGroupPanel() }
        // 새 버전: 큰 창으로 알림 (한 번 켤 때마다 한 번) · 최소 버전보다 낮으면 업데이트해야 쓸 수 있음
        if (!Updater.checkedThisRun) {
            Updater.checkedThisRun = true
            Updater.check(this) { v ->
                if (v == null || isFinishing) return@check
                paintVersion()
                if (!forceUpdate && !guideOpen) Updater.showDialog(this, false)
            }
        }
        // 처음 쓰는 사람: 그림 안내 한 번 (다 보면 새 버전 창)
        if (!Store.guideSeen && !guideOpen) {
            guideOpen = true
            Guide.show(this) {
                guideOpen = false
                if (Updater.latestCode != null && !forceUpdate && !isFinishing) Updater.showDialog(this, false)
                checkForced()
            }
        }
        Cloud.loadMinVersion { min ->
            forceUpdate = min > 0 && Updater.currentCode(this) < min
            checkForced()
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
        Live.unwatch()
        GroupNews.stopLive(newsLive)
        Chat.stop(chatListener)
        Chat.stopPresence()
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

        // (새 버전은 맨 위 줄 대신 큰 창으로 알려요 · Updater.showDialog)

        // 상단: 왼쪽 지도·물때 버튼 + 제목
        val header = Ui.row(this)
        // 제목: 맨 왼쪽, 남는 폭에 맞춰 글자 크기 자동 (지도·채팅·?·⚙는 오른쪽)
        // 왼쪽: 지금 그룹 대표 이미지(누르면 그룹 창) + 작은 그룹 이름 + 제목
        headIcon = android.widget.FrameLayout(this).apply { setOnClickListener { openGroupPanel() } }
        header.addView(headIcon, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        headGroupName = Ui.text(this, 10f, Ui.GREEN, true, "").apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; visibility = View.GONE
            setOnClickListener { openGroupPanel() }
        }
        titleCol.addView(headGroupName)
        titleCol.addView(Ui.text(this, 26f, Ui.TEXT, true, "조과기록").apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(13, 30, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        header.addView(titleCol, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(6) })
        // 지도 버튼: 큰 글씨 '지도' + 작은 글씨 '그룹 · 현위치'
        header.addView(smallBtn("") { openMap() }.apply {
            text = android.text.SpannableStringBuilder("지도\n").apply {
                val s0 = length; append("그룹 · 현위치")
                setSpan(android.text.style.AbsoluteSizeSpan(10, true), s0, length, 0)
                setSpan(android.text.style.ForegroundColorSpan(Ui.GREEN), s0, length, 0)
            }
            setLineSpacing(0f, 0.95f)
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }, LinearLayout.LayoutParams(WRAP, dp(48)))
        // 채팅 버튼: '채팅' + 작은 글씨 '접속 N명', 안 읽은 글은 빨간 숫자
        // 빨간 숫자가 잘리지 않게: 위·오른쪽에 여유 칸을 두고 그 안에 숫자를 그림
        val chatBox = android.widget.FrameLayout(this).apply { clipChildren = false; clipToPadding = false; setPadding(0, dp(9), dp(7), dp(9)) }
        chatBtn = smallBtn("") { startActivity(Intent(this, ChatActivity::class.java)) }.apply {
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setLineSpacing(0f, 0.95f)
            background = Ui.bg(this@MainActivity, Color.parseColor("#2A2310"), 12, Color.parseColor("#F2C14E"))
        }
        chatBox.addView(chatBtn, android.widget.FrameLayout.LayoutParams(WRAP, dp(48)))
        chatBadge = Ui.text(this, 10f, Color.WHITE, true, "").apply {
            gravity = Gravity.CENTER; setPadding(dp(5), 0, dp(5), 0)
            background = Ui.bg(this@MainActivity, RED, 99, Ui.BG)
            visibility = View.GONE
        }
        chatBox.addView(chatBadge, android.widget.FrameLayout.LayoutParams(WRAP, dp(18), Gravity.TOP or Gravity.END).apply { topMargin = -dp(9); rightMargin = -dp(7) })
        header.addView(chatBox, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(4); rightMargin = dp(0) })
        paintChat()
        // 1:1 문의 (나와 운영자만): 채팅 옆 ✉, 안 읽은 답장은 빨간 점
        val inqBox = android.widget.FrameLayout(this).apply { clipChildren = false; clipToPadding = false; setPadding(0, dp(9), dp(7), dp(9)) }
        // 1:1 문의: 지도·채팅과 같은 글자 버튼 ('문의' + 작은 '관리자 1:1'), 답장 오면 빨간 숫자
        val purple = Color.parseColor("#CFC8FF")
        inqBox.addView(smallBtn("") {
            if (Cloud.user == null) showMessage("구글 로그인을 하면 운영자에게 1:1 문의를 할 수 있어요")
            else startActivity(Intent(this@MainActivity, InquiryActivity::class.java))
        }.apply {
            text = android.text.SpannableStringBuilder("문의\n").apply {
                val s0 = length; append("관리자 1:1")
                setSpan(android.text.style.AbsoluteSizeSpan(10, true), s0, length, 0)
            }
            setTextColor(purple); setLineSpacing(0f, 0.95f); setPadding(dp(8), dp(2), dp(8), dp(2))
            background = Ui.bg(this@MainActivity, Color.parseColor("#1E1A3A"), 12, Color.parseColor("#7C6BD6"))
        }, android.widget.FrameLayout.LayoutParams(WRAP, dp(48)))
        inqBadge = Ui.text(this, 10f, Color.WHITE, true, "").apply {
            gravity = Gravity.CENTER; setPadding(dp(5), 0, dp(5), 0)
            background = Ui.bg(this@MainActivity, RED, 99, Ui.BG); visibility = View.GONE
        }
        inqBox.addView(inqBadge, android.widget.FrameLayout.LayoutParams(WRAP, dp(18), Gravity.TOP or Gravity.END).apply { topMargin = -dp(9); rightMargin = -dp(7) })
        header.addView(inqBox, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(2) })
        // (✉ 문의가 들어가서 '?' 설명서는 ☰ 메뉴의 '사용 설명서'로)
        // 개인 설정 (톱니바퀴)
        header.addView(Ui.text(this, 19f, Ui.BLUE, true, "⚙").apply {
            gravity = Gravity.CENTER
            background = Ui.bg(this@MainActivity, Ui.CARD, 99, Ui.BLUE)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { leftMargin = dp(2) })
        col.addView(header)
        paintHeadGroup()

        // 출조 종류 칩은 맨 아래 '그룹' 칸 안에서 고름 (renderGroupPanel)
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
        // 출조 중: 내 출조를 누구에게 알리는지 (눌러서 바로 바꾸기)
        liveChip = Ui.text(this, 12f, Ui.GREEN, true, "").apply {
            setPadding(dp(10), dp(3), dp(10), dp(3)); visibility = View.GONE
            setOnClickListener {
                val box = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
                fun draw() { box.removeAllViews(); liveScopeViews(box) { draw() } }
                draw()
                val before = Store.liveScope + Store.liveGroups + Store.liveLoc
                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("내 출조 알리기")
                    .setView(ScrollView(this@MainActivity).apply { addView(box) })
                    .setPositiveButton("확인", null)
                    .setOnDismissListener {
                        if (Store.liveScope + Store.liveGroups + Store.liveLoc != before) {
                            Live.scopeChanged(); paintLiveChip()
                            showMessage("내 출조 알리기: ${Store.liveScopeLabel()}")
                        }
                    }.show()
            }
        }
        col.addView(liveChip, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })

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
        // 퀵 버튼 (3번 이상, 개수 제한 없음 · 터치로만)
        val quickBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(quickBox, Ui.lp(this, MATCH, WRAP, top = 0))
        quick = QuickButtons(this, quickBox, { showMessage(it) }) { title, done ->
            val cat = Store.category
            val list = Store.species(cat)
            BigList.show(this, title, list, listOf("+ 새 어종 추가", "⚙ 내 어종 목록 관리")) { i, label ->
                when {
                    i < list.size -> done(label)
                    i == list.size -> askNewSpecies(cat) { name -> done(name) }
                    else -> manageMySpecies(cat)
                }
            }
        }
        // 같이 출조 중인 그룹원 (50m 안, 5분마다)
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
        recentAllBtn = Ui.button(this, "전체 보기 ›") { openAllCatches() }.apply { minHeight = dp(42); textSize = 14f; visibility = View.GONE }
        recentBox.addView(recentAllBtn, Ui.lp(this, MATCH, WRAP, top = 8))
        outerRecent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(outerRecent, Ui.lp(this, MATCH, WRAP))

        // ---- 맨 아래: [그룹] [메뉴] 두 버튼 → 누르면 물때 보기 같은 큰 창 ----
        col.addView(divider(), Ui.lp(this, MATCH, dp(1), top = 20))
        // 세 줄: 그룹 · 메뉴 · 관리자 페이지 (한 줄에 하나씩) + 맨 위에 전체 조황 · 포인트 게시판
        val bottomRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        boardBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10))
            background = Ui.bg(this@MainActivity, Color.parseColor("#2A1A0C"), 16, Ui.ORANGE)
            isClickable = true
            setOnClickListener {
                if (Cloud.user == null) showMessage("구글 로그인을 하면 볼 수 있어요")
                else startActivity(Intent(this@MainActivity, BoardActivity::class.java))
            }
        }
        // 글자는 가운데 (오른쪽 › 와 같은 폭의 빈칸을 왼쪽에 둬서 정확히 가운데)
        boardBtn.addView(Ui.text(this, 16f, Color.TRANSPARENT, true, "›"))
        val bt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        bt.addView(Ui.text(this, 16f, Color.parseColor("#F2B27A"), true, "🌊 전체 조황 · 포인트 게시판").apply { gravity = Gravity.CENTER })
        boardSub = Ui.text(this, 11f, Color.parseColor("#C99A72"), false, "모든 사용자가 공개한 출조").apply { gravity = Gravity.CENTER }
        bt.addView(boardSub)
        boardBtn.addView(bt, LinearLayout.LayoutParams(0, WRAP, 1f))
        boardBtn.addView(Ui.text(this, 16f, Color.parseColor("#F2B27A"), true, "›"))
        bottomRow.addView(boardBtn, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        // 🟢 지금 출조 중 (내 그룹 + 전체 공유) — 게시판 바로 아래, 그룹 줄 위
        nowBtn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(14), dp(10), dp(14), dp(10))
            background = Ui.bg(this@MainActivity, Color.parseColor("#16352A"), 14, Ui.GREEN)
            isClickable = true; visibility = View.GONE
            setOnClickListener { openNowDialog() }
        }
        nowTitle = Ui.text(this, 16f, Color.parseColor("#9FE7C1"), true, "🟢 지금 출조 중").apply { gravity = Gravity.CENTER }
        nowSub = Ui.text(this, 11f, Color.parseColor("#C9D3DE"), false, "").apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        nowBtn.addView(nowTitle); nowBtn.addView(nowSub)
        bottomRow.addView(nowBtn, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        val gFrame = android.widget.FrameLayout(this).apply { clipChildren = false; clipToPadding = false; setPadding(0, dp(8), 0, 0) }
        groupBtn = Ui.button(this, "그룹") { openGroupDialog() }.apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 16f }
        gFrame.addView(groupBtn, android.widget.FrameLayout.LayoutParams(MATCH, WRAP))
        groupDot = redDot(0)
        gFrame.addView(groupDot, android.widget.FrameLayout.LayoutParams(WRAP, dp(22), Gravity.TOP or Gravity.END).apply { topMargin = -dp(8); rightMargin = dp(4) })
        bottomRow.addView(gFrame, LinearLayout.LayoutParams(MATCH, WRAP))
        groupFrame = gFrame
        groupBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        menuBtn = Ui.button(this, "☰ 메뉴") { openMenuDialog() }.apply { textSize = 16f }
        bottomRow.addView(menuBtn, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        // 총관리자(나)에게만: 관리자 페이지
        adminMenuBtn = Ui.button(this, "🛠 관리자 페이지") { openAdminMenu() }.apply {
            textSize = 16f
            setTextColor(Color.parseColor("#F2C14E")); background = Ui.bg(this@MainActivity, Color.parseColor("#2A2310"), 14, Color.parseColor("#F2C14E"))
            visibility = View.GONE
        }
        bottomRow.addView(adminMenuBtn, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        col.addView(bottomRow, Ui.lp(this, MATCH, WRAP, top = 8))

        // 메뉴 창 안에 들어갈 내용 (창을 열 때 붙임)
        menuBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        menuBox.addView(Ui.button(this, "⚙ 개인 설정 (닉네임 · 알림 · 같이 출조 거리)") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, Ui.lp(this, MATCH, WRAP))
        menuBox.addView(Ui.button(this, "❓ 사용 설명서 · 처음 안내 다시 보기") {
            startActivity(Intent(this, HelpActivity::class.java))
        }, Ui.lp(this, MATCH, WRAP, top = 10))
        innerRecent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        menuBox.addView(innerRecent, Ui.lp(this, MATCH, WRAP))

        menuBox.addView(Ui.button(this, "📦 나만의 재고표 (준비물 · 봉돌 수량)") { Inventory.show(this) }, Ui.lp(this, MATCH, WRAP, top = 10))
        menuBox.addView(Ui.button(this, "🗑 휴지통 (남이 지운 내 조과 · 30일)") {
            if (Cloud.user == null) showMessage("구글 로그인이 필요해요") else Trash.show(this)
        }, Ui.lp(this, MATCH, WRAP, top = 10))
        historyBtn = Ui.button(this, "지난 출조 · 내 기록 보관함") {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        menuBox.addView(historyBtn, Ui.lp(this, MATCH, WRAP, top = 12))

        // 지금 들어와 있는 그룹 (크게) · 로그인 전엔 로그인 버튼
        val acc = Ui.card(this)
        accountInfo = Ui.text(this, 12f, Ui.SUB, false, "지금 들어와 있는 그룹")
        bandBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        accountBtn = Ui.button(this, "구글 로그인") {
            when {
                Cloud.user == null -> account.signIn { refreshAccount() }
                else -> account.openGroups()
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
        acc.addView(bandBox, Ui.lp(this, MATCH, WRAP, top = 6))
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
        versionText = Ui.text(this, 12f, Ui.SUB, false, "버전 ${BuildConfigInfo.VERSION}").apply {
            minHeight = dp(36); gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { if (Updater.latestCode != null) { menuDialog?.dismiss(); Updater.showDialog(this@MainActivity, forceUpdate) } }
        }
        menuBox.addView(versionText, Ui.lp(this, MATCH, WRAP, top = 12))
        paintVersion()
        col.addView(View(this), Ui.lp(this, MATCH, dp(24)))
        paintMenu()

        scroller = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(col)
        }
        return scroller
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
        // 그림은 연하게, 이마의 어종 이름표(1번 빨강 띠 · 2번 청록 띠)는 진하게
        val art = SpeciesArtView(this).apply {
            this.ink = ink; artAlpha = 0.3f
            labelBg = if (slot == 1) Color.parseColor("#E8505B") else Color.parseColor("#0B6E86")
        }
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
    private val milestoneListener: (Int, Int, Int) -> Unit = lambda@{ slot, count, tier ->
        if (slot > 2) { showMessage("${Store.current?.name(slot) ?: ""} ${count}마리 돌파! ${Milestone.titleWithCrown(tier)}"); return@lambda }
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
            box.addView(Ui.text(this, 15f, Ui.TEXT, false, t.line(unit = "마리")))
            // 📸 대표 조과 사진 (찍기 · 앨범 · 나중에)
            val mp = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10))
                background = Ui.bg(this@MainActivity, Ui.CARD, 12, Ui.GREEN)
            }
            val prev = android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                background = Ui.bg(this@MainActivity, Color.parseColor("#0F2233"), 10, Ui.GREEN)
            }
            val prevTxt = Ui.text(this, 11f, Ui.GREEN, true, "📷\n사진 넣기").apply { gravity = Gravity.CENTER }
            val pf = android.widget.FrameLayout(this)
            pf.addView(prevTxt, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
            pf.addView(prev, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
            mp.addView(pf, LinearLayout.LayoutParams(dp(72), dp(72)))
            fun paintPrev() {
                val p = Store.current?.main()
                val b = p?.let { Photos.thumbBitmap(this, it) }
                prev.setImageBitmap(b); prev.visibility = if (b != null) View.VISIBLE else View.GONE
            }
            mainPrevPaint = { paintPrev() }
            paintPrev()
            val mcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            mcol.addView(Ui.text(this, 14f, Color.parseColor("#9FE7C1"), true, "📸 대표 조과 사진"))
            mcol.addView(Ui.text(this, 11f, Ui.SUB, false, "오늘 조과를 한 장으로 · 목록·게시판 오른쪽에 크게 보여요"), Ui.lp(this, MATCH, WRAP, top = 2))
            val mbtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun mbtn(label: String, click: () -> Unit) = Ui.text(this, 12f, Ui.TEXT, true, label).apply {
                gravity = Gravity.CENTER; setPadding(dp(10), dp(5), dp(10), dp(5))
                background = Ui.bg(this@MainActivity, Color.TRANSPARENT, 99, Ui.LINE); setOnClickListener { click() }
            }
            mbtns.addView(mbtn("📷 찍기") { MainPhoto.camera(this, t.id) })
            mbtns.addView(mbtn("🖼 앨범") { MainPhoto.album(this, t.id) }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
            mbtns.addView(Ui.text(this, 12f, Ui.SUB, false, "  나중에 출조 기록에서도 돼요"))
            mcol.addView(mbtns, Ui.lp(this, MATCH, WRAP, top = 6))
            mp.addView(mcol, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(10) })
            pf.setOnClickListener { MainPhoto.album(this, t.id) }
            box.addView(mp, Ui.lp(this, MATCH, WRAP, top = 10))
            val canShare = Cloud.user != null && t.catches.isNotEmpty()
            val groups = if (Cloud.user != null) Cloud.groups else emptyList()
            val catName = Store.catLabel(t.cat.ifBlank { Store.category })
            // 올릴 그룹 고르기 (지금 그룹만 기본 체크)
            val picks = LinkedHashMap<String, CheckBox>()
            var pubRef: CheckBox? = null
            /** 게시판에 같이 보일 그룹 (기본 = 지금 그룹) */
            var pubChoice = if (Cloud.user != null) Cloud.active?.id ?: "" else ""
            var drawOptsRef: (() -> Unit)? = null
            var optsRef: View? = null
            var okRef: android.widget.Button? = null
            /** 완료 버튼 글자: 게시판에 올리면 (게시판 업로드), 그룹에만 올리면 (그룹 업로드) */
            fun okLabel(): String {
                val pub = pubRef?.isChecked == true && pubRef?.visibility == View.VISIBLE
                val n = picks.values.count { it.isChecked }
                return "✓ 완료" + when { pub -> " (게시판 업로드)"; n > 0 -> " (그룹 업로드)"; else -> "" }
            }
            fun paintOk() { okRef?.text = okLabel() }
            fun titleText0(): String {
                val chosen = groups.filter { picks[it.id]?.isChecked == true }
                val where = when {
                    Cloud.user == null || groups.isEmpty() -> "그룹 없음 · $catName · 내 폰에만 저장"
                    chosen.isEmpty() -> "그룹에 안 올림 · $catName"
                    chosen.size == 1 -> "${chosen[0].title} · $catName"
                    else -> "그룹 ${chosen.size}곳 · $catName"
                }
                return "출조를 종료할까요? ($where)"
            }
            val sumView = Ui.text(this, 12f, Ui.SUB, false, "")
            var titleRef: TextView? = null
            fun refreshSum() {
                val n = picks.values.count { it.isChecked }
                val pub = pubRef?.isChecked == true && pubRef?.visibility == View.VISIBLE
                sumView.text = when {
                    !canShare -> ""
                    n == 0 && !pub -> "그룹에 안 올리고 내 폰에만 저장해요"
                    else -> (if (n > 0) "${n}개 그룹" else "") + (if (n > 0 && pub) " + " else "") + (if (pub) "전체 게시판" else "") + "에 올려요"
                }
            }
            if (canShare && groups.isNotEmpty()) {
                val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                head.addView(Ui.text(this, 14f, Ui.TEXT, true, "올릴 그룹 고르기"), LinearLayout.LayoutParams(0, WRAP, 1f))
                val allBtn = Ui.text(this, 12f, Ui.GREEN, true, "☐ 전체 선택").apply {
                    setPadding(dp(10), dp(4), dp(10), dp(4)); background = Ui.bg(this@MainActivity, Color.TRANSPARENT, 99, Color.parseColor("#2F6B52"))
                }
                head.addView(allBtn)
                box.addView(head, Ui.lp(this, MATCH, WRAP, top = 12))
                val list = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(2), dp(8), dp(2))
                    background = Ui.bg(this@MainActivity, Ui.CARD, 12)
                }
                fun syncAll() { allBtn.text = if (picks.values.all { it.isChecked }) "☑ 전체 선택" else "☐ 전체 선택" }
                groups.forEach { g ->
                    val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(46) }
                    val cb = CheckBox(this).apply {
                        buttonTintList = ColorStateList.valueOf(Ui.BLUE); isChecked = g.id == Cloud.active?.id
                        setOnCheckedChangeListener { _, _ -> syncAll(); refreshSum(); titleRef?.text = titleText0(); paintOk(); drawOptsRef?.invoke() }
                    }
                    picks[g.id] = cb
                    r.addView(cb)
                    r.addView(GroupIcon.view(this, g, 28, GroupBand.catIcon(GroupBand.currentCat(g))), LinearLayout.LayoutParams(dp(28), dp(28)))
                    r.addView(Ui.text(this, 14f, Ui.TEXT, true).apply { text = g.styled() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(10) })
                    if (g.id == Cloud.active?.id) r.addView(Ui.text(this, 11f, Ui.GREEN, false, "지금 그룹"))
                    r.setOnClickListener { cb.isChecked = !cb.isChecked }
                    list.addView(r)
                }
                allBtn.setOnClickListener { val all = !picks.values.all { it.isChecked }; picks.values.forEach { it.isChecked = all } }
                syncAll()
                box.addView(list, Ui.lp(this, MATCH, WRAP, top = 6))
            }
            val pubBox = CheckBox(this).apply {
                text = "전체 게시판에도 공개 (위치는 다음 창에서 정확/대략 선택, 경로·배 이름은 안 올라감)"
                setTextColor(Ui.TEXT); textSize = 14f
                buttonTintList = ColorStateList.valueOf(Ui.BLUE)
                isChecked = canShare
                visibility = if (canShare) View.VISIBLE else View.GONE
                setOnCheckedChangeListener { _, _ -> refreshSum(); optsRef?.visibility = if (isChecked) View.VISIBLE else View.GONE; paintOk() }
            }
            pubRef = pubBox
            box.addView(pubBox, Ui.lp(this, MATCH, WRAP, top = 8))
            // 게시판 표시 방법: 이름(익명/닉네임) · 그룹 표시(안 함/그룹) + 미리보기
            val opts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
                background = Ui.bg(this@MainActivity, Ui.CARD, 12, Color.parseColor("#F28C38"))
            }
            fun drawOpts() {
                opts.removeAllViews()
                val orange = Color.parseColor("#F2B27A")
                fun pill(label: CharSequence, on: Boolean, click: () -> Unit) = Ui.text(this, 13f, if (on) Ui.BG else Ui.TEXT, true).apply {
                    text = label; gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(6), 0, dp(6), 0)
                    background = if (on) Ui.bg(this@MainActivity, Ui.TEXT, 99) else Ui.bg(this@MainActivity, Color.TRANSPARENT, 99, Ui.LINE)
                    setOnClickListener { click(); drawOpts() }
                }
                fun two(a: View, b: View) = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(a, LinearLayout.LayoutParams(0, dp(38), 1f)); addView(b, LinearLayout.LayoutParams(0, dp(38), 1f).apply { leftMargin = dp(6) })
                }
                val anon = Store.nick == "" || Cloud.nick == null
                opts.addView(Ui.text(this, 13f, orange, true, "게시판에 보일 이름"))
                opts.addView(two(pill(if (anon) "✓ 익명" else "익명", anon) { Store.nick = "" },
                    pill((if (!anon) "✓ " else "") + "닉네임 (${Cloud.nick ?: "아직 없음"})", !anon) {
                        if (Cloud.nick == null) showMessage("닉네임이 없어요 · ⚙ 개인 설정에서 먼저 정해 주세요") else Store.nick = "me"
                    }), Ui.lp(this, MATCH, WRAP, top = 6))
                // 그룹 표시: 기본 = 이번 출조의 지금 그룹, 고를 수 있는 건 위에서 체크한 올릴 그룹만
                val checked = groups.filter { picks[it.id]?.isChecked == true }
                if (pubChoice != "" && checked.none { it.id == pubChoice }) pubChoice = checked.firstOrNull()?.id ?: ""
                val pg = checked.firstOrNull { it.id == pubChoice }
                opts.addView(Ui.text(this, 13f, orange, true, "그룹 표시"), Ui.lp(this, MATCH, WRAP, top = 10))
                val gLabel: CharSequence = if (pg == null) "그룹 고르기 ▾"
                    else android.text.SpannableStringBuilder("✓ ").append(GroupIcon.prefix(this, pg, 18, GroupBand.catIcon(GroupBand.currentCat(pg)))).append(pg.title)
                        .append(if (pg.id == Cloud.active?.id) " · 지금 그룹" else "").append(" ▾")
                opts.addView(two(pill(if (pg == null) "✓ 안 함" else "안 함", pg == null) { pubChoice = "" },
                    pill(gLabel, pg != null) {
                        if (checked.isEmpty()) { showMessage("위에서 올릴 그룹을 먼저 체크해 주세요"); return@pill }
                        if (checked.size == 1) { pubChoice = checked[0].id; return@pill }
                        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("게시판에 보일 그룹 (올릴 그룹 중)")
                            .setItems(checked.map { it.title }.toTypedArray()) { _, i -> pubChoice = checked[i].id; drawOpts() }
                            .show()
                    }), Ui.lp(this, MATCH, WRAP, top = 6))
                // 미리보기
                opts.addView(Ui.text(this, 11f, Ui.SUB, false, "게시판에서 이렇게 보여요"), Ui.lp(this, MATCH, WRAP, top = 10))
                val pv = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = Ui.bg(this@MainActivity, Ui.BG, 10)
                }
                if (pg != null) pv.addView(GroupIcon.view(this, pg, 28, GroupBand.catIcon(GroupBand.currentCat(pg))), LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(8) })
                val pvT = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                pvT.addView(Ui.text(this, 14f, Ui.TEXT, true).apply {
                    text = android.text.SpannableStringBuilder(if (Store.nick == "" || Cloud.nick == null) "익명" else Cloud.displayName).apply {
                        if (pg != null) { val st = length; append("  · ${pg.title}"); setSpan(android.text.style.ForegroundColorSpan(Ui.GREEN), st, length, 0)
                            setSpan(android.text.style.AbsoluteSizeSpan(11, true), st, length, 0) }
                    }
                })
                pvT.addView(Ui.text(this, 12f, Ui.SUB, false, t.line() + " · " + catName))
                pv.addView(pvT, LinearLayout.LayoutParams(0, WRAP, 1f))
                // 대표 사진도 미리보기에
                Store.current?.main()?.let { mp -> Photos.thumbBitmap(this, mp) }?.let { bm ->
                    pv.addView(android.widget.ImageView(this).apply {
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                        background = Ui.bg(this@MainActivity, Ui.CARD, 8); setImageBitmap(bm)
                    }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { leftMargin = dp(8) })
                }
                opts.addView(pv, Ui.lp(this, MATCH, WRAP, top = 4))
                opts.addView(Ui.text(this, 11f, Ui.SUB, false, "이름(익명·닉네임)은 지난번 고른 대로 기억해요"), Ui.lp(this, MATCH, WRAP, top = 4))
            }
            optsRef = opts
            drawOptsRef = { drawOpts() }
            drawOpts()
            opts.visibility = if (pubBox.isChecked && pubBox.visibility == View.VISIBLE) View.VISIBLE else View.GONE
            box.addView(opts, Ui.lp(this, MATCH, WRAP, top = 6))
            box.addView(sumView, Ui.lp(this, MATCH, WRAP, top = 4))
            refreshSum()
            // (이용료·꼭 필요한 물건은 출조 기록 화면에서 적어요)
            // 제목이 길어도 잘리지 않게 직접 만든 제목
            val titleView = Ui.text(this, 19f, Ui.TEXT, true, titleText0()).apply { setPadding(dp(22), dp(20), dp(22), dp(4)) }
            titleRef = titleView
            val dlg = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setCustomTitle(titleView)
                .setView(ScrollView(this).apply { addView(box) })
                .setPositiveButton(okLabel()) { _, _ ->
                    Store.pubGroup = pubChoice
                    val ended = Store.endTrip()
                    RemoteService.stop(this)
                    if (ended != null && Cloud.user != null) {
                        if (ended.catches.isNotEmpty()) {
                            val chosen = groups.filter { picks[it.id]?.isChecked == true }
                            if (chosen.isNotEmpty()) account.uploadAll(ended, chosen)
                        }
                        Cloud.backupPending()
                    }
                    // 그 출조 시간대 날씨를 출조 기록에 넣어 두기 (나중에 다시 안 불러오게)
                    if (ended != null) Weather.snapshot(this, ended) { wx -> if (wx != null) Store.setWx(ended.id, wx) }
                    if (ended != null) startActivity(Intent(this, TripActivity::class.java)
                        .putExtra("id", ended.id).putExtra("publish", pubBox.isChecked && pubBox.visibility == View.VISIBLE))
                }
                .setNegativeButton("취소", null)
                .create()
            dlg.show()
            // 완료 = 초록 강조
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.let { b ->
                okRef = b
                b.setTextColor(Color.parseColor("#08311F")); b.background = Ui.bg(this, Ui.GREEN, 99)
                b.setPadding(dp(16), 0, dp(16), 0); b.isAllCaps = false
            }
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.let { b -> b.setTextColor(Ui.TEXT); b.isAllCaps = false }
        }
    }

    /** 켜러 갔다 돌아오면 이어서 할 일 (출조 시작 · 위치 공유 켜기) */
    private var afterLoc: (() -> Unit)? = null

    /**
     * 위치가 꺼져 있으면 '📍 위치가 꺼져 있어요' 창: 앱 위치 사용 · 폰 GPS · 위치 권한 중 꺼진 것만 차례로 켜러 가기
     *  ok = 다 켜져 있거나 켜고 돌아왔을 때 · skip = '위치 없이 …' 눌렀을 때
     */
    private fun checkLocation(skipLabel: String, ok: () -> Unit, skip: () -> Unit) {
        val appOn = Store.appLoc; val gps = Tracker.gpsOn(this); val perm = Tracker.granted(this)
        if (appOn && gps && perm) { ok(); return }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), dp(4)) }
        box.addView(Ui.text(this, 13f, Ui.SUB, false, "출조 경로 · 조과 위치 · 위치 공유를 하려면 아래가 켜져 있어야 해요."))
        listOf("❶ 이 앱에서 내 위치 사용" to appOn, "❷ 폰 위치(GPS)" to gps, "❸ 위치 권한 (앱 허용)" to perm).forEach { (l, on) ->
            val r = Ui.row(this).apply { setPadding(dp(12), dp(9), dp(12), dp(9)); background = Ui.bg(this@MainActivity, Color.TRANSPARENT, 10, Ui.LINE) }
            r.addView(Ui.text(this, 13f, Ui.TEXT, false, l), LinearLayout.LayoutParams(0, WRAP, 1f))
            r.addView(Ui.text(this, 13f, if (on) Ui.GREEN else Color.parseColor("#FF8A92"), true, if (on) "켜짐" else "꺼짐"))
            box.addView(r, Ui.lp(this, MATCH, WRAP, top = 6))
        }
        val dlg = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("📍 위치가 꺼져 있어요")
            .setView(box)
            .setPositiveButton("켜러 가기") { _, _ ->
                afterLoc = ok
                when {
                    !appOn -> { showMessage("'📍 이 앱에서 내 위치 사용'을 켜고 돌아오세요"); startActivity(Intent(this, SettingsActivity::class.java)) }
                    !gps -> { showMessage("위치(GPS)를 켜고 돌아오세요"); try { startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) {} }
                    else -> requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 2)
                }
            }
            .setNeutralButton(skipLabel) { _, _ -> afterLoc = null; skip() }
            .setNegativeButton("취소") { _, _ -> afterLoc = null }
            .show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.let { b -> b.setTextColor(Ui.GREEN); b.isAllCaps = false }
    }

    /** 켜러 갔다가 돌아왔을 때: 다 켜졌으면 이어서, 아직 꺼진 게 있으면 다시 안내 */
    private fun resumeAfterLoc() {
        val next = afterLoc ?: return
        afterLoc = null
        if (Store.appLoc && Tracker.gpsOn(this) && Tracker.granted(this)) next()
        else window.decorView.postDelayed({ if (!isFinishing) checkLocation("위치 없이 계속", next) { next() } }, 300)
    }

    private fun startTrip(boat: String, port: String) {
        if (Store.current != null) return
        checkLocation("위치 없이 출조 시작", { startTripNow(boat, port) }) { startTripNow(boat, port) }
    }

    private fun startTripNow(boat: String, port: String) {
        if (Store.current != null) return
        Live.sharedGid = Cloud.active?.id
        Store.startTrip(boat, port)
        RemoteService.start(this)
        bestLocation()?.let { Tide.prefetch(this, it.first, it.second); Weather.prefetch(this, it.first, it.second) }
        showMessage("출조 시작! 화면을 꺼도 리모컨으로 기록돼요")
    }

    private fun suggestField(hint: String, items: List<String>, value: String): AutoCompleteTextView =
        AutoCompleteTextView(this).apply {
            this.hint = hint
            setHintTextColor(Color.parseColor("#6E7A86"))
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
        // 출조 시작: 지금 그룹의 출조 종류를 눌러서 고르기
        if (tripId == null) {
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val g = if (Cloud.user == null) null else Cloud.active
            if (g != null) head.addView(GroupIcon.view(this, g, 24, GroupBand.catIcon(GroupBand.currentCat(g))), LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(8) })
            head.addView(Ui.text(this, 14f, Ui.TEXT, true, "출조 종류"))
            head.addView(Ui.text(this, 12f, Ui.SUB, false, if (g != null) "  · ${g.title}의 종류" else "  · 전체 종류"))
            box.addView(head)
            val cats = visibleCats()
            val chipBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            fun drawChips() {
                chipBox.removeAllViews()
                var line: LinearLayout? = null
                cats.forEachIndexed { i, c ->
                    if (i % 3 == 0) { line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; chipBox.addView(line, Ui.lp(this, MATCH, WRAP, top = 6)) }
                    val on = Store.category == c
                    line!!.addView(Ui.text(this, 13f, if (on) Ui.BG else Ui.TEXT, true, (if (on) "✓ " else "") + Store.catLabel(c)).apply {
                        gravity = Gravity.CENTER; maxLines = 1
                        setAutoSizeTextTypeUniformWithConfiguration(9, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                        background = if (on) Ui.bg(this@MainActivity, Ui.TEXT, 99) else Ui.bg(this@MainActivity, Ui.CARD, 99, Ui.LINE)
                        setOnClickListener { if (Store.category != c) { Store.category = c; drawChips() } }
                    }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { if (i % 3 > 0) leftMargin = dp(6) })
                }
                val rest = cats.size % 3
                if (rest != 0) repeat(3 - rest) { line?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(6) }) }
            }
            drawChips()
            box.addView(chipBox, Ui.lp(this, MATCH, WRAP, top = 2))
        }
        // 🟢 내 출조 알리기 (출조 시작 때만)
        val scopeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10))
            background = Ui.bg(this@MainActivity, Ui.CARD, 12, Ui.GREEN)
        }
        fun drawScope() {
            scopeBox.removeAllViews()
            scopeBox.addView(Ui.text(this, 14f, Color.parseColor("#9FE7C1"), true, "🟢 내 출조 알리기"))
            liveScopeViews(scopeBox) { drawScope() }
            scopeBox.addView(Ui.text(this, 11f, Ui.SUB, false, "지난번 고른 대로 기억 · 출조 중에 메인에서 바로 바꾸기"), Ui.lp(this, MATCH, WRAP, top = 6))
        }
        if (tripId == null && Cloud.user != null && Cloud.groups.isNotEmpty()) {
            drawScope()
            box.addView(scopeBox, Ui.lp(this, MATCH, WRAP, top = 12))
        }
        // 칸 위 작은 제목 + 칸 안 흐린 예시
        val boat = suggestField("예: 테티스호, 하나개 포인트", Store.boats(), t?.boat ?: Store.boats().firstOrNull() ?: "")
        val port = suggestField("예: 영흥도 진두항, 하나개 주차장", Store.ports(), t?.port ?: Store.ports().firstOrNull() ?: "")
        box.addView(Ui.text(this, 12f, Ui.SUB, false, "배 이름 · 포인트"), Ui.lp(this, MATCH, WRAP, top = if (tripId == null) 14 else 0))
        box.addView(boat)
        box.addView(Ui.text(this, 12f, Ui.SUB, false, "항구 · 주요 장소"), Ui.lp(this, MATCH, WRAP, top = 10))
        box.addView(port)
        val b = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (tripId == null) "출조 시작 · 배와 항구" else "배와 항구 수정")
            .setMessage("한 번 적은 이름은 목록에 저장돼서 다음부터 고를 수 있어요")
            .setView(ScrollView(this).apply { addView(box) })
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

    /** 버튼 어종: 지금 출조 종류의 어종 목록에서 고르기 */
    private fun askRename(slot: Int) {
        val cat = Store.current?.cat?.ifBlank { null } ?: Store.category
        val list = Store.species(cat)
        BigList.show(this, "버튼 $slot 어종 (${Store.catLabel(cat)})", list, listOf("+ 새 어종 추가", "⚙ 내 어종 목록 관리")) { i, label ->
            when {
                i < list.size -> Store.rename(slot, label)
                i == list.size -> askNewSpecies(cat) { name -> Store.rename(slot, name) }
                else -> manageMySpecies(cat)
            }
        }
    }

    private fun askNewSpecies(cat: String, then: ((String) -> Unit)? = null) {
        val input = EditText(this).apply { hint = "어종 이름"; inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("${Store.catLabel(cat)} 어종 추가" + if (Cloud.isAdmin) " (모든 사용자)" else " (내 목록)")
            .setView(input)
            .setPositiveButton("추가") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isEmpty()) return@setPositiveButton
                // 관리자: 모두의 목록에 · 일반 사용자: 내 목록에만
                if (Cloud.isAdmin) { Store.addSpecies(cat, n); saveSpeciesToServer() }
                else { Store.addMySpecies(cat, n); showMessage("'$n'을(를) 내 어종 목록에 추가했어요 (나만 보여요)") }
                then?.invoke(n)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 종류별 어종 관리: 추가, 순서, 삭제 */
    private fun manageSpecies(cat: String) {
        val list = Store.adminSpecies(cat)
        BigList.show(this, "${Store.catLabel(cat)} 어종 (눌러서 순서·삭제)", list, listOf("+ 새 어종 추가")) { i, label ->
            if (i >= list.size) { askNewSpecies(cat) { manageSpecies(cat) }; return@show }
            val opts = arrayOf("위로 올리기", "아래로 내리기", "삭제")
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(label)
                .setItems(opts) { _, w ->
                    val l = Store.adminSpecies(cat).toMutableList()
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

    /** 내 어종 목록 관리 (나만): 지우기 · 다시 보이기 · 추가 — 업데이트해도 그대로 */
    private fun manageMySpecies(cat: String) {
        val list = Store.species(cat)
        val hidden = Store.myHidden(cat)
        val mine = Store.myAdded(cat)
        val labels = list.map { if (it in mine) "$it  (내가 추가)" else it }
        val tail = mutableListOf("+ 새 어종 추가")
        if (hidden.isNotEmpty()) tail.add("↺ 지운 어종 다시 보이기 (${hidden.size})")
        BigList.show(this, "내 ${Store.catLabel(cat)} 어종 (눌러서 지우기)", labels, tail) { i, _ ->
            when {
                i < list.size -> AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("'${list[i]}'을(를) 내 목록에서 지울까요?")
                    .setMessage("내 폰에서만 안 보여요 · 다른 사람 목록과 지난 기록은 그대로 · 나중에 다시 보이게 할 수 있어요")
                    .setPositiveButton("지우기") { _, _ -> Store.removeMySpecies(cat, list[i]); manageMySpecies(cat) }
                    .setNegativeButton("취소", null).show()
                i == list.size -> askNewSpecies(cat) { manageMySpecies(cat) }
                else -> BigList.show(this, "다시 보이게 할 어종", hidden, emptyList()) { k, _ ->
                    hidden.getOrNull(k)?.let { Store.unhideSpecies(cat, it); showMessage("'$it' 다시 보여요") }
                    manageMySpecies(cat)
                }
            }
        }
    }

    private fun saveSpeciesToServer() {
        Cloud.saveSpecies { err -> showMessage(err ?: "어종 목록을 모두에게 저장했어요") }
    }

    /** 그룹에 정해진 출조 종류만 보여줌 (그룹·로그인 없으면 전부) */
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
                catRow.addView(line, Ui.lp(this, MATCH, dp(44), top = if (i == 0) 0 else 8))
            }
            val on = c == sel
            line!!.addView(Ui.text(this, 14f, if (on) Ui.BG else Ui.TEXT, true, (if (on) "✓ " else "") + Store.catLabel(c)).apply {
                gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                background = if (on) Ui.bg(this@MainActivity, Ui.TEXT, 22) else Ui.bg(this@MainActivity, Ui.CARD, 22, Ui.LINE)
                setOnClickListener {
                    if (on) showMessage("지금 출조 종류: ${Store.catLabel(c)}")
                    else if (Store.current != null) showMessage("출조 중에는 종류를 바꿀 수 없어요 (종료 후 변경)")
                    else { catOpen = false; Store.category = c; showMessage("${Store.catLabel(c)}(으)로 바꿨어요") }
                }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i % 3 > 0) leftMargin = dp(8) })
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

    /** 출조 완료 창의 대표 사진 미리보기 다시 그리기 */
    private var mainPrevPaint: (() -> Unit)? = null

    @Deprecated("old api")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        MainPhoto.onResult(this, requestCode, resultCode, data) { mainPrevPaint?.invoke() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            if (afterLoc != null) { resumeAfterLoc(); return }
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
        menuBtn.text = "☰ 메뉴"
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

    private fun groupLabel(g: Cloud.Group) = g.title

    /** 맨 아래 그룹 칸: 버튼(빨간 숫자) + 펼치면 그룹 바꾸기 · 새 소식 · 그룹 기능 */
    /** 메뉴 안 '지금 들어와 있는 그룹' 띠 (종류가 바뀌어도 다시 그림) */
    private fun renderBand(): View? {
        if (!::bandBox.isInitialized) return null
        bandBox.removeAllViews()
        val band = GroupBand.view(this, true, onClick = { openGroupPanel() })
        if (band != null) bandBox.addView(band)
        return band
    }

    // ---------------- 이번 출조 기록 전체 보기 ----------------
    private fun openAllCatches() {
        if (Store.current == null) return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        allBox = box
        fillAllCatches()
        allDialog = bigDialog("이번 출조 기록 전체", box) { allDialog = null; allBox = null }
    }

    private fun fillAllCatches() {
        val box = allBox ?: return
        val t = Store.current ?: run { allDialog?.dismiss(); return }
        box.removeAllViews()
        box.addView(Ui.text(this, 16f, Ui.TEXT, true,
            "${t.line()} · 총 ${t.catches.size}마리"))
        val list = t.catches.asReversed()
        list.forEachIndexed { i, c ->
            val nth = t.catches.subList(0, t.catches.size - i).count { it.slot == c.slot }
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = Ui.bg(this@MainActivity, Ui.CARD, 10)
            }
            r.addView(Ui.text(this, 14f, Color.parseColor("#CFE0EA"), false, timeFmt.format(Date(c.time))).apply { typeface = Typeface.MONOSPACE },
                LinearLayout.LayoutParams(WRAP, WRAP))
            r.addView(View(this).apply { background = Ui.bg(this@MainActivity, if (c.slot in 1..Ui.SLOT.size) Ui.SLOT[c.slot - 1] else Store.slotColor(c.slot), 99) },
                LinearLayout.LayoutParams(dp(10), dp(10)).apply { leftMargin = dp(12) })
            r.addView(Ui.text(this, 15f, Ui.TEXT, true, t.name(c.slot)), LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
            r.addView(Ui.text(this, 13f, Ui.SUB, false, "${nth}번째"))
            box.addView(r, Ui.lp(this, MATCH, WRAP, top = 6))
        }
    }

    // ---------------- 그룹 창 · 메뉴 창 (물때 보기처럼 큰 창) ----------------
    private var groupDialog: AlertDialog? = null
    private var menuDialog: AlertDialog? = null

    private fun bigDialog(title: String, content: View, onDismiss: () -> Unit): AlertDialog {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(4), dp(18), dp(8)) }
        (content.parent as? android.view.ViewGroup)?.removeView(content)
        wrap.addView(content, Ui.lp(this, MATCH, WRAP))
        val dlg = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(wrap) })
            .setPositiveButton("닫기", null)
            .setOnDismissListener { (content.parent as? android.view.ViewGroup)?.removeView(content); onDismiss() }
            .show()
        dlg.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.92).toInt())
        return dlg
    }

    private fun openGroupDialog() {
        if (groupDialog?.isShowing == true) return
        groupOpen = true; groupSwitchOpen = false
        renderGroupPanel()
        groupDialog = bigDialog("그룹", groupBox) { groupOpen = false; groupDialog = null; renderGroupPanel() }
        // 열 때 그룹 목록·새 소식 다시 받기 (30초에 한 번)
        if (Cloud.user != null && System.currentTimeMillis() - groupsAt > 30_000L) {
            groupsAt = System.currentTimeMillis()
            Cloud.refresh { GroupNews.load(true) { if (!isFinishing) renderGroupPanel() } }
        } else GroupNews.load(false) { if (!isFinishing) renderGroupPanel() }
    }

    private lateinit var adminMenuBtn: TextView
    private lateinit var liveChip: TextView
    private lateinit var nowBtn: LinearLayout
    private lateinit var nowTitle: TextView
    private lateinit var nowSub: TextView
    private var nowAt = 0L

    /** 맨 아래 '🟢 지금 출조 중 N명' 버튼 (1분에 한 번만 새로 받음) */
    private fun paintNowBtn() {
        if (!::nowBtn.isInitialized) return
        if (Cloud.user == null) { nowBtn.visibility = View.GONE; return }
        fun draw(l: List<Live.AnyNow>) {
            nowBtn.visibility = View.VISIBLE
            // 아무도 없으면 작은 한 줄로 (자리 덜 차지)
            val none = l.isEmpty()
            nowTitle.textSize = if (none) 12f else 16f
            nowTitle.text = if (none) "🟢 지금 출조 중인 사람 없음" else "🟢 지금 출조 중 ${l.size}명"
            nowSub.visibility = if (none) View.GONE else View.VISIBLE
            nowSub.text = l.take(3).joinToString(", ") { it.name } + (if (l.size > 3) " 외 ${l.size - 3}명" else "") + " · 눌러서 보기"
            nowBtn.setPadding(dp(14), if (none) dp(5) else dp(10), dp(14), if (none) dp(5) else dp(10))
            nowBtn.alpha = if (none) 0.7f else 1f
        }
        draw(Live.allCached())
        if (System.currentTimeMillis() - nowAt < 3 * 60_000L) return
        nowAt = System.currentTimeMillis()
        Live.nowAll { l -> if (!isFinishing) draw(l) }
    }

    /** '지금 출조 중' 목록 창: 사람을 누르면 그 사람 위치 지도 */
    private fun openNowDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        fun draw(l: List<Live.AnyNow>) {
            box.removeAllViews()
            if (l.isEmpty()) {
                box.addView(Ui.text(this, 14f, Ui.SUB, false, "지금 출조 중인 사람이 없어요.\n출조를 알리기로 한 사람만 보여요."), Ui.lp(this, MATCH, WRAP, top = 8))
                return
            }
            l.forEach { m ->
                val mins = ((System.currentTimeMillis() - m.start) / 60_000L).coerceAtLeast(0)
                val hasLoc = m.lat != null && m.lon != null
                val r = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = Ui.bg(this@MainActivity, Ui.CARD, 12, Ui.LINE); isClickable = true
                    setOnClickListener {
                        if (hasLoc) {
                            val ago = if (m.at > 0) MapDialog.agoText((System.currentTimeMillis() - m.at) / 1000) else ""
                            MapDialog.showPerson(this@MainActivity, m.name, m.lat!!, m.lon!!,
                                listOf(Store.catShort(m.cat).ifBlank { "출조" }, m.line, if (ago.isNotBlank()) "위치 $ago" else null).filterNotNull().joinToString(" · "))
                        } else showMessage(if (m.pub && m.groups.isEmpty()) "전체 공유는 위치 대신 동네 이름만 보여요" else "${m.name}님은 위치 공유를 꺼 두었어요")
                    }
                }
                val tx = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                tx.addView(Ui.text(this, 15f, Ui.TEXT, true, m.name).apply {
                    append(android.text.SpannableStringBuilder("  · " + Store.catShort(m.cat).ifBlank { "출조" } + " · " +
                        (if (mins >= 60) "${mins / 60}시간 ${mins % 60}분" else "${mins}분")).apply {
                        setSpan(android.text.style.AbsoluteSizeSpan(12, true), 0, length, 0)
                        setSpan(android.text.style.ForegroundColorSpan(Ui.SUB), 0, length, 0)
                    })
                })
                tx.addView(Ui.text(this, 13f, Color.parseColor("#FFB36B"), false, m.line.ifBlank { "0마리" }))
                val where = (m.groups.map { "🚤 $it" } + (if (m.pub) listOf("🌊 전체 공유" + (if (m.area.isNotBlank()) " · ${m.area} 부근" else "")) else emptyList())).joinToString(" · ")
                tx.addView(Ui.text(this, 11f, Ui.SUB, false, where))
                r.addView(tx, LinearLayout.LayoutParams(0, WRAP, 1f))
                r.addView(Ui.text(this, 13f, if (hasLoc) Ui.GREEN else Ui.SUB, true, if (hasLoc) "📍 지도 ›" else "위치 안 보임"))
                box.addView(r, Ui.lp(this, MATCH, WRAP, top = 8))
            }
            box.addView(Ui.text(this, 11f, Ui.SUB, false, "출조를 알리기로 한 사람만 보여요 · 📍 = 위치 공유 중 (누르면 그 사람 위치 지도)"), Ui.lp(this, MATCH, WRAP, top = 10))
        }
        draw(Live.allCached())
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("🟢 지금 출조 중")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("닫기", null).show()
        nowAt = System.currentTimeMillis()
        Live.nowAll { l -> if (!isFinishing) { draw(l); paintNowBtn() } }
    }
    /**
     * '내 출조 알리기' 고르는 칸 (출조 시작 창 · 출조 중 칩 창 같이 씀)
     *  🔒 알리지 않기 / 그룹에 알리기 (가입한 그룹 하나씩 체크) / 🌊 전체 사용자에게 공유하기
     */
    private fun liveScopeViews(box: LinearLayout, redraw: () -> Unit) {
        // 처음 동의 때 '같이 출조 중 공유'를 안 했으면 알리지 않기로 보임 (여기서 고르면 동의한 것으로)
        val scope = if (!Consent.shareLive(this)) "none" else Store.liveScope
        fun option(key: String, title: String, sub: String?): LinearLayout {
            val on = scope == key
            val o = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(9), dp(12), dp(9))
                background = Ui.bg(this@MainActivity, Color.TRANSPARENT, 10, if (on) Ui.TEXT else Ui.LINE)
                isClickable = true
                setOnClickListener {
                    if (key == "groups" && Store.liveGroups == null) Store.liveGroups = Live.checkedGroupIds()
                    if (key != "none") Consent.setShareLive(this@MainActivity, true)
                    Store.liveScope = key; redraw()
                }
            }
            val r = Ui.row(this)
            r.addView(Ui.text(this, 15f, if (on) Ui.TEXT else Ui.SUB, true, if (on) "◉" else "○"), LinearLayout.LayoutParams(dp(26), WRAP))
            val tx = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            tx.addView(Ui.text(this, 14f, Ui.TEXT, on, title))
            sub?.let { tx.addView(Ui.text(this, 11f, Ui.SUB, false, it)) }
            r.addView(tx, LinearLayout.LayoutParams(0, WRAP, 1f))
            o.addView(r)
            box.addView(o, Ui.lp(this, MATCH, WRAP, top = 8))
            return o
        }
        option("none", "🔒 알리지 않기", "아무에게도 '지금 출조 중'·마릿수·위치가 안 보여요")
        val g = option("groups", "그룹에 알리기", "체크한 그룹 사람들에게만 보여요")
        if (scope == "groups") {
            val ids = Live.checkedGroupIds()
            Cloud.groups.forEach { gr ->
                val checked = gr.id in ids
                val r = Ui.row(this).apply {
                    setPadding(dp(26), dp(6), 0, dp(2)); isClickable = true
                    setOnClickListener {
                        val cur = Live.checkedGroupIds().toMutableSet()
                        if (!cur.add(gr.id)) cur.remove(gr.id)
                        Store.liveGroups = cur; redraw()
                    }
                }
                r.addView(Ui.text(this, 13f, if (checked) Ui.BG else Ui.SUB, true, if (checked) "✓" else "").apply {
                    gravity = Gravity.CENTER
                    background = if (checked) Ui.bg(this@MainActivity, Ui.GREEN, 4) else Ui.bg(this@MainActivity, Color.TRANSPARENT, 4, Ui.SUB)
                }, LinearLayout.LayoutParams(dp(20), dp(20)))
                r.addView(Ui.text(this, 13f, if (checked) Ui.TEXT else Ui.SUB, false, "").apply {
                    text = android.text.SpannableStringBuilder(GroupIcon.prefix(this@MainActivity, gr, 16)).append(gr.title)
                    if (gr.id == Cloud.active?.id) append(android.text.SpannableStringBuilder("  지금 그룹").apply {
                        setSpan(android.text.style.ForegroundColorSpan(Ui.GREEN), 0, length, 0)
                        setSpan(android.text.style.AbsoluteSizeSpan(11, true), 0, length, 0)
                    })
                }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
                g.addView(r)
            }
            if (ids.none { id -> Cloud.groups.any { it.id == id } })
                g.addView(Ui.text(this, 11f, Color.parseColor("#FF8A92"), false, "체크한 그룹이 없으면 아무에게도 안 보여요").apply { setPadding(dp(26), dp(4), 0, 0) })
        }
        option("public", "🌊 전체 사용자에게 공유하기", "앱 쓰는 모든 사람 · 전체 게시판 맨 위에 보여요 (이름은 게시판처럼 익명/닉네임, 위치는 동네 이름만) · 내 그룹 모두에도 보여요")
        // 📍 위치 공유하기 (알릴 때만)
        if (scope != "none") {
            val on = Store.liveLoc && Store.appLoc
            val r = Ui.row(this).apply {
                setPadding(dp(4), dp(10), 0, dp(2)); isClickable = true
                setOnClickListener {
                    if (on) { Store.liveLoc = false; redraw() }
                    // 켤 때 위치가 꺼져 있으면 안내 → 켜고 돌아오면 체크
                    else checkLocation("위치 없이 마릿수만 공유", { Store.liveLoc = true; redraw() }) { Store.liveLoc = false; redraw() }
                }
            }
            r.addView(Ui.text(this, 13f, if (on) Ui.BG else Ui.SUB, true, if (on) "✓" else "").apply {
                gravity = Gravity.CENTER
                background = if (on) Ui.bg(this@MainActivity, Ui.GREEN, 4) else Ui.bg(this@MainActivity, Color.TRANSPARENT, 4, Ui.SUB)
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            val tx = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            tx.addView(Ui.text(this, 14f, Ui.TEXT, true, "📍 위치 공유하기"))
            tx.addView(Ui.text(this, 11f, Ui.SUB, false,
                if (on) "그룹 사람들이 '지금 출조 중'에서 눌러 지도로 내 위치를 볼 수 있어요" + (if (scope == "public") " (전체 공유는 동네 이름만)" else "")
                else "끄면 '출조 중'과 마릿수만 보이고 지도엔 안 나와요"))
            r.addView(tx, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(10) })
            box.addView(r, Ui.lp(this, MATCH, WRAP, top = 4))
        }
    }

    private fun paintLiveChip() {
        if (!::liveChip.isInitialized) return
        val t = Store.current
        if (t == null || Cloud.user == null || Cloud.groups.isEmpty()) { liveChip.visibility = View.GONE; return }
        liveChip.visibility = View.VISIBLE
        val none = !Consent.shareLive(this) || Store.liveScope == "none" || (Store.liveScope == "groups" && Live.shareGroups().isEmpty())
        liveChip.text = when (if (!Consent.shareLive(this)) "none" else Store.liveScope) {
            "none" -> "🔒 내 출조 알리지 않는 중 ▾"
            "public" -> "🌊 전체 사용자에게 공유 중 ▾"
            else -> Live.shareGroups().let { gs ->
                if (gs.isEmpty()) "🔒 체크한 그룹이 없어 안 알리는 중 ▾"
                else "🟢 " + (if (gs.size == 1) gs[0].title else "${gs[0].title} 외 ${gs.size - 1}곳") + "에 알리는 중 ▾"
            }
        }
        liveChip.setTextColor(if (none) Ui.SUB else Ui.GREEN)
        liveChip.background = Ui.bg(this, Color.TRANSPARENT, 99, if (none) Ui.LINE else Ui.GREEN)
    }
    private lateinit var boardBtn: LinearLayout
    private lateinit var boardSub: TextView
    private var boardAt = 0L
    /** 게시판 버튼 아래 작은 글씨: 오늘 새 글 수 (10분에 한 번만 받음) */
    private fun paintBoardSub() {
        if (!::boardSub.isInitialized || Cloud.user == null) return
        if (System.currentTimeMillis() - boardAt < 10 * 60_000L) return
        boardAt = System.currentTimeMillis()
        val day0 = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
        PublicBoard.load(day0) { list, _ ->
            if (isFinishing || list == null) return@load
            val n = list.count { it.owner != Cloud.uid }
            boardN = n; paintBoardSubText()
        }
        Live.publicNow { l -> if (!isFinishing) { boardLive = l.count { it.uid != Cloud.uid }; paintBoardSubText() } }
    }
    private var boardN = 0
    private var boardLive = 0
    private fun paintBoardSubText() {
        boardSub.text = listOfNotNull("모든 사용자가 공개한 출조", if (boardN > 0) "오늘 새 글 $boardN" else null,
            if (boardLive > 0) "🟢 지금 출조 중 ${boardLive}명" else null).joinToString(" · ")
    }

    /** 총관리자 메뉴 */
    private fun openAdminMenu() {
        if (!Cloud.isAdmin) return
        val items = listOf("✉ 1:1 문의함", "👥 사용자 목록 · 권한 · 최소 버전 · 정지", "🐟 어종 관리", "💬 전체 채팅방 초기화")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("🛠 관리자 메뉴")
            .setItems(items.toTypedArray()) { _, i ->
                when (i) {
                    0 -> startActivity(Intent(this, InquiryActivity::class.java))
                    1 -> startActivity(Intent(this, AdminActivity::class.java))
                    2 -> speciesBtn.performClick()
                    3 -> AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("전체 채팅방을 초기화할까요?")
                        .setMessage("전체 채팅방 글만 모두 지워요. 그룹 채팅방과 1:1 문의방은 그대로예요. 되돌릴 수 없어요.")
                        .setPositiveButton("초기화") { _, _ -> Chat.resetAll { err -> showMessage(err ?: "전체 채팅방을 초기화했어요") } }
                        .setNegativeButton("취소", null).show()
                    else -> {}
                }
            }
            .setNegativeButton("닫기", null).show()
    }

    private fun openMenuDialog() {
        if (menuDialog?.isShowing == true) return
        menuBox.visibility = View.VISIBLE
        menuDialog = bigDialog("메뉴", menuBox) { menuDialog = null }
    }

    override fun onDestroy() {
        groupDialog?.dismiss(); menuDialog?.dismiss(); allDialog?.dismiss()
        super.onDestroy()
    }

    /** 그룹장: 그룹 출조 종류 고르기 (여러 개) */
    private fun editGroupTypes(g: Cloud.Group) {
        account.askTypes("'${groupLabel(g)}' 출조 종류 (여러 개 선택 가능)", g.types) { types ->
            Cloud.setGroupTypes(types) { err -> showMessage(err ?: "그룹 출조 종류를 바꿨어요: " + types.joinToString(" · ") { Store.catLabel(it) }) }
        }
    }

    /** 메뉴의 '지금 그룹'을 누르면: 그룹 칸을 펼치고 그쪽으로 이동 */
    private fun openGroupPanel() {
        menuDialog?.dismiss()
        openGroupDialog()
    }

    /** 그룹 창 '출조 종류' 커튼바 펼침 (칩을 고르면 자동으로 다시 접힘) */
    private var catOpen = false

    /** 그룹 칸 안 '출조 종류': 평소엔 '🎣 출조 종류 · 지금: ○○ ▾' 한 줄, 누르면 칩·(그룹장) 추가·빼기 */
    private fun addCatSection() {
        val cats = visibleCats()
        (catRow.parent as? LinearLayout)?.removeView(catRow)
        val g = Cloud.active
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(14), dp(11), dp(14), dp(11))
            background = Ui.bg(this@MainActivity, Ui.CARD, 14, if (catOpen) Ui.TEXT else Ui.LINE)
            isClickable = true
            setOnClickListener { catOpen = !catOpen; renderGroupPanel() }
        }
        // 가운데 정렬 (오른쪽 ▾ 와 같은 폭을 왼쪽에 비워 둠)
        head.addView(Ui.text(this, 13f, Color.TRANSPARENT, true, if (catOpen) "▴" else "▾"))
        head.addView(Ui.text(this, 15f, Ui.TEXT, true, "🎣 출조 종류 · 지금: ").apply {
            gravity = Gravity.CENTER
            append(android.text.SpannableStringBuilder(Store.catLabel(Store.current?.cat?.takeIf { it.isNotBlank() } ?: Store.category)).apply {
                setSpan(android.text.style.ForegroundColorSpan(Ui.GREEN), 0, length, 0)
            })
            if (cats.size <= 1) append(android.text.SpannableStringBuilder("  (이 그룹 종류 1개)").apply {
                setSpan(android.text.style.AbsoluteSizeSpan(11, true), 0, length, 0)
                setSpan(android.text.style.ForegroundColorSpan(Ui.SUB), 0, length, 0)
            })
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(Ui.text(this, 13f, Ui.SUB, true, if (catOpen) "▴" else "▾"))
        groupBox.addView(head, Ui.lp(this, MATCH, WRAP, top = 14))
        if (!catOpen) return
        groupBox.addView(catRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        renderCats()
        // 그룹장: 우리 그룹 출조 종류 추가·빼기 (관리자가 만든 종류 중에서)
        if (g != null && Cloud.isOwner) {
            groupBox.addView(Ui.text(this, 13f, Ui.BLUE, true, "✎ 우리 그룹 출조 종류 추가 · 빼기 (그룹장)").apply {
                gravity = Gravity.CENTER
                background = Ui.bg(this@MainActivity, Color.TRANSPARENT, 99, Ui.BLUE)
                setOnClickListener { editGroupTypes(g) }
            }, LinearLayout.LayoutParams(MATCH, dp(42)).apply { topMargin = dp(8) })
        }
    }

    private fun renderGroupPanel() {
        paintHeadGroup()
        if (!::groupBtn.isInitialized) return
        val g = if (Cloud.user == null) null else Cloud.active
        val total = GroupNews.total()
        val todayN = GroupNews.today(g?.id)
        val tomN = GroupNews.tomorrow(g?.id)
        // 강조: 안 본 새 글(빨강) → 내일 출조(초록) · 확인한 일정은 강조 없음
        val sched = when { tomN > 0 -> " · 내일 출조!"; todayN > 0 -> " · 오늘 출조"; else -> "" }
        val base: CharSequence = if (g == null) "👥 그룹"
            else android.text.SpannableStringBuilder(GroupIcon.prefix(this, g, 24, GroupBand.catIcon(GroupBand.currentCat(g)))).append(g.styled())
        groupBtn.text = android.text.SpannableStringBuilder(base).apply {
            if (sched.isNotEmpty()) {
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
        // 🟢 지금 출조 중은 그룹 줄·그룹 창이 아니라 맨 아래 따로 (paintNowBtn)
        groupBtn.maxLines = 1
        paintNowBtn()
        groupDot.text = total.toString()
        groupDot.visibility = if (total > 0) View.VISIBLE else View.GONE
        if (!groupOpen) return
        groupBox.removeAllViews()
        // 맨 위: 지금 여기에서 설정 중이에요 (종류 색으로 꽉 찬 띠)
        GroupBand.view(this, true, filled = true)?.let { groupBox.addView(it) }
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
        val activeId = g?.id
        // 현재 그룹 변경: 눌러서 펼치기·접기 (그룹마다 빨간 숫자)
        val newsAll = gs.sumOf { GroupNews.count(it.id) }
        val sw = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(4), dp(14), dp(if (groupSwitchOpen) 12 else 4))
            background = Ui.bg(this@MainActivity, Ui.CARD, 16, if (groupSwitchOpen) Ui.GREEN else Ui.LINE)
        }
        val swHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(44)
            setOnClickListener { groupSwitchOpen = !groupSwitchOpen; renderGroupPanel() }
        }
        swHead.addView(Ui.text(this, 15f, Ui.TEXT, true, "🔄 현재 그룹 변경").apply {
            append(android.text.SpannableStringBuilder("  · 내 그룹 ${gs.size}개").apply {
                setSpan(android.text.style.AbsoluteSizeSpan(12, true), 0, length, 0)
                setSpan(android.text.style.ForegroundColorSpan(Ui.SUB), 0, length, 0)
            })
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (!groupSwitchOpen && newsAll > 0) swHead.addView(redDot(newsAll), LinearLayout.LayoutParams(WRAP, dp(20)).apply { rightMargin = dp(8) })
        swHead.addView(Ui.text(this, 16f, if (groupSwitchOpen) Ui.GREEN else Ui.SUB, true, if (groupSwitchOpen) "▴" else "▾"))
        sw.addView(swHead)
        groupBox.addView(sw, Ui.lp(this, MATCH, WRAP, top = 12))
        if (groupSwitchOpen) {
        val switchBox = sw
        // 가입한 그룹 모두: 두 칸씩 여러 줄 (옆으로 밀지 않아도 다 보이게)
        var rowL: LinearLayout? = null
        gs.forEachIndexed { i, gr ->
            if (i % 2 == 0) {
                rowL = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
                switchBox.addView(rowL, Ui.lp(this, MATCH, WRAP, top = 2))
            }
            val on = gr.id == activeId
            val f = android.widget.FrameLayout(this).apply { clipChildren = false; clipToPadding = false; setPadding(0, dp(8), 0, 0) }
            f.addView(Ui.text(this, 14f, if (on) Color.parseColor("#08311F") else Ui.TEXT, true).apply {
                text = android.text.SpannableStringBuilder().append(if (on) "✓ " else "")
                    .append(GroupIcon.prefix(this@MainActivity, gr, 22, GroupBand.catIcon(GroupBand.currentCat(gr))))
                    .append(gr.styled()).append(if (gr.owner == Cloud.uid) " 👑" else "")
                gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(10), 0, dp(10), 0)
                background = if (on) Ui.bg(this@MainActivity, Ui.GREEN, 99) else Ui.bg(this@MainActivity, Ui.CARD, 99, Ui.LINE)
                setOnClickListener {
                    if (on) return@setOnClickListener
                    if (Store.current != null && Store.current?.catches?.isNotEmpty() == true)
                        AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("'${gr.name}' 그룹으로 바꿀까요?")
                            .setMessage("지금 출조는 종료할 때 바뀐 그룹에 올라가요")
                            .setPositiveButton("바꾸기") { _, _ -> groupSwitchOpen = false; Cloud.setActive(gr.id); showMessage("'${gr.name}' 그룹으로 바꿨어요") }
                            .setNegativeButton("취소", null)
                            .show()
                    else { groupSwitchOpen = false; Cloud.setActive(gr.id); showMessage("'${gr.name}' 그룹으로 바꿨어요") }
                }
            }, android.widget.FrameLayout.LayoutParams(MATCH, dp(42)))
            val n = GroupNews.count(gr.id)
            f.addView(redDot(n), android.widget.FrameLayout.LayoutParams(WRAP, dp(20), Gravity.TOP or Gravity.END).apply { topMargin = -dp(8); rightMargin = dp(4) })
            rowL!!.addView(f, LinearLayout.LayoutParams(0, dp(50), 1f).apply { if (i % 2 == 1) leftMargin = dp(8) })
        }
        if (gs.size % 2 == 1) rowL?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(8) })
        // 그룹 만들기·가입: 한 줄 전체 폭 (흐린 테두리)
        switchBox.addView(Ui.text(this, 13f, Ui.SUB, true, "+ 그룹 만들기 · 가입").apply {
            gravity = Gravity.CENTER; minHeight = dp(40)
            background = Ui.bg(this@MainActivity, Color.TRANSPARENT, 99, Color.parseColor("#2E4256"))
            setOnClickListener { account.openGroups() }
        }, LinearLayout.LayoutParams(MATCH, dp(42)).apply { topMargin = dp(10) })
        }
        // 출조 종류 (그룹에 정해진 종류만)
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
        // 그룹 출조 기록: 새로 올라온 출조가 있으면 초록 강조 + 개수 (열면 꺼짐)
        val newTr = GroupNews.newTrips(activeId)
        val tripsBtn = gbtn(if (newTr > 0) "그룹 출조 기록 · 새 글 $newTr" else "그룹 출조 기록", newTr > 0) {
            startActivity(Intent(this, GroupTripsActivity::class.java))
        }
        pair(tripsBtn,
            gbtn("같이 출조 중") { showMates() })
        // 그룹 채팅: 안 읽은 글이 있으면 노랑 강조 + 개수
        val chatN = activeId?.let { Chat.unread(it) } ?: 0
        val chatG = gbtn(if (chatN > 0) "💬 그룹 채팅 · 새 글 $chatN" else "💬 그룹 채팅") {
            startActivity(Intent(this, ChatActivity::class.java).putExtra("room", activeId))
        }
        if (chatN > 0) { chatG.setTextColor(Color.parseColor("#F2C14E")); chatG.background = Ui.bg(this, Color.parseColor("#2A2310"), 99, Color.parseColor("#F2C14E")) }
        pair(chatG, gbtn("달력 · 멤버 · 그룹 관리 · 초대") { startActivity(Intent(this, GroupActivity::class.java)) }.apply { maxLines = 1; setAutoSizeTextTypeUniformWithConfiguration(10, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP) })
        // 그룹 탈퇴 (그룹원만, 빨갛게)
        if (g != null && g.owner != Cloud.uid) {
            groupBox.addView(Ui.text(this, 14f, Color.WHITE, true).apply {
                text = android.text.SpannableStringBuilder("그룹 탈퇴 · ").append(g.styled())
                gravity = Gravity.CENTER; minHeight = dp(46)
                background = Ui.bg(this@MainActivity, Color.parseColor("#B3262F"), 99)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("'${g.title}'에서 탈퇴할까요?")
                        .setMessage("그룹 채팅에 탈퇴 알림이 남고, 이 그룹의 공지·일정·출조 기록을 더 볼 수 없어요. 내 폰 기록은 그대로예요.\n다시 들어오려면 초대 코드가 필요해요.")
                        .setPositiveButton("탈퇴") { _, _ -> Cloud.leaveGroup(g) { err -> showMessage(err ?: "탈퇴했어요"); renderGroupPanel() } }
                        .setNegativeButton("취소", null).show()
                }
            }, Ui.lp(this, MATCH, WRAP, top = 18))
        }
    }

    /** 새 소식 카드: 일정(하늘) / 공지(노랑) + 확인 */
    private fun newsCard(it: GroupNews.Item): View {
        val isTrip = it.kind == "trip"
        val col = if (it.isNotice) Color.parseColor("#F2C14E") else if (isTrip) Ui.GREEN else Ui.BLUE
        val ink = if (it.isNotice) Color.parseColor("#3A2A02") else if (isTrip) Color.parseColor("#08311F") else Color.parseColor("#06303A")
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(10), dp(10))
            background = Ui.bg(this@MainActivity, Ui.CARD, 14, col)
            isClickable = true
            setOnClickListener { _ ->
                startActivity(Intent(this@MainActivity, if (it.isNotice) NoticeActivity::class.java else if (isTrip) GroupTripsActivity::class.java else ScheduleActivity::class.java))
            }
        }
        val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line.addView(Ui.text(this, 11f, ink, true, if (it.isNotice) "공지" else if (isTrip) "조과" else "일정").apply {
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

    /** 같이 출조 중 (출조 중일 때 50m 안 그룹원) */
    private fun showMates() {
        if (Store.current == null) { showMessage("출조 중일 때 같이 출조 중인 그룹원이 보여요 (${Live.rangeLabel()})"); return }
        Live.sync(true)
        val ms = Live.mates
        val msg = if (ms.isEmpty()) Live.reason.ifBlank { "지금 같이 출조 중인 그룹원이 없어요" }
        else ms.joinToString("\n") { m ->
            String.format(Locale.KOREA, "%s · %s %d · %s %d · 약 %.0fm", m.name,
                m.names.getOrElse(0) { "1번" }, m.c1, m.names.getOrElse(1) { "2번" }, m.c2, m.meters)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("같이 출조 중")
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
        // 그룹이 바뀌면 그룹 채팅방도 다시 지켜봄
        Chat.refreshRooms()
        paintChat()
        renderGroupPanel()
        GroupNews.load(false) { if (!isFinishing) renderGroupPanel() }
        val u = Cloud.user
        val band = renderBand()
        when {
            u == null -> {
                accountInfo.text = "친구와 조과 공유 · 로그인하면 그룹에 올리고, 내 기록도 서버에 백업돼요"
                accountBtn.text = "구글 로그인"; accountBtn.visibility = View.VISIBLE
            }
            band == null -> {
                accountInfo.text = "${Cloud.displayName} · 아직 그룹이 없어요"
                accountBtn.text = "그룹 만들기 / 가입"; accountBtn.visibility = View.VISIBLE
            }
            else -> { accountInfo.text = "지금 들어와 있는 그룹"; accountBtn.visibility = View.GONE }
        }
        if (hideRouteBox.isChecked != Store.hideRoute) hideRouteBox.isChecked = Store.hideRoute
        // 관리자 기능은 '🛠 관리자' 메뉴로 (일반 메뉴는 모두 같게)
        adminBtn.visibility = View.GONE
        speciesBtn.visibility = View.GONE
        if (::adminMenuBtn.isInitialized) adminMenuBtn.visibility = if (Cloud.isAdmin) View.VISIBLE else View.GONE
        GroupNews.startLive(newsLive)
        // 그룹 목록이 오면 같이 출조 중 실시간 연결 바로 다시 붙이기
        if (Store.current != null) Live.watch()
        renderCats()
    }

    /** 카운터 아래: 같이 출조 중인 그룹원 마릿수 */
    private fun renderLive() {
        if (!::liveBox.isInitialized) return
        liveBox.removeAllViews()
        val ms = Live.mates
        if (Store.current == null || Cloud.user == null || Cloud.groups.isEmpty()) { liveBox.visibility = View.GONE; return }
        liveBox.visibility = View.VISIBLE
        liveBox.addView(Ui.text(this, 12f, Ui.GREEN, true, "같이 출조 중 (${Live.rangeLabel()}) · 실시간"))
        // 같이 출조하다 종료한 사람 (1시간 동안만)
        Live.endedMates.forEach { m ->
            val c = listOf(m.names.getOrElse(0) { "1번" } to m.c1, m.names.getOrElse(1) { "2번" } to m.c2)
                .filter { it.second > 0 }.joinToString(" · ") { "${it.first} ${it.second}" }
            liveBox.addView(Ui.text(this, 12f, Ui.SUB, false,
                "■ ${m.name}님은 출조를 종료했어요 · " + SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(m.time)) +
                    (if (c.isNotBlank()) " · $c" else "")), Ui.lp(this, MATCH, WRAP, top = 2))
        }
        // 안 보일 때 이유
        if (ms.isEmpty()) {
            liveBox.addView(Ui.text(this, 12f, Ui.SUB, false, Live.reason.ifBlank { "같이 출조 중인 그룹원을 찾는 중…" }), Ui.lp(this, MATCH, WRAP, top = 2))
            return
        }
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
                    showMessage(String.format(Locale.KOREA, "%s · %s · %s 기준",
                        m.name, if (m.meters > 0) String.format(Locale.KOREA, "약 %.0fm 거리", m.meters) else "거리 모름",
                        SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(m.time))))
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
        if (minute != lastMinute && ::last1.isInitialized) {
            lastMinute = minute; last1.text = lastText(t, 1); last2.text = lastText(t, 2)
            renderLive()  // "N분 전" 1분마다 갱신
        }
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
        renderBand()
        refreshTideRow()
        val t = Store.current
        placeText.visibility = if (t == null) View.GONE else View.VISIBLE
        paintLiveChip()
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
        quick?.render()
        tripBtn.text = if (t == null) "출조 시작" else "■ 출조 종료"
        // 출조 중엔 빨간 바탕 · 흰 글씨 (실수로 안 누르게, 끝낼 때 바로 찾게)
        if (t == null) { tripBtn.setTextColor(Ui.BG); tripBtn.background = Ui.bg(this, Ui.TEXT, 14) }
        else { tripBtn.setTextColor(Color.WHITE); tripBtn.background = Ui.bg(this, RED, 14) }
        undoBtn.visibility = if (t == null) View.GONE else View.VISIBLE
        placeRecent()
        if (t != null) Live.watch() else { Live.unwatch(); checkForced() }
        renderLive()

        recent.text = if (t == null || t.catches.isEmpty()) {
            "아직 기록이 없어요"
        } else {
            val sb = StringBuilder("총 ${t.catches.size}마리\n")
            t.catches.asReversed().take(5).forEachIndexed { i, c ->
                val nth = t.catches.subList(0, t.catches.size - i).count { it.slot == c.slot }
                sb.append(timeFmt.format(Date(c.time))).append("  ")
                    .append(if (c.slot == 1) "●" else "○").append(" ")
                    .append(t.name(c.slot)).append("  ").append(nth).append("번째\n")
            }
            sb.toString().trimEnd()
        }
        historyBtn.text = "지난 출조 · 내 기록 보관함 (${Store.history.size})"
        // 최근 기록은 5개만, 나머지는 '전체 보기' 창
        val n = t?.catches?.size ?: 0
        recentAllBtn.visibility = if (t != null && n > 5) View.VISIBLE else View.GONE
        recentAllBtn.text = "전체 보기 ($n) ›"
        if (allDialog?.isShowing == true) fillAllCatches()
    }
}
