package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import org.json.JSONArray
import org.json.JSONObject

/**
 * 나만의 재고표 3단계: 품목(봉돌) → 종류(야광 봉돌) → 호수(10호) 수량
 *  - 누르면 아래로 펼침, 위 단계 숫자 = 아래 합계
 *  - 저장은 폰에만, '서버에 저장하기'를 눌렀을 때만 내 계정(users/{uid}.inv)에 올림
 *  - 추가 · 수정 · 삭제 모두
 */
object Inventory {

    class Item(var name: String, var qty: Int, var note: String, val subs: MutableList<Item> = mutableListOf(), var open: Boolean = false) {
        fun total(): Int = if (subs.isEmpty()) qty else subs.sumOf { it.total() }
    }

    private val LV_NAME = listOf("품목", "종류", "호수")
    private val LV_HINT = listOf("품목 (예: 봉돌, 에기, 채비)", "종류 (예: 야광 봉돌, 다이아몬드 봉돌)", "호수 (예: 10호)")

    private fun prefs(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)

    private fun toJson(list: List<Item>): JSONArray = JSONArray().apply {
        list.forEach { put(JSONObject().put("n", it.name).put("q", it.qty).put("m", it.note).put("s", toJson(it.subs))) }
    }
    private fun fromJson(a: JSONArray?): MutableList<Item> = (0 until (a?.length() ?: 0)).map { i ->
        val o = a!!.getJSONObject(i)
        Item(o.optString("n"), o.optInt("q"), o.optString("m"), fromJson(o.optJSONArray("s")))
    }.toMutableList()

    fun load(c: Context): MutableList<Item> = try { fromJson(JSONArray(prefs(c).getString("inventory", "[]"))) } catch (_: Exception) { mutableListOf() }

    /** 폰에만 저장 */
    private fun save(c: Context, list: List<Item>) {
        prefs(c).edit().putString("inventory", toJson(list).toString()).putBoolean("inv_dirty", true).apply()
    }

    private fun toast(c: Context, s: String) = Toast.makeText(c, s, Toast.LENGTH_LONG).show()

    /** '서버에 저장하기' */
    private fun upload(c: Context, list: List<Item>, done: () -> Unit) {
        val me = Cloud.uid ?: run { toast(c, "로그인하면 서버에 저장할 수 있어요"); return }
        val s = toJson(list).toString()
        FirebaseFirestore.getInstance().collection("users").document(me)
            .set(mapOf("inv" to s, "invAt" to System.currentTimeMillis()), SetOptions.merge())
            .addOnSuccessListener { prefs(c).edit().putBoolean("inv_dirty", false).apply(); toast(c, "서버에 저장했어요"); done() }
            .addOnFailureListener { toast(c, "서버 저장 실패: ${it.message}") }
    }

