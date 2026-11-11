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
    /** v3.9.2: 화면 크기 자동 맞춤 (Ui.fit) */
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(Ui.fit(newBase)) }


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
    private lateinit var tideWarn: TextView
    private var warnAt = 0L
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
    private var stationsSynced = false
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
        // v3.9.2 (165): 그룹 이미지 크게 (36 → 48)
        headIcon.addView(GroupIcon.view(this, g, 48, GroupBand.catIcon(GroupBand.currentCat(g))), android.widget.FrameLayout.LayoutParams(dp(48), dp(48)))
        headGroupName.text = g.title; headGroupName.visibility = View.VISIBLE
    }
    private fun paintInquiry() {
        if (!::inqBadge.isInitialized || Cloud.user == null) return
        Inquiry.unread { n -> if (!isFinishing) {
            inqBadge.text = "$n"; inqBadge.visibility = if (n > 0) View.VISIBLE else View.GONE
            // 맨 아래 '더보기' 탭에도 같은 숫자
            tabMoreN = n
            if (::tabMoreBadge.isInitialized) { tabMoreBadge.text = "$n"; tabMoreBadge.visibility = if (n > 0) View.VISIBLE else View.GONE }
            paintQuick()
        } }
    }
    private val chatListener: () -> Unit = { paintChat(); renderGroupPanel() }

    /** 메인 둘째 줄 칸 글씨: 큰 이름 + 작은 설명 (191) · 기본 글씨 11 기준으로 이름 16.5 (4글자 이상 15) */
    private fun headTwo(big: String, small: String, smallColor: Int?): CharSequence = android.text.SpannableStringBuilder("$big\n").apply {
        val name = big.substringAfter(' ')
        val e = length - 1
        setSpan(android.text.style.RelativeSizeSpan(if (name.length >= 4) 15f / 11f else 16.5f / 11f), 0, e, 0)
        setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, e, 0)
        val s0 = length; append(small)
        if (smallColor != null) setSpan(android.text.style.ForegroundColorSpan(smallColor), s0, length, 0)
    }

    /** 칸 폭보다 이름이 길면 (작은 폰 · 화면 크기 설정) 글씨만 조금씩 줄임 · 칸 크기는 그대로 */
    private fun fitHead(t: TextView) {
        var base = -1f
        var lastW = -1
        t.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
            val tv = v as TextView
            if (r - l == lastW) return@addOnLayoutChangeListener
            lastW = r - l
            if (base < 0) base = tv.textSize
            tv.post {
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, base)
                fun shrink(n: Int) {
                    val lay = tv.layout ?: return
                    val avail = tv.width - tv.paddingLeft - tv.paddingRight
                    var widest = 0f
                    for (i in 0 until lay.lineCount) widest = maxOf(widest, lay.getLineWidth(i))
                    val over = lay.lineCount > 2 || widest > avail + 0.5f || (0 until lay.lineCount).any { lay.getEllipsisCount(it) > 0 }
                    if (over && n < 6) { tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, tv.textSize * 0.93f); tv.post { shrink(n + 1) } }
                }
                tv.post { shrink(0) }
            }
        }
    }

    /** 채팅 버튼: 접속 인원 + 안 읽은 글 수 */
    private fun paintChat() {
        if (!::chatBtn.isInitialized) return
        // v4.7 (217): 맨 아래 '채팅' 탭 이름 옆에 접속 인원 (예전처럼)
        chatBtn.text = android.text.SpannableStringBuilder("채팅").apply {
            if (Cloud.user != null && chatOnline > 0) {
                val s0 = length; append(" 접속${chatOnline}")
                setSpan(android.text.style.ForegroundColorSpan(Ui.GREEN), s0, length, 0)
            }
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
        handler.post { handleOpen(intent) }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private var consentOpen = false
    /** v4.9 (224): 홈 화면 위젯을 누르면 그 화면 바로 열기 ("open" = "tide" / "bag") */
    private fun handleOpen(i: Intent?) {
        val what = i?.getStringExtra("open") ?: return
        i.removeExtra("open")
        if (!::scroller.isInitialized) return
        when (what) {
            "tide" -> openTodayTide()
            "bag" -> Inventory.show(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handler.post { handleOpen(intent) }
    }

    private var adminEditCheckAt = 0L

    override fun onResume() {
        super.onResume()
        // 처음 한 번: 위치·개인정보 동의 (동의해야 사용)
        if (!Consent.ok(this)) {
            if (!consentOpen) { consentOpen = true; Consent.show(this) { consentOpen = false; recreate() } }
            return
        }
        paintBoardSub(); paintNowBtn()
        resumeAfterLoc()
        // v5.2 (240): 관리자가 고친 내 그룹 기록 → 폰 기록에도 (10분에 한 번)
        if (Cloud.user != null && Cloud.groups.isNotEmpty() && System.currentTimeMillis() - adminEditCheckAt > 10 * 60_000L) {
            adminEditCheckAt = System.currentTimeMillis()
            Cloud.applyAdminEdits { n -> if (n > 0 && !isFinishing) android.widget.Toast.makeText(this, "관리자가 내 출조 ${n}개의 조과를 고쳤어요 (폰 기록도 같이 고침)", android.widget.Toast.LENGTH_LONG).show() }
        }
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
            if (!stationsSynced) { stationsSynced = true; StationAdmin.sync(this) }
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
        col.addView(Ui.text(this, 26f, Ui.TEXT, true, "조과일지").apply { gravity = Gravity.CENTER },
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
        headGroupName = Ui.text(this, 12.5f, Ui.GREEN, true, "").apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; visibility = View.GONE
            setOnClickListener { openGroupPanel() }
        }
        titleCol.addView(headGroupName)
        titleCol.addView(Ui.text(this, 26f, Ui.TEXT, true, "조과일지").apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(13, 30, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        header.addView(titleCol, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(6) })
        // v4.8 (219): ⚙ 바로 왼쪽 네모 퀵바 (3개까지 · 4개부터는 제목 아래 한 줄)
        quickInline = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(quickInline, LinearLayout.LayoutParams(WRAP, WRAP))
        // v4.7.1: ⚙ 기본 설정은 오른쪽 맨 위에 꼭 (더보기에도 같이 있음)
        header.addView(Ui.text(this, 19f, Ui.BLUE, true, "⚙").apply {
            gravity = Gravity.CENTER
            background = Ui.bg(this@MainActivity, Ui.CARD, 99, Ui.BLUE)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(4) })
        col.addView(header)
        quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; setPadding(0, dp(6), 0, 0) }
        quickRowWrap = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; addView(quickRow) }
        col.addView(quickRowWrap, Ui.lp(this, MATCH, WRAP, top = 4))
        paintQuick()
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        // v4.2 (191): 칸 크기는 그대로 · 글씨만 크게 (이름 16.5 · 4글자 15 · 작은 글씨 11) · 칸보다 길면 알아서 줄임
        fun two(big: String, small: String, smallColor: Int?) = headTwo(big, small, smallColor)
        fun cell(v: View, last: Boolean = false): android.widget.FrameLayout = android.widget.FrameLayout(this).apply {
            clipChildren = false; clipToPadding = false; setPadding(0, dp(8), if (last) 0 else dp(5), 0)
            addView(v, android.widget.FrameLayout.LayoutParams(MATCH, dp(50)))
        }
        fun styled(t: TextView, fill: Int, stroke: Int, textColor: Int = Ui.TEXT) = t.apply {
            textSize = 11f; setTextColor(textColor); setLineSpacing(0f, 0.92f); setPadding(dp(1), dp(1), dp(1), dp(1)); maxLines = 2
            fitHead(this)
            background = Ui.bg(this@MainActivity, fill, 12, stroke)
        }
        // 🎒 가방 (나만의 재고표)
        btnRow.addView(cell(styled(smallBtn("") { Inventory.show(this) }, Color.parseColor("#12301F"), Ui.GREEN, Color.parseColor("#BFF5D8")).apply {
            text = two("🎒 가방", "재고표", Ui.GREEN)
        }), LinearLayout.LayoutParams(0, WRAP, 1f))
        // v6.0 (241): 🎮 미니게임 뺌
        // 🗺 지도 (바다색으로 채워 강조 · 182)
        btnRow.addView(cell(styled(smallBtn("") { openMap() }, Color.parseColor("#1F6E96"), Color.parseColor("#7FD3E8"), Color.WHITE).apply {
            text = two("🗺 지도", "현위치", Color.parseColor("#D6F3FF"))
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#2E8FB8"), Color.parseColor("#1C5F86"))).apply { cornerRadius = dp(12).toFloat(); setStroke(dp(1), Color.parseColor("#7FD3E8")) }
        }), LinearLayout.LayoutParams(0, WRAP, 1f))
        // 📅 일정 (물때 달력)
        btnRow.addView(cell(styled(smallBtn("") { val l = bestLocation(); TideCalendar.show(this, l?.first, l?.second) }, Ui.CARD, Color.parseColor("#FFB27A")).apply {
            text = two("🎣 출조추천", "좋은 날", Color.parseColor("#FFB27A"))
        }), LinearLayout.LayoutParams(0, WRAP, 1f))
        // 💬 채팅 (안 읽은 글은 빨간 숫자)
        chatBtn = styled(smallBtn("") { startActivity(Intent(this, ChatActivity::class.java)) }, Color.parseColor("#2A2310"), Color.parseColor("#F2C14E"))
        val chatBox = cell(chatBtn)
        chatBadge = Ui.text(this, 10f, Color.WHITE, true, "").apply {
            gravity = Gravity.CENTER; setPadding(dp(5), 0, dp(5), 0)
            background = Ui.bg(this@MainActivity, RED, 99, Ui.BG)
            visibility = View.GONE
        }
        chatBox.addView(chatBadge, android.widget.FrameLayout.LayoutParams(WRAP, dp(18), Gravity.TOP or Gravity.END).apply { topMargin = -dp(8); rightMargin = -dp(2) })
        btnRow.addView(chatBox, LinearLayout.LayoutParams(0, WRAP, 1f))
        paintChat()
        // ✉ 문의 (관리자 1:1 · 답장 오면 빨간 숫자)
        val purple = Color.parseColor("#CFC8FF")
        val inqBox = cell(styled(smallBtn("") {
            if (Cloud.user == null) showMessage("구글 로그인을 하면 운영자에게 1:1 문의를 할 수 있어요")
            else startActivity(Intent(this@MainActivity, InquiryActivity::class.java))
        }, Color.parseColor("#1E1A3A"), Color.parseColor("#7C6BD6"), purple).apply { text = two("✉ 관리자", "문의", null) }, last = true)
        inqBadge = Ui.text(this, 10f, Color.WHITE, true, "").apply {
            gravity = Gravity.CENTER; setPadding(dp(5), 0, dp(5), 0)
            background = Ui.bg(this@MainActivity, RED, 99, Ui.BG); visibility = View.GONE
        }
        inqBox.addView(inqBadge, android.widget.FrameLayout.LayoutParams(WRAP, dp(18), Gravity.TOP or Gravity.END).apply { topMargin = -dp(8); rightMargin = -dp(4) })
        btnRow.addView(inqBox, LinearLayout.LayoutParams(0, WRAP, 1f))
        // v4.7 (217): 위쪽 버튼 줄은 화면에 안 붙임 → 맨 아래 탭(지도 · 채팅 · 가방)과 '더보기'(출조추천 · 게임 · 관리자 문의)로
        paintHeadGroup()

        // 출조 종류 칩은 맨 아래 '그룹' 칸 안에서 고름 (renderGroupPanel)
        catRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 물때 한 줄 + "물때 보기 ›" (누르면 날짜를 바꿀 수 있는 상세 창)
        val tideCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = WxArt.CardBg(this@MainActivity, Ui.CARD, 14, WxArt.NONE).also { tideBg = it }
            setPadding(dp(14), dp(10), dp(10), dp(10))
            isClickable = true
            setOnClickListener { openTodayTide() }
        }
        tideCardView = tideCard
        val tideTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // v3.9.4 (177): ⚠ 기상특보 빨간 띠 (내 위치 바다 구역에 특보가 있을 때만)
        tideWarn = Ui.text(this, 12.5f, Color.WHITE, true, "").apply {
            visibility = View.GONE; setPadding(dp(8), dp(3), dp(8), dp(3)); background = Ui.bg(this@MainActivity, Color.parseColor("#8A1F28"), 8)
        }
        tideTexts.addView(tideWarn, Ui.lp(this, MATCH, WRAP).apply { bottomMargin = dp(4) })
        tideTitle = Ui.text(this, 18f, Ui.TEXT, true, "").apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        tideSub = Ui.text(this, 13f, Ui.SUB, false, "물때 불러오는 중…")
        tideSun = Ui.text(this, 12f, Ui.SUB, false, "")
        tideTexts.addView(tideTitle); tideTexts.addView(tideSub, Ui.lp(this, MATCH, WRAP, top = 2))
        tideTexts.addView(tideSun, Ui.lp(this, MATCH, WRAP, top = 4))
        tideCard.addView(tideTexts, LinearLayout.LayoutParams(0, WRAP, 1f))
        // v4.7 (217): '물때 보기' 큰 버튼 대신 작은 '물때 ›' (카드 어디를 눌러도 열림)
        tideCard.addView(Ui.text(this, 13f, Ui.BLUE, true, "물때 ›").apply {
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, dp(2), 0)
            setOnClickListener { openTodayTide() }
        }, LinearLayout.LayoutParams(WRAP, dp(38)).apply { leftMargin = dp(4) })
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
                AlertDialog.Builder(this@MainActivity, R.style.FlAlert)
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
            background = Ui.bg(this@MainActivity, Ui.CARD, 14, Color.parseColor("#2B5468"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        message = Ui.text(this, 13f, Ui.GREEN).apply { visibility = View.GONE }

        // 출조 시작 / 종료: 카드 바로 위 (앱 켜면 바로 누를 수 있게)
        tripBtn = Ui.button(this, "출조 시작", filled = true) { onTripButton() }.apply { maxLines = 1 }
        col.addView(tripBtn, Ui.lp(this, MATCH, WRAP, top = 14))
        // v4.7 (217): 기록 방식은 출조 시작 바로 밑 한 줄 + 눈에 띄는 '바꾸기'
        col.addView(methodBox, Ui.lp(this, MATCH, WRAP, top = 8))
        col.addView(message, Ui.lp(this, MATCH, WRAP, top = 6))
        Zones.listener = { msg -> runOnUiThread { if (!isFinishing) showMessage(msg) } }

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
        // v4.7 (217): "✎를 누르면 어종 변경 · 리모컨 길게 = 취소" 안내는 사용 설명서로 옮김

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
        // 순서: 🟢 지금 출조 중 → 전체 조황 · 포인트 게시판 → 그룹 → 메뉴 → 관리자 페이지 (한 줄에 하나씩)
        val bottomRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        // 🟢 지금 출조 중 (내 그룹 + 전체 공유) — 맨 위 (그 아래 게시판 → 그룹 → 메뉴 → 관리자)
        nowBtn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(14), dp(10), dp(14), dp(10))
            background = Ui.bg(this@MainActivity, Color.parseColor("#16352A"), 14, Ui.GREEN)
            isClickable = true; visibility = View.GONE
            setOnClickListener { openNowDialog() }
        }
        nowTitle = Ui.text(this, 16f, Color.parseColor("#9FE7C1"), true, "🟢 지금 출조 중").apply { gravity = Gravity.CENTER }
        nowSub = Ui.text(this, 11f, Color.parseColor("#C9D3DE"), false, "").apply { gravity = Gravity.CENTER; maxLines = 4; setLineSpacing(0f, 1.2f) }
        nowBtn.addView(nowTitle); nowBtn.addView(nowSub)
        bottomRow.addView(nowBtn, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
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
        val gFrame = android.widget.FrameLayout(this).apply { clipChildren = false; clipToPadding = false; setPadding(0, dp(8), 0, 0) }
        groupBtn = Ui.button(this, "그룹") { openGroupDialog() }.apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 16f }
        gFrame.addView(groupBtn, android.widget.FrameLayout.LayoutParams(MATCH, WRAP))
        groupDot = redDot(0)
        gFrame.addView(groupDot, android.widget.FrameLayout.LayoutParams(WRAP, dp(22), Gravity.TOP or Gravity.END).apply { topMargin = -dp(8); rightMargin = dp(4) })
        // v4.7 (217): 그룹 · ☰ 메뉴 · 관리자 페이지는 맨 아래 탭 '그룹' · '더보기'로 (버튼 자체는 그대로 두고 화면엔 안 붙임)
        groupFrame = gFrame
        groupBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        menuBtn = Ui.button(this, "☰ 메뉴") { openMenuDialog() }.apply { textSize = 16f }
        // 총관리자(나)에게만: 관리자 페이지
        adminMenuBtn = Ui.button(this, "🛠 관리자 페이지") { openAdminMenu() }.apply {
            textSize = 16f
            setTextColor(Color.parseColor("#F2C14E")); background = Ui.bg(this@MainActivity, Color.parseColor("#2A2310"), 14, Color.parseColor("#F2C14E"))
            visibility = View.GONE
        }
        col.addView(bottomRow, Ui.lp(this, MATCH, WRAP, top = 8))

        // 메뉴 창 안에 들어갈 내용 (창을 열 때 붙임)
        menuBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // v4.7 (217): '더보기' 맨 위 큰 칸 4개 — 출조추천 · 설정 / 관리자 문의 · 게임(관리자)
        buildMoreGrid()
        menuBox.addView(Ui.button(this, "❓ 사용 설명서 · 처음 안내 다시 보기") {
            startActivity(Intent(this, HelpActivity::class.java))
        }, Ui.lp(this, MATCH, WRAP))
        menuBox.addView(Ui.button(this, "📋 업데이트 내역") { History.show(this) }, Ui.lp(this, MATCH, WRAP, top = 8))
        innerRecent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        menuBox.addView(innerRecent, Ui.lp(this, MATCH, WRAP))

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
        hideRouteBox = Ui.checkFit(CheckBox(this)).apply {
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
        versionText = Ui.text(this, 12f, Ui.SUB, false, "버전 ${BuildConfigInfo.VERSION}").apply {
            minHeight = dp(36); gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { if (Updater.latestCode != null) { menuDialog?.dismiss(); Updater.showDialog(this@MainActivity, forceUpdate) } }
        }
        // 🛠 관리자 페이지 (관리자만 보임 · 예전 메인 맨 아래에서 옮김)
        menuBox.addView(adminMenuBtn, Ui.lp(this, MATCH, WRAP, top = 12))
        menuBox.addView(versionText, Ui.lp(this, MATCH, WRAP, top = 12))
        paintVersion()
        col.addView(View(this), Ui.lp(this, MATCH, dp(24)))
        paintMenu()

        scroller = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(col)
        }
        // v4.7 (217): 맨 아래 고정 탭 6개
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            addView(scroller, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(buildTabBar(), LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    // ---------------- v4.8 (219): 맨 위 네모 퀵바 ----------------
    private lateinit var quickInline: LinearLayout
    private lateinit var quickRow: LinearLayout
    private lateinit var quickRowWrap: View
    private class QItem(val id: String, val icon: String, val label: String, val fill: String, val stroke: String, val admin: Boolean = false, val state: (() -> Boolean)? = null)
    private val QUICK by lazy { listOf(
        // v4.9 (221): 앱의 모든 메뉴
        QItem("map", "🗺", "지도", "#1C5F86", "#7FD3E8"),
        QItem("chat", "💬", "채팅", "#2A2310", "#F2C14E"),
        QItem("bag", "🎒", "가방", "#12301F", "#5FD39A"),
        QItem("groupwin", "👥", "그룹", "#12301F", "#2F6A48"),
        QItem("settings", "⚙", "설정", "#1A2A30", "#5FC4E0"),
        QItem("cal", "🎣", "추천", "#2A2410", "#6A5A22"),
        QItem("tide", "🌊", "물때", "#10283A", "#2E5A7A"),
        QItem("group", "🔄", "그룹변경", "#12301F", "#2F6A48"),
        QItem("hist", "📚", "지난출조", "#12301F", "#2F6A48"),
        QItem("board", "📋", "조황", "#2A1E10", "#6A4A22"),
        QItem("point", "📍", "포인트", "#2A1E10", "#6A4A22"),
        QItem("now", "🟢", "출조중", "#10301E", "#2F6A48"),
        QItem("trash", "🗑", "휴지통", "#2A1418", "#6A2E38"),
        QItem("help", "❓", "설명서", "#1A2A30", "#2E4A52"),
        QItem("upd", "📄", "업데이트", "#1A2A30", "#2E4A52"),
        QItem("inq", "✉", "문의", "#16263A", "#2E4A6A"),
        QItem("admin", "🛠", "관리", "#2A2410", "#F2C14E", admin = true),
        QItem("loc", "🧭", "위치", "#10283A", "#5FC4E0", state = { Store.appLoc }),
        QItem("saver", "🔋", "절약", "#12301F", "#5FD39A", state = { Store.saver }),
        QItem("method", "🎛", "기록방식", "#16303D", "#2B5468"),
        QItem("alarm", "🔔", "알람", "#2A2410", "#6A5A22"),
        QItem("scale", "📐", "화면크기", "#1A2A30", "#2E4A52"),
        QItem("offline", "📥", "오프라인", "#1A2A30", "#2E4A52"),
        QItem("nick", "👤", "닉네임", "#1A2A30", "#2E4A52"))
    }
    private fun quickPrefs() = getSharedPreferences("fishlog", MODE_PRIVATE)
    private fun quickIds(): List<String> {
        val raw = quickPrefs().getString("quick_items", null) ?: "cal,group"
        return raw.split(",").map { it.trim() }.filter { id -> QUICK.any { it.id == id && (!it.admin || Cloud.isAdmin) } }.distinct()
    }
    private fun saveQuick(ids: List<String>) { quickPrefs().edit().putString("quick_items", ids.joinToString(",")).apply(); paintQuick() }

    private fun quickAction(id: String) {
        when (id) {
            "map" -> openMap()
            "chat" -> startActivity(Intent(this, ChatActivity::class.java))
            "bag" -> Inventory.show(this)
            "groupwin" -> openGroupDialog()
            "settings" -> startActivity(Intent(this, SettingsActivity::class.java))
            "cal" -> { val l = bestLocation(); TideCalendar.show(this, l?.first, l?.second) }
            "tide" -> openTodayTide()
            "group" -> account.openGroups()
            "hist" -> startActivity(Intent(this, HistoryActivity::class.java))
            "board", "point" -> {
                if (Cloud.user == null) { showMessage("구글 로그인을 하면 볼 수 있어요"); return }
                quickPrefs().edit().putInt("board_tab", if (id == "point") 1 else 0).apply()
                startActivity(Intent(this, BoardActivity::class.java))
            }
            "now" -> openNowDialog()
            "trash" -> if (Cloud.user == null) showMessage("구글 로그인이 필요해요") else Trash.show(this)
            "help" -> startActivity(Intent(this, HelpActivity::class.java))
            "upd" -> History.show(this)
            "inq" -> if (Cloud.user == null) showMessage("구글 로그인을 하면 운영자에게 1:1 문의를 할 수 있어요")
                     else startActivity(Intent(this, InquiryActivity::class.java))
            "admin" -> openAdminMenu()
            "loc" -> {
                val on = !Store.appLoc
                Store.appLoc = on
                if (!on) { Tracker.forget(); Live.sync(true) } else if (Store.current != null) RemoteService.start(this)
                showMessage(if (on) "🧭 이 앱에서 내 위치 사용 켬" else "🧭 내 위치 사용 끔 (경로 · 조과 위치 · 위치 공유 안 씀)")
            }
            "saver" -> {
                Store.saver = !Store.saver
                showMessage(if (Store.saver) "🔋 배터리 절약 켬 · 위치 3분 간격 · 서버 올리기 5분마다" else "배터리 절약 끔 · 원래대로")
                renderMethods()
            }
            "method" -> { methodsOpen = true; renderMethods(); scroller.post { scroller.smoothScrollTo(0, maxOf(0, methodBox.top - dp(80))) } }
            "alarm" -> TideDialog.pickAlarm(this) {}
            "scale", "offline" -> startActivity(Intent(this, SettingsActivity::class.java))
            "nick" -> startActivity(Intent(this, SettingsActivity::class.java).putExtra("askNick", true))
            "qset" -> { openQuickSettings(); return }
        }
        paintQuick()
    }

    /** 네모 칸 하나 (위 그림 · 아래 이름 · 켜고 끄는 칸은 꺼지면 흐리게 · 문의 답장 빨간 숫자) */
    private fun quickTile(q: QItem, size: Int): View {
        val f = android.widget.FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        val on = q.state?.invoke() ?: true
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = Ui.bg(this@MainActivity, Color.parseColor(q.fill), 10, Color.parseColor(q.stroke))
            alpha = if (on) 1f else 0.45f
            isClickable = true
            setOnClickListener { quickAction(q.id) }
            setOnLongClickListener { openQuickSettings(); true }
        }
        box.addView(Ui.text(this, if (size >= 46) 17f else 15f, Ui.TEXT, false, q.icon).apply { gravity = Gravity.CENTER; includeFontPadding = false })
        box.addView(Ui.text(this, 9.5f, Color.parseColor("#D7E3E8"), true, if (q.state != null) q.label + (if (on) " 켬" else " 끔") else q.label).apply {
            gravity = Gravity.CENTER; maxLines = 1; includeFontPadding = false
            setAutoSizeTextTypeUniformWithConfiguration(7, 10, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }, LinearLayout.LayoutParams(MATCH, dp(13)))
        f.addView(box, android.widget.FrameLayout.LayoutParams(dp(size), dp(size)))
        if (q.id == "inq" && tabMoreN > 0) f.addView(Ui.text(this, 9.5f, Color.WHITE, true, "$tabMoreN").apply {
            gravity = Gravity.CENTER; setPadding(dp(4), 0, dp(4), 0); background = Ui.bg(this@MainActivity, RED, 99, Ui.BG)
        }, android.widget.FrameLayout.LayoutParams(WRAP, dp(16), Gravity.TOP or Gravity.END).apply { topMargin = -dp(6); rightMargin = -dp(5) })
        return f
    }

    private fun paintQuick() {
        if (!::quickInline.isInitialized || !::quickRow.isInitialized) return
        val ids = quickIds()
        val items = ids.mapNotNull { id -> QUICK.firstOrNull { it.id == id } }
        quickInline.removeAllViews(); quickRow.removeAllViews()
        // v4.9 (221): '⚡ 퀵바 설정' 칸은 고정 (뺄 수 없음 · 늘 맨 끝)
        val fixed = QItem("qset", "⚡", "퀵바설정", "#1E2A12", "#C9E26A")
        if (items.size <= 2) {
            (items + fixed).forEach { q -> quickInline.addView(quickTile(q, 40), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(5) }) }
            quickInline.visibility = View.VISIBLE
            quickRowWrap.visibility = View.GONE
        } else {
            quickInline.visibility = View.GONE
            (items + fixed).forEachIndexed { i, q -> quickRow.addView(quickTile(q, 48), LinearLayout.LayoutParams(WRAP, WRAP).apply { if (i > 0) leftMargin = dp(6) }) }
            quickRowWrap.visibility = View.VISIBLE
        }
    }

    /** ⚡ 퀵바 설정: 칸마다 켜기 · 끄기 · ▲▼ 순서 · 기본으로 */
    private fun openQuickSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(4), dp(18), dp(4)) }
        fun draw() {
            box.removeAllViews()
            box.addView(Ui.text(this, 12f, Ui.SUB, false, "체크한 메뉴가 맨 위 네모 칸으로 보여요 · ▲▼로 순서 · 2개까지는 ⚙ 옆, 3개부터는 제목 아래 한 줄 · ⚡ 퀵바 설정 칸은 늘 맨 끝에 고정"))
            val ids = quickIds().toMutableList()
            val avail = QUICK.filter { !it.admin || Cloud.isAdmin }
            val ordered = ids.mapNotNull { id -> avail.firstOrNull { it.id == id } } + avail.filter { it.id !in ids }
            ordered.forEach { q ->
                val on = q.id in ids
                val r = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(46)
                    isClickable = true
                    setOnClickListener { if (on) ids.remove(q.id) else ids.add(q.id); saveQuick(ids); draw() }
                }
                r.addView(Ui.text(this, 14f, Color.parseColor("#04222A"), true, if (on) "✓" else "").apply {
                    gravity = Gravity.CENTER
                    background = if (on) Ui.bg(this@MainActivity, Ui.BLUE, 5) else Ui.bg(this@MainActivity, Color.TRANSPARENT, 5, Ui.LINE)
                }, LinearLayout.LayoutParams(dp(22), dp(22)))
                r.addView(Ui.text(this, 15f, if (on) Ui.TEXT else Ui.SUB, on, "${q.icon}  ${q.label}" + if (q.state != null) " (켜기/끄기)" else "").apply { setPadding(dp(10), 0, 0, 0) },
                    LinearLayout.LayoutParams(0, WRAP, 1f))
                if (on) {
                    val i = ids.indexOf(q.id)
                    fun mv(t: String, d: Int) = Ui.text(this, 14f, Ui.TEXT, true, t).apply {
                        gravity = Gravity.CENTER; background = Ui.bg(this@MainActivity, Color.TRANSPARENT, 8, Ui.LINE)
                        setOnClickListener { val j = i + d; if (j in ids.indices) { ids.removeAt(i); ids.add(j, q.id); saveQuick(ids); draw() } }
                    }
                    if (i > 0) r.addView(mv("▲", -1), LinearLayout.LayoutParams(dp(36), dp(34)))
                    if (i < ids.size - 1) r.addView(mv("▼", 1), LinearLayout.LayoutParams(dp(36), dp(34)).apply { leftMargin = dp(6) })
                }
                box.addView(r)
                box.addView(View(this).apply { setBackgroundColor(Ui.LINE) }, LinearLayout.LayoutParams(MATCH, 1))
            }
        }
        draw()
        AlertDialog.Builder(this, R.style.FlAlert)
            .setTitle("⚡ 퀵바 설정")
            .setView(ScrollView(this).apply { addView(box) })
            .setNeutralButton("기본으로") { _, _ -> quickPrefs().edit().remove("quick_items").apply(); paintQuick(); showMessage("퀵바를 기본(추천 · 그룹변경)으로 되돌렸어요") }
            .setPositiveButton("닫기", null)
            .show()
    }

    // ---------------- v4.7 (217): 맨 아래 탭 · 더보기 ----------------
    private lateinit var tabGroupBadge: TextView
    private lateinit var tabMoreBadge: TextView
    private var tabMoreN = 0

    /** 탭 하나: 위 그림 + 아래 이름 + 오른쪽 위 빨간 숫자 */
    private fun tabItem(icon: String, label: String, onClick: () -> Unit): Triple<View, TextView, TextView> {
        val f = android.widget.FrameLayout(this).apply {
            isClickable = true; clipChildren = false; clipToPadding = false
            setOnClickListener { onClick() }
        }
        val colv = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        colv.addView(Ui.text(this, 20f, Ui.TEXT, false, icon).apply { gravity = Gravity.CENTER })
        val lab = Ui.text(this, 11.5f, Ui.SUB, true, label).apply {
            gravity = Gravity.CENTER; maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(8, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        colv.addView(lab, LinearLayout.LayoutParams(MATCH, dp(16)))
        f.addView(colv, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        val badge = Ui.text(this, 10f, Color.WHITE, true, "").apply {
            gravity = Gravity.CENTER; setPadding(dp(5), 0, dp(5), 0); minWidth = dp(18)
            background = Ui.bg(this@MainActivity, RED, 99, Ui.BG); visibility = View.GONE
        }
        f.addView(badge, android.widget.FrameLayout.LayoutParams(WRAP, dp(18), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(2); leftMargin = dp(18) })
        return Triple(f, lab, badge)
    }

    private fun buildTabBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0B161E"))
            setPadding(0, dp(6), 0, dp(6))
        }
        val line = View(this).apply { setBackgroundColor(Color.parseColor("#23394A")) }
        val home = tabItem("🏠", "홈") { scroller.smoothScrollTo(0, 0) }
        home.second.setTextColor(Ui.BLUE)
        val map = tabItem("🗺", "지도") { openMap() }
        val grp = tabItem("👥", "그룹") { openGroupDialog() }
        val chat = tabItem("💬", "채팅") { startActivity(Intent(this, ChatActivity::class.java)) }
        val bag = tabItem("🎒", "가방") { Inventory.show(this) }
        val more = tabItem("⋯", "더보기") { openMenuDialog() }
        listOf(home, map, grp, chat, bag, more).forEach { bar.addView(it.first, LinearLayout.LayoutParams(0, dp(52), 1f)) }
        tabGroupBadge = grp.third
        chatBtn = chat.second; chatBadge = chat.third
        tabMoreBadge = more.third
        paintChat()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(line, LinearLayout.LayoutParams(MATCH, dp(1)))
            addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    /** 더보기 맨 위 큰 칸: 출조추천 · 설정 / 관리자 문의 */
    private fun buildMoreGrid() {
        fun cell(icon: String, title: String, sub: String, fill: String, stroke: String, ink: String, onClick: () -> Unit): android.widget.FrameLayout {
            val f = android.widget.FrameLayout(this).apply { clipChildren = false; clipToPadding = false; setPadding(0, dp(6), 0, 0) }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = Ui.bg(this@MainActivity, Color.parseColor(fill), 12, Color.parseColor(stroke))
                isClickable = true
                setOnClickListener { menuDialog?.dismiss(); onClick() }
            }
            box.addView(Ui.text(this, 16f, Ui.TEXT, true, "$icon $title").apply { maxLines = 1 })
            box.addView(Ui.text(this, 11.5f, Color.parseColor(ink), false, sub).apply { maxLines = 1 })
            f.addView(box, android.widget.FrameLayout.LayoutParams(MATCH, WRAP))
            return f
        }
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r1.addView(cell("🎣", "출조추천", "좋은 날 달력", "#2A2410", "#6A5A22", "#E5CF8F") {
            val l = bestLocation(); TideCalendar.show(this, l?.first, l?.second)
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) })
        r1.addView(cell("⚙", "설정", "위치 · 닉네임 · 화면 크기", "#1A2A30", "#2E4A52", "#A9C4CB") {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        menuBox.addView(r1, Ui.lp(this, MATCH, WRAP))
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val inq = cell("✉", "관리자 문의", "1:1 · 답장 오면 빨간 숫자", "#16263A", "#2E4A6A", "#A9BFE0") {
            if (Cloud.user == null) showMessage("구글 로그인을 하면 운영자에게 1:1 문의를 할 수 있어요")
            else startActivity(Intent(this@MainActivity, InquiryActivity::class.java))
        }
        inqBadge = Ui.text(this, 10f, Color.WHITE, true, "").apply {
            gravity = Gravity.CENTER; setPadding(dp(5), 0, dp(5), 0)
            background = Ui.bg(this@MainActivity, RED, 99, Ui.BG); visibility = View.GONE
        }
        inq.addView(inqBadge, android.widget.FrameLayout.LayoutParams(WRAP, dp(18), Gravity.TOP or Gravity.END).apply { rightMargin = dp(6) })
        r2.addView(inq, LinearLayout.LayoutParams(0, WRAP, 1f))
        menuBox.addView(r2, Ui.lp(this, MATCH, WRAP, top = 2))
        menuBox.addView(Ui.button(this, "⚡ 퀵바 설정 (맨 위 네모 칸 · 켜고 끄기 · 순서)") { menuDialog?.dismiss(); openQuickSettings() },
            Ui.lp(this, MATCH, WRAP, top = 8))
        menuBox.addView(View(this).apply { setBackgroundColor(Ui.LINE) }, Ui.lp(this, MATCH, dp(1), top = 12).apply { bottomMargin = dp(10) })
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
        if (slot > 2) { showMessage("${Store.current?.name(slot) ?: ""} " + if (count == 1) "첫 손맛!" else "${count}마리 돌파! ${Milestone.titleWithCrown(tier)}"); return@lambda }
        val banner = if (slot == 1) banner1 else banner2
        val conf = if (slot == 1) confetti1 else confetti2
        banner.text = if (count == 1) "🎉 ${Milestone.titleWithCrown(tier)}!" else "${count}마리 돌파! ${Milestone.titleWithCrown(tier)}"
        banner.visibility = View.VISIBLE
        conf.start(if (tier >= 8) 90 else if (tier >= 5) 60 else 30)
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
    private fun remoteOn() = Store.anyKey()
    private var remoteOpen = false

    /** 한 번이라도 골랐는지 (예전부터 쓰던 사람은 고른 걸로) */
    private fun methodsChosen(): Boolean = Store.methodsChosen || Store.history.isNotEmpty() || remoteOn() || Store.watchMode

    private fun chip(label: String, on: Boolean) = Ui.text(this, 12f, if (on) Color.parseColor("#04222A") else Ui.SUB, on, label).apply {
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = if (on) Ui.bg(this@MainActivity, Ui.BLUE, 99) else Ui.bg(this@MainActivity, Color.TRANSPARENT, 99, Ui.LINE)
    }

    /** 리모컨 커튼: 번호(1~8)마다 [리모컨 버튼] 눌러 실제 버튼 등록 · 이름 · 지우기 · 추가 */
    private fun remoteRows() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), 0, dp(6), dp(4)) }
        box.addView(Ui.text(this, 11.5f, Ui.SUB, false,
            "[리모컨 버튼]을 누르고 리모컨에서 쓸 버튼을 한 번 누르면 등록돼요 · 길게(1초) 누르면 마지막 기록 취소"), Ui.lp(this, MATCH, WRAP, top = 6))
        val rows = Store.remoteRows
        for (n in 1..rows) {
            val code = Store.key(n)
            val waiting = ButtonRouter.learningStep == n
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(6), dp(6))
                background = Ui.bg(this@MainActivity, Color.argb(60, 0, 0, 0), 10, if (waiting) Ui.BLUE else 0)
            }
            r.addView(Ui.text(this, 13f, Color.WHITE, true, "$n").apply {
                gravity = Gravity.CENTER
                background = Ui.bg(this@MainActivity, Store.slotColor(Store.slotForRemote(n) ?: n), 99)
            }, LinearLayout.LayoutParams(dp(26), dp(26)))
            val nm = Store.keyName(n)
            val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(Ui.text(this, 14f, Ui.TEXT, true, "${n}번" + if (nm.isNotBlank()) " · $nm" else ""))
            txt.addView(Ui.text(this, 11.5f, if (code == null) Ui.SUB else Color.parseColor("#FFD79A"), false,
                when { waiting -> "리모컨 버튼을 눌러주세요…"; code == null -> "미등록"; else -> ButtonRouter.label(code) }).apply { maxLines = 1 })
            r.addView(txt, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
            fun small(label: String, act: () -> Unit) = Ui.text(this, 12f, Ui.TEXT, true, label).apply {
                gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), 0); maxLines = 1
                background = Ui.bg(this@MainActivity, Color.TRANSPARENT, 8, Ui.LINE)
                setOnClickListener { act() }
            }
            r.addView(small(if (waiting) "등록 취소" else "리모컨 버튼") {
                Store.methodsChosen = true
                if (waiting) ButtonRouter.cancelLearning() else ButtonRouter.startLearning(n)
                refresh(); renderMethods()
            }, LinearLayout.LayoutParams(WRAP, dp(32)))
            if (code != null) r.addView(small("✎") {
                val e = android.widget.EditText(this).apply { setText(nm); hint = "예: 위 큰 버튼, 왼쪽 동그라미"; setSingleLine() }
                AlertDialog.Builder(this, R.style.FlAlert)
                    .setTitle("리모컨 ${n}번 버튼 이름").setView(e)
                    .setPositiveButton("저장") { _, _ -> Store.setKeyName(n, e.text.toString().take(12)); renderMethods() }
                    .setNegativeButton("취소", null).show()
            }, LinearLayout.LayoutParams(dp(34), dp(32)).apply { leftMargin = dp(4) })
            if (code != null || (n == rows && n > 1)) r.addView(small("✕") {
                AlertDialog.Builder(this, R.style.FlAlert)
                    .setTitle(if (code != null) "리모컨 ${n}번 등록을 지울까요?" else "${n}번 줄을 뺄까요?")
                    .setPositiveButton("지우기") { _, _ ->
                        if (code != null) { Store.setKey(n, null); Store.setKeyName(n, "") }
                        if (n == Store.remoteRows && n > 1 && Store.key(n) == null) Store.remoteRows = n - 1
                        if (ButtonRouter.learningStep == n) ButtonRouter.cancelLearning()
                        renderMethods()
                    }.setNegativeButton("취소", null).show()
            }, LinearLayout.LayoutParams(dp(34), dp(32)).apply { leftMargin = dp(4) })
            box.addView(r, Ui.lp(this, MATCH, WRAP, top = 6))
        }
        if (rows < Store.MAX_REMOTE) box.addView(Ui.text(this, 13f, Ui.BLUE, true, "＋ 리모컨 버튼 추가 ($rows / ${Store.MAX_REMOTE})").apply {
            gravity = Gravity.CENTER; setPadding(0, dp(9), 0, dp(9))
            background = Ui.bg(this@MainActivity, Color.TRANSPARENT, 10, Ui.BLUE)
            setOnClickListener {
                Store.methodsChosen = true
                Store.remoteRows = rows + 1
                ButtonRouter.startLearning(rows + 1)
                refresh(); renderMethods()
            }
        }, Ui.lp(this, MATCH, WRAP, top = 8))
        // v4.1 (187): 따닥 연타는 다 세요 · 한 번 누를 때 두 번씩 세지는 리모컨이면 '느리게'
        box.addView(Ui.text(this, 13f, if (Store.remoteSlow) Color.parseColor("#1B0E02") else Ui.TEXT, true,
            (if (Store.remoteSlow) "☑" else "☐") + " 느리게 (한 번 누를 때 두 번씩 세지면 켜기)").apply {
            gravity = Gravity.CENTER; setPadding(dp(10), dp(8), dp(10), dp(8))
            background = if (Store.remoteSlow) Ui.bg(this@MainActivity, Color.parseColor("#FFB27A"), 10) else Ui.bg(this@MainActivity, Color.TRANSPARENT, 10, Ui.LINE)
            setOnClickListener {
                Store.remoteSlow = !Store.remoteSlow
                showMessage(if (Store.remoteSlow) "느리게: 같은 버튼은 0.35초 안에 다시 눌러도 한 번만 세요" else "빠르게: 따닥 연타도 다 세요")
                renderMethods()
            }
        }, Ui.lp(this, MATCH, WRAP, top = 6))
        methodBox.addView(box)
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
            left.addView(Ui.text(this, 12f, Ui.SUB, false, "기록 방식 · ${Store.catLabel(Store.current?.cat?.ifBlank { null } ?: Store.category)}"))
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val on = mutableListOf<String>()
            if (remoteOn()) on.add("리모컨")
            if (Store.touchMode) on.add("터치")
            if (Store.watchMode) on.add("워치 ⏮1 ⏭2")
            if (on.isEmpty()) on.add("선택 안 됨")
            on.forEach { chips.addView(chip(it, it != "선택 안 됨"), LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) }) }
            // v3.8: 칩을 누르면 바로 켜짐/꺼짐 (출조 중에도 바로 적용)
            chips.addView(chip(if (Store.saver) "🔋 배터리 절약" else "배터리 절약 꺼짐", Store.saver).apply {
                setOnClickListener {
                    Store.saver = !Store.saver
                    showMessage(if (Store.saver) "🔋 배터리 절약 켬 · 위치 3분 간격 · 서버 올리기 5분마다" else "배터리 절약 끔 · 원래대로")
                    renderMethods()
                }
            })
            left.addView(Ui.hscroll(this, chips),
                Ui.lp(this, MATCH, WRAP, top = 6))
            row.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))
            // v4.7 (217): '바꾸기'를 눈에 띄게 (하늘색으로 꽉 찬 버튼)
            row.addView(Ui.text(this, 14f, Color.parseColor("#04303B"), true, "⚙ 바꾸기").apply {
                gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0)
                background = Ui.bg(this@MainActivity, Ui.BLUE, 12)
                setOnClickListener { methodsOpen = true; renderMethods() }
            }, LinearLayout.LayoutParams(WRAP, dp(40)).apply { leftMargin = dp(8) })
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

        fun option(title: String, sub: String, on: Boolean, action: (() -> Unit)?, help: Pair<String, String>? = null, note: String? = null, onToggle: () -> Unit) {
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
            txt.addView(Ui.text(this, 15f, Ui.TEXT, true, title).apply {
                maxLines = 1
                // 제목 옆 작은 안내 (예: 리모컨 (음악 리모컨 전용))
                if (note != null) append(android.text.SpannableStringBuilder("  $note").apply {
                    setSpan(android.text.style.AbsoluteSizeSpan(11, true), 0, length, 0)
                    setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#FFD79A")), 0, length, 0)
                })
            })
            txt.addView(Ui.text(this, 12f, Ui.SUB, false, sub))
            r.addView(txt, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(10) })
            if (action != null) r.addView(Ui.text(this, 12f, Ui.TEXT, true, if (learning) "등록 취소" else "버튼 등록").apply {
                gravity = Gravity.CENTER; setPadding(dp(10), 0, dp(10), 0)
                background = Ui.bg(this@MainActivity, Color.TRANSPARENT, 8, Ui.LINE)
                setOnClickListener { Store.methodsChosen = true; action(); renderMethods() }
            }, LinearLayout.LayoutParams(WRAP, dp(34)))
            // ? = 이 기록 방식 설명 창
            if (help != null) r.addView(Ui.text(this, 15f, Ui.BLUE, true, "?").apply {
                gravity = Gravity.CENTER
                background = Ui.bg(this@MainActivity, Color.TRANSPARENT, 99, Ui.BLUE)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity, R.style.FlAlert)
                        .setTitle(help.first).setMessage(help.second).setPositiveButton("알겠어요", null).show()
                }
            }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { leftMargin = dp(8) })
            methodBox.addView(r, Ui.lp(this, MATCH, WRAP, top = 8))
        }
        val nKeys = (1..Store.MAX_REMOTE).count { Store.key(it) != null }
        option("리모컨", if (nKeys == 0) "버튼을 등록해 주세요 (최대 ${Store.MAX_REMOTE}개)" else "버튼 ${nKeys}개 등록 · 리모컨 N번 = N번 카드" + if (remoteOpen || learning) "  ▲" else "  ▼",
            remoteOn(), null, "🎮 어떤 리모컨이 되나요?" to
            "블루투스 음악(미디어) 리모컨만 돼요.\n버튼에 VOL+ · VOL− · ⏮ · ⏭ · ⏯ 그림이 있는 리모컨이에요. 화면을 꺼도, 다른 앱을 켜 둬도 기록돼요.\n\n" +
            "✅ 되는 것\n• 자동차 핸들 · 자전거 핸들용 음악 리모컨\n• 'Music · Media control' 리모컨\n\n" +
            "❌ 안 되는 것\n• 틱톡 · 쇼츠 넘기기 리모컨 (화면 밀기 방식)\n• 셀카 · 카메라 셔터 리모컨 (신호가 1개뿐)\n• 마우스 모드 리모컨 (화면에 화살표가 생김)\n\n" +
            "살 때 팁: 상품 설명에 'Music', 'Media', '볼륨', '이전 · 다음 곡'이 있는지 확인하세요.\n" +
            "가운데(재생) 버튼을 길게 누르면 음성 비서가 켜지는 리모컨도 있어요. '길게 = 마지막 기록 취소'는 다른 버튼으로 쓰세요.",
            "(음악 리모컨 전용)") { remoteOpen = !remoteOpen }
        if (remoteOpen || learning) remoteRows()
        option("터치", "카드를 눌러 +1", Store.touchMode, null) { Store.touchMode = !Store.touchMode }
        option("갤럭시 워치", "워치 음악 화면 ⏮ = 1번 · ⏭ = 2번", Store.watchMode, null) {
            Store.watchMode = !Store.watchMode
            if (Store.watchMode) showWatchGuide()
        }
        option("배터리 절약", "위치 3분 간격 · 서버 올리기 5분마다", Store.saver, null, "🔋 배터리 절약이란?" to
            "출조 중 위치를 재는 간격을 3분으로 늘려서 배터리를 아껴요.\n\n" +
            "• 좋은 점: 하루 종일 타도 배터리가 오래가요. 보조배터리가 없을 때 추천해요.\n\n" +
            "• 달라지는 점: 이동 경로가 듬성듬성 그려지고, 같이 출조 중인 사람들에게 보이는 내 위치가 덜 자주 바뀌어요. 서버에 올리는 것도 5분마다 모아서 해서, 친구 화면의 내 마릿수 · 칭호 알림이 최대 5분 늦게 보여요 (출조를 끝내면 남은 건 바로 올라가요).\n\n" +
            "• 바꾸기: 출조 중에도 '배터리 절약' 칩을 한 번 누르면 바로 켜지고 꺼져요.\n\n" +
            "• 그대로인 것: 조과 위치는 정확해요. 카드나 리모컨을 누르면 그 뒤 1분 동안 GPS를 촘촘히 재서 그 자리를 잡아요.\n\n" +
            "• 추천: 배터리가 넉넉하면 끄고, 오래 탈 때나 배터리가 부족하면 켜세요.") { Store.saver = !Store.saver }
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
                    val cb = Ui.checkFit(CheckBox(this)).apply {
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
            val pubBox = Ui.checkFit(CheckBox(this)).apply {
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
                // 높이는 글자에 맞춰 (최소 40) — 고정 높이면 글자를 크게 쓴 폰에서 테두리가 잘림
                fun two(a: View, b: View) = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    (a as? TextView)?.apply { minHeight = dp(40); setPadding(paddingLeft, dp(7), paddingRight, dp(7)) }
                    (b as? TextView)?.apply { minHeight = dp(40); setPadding(paddingLeft, dp(7), paddingRight, dp(7)) }
                    addView(a, LinearLayout.LayoutParams(0, WRAP, 1f)); addView(b, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(6) })
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
                        AlertDialog.Builder(this, R.style.FlAlert)
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
            val dlg = AlertDialog.Builder(this, R.style.FlAlert)
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
        val dlg = AlertDialog.Builder(this, R.style.FlAlert)
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

    private fun tripPrefs() = getSharedPreferences("fishlog", MODE_PRIVATE)
    /** 위치 공유 쓸 수 있나 (앱 위치 사용 + 위치 권한) */
    private fun locShareOk() = Store.appLoc && Tracker.granted(this)

    /**
     * 배·항구 입력. tripId == null 이면 새 출조 시작 (v4.2 · 193 · 194)
     *  - 순서: 버튼(취소 · 건너뛰기 · ▶ 출조 시작) → 배 · 항구 (나란히) → 출조 종류 → 내 출조 알리기
     *  - 처음: 그룹에 알리기 + 내 그룹 모두 체크 · 위치 권한이 있으면 📍 위치 공유 자동 체크, 없으면 자동 해제
     *  - 한 번 출조를 시작하면 이 창 내용(종류 · 알리기 · 그룹 · 위치 공유 · 배 · 항구)을 다음 기본값으로 기억
     */
    private fun askPlace(tripId: Long?) {
        val t = tripId?.let { Store.find(it) }
        val tp = tripPrefs()
        val saved = tp.getBoolean("tripSetupSaved", false)
        if (tripId == null) {
            if (!saved) {
                Store.liveScope = "groups"
                if (Store.liveGroups == null) Store.liveGroups = setOf("*")
            }
            // 위치 공유는 열 때마다 권한을 다시 확인: 권한 있으면 내가 고른 값(처음엔 체크), 없으면 해제
            Store.liveLoc = if (locShareOk()) tp.getBoolean("liveLocWant", true) else false
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        var dlg: AlertDialog? = null
        // 칸 위 작은 제목 + 칸 안 흐린 예시 (새 출조면 지난번 적은 배 · 항구)
        val boat0 = t?.boat ?: (if (saved) tp.getString("lastBoat", null) else null) ?: Store.boats().firstOrNull() ?: ""
        val port0 = t?.port ?: (if (saved) tp.getString("lastPort", null) else null) ?: Store.ports().firstOrNull() ?: ""
        val boat = suggestField("예: 테티스호", Store.boats(), boat0)
        val port = suggestField("예: 영흥도 진두항", Store.ports(), port0)

        fun remember(b: String?, p: String?) {
            tp.edit().apply {
                putBoolean("tripSetupSaved", true)
                if (locShareOk()) putBoolean("liveLocWant", Store.liveLoc)
                if (b != null) { putString("lastBoat", b); putString("lastPort", p ?: "") }
            }.apply()
            if (Store.liveScope != "none") Consent.setShareLive(this, true)
        }

        if (tripId == null) {
            // ① 버튼 맨 위
            val br = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            br.addView(Ui.button(this, "취소") { dlg?.dismiss() }.apply { minHeight = dp(46); textSize = 14f },
                LinearLayout.LayoutParams(0, WRAP, 0.85f))
            br.addView(Ui.button(this, "건너뛰기") { dlg?.dismiss(); remember(null, null); startTrip("", "") }.apply { minHeight = dp(46); textSize = 14f },
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(6) })
            br.addView(Ui.button(this, "▶ 출조 시작", filled = true) {
                val bt = boat.text.toString().trim(); val pt = port.text.toString().trim()
                dlg?.dismiss(); remember(bt, pt); startTrip(bt, pt)
            }.apply { minHeight = dp(46); textSize = 15f; background = Ui.bg(this@MainActivity, Ui.GREEN, 14) },
                LinearLayout.LayoutParams(0, WRAP, 1.4f).apply { leftMargin = dp(6) })
            box.addView(br)
            // ② 배 · 항구 나란히
            val pr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun fieldCol(label: String, f: View) = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.text(this@MainActivity, 12f, Ui.SUB, false, label))
                addView(f, Ui.lp(this@MainActivity, MATCH, WRAP))
            }
            pr.addView(fieldCol("🚤 배 이름 · 포인트", boat), LinearLayout.LayoutParams(0, WRAP, 1f))
            pr.addView(fieldCol("⚓ 항구 · 주요 장소", port), LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(10) })
            box.addView(pr, Ui.lp(this, MATCH, WRAP, top = 14))
            box.addView(Ui.text(this, 11f, Ui.SUB, false, "한 번 적은 이름은 목록에 저장돼서 다음부터 눌러서 골라요"), Ui.lp(this, MATCH, WRAP, top = 2))
            // ③ 출조 종류
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val g = if (Cloud.user == null) null else Cloud.active
            if (g != null) head.addView(GroupIcon.view(this, g, 24, GroupBand.catIcon(GroupBand.currentCat(g))), LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(8) })
            head.addView(Ui.text(this, 14f, Ui.TEXT, true, "출조 종류"))
            head.addView(Ui.text(this, 12f, Ui.SUB, false, if (g != null) "  · ${g.title}의 종류" else "  · 전체 종류"))
            box.addView(head, Ui.lp(this, MATCH, WRAP, top = 14))
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
            // ④ 🟢 내 출조 알리기
            if (Cloud.user != null && Cloud.groups.isNotEmpty()) {
                val scopeBox = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10))
                    background = Ui.bg(this@MainActivity, Ui.CARD, 12, Ui.GREEN)
                }
                fun drawScope() {
                    scopeBox.removeAllViews()
                    scopeBox.addView(Ui.text(this, 14f, Color.parseColor("#9FE7C1"), true, "🟢 내 출조 알리기"))
                    liveScopeViews(scopeBox) { drawScope() }
                    scopeBox.addView(Ui.text(this, 11f, Ui.SUB, false,
                        (if (saved) "지난번 출조 때 고른 대로 맞춰 놨어요" else "처음엔 그룹에 알리기 · 내 그룹 모두 체크") +
                            " · 출조 시작을 누르면 다음에도 이대로 · 출조 중에 메인에서 바로 바꾸기"), Ui.lp(this, MATCH, WRAP, top = 6))
                }
                drawScope()
                box.addView(scopeBox, Ui.lp(this, MATCH, WRAP, top = 14))
            }
            dlg = AlertDialog.Builder(this, R.style.FlAlert)
                .setTitle("🎣 출조 시작")
                .setView(ScrollView(this).apply { addView(box) })
                .show()
            return
        }
        // 출조 중 · 지난 출조: 배와 항구 수정
        box.addView(Ui.text(this, 12f, Ui.SUB, false, "🚤 배 이름 · 포인트"))
        box.addView(boat)
        box.addView(Ui.text(this, 12f, Ui.SUB, false, "⚓ 항구 · 주요 장소"), Ui.lp(this, MATCH, WRAP, top = 10))
        box.addView(port)
        AlertDialog.Builder(this, R.style.FlAlert)
            .setTitle("배와 항구 수정")
            .setMessage("한 번 적은 이름은 목록에 저장돼서 다음부터 고를 수 있어요")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("저장") { _, _ -> Store.setPlace(tripId, boat.text.toString(), port.text.toString()) }
            .setNegativeButton("취소", null)
            .show()
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
        AlertDialog.Builder(this, R.style.FlAlert)
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
            AlertDialog.Builder(this, R.style.FlAlert)
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
                i < list.size -> AlertDialog.Builder(this, R.style.FlAlert)
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
    private var tideBg: WxArt.CardBg? = null
    private var tideCardView: View? = null
    private var wxKey = ""
    private var wxAt = 0L

    /** 첫 줄 맨 앞: 📍안산 (지금 위치의 시 · 군 이름 짧게) */
    private fun shortPlace(loc: Pair<Double, Double>?): String {
        if (loc == null) return ""
        Spot.current?.let { return it.name }   // v5.1: 고른 지점 이름 (예: 신도)
        val full = Area.name(this, loc.first, loc.second) { refreshTideRow() }
        val w = full.trim().split(" ").firstOrNull()?.trim() ?: return ""
        return if (w.length >= 3 && (w.endsWith("시") || w.endsWith("군"))) w.dropLast(1) else w
    }

    private fun paintTideTitle(t: Long, loc: Pair<Double, Double>?) {
        val place = shortPlace(loc)
        val sb = android.text.SpannableStringBuilder()
        if (place.isNotBlank()) {
            sb.append("📍$place ")
            sb.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#FFB27A")), 0, sb.length, 0)
            sb.setSpan(android.text.style.RelativeSizeSpan(0.88f), 0, sb.length, 0)
        }
        val mul = Tide.mulName(t).substringBefore(" (")
        val m0 = sb.length
        sb.append(TideDialog.dayTitle(mul, t, mainPct, true))
        // v4.7 (217): 중요한 물때(예: 5물 · 사리)는 노란색으로 강조
        sb.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#FFD166")), m0, m0 + mul.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tideTitle.text = sb
    }

    /** 물때 카드 배경 = 지금 날씨 그림 (30분마다) */
    private fun paintTideWeather(loc: Pair<Double, Double>?) {
        if (loc == null) return
        val now = System.currentTimeMillis()
        val key = String.format(Locale.US, "%.2f,%.2f", loc.first, loc.second)
        if (key == wxKey && now - wxAt < 30 * 60_000L) return
        wxKey = key; wxAt = now
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val day0 = cal.timeInMillis
        Weather.fetch(this, loc.first, loc.second, day0) { day ->
            if (isFinishing) return@fetch
            val hr = ((System.currentTimeMillis() - day0) / 3_600_000L).toInt()
            val k = WxArt.kindOf(day.at(hr) ?: day.at(hr - 1) ?: day.at(hr + 1))
            WidgetData.saveWx(this, day.at(hr) ?: day.at(hr - 1) ?: day.at(hr + 1))
            if (k == WxArt.NONE && day.hours.isEmpty()) wxAt = 0L   // 못 받았으면 다음에 다시
            tideBg?.let { it.kind = k; it.invalidateSelf() }
            tideCardView?.invalidate()
        }
    }

    private fun refreshTideRow() {
        val now = System.currentTimeMillis()
        val loc = Spot.base(this, bestLocation())   // v5.1: 고른 지점 · 관측소 기준
        paintTideTitle(now, loc)
        paintTideWeather(loc)
        paintSun(now, loc)
        // ⚠ 기상특보 (30분마다)
        if (loc != null && now - warnAt > 30 * 60_000L) {
            warnAt = now
            Warn.forPlace(loc.first, loc.second) { w ->
                if (isFinishing) return@forPlace
                if (w.isNullOrBlank()) tideWarn.visibility = View.GONE
                else { tideWarn.text = "⚠ ${Warn.seaArea(loc.first, loc.second)} $w (기상청)"; tideWarn.visibility = View.VISIBLE }
            }
        }
        if (loc == null) { tideSub.text = "위치를 알면 만조·간조가 보여요 · 눌러서 보기"; return }
        val st = Tide.stationFor(this, loc.first, loc.second)
        val key = (st?.code ?: "") + "|" + SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(Date(now))
        if (key == strengthKey && now - strengthAt < 10 * 60_000L) return
        strengthKey = key; strengthAt = now
        Tide.strengthAt(this, loc.first, loc.second, now) { day, stg ->
            if (isFinishing) return@strengthAt
            if (day == null) { tideSub.text = "물때를 불러오지 못했어요 · 눌러서 다시 보기"; return@strengthAt }
            val t = System.currentTimeMillis()
            // v4.7 (217): '지금 날물'은 주황 꼬리표, 다음 만조 · 간조 시각과 물세기는 진하게
            val sb = android.text.SpannableStringBuilder()
            fun bold(txt: String, color: Int) { val a0 = sb.length; sb.append(txt)
                sb.setSpan(android.text.style.ForegroundColorSpan(color), a0, sb.length, 0)
                sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), a0, sb.length, 0) }
            fun sep() { if (sb.isNotEmpty()) sb.append(" · ") }
            Tide.rising(day, t)?.let { up ->
                val a0 = sb.length; sb.append(if (up) " 지금 들물 " else " 지금 날물 ")
                val c = if (up) Color.parseColor("#5FC4E0") else Color.parseColor("#F2A65A")
                sb.setSpan(android.text.style.ForegroundColorSpan(c), a0, sb.length, 0)
                sb.setSpan(android.text.style.BackgroundColorSpan(Color.argb(55, Color.red(c), Color.green(c), Color.blue(c))), a0, sb.length, 0)
                sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), a0, sb.length, 0)
            }
            Tide.extremes(day).firstOrNull { it.time > t }?.let {
                sep(); sb.append(if (it.high) "다음 만조 " else "다음 간조 ")
                bold(SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(it.time)), Color.WHITE)
            }
            stg?.let { sep(); sb.append("물세기 "); bold("${it.pct}%", if (it.pct >= 80) Ui.ORANGE else Ui.GREEN); if (it.estimated) sb.append("(추정)") }
            mainPct = stg?.pct
            paintTideTitle(t, loc)
            // v4.9 (224): 홈 화면 물때 위젯이 쓸 값 저장 (서버 다시 안 읽음)
            WidgetData.saveTide(this, loc.first, loc.second, shortPlace(loc), day, stg?.pct)
            tideSub.text = if (sb.isEmpty()) "눌러서 물때 보기" else sb
            tideSub.setTextColor(Color.parseColor("#C9D6DD"))
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
        // v3.8.1: 출조 중이 아니면 지난 출조를 열지 않고 빈 지도 + 내 위치만 (지난 조과는 '지난 기록 겹치기'를 켰을 때만)
        val t = Store.current
        if (t == null) {
            MapDialog.show(this, "🗺 지도 · 내 위치", emptyList(),
                "출조 중 아님 · 지난 조과는 '지난 기록'을 누르면 보여요")
            return
        }
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
        val loc = bestLocation() ?: Spot.base(this, null, null)
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
        if (Zones.onResult(this, requestCode, resultCode, data)) return
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
        val dlg = AlertDialog.Builder(this, R.style.FlAlert)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(wrap) })
            .setPositiveButton("닫기", null)
            .setOnDismissListener { (content.parent as? android.view.ViewGroup)?.removeView(content); onDismiss() }
            .show()
        dlg.window?.setLayout(Ui.dialogW(this), (resources.displayMetrics.heightPixels * 0.92).toInt())
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
            // 이름 옆에 어디에 알리는 중인지 꼬리표 (그룹 이름 · 전체)
            val sb = android.text.SpannableStringBuilder()
            l.take(3).forEachIndexed { i, m ->
                if (i > 0) sb.append("\n")
                sb.append(m.name)
                fun tag(t: String, bgc: Int, fg: Int) {
                    sb.append(" ")
                    val st = sb.length
                    sb.append("\u00A0$t\u00A0")
                    sb.setSpan(android.text.style.BackgroundColorSpan(bgc), st, sb.length, 0)
                    sb.setSpan(android.text.style.ForegroundColorSpan(fg), st, sb.length, 0)
                    sb.setSpan(android.text.style.RelativeSizeSpan(0.85f), st, sb.length, 0)
                }
                m.groups.take(2).forEach { tag(it, Color.parseColor("#2B4A6B"), Color.parseColor("#BFE0FF")) }
                if (m.groups.size > 2) tag("+${m.groups.size - 2}", Color.parseColor("#2B4A6B"), Color.parseColor("#BFE0FF"))
                if (m.pub) tag("전체", Color.parseColor("#5A3B12"), Color.parseColor("#FFD79A"))
            }
            sb.append((if (l.size > 3) "\n외 ${l.size - 3}명" else "") + " · 눌러서 보기")
            nowSub.text = sb
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
                                listOf(Store.catShort(m.cat).ifBlank { "출조" }, m.line, if (ago.isNotBlank()) "위치 $ago" else null).filterNotNull().joinToString(" · "),
                                m.uid, m.gids, m.pub)
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
                // 📢 어디에 알리는 중 (그룹 꼬리표 · 전체 공유) · 위치 공유 여부
                val where = android.text.SpannableStringBuilder("📢 ")
                fun tag(t: String, bgc: Int, fg: Int) {
                    val st = where.length
                    where.append("\u00A0$t\u00A0")
                    where.setSpan(android.text.style.BackgroundColorSpan(bgc), st, where.length, 0)
                    where.setSpan(android.text.style.ForegroundColorSpan(fg), st, where.length, 0)
                    where.setSpan(android.text.style.StyleSpan(Typeface.BOLD), st, where.length, 0)
                    where.append(" ")
                }
                m.groups.forEach { tag(it, Color.parseColor("#2B4A6B"), Color.parseColor("#BFE0FF")) }
                if (m.pub) tag("전체 공유" + if (m.area.isNotBlank()) " · ${m.area} 부근" else "", Color.parseColor("#5A3B12"), Color.parseColor("#FFD79A"))
                where.append(if (hasLoc) "· 📍 위치 공유 중" else "· 위치 끔")
                tx.addView(Ui.text(this, 11.5f, Ui.SUB, false, "").apply { text = where; setLineSpacing(0f, 1.25f) }, Ui.lp(this, MATCH, WRAP, top = 3))
                r.addView(tx, LinearLayout.LayoutParams(0, WRAP, 1f))
                r.addView(Ui.text(this, 13f, if (hasLoc) Ui.GREEN else Ui.SUB, true, if (hasLoc) "📍 지도 ›" else "위치 안 보임"))
                box.addView(r, Ui.lp(this, MATCH, WRAP, top = 8))
            }
            box.addView(Ui.text(this, 11f, Ui.SUB, false, "출조를 알리기로 한 사람만 보여요 · 📍 = 위치 공유 중 (누르면 그 사람 위치 지도)"), Ui.lp(this, MATCH, WRAP, top = 10))
        }
        draw(Live.allCached())
        AlertDialog.Builder(this, R.style.FlAlert)
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
                    if (on) { Store.liveLoc = false; tripPrefs().edit().putBoolean("liveLocWant", false).apply(); redraw() }
                    // 켤 때 위치가 꺼져 있으면 안내 → 켜고 돌아오면 체크
                    else checkLocation("위치 없이 마릿수만 공유", { Store.liveLoc = true; tripPrefs().edit().putBoolean("liveLocWant", true).apply(); redraw() }) { Store.liveLoc = false; redraw() }
                }
            }
            r.addView(Ui.text(this, 13f, if (on) Ui.BG else Ui.SUB, true, if (on) "✓" else "").apply {
                gravity = Gravity.CENTER
                background = if (on) Ui.bg(this@MainActivity, Ui.GREEN, 4) else Ui.bg(this@MainActivity, Color.TRANSPARENT, 4, Ui.SUB)
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            val tx = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            tx.addView(Ui.text(this, 14f, Ui.TEXT, true, "📍 위치 공유하기"))
            if (!locShareOk()) tx.addView(Ui.text(this, 11f, Color.parseColor("#FFB27A"), false,
                if (!Store.appLoc) "앱 위치 사용이 꺼져 있어서 자동으로 해제했어요 · 누르면 켜러 가기" else "위치 권한이 없어서 자동으로 해제했어요 · 누르면 권한 허용하기"))
            tx.addView(Ui.text(this, 11f, Ui.SUB, false,
                if (on) "그룹 사람들이 '지금 출조 중'에서 눌러 지도로 내 위치를 볼 수 있어요" + (if (scope == "public") " (전체 공유는 동네 이름만)" else "")
                else "끄면 '출조 중'과 마릿수만 보이고 지도엔 안 나와요"))
            r.addView(tx, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(10) })
            box.addView(r, Ui.lp(this, MATCH, WRAP, top = 4))
            // 전체 공유일 때: 정확한 위치·조과 지도까지 공유할지 (기본은 동네 이름만)
            if (scope == "public" && on) {
                val ex = Store.livePubExact
                val r2 = Ui.row(this).apply {
                    setPadding(dp(30), dp(6), 0, dp(2)); isClickable = true
                    setOnClickListener { Store.livePubExact = !ex; Live.scopeChanged(); redraw() }
                }
                r2.addView(Ui.text(this, 12f, if (ex) Ui.BG else Ui.SUB, true, if (ex) "✓" else "").apply {
                    gravity = Gravity.CENTER
                    background = if (ex) Ui.bg(this@MainActivity, Ui.ORANGE, 4) else Ui.bg(this@MainActivity, Color.TRANSPARENT, 4, Ui.SUB)
                }, LinearLayout.LayoutParams(dp(20), dp(20)))
                val t2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                t2.addView(Ui.text(this, 13f, Ui.TEXT, true, "🗺 전체에도 정확한 위치 · 조과 지도 공유"))
                t2.addView(Ui.text(this, 11f, Ui.SUB, false, "끄면 전체 사용자에겐 동네 이름만 · 지도는 관리자가 권한 준 사람만 볼 수 있어요"))
                r2.addView(t2, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(10) })
                box.addView(r2, Ui.lp(this, MATCH, WRAP, top = 2))
            }
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
        // v4.2.6 (203): 1:1 문의함은 메인 '✉ 관리자 문의' · 미니게임은 메인 '🎮 게임' 칸으로 (중복이라 뺌)
        // v4.4: 🧪 미니게임 테스트판 (새 기능 먼저 시험 → 괜찮으면 본 게임에 덮어씀)
        val items = listOf("👥 사용자 목록 · 권한 · 최소 버전 · 정지", "🐟 어종 관리", "💬 전체 채팅방 초기화", "🦀 해루질 참고 구역 (CSV 올리기 · 직접 그리기)", "🌊 물때 관측소 찾기 · 추가 · 수정 · 삭제")
        AlertDialog.Builder(this, R.style.FlAlert)
            .setTitle("🛠 관리자 메뉴")
            .setItems(items.toTypedArray()) { _, i ->
                when (i) {
                    0 -> startActivity(Intent(this, AdminActivity::class.java))
                    1 -> {
                        val cats = Store.CATS
                        BigList.show(this, "어느 종류의 어종을 관리할까요?", cats.map { Store.catLabel(it) }, emptyList()) { j, _ ->
                            cats.getOrNull(j)?.let { manageSpecies(it) }
                        }
                    }
                    2 -> AlertDialog.Builder(this, R.style.FlAlert)
                        .setTitle("전체 채팅방을 초기화할까요?")
                        .setMessage("전체 채팅방 글만 모두 지워요. 그룹 채팅방과 1:1 문의방은 그대로예요. 되돌릴 수 없어요.")
                        .setPositiveButton("초기화") { _, _ -> Chat.resetAll { err -> showMessage(err ?: "전체 채팅방을 초기화했어요") } }
                        .setNegativeButton("취소", null).show()
                    3 -> Zones.openAdmin(this)
                    4 -> StationAdmin.open(this)
                    else -> {}
                }
            }
            .setNegativeButton("닫기", null).show()
    }

    private fun openMenuDialog() {
        if (menuDialog?.isShowing == true) return
        menuBox.visibility = View.VISIBLE
        menuDialog = bigDialog("더보기", menuBox) { menuDialog = null }
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
        // 맨 아래 '그룹' 탭: 새 소식 빨간 숫자 · 내일 일정만 있으면 초록 점
        if (::tabGroupBadge.isInitialized) {
            tabGroupBadge.text = if (total > 0) total.toString() else "•"
            tabGroupBadge.background = Ui.bg(this, if (total > 0) RED else Ui.GREEN, 99, Ui.BG)
            tabGroupBadge.visibility = if (total > 0 || tomN > 0) View.VISIBLE else View.GONE
        }
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
                        AlertDialog.Builder(this@MainActivity, R.style.FlAlert)
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
                    AlertDialog.Builder(this@MainActivity, R.style.FlAlert)
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
        AlertDialog.Builder(this, R.style.FlAlert)
            .setTitle("같이 출조 중")
            .setMessage(msg)
            .setPositiveButton("확인", null).show()
    }

    /** 워치 기록을 켰을 때: 워치 음악 화면 자동 실행 설정 안내 */
    private fun showWatchGuide() {
        AlertDialog.Builder(this, R.style.FlAlert)
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
        if (::adminMenuBtn.isInitialized) adminMenuBtn.visibility = if (Cloud.isAdmin) View.VISIBLE else View.GONE
        paintQuick()
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
        status.visibility = if (t == null) View.GONE else View.VISIBLE   // v4.7: 대기 중 안내 줄은 기록 방식 줄로 합침
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
        // 대기 중: ▶(분홍 동그라미) + 크고 굵은 '출조 시작' · 주황 + 하늘 이중 테두리 (높이는 그대로 52dp)
        // 출조 중엔 빨간 바탕 · 흰 글씨 (실수로 안 누르게, 끝낼 때 바로 찾게)
        if (t == null) {
            tripBtn.text = android.text.SpannableStringBuilder("  출조 시작").apply {
                setSpan(android.text.style.ImageSpan(WxArt.PlayIcon(this@MainActivity, 20),
                    if (android.os.Build.VERSION.SDK_INT >= 29) android.text.style.DynamicDrawableSpan.ALIGN_CENTER else android.text.style.DynamicDrawableSpan.ALIGN_BASELINE),
                    0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            tripBtn.textSize = 20f; tripBtn.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            tripBtn.setTextColor(Ui.BG); tripBtn.background = WxArt.doubleBorder(this, Ui.TEXT)
        } else {
            tripBtn.text = "■ 출조 종료"; tripBtn.textSize = 15f
            tripBtn.setTextColor(Color.WHITE); tripBtn.background = Ui.bg(this, RED, 14)
        }
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
