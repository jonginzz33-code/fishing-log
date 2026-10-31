package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
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

    /** 지금 채우는 칸 (section 이 바꿈) */
    private lateinit var listBox: LinearLayout
    private lateinit var rootBox: LinearLayout
    /** 펼친 항목 (처음엔 모두 접힘) */
    private val openSecs = HashSet<String>()
    private val openUsers = HashSet<String>()
    private var userQuery = ""
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
        rootBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listBox = rootBox
        col.addView(rootBox, Ui.lp(this, MATCH, WRAP, top = 6))
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

    /** 커튼바: 제목 + 한 줄 요약, 누르면 아래 내용 펼치기·접기 */
    private fun section(title: String, summary: String = "") {
        val key = title.substringBefore(" (")
        val open = key in openSecs
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = if (open) View.VISIBLE else View.GONE }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = Ui.bg(this@AdminActivity, Ui.CARD, 12, if (open) Ui.GREEN else 0)
        }
        val tv = Ui.text(this, 16f, Ui.TEXT, true, title)
        if (summary.isNotBlank()) tv.append(android.text.SpannableStringBuilder("  · $summary").apply {
            setSpan(android.text.style.AbsoluteSizeSpan(12, true), 0, length, 0)
            setSpan(android.text.style.ForegroundColorSpan(Ui.SUB), 0, length, 0)
        })
        head.addView(tv, LinearLayout.LayoutParams(0, WRAP, 1f))
        val arrow = Ui.text(this, 15f, if (open) Ui.GREEN else Ui.SUB, true, if (open) "▴" else "▾")
        head.addView(arrow)
        head.setOnClickListener {
            val now = body.visibility != View.VISIBLE
            body.visibility = if (now) View.VISIBLE else View.GONE
            if (now) openSecs.add(key) else openSecs.remove(key)
            arrow.text = if (now) "▴" else "▾"; arrow.setTextColor(if (now) Ui.GREEN else Ui.SUB)
            head.background = Ui.bg(this, Ui.CARD, 12, if (now) Ui.GREEN else 0)
        }
        rootBox.addView(head, Ui.lp(this, MATCH, WRAP, top = 8))
        rootBox.addView(body, Ui.lp(this, MATCH, WRAP))
        listBox = body
    }

    private fun load() {
        listBox.removeAllViews()
        listBox.addView(Ui.text(this, 14f, Ui.SUB, false, "불러오는 중…"), Ui.lp(this, MATCH, WRAP, top = 10))
        var left = 7
        var err: String? = null
        fun step() { left--; if (left == 0) render(err) }
        Cloud.adminUsers { list, e -> users = list ?: emptyList(); if (list == null) err = e; step() }
        Moderation.loadBans { list, e -> bans = list ?: emptyList(); if (list == null && err == null) err = e; step() }
        Moderation.reportCounts { m -> reports = m; step() }
        Moderation.loadLimit { step() }
        Cloud.loadCats { step() }
        Cloud.loadMinVersion { v -> minVer = v; step() }
        Cloud.allPerms { m -> perms = m; step() }
    }
    private var perms: Map<String, Set<String>> = emptyMap()

    private var nickReqs: List<Cloud.NickRequest> = emptyList()

    // ---------------- 닉네임 변경 요청 ----------------
    private fun renderNickRequests() {
        section("닉네임 변경 요청 (${nickReqs.size})")
        if (nickReqs.isEmpty()) {
            listBox.addView(Ui.text(this, 13f, Ui.SUB, false, "대기 중인 요청이 없어요"), Ui.lp(this, MATCH, WRAP, top = 6))
            return
        }
        val df = java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.KOREA)
        nickReqs.forEach { r ->
            val c = Ui.card(this)
            c.addView(Ui.text(this, 15f, Ui.TEXT, true, "${r.old.ifBlank { "(없음)" }} → ${r.new}"))
            c.addView(Ui.text(this, 12f, Ui.SUB, false,
                listOf(r.realName, r.email, df.format(java.util.Date(r.time))).filter { it.isNotBlank() }.joinToString(" · ") +
                    (if (r.reason.isNotBlank()) "\n이유: ${r.reason}" else "")), Ui.lp(this, MATCH, WRAP, top = 2))
            val row = Ui.row(this)
            row.addView(Ui.button(this, "승인", true) {
                Cloud.approveNick(r) { e -> toast(e ?: "'${r.new}'(으)로 바꿨어요"); load() }
            }.apply { minHeight = dp(40); textSize = 13f }, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(Ui.button(this, "거절") {
                Cloud.rejectNick(r) { e -> toast(e ?: "거절했어요"); load() }
            }.apply { minHeight = dp(40); textSize = 13f }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
            c.addView(row, Ui.lp(this, MATCH, WRAP, top = 8))
            listBox.addView(c, Ui.lp(this, MATCH, WRAP, top = 8))
        }
    }

    private fun nameOf(uid: String): String = users.firstOrNull { it.uid == uid }?.name ?: bans.firstOrNull { it.uid == uid }?.name ?: "알 수 없음"

    private fun render(err: String?) {
        rootBox.removeAllViews()
        listBox = rootBox
        if (err != null) listBox.addView(Ui.text(this, 13f, Ui.ORANGE, false, err), Ui.lp(this, MATCH, WRAP, top = 10))
        renderMinVersion()
        renderCatAdmin()
        renderLimit()
        renderReports()
        renderBans()
        renderUsers()
    }

    // ---------------- 최소 버전 (옛날 버전 막기) ----------------
    private var minVer = 0L
    private fun renderMinVersion() {
        section("최소 버전 (옛날 버전 막기)", if (minVer > 0) "번호 $minVer 보다 낮은 앱 막는 중" else "막지 않음")
        val c = Ui.card(this)
        val mine = Updater.currentCode(this)
        c.addView(Ui.text(this, 14f, Ui.TEXT, true,
            if (minVer > 0) "지금 막는 중: 버전 번호 $minVer 보다 낮은 앱" else "지금은 막지 않아요"))
        c.addView(Ui.text(this, 12f, Ui.SUB, false,
            "내 앱 버전 ${BuildConfigInfo.VERSION} (번호 $mine) · 막힌 사람은 '업데이트해야 쓸 수 있어요' 창이 떠요 · 출조 중에는 막지 않고 끝난 뒤에 막아요 · 1.9.7보다 옛날 앱은 이 기능이 없어서 못 막아요"),
            Ui.lp(this, MATCH, WRAP, top = 4))
        val r = Ui.row(this)
        r.addView(Ui.button(this, "내 버전보다 낮은 버전 막기", true) {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("버전 ${BuildConfigInfo.VERSION}보다 옛날 앱을 막을까요?")
                .setMessage("그 사람들은 업데이트해야 앱을 쓸 수 있어요. 새 버전이 GitHub에 올라간 뒤에 막아 주세요.")
                .setPositiveButton("막기") { _, _ -> Cloud.setMinVersion(mine) { e -> toast(e ?: "막았어요"); load() } }
                .setNegativeButton("취소", null).show()
        }.apply { minHeight = dp(44); textSize = 13f }, LinearLayout.LayoutParams(0, WRAP, 1.4f))
        r.addView(Ui.button(this, "막기 풀기") {
            Cloud.setMinVersion(0) { e -> toast(e ?: "막기를 풀었어요"); load() }
        }.apply { minHeight = dp(44); textSize = 13f }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        c.addView(r, Ui.lp(this, MATCH, WRAP, top = 10))
        listBox.addView(c, Ui.lp(this, MATCH, WRAP, top = 8))
    }

    // ---------------- 출조 종류 관리 (추가·이름·순서·숨기기) ----------------
    private fun renderCatAdmin() {
        section("출조 종류 관리", "${Store.allCats().size}개")
        val c = Ui.card(this)
        c.addView(Ui.text(this, 12f, Ui.SUB, false,
            "여기서 바꾸면 모든 사람 앱에 반영돼요 (앱을 다시 켤 때) · 숨긴 종류는 고를 수 없지만 지난 기록 이름은 그대로 보여요"))
        val list = Store.allCats()
        list.forEachIndexed { i, cat ->
            // 첫 줄: 이름 / 둘째 줄: ▲ ▼ 이름 숨기기 삭제
            c.addView(Ui.text(this, 15f, if (cat.hidden) Ui.SUB else Ui.TEXT, true,
                cat.label + (if (cat.short.isNotBlank() && cat.short != cat.label) " (${cat.short})" else "") + (if (cat.hidden) " · 숨김" else "")),
                Ui.lp(this, MATCH, WRAP, top = if (i == 0) 10 else 14))
            val r = Ui.row(this)
            fun mini(label: String, color: Int = Ui.TEXT, onClick: () -> Unit) = Ui.text(this, 12f, color, true, label).apply {
                gravity = Gravity.CENTER; setPadding(dp(6), 0, dp(6), 0)
                background = Ui.bg(this@AdminActivity, android.graphics.Color.TRANSPARENT, 8, if (color == Ui.TEXT) Ui.LINE else color)
                setOnClickListener { onClick() }
            }
            r.addView(mini("▲") { if (i > 0) save(list.toMutableList().apply { add(i - 1, removeAt(i)) }) }, LinearLayout.LayoutParams(0, dp(34), 1f))
            r.addView(mini("▼") { if (i < list.size - 1) save(list.toMutableList().apply { add(i + 1, removeAt(i)) }) },
                LinearLayout.LayoutParams(0, dp(34), 1f).apply { leftMargin = dp(6) })
            r.addView(mini("이름") { askCat(cat) { l, sh -> save(list.map { if (it.id == cat.id) Store.Cat(it.id, l, sh, it.hidden) else it }) } },
                LinearLayout.LayoutParams(0, dp(34), 1.2f).apply { leftMargin = dp(6) })
            r.addView(mini(if (cat.hidden) "보이기" else "숨기기") {
                if (!cat.hidden && list.count { !it.hidden } <= 1) { toast("종류는 하나 이상 있어야 해요"); return@mini }
                save(list.map { if (it.id == cat.id) Store.Cat(it.id, it.label, it.short, !it.hidden) else it })
            }, LinearLayout.LayoutParams(0, dp(34), 1.4f).apply { leftMargin = dp(6) })
            r.addView(mini("삭제", android.graphics.Color.parseColor("#F07AA0")) { askDelete(list, cat) },
                LinearLayout.LayoutParams(0, dp(34), 1.2f).apply { leftMargin = dp(6) })
            c.addView(r, Ui.lp(this, MATCH, WRAP, top = 6))
        }
        c.addView(Ui.button(this, "+ 출조 종류 추가", true) {
            askCat(null) { l, sh ->
                if (list.any { it.label.replace(" ", "") == l.replace(" ", "") }) { toast("이미 있는 이름이에요"); return@askCat }
                save(list + Store.Cat("c" + java.lang.Long.toString(System.currentTimeMillis(), 36), l, sh))
            }
        }.apply { minHeight = dp(44) }, Ui.lp(this, MATCH, WRAP, top = 12))
        c.addView(Ui.text(this, 11f, Ui.SUB, false, "새 종류의 어종은 메인 메뉴 → '어종 관리 (관리자)'에서 넣어 주세요\n" +
            "숨기기 = 목록에서만 잠깐 빼기 (다시 보이기 가능) · 삭제 = 아예 없애기 (지난 기록엔 '(지운 종류)'로 보여요)"), Ui.lp(this, MATCH, WRAP, top = 6))
        listBox.addView(c, Ui.lp(this, MATCH, WRAP, top = 8))
    }

    private fun save(list: List<Store.Cat>) {
        Cloud.saveCats(list) { e -> toast(e ?: "출조 종류를 저장했어요"); render(null) }
    }

    /** 삭제: 이 종류를 쓰는 내 출조·그룹 수를 알려주고 한 번 더 확인 */
    private fun askDelete(list: List<Store.Cat>, cat: Store.Cat) {
        if (list.count { !it.hidden && it.id != cat.id } < 1) { toast("종류는 하나 이상 있어야 해요"); return }
        val trips = Store.history.count { it.cat == cat.id }
        val groups = Cloud.groups.count { cat.id in it.types }
        val warn = listOfNotNull(if (trips > 0) "내 폰의 출조 ${trips}개가 이 종류예요" else null,
            if (groups > 0) "이 종류를 쓰는 내 그룹이 ${groups}개 있어요" else null)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("'${cat.label}'을(를) 지울까요?")
            .setMessage((if (warn.isEmpty()) "" else warn.joinToString("\n") + "\n\n") +
                "지운 뒤에도 지난 기록은 남고, 종류 이름만 '(지운 종류)'로 보여요.\n잠깐 빼 두려면 '숨기기'를 쓰세요.")
            .setPositiveButton("삭제") { _, _ -> save(list.filter { it.id != cat.id }) }
            .setNegativeButton("취소", null).show()
    }

    /** 이름 · 짧은 이름 (예: 원투 낚시 / 원투) */
    private fun askCat(old: Store.Cat?, done: (String, String) -> Unit) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), 0) }
        val name = EditText(this).apply { hint = "이름 (예: 원투 낚시)"; inputType = InputType.TYPE_CLASS_TEXT; setSingleLine(); setText(old?.label ?: "") }
        val short = EditText(this).apply { hint = "짧은 이름 (예: 원투, 비우면 자동)"; inputType = InputType.TYPE_CLASS_TEXT; setSingleLine(); setText(old?.short ?: "") }
        col.addView(name); col.addView(short)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (old == null) "출조 종류 추가" else "이름 바꾸기")
            .setView(col)
            .setPositiveButton("저장") { _, _ ->
                val l = name.text.toString().trim()
                if (l.isEmpty()) { toast("이름을 적어 주세요"); return@setPositiveButton }
                val sh = short.text.toString().trim().ifBlank { l.replace("낚시", "").trim().ifBlank { l } }
                done(l, sh)
            }
            .setNegativeButton("취소", null).show()
    }

    // ---------------- 신고 누적 기준 ----------------
    private fun renderLimit() {
        section("신고 누적 기준", "${Moderation.reportLimit}명 이상")
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
        val day = System.currentTimeMillis() - 24 * 3600_000L
        val week = System.currentTimeMillis() - 7 * 24 * 3600_000L
        section("사용자 (${users.size})", "오늘 ${users.count { it.lastSeen > day }} · 7일 ${users.count { it.lastSeen > week }}")
        val sum = Ui.row(this)
        sum.addView(Ui.stat(this, "전체", "${users.size}명"), LinearLayout.LayoutParams(0, WRAP, 1f))
        sum.addView(Ui.stat(this, "오늘", "${users.count { it.lastSeen > day }}명"), LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        sum.addView(Ui.stat(this, "7일", "${users.count { it.lastSeen > week }}명"), LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        listBox.addView(sum, Ui.lp(this, MATCH, WRAP, top = 10))
        // 이름·이메일 찾기
        val usersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listBox.addView(EditText(this).apply {
            hint = "🔍 이름 · 닉네임 · 이메일 찾기"; setText(userQuery); setSingleLine()
            setTextColor(Ui.TEXT); setHintTextColor(Ui.SUB); textSize = 14f
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(x: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(x: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) { userQuery = e?.toString()?.trim() ?: ""; drawUsers(usersBox) }
            })
        }, Ui.lp(this, MATCH, WRAP, top = 8))
        listBox.addView(usersBox, Ui.lp(this, MATCH, WRAP))
        drawUsers(usersBox)
    }

    /** 사용자: 한 사람 한 줄, 누르면 상세·권한·정지 */
    private fun drawUsers(box: LinearLayout) {
        box.removeAllViews()
        val day = System.currentTimeMillis() - 24 * 3600_000L
        val q = userQuery.lowercase()
        users.filter { u -> q.isBlank() || listOf(u.nick ?: "", u.realName, u.email).any { it.lowercase().contains(q) } }.forEach { u ->
            val c = Ui.card(this)
            val open = u.uid in openUsers
            val r = Ui.row(this).apply {
                isClickable = true
                setOnClickListener { if (u.uid in openUsers) openUsers.remove(u.uid) else openUsers.add(u.uid); drawUsers(box) }
            }
            // 관리자만: 닉네임 + 실명
            r.addView(Ui.text(this, 16f, Ui.TEXT, true, (u.nick ?: "(닉네임 없음)") +
                (if (u.realName.isNotBlank()) "  ·  ${u.realName}" else "")), LinearLayout.LayoutParams(0, WRAP, 1f))
            r.addView(Ui.text(this, 13f, if (u.lastSeen > day) Ui.GREEN else Ui.SUB, true, ago(u.lastSeen)))
            r.addView(Ui.text(this, 14f, if (open) Ui.GREEN else Ui.SUB, true, if (open) "  ▾" else "  ▸"))
            c.addView(r)
            if (!open) { box.addView(c, Ui.lp(this, MATCH, WRAP, top = 6)); return@forEach }
            if (u.email.isNotBlank()) c.addView(Ui.text(this, 13f, Ui.SUB, false, u.email), Ui.lp(this, MATCH, WRAP, top = 2))
            val info = buildString {
                if (u.lastSeen > 0) append("마지막 접속 ").append(fmt.format(Date(u.lastSeen)))
                if (u.version.isNotBlank()) append(" · 앱 v").append(u.version)
                if (u.groups > 0) append(" · 그룹 ").append(u.groups).append("개")
                val n = reports[u.uid] ?: 0
                if (n > 0) append(" · 신고 ").append(n)
            }
            if (info.isNotBlank()) c.addView(Ui.text(this, 12f, Ui.SUB, false, info), Ui.lp(this, MATCH, WRAP, top = 4))
            // 접속 기기: "기기: Galaxy S24 Ultra (SM-S928N) · 안드로이드 15"
            val dev = buildString {
                if (u.model.isNotBlank()) append(u.model)
                if (u.modelCode.isNotBlank() && !u.model.contains(u.modelCode)) append(" (").append(u.modelCode).append(")")
                if (u.os.isNotBlank()) append(" · 안드로이드 ").append(u.os)
            }
            c.addView(Ui.text(this, 12f, if (dev.isBlank()) Ui.SUB else Ui.BLUE, false,
                if (dev.isBlank()) "기기: 아직 모름 (v1.6 이상으로 접속하면 표시)" else "기기: $dev"), Ui.lp(this, MATCH, WRAP, top = 2))
            // 권한 체크박스 (기본 모두 꺼짐, 운영자가 켜고 끔)
            if (u.uid != Cloud.uid) {
                val pr = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(6), dp(10), dp(6)); background = Ui.bg(this@AdminActivity, android.graphics.Color.parseColor("#0F2233"), 10) }
                pr.addView(Ui.text(this, 12f, Ui.SUB, true, "권한"))
                Cloud.PERMS.forEach { (key, label) ->
                    pr.addView(android.widget.CheckBox(this).apply {
                        text = label; setTextColor(Ui.TEXT); textSize = 14f
                        buttonTintList = android.content.res.ColorStateList.valueOf(Ui.GREEN)
                        isChecked = key in (perms[u.uid] ?: emptySet())
                        setOnCheckedChangeListener { _, on ->
                            Cloud.setPerm(u.uid, key, on) { e ->
                                if (e != null) android.widget.Toast.makeText(this@AdminActivity, e, android.widget.Toast.LENGTH_LONG).show()
                                else perms = perms + (u.uid to ((perms[u.uid] ?: emptySet()).let { if (on) it + key else it - key }))
                            }
                        }
                    })
                }
                c.addView(pr, Ui.lp(this, MATCH, WRAP, top = 8))
            }
            if (u.uid != Cloud.uid) {
                if (bans.any { it.uid == u.uid }) c.addView(Ui.text(this, 13f, Ui.ORANGE, true, "정지됨 · 위 '정지 관리'에서 해제"), Ui.lp(this, MATCH, WRAP, top = 6))
                else c.addView(Ui.button(this, "이용 정지") { askBan(u.uid) }.apply { minHeight = dp(40); textSize = 13f }, Ui.lp(this, MATCH, WRAP, top = 8))
            }
            box.addView(c, Ui.lp(this, MATCH, WRAP, top = 6))
        }
    }
}
