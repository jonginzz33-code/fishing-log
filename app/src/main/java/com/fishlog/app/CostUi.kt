package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP

/** 이용료(무료/유료·금액) + 꼭 필요한 물건 입력 칸 */
object CostUi {

    class Editor(val view: View, val read: () -> Store.Cost)

    fun editor(a: Activity, cat: String, init: Store.Cost?): Editor {
        fun dp(v: Int) = Ui.dp(a, v)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        var paid: Boolean? = init?.paid
        val gear = linkedSetOf<String>().apply { init?.gear?.let { addAll(it) } }

        // ---- 이용료 ----
        box.addView(Ui.text(a, 15f, Ui.TEXT, true, "이용료"), Ui.lp(a, MATCH, WRAP, top = 12))
        val row = Ui.row(a)
        val freeBtn = pill(a, "무료")
        val paidBtn = pill(a, "유료")
        val amount = EditText(a).apply {
            hint = "금액 (원)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Ui.TEXT); setHintTextColor(Ui.SUB); textSize = 16f
            if (init != null && init.fee > 0) setText(init.fee.toString())
        }
        fun paint() {
            setOn(a, freeBtn, paid == false); setOn(a, paidBtn, paid == true)
            amount.visibility = if (paid == true) View.VISIBLE else View.GONE
        }
        freeBtn.setOnClickListener { paid = if (paid == false) null else false; paint() }
        paidBtn.setOnClickListener { paid = if (paid == true) null else true; paint() }
        row.addView(freeBtn, LinearLayout.LayoutParams(0, dp(42), 1f))
        row.addView(paidBtn, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(8) })
        box.addView(row, Ui.lp(a, MATCH, WRAP, top = 6))
        box.addView(amount, Ui.lp(a, MATCH, WRAP, top = 4))
        paint()

        // ---- 꼭 필요한 물건 ----
        box.addView(Ui.text(a, 15f, Ui.TEXT, true, "꼭 필요한 물건"), Ui.lp(a, MATCH, WRAP, top = 12))
        val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        box.addView(grid, Ui.lp(a, MATCH, WRAP, top = 4))
        fun renderGear() {
            grid.removeAllViews()
            val items = (Store.gearOptions(cat) + gear).distinct()
            (items + "+ 직접 추가").chunked(3).forEach { rowItems ->
                val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                rowItems.forEachIndexed { i, g ->
                    val add = g == "+ 직접 추가"
                    val chip = pill(a, g, 13f)
                    if (add) chip.setTextColor(Ui.BLUE) else setOn(a, chip, g in gear)
                    chip.setOnClickListener {
                        if (add) askGear(a, cat) { name -> gear.add(name); renderGear() }
                        else { if (g in gear) gear.remove(g) else gear.add(g); setOn(a, chip, g in gear) }
                    }
                    r.addView(chip, LinearLayout.LayoutParams(0, dp(38), 1f).apply { if (i > 0) leftMargin = dp(6) })
                }
                repeat(3 - rowItems.size) { r.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(6) }) }
                grid.addView(r, Ui.lp(a, MATCH, WRAP, top = 6))
            }
        }
        renderGear()
        box.addView(Ui.text(a, 11f, Ui.SUB, false, "같은 배·항구로 다음에 출조하면 지금 고른 게 미리 채워져요. 금액과 준비물은 내 기록과 그룹에만 보여요."),
            Ui.lp(a, MATCH, WRAP, top = 6))

        return Editor(box) {
            val fee = if (paid == true) amount.text.toString().filter { it.isDigit() }.take(9).toIntOrNull() ?: 0 else 0
            Store.Cost(paid, fee, gear.toList())
        }
    }

    private fun pill(a: Activity, label: String, size: Float = 14f): TextView =
        Ui.text(a, size, Ui.TEXT, true, label).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            background = Ui.bg(a, Ui.CARD, 20, Ui.LINE)
            isClickable = true
        }

    private fun setOn(a: Activity, v: TextView, on: Boolean) {
        v.setTextColor(if (on) Ui.BG else Ui.TEXT)
        v.background = if (on) Ui.bg(a, Ui.TEXT, 20) else Ui.bg(a, Ui.CARD, 20, Ui.LINE)
    }

    private fun askGear(a: Activity, cat: String, then: (String) -> Unit) {
        val input = EditText(a).apply { hint = "예: 장화"; inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("필요한 물건 추가")
            .setMessage("다음부터 목록에 같이 나와요")
            .setView(input)
            .setPositiveButton("추가") { _, _ ->
                val n = input.text.toString().trim().take(20)
                if (n.isNotEmpty()) { Store.addGearOption(cat, n); then(n) }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 저장 + 이미 올린 그룹·백업에도 반영 (사진은 다시 안 올라감) */
    fun save(t: Store.Trip, c: Store.Cost) {
        Store.setCost(t.id, c)
        Store.find(t.id)?.let { tt ->
            Store.sharedGroups(tt.id).forEach { gid -> Cloud.groups.firstOrNull { it.id == gid }?.let { g -> Cloud.uploadTrip(tt, g) { } } }
            Cloud.backupPending()
        }
    }

    /** 출조 기록 화면에서 고치기: 저장 후 백업·그룹 공유에도 반영 */
    fun edit(a: Activity, t: Store.Trip, done: () -> Unit) {
        val ed = editor(a, t.cat.ifBlank { Store.category }, Store.Cost(t.paid, t.fee, t.gear.toList()))
        val wrap = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(a, 20), 0, Ui.dp(a, 20), 0); addView(ed.view) }
        AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("이용료 · 준비물")
            .setView(android.widget.ScrollView(a).apply { addView(wrap) })
            .setPositiveButton("저장") { _, _ -> save(t, ed.read()); done() }
            .setNegativeButton("취소", null)
            .show()
    }
}