    /** '서버에서 불러오기' (폰을 바꿨을 때) */
    private fun download(c: Context, done: (Boolean) -> Unit) {
        val me = Cloud.uid ?: run { toast(c, "로그인하면 서버에서 불러올 수 있어요"); return }
        FirebaseFirestore.getInstance().collection("users").document(me).get().addOnSuccessListener { d ->
            val s = d.getString("inv")
            if (s == null) { toast(c, "서버에 저장된 재고표가 없어요"); done(false); return@addOnSuccessListener }
            prefs(c).edit().putString("inventory", s).putBoolean("inv_dirty", false).apply()
            toast(c, "서버에서 불러왔어요"); done(true)
        }.addOnFailureListener { toast(c, "불러오기 실패: ${it.message}"); done(false) }
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
        lateinit var render: () -> Unit

        fun et(h: String, v: String, num: Boolean = false) = EditText(a).apply {
            hint = h; setText(v); setTextColor(Ui.TEXT); setHintTextColor(Ui.SUB); setSingleLine()
            if (num) inputType = InputType.TYPE_CLASS_NUMBER
        }

        /** 이름·수량·비고 한 칸 수정/추가 (lv: 0 품목, 1 종류, 2 호수) */
        fun edit(title: String, lv: Int, cur: Item?, ok: (String, Int, String) -> Unit) {
            val f = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), 0) }
            val n = et(LV_HINT[lv], cur?.name ?: "")
            val q = et("수량", (cur?.qty ?: 0).toString(), true)
            val m = et("비고 (예: 다음에 더 사기)", cur?.note ?: "")
            f.addView(n)
            if (cur == null || cur.subs.isEmpty()) f.addView(q)
            else f.addView(Ui.text(a, 12f, Ui.SUB, false, "수량은 아래 항목 합계 (${cur.total()})"))
            f.addView(m)
            AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title).setView(f)
                .setPositiveButton("저장") { _, _ ->
                    val name = n.text.toString().trim()
                    if (name.isNotEmpty()) ok(name, q.text.toString().toIntOrNull() ?: (cur?.qty ?: 0), m.text.toString().trim())
                }
                .setNegativeButton("취소", null).show()
        }

        /** 처음 품목 등록: 품목 + 종류 여러 개 + 종류마다 호수·수량 여러 줄을 한 번에 */
        fun addFull() {
            val f = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(4), dp(18), dp(8)) }
            val name = et(LV_HINT[0], "")
            f.addView(name)
            val noKind = et("종류 없이 수량만 (예: 30)", "", true)
            f.addView(noKind)
            f.addView(Ui.text(a, 11.5f, Ui.SUB, false, "종류·호수가 있으면 아래에서 추가 (그러면 수량은 자동 합계)"), Ui.lp(a, MATCH, WRAP, top = 2))
            class SizeRow(val v: View, val n: EditText, val q: EditText)
            class KindRow(val box: LinearLayout, val n: EditText, val sizes: MutableList<SizeRow>)
            val kinds = mutableListOf<KindRow>()
            val kindsBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            f.addView(kindsBox)
            fun addSize(k: KindRow) {
                val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val sn = et("호수 (예: 10호)", ""); val sq = et("수량", "", true)
                r.addView(sn, LinearLayout.LayoutParams(0, WRAP, 1.4f)); r.addView(sq, LinearLayout.LayoutParams(0, WRAP, 1f))
                lateinit var row: SizeRow
                r.addView(Ui.text(a, 14f, Color.parseColor("#FF8A92"), true, "✕").apply {
                    gravity = Gravity.CENTER; setOnClickListener { k.box.removeView(r); k.sizes.remove(row) }
                }, LinearLayout.LayoutParams(dp(32), dp(36)))
                row = SizeRow(r, sn, sq); k.sizes.add(row)
                k.box.addView(r, k.box.childCount - 1)
            }
            fun addKind() {
                val kb = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(6), dp(10), dp(8))
                    background = Ui.bg(a, Color.parseColor("#0F2233"), 12, Ui.LINE)
                }
                val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val kn = et(LV_HINT[1], "")
                head.addView(kn, LinearLayout.LayoutParams(0, WRAP, 1f))
                val k = KindRow(kb, kn, mutableListOf())
                head.addView(Ui.text(a, 12f, Color.parseColor("#FF8A92"), true, "종류 빼기").apply {
                    setPadding(dp(8), dp(6), dp(4), dp(6)); setOnClickListener { kindsBox.removeView(kb); kinds.remove(k) }
                })
                kb.addView(head)
                kb.addView(Ui.text(a, 13f, Ui.BLUE, true, "＋ 호수 추가").apply {
                    setPadding(dp(4), dp(8), dp(4), dp(4)); setOnClickListener { addSize(k) }
                })
                kinds.add(k); kindsBox.addView(kb, Ui.lp(a, MATCH, WRAP, top = 8))
                addSize(k)
            }
            f.addView(Ui.text(a, 14f, Ui.GREEN, true, "＋ 종류 추가").apply {
                gravity = Gravity.CENTER; minHeight = dp(40)
                background = Ui.bg(a, Color.TRANSPARENT, 99, Ui.GREEN)
                setOnClickListener { addKind() }
            }, Ui.lp(a, MATCH, WRAP, top = 10))
            AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("품목 추가")
                .setView(ScrollView(a).apply { addView(f) })
                .setPositiveButton("저장") { _, _ ->
                    val nm = name.text.toString().trim()
                    if (nm.isEmpty()) { toast(a, "품목 이름을 적어 주세요"); return@setPositiveButton }
                    val item = Item(nm, noKind.text.toString().toIntOrNull() ?: 0, "")
                    kinds.forEach { k ->
                        val kn = k.n.text.toString().trim()
                        val sizes = k.sizes.mapNotNull { s ->
                            val sn = s.n.text.toString().trim()
                            if (sn.isEmpty()) null else Item(sn, s.q.text.toString().toIntOrNull() ?: 0, "")
                        }
                        if (kn.isNotEmpty()) item.subs.add(Item(kn, 0, "", sizes.toMutableList()))
                    }
                    item.open = item.subs.isNotEmpty()
                    list.add(item); save(a, list); render()
                }
                .setNegativeButton("취소", null).show()
        }

        fun row(item: Item, parent: MutableList<Item>, lv: Int): LinearLayout {
            val r = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10 + lv * 14), dp(7), dp(6), dp(7))
                background = Ui.bg(a, when (lv) { 0 -> Ui.CARD; 1 -> Color.parseColor("#0F2233"); else -> Color.parseColor("#0C1C2A") }, 12)
                isClickable = true
                setOnClickListener { if (lv < 2) { item.open = !item.open; render() } }
            }
            val arrow = if (lv < 2) (if (item.open) "▾ " else "▸ ") else "· "
            val tcol = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            tcol.addView(Ui.text(a, when (lv) { 0 -> 15f; 1 -> 14f; else -> 13.5f }, Ui.TEXT, lv < 2, arrow + item.name))
            if (item.note.isNotBlank()) tcol.addView(Ui.text(a, 11.5f, Ui.SUB, false, item.note))
            r.addView(tcol, LinearLayout.LayoutParams(0, WRAP, 1f))
            // ✎ 수정 · 🗑 삭제
            r.addView(Ui.text(a, 14f, Ui.SUB, true, "✎").apply {
                gravity = Gravity.CENTER
                setOnClickListener { edit("${LV_NAME[lv]} 수정", lv, item) { n, q, m -> item.name = n; item.qty = q; item.note = m; save(a, list); render() } }
            }, LinearLayout.LayoutParams(dp(28), dp(32)))
            r.addView(Ui.text(a, 13f, Color.parseColor("#FF8A92"), true, "🗑").apply {
                gravity = Gravity.CENTER
                setOnClickListener {
                    AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setMessage("'${item.name}'을(를) 지울까요?" + if (item.subs.isNotEmpty()) " (아래 항목도 같이)" else "")
                        .setPositiveButton("삭제") { _, _ -> parent.remove(item); save(a, list); render() }
                        .setNegativeButton("취소", null).show()
                }
            }, LinearLayout.LayoutParams(dp(28), dp(32)))
            val total = item.total()
            val leaf = item.subs.isEmpty()
            fun pm(label: String, d: Int) = Ui.text(a, 16f, Ui.TEXT, true, label).apply {
                gravity = Gravity.CENTER; background = Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE)
                setOnClickListener { _ -> item.qty = maxOf(0, item.qty + d); save(a, list); render() }
            }
            if (leaf) r.addView(pm("−", -1), LinearLayout.LayoutParams(dp(30), dp(30)))
            r.addView(Ui.text(a, 15f, if (total == 0) Color.parseColor("#FF8A92") else Ui.ORANGE, true, "$total").apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(dp(if (leaf) 40 else 100), WRAP))
            if (leaf) r.addView(pm("＋", 1), LinearLayout.LayoutParams(dp(30), dp(30)))
            return r
        }

        fun addBtn(label: String, lv: Int, onClick: () -> Unit) = Ui.text(a, 12.5f, Ui.BLUE, true, label).apply {
            setPadding(dp(14 + lv * 14), dp(6), dp(8), dp(6)); setOnClickListener { onClick() }
        }

        render = {
            box.removeAllViews()
            box.addView(Ui.text(a, 11.5f, Ui.SUB, false, "누르면 아래로 펼쳐져요 · 위 단계 숫자는 아래 합계 · ✎ 수정 · 🗑 삭제 · 수량 0은 빨강"))
            if (list.isEmpty()) box.addView(Ui.text(a, 14f, Ui.SUB, false, "아직 품목이 없어요 · 아래 '＋ 품목 추가'"), Ui.lp(a, MATCH, WRAP, top = 10))
            list.forEach { p ->
                box.addView(row(p, list, 0), Ui.lp(a, MATCH, WRAP, top = 8))
                if (!p.open) return@forEach
                p.subs.forEach { k ->
                    box.addView(row(k, p.subs, 1), Ui.lp(a, MATCH, WRAP, top = 3))
                    if (k.open) {
                        k.subs.forEach { s -> box.addView(row(s, k.subs, 2), Ui.lp(a, MATCH, WRAP, top = 2)) }
                        box.addView(addBtn("＋ 호수 추가", 2) {
                            edit("'${k.name}' 호수 추가", 2, null) { n, q, m -> k.subs.add(Item(n, q, m)); k.open = true; save(a, list); render() }
                        })
                    }
                }
                box.addView(addBtn("＋ 종류 추가", 1) {
                    edit("'${p.name}' 종류 추가", 1, null) { n, q, m -> p.subs.add(Item(n, q, m)); p.open = true; save(a, list); render() }
                })
            }
            box.addView(Ui.button(a, "＋ 품목 추가", true) { addFull() }.apply { minHeight = dp(44) }, Ui.lp(a, MATCH, WRAP, top = 12))
            // 저장: 폰에는 바로바로, 서버는 버튼으로
            val dirty = prefs(a).getBoolean("inv_dirty", false)
            val sr = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            sr.addView(Ui.button(a, "저장") { save(a, list); toast(a, "폰에 저장했어요"); dlg.dismiss() }.apply { minHeight = dp(44) },
                LinearLayout.LayoutParams(0, WRAP, 1f))
            sr.addView(Ui.button(a, if (dirty) "☁ 서버에 저장하기 •" else "☁ 서버에 저장하기") { upload(a, list) { render() } }.apply {
                minHeight = dp(44); setTextColor(Ui.GREEN); background = Ui.bg(a, Color.TRANSPARENT, 14, Ui.GREEN)
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
            box.addView(sr, Ui.lp(a, MATCH, WRAP, top = 10))
            box.addView(Ui.text(a, 11f, Ui.SUB, false, "폰에는 바로 저장돼요 · 폰을 바꿀 때를 대비해 '서버에 저장하기'를 눌러 두세요" +
                if (dirty) "\n• 서버에 아직 안 올린 변경이 있어요" else ""), Ui.lp(a, MATCH, WRAP, top = 4))
            box.addView(Ui.text(a, 12f, Ui.SUB, true, "서버에서 불러오기").apply {
                setPadding(0, dp(8), 0, dp(6))
                setOnClickListener {
                    AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setMessage("서버에 저장해 둔 재고표로 바꿀까요? (지금 폰의 재고표는 덮어써요)")
                        .setPositiveButton("불러오기") { _, _ -> download(a) { ok -> if (ok) { list = load(a); render() } } }
                        .setNegativeButton("취소", null).show()
                }
            })
        }
        dlg.show()
        render()
    }
}
