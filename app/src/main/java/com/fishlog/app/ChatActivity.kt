package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 채팅 창: 맨 위 탭 = 전체 + 내가 가입한 그룹마다 하나
 *  - 알림 없음 (창을 열었을 때만 보임) · 안 읽은 글은 탭과 메인 '채팅' 버튼에 빨간 숫자
 *  - 전체방은 채팅 이름을 바꿀 수 있음, 그룹방은 닉네임 그대로
 *  - 운영자는 이름을 바꾼 사람도 실제 닉네임이 보임 (운영자만)
 */
class ChatActivity : Activity() {

    private fun dp(v: Int) = Ui.dp(this, v)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private val YELLOW = Color.parseColor("#F2C14E")
    private val RED = Color.parseColor("#E8505B")

    private var room = Chat.ALL
    private lateinit var tabs: LinearLayout
    private lateinit var banner: LinearLayout
    private lateinit var nameRow: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var onlineText: TextView
    private var muted: Set<String> = emptySet()
    private var lastCount = -1
    private val tf = SimpleDateFormat("HH:mm", Locale.KOREA)
    private val df = SimpleDateFormat("M/d HH:mm", Locale.KOREA)
    private val dayKey = SimpleDateFormat("yyyyMMdd", Locale.KOREA)

    private val onChange: () -> Unit = { if (!isFinishing) { renderTabs(); renderList() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        room = intent.getStringExtra("room")?.takeIf { r -> r == Chat.ALL || Cloud.groups.any { it.id == r } } ?: Chat.ALL

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(14), dp(14), dp(14), dp(10))
        }
        val top = Ui.row(this)
        top.addView(Ui.button(this, "← 뒤로") { finish() }.apply { minHeight = dp(42) }, LinearLayout.LayoutParams(WRAP, WRAP))
        top.addView(Ui.text(this, 20f, Ui.TEXT, true, "채팅"), LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(12) })
        onlineText = Ui.text(this, 12f, Ui.GREEN, true, "")
        top.addView(onlineText)
        root.addView(top)

        if (Cloud.user == null) {
            root.addView(Ui.text(this, 14f, Ui.SUB, false, "구글 로그인을 하면 채팅을 할 수 있어요"), Ui.lp(this, MATCH, WRAP, top = 16))
            setContentView(root); return
        }

        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, dp(8), 0, 0)
            addView(tabs)
        }, Ui.lp(this, MATCH, WRAP, top = 6))
        banner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(banner, Ui.lp(this, MATCH, WRAP, top = 8))
        nameRow = Ui.row(this)
        root.addView(nameRow, Ui.lp(this, MATCH, WRAP, top = 6))

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(8)) }
        scroll = ScrollView(this).apply { addView(list); isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(6) })

        val bar = Ui.row(this)
        input = EditText(this).apply {
            hint = "메시지 입력…"
            setHintTextColor(Ui.SUB); setTextColor(Ui.TEXT); textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4
            background = Ui.bg(this@ChatActivity, Ui.CARD, 14, Ui.LINE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        bar.addView(input, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(Ui.text(this, 15f, Color.parseColor("#08311F"), true, "보내기").apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            background = Ui.bg(this@ChatActivity, Ui.GREEN, 14)
            setOnClickListener { send() }
        }, LinearLayout.LayoutParams(WRAP, dp(46)).apply { leftMargin = dp(8) })
        root.addView(bar, Ui.lp(this, MATCH, WRAP, top = 4))
        root.addView(Ui.text(this, 10.5f, Ui.SUB, false,
            "내 글 꾹 누르면 수정·삭제 · 남의 글 꾹 누르면 신고·차단 · 욕설은 자동으로 ** · 최근 3일만 보여요"),
            Ui.lp(this, MATCH, WRAP, top = 4))
        setContentView(root)

        if (Cloud.isAdmin) {
            Chat.loadRealNames { if (!isFinishing) renderList() }
            Chat.mutedUids { muted = it; if (!isFinishing) renderList() }
        }
        renderAll()
    }

    override fun onResume() {
        super.onResume()
        if (Cloud.user == null) return
        Chat.start(onChange)
        Chat.startPresence { n -> if (!isFinishing) onlineText.text = "● 접속 ${n}명" }
    }

    override fun onPause() {
        if (Cloud.user != null) { Chat.markSeen(room); Chat.stop(onChange); Chat.stopPresence() }
        super.onPause()
    }

    private fun groupOf(r: String) = Cloud.groups.firstOrNull { it.id == r }
    private fun groupLabel(g: Cloud.Group) = if (g.name.trim().endsWith("그룹")) g.name else "${g.name} 그룹"

    private fun renderAll() { renderTabs(); renderBanner(); renderName(); lastCount = -1; renderList() }

    private fun renderTabs() {
        tabs.removeAllViews()
        Chat.rooms().forEach { r ->
            val on = r == room
            val label = if (r == Chat.ALL) "전체" else groupOf(r)?.let { groupLabel(it) } ?: "그룹"
            val n = if (on) 0 else Chat.unread(r)
            val box = android.widget.FrameLayout(this).apply { setPadding(0, dp(6), dp(6), 0); clipChildren = false; clipToPadding = false }
            box.addView(Ui.text(this, 13f, if (on) Color.parseColor("#08311F") else Ui.TEXT, true, (if (on && r != Chat.ALL) "🚤 " else "") + label).apply {
                setPadding(dp(13), dp(7), dp(13), dp(7))
                background = if (on) Ui.bg(this@ChatActivity, Ui.GREEN, 99) else Ui.bg(this@ChatActivity, Color.TRANSPARENT, 99, Ui.LINE)
                setOnClickListener { if (room != r) { Chat.markSeen(room); room = r; renderAll() } }
            })
            if (n > 0) box.addView(Ui.text(this, 10f, Color.WHITE, true, if (n > 99) "99+" else "$n").apply {
                gravity = Gravity.CENTER; setPadding(dp(5), 0, dp(5), 0)
                background = Ui.bg(this@ChatActivity, RED, 99)
            }, android.widget.FrameLayout.LayoutParams(WRAP, dp(16), Gravity.TOP or Gravity.END).apply { topMargin = -dp(6); rightMargin = -dp(4) })
            tabs.addView(box, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(4) })
        }
    }

    private fun renderBanner() {
        banner.removeAllViews()
        val g = groupOf(room)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        if (g == null) {
            box.background = Ui.bg(this, Ui.CARD, 12)
            box.addView(Ui.text(this, 14f, Ui.TEXT, true, "전체 채팅방"))
            box.addView(Ui.text(this, 11f, Ui.SUB, false, "앱을 쓰는 모든 사람이 봐요 · 알림 없음 (창을 열었을 때만 보여요)"))
        } else {
            box.background = Ui.bg(this, Color.parseColor("#16352A"), 12, Ui.GREEN)
            box.addView(Ui.text(this, 14f, Ui.GREEN, true, "${groupLabel(g)} 채팅방"))
            box.addView(Ui.text(this, 11f, Color.parseColor("#9FE1CB"), false, "그룹원만 볼 수 있어요 · 알림 없음"))
        }
        banner.addView(box)
        if (Cloud.isAdmin) banner.addView(Ui.text(this, 11f, YELLOW, true, "운영자 보기 · 실제 닉네임 표시 (나만 보여요)"), Ui.lp(this, MATCH, WRAP, top = 4))
        input.hint = if (g == null) "전체에 보내기…" else "${groupLabel(g)}에 보내기…"
    }

    private fun renderName() {
        nameRow.removeAllViews()
        val all = room == Chat.ALL
        nameRow.addView(Ui.text(this, 12f, Ui.SUB, false, "채팅 이름 "))
        nameRow.addView(Ui.text(this, 13f, Ui.TEXT, true, Chat.nameFor(room)), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (!all) { nameRow.addView(Ui.text(this, 11f, Ui.SUB, false, "그룹방은 닉네임 그대로")); return }
        fun pill(label: String, click: () -> Unit) = Ui.text(this, 12f, Ui.TEXT, true, label).apply {
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = Ui.bg(this@ChatActivity, Color.TRANSPARENT, 99, Ui.LINE)
            setOnClickListener { click() }
        }
        nameRow.addView(pill("바꾸기 ✎") { askName() })
        if (Chat.chatName != null) nameRow.addView(pill("닉네임으로") {
            Chat.useNickname(); renderName(); toast("이제 닉네임(${Cloud.displayName})으로 보여요")
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
    }

    private fun askName() {
        val et = EditText(this).apply {
            setText(Chat.chatName ?: ""); hint = "2~12자 (예: 바다의왕)"
            setTextColor(Ui.TEXT); setHintTextColor(Ui.SUB)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("전체 채팅방 이름")
            .setMessage("전체 채팅방에서만 이 이름으로 보여요. 그룹방과 다른 화면은 닉네임 그대로예요.")
            .setView(LinearLayout(this).apply { setPadding(dp(20), dp(4), dp(20), 0); addView(et, LinearLayout.LayoutParams(MATCH, WRAP)) })
            .setPositiveButton("저장") { _, _ ->
                Chat.setChatName(et.text.toString()) { err -> if (err != null) toast(err) else { renderName(); toast("채팅 이름을 바꿨어요") } }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun renderList() {
        if (!::list.isInitialized) return
        val msgs = Chat.messages(room)
        val atBottom = scroll.scrollY + scroll.height >= list.height - dp(60)
        list.removeAllViews()
        if (msgs.isEmpty()) {
            list.addView(Ui.text(this, 13f, Ui.SUB, false, "아직 대화가 없어요 · 첫 인사를 남겨 보세요 👋").apply { gravity = Gravity.CENTER },
                Ui.lp(this, MATCH, WRAP, top = 30))
        }
        val today = dayKey.format(Date())
        val owner = groupOf(room)?.owner
        msgs.forEach { m -> list.addView(bubble(m, today, owner), Ui.lp(this, MATCH, WRAP, top = 8)) }
        if (lastCount < 0 || msgs.size > lastCount && (atBottom || msgs.lastOrNull()?.mine == true)) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        lastCount = msgs.size
        Chat.markSeen(room)
    }

    private fun bubble(m: Chat.Msg, today: String, owner: String?): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (m.mine) Gravity.END else Gravity.START
        }
        val time = if (dayKey.format(Date(m.time)) == today) tf.format(Date(m.time)) else df.format(Date(m.time))
        val head = android.text.SpannableStringBuilder()
        if (owner != null && m.uid == owner) {
            val s = head.length; head.append("♛ ")
            head.setSpan(android.text.style.ForegroundColorSpan(YELLOW), s, head.length, 0)
        }
        head.append(if (m.mine) "나" else m.name)
        if (Cloud.isAdmin) {
            val real = Chat.realNames[m.uid]
            if (real != null && real != m.name) {
                val s = head.length; head.append(" (실제: $real)")
                head.setSpan(android.text.style.ForegroundColorSpan(YELLOW), s, head.length, 0)
            }
            if (m.uid in muted) {
                val s = head.length; head.append(" · 채팅 금지됨")
                head.setSpan(android.text.style.ForegroundColorSpan(RED), s, head.length, 0)
            }
        }
        head.append(" · ").append(time)
        if (m.edited > 0) head.append(" (수정됨)")
        col.addView(Ui.text(this, 11f, Ui.SUB, false, "").apply { text = head })
        val masked = m.text.count { it == '*' }
        col.addView(Ui.text(this, 14.5f, if (m.mine) Color.parseColor("#04342C") else Ui.TEXT, false, m.text).apply {
            setPadding(dp(11), dp(8), dp(11), dp(8))
            setLineSpacing(0f, 1.15f)
            maxWidth = (resources.displayMetrics.widthPixels * 0.78).toInt()
            background = if (m.mine) Ui.bg(this@ChatActivity, Ui.BLUE, 14) else Ui.bg(this@ChatActivity, Color.parseColor("#1E3247"), 14)
            setTextIsSelectable(false)
            setOnLongClickListener { menu(m); true }
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) })
        if (Cloud.isAdmin && masked >= 2 && !m.mine)
            col.addView(Ui.text(this, 10.5f, Color.parseColor("#FF8A92"), false, "욕설 가림 · 꾹 눌러서 삭제·채팅 금지"))
        return col
    }

    private fun menu(m: Chat.Msg) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        val groupOwner = groupOf(room)?.owner == Cloud.uid
        if (m.mine) {
            items.add("수정" to { editMsg(m) })
            items.add("삭제" to { confirmDelete(m) })
        } else {
            items.add("이 사람 신고·차단 (내 화면에서 안 보이게)" to {
                Moderation.block(m.uid, "채팅: ${m.name}") { err ->
                    toast(err ?: "차단했어요. '차단 목록'에서 풀 수 있어요"); renderList()
                }
            })
            if (Cloud.isAdmin || (room != Chat.ALL && groupOwner)) items.add("글 삭제" to { confirmDelete(m) })
            if (Cloud.isAdmin) {
                if (m.uid in muted) items.add("채팅 금지 풀기" to {
                    Chat.unmute(m.uid) { err -> toast(err ?: "채팅 금지를 풀었어요"); Chat.mutedUids { muted = it; renderList() } }
                })
                else items.add("채팅 금지 (글을 못 써요)" to {
                    val real = Chat.realNames[m.uid] ?: m.name
                    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("채팅 금지")
                        .setMessage("$real (채팅 이름: ${m.name}) 님이 모든 채팅방에 글을 못 쓰게 할까요? 나중에 풀 수 있어요.")
                        .setPositiveButton("금지") { _, _ ->
                            Chat.mute(m.uid, real) { err -> toast(err ?: "채팅 금지했어요"); Chat.mutedUids { muted = it; renderList() } }
                        }
                        .setNegativeButton("취소", null).show()
                })
            }
        }
        items.add("글 복사" to {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("chat", m.text)); toast("복사했어요")
        })
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .show()
    }

    private fun editMsg(m: Chat.Msg) {
        val et = EditText(this).apply { setText(m.text); setTextColor(Ui.TEXT); setSelection(text.length) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("글 수정")
            .setView(LinearLayout(this).apply { setPadding(dp(20), dp(4), dp(20), 0); addView(et, LinearLayout.LayoutParams(MATCH, WRAP)) })
            .setPositiveButton("저장") { _, _ -> Chat.edit(m, et.text.toString()) { err -> if (err != null) toast(err) } }
            .setNegativeButton("취소", null).show()
    }

    private fun confirmDelete(m: Chat.Msg) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage("이 글을 지울까요?")
            .setPositiveButton("삭제") { _, _ -> Chat.delete(m) { err -> if (err != null) toast(err) } }
            .setNegativeButton("취소", null).show()
    }

    private var sending = false
    private fun send() {
        if (sending) return
        val t = input.text.toString()
        if (t.isBlank()) return
        sending = true
        Chat.send(room, t) { err ->
            sending = false
            if (err != null) toast(err) else input.setText("")
        }
    }
}
