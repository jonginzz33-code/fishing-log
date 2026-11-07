package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
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
 * 🎒 내 가방 (나만의 재고표 · v4.1 · 188)
 *  - ① 카테고리 (여러 개 · 이름 · 그림 · 순서 · 삭제)  ② 카테고리 안 상세 항목 (수량 ± · 비고 · 세부(호수) · 부족 기준)
 *  - 저장은 폰에 바로, '서버에 저장하기'를 눌렀을 때만 내 계정(users/{uid}.inv2)에 올림
 *  - 예전 재고표(품목 → 종류 → 호수)는 '기본' 카테고리로 자동으로 옮김
 */
object Inventory {

    class Item(var name: String, var qty: Int, var note: String, val subs: MutableList<Item> = mutableListOf(), var low: Int = 0) {
        fun total(): Int = if (subs.isEmpty()) qty else subs.sumOf { it.total() }
        fun isLow() = low > 0 && total() < low
    }
    class Cat(var name: String, var icon: String, val items: MutableList<Item> = mutableListOf()) {
        fun total() = items.sumOf { it.total() }
        fun lowCount() = items.count { it.isLow() }
    }

    private val ICONS = listOf("🎒", "🎣", "🥅", "🦺", "🍙", "🧰", "🪝", "🦑", "🦀", "🔦", "🧤", "🧊")
    private fun prefs(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)

    private fun itemJson(it: Item): JSONObject = JSONObject().put("n", it.name).put("q", it.qty).put("m", it.note).put("l", it.low)
        .put("s", JSONArray().apply { it.subs.forEach { s -> put(itemJson(s)) } })
    private fun itemFrom(o: JSONObject): Item = Item(o.optString("n"), o.optInt("q"), o.optString("m"),
        (0 until (o.optJSONArray("s")?.length() ?: 0)).map { itemFrom(o.getJSONArray("s").getJSONObject(it)) }.toMutableList(), o.optInt("l"))
    private fun toJson(cats: List<Cat>): JSONArray = JSONArray().apply {
        cats.forEach { c -> put(JSONObject().put("c", c.name).put("i", c.icon).put("items", JSONArray().apply { c.items.forEach { put(itemJson(it)) } })) }
    }
    private fun fromJson(a: JSONArray): MutableList<Cat> = (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        val its = o.optJSONArray("items")
        Cat(o.optString("c"), o.optString("i").ifBlank { "🎒" }, (0 until (its?.length() ?: 0)).map { itemFrom(its!!.getJSONObject(it)) }.toMutableList())
    }.toMutableList()

    /** 예전 3단계(품목 → 종류 → 호수) → '기본' 카테고리 (호수는 '종류 호수' 세부로) */
    private fun migrateOld(s: String?): MutableList<Cat> {
        val a = try { JSONArray(s ?: "[]") } catch (_: Exception) { JSONArray() }
        if (a.length() == 0) return mutableListOf()
        val items = (0 until a.length()).map { i ->
            val p = itemFrom(a.getJSONObject(i))
            val flat = p.subs.flatMap { k -> if (k.subs.isEmpty()) listOf(Item(k.name, k.qty, k.note)) else k.subs.map { z -> Item("${k.name} ${z.name}", z.qty, z.note) } }
            Item(p.name, p.qty, p.note, flat.toMutableList())
        }
        return mutableListOf(Cat("기본", "🎒", items.toMutableList()))
    }

    fun load(c: Context): MutableList<Cat> {
        val s = prefs(c).getString("inventory2", null)
        if (s != null) return try { fromJson(JSONArray(s)) } catch (_: Exception) { mutableListOf() }
        val m = migrateOld(prefs(c).getString("inventory", null))
        if (m.isNotEmpty()) save(c, m, dirty = false)
        return m
    }

    private fun save(c: Context, cats: List<Cat>, dirty: Boolean = true) {
        prefs(c).edit().putString("inventory2", toJson(cats).toString()).apply { if (dirty) putBoolean("inv_dirty", true) }.apply()
    }

    private fun toast(c: Context, s: String) = Toast.makeText(c, s, Toast.LENGTH_LONG).show()

