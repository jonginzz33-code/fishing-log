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

    class Item(var name: String, var qty: Int, var note: String, val subs: MutableList<Item> = mutableListOf(), var low: Int = 0, var icon: String = "") {
        fun total(): Int = if (subs.isEmpty()) qty else subs.sumOf { it.total() }
        fun isLow() = low > 0 && total() < low
    }
    class Cat(var name: String, var icon: String, val items: MutableList<Item> = mutableListOf()) {
        fun total() = items.sumOf { it.total() }
        fun lowCount() = items.count { it.isLow() }
    }

    private val ICONS = listOf("🎒", "🎣", "🥅", "🦺", "🍙", "🧰", "🪝", "🦑", "🦀", "🔦", "🧤", "🧊")

    /** v4.8 (220): 낚시 장비 그림 (res/drawable-nodpi/gear_*.png) · 아이콘 글자는 "g:이름" */
    val GEAR = listOf(
        "sinker" to "봉돌", "iron" to "쇠봉돌", "tungsten" to "텅스텐", "glow" to "형광 봉돌", "stick" to "막대 봉돌",
        "hole" to "구멍 봉돌", "swsinker" to "도래 봉돌", "color" to "컬러 봉돌", "spike" to "스파이크", "splitshot" to "좁쌀 봉돌",
        "snap" to "핀도래", "swivel" to "도래", "jighead" to "지그헤드", "rig" to "채비", "egi" to "에기", "hook" to "바늘",
        "line" to "낚싯줄", "rod" to "낚싯대", "baitreel" to "베이트 릴", "spinreel" to "스피닝 릴",
        "vest" to "구명조끼", "cooler" to "쿨러", "lamp" to "집어등", "glove" to "장갑")

    /** 이름으로 그림 고르기 (구체적인 것부터) · 없으면 "" */
    fun autoIcon(name: String): String {
        val n = name.replace(" ", "")
        val rules = listOf(
            listOf("텅스텐") to "tungsten", listOf("형광", "야광") to "glow", listOf("막대봉돌", "막대") to "stick",
            listOf("쇠봉돌", "무쇠") to "iron", listOf("구멍봉돌", "관통") to "hole", listOf("도래봉돌") to "swsinker",
            listOf("컬러봉돌", "컬러") to "color", listOf("스파이크", "원투봉돌") to "spike", listOf("좁쌀", "좁살") to "splitshot",
            listOf("봉돌", "싱커") to "sinker", listOf("핀도", "스냅") to "snap", listOf("도래", "도레", "스위벨") to "swivel",
            listOf("지그") to "jighead", listOf("에기") to "egi", listOf("채비", "체비") to "rig", listOf("바늘", "훅") to "hook",
            listOf("베이트") to "baitreel", listOf("스피닝", "릴") to "spinreel", listOf("낚싯대", "낚시대", "로드") to "rod",
            listOf("합사", "원줄", "목줄", "라인", "낚싯줄", "줄") to "line", listOf("구명", "조끼") to "vest",
            listOf("쿨러", "아이스박스") to "cooler", listOf("집어등", "랜턴", "라이트") to "lamp", listOf("장갑") to "glove")
        for ((keys, ic) in rules) if (keys.any { n.contains(it) }) return "g:$ic"
        return ""
    }

    /** 그림 고르기 칸: 장비 그림 24종 + 이모지 (한 줄 6칸씩) · allowNone = '없음'(-) 칸 */
    private fun iconGrid(a: Activity, box: LinearLayout, cur: String, allowNone: Boolean = false, pick: (String) -> Unit) {
        val all = (if (allowNone) listOf("-") else emptyList()) + GEAR.map { "g:" + it.first } + ICONS
        var row: LinearLayout? = null
        all.forEachIndexed { i, ic ->
            if (i % 6 == 0) { row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }; box.addView(row, Ui.lp(a, MATCH, WRAP, top = 4)) }
            val on = ic == cur || (ic == "-" && cur.isBlank())
            val v: View = if (ic == "-") Ui.text(a, 11f, Ui.SUB, true, "없음").apply { gravity = Gravity.CENTER } else iconView(a, ic, 40)
            v.background = if (on) Ui.bg(a, Color.parseColor("#3A2814"), 10, Color.parseColor("#FFB27A")) else Ui.bg(a, Color.parseColor("#0F2233"), 10)
            v.setOnClickListener { pick(ic) }
            row!!.addView(v, LinearLayout.LayoutParams(0, Ui.dp(a, 44), 1f).apply { if (i % 6 > 0) leftMargin = Ui.dp(a, 4) })
        }
        val rem = all.size % 6
        if (rem > 0) repeat(6 - rem) { row!!.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = Ui.dp(a, 4) }) }
    }

    /** 아이콘 보기: "g:이름" = 그림 · 나머지 = 이모지 글자 */
    fun iconView(c: Context, icon: String, sizeDp: Int): View {
        if (icon.startsWith("g:")) {
            val id = c.resources.getIdentifier("gear_" + icon.removePrefix("g:"), "drawable", c.packageName)
            if (id != 0) return android.widget.ImageView(c).apply {
                setImageResource(id); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                val p = Ui.dp(c, maxOf(2, sizeDp / 10)); setPadding(p, p, p, p)
            }
        }
        return Ui.text(c, sizeDp * 0.5f, Ui.TEXT, false, if (icon.startsWith("g:")) "🎒" else icon).apply { gravity = Gravity.CENTER }
    }
    private fun prefs(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)

    private fun itemJson(it: Item): JSONObject = JSONObject().put("n", it.name).put("q", it.qty).put("m", it.note).put("l", it.low).put("i", it.icon)
        .put("s", JSONArray().apply { it.subs.forEach { s -> put(itemJson(s)) } })
    private fun itemFrom(o: JSONObject): Item = Item(o.optString("n"), o.optInt("q"), o.optString("m"),
        (0 until (o.optJSONArray("s")?.length() ?: 0)).map { itemFrom(o.getJSONArray("s").getJSONObject(it)) }.toMutableList(), o.optInt("l"), o.optString("i"))
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
        if (s != null) {
            val cats = try { fromJson(JSONArray(s)) } catch (_: Exception) { mutableListOf() }
            // v4.8 (220): 한 번만 — 그림이 기본(🎒)인 카테고리는 이름 보고 장비 그림으로
            if (!prefs(c).getBoolean("inv_gear1", false)) {
                cats.forEach { k -> if (k.icon == "🎒") autoIcon(k.name).takeIf { it.isNotEmpty() }?.let { k.icon = it } }
                prefs(c).edit().putBoolean("inv_gear1", true).apply()
                save(c, cats, dirty = false)
            }
            return cats
        }
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
        val dlg = Dialog(a, R.style.FlFullUp)
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
        fun confirm(msg: String, ok: () -> Unit) = AlertDialog.Builder(a, R.style.FlAlert)
            .setMessage(msg).setPositiveButton("삭제") { _, _ -> ok() }.setNegativeButton("취소", null).show()

        /** 카테고리 추가 · 수정 (이름 + 그림) */
        fun editCat(c: Cat?) {
            val f = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), 0) }
            val n = et("카테고리 이름 (예: 선상 채비, 해루질 장비)", c?.name ?: "")
            f.addView(n)
            var icon = c?.icon ?: ""
            var picked = c != null
            val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            fun paintIcons() {
                grid.removeAllViews()
                val shown = if (icon.isBlank()) autoIcon(n.text.toString()).ifBlank { "🎒" } else icon
                iconGrid(a, grid, shown) { ic -> icon = ic; picked = true; paintIcons() }
            }
            paintIcons()
            // 이름을 적으면 그림 자동 (직접 고르기 전까지)
            n.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { if (!picked) { icon = ""; paintIcons() } }
                override fun beforeTextChanged(s: CharSequence?, st: Int, c0: Int, af: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c0: Int) {}
            })
            f.addView(Ui.text(a, 12.5f, Ui.SUB, false, "그림 (이름을 적으면 자동으로 골라요 · 눌러서 바꾸기)"), Ui.lp(a, MATCH, WRAP, top = 8))
            f.addView(grid, Ui.lp(a, MATCH, WRAP, top = 4))
            if (c != null) {
                // 순서 바꾸기 (누르면 바로 옮겨짐)
                val mv = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                mv.addView(Ui.text(a, 12.5f, Ui.SUB, false, "순서"), LinearLayout.LayoutParams(0, WRAP, 1f))
                fun mvBtn(t: String, d: Int) = Ui.text(a, 13f, Ui.TEXT, true, t).apply {
                    gravity = Gravity.CENTER; background = Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE)
                    setOnClickListener {
                        val i = cats.indexOf(c); val j = i + d
                        if (i < 0 || j < 0 || j >= cats.size) { toast(a, if (d < 0) "맨 위예요" else "맨 아래예요"); return@setOnClickListener }
                        cats.removeAt(i); cats.add(j, c); save(a, cats); render(); toast(a, if (d < 0) "위로 옮겼어요" else "아래로 옮겼어요")
                    }
                }
                mv.addView(mvBtn("▲ 위로", -1), LinearLayout.LayoutParams(dp(84), dp(36)))
                mv.addView(mvBtn("▼ 아래로", 1), LinearLayout.LayoutParams(dp(84), dp(36)).apply { leftMargin = dp(6) })
                f.addView(mv, Ui.lp(a, MATCH, WRAP, top = 10))
            }
            AlertDialog.Builder(a, R.style.FlAlert).setTitle(if (c == null) "＋ 카테고리 추가" else "카테고리 수정")
                .setView(f)
                .setPositiveButton("저장") { _, _ ->
                    val nm = n.text.toString().trim()
                    if (nm.isEmpty()) { toast(a, "이름을 적어 주세요"); return@setPositiveButton }
                    val ic = icon.ifBlank { autoIcon(nm).ifBlank { "🎒" } }
                    if (c == null) cats.add(Cat(nm, ic)) else { c.name = nm; c.icon = ic }
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
            // v4.8 (220): 항목 그림 (예: 텅스텐 12호 → 텅스텐 봉돌) · 비우면 이름 보고 자동
            var iic = it0?.icon ?: ""
            val igrid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            fun paintI() { igrid.removeAllViews(); iconGrid(a, igrid, iic.ifBlank { autoIcon(n.text.toString()) }, allowNone = true) { ic -> iic = ic; paintI() } }
            paintI()
            n.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { if (iic.isBlank()) paintI() }
                override fun beforeTextChanged(s: CharSequence?, st: Int, c0: Int, af: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c0: Int) {}
            })
            f.addView(Ui.text(a, 12.5f, Ui.SUB, false, "그림 (비우면 이름 보고 자동 · '없음' = 그림 안 씀)"), Ui.lp(a, MATCH, WRAP, top = 10))
            f.addView(igrid, Ui.lp(a, MATCH, WRAP, top = 4))
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
            AlertDialog.Builder(a, R.style.FlAlert).setTitle(if (it0 == null) "＋ 항목 추가" else "항목 수정")
                .setView(ScrollView(a).apply { addView(f) })
                .setPositiveButton("저장") { _, _ ->
                    val nm = n.text.toString().trim()
                    if (nm.isEmpty()) { toast(a, "이름을 적어 주세요"); return@setPositiveButton }
                    val ss = subs.mapNotNull { s -> s.n.text.toString().trim().takeIf { it.isNotEmpty() }?.let { Item(it, s.q.text.toString().toIntOrNull() ?: 0, "") } }
                    val item = it0 ?: Item(nm, 0, "").also { cat.items.add(it) }
                    item.name = nm; item.qty = q.text.toString().toIntOrNull() ?: 0; item.note = m.text.toString().trim()
                    item.low = l.text.toString().toIntOrNull() ?: 0
                    item.icon = iic
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

        fun smallBtn(t: String, col: Int, onClick: () -> Unit) = Ui.text(a, 14f, col, true, t).apply {
            gravity = Gravity.CENTER; background = Ui.bg(a, Color.TRANSPARENT, 10, Ui.LINE); setOnClickListener { onClick() }
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
                }
                r.addView(iconView(a, c.icon, 46).apply { background = Ui.bg(a, Color.parseColor("#0F2233"), 12) },
                    LinearLayout.LayoutParams(dp(46), dp(46)))
                val tc = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
                tc.addView(Ui.text(a, 15.5f, Ui.TEXT, true, c.name))
                val lowN = c.lowCount()
                tc.addView(Ui.text(a, 12f, Ui.SUB, false, "항목 ${c.items.size}개 · 합계 ${c.total()}개" + if (lowN > 0) "  ⚠ 부족 $lowN" else "").apply {
                    if (lowN > 0) setTextColor(Color.parseColor("#FF8A92"))
                })
                r.addView(tc, LinearLayout.LayoutParams(0, WRAP, 1f))
                r.addView(smallBtn("✎", Ui.TEXT) { editCat(c) }, LinearLayout.LayoutParams(dp(38), dp(36)))
                r.addView(smallBtn("🗑", Color.parseColor("#FF8A92")) {
                    confirm("'${c.name}' 카테고리를 지울까요? (안의 항목 ${c.items.size}개도 같이)") { cats.remove(c); save(a, cats); render() }
                }, LinearLayout.LayoutParams(dp(38), dp(36)).apply { leftMargin = dp(6) })
                r.addView(Ui.text(a, 20f, Color.parseColor("#FFB27A"), true, "›").apply { setPadding(dp(8), 0, 0, 0) })
                box.addView(r, Ui.lp(a, MATCH, WRAP, top = 8))
            }
            box.addView(Ui.text(a, 14f, Ui.TEXT, true, "＋ 카테고리 추가").apply {
                gravity = Gravity.CENTER; minHeight = dp(46); background = Ui.bg(a, Color.TRANSPARENT, 14, Ui.LINE)
                setOnClickListener { editCat(null) }
            }, Ui.lp(a, MATCH, WRAP, top = 10))
            box.addView(Ui.text(a, 11.5f, Ui.SUB, false, "카테고리를 누르면 안의 항목 · ✎ = 이름 · 그림 · 순서 바꾸기 · 🗑 = 삭제").apply { gravity = Gravity.CENTER }, Ui.lp(a, MATCH, WRAP, top = 6))
            // 서버 저장 · 불러오기
            val dirty = prefs(a).getBoolean("inv_dirty", false)
            val sr = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            sr.addView(Ui.button(a, if (dirty) "☁ 서버에 저장하기 •" else "☁ 서버에 저장하기") { upload(a, cats) { render() } }.apply {
                minHeight = dp(44); setTextColor(Ui.GREEN); background = Ui.bg(a, Color.TRANSPARENT, 14, Ui.GREEN)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            sr.addView(Ui.button(a, "서버에서 불러오기") {
                AlertDialog.Builder(a, R.style.FlAlert)
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
            val ttl = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            ttl.addView(iconView(a, c.icon, 32), LinearLayout.LayoutParams(dp(32), dp(32)))
            ttl.addView(Ui.text(a, 17f, Ui.TEXT, true, c.name).apply { maxLines = 1; setPadding(dp(6), 0, 0, 0) })
            top.addView(ttl, LinearLayout.LayoutParams(0, WRAP, 1f))
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
                }
                val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val iic = if (it.icon == "-") "" else it.icon.ifBlank { autoIcon(it.name) }
                if (iic.isNotEmpty()) r.addView(iconView(a, iic, 34).apply { background = Ui.bg(a, Color.parseColor("#0F2233"), 10) },
                    LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(8) })
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
                // 수정 · 삭제 (눈에 보이게)
                val act = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
                val idx = c.items.indexOf(it)
                if (idx > 0) act.addView(smallBtn("▲", Ui.SUB) { c.items.removeAt(idx); c.items.add(idx - 1, it); save(a, cats); render() },
                    LinearLayout.LayoutParams(dp(34), dp(32)).apply { rightMargin = dp(6) })
                if (idx < c.items.size - 1) act.addView(smallBtn("▼", Ui.SUB) { c.items.removeAt(idx); c.items.add(idx + 1, it); save(a, cats); render() },
                    LinearLayout.LayoutParams(dp(34), dp(32)).apply { rightMargin = dp(6) })
                act.addView(smallBtn("✎ 수정", Ui.TEXT) { editItem(c, it) }, LinearLayout.LayoutParams(dp(70), dp(32)))
                act.addView(smallBtn("🗑 삭제", Color.parseColor("#FF8A92")) {
                    confirm("'${it.name}'을(를) 지울까요?") { c.items.remove(it); save(a, cats); render() }
                }, LinearLayout.LayoutParams(dp(70), dp(32)).apply { leftMargin = dp(6) })
                card.addView(act, Ui.lp(a, MATCH, WRAP, top = 6))
                box.addView(card, Ui.lp(a, MATCH, WRAP, top = 8))
            }
            box.addView(Ui.text(a, 14f, Ui.TEXT, true, "＋ 항목 추가").apply {
                gravity = Gravity.CENTER; minHeight = dp(46); background = Ui.bg(a, Color.TRANSPARENT, 14, Ui.LINE)
                setOnClickListener { editItem(c, null) }
            }, Ui.lp(a, MATCH, WRAP, top = 10))
            box.addView(Ui.text(a, 11.5f, Ui.SUB, false, "＋ − = 수량 · ✎ 수정 = 이름 · 비고 · 세부(호수) · 부족 기준 · 🗑 삭제 · ▲▼ 순서").apply { gravity = Gravity.CENTER }, Ui.lp(a, MATCH, WRAP, top = 6))
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
