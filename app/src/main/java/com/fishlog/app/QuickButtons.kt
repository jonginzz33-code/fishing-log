package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP

/**
 * 퀵 버튼 (3번 이상): 1·2번 큰 카드 아래에 개수 제한 없이
 *  - 버튼마다 크기(작게·보통·크게)·색·순서
 *  - 리모컨·워치는 1·2번만, 3번 이상은 터치로만
 *  - 누르면 +1, 꾹 누르면 그 어종 마지막 기록 −1
 */
class QuickButtons(
    private val a: Activity,
    private val box: LinearLayout,
    private val message: (String) -> Unit,
    private val pickSpecies: (title: String, done: (String) -> Unit) -> Unit
) {
    private fun dp(v: Int) = Ui.dp(a, v)
    private var sig = ""
    private val counts = HashMap<Int, TextView>()
    private val names = HashMap<Int, TextView>()
    private val arts = HashMap<Int, SpeciesArtView>()

    private fun units(size: Int) = if (size >= 2) 3 else 2
    private fun height(size: Int) = when (size) { 2 -> 150; 1 -> 104; else -> 54 }

    /** 설정이 바뀌었을 때만 다시 만들고, 아니면 숫자만 바꿈 */
    fun render() {
        val list = Store.quick()
        val t = Store.current
        val s = list.joinToString("|") { "${it.slot},${it.name},${it.size},${it.color}" } + "#" + Store.category
        if (s != sig) { sig = s; build(list) }
        list.forEach { q ->
            val nm = if (t != null && t.names.size >= q.slot) t.name(q.slot) else q.name
            counts[q.slot]?.text = (t?.count(q.slot) ?: 0).toString()
            names[q.slot]?.text = nm
            arts[q.slot]?.species = nm
        }
    }

    private fun build(list: List<Store.Quick>) {
        box.removeAllViews(); counts.clear(); names.clear(); arts.clear()
        // 줄 채우기: 한 줄 6칸 (크게 3칸 · 보통/작게 2칸)
        var row: LinearLayout? = null
        var used = 0
        fun newRow() {
            row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            box.addView(row, Ui.lp(a, MATCH, WRAP, top = 8)); used = 0
        }
        fun fill() { row?.let { r -> if (used in 1..5) r.addView(View(a), LinearLayout.LayoutParams(0, 1, (6 - used).toFloat())) } }
        list.forEach { q ->
            val u = units(q.size)
            if (row == null || used + u > 6) { fill(); newRow() }
            row!!.addView(card(q), LinearLayout.LayoutParams(0, dp(height(q.size)), u.toFloat()).apply {
                if (used > 0) leftMargin = dp(8)
            })
            used += u
        }
        // ＋ 어종 추가 (점선 칸)
        if (row == null || used + 2 > 6) { fill(); newRow() }
        row!!.addView(TextView(a).apply {
            text = "＋ 어종 추가"; gravity = Gravity.CENTER; textSize = 13f; setTextColor(Ui.SUB); typeface = Typeface.DEFAULT_BOLD
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setStroke(dp(1), Color.parseColor("#3A5A70"), dp(5).toFloat(), dp(4).toFloat())
            }
            setOnClickListener { addNew() }
        }, LinearLayout.LayoutParams(0, dp(54), 2f).apply { if (used > 0) leftMargin = dp(8) })
        used += 2
        fill()
        if (list.isNotEmpty()) box.addView(Ui.text(a, 12f, Ui.SUB, true, "⚙ 퀵 버튼 관리 (크기·색·순서·삭제)").apply {
            setPadding(0, dp(8), 0, dp(2)); setOnClickListener { manage() }
        })
    }

    private fun card(q: Store.Quick): View {
        val col = Store.slotColor(q.slot)
        val ink = Color.parseColor("#14141A")
        val f = FrameLayout(a).apply {
            background = Ui.bg(a, col, if (q.size == 0) 12 else 16)
            clipToOutline = true; isClickable = true
            setOnClickListener {
                if (!Store.touchMode) { message("터치 기록이 꺼져 있어요 (기록 방식에서 '터치' 켜기)"); return@setOnClickListener }
                if (Store.current == null) { message("먼저 '출조 시작'을 눌러주세요"); return@setOnClickListener }
                Store.addCatch(q.slot)
                Haptics.slot(a, 1)
            }
            setOnLongClickListener {
                if (Store.current == null) return@setOnLongClickListener true
                if (Store.removeLastOf(q.slot)) message("${Store.current?.name(q.slot) ?: q.name} 마지막 기록을 지웠어요 (−1)")
                true
            }
        }
        val name = Ui.text(a, if (q.size == 0) 13f else 14f, Color.WHITE, true, q.name).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(3f, 0f, 1f, Color.argb(110, 0, 0, 0))
        }
        val count = TextView(a).apply {
            setTextColor(ink); typeface = Typeface.create("sans-serif-black", Typeface.BOLD); text = "0"
            includeFontPadding = false
            setShadowLayer(4f, 0f, 0f, Color.argb(150, 255, 255, 255))
        }
        names[q.slot] = name; counts[q.slot] = count
        if (q.size == 0) {
            // 작게: 한 줄 (이름 · 숫자)
            val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, dp(10), 0) }
            r.addView(name, LinearLayout.LayoutParams(0, WRAP, 1f))
            count.textSize = 20f
            r.addView(count)
            f.addView(r, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            val art = SpeciesArtView(a).apply { this.ink = ink; artAlpha = 0.3f; species = q.name }
            arts[q.slot] = art
            val sz = if (q.size >= 2) 84 else 58
            f.addView(art, FrameLayout.LayoutParams(dp(sz), dp(sz), Gravity.BOTTOM or Gravity.END).apply { rightMargin = -dp(4); bottomMargin = -dp(4) })
            f.addView(name, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply { topMargin = dp(8); leftMargin = dp(10); rightMargin = dp(8) })
            count.textSize = if (q.size >= 2) 52f else 34f
            f.addView(count, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(12); bottomMargin = dp(8) })
        }
        return f
    }

    private fun addNew() {
        pickSpecies("퀵 버튼 어종 고르기 (${Store.catLabel(Store.category)})") { nm ->
            val l = Store.quick()
            l.add(Store.Quick(Store.newQuickSlot(), nm, 1, l.size % Store.QUICK_COLORS.size))
            Store.saveQuick(l)
            render()
            message("'$nm' 퀵 버튼을 만들었어요 · 크기·색은 '퀵 버튼 관리'에서")
        }
    }

    /** 퀵 버튼 관리: 순서(▲▼) · 크기 · 색 · 어종 바꾸기 · 삭제 · 추가 */
    fun manage() {
        val wrap = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(8)) }
        val dlg = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("퀵 버튼 (${Store.catLabel(Store.category)})")
            .setView(ScrollView(a).apply { addView(wrap) })
            .setPositiveButton("닫기", null).create()
        lateinit var draw: () -> Unit
        draw = {
            wrap.removeAllViews()
            wrap.addView(Ui.text(a, 12f, Ui.SUB, false,
                "리모컨·워치는 위의 큰 카드 1·2번만 · 퀵 버튼은 터치로만 기록 · 낚시 종류마다 따로 저장돼요"))
            val l = Store.quick()
            if (l.isEmpty()) wrap.addView(Ui.text(a, 14f, Ui.SUB, false, "아직 퀵 버튼이 없어요"), Ui.lp(a, MATCH, WRAP, top = 10))
            l.forEachIndexed { i, q ->
                val card = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = Ui.bg(a, Ui.CARD, 12)
                }
                val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                fun mv(lbl: String, d: Int) = Ui.text(a, 14f, Ui.TEXT, true, lbl).apply {
                    gravity = Gravity.CENTER; background = Ui.bg(a, Color.TRANSPARENT, 8, Ui.LINE)
                    setOnClickListener {
                        val j = i + d
                        if (j in l.indices) { java.util.Collections.swap(l, i, j); Store.saveQuick(l); draw(); render() }
                    }
                }
                r.addView(mv("▲", -1), LinearLayout.LayoutParams(dp(30), dp(30)))
                r.addView(mv("▼", 1), LinearLayout.LayoutParams(dp(30), dp(30)).apply { leftMargin = dp(4) })
                r.addView(View(a).apply { background = Ui.bg(a, Store.slotColor(q.slot), 4) }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { leftMargin = dp(10) })
                r.addView(Ui.text(a, 15f, Ui.TEXT, true, q.name), LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
                r.addView(Ui.text(a, 15f, Ui.SUB, true, "✎").apply {
                    gravity = Gravity.CENTER
                    setOnClickListener { pickSpecies("퀵 버튼 어종 바꾸기") { nm -> Store.rename(q.slot, nm); draw(); render() } }
                }, LinearLayout.LayoutParams(dp(34), dp(34)))
                r.addView(Ui.text(a, 14f, Color.parseColor("#FF8A92"), true, "🗑").apply {
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setMessage("'${q.name}' 퀵 버튼을 지울까요? (지난 기록은 그대로 남아요)")
                            .setPositiveButton("삭제") { _, _ -> l.remove(q); Store.saveQuick(l); draw(); render() }
                            .setNegativeButton("취소", null).show()
                    }
                }, LinearLayout.LayoutParams(dp(34), dp(34)))
                card.addView(r)
                // 크기
                val sz = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                sz.addView(Ui.text(a, 12f, Ui.SUB, false, "크기"), LinearLayout.LayoutParams(dp(36), WRAP))
                listOf(0 to "작게", 1 to "보통", 2 to "크게").forEach { (v, lb) ->
                    val on = q.size == v
                    sz.addView(Ui.text(a, 12f, if (on) Ui.BG else Ui.TEXT, true, lb).apply {
                        gravity = Gravity.CENTER
                        background = if (on) Ui.bg(a, Ui.TEXT, 99) else Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE)
                        setOnClickListener { q.size = v; Store.saveQuick(l); draw(); render() }
                    }, LinearLayout.LayoutParams(0, dp(30), 1f).apply { leftMargin = dp(4) })
                }
                card.addView(sz, Ui.lp(a, MATCH, WRAP, top = 6))
                // 색
                val cr = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                cr.addView(Ui.text(a, 12f, Ui.SUB, false, "색"), LinearLayout.LayoutParams(dp(36), WRAP))
                Store.QUICK_COLORS.forEachIndexed { ci, hex ->
                    cr.addView(View(a).apply {
                        background = Ui.bg(a, Color.parseColor(hex), 99, if (q.color == ci) Color.WHITE else Color.TRANSPARENT)
                        setOnClickListener { q.color = ci; Store.saveQuick(l); draw(); render() }
                    }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { leftMargin = dp(5) })
                }
                card.addView(cr, Ui.lp(a, MATCH, WRAP, top = 6))
                wrap.addView(card, Ui.lp(a, MATCH, WRAP, top = 8))
            }
            wrap.addView(Ui.button(a, "＋ 버튼 추가", true) { addNew(); dlg.dismiss() }.apply { minHeight = dp(44) }, Ui.lp(a, MATCH, WRAP, top = 12))
        }
        draw()
        dlg.show()
    }
}
