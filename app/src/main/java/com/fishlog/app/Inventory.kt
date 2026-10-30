package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import org.json.JSONArray
import org.json.JSONObject

/**
 * 나만의 재고표: [품목] [수량] [비고], 품목을 누르면 세부 항목이 펼쳐짐 (예: 봉돌 → 8호·10호·12호)
 *  - 폰에 저장 + 로그인하면 내 계정(users/{uid}.inv)에도 백업
 *  - 추가 · 수정 · 삭제, 수량은 −/＋ 로 바로
 */
object Inventory {

    class Item(var name: String, var qty: Int, var note: String, val subs: MutableList<Item> = mutableListOf(), var open: Boolean = false)

    private fun prefs(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)

    private fun toJson(list: List<Item>): JSONArray = JSONArray().apply {
        list.forEach { put(JSONObject().put("n", it.name).put("q", it.qty).put("m", it.note).put("s", toJson(it.subs))) }
    }
    private fun fromJson(a: JSONArray?): MutableList<Item> = (0 until (a?.length() ?: 0)).map { i ->
        val o = a!!.getJSONObject(i)
        Item(o.optString("n"), o.optInt("q"), o.optString("m"), fromJson(o.optJSONArray("s")))
    }.toMutableList()

    fun load(c: Context): MutableList<Item> = try { fromJson(JSONArray(prefs(c).getString("inventory", "[]"))) } catch (_: Exception) { mutableListOf() }

    private fun save(c: Context, list: List<Item>) {
        val s = toJson(list).toString()
        prefs(c).edit().putString("inventory", s).apply()
        Cloud.uid?.let { FirebaseFirestore.getInstance().collection("users").document(it).set(mapOf("inv" to s), SetOptions.merge()) }
    }

    /** 폰에 없고 서버에 있으면 받아오기 (폰 바꿨을 때) */
    private fun restore(c: Context, done: () -> Unit) {
        val me = Cloud.uid ?: return done()
        if (load(c).isNotEmpty()) return done()
        FirebaseFirestore.getInstance().collection("users").document(me).get().addOnSuccessListener { d ->
            d.getString("inv")?.let { prefs(c).edit().putString("inventory", it).apply() }
            done()
        }.addOnFailureListener { done() }
    }

