package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 관리자 전용: 신고 누적 기준 · 신고 누적 목록 · 정지 관리 · 사용자 목록 */
class AdminActivity : Activity() {

    private lateinit var listBox: LinearLayout
    private val fmt = SimpleDateFormat("yyyy.M.d HH:mm", Locale.KOREA)
    private val dfmt = SimpleDateFormat("yy.M.d", Locale.KOREA)
    private fun dp(v: Int) = Ui.dp(this, v)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private var users: List<Cloud.AppUser> = emptyList()
    private var bans: List<Moderation.Ban> = emptyList()
    private var reports: Map<String, Int> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Cloud.isAdmin) { finish(); return }
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        val col = Ui.column(this)
        val top = Ui.row(this)
        top.addView(Ui.button(this, "← 뒤로") { finish() }.apply { minHeight = dp(44) }, LinearLayout.LayoutParams(WRAP, WRAP))
        top.addView(Ui.text(this, 20f, Ui.TEXT, true, "관리자"), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(14) })
        col.addView(top)
        col.addView(Ui.button(this, "새로고침") { load() }.apply { minHeight = dp(44); textSize = 14f }, Ui.lp(this, MATCH, WRAP, top = 12))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox, Ui.lp(this, MATCH, WRAP, top = 6))
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(col) })
        load()
    }

    private fun ago(t: Long): String {
        if (t <= 0) return "기록 없음 (예전 버전)"
        val m = (System.currentTimeMillis() - t) / 60_000L
        return when {
            m < 10 -> "방금 접속"
            m < 60 -> "${m}분 전"
            m < 24 * 60 -> "${m / 60}시간 전"
            else -> "${m / (24 * 60)}일 전"
        }
    }

    private fun section(title: String) =
        listBox.addView(Ui.text(this, 17f, Ui.TEXT, true, title), Ui.lp(this, MATCH, WRAP, top = 22))

    private fun load() {
        listBox.removeAllViews()
        listBox.addView(Ui.text(this, 14f, Ui.SUB, false, "불러오는 중…"), Ui.lp(this, MATCH, WRAP, top = 10))
        var left = 4
        var err: String? = null
        fun step() { left--; if (left == 0) render(err) }
        Cloud.adminUsers { list, e -> users = list ?: emptyList(); if (list == null) err = e; step() }
        Moderation.loadBans { list, e -> bans = list ?: emptyList(); if (list == null && err == null) err = e; step() }
        Moderation.reportCounts { m -> reports = m; step() }
        Moderation.loadLimit { step() }
    }

    private fun nameOf(uid: String): String = users.firstOrNull { it.uid == uid }?.name ?: bans.firstOrNull { it.uid == uid }?.name ?: "알 수 없음"

    private fun render(err: String?) {
        listBox.removeAllViews()
        if (err != null) listBox.addView(Ui.text(this, 13f, Ui.ORANGE, false, err), Ui.lp(this, MATCH, WRAP, top = 10))
        renderLimit()
        renderReports()
        renderBans()
        renderUsers()
    }

    // ---------------- 신고 누적 기준 ----------------
    private fun renderLimit() {
        section("신고 누적 기준")
        val c = Ui.card(this)
        c.addView(Ui.text(this, 13f, Ui.SUB, false,
            "여기 적은 수 이상의 사람이 차단하면, 그 사람 글이 모든 사용자의 조황·포인트 계산에서 빠져요"))
        val r = Ui.row(this)
        val input = EditText(this).apply {
            setText(Moderation.reportLimit.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Ui.TEXT); textSize = 18f
            gravity = Gravity.CENTER
        }
        r.addView(input, LinearLayout.LayoutParams(dp(90), WRAP))
        r.addView(Ui.text(this, 15f, Ui.TEXT, false, "명 이상"), LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        r.addView(Ui.button(this, "저장", true) {
            val v = input.text.toString().trim().toIntOrNull()
            if (v == null || v < 1) { toast("1 이상 숫자를 적어 주세요"); return@button }
            Moderation.saveLimit(v) { e -> toast(e ?: "저장했어요 · 이제 ${v}명 이상 차단하면 집계에서 빠져요"); if (e == null) render(null) }
        }.apply { minHeight = dp(44) }, LinearLayout.LayoutParams(WRAP, WRAP))
        c.addView(r, Ui.lp(this, MATCH, WRAP, top = 8))
        listBox.addView(c, Ui.lp(this, MATCH, WRAP, top = 8))
    }

    // ---------------- 신고 누적 목록 ----------------
    private fun renderReports() {
        val limit = Moderation.reportLimit
        val list = reports.entries.filter { it.value > 0 }.sortedByDescending { it.value }
        section("신고 누적 목록 (${list.size})")
        if (list.isEmpty()) {
            listBox.addView(Ui.text(this, 13f, Ui.SUB, false, "차단당한 사람이 없어요"), Ui.lp(this, MATCH, WRAP, top = 6))
            return
        }
        list.forEach { (uid, n) ->
            val c = Ui.card(this)
            val r = Ui.row(this)
            r.addView(Ui.text(this, 16f, Ui.TEXT, true, nameOf(uid)), LinearLayout.LayoutParams(0, WRAP, 1f))
            r.addView(Ui.chip(this, "⚠ 신고 $n", if (n >= limit) Ui.ORANGE else Ui.SUB))
            c.addView(r)
            users.firstOrNull { it.uid == uid }?.email?.takeIf { it.isNotBlank() }?.let {
                c.addView(Ui.text(this, 12f, Ui.SUB, false, it), Ui.lp(this, MATCH, WRAP, top = 2))
            }
            c.addView(Ui.text(this, 12f, if (n >= limit) Ui.ORANGE else Ui.SUB, false,
                if (n >= limit) "기준(${limit}명) 이상 → 모든 사용자 집계에서 빠지는 중" else "기준 ${limit}명 미만 → 집계에 포함"),
                Ui.lp(this, MATCH, WRAP, top = 4))
            val btns = Ui.row(this)
            val banned = bans.any { it.uid == uid }
            if (!banned) btns.addView(Ui.button(this, "이용 정지") { askBan(uid) }.apply { minHeight = dp(42); textSize = 14f },
                LinearLayout.LayoutParams(0, WRAP, 1f))
            else btns.addView(Ui.text(this, 13f, Ui.ORANGE, true, "정지된 계정"), LinearLayout.LayoutParams(0, WRAP, 1f))
            btns.addView(Ui.button(this, "신고 초기화") {
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("신고 수를 0으로 할까요?")
                    .setMessage("다시 모든 사용자의 집계에 들어가요. 각자 개인적으로 차단한 건 그대로예요.")
                    .setPositiveButton("초기화") { _, _ -> Moderation.resetReports(uid) { e -> toast(e ?: "초기화했어요"); load() } }
                    .setNegativeButton("취소", null)
                    .show()
            }.apply { minHeight = dp(42); textSize = 14f }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
            c.addView(btns, Ui.lp(this, MATCH, WRAP, top = 8))
            listBox.addView(c, Ui.lp(this, MATCH, WRAP, top = 8))
        }
    }

    // ---------------- 정지 관리 ----------------
    private fun linkedOf(b: Moderation.Ban): List<Moderation.Ban> =
        if (b.device.isBlank()) emptyList() else bans.filter { it.uid != b.uid && it.device == b.device }

    private fun renderBans() {
        // 같은 폰으로 묶인 계정은 한 줄로
        val shown = mutableListOf<Moderation.Ban>()
        val seen = mutableSetOf<String>()
        bans.forEach { b ->
            if (b.uid in seen) return@forEach
            seen.add(b.uid); linkedOf(b).forEach { seen.add(it.uid) }
            shown.add(b)
        }
        section("정지 관리 (${shown.size})")
        if (shown.isEmpty()) {
            listBox.addView(Ui.text(this, 13f, Ui.SUB, false, "정지된 사람이 없어요"), Ui.lp(this, MATCH, WRAP, top = 6))
            return
        }
        shown.forEach { b ->
            val linked = linkedOf(b)
            val sameDevice = if (b.device.isBlank()) emptyList()
                else users.filter { it.device == b.device && it.uid != b.uid && linked.none { l -> l.uid == it.uid } }
            val c = Ui.card(this)
            val r = Ui.row(this)
            r.addView(Ui.text(this, 16f, Ui.TEXT, true, b.name), LinearLayout.LayoutParams(0, WRAP, 1f))
            r.addView(Ui.chip(this, "정지", Ui.ORANGE))
            c.addView(r)
            val info = buildString {
                append(dfmt.format(Date(b.time))).append(" 정지")
                val n = reports[b.uid] ?: 0
                if (n > 0) append(" · 신고 ").append(n)
                append(if (b.device.isNotBlank()) " · 폰도 정지" else " · 계정만 (폰 정보 없음)")
            }
            c.addView(Ui.text(this, 13f, Ui.SUB, false, info), Ui.lp(this, MATCH, WRAP, top = 4))
            if (b.reason.isNotBlank()) c.addView(Ui.text(this, 13f, Ui.TEXT, false, "사유: ${b.reason}"), Ui.lp(this, MATCH, WRAP, top = 2))
            if (linked.isNotEmpty() || sameDevice.isNotEmpty()) {
                c.addView(Ui.text(this, 12f, Ui.SUB, false, "같은 폰으로 쓴 계정: " +
                    (linked.map { it.name } + sameDevice.map { it.name + " (정지 전 접속)" }).joinToString(", ")),
                    Ui.lp(this, MATCH, WRAP, top = 2))
            }
            c.addView(Ui.button(this, "정지 해제") {
                val all = listOf(b.uid) + linked.map { it.uid }
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("정지를 해제할까요?")
                    .setMessage("${b.name}" + (if (linked.isNotEmpty()) " 외 ${linked.size}개 계정" else "") +
                        (if (b.device.isNotBlank()) "과 이 폰" else "") + "의 정지가 풀려요. 앱을 다시 켜면 정상적으로 쓸 수 있어요.")
                    .setPositiveButton("해제") { _, _ -> Moderation.unban(all, b.device) { e -> toast(e ?: "정지를 해제했어요"); load() } }
                    .setNegativeButton("취소", null)
                    .show()
            }.apply { minHeight = dp(42); textSize = 14f }, Ui.lp(this, MATCH, WRAP, top = 8))
            listBox.addView(c, Ui.lp(this, MATCH, WRAP, top = 8))
        }
    }

    private fun askBan(uid: String) {
        if (uid == Cloud.uid) { toast("관리자 자신은 정지할 수 없어요"); return }
        val u = users.firstOrNull { it.uid == uid }
        val name = u?.name ?: nameOf(uid)
        val device = u?.device ?: ""
        val extra = if (device.isBlank()) emptyList() else users.filter { it.device == device && it.uid != uid }.map { it.uid to it.name }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), 0) }
        box.addView(Ui.text(this, 14f, Ui.TEXT, false,
            (if (device.isNotBlank()) "이 계정과 이 사람의 폰을 같이 정지해요. 앱을 켜면 '이용이 제한된 계정입니다' 화면만 나와요."
            else "이 계정을 정지해요. (폰 정보가 없어요 — 예전 버전 앱이라 계정만 정지돼요)") +
                (if (extra.isNotEmpty()) "\n같은 폰으로 쓴 계정도 같이 정지: " + extra.joinToString(", ") { it.second } else "")))
        val input = EditText(this).apply { hint = "사유 메모 (선택)"; setTextColor(Ui.TEXT); inputType = InputType.TYPE_CLASS_TEXT }
        box.addView(input, Ui.lp(this, MATCH, WRAP, top = 10))
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("$name 이용 정지")
            .setView(box)
            .setPositiveButton("정지") { _, _ ->
                Moderation.ban(uid, name, device, input.text.toString().trim(), extra) { e -> toast(e ?: "정지했어요"); load() }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ---------------- 사용자 목록 ----------------
    private fun renderUsers() {
        section("사용자 (${users.size})")
        val day = System.currentTimeMillis() - 24 * 3600_000L
        val week = System.currentTimeMillis() - 7 * 24 * 3600_000L
        val sum = Ui.row(this)
        sum.addView(Ui.stat(this, "전체", "${users.size}명"), LinearLayout.LayoutParams(0, WRAP, 1f))
        sum.addView(Ui.stat(this, "오늘", "${users.count { it.lastSeen > day }}명"), LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        sum.addView(Ui.stat(this, "7일", "${users.count { it.lastSeen > week }}명"), LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        listBox.addView(sum, Ui.lp(this, MATCH, WRAP, top = 10))
        users.forEach { u ->
            val c = Ui.card(this)
            val r = Ui.row(this)
            r.addView(Ui.text(this, 16f, Ui.TEXT, true, u.name), LinearLayout.LayoutParams(0, WRAP, 1f))
            r.addView(Ui.text(this, 13f, if (u.lastSeen > day) Ui.GREEN else Ui.SUB, true, ago(u.lastSeen)))
            c.addView(r)
            if (u.email.isNotBlank()) c.addView(Ui.text(this, 13f, Ui.SUB, false, u.email), Ui.lp(this, MATCH, WRAP, top = 2))
            val info = buildString {
                if (u.lastSeen > 0) append("마지막 접속 ").append(fmt.format(Date(u.lastSeen)))
                if (u.version.isNotBlank()) append(" · 앱 v").append(u.version)
                if (u.groups > 0) append(" · 그룹 ").append(u.groups).append("개")
                val n = reports[u.uid] ?: 0
                if (n > 0) append(" · 신고 ").append(n)
            }
            if (info.isNotBlank()) c.addView(Ui.text(this, 12f, Ui.SUB, false, info), Ui.lp(this, MATCH, WRAP, top = 4))
            if (u.uid != Cloud.uid) {
                if (bans.any { it.uid == u.uid }) c.addView(Ui.text(this, 13f, Ui.ORANGE, true, "정지됨 · 위 '정지 관리'에서 해제"), Ui.lp(this, MATCH, WRAP, top = 6))
                else c.addView(Ui.button(this, "이용 정지") { askBan(u.uid) }.apply { minHeight = dp(40); textSize = 13f }, Ui.lp(this, MATCH, WRAP, top = 8))
            }
            listBox.addView(c, Ui.lp(this, MATCH, WRAP, top = 8))
        }
    }
}