    private fun upload(c: Context, cats: List<Cat>, done: () -> Unit) {
        val me = Cloud.uid ?: run { toast(c, "로그인하면 서버에 저장할 수 있어요"); return }
        FirebaseFirestore.getInstance().collection("users").document(me)
            .set(mapOf("inv2" to toJson(cats).toString(), "invAt" to System.currentTimeMillis()), SetOptions.merge())
            .addOnSuccessListener { prefs(c).edit().putBoolean("inv_dirty", false).apply(); toast(c, "서버에 저장했어요"); done() }
            .addOnFailureListener { toast(c, "서버 저장 실패: ${it.message}") }
    }

    private fun download(c: Context, done: (Boolean) -> Unit) {
        val me = Cloud.uid ?: run { toast(c, "로그인하면 서버에서 불러올 수 있어요"); return }
        FirebaseFirestore.getInstance().collection("users").document(me).get().addOnSuccessListener { d ->
            val s2 = d.getString("inv2"); val s1 = d.getString("inv")
            val cats = when {
                s2 != null -> try { fromJson(JSONArray(s2)) } catch (_: Exception) { null }
                s1 != null -> migrateOld(s1)
                else -> null
            }
            if (cats == null) { toast(c, "서버에 저장된 재고표가 없어요"); done(false); return@addOnSuccessListener }
            save(c, cats, dirty = false); toast(c, "서버에서 불러왔어요"); done(true)
        }.addOnFailureListener { toast(c, "불러오기 실패: ${it.message}"); done(false) }
    }