    fun show(a: Activity) {
        fun dp(v: Int) = Ui.dp(a, v)
        var list = load(a)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(4), dp(14), dp(4)) }
        val dlg = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("📦 나만의 재고표")
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton("닫기", null)
            .create()

        fun edit(title: String, it: Item?, ok: (String, Int, String) -> Unit) {
            val f = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), 0) }
            fun et(h: String, v: String, num: Boolean = false) = EditText(a).apply {
                hint = h; setText(v); setTextColor(Ui.TEXT); setHintTextColor(Ui.SUB)
                if (num) inputType = InputType.TYPE_CLASS_NUMBER
            }
            val n = et("품목 (예: 봉돌, 에기, 채비)", it?.name ?: "")
            val q = et("수량", (it?.qty ?: 0).toString(), true)
            val m = et("비고 (예: 10호, 다음에 더 사기)", it?.note ?: "")
            f.addView(n); f.addView(q); f.addView(m)
            AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title).setView(f)
                .setPositiveButton("저장") { _, _ ->
                    val name = n.text.toString().trim()
                    if (name.isNotEmpty()) ok(name, q.text.toString().toIntOrNull() ?: 0, m.text.toString().trim())
                }
                .setNegativeButton("취소", null).show()
        }

        lateinit var render: () -> Unit
        fun row(it: Item, parent: MutableList<Item>, sub: Boolean): LinearLayout {
            val r = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(if (sub) 22 else 10), dp(8), dp(6), dp(8))
                background = Ui.bg(a, if (sub) Color.parseColor("#0F2233") else Ui.CARD, 12)
                isClickable = true
                setOnClickListener { if (!sub) { it.open = !it.open; render() } }
                setOnLongClickListener {
                    val opts = if (sub) arrayOf("수정", "삭제") else arrayOf("수정", "세부 항목 추가", "삭제")
                    AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(it.name)
                        .setItems(opts) { _, i ->
                            when (opts[i]) {
                                "수정" -> edit("수정", it) { n, q, m -> it.name = n; it.qty = q; it.note = m; save(a, list); render() }
                                "세부 항목 추가" -> edit("'${it.name}' 세부 항목", null) { n, q, m -> it.subs.add(Item(n, q, m)); it.open = true; save(a, list); render() }
                                "삭제" -> AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                    .setMessage("'${it.name}'을(를) 지울까요?" + if (it.subs.isNotEmpty()) " (세부 항목도 같이)" else "")
                                    .setPositiveButton("삭제") { _, _ -> parent.remove(it); save(a, list); render() }
                                    .setNegativeButton("취소", null).show()
                            }
                        }.show()
                    true
                }
            }
            val name = (if (!sub && it.subs.isNotEmpty()) (if (it.open) "▾ " else "▸ ") else if (sub) "· " else "") + it.name
            val tcol = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            tcol.addView(Ui.text(a, if (sub) 13.5f else 15f, Ui.TEXT, !sub, name))
            if (it.note.isNotBlank()) tcol.addView(Ui.text(a, 11.5f, Ui.SUB, false, it.note))
            r.addView(tcol, LinearLayout.LayoutParams(0, WRAP, 1f))
            val total = if (!sub && it.subs.isNotEmpty()) it.subs.sumOf { s -> s.qty } else it.qty
            fun pm(label: String, d: Int) = Ui.text(a, 16f, Ui.TEXT, true, label).apply {
                gravity = Gravity.CENTER; background = Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE)
                setOnClickListener { _ -> it.qty = maxOf(0, it.qty + d); save(a, list); render() }
            }
            if (sub || it.subs.isEmpty()) r.addView(pm("−", -1), LinearLayout.LayoutParams(dp(32), dp(32)))
            r.addView(Ui.text(a, 15f, if (total == 0) Color.parseColor("#FF8A92") else Ui.ORANGE, true, "$total").apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(dp(44), WRAP))
            if (sub || it.subs.isEmpty()) r.addView(pm("＋", 1), LinearLayout.LayoutParams(dp(32), dp(32)))
            return r
        }

        render = {
            box.removeAllViews()
            box.addView(Ui.text(a, 11.5f, Ui.SUB, false, "품목을 누르면 세부 항목이 펼쳐져요 · 꾹 누르면 수정·세부 추가·삭제 · 수량 0은 빨강"))
            val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(8), dp(6), 0) }
            head.addView(Ui.text(a, 11f, Ui.SUB, true, "품목 · 비고"), LinearLayout.LayoutParams(0, WRAP, 1f))
            head.addView(Ui.text(a, 11f, Ui.SUB, true, "수량"), LinearLayout.LayoutParams(dp(108), WRAP))
            box.addView(head)
            if (list.isEmpty()) box.addView(Ui.text(a, 14f, Ui.SUB, false, "아직 품목이 없어요 · 아래 '＋ 품목 추가'"), Ui.lp(a, MATCH, WRAP, top = 10))
            list.forEach { it ->
                box.addView(row(it, list, false), Ui.lp(a, MATCH, WRAP, top = 6))
                if (it.open) it.subs.forEach { s -> box.addView(row(s, it.subs, true), Ui.lp(a, MATCH, WRAP, top = 3)) }
            }
            box.addView(Ui.button(a, "＋ 품목 추가", true) {
                edit("품목 추가", null) { n, q, m -> list.add(Item(n, q, m)); save(a, list); render() }
            }.apply { minHeight = dp(44) }, Ui.lp(a, MATCH, WRAP, top = 12))
        }
        dlg.show()
        render()
        restore(a) { if (dlg.isShowing) { list = load(a); render() } }
    }
}
