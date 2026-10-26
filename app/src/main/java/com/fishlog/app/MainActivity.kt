package com.fishlog.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    // 색상 (디자인 시안과 동일)
    private val cBg = Color.parseColor("#0B1A26")
    private val cCard = Color.parseColor("#12293A")
    private val cLine = Color.parseColor("#3A5A70")
    private val cText = Color.parseColor("#EAF2F6")
    private val cSub = Color.parseColor("#9FB4C2")
    private val cOrange = Color.parseColor("#F28C38")
    private val cBlue = Color.parseColor("#5BC8DF")
    private val cGreen = Color.parseColor("#5FD39A")

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    private val dateFmt = SimpleDateFormat("M월 d일 (E) HH:mm", Locale.KOREA)

    private lateinit var status: TextView
    private lateinit var remoteInfo: TextView
    private lateinit var message: TextView
    private lateinit var learnBtn: TextView
    private lateinit var touchBox: CheckBox
    private lateinit var name1: TextView
    private lateinit var name2: TextView
    private lateinit var count1: TextView
    private lateinit var count2: TextView
    private lateinit var last1: TextView
    private lateinit var last2: TextView
    private lateinit var tripBtn: TextView
    private lateinit var undoBtn: TextView
    private lateinit var recent: TextView
    private lateinit var historyBox: LinearLayout
    private lateinit var accountInfo: TextView
    private lateinit var accountBtn: TextView
    private lateinit var hideRouteBox: CheckBox
    private lateinit var account: AccountUi
    private val cloudListener: () -> Unit = { refreshAccount() }
    private var cloudLoaded = false

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1000)
        }
    }
    private val storeListener: () -> Unit = { refresh() }
    private var crashShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 지난번에 앱이 죽었다면 원인을 먼저 보여줌 (캡처해서 보내면 바로 고칠 수 있게)
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
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        if (crashShown) return
        Store.addListener(storeListener)
        Cloud.addListener(cloudListener)
        ButtonRouter.onMessage = { showMessage(it) }
        if (Store.current != null) RemoteService.start(this)
        if (!cloudLoaded && Cloud.user != null) { cloudLoaded = true; Cloud.refresh() }
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

    /** 화면이 켜져 있을 때 리모컨 입력은 여기로 먼저 들어옴 */
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun bg(color: Int, radius: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    private fun text(size: Float, color: Int = cText, bold: Boolean = false): TextView =
        TextView(this).apply {
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun button(label: String, fill: Int, textColor: Int, stroke: Int = 0): TextView =
        text(16f, textColor, true).apply {
            this.text = label
            gravity = Gravity.CENTER
            minHeight = dp(54)
            background = bg(fill, 14, stroke)
            isClickable = true
            isFocusable = false
        }

    private fun lp(w: Int, h: Int, weight: Float = 0f, top: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        // 상단: 왼쪽 지도 버튼 + 제목
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mapBtn = text(14f, cText, true).apply {
            text = "지도"
            gravity = Gravity.CENTER
            background = bg(cCard, 12, cLine)
            setOnClickListener { openMap() }
        }
        header.addView(mapBtn, LinearLayout.LayoutParams(dp(56), dp(48)))
        header.addView(text(24f, bold = true).apply { text = "조과기록" },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(12) })
        col.addView(header)
        status = text(14f, cSub)
        col.addView(status, lp(MATCH, WRAP, top = 6))

        // 친구 공유 카드
        val acc = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(cCard, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        accountInfo = text(14f)
        accountBtn = button("구글 로그인", Color.TRANSPARENT, cText, cLine).apply {
            minHeight = dp(44)
            textSize = 14f
            setOnClickListener {
                if (Cloud.user == null) account.signIn { refreshAccount() } else account.openGroups()
            }
        }
        hideRouteBox = CheckBox(this).apply {
            text = "그룹에 올릴 때 이동 경로 숨기기"
            setTextColor(cText)
            textSize = 14f
            buttonTintList = android.content.res.ColorStateList.valueOf(cBlue)
            minHeight = dp(44)
            setOnCheckedChangeListener { _, checked -> if (checked != Store.hideRoute) Store.hideRoute = checked }
        }
        acc.addView(accountInfo)
        acc.addView(accountBtn, lp(MATCH, WRAP, top = 10))
        acc.addView(hideRouteBox, lp(MATCH, WRAP, top = 4))
        col.addView(acc, lp(MATCH, WRAP, top = 16))

        // 리모컨 카드
        val remote = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(cCard, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        remoteInfo = text(14f)
        message = text(13f, cGreen).apply { visibility = View.GONE }
        learnBtn = button("리모컨 버튼 등록", Color.TRANSPARENT, cText, cLine).apply {
            minHeight = dp(44)
            textSize = 14f
            setOnClickListener {
                if (ButtonRouter.learningStep == 0) ButtonRouter.startLearning()
                else ButtonRouter.cancelLearning()
                refresh()
            }
        }
        touchBox = CheckBox(this).apply {
            text = "터치로 기록 (리모컨 없을 때 카드를 눌러 +1)"
            setTextColor(cText)
            textSize = 15f
            buttonTintList = android.content.res.ColorStateList.valueOf(cBlue)
            minHeight = dp(44)
            setOnCheckedChangeListener { _, checked -> if (checked != Store.touchMode) Store.touchMode = checked }
        }
        remote.addView(remoteInfo)
        remote.addView(message, lp(MATCH, WRAP, top = 6))
        remote.addView(learnBtn, lp(MATCH, WRAP, top = 10))
        remote.addView(touchBox, lp(MATCH, WRAP, top = 6))
        col.addView(remote, lp(MATCH, WRAP, top = 16))

        // 카운터 2개
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val c1 = counterCard(1, cOrange, Color.parseColor("#1B0E02"))
        val c2 = counterCard(2, cBlue, Color.parseColor("#04222A"))
        row.addView(c1, lp(0, dp(190), 1f))
        row.addView(View(this), lp(dp(12), 1))
        row.addView(c2, lp(0, dp(190), 1f))
        col.addView(row, lp(MATCH, WRAP, top = 16))
        col.addView(text(12f, cSub).apply {
            text = "어종 이름(흰 칸)을 누르면 언제든 이름 변경 · 리모컨 버튼을 길게(1초) 누르면 마지막 기록 취소"
        }, lp(MATCH, WRAP, top = 8))

        // 출조 버튼들
        tripBtn = button("출조 시작", cText, cBg).apply { setOnClickListener { onTripButton() } }
        undoBtn = button("마지막 기록 취소", Color.TRANSPARENT, cText, cLine).apply {
            setOnClickListener {
                if (Store.undoLast() != 0) { Haptics.undo(this@MainActivity); showMessage("마지막 기록을 취소했어요") }
            }
        }
        col.addView(tripBtn, lp(MATCH, WRAP, top = 16))
        col.addView(undoBtn, lp(MATCH, WRAP, top = 10))

        // 최근 기록
        col.addView(text(16f, bold = true).apply { text = "최근 기록" }, lp(MATCH, WRAP, top = 24))
        recent = text(15f, Color.parseColor("#CFE0EA")).apply {
            background = bg(cCard, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.3f)
        }
        col.addView(recent, lp(MATCH, WRAP, top = 8))

        // 지난 출조
        col.addView(text(16f, bold = true).apply { text = "지난 출조" }, lp(MATCH, WRAP, top = 24))
        historyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(historyBox, lp(MATCH, WRAP, top = 8))

        return ScrollView(this).apply {
            setBackgroundColor(cBg)
            isFillViewport = true
            addView(col)
        }
    }

    private fun counterCard(slot: Int, color: Int, ink: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(color, 20)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            setOnClickListener {
                if (!Store.touchMode) { showMessage("터치 기록이 꺼져 있어요 (위 체크박스)"); return@setOnClickListener }
                if (Store.current == null) { showMessage("먼저 '출조 시작'을 눌러주세요"); return@setOnClickListener }
                Store.addCatch(slot)
                Haptics.slot(this@MainActivity, slot)
            }
        }
        card.addView(text(12f, ink, true).apply {
            text = "버튼 $slot"
            background = bg(Color.argb(36, 0, 0, 0), 99)
            setPadding(dp(10), dp(3), dp(10), dp(3))
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        val name = text(20f, ink, true).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = bg(Color.argb(60, 255, 255, 255), 10)
            setOnClickListener { askRename(slot) }
        }
        card.addView(name, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
        card.addView(View(this), lp(MATCH, 0, 1f))
        val count = text(60f, ink, true).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
        card.addView(count)
        val last = text(12f, ink)
        card.addView(last)
        if (slot == 1) { name1 = name; count1 = count; last1 = last } else { name2 = name; count2 = count; last2 = last }
        return card
    }

    // ---------------- 동작 ----------------

    private fun onTripButton() {
        val t = Store.current
        if (t == null) {
            if (!Tracker.hasPermission(this)) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 2)
            }
            Store.startTrip()
            RemoteService.start(this)
            showMessage("출조 시작! 화면을 꺼도 리모컨으로 기록돼요")
        } else {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("출조를 종료할까요?")
                .setMessage("${t.name(1)} ${t.count(1)}마리 · ${t.name(2)} ${t.count(2)}마리")
                .setPositiveButton("종료") { _, _ ->
                    val ended = Store.endTrip()
                    RemoteService.stop(this)
                    // 로그인 + 그룹이 있으면 자동으로 그룹에 올림 (인터넷 없으면 연결될 때 올라감)
                    if (ended != null && Cloud.user != null && Cloud.active != null) account.upload(ended)
                }
                .setNegativeButton("계속", null)
                .show()
        }
    }

    private fun askRename(slot: Int, tripId: Long? = null) {
        val currentName = if (tripId == null) {
            Store.current?.name(slot) ?: Store.defaultName(slot)
        } else {
            Store.history.firstOrNull { it.id == tripId }?.name(slot) ?: return
        }
        val input = EditText(this).apply {
            setText(if (currentName == "${slot}번") "" else currentName)
            setSelection(text.length)
            hint = "예: 갑오징어"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("버튼 $slot 어종 이름")
            .setMessage(if (tripId == null && Store.current == null) "다음 출조부터 이 이름으로 기록돼요"
                else "이미 기록된 것까지 모두 이 이름으로 바뀌어요")
            .setView(input)
            .setPositiveButton("저장") { _, _ ->
                val v = input.text.toString().trim()
                if (tripId == null) Store.rename(slot, v) else Store.renameInHistory(tripId, slot, v)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun openHistory(t: Store.Trip) {
        val items = arrayOf(
            "지도 보기 (경로·조과 위치)",
            if (t.uploaded) "그룹에 다시 올리기" else "그룹에 올리기",
            "함께한 출조 보기",
            "기록 공유하기 (카톡 등 텍스트)",
            "버튼 1 이름 바꾸기", "버튼 2 이름 바꾸기", "삭제"
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(dateFmt.format(Date(t.start)))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> MapDialog.showTrip(this, t)
                    1 -> account.upload(t)
                    2 -> account.openTogetherFor(t)
                    3 -> share(t)
                    4 -> askRename(1, t.id)
                    5 -> askRename(2, t.id)
                    6 -> AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("이 출조 기록을 삭제할까요?")
                        .setPositiveButton("삭제") { _, _ -> Store.deleteTrip(t.id) }
                        .setNegativeButton("취소", null)
                        .show()
                }
            }
            .show()
    }

    /** 좌측 상단 지도 버튼: 출조 중이면 지금 출조, 아니면 가장 최근 출조 */
    private fun openMap() {
        val t = Store.current ?: Store.history.firstOrNull()
        if (t == null) { showMessage("출조를 시작하면 지도에 경로와 조과 위치가 표시돼요"); return }
        MapDialog.showTrip(this, t)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            if (Tracker.hasPermission(this)) {
                if (Store.current != null) RemoteService.start(this) // 위치 기록 켜기
            } else {
                showMessage("위치 권한이 없으면 지도에 경로가 표시되지 않아요 (조과 기록은 정상)")
            }
        }
    }

    private fun refreshAccount() {
        if (!::accountInfo.isInitialized) return
        val u = Cloud.user
        if (u == null) {
            accountInfo.text = "친구와 조과 공유 · 로그인하면 그룹에 올리고 함께 볼 수 있어요"
            accountBtn.text = "구글 로그인"
        } else {
            val g = Cloud.active
            accountInfo.text = if (g == null) "${Cloud.displayName} · 아직 그룹이 없어요"
            else "${Cloud.displayName} · 그룹 '${g.name}' (초대 코드 ${g.code})"
            accountBtn.text = if (g == null) "그룹 만들기 / 가입" else "그룹 · 친구 출조 보기"
        }
        if (hideRouteBox.isChecked != Store.hideRoute) hideRouteBox.isChecked = Store.hideRoute
    }

    private fun share(t: Store.Trip) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, Store.exportText(t))
        }
        startActivity(Intent.createChooser(send, "조과 공유"))
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
            String.format(Locale.KOREA, "출조 중 · %s 시작 · 경과 %02d:%02d:%02d",
                timeFmt.format(Date(t.start)).substring(0, 5), sec / 3600, (sec / 60) % 60, sec % 60)
        }
    }

    private fun refreshRemote() {
        remoteInfo.text = "리모컨  1번: ${ButtonRouter.label(Store.key1)}   2번: ${ButtonRouter.label(Store.key2)}"
        learnBtn.text = if (ButtonRouter.learningStep == 0) "리모컨 버튼 등록" else "등록 취소"
    }

    private fun refresh() {
        updateStatus()
        refreshRemote()
        val t = Store.current
        name1.text = (t?.name(1) ?: Store.defaultName(1)) + "  ✎"
        name2.text = (t?.name(2) ?: Store.defaultName(2)) + "  ✎"
        if (touchBox.isChecked != Store.touchMode) touchBox.isChecked = Store.touchMode
        count1.text = (t?.count(1) ?: 0).toString()
        count2.text = (t?.count(2) ?: 0).toString()
        last1.text = t?.catches?.lastOrNull { it.slot == 1 }?.let { "마지막 " + timeFmt.format(Date(it.time)) } ?: " "
        last2.text = t?.catches?.lastOrNull { it.slot == 2 }?.let { "마지막 " + timeFmt.format(Date(it.time)) } ?: " "
        tripBtn.text = if (t == null) "출조 시작" else "출조 종료"
        undoBtn.visibility = if (t == null) View.GONE else View.VISIBLE

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

        historyBox.removeAllViews()
        if (Store.history.isEmpty()) {
            historyBox.addView(text(14f, cSub).apply { text = "종료한 출조가 여기에 쌓여요" })
        }
        Store.history.take(30).forEach { h ->
            val dur = (h.end - h.start) / 60000
            val v = text(15f).apply {
                text = "${dateFmt.format(Date(h.start))} · ${dur / 60}시간 ${dur % 60}분" +
                    (if (h.uploaded) " · 그룹에 올림" else "") + "\n" +
                    "${h.name(1)} ${h.count(1)} · ${h.name(2)} ${h.count(2)} · 총 ${h.catches.size}"
                background = bg(cCard, 14)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setLineSpacing(0f, 1.25f)
                isClickable = true
                setOnClickListener { openHistory(h) }
            }
            historyBox.addView(v, lp(MATCH, WRAP, top = 8))
        }
    }
}