    fun show(a: Activity) {
        fun dp(v: Int) = Ui.dp(a, v)
        var cats = load(a)
        var cur: Cat? = null
        val dlg = Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG); setPadding(dp(14), dp(12), dp(14), dp(10)) }
        val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        root.addView(top)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(a).apply { addView(box) }, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })
        lateinit var render: () -> Unit

        fun et(h: String, v: String, num: Boolean = false) = EditText(a).apply {
            hint = h; setText(v); setTextColor(Ui.TEXT); setHintTextColor(Ui.SUB); setSingleLine()
            if (num) inputType = InputType.TYPE_CLASS_NUMBER
        }
        fun confirm(msg: String, ok: () -> Unit) = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage(msg).setPositiveButton("삭제") { _, _ -> ok() }.setNegativeButton("취소", null).show()

        /** 카테고리 추가 · 수정 (이름 + 그림) */
        fun editCat(c: Cat?) {
            val f = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), 0) }
            val n = et("카테고리 이름 (예: 선상 채비, 해루질 장비)", c?.name ?: "")
            f.addView(n)
            var icon = c?.icon ?: "🎒"
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            fun paintIcons() {
                row.removeAllViews()
                ICONS.forEach { ic ->
                    row.addView(Ui.text(a, 20f, Ui.TEXT, false, ic).apply {
                        gravity = Gravity.CENTER
                        background = if (ic == icon) Ui.bg(a, Color.parseColor("#3A2814"), 10, Color.parseColor("#FFB27A")) else null
                        setOnClickListener { icon = ic; paintIcons() }
                    }, LinearLayout.LayoutParams(dp(40), dp(40)))
                }
            }
            paintIcons()
            f.addView(Ui.hscroll(a, row, Color.parseColor("#2B2B2B")), Ui.lp(a, MATCH, WRAP, top = 8))
            AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(if (c == null) "＋ 카테고리 추가" else "카테고리 수정")
                .setView(f)
                .setPositiveButton("저장") { _, _ ->
                    val nm = n.text.toString().trim()
                    if (nm.isEmpty()) { toast(a, "이름을 적어 주세요"); return@setPositiveButton }
                    if (c == null) cats.add(Cat(nm, icon)) else { c.name = nm; c.icon = icon }
                    save(a, cats); render()
                }.setNegativeButton("취소", null).show()
        }

        /** 상세 항목 추가 · 수정 (이름 · 수량 · 비고 · 부족 기준 · 세부) */
        fun editItem(cat: Cat, it0: Item?) {
            val f = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), dp(6)) }
            val n = et("이름 (예: 봉돌, 에기, 장갑)", it0?.name ?: "")
            val q = et("수량", (it0?.qty ?: 0).toString(), true)
            val m = et("비고 (예: 주황 · 핑크 보충)", it0?.note ?: "")
            val l = et("이 수량보다 적으면 '부족' (0 = 안 씀)", (it0?.low ?: 0).toString(), true)
            f.addView(n); f.addView(q); f.addView(m); f.addView(l)
            f.addView(Ui.text(a, 12.5f, Ui.TEXT, true, "세부 (예: 10호 · 15호) — 있으면 수량은 세부 합계"), Ui.lp(a, MATCH, WRAP, top = 10))
            class SubRow(val n: EditText, val q: EditText)
            val subs = mutableListOf<SubRow>()
            val subBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            f.addView(subBox)
            fun addSub(name: String, qty: Int) {
                val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val sn = et("세부 (예: 10호)", name); val sq = et("수량", qty.toString(), true)
                r.addView(sn, LinearLayout.LayoutParams(0, WRAP, 1.4f)); r.addView(sq, LinearLayout.LayoutParams(0, WRAP, 1f))
                val row = SubRow(sn, sq)
                r.addView(Ui.text(a, 14f, Color.parseColor("#FF8A92"), true, "✕").apply {
                    gravity = Gravity.CENTER; setOnClickListener { subBox.removeView(r); subs.remove(row) }
                }, LinearLayout.LayoutParams(dp(32), dp(36)))
                subs.add(row); subBox.addView(r)
            }
            it0?.subs?.forEach { addSub(it.name, it.qty) }
            f.addView(Ui.text(a, 13f, Ui.BLUE, true, "＋ 세부 추가").apply { setPadding(dp(4), dp(8), dp(4), dp(4)); setOnClickListener { addSub("", 0) } })
            AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(if (it0 == null) "＋ 항목 추가" else "항목 수정")
                .setView(ScrollView(a).apply { addView(f) })
                .setPositiveButton("저장") { _, _ ->
                    val nm = n.text.toString().trim()
                    if (nm.isEmpty()) { toast(a, "이름을 적어 주세요"); return@setPositiveButton }
                    val ss = subs.mapNotNull { s -> s.n.text.toString().trim().takeIf { it.isNotEmpty() }?.let { Item(it, s.q.text.toString().toIntOrNull() ?: 0, "") } }
                    val item = it0 ?: Item(nm, 0, "").also { cat.items.add(it) }
                    item.name = nm; item.qty = q.text.toString().toIntOrNull() ?: 0; item.note = m.text.toString().trim()
                    item.low = l.text.toString().toIntOrNull() ?: 0
                    item.subs.clear(); item.subs.addAll(ss)
                    save(a, cats); render()
                }.setNegativeButton("취소", null).show()
        }

        fun pm(label: String, onClick: () -> Unit) = Ui.text(a, 17f, Ui.TEXT, true, label).apply {
            gravity = Gravity.CENTER; background = Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE); setOnClickListener { onClick() }
        }
        fun qtyRow(qty: Int, low: Boolean, size: Float, onDelta: (Int) -> Unit) = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(pm("−") { onDelta(-1) }, LinearLayout.LayoutParams(dp(32), dp(32)))
            addView(Ui.text(a, size, if (low || qty == 0) Color.parseColor("#FF8A92") else Ui.TEXT, true, "$qty").apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(dp(44), WRAP))
            addView(pm("＋") { onDelta(1) }, LinearLayout.LayoutParams(dp(32), dp(32)))
        }

        fun renderCats() {
            top.removeAllViews()
            top.addView(Ui.text(a, 18f, Ui.TEXT, true, "🎒 내 가방"), LinearLayout.LayoutParams(0, WRAP, 1f))
            top.addView(Ui.text(a, 13f, Ui.TEXT, true, "닫기").apply {
                gravity = Gravity.CENTER; setPadding(dp(14), dp(6), dp(14), dp(6)); background = Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE)
                setOnClickListener { dlg.dismiss() }
            })
            if (cats.isEmpty()) box.addView(Ui.text(a, 14f, Ui.SUB, false, "아직 카테고리가 없어요 · 아래 '＋ 카테고리 추가'로 만들어 보세요\n(예: 🎣 선상 채비 · 🥅 해루질 장비 · 🦺 안전 · 옷)"), Ui.lp(a, MATCH, WRAP, top = 10))
            cats.forEachIndexed { i, c ->
                val r = LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = Ui.bg(a, Ui.CARD, 14)
                    setOnClickListener { cur = c; render() }
                    setOnLongClickListener {
                        val opts = arrayOf("✎ 이름 · 그림 바꾸기", "▲ 위로", "▼ 아래로", "🗑 삭제")
                        AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("${c.icon} ${c.name}")
                            .setItems(opts) { _, w ->
                                when (w) {
                                    0 -> editCat(c)
                                    1 -> if (i > 0) { cats.removeAt(i); cats.add(i - 1, c); save(a, cats); render() }
                                    2 -> if (i < cats.size - 1) { cats.removeAt(i); cats.add(i + 1, c); save(a, cats); render() }
                                    3 -> confirm("'${c.name}' 카테고리를 지울까요? (안의 항목 ${c.items.size}개도 같이)") { cats.remove(c); save(a, cats); render() }
                                }
                            }.show(); true
                    }
                }
                r.addView(Ui.text(a, 22f, Ui.TEXT, false, c.icon).apply { gravity = Gravity.CENTER; background = Ui.bg(a, Color.parseColor("#0F2233"), 12) },
                    LinearLayout.LayoutParams(dp(42), dp(42)))
                val tc = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
                tc.addView(Ui.text(a, 15.5f, Ui.TEXT, true, c.name))
                val lowN = c.lowCount()
                tc.addView(Ui.text(a, 12f, Ui.SUB, false, "항목 ${c.items.size}개 · 합계 ${c.total()}개" + if (lowN > 0) "  ⚠ 부족 $lowN" else "").apply {
                    if (lowN > 0) setTextColor(Color.parseColor("#FF8A92"))
                })
                r.addView(tc, LinearLayout.LayoutParams(0, WRAP, 1f))
                r.addView(Ui.text(a, 20f, Color.parseColor("#FFB27A"), true, "›"))
                box.addView(r, Ui.lp(a, MATCH, WRAP, top = 8))
            }
            box.addView(Ui.text(a, 14f, Ui.TEXT, true, "＋ 카테고리 추가").apply {
                gravity = Gravity.CENTER; minHeight = dp(46); background = Ui.bg(a, Color.TRANSPARENT, 14, Ui.LINE)
                setOnClickListener { editCat(null) }
            }, Ui.lp(a, MATCH, WRAP, top = 10))
            box.addView(Ui.text(a, 11.5f, Ui.SUB, false, "카테고리를 꾹 누르면 이름 · 그림 바꾸기 / 순서 / 삭제").apply { gravity = Gravity.CENTER }, Ui.lp(a, MATCH, WRAP, top = 6))
            // 서버 저장 · 불러오기
            val dirty = prefs(a).getBoolean("inv_dirty", false)
            val sr = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            sr.addView(Ui.button(a, if (dirty) "☁ 서버에 저장하기 •" else "☁ 서버에 저장하기") { upload(a, cats) { render() } }.apply {
                minHeight = dp(44); setTextColor(Ui.GREEN); background = Ui.bg(a, Color.TRANSPARENT, 14, Ui.GREEN)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            sr.addView(Ui.button(a, "서버에서 불러오기") {
                AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage("서버에 저장해 둔 가방으로 바꿀까요? (지금 폰의 가방은 덮어써요)")
                    .setPositiveButton("불러오기") { _, _ -> download(a) { ok -> if (ok) { cats = load(a); cur = null; render() } } }
                    .setNegativeButton("취소", null).show()
            }.apply { minHeight = dp(44) }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
            box.addView(sr, Ui.lp(a, MATCH, WRAP, top = 16))
            box.addView(Ui.text(a, 11f, Ui.SUB, false, "폰에는 바로 저장돼요 · 폰을 바꿀 때를 대비해 가끔 '서버에 저장하기'를 눌러 두세요" +
                if (dirty) "\n• 서버에 아직 안 올린 변경이 있어요" else ""), Ui.lp(a, MATCH, WRAP, top = 4))
        }

        fun renderItems(c: Cat) {
            top.removeAllViews()
            top.addView(Ui.text(a, 15f, Color.parseColor("#FFB27A"), true, "‹ 가방").apply {
                setPadding(0, dp(6), dp(10), dp(6)); setOnClickListener { cur = null; render() }
            })
            top.addView(Ui.text(a, 17f, Ui.TEXT, true, "${c.icon} ${c.name}").apply { gravity = Gravity.CENTER; maxLines = 1 }, LinearLayout.LayoutParams(0, WRAP, 1f))
            top.addView(Ui.text(a, 13f, Ui.TEXT, true, "닫기").apply {
                gravity = Gravity.CENTER; setPadding(dp(14), dp(6), dp(14), dp(6)); background = Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE)
                setOnClickListener { dlg.dismiss() }
            })
            if (c.items.isEmpty()) box.addView(Ui.text(a, 14f, Ui.SUB, false, "아직 항목이 없어요 · 아래 '＋ 항목 추가'"), Ui.lp(a, MATCH, WRAP, top = 10))
            c.items.forEach { it ->
                val card = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(10), dp(10))
                    background = Ui.bg(a, Ui.CARD, 14, if (it.isLow()) Color.parseColor("#E8505B") else Color.TRANSPARENT)
                    setOnClickListener { _ -> editItem(c, it) }
                    setOnLongClickListener { _ -> confirm("'${it.name}'을(를) 지울까요?") { c.items.remove(it); save(a, cats); render() }; true }
                }
                val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                r.addView(Ui.text(a, 15.5f, Ui.TEXT, true, it.name), LinearLayout.LayoutParams(0, WRAP, 1f))
                if (it.subs.isEmpty()) r.addView(qtyRow(it.qty, it.isLow(), 18f) { d -> it.qty = maxOf(0, it.qty + d); save(a, cats); render() })
                else r.addView(Ui.text(a, 18f, if (it.isLow()) Color.parseColor("#FF8A92") else Ui.ORANGE, true, "${it.total()}").apply { setPadding(dp(8), 0, dp(14), 0) })
                card.addView(r)
                it.subs.forEach { s ->
                    val sr = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(4), 0, 0) }
                    sr.addView(Ui.text(a, 13.5f, Ui.SUB, false, "· ${s.name}"), LinearLayout.LayoutParams(0, WRAP, 1f))
                    sr.addView(qtyRow(s.qty, false, 15f) { d -> s.qty = maxOf(0, s.qty + d); save(a, cats); render() })
                    card.addView(sr)
                }
                val notes = listOfNotNull(it.note.takeIf { n -> n.isNotBlank() }, if (it.low > 0) (if (it.isLow()) "⚠ 부족 (${it.low}개 아래)" else "부족 기준 ${it.low}개") else null)
                if (notes.isNotEmpty()) card.addView(Ui.text(a, 11.5f, if (it.isLow()) Color.parseColor("#FF8A92") else Ui.SUB, false, notes.joinToString(" · ")), Ui.lp(a, MATCH, WRAP, top = 4))
                box.addView(card, Ui.lp(a, MATCH, WRAP, top = 8))
            }
            box.addView(Ui.text(a, 14f, Ui.TEXT, true, "＋ 항목 추가").apply {
                gravity = Gravity.CENTER; minHeight = dp(46); background = Ui.bg(a, Color.TRANSPARENT, 14, Ui.LINE)
                setOnClickListener { editItem(c, null) }
            }, Ui.lp(a, MATCH, WRAP, top = 10))
            box.addView(Ui.text(a, 11.5f, Ui.SUB, false, "항목을 누르면 이름 · 비고 · 세부(호수) · 부족 기준 수정 · 꾹 누르면 삭제").apply { gravity = Gravity.CENTER }, Ui.lp(a, MATCH, WRAP, top = 6))
        }

        render = {
            box.removeAllViews()
            val c = cur
            if (c != null && cats.contains(c)) renderItems(c) else { cur = null; renderCats() }
        }
        render()
        dlg.setContentView(root)
        dlg.setOnKeyListener { _, code, ev ->
            if (code == android.view.KeyEvent.KEYCODE_BACK && ev.action == android.view.KeyEvent.ACTION_UP && cur != null) { cur = null; render(); true } else false
        }
        dlg.show()
    }
}
