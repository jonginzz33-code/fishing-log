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
    private lateinit var stationText: TextView
    private lateinit var strengthText: TextView
    private var strengthAt = 0L
    private var strengthKey = ""
    private lateinit var groupBar: LinearLayout
    private lateinit var groupScroll: android.widget.HorizontalScrollView
    private lateinit var alarmBtn: TextView
    private lateinit var adminBtn: TextView
    private lateinit var scheduleBtn: TextView
    private var eventsFor: String? = null
    private var upcoming = 0
    private var todayEvents = 0
    private lateinit var updateBar: TextView
    private lateinit var remoteInfo: TextView
    private lateinit var message: TextView
    private lateinit var learnBtn: TextView
    private lateinit var touchBox: CheckBox
    private lateinit var saverBox: CheckBox
    private lateinit var watchBox: CheckBox
    private lateinit var menuBtn: TextView
    private lateinit var menuBox: LinearLayout
    private lateinit var recentBox: LinearLayout
    private lateinit var outerRecent: LinearLayout
    private lateinit var innerRecent: LinearLayout
    private lateinit var catRow: LinearLayout
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
    private val cloudListener: () -> Unit = { refreshAccount() }
    private var crashShown = false
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
        account = AccountUi(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        if (crashShown) return
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
        ButtonRouter.onMessage = { showMessage(it) }
        if (Store.current != null) RemoteService.start(this)
        if (!cloudLoaded && Cloud.user != null) {
            cloudLoaded = true
            Cloud.refresh()
            Cloud.backupPending()
        }
        Cloud.loadSpecies()
        Tide.prune(this)
        Photos.cleanOldShareCopies(this)
        eventsFor = null
        if (!Updater.checkedThisRun) {
            Updater.checkedThisRun = true
            Updater.check(this) { v -> if (v != null) updateBar.visibility = View.VISIBLE }
        }
        refresh()
        refreshAccount()
        handler.post(ticker)
    }

    override fun onPause() {
        if (crashShown) { super.onPause(); return }
        Store.removeListener(storeListener)
        Cloud.removeListener(cloudListener)
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
        header.addView(smallBtn("물때") { openTodayTide() }, LinearLayout.LayoutParams(dp(56), dp(48)).apply { leftMargin = dp(8) })
        header.addView(Ui.text(this, 20f, Ui.TEXT, true, "조과기록"),
            LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(10) })
        // 가입한 그룹: 옆으로 밀어서 보고 눌러서 바꾸기
        groupBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        groupScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(groupBar)
        }
        header.addView(groupScroll, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8); rightMargin = dp(6) })
        header.addView(Ui.text(this, 18f, Ui.TEXT, true, "?").apply {
            gravity = Gravity.CENTER
            background = Ui.bg(this@MainActivity, Ui.CARD, 99, Ui.LINE)
            setOnClickListener { startActivity(Intent(this@MainActivity, HelpActivity::class.java)) }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        col.addView(header)

        // 낚시 종류: 선상 · 해루질 · 워킹 (선택된 걸 한 번 더 누르면 어종 관리)
        catRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(catRow, Ui.lp(this, MATCH, WRAP, top = 12))
        if (Cloud.isAdmin) col.addView(Ui.text(this, 11f, Ui.SUB, false, "관리자: 선택된 종류를 한 번 더 누르면 어종 추가·삭제"), Ui.lp(this, MATCH, WRAP, top = 4))

        placeText = Ui.text(this, 17f, Ui.TEXT, true).apply {
            setOnClickListener { Store.current?.let { askPlace(it.id) } }
        }
        col.addView(placeText, Ui.lp(this, MATCH, WRAP, top = 12))
        // 물때 지역 + 만조·간조 알람
        val tideRow = Ui.row(this)
        stationText = Ui.text(this, 13f, Ui.BLUE, true).apply {
            minHeight = dp(36); gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { pickStation() }
        }
        tideRow.addView(stationText, LinearLayout.LayoutParams(0, WRAP, 1f))
        alarmBtn = smallBtn("") { pickAlarm() }.apply { textSize = 12f; setPadding(dp(10), 0, dp(10), 0) }
        tideRow.addView(alarmBtn, LinearLayout.LayoutParams(WRAP, dp(36)).apply { leftMargin = dp(8) })
        col.addView(tideRow, Ui.lp(this, MATCH, WRAP, top = 2))
        // 물세기 (누르면 물때 그래프 + 시간별 물흐름)
        strengthText = Ui.text(this, 14f, Ui.TEXT, true, "").apply {
            visibility = View.GONE
            setOnClickListener { openTodayTide() }
        }
        col.addView(strengthText, Ui.lp(this, MATCH, WRAP, top = 2))
        status = Ui.text(this, 14f, Ui.SUB)
        col.addView(status, Ui.lp(this, MATCH, WRAP, top = 2))

        // 리모컨 카드
        val remote = Ui.card(this)
        remoteInfo = Ui.text(this, 14f)
        message = Ui.text(this, 13f, Ui.GREEN).apply { visibility = View.GONE }
        learnBtn = Ui.button(this, "리모컨 버튼 등록") {
            if (ButtonRouter.learningStep == 0) ButtonRouter.startLearning() else ButtonRouter.cancelLearning()
            refresh()
        }.apply { minHeight = dp(44); textSize = 14f }
        touchBox = CheckBox(this).apply {
            text = "터치로 기록 (리모컨 없을 때 카드를 눌러 +1)"
            setTextColor(Ui.TEXT); textSize = 15f
            buttonTintList = ColorStateList.valueOf(Ui.BLUE)
            minHeight = dp(44)
            setOnCheckedChangeListener { _, checked -> if (checked != Store.touchMode) Store.touchMode = checked }
        }
        remote.addView(remoteInfo)
        remote.addView(message, Ui.lp(this, MATCH, WRAP, top = 6))
        remote.addView(learnBtn, Ui.lp(this, MATCH, WRAP, top = 10))
        saverBox = CheckBox(this).apply {
            text = "배터리 절약 모드 (이동 경로를 3분 간격으로)"
            setTextColor(Ui.TEXT); textSize = 15f
            buttonTintList = ColorStateList.valueOf(Ui.BLUE)
            minHeight = dp(44)
            setOnCheckedChangeListener { _, checked -> if (checked != Store.saver) Store.saver = checked }
        }
        remote.addView(touchBox, Ui.lp(this, MATCH, WRAP, top = 6))
        watchBox = CheckBox(this).apply {
            text = "갤럭시 워치로 기록 (워치 음악 화면 ⏮ = 1번, ⏭ = 2번)"
            setTextColor(Ui.TEXT); textSize = 15f
            buttonTintList = ColorStateList.valueOf(Ui.BLUE)
            minHeight = dp(44)
            setOnCheckedChangeListener { _, checked ->
                if (checked != Store.watchMode) {
                    Store.watchMode = checked
                    if (checked) showWatchGuide()
                }
            }
        }
        remote.addView(watchBox, Ui.lp(this, MATCH, WRAP))
        remote.addView(saverBox, Ui.lp(this, MATCH, WRAP))
        col.addView(remote, Ui.lp(this, MATCH, WRAP, top = 14))

        // 카운터
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(counterCard(1, Ui.ORANGE, Color.parseColor("#1B0E02")), Ui.lp(this, 0, dp(190), 1f))
        row.addView(View(this), Ui.lp(this, dp(12), 1))
        row.addView(counterCard(2, Ui.BLUE, Color.parseColor("#04222A")), Ui.lp(this, 0, dp(190), 1f))
        col.addView(row, Ui.lp(this, MATCH, WRAP, top = 14))
        col.addView(Ui.text(this, 12f, Ui.SUB, false,
            "어종 이름(흰 칸)을 누르면 언제든 변경 · 리모컨 버튼을 길게(1초) 누르면 마지막 기록 취소"),
            Ui.lp(this, MATCH, WRAP, top = 8))

        tripBtn = Ui.button(this, "출조 시작", filled = true) { onTripButton() }
        undoBtn = Ui.button(this, "마지막 기록 취소") {
            if (Store.undoLast() != 0) { Haptics.undo(this); showMessage("마지막 기록을 취소했어요") }
        }
        col.addView(tripBtn, Ui.lp(this, MATCH, WRAP, top = 16))
        col.addView(undoBtn, Ui.lp(this, MATCH, WRAP, top = 10))

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

        // 메뉴 ▼ : 아래 항목들을 접고 펼치기
        menuBtn = Ui.button(this, "메뉴 ▼") {
            Store.menuOpen = !Store.menuOpen
            paintMenu()
        }
        col.addView(menuBtn, Ui.lp(this, MATCH, WRAP, top = 12))
        menuBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(menuBox, Ui.lp(this, MATCH, WRAP))
        innerRecent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        menuBox.addView(innerRecent, Ui.lp(this, MATCH, WRAP))

        historyBtn = Ui.button(this, "지난 출조 · 내 기록 보관함") {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        menuBox.addView(historyBtn, Ui.lp(this, MATCH, WRAP, top = 12))
        scheduleBtn = Ui.button(this, "그룹 일정 확인") {
            if (Cloud.active == null) showMessage("그룹에 가입하면 일정을 볼 수 있어요")
            else startActivity(Intent(this, ScheduleActivity::class.java))
        }
        menuBox.addView(scheduleBtn, Ui.lp(this, MATCH, WRAP, top = 10))
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
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.bg(this@MainActivity, color, 20)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            setOnClickListener {
                if (!Store.touchMode) { showMessage("터치 기록이 꺼져 있어요 (위 체크박스)"); return@setOnClickListener }
                if (Store.current == null) { showMessage("먼저 '출조 시작'을 눌러주세요"); return@setOnClickListener }
                Store.addCatch(slot)
                Haptics.slot(this@MainActivity, slot)
            }
        }
        card.addView(Ui.text(this, 12f, ink, true, "버튼 $slot").apply {
            background = Ui.bg(this@MainActivity, Color.argb(36, 0, 0, 0), 99)
            setPadding(dp(10), dp(3), dp(10), dp(3))
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        val name = Ui.text(this, 20f, ink, true).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = Ui.bg(this@MainActivity, Color.argb(60, 255, 255, 255), 10)
            setOnClickListener { askRename(slot) }
        }
        card.addView(name, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
        card.addView(View(this), Ui.lp(this, MATCH, 0, 1f))
        val count = Ui.text(this, 60f, ink, true).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
        card.addView(count)
        val last = Ui.text(this, 12f, ink)
        card.addView(last)
        if (slot == 1) { name1 = name; count1 = count; last1 = last } else { name2 = name; count2 = count; last2 = last }
        return card
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
        shown.forEachIndexed { i, c ->
            val on = c == sel
            catRow.addView(Ui.text(this, 15f, if (on) Ui.BG else Ui.TEXT, true, Store.catLabel(c)).apply {
                gravity = Gravity.CENTER
                minHeight = dp(44)
                background = if (on) Ui.bg(this@MainActivity, Ui.TEXT, 22) else Ui.bg(this@MainActivity, Ui.CARD, 22, Ui.LINE)
                setOnClickListener {
                    if (on) { if (Cloud.isAdmin) manageSpecies(c) else showMessage("어종 추가·삭제는 관리자만 할 수 있어요") }
                    else if (Store.current != null) showMessage("출조 중에는 종류를 바꿀 수 없어요 (종료 후 변경)")
                    else Store.category = c
                }
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
    }

    private fun refreshTideRow() {
        val loc = bestLocation()
        val st = Tide.stationFor(this, loc?.first, loc?.second)
        val km = st?.let { Tide.stationKm(it, loc?.first, loc?.second) }
        val dist = km?.let { String.format(Locale.KOREA, " · 내 위치에서 %.0fkm", it) } ?: ""
        val stale = if (loc != null && !freshLocation()) " (마지막 위치 기준)" else ""
        stationText.text = when {
            st == null -> "물때 기준: 위치 확인 중 · 눌러서 고르기 ▾"
            Store.tideStation != null -> "물때 기준: ${st.name} 관측소 (직접 고름)$dist ▾"
            else -> "물때 기준: ${st.name} 관측소$dist$stale ▾"
        }
        val m = Store.tideAlarmMin
        alarmBtn.text = if (m <= 0) "설정 (만조·간조 알람)" else "알람 ${m}분 전 ⏰"
        refreshStrength(loc, st?.code ?: "")
    }

    /** 물세기: 10분마다 또는 물때 지역이 바뀌면 다시 계산 */
    private fun refreshStrength(loc: Pair<Double, Double>?, code: String) {
        if (loc == null) return
        val now = System.currentTimeMillis()
        val key = code + "|" + SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(Date(now))
        if (key == strengthKey && now - strengthAt < 10 * 60_000L) return
        strengthKey = key; strengthAt = now
        Tide.strengthAt(this, loc.first, loc.second, now) { day, st ->
            if (isFinishing || day == null || st == null) return@strengthAt
            strengthText.text = TideDialog.strengthLine(day, st) + "  ›"
            strengthText.setTextColor(if (st.pct >= 80) Ui.ORANGE else Ui.TEXT)
            strengthText.visibility = View.VISIBLE
        }
    }

    private fun pickStation() {
        val loc = bestLocation()
        val list = Tide.stationsNear(this, loc?.first, loc?.second)
        val labels = list.map { s ->
            val km = Tide.stationKm(s, loc?.first, loc?.second)
            (if (s.code == Store.tideStation) "✓ " else "") + s.name + (km?.let { String.format(Locale.KOREA, "  %.0fkm", it) } ?: "")
        }
        val auto = (if (Store.tideStation == null) "✓ " else "") + "가장 가까운 곳 자동 (추천)"
        BigList.show(this, "물때 지역 (조위관측소)", listOf(auto) + labels, emptyList()) { i, _ ->
            Store.tideStation = if (i == 0) null else list[i - 1].code
            refreshTideRow()
            showMessage(if (i == 0) "가장 가까운 관측소로 물때를 봐요" else "${list[i - 1].name} 관측소로 물때를 봐요")
        }
    }

    private fun pickAlarm() {
        val mins = listOf(0, 10, 20, 30, 40, 50, 60)
        val labels = mins.map { (if (it == Store.tideAlarmMin) "✓ " else "") + if (it == 0) "끄기" else "만조·간조 ${it}분 전" }
        BigList.show(this, "만조·간조 알람 (출조 중에만 울려요)", labels, emptyList()) { i, _ ->
            Store.tideAlarmMin = mins[i]
            refreshTideRow()
            showMessage(if (mins[i] == 0) "알람을 껐어요" else "출조 중 만조·간조 ${mins[i]}분 전에 알려드려요")
        }
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
        TideDialog.show(this, loc.first, loc.second, System.currentTimeMillis(), Store.current)
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

    /** 그룹 일정 버튼: 다가오는 일정 개수 + 강조 */
    private fun refreshSchedule() {
        val gid = Cloud.active?.id
        if (gid == null) { eventsFor = null; return }
        if (eventsFor != gid) {
            eventsFor = gid
            upcoming = 0; todayEvents = 0
            Cloud.loadEvents { list, _ ->
                if (Cloud.active?.id != gid || list == null) return@loadEvents
                val today = SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(Date())
                upcoming = list.count { it.date >= today }
                todayEvents = list.count { it.date == today }
                paintSchedule()
            }
        }
        paintSchedule()
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
        val badge = when {
            Cloud.active == null -> ""
            todayEvents > 0 -> " · 오늘 출조!"
            upcoming > 0 -> " · 일정 $upcoming"
            else -> ""
        }
        menuBtn.text = if (Store.menuOpen) "메뉴 접기 ▲" else "메뉴 ▼$badge"
        when {
            !Store.menuOpen && todayEvents > 0 -> { menuBtn.setTextColor(Ui.BG); menuBtn.background = Ui.bg(this, Ui.GREEN, 14) }
            !Store.menuOpen && upcoming > 0 -> { menuBtn.setTextColor(Ui.GREEN); menuBtn.background = Ui.bg(this, Color.TRANSPARENT, 14, Ui.GREEN) }
            else -> { menuBtn.setTextColor(Ui.TEXT); menuBtn.background = Ui.bg(this, Color.TRANSPARENT, 14, Ui.LINE) }
        }
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

    private fun paintSchedule() {
        paintMenu()
        when {
            todayEvents > 0 -> {
                scheduleBtn.text = "그룹 일정 확인 ($upcoming) · 오늘 출조!"
                scheduleBtn.setTextColor(Ui.BG)
                scheduleBtn.background = Ui.bg(this, Ui.GREEN, 14)
            }
            upcoming > 0 -> {
                scheduleBtn.text = "그룹 일정 확인 ($upcoming)"
                scheduleBtn.setTextColor(Ui.GREEN)
                scheduleBtn.background = Ui.bg(this, Color.TRANSPARENT, 14, Ui.GREEN)
            }
            else -> {
                scheduleBtn.text = "그룹 일정 확인"
                scheduleBtn.setTextColor(Ui.TEXT)
                scheduleBtn.background = Ui.bg(this, Color.TRANSPARENT, 14, Ui.LINE)
            }
        }
    }

    private fun renderHeaderGroups() {
        groupBar.removeAllViews()
        val gs = if (Cloud.user == null) emptyList() else Cloud.groups
        if (gs.isEmpty()) { groupScroll.visibility = View.INVISIBLE; return }
        groupScroll.visibility = View.VISIBLE
        val activeId = Cloud.active?.id
        var activeView: View? = null
        gs.forEach { g ->
            val on = g.id == activeId
            val label = if (g.name.trim().endsWith("그룹")) g.name else "${g.name} 그룹"
            val chip = Ui.text(this, 13f, if (on) Ui.BG else Ui.TEXT, true, label).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(12), 0, dp(12), 0)
                background = if (on) Ui.bg(this@MainActivity, Ui.GREEN, 99) else Ui.bg(this@MainActivity, Ui.CARD, 99, Ui.LINE)
                setOnClickListener {
                    if (on) startActivity(Intent(this@MainActivity, GroupActivity::class.java))
                    else if (Store.current != null && Store.current?.catches?.isNotEmpty() == true)
                        AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("'${g.name}' 그룹으로 바꿀까요?")
                            .setMessage("지금 출조는 종료할 때 바뀐 그룹에 올라가요")
                            .setPositiveButton("바꾸기") { _, _ -> Cloud.setActive(g.id); showMessage("'${g.name}' 그룹으로 바꿨어요") }
                            .setNegativeButton("취소", null)
                            .show()
                    else { Cloud.setActive(g.id); showMessage("'${g.name}' 그룹으로 바꿨어요") }
                }
            }
            if (on) activeView = chip
            groupBar.addView(chip, LinearLayout.LayoutParams(WRAP, dp(36)).apply { rightMargin = dp(6) })
        }
        activeView?.let { v -> groupScroll.post { groupScroll.smoothScrollTo((v.left - dp(8)).coerceAtLeast(0), 0) } }
    }

    private fun refreshAccount() {
        if (!::accountInfo.isInitialized) return
        renderHeaderGroups()
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
        scheduleBtn.visibility = if (Cloud.active != null) View.VISIBLE else View.GONE
        refreshSchedule()
        renderCats()
    }

    private fun showMessage(msg: String) {
        message.text = msg
        message.visibility = View.VISIBLE
        handler.removeCallbacks(hideMessage)
        if (ButtonRouter.learningStep == 0) handler.postDelayed(hideMessage, 4000)
        refreshRemote()
    }

    private val hideMessage = Runnable { message.visibility = View.GONE }

    // ---------------- 화면 갱신 ----------------

    private fun updateStatus() {
        val t = Store.current
        status.text = if (t == null) {
            "대기 중 · '출조 시작'을 누르면 기록을 시작해요"
        } else {
            val sec = (System.currentTimeMillis() - t.start) / 1000
            String.format(Locale.KOREA, "출조 중 · %s 시작 · 경과 %02d:%02d:%02d%s",
                timeFmt.format(Date(t.start)).substring(0, 5), sec / 3600, (sec / 60) % 60, sec % 60,
                if (Tracker.running) " · 위치 기록 중" else "")
        }
    }

    private fun refreshRemote() {
        remoteInfo.text = "리모컨  1번: ${ButtonRouter.label(Store.key1)}   2번: ${ButtonRouter.label(Store.key2)}"
        learnBtn.text = if (ButtonRouter.learningStep == 0) "리모컨 버튼 등록" else "등록 취소"
    }

    private fun refresh() {
        updateStatus()
        refreshRemote()
        renderCats()
        refreshTideRow()
        val t = Store.current
        placeText.text = when {
            t == null -> Tide.mulName(System.currentTimeMillis())
            t.place().isBlank() -> "배·항구 입력하기 ✎"
            else -> t.place() + "  ✎"
        }
        name1.text = (t?.name(1) ?: Store.defaultName(1)) + "  ✎"
        name2.text = (t?.name(2) ?: Store.defaultName(2)) + "  ✎"
        if (touchBox.isChecked != Store.touchMode) touchBox.isChecked = Store.touchMode
        if (saverBox.isChecked != Store.saver) saverBox.isChecked = Store.saver
        if (watchBox.isChecked != Store.watchMode) watchBox.isChecked = Store.watchMode
        count1.text = (t?.count(1) ?: 0).toString()
        count2.text = (t?.count(2) ?: 0).toString()
        last1.text = t?.catches?.lastOrNull { it.slot == 1 }?.let { "마지막 " + timeFmt.format(Date(it.time)) } ?: " "
        last2.text = t?.catches?.lastOrNull { it.slot == 2 }?.let { "마지막 " + timeFmt.format(Date(it.time)) } ?: " "
        tripBtn.text = if (t == null) "출조 시작" else "출조 종료"
        undoBtn.visibility = if (t == null) View.GONE else View.VISIBLE
        placeRecent()

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
