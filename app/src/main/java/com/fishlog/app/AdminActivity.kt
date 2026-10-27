package com.fishlog.app

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 관리자 전용: 앱 사용자 목록, 마지막 접속, 앱 버전 */
class AdminActivity : Activity() {

    private lateinit var listBox: LinearLayout
    private val fmt = SimpleDateFormat("yyyy.M.d HH:mm", Locale.KOREA)
    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Cloud.isAdmin) { finish(); return }
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        val col = Ui.column(this)
        val top = Ui.row(this)
        top.addView(Ui.button(this, "← 뒤로") { finish() }.apply { minHeight = dp(44) }, LinearLayout.LayoutParams(WRAP, WRAP))
        top.addView(Ui.text(this, 20f, Ui.TEXT, true, "관리자 · 사용자"), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(14) })
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

    private fun load() {
        listBox.removeAllViews()
        listBox.addView(Ui.text(this, 14f, Ui.SUB, false, "불러오는 중…"), Ui.lp(this, MATCH, WRAP, top = 10))
        Cloud.adminUsers { list, err ->
            listBox.removeAllViews()
            if (list == null) {
                listBox.addView(Ui.text(this, 14f, Ui.ORANGE, false, err ?: "실패"), Ui.lp(this, MATCH, WRAP, top = 10))
                return@adminUsers
            }
            val day = System.currentTimeMillis() - 24 * 3600_000L
            val week = System.currentTimeMillis() - 7 * 24 * 3600_000L
            val sum = Ui.row(this)
            sum.addView(Ui.stat(this, "전체", "${list.size}명"), LinearLayout.LayoutParams(0, WRAP, 1f))
            sum.addView(Ui.stat(this, "오늘", "${list.count { it.lastSeen > day }}명"), LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
            sum.addView(Ui.stat(this, "7일", "${list.count { it.lastSeen > week }}명"), LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
            listBox.addView(sum, Ui.lp(this, MATCH, WRAP, top = 12))
            list.forEach { u ->
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
                }
                if (info.isNotBlank()) c.addView(Ui.text(this, 12f, Ui.SUB, false, info), Ui.lp(this, MATCH, WRAP, top = 4))
                listBox.addView(c, Ui.lp(this, MATCH, WRAP, top = 8))
            }
        }
    }
}
