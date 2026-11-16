package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import org.json.JSONArray
import org.json.JSONObject

/**
 * v6.23 (270): ⛴ 자주 가는 선사 — 배(선사) 이름 · 예약 사이트 주소 · 전화 · 메모
 *  [🌐 열기] = 크롬 등 인터넷 창으로 · [📞] 전화 · [✎] 수정 · [🗑] 삭제 · ▲▼ 순서
 *  저장은 폰 (SharedPreferences "fishlog" 의 boat_sites → 💾 백업에 자동 포함)
 */
object Boats {
    class Site(var name: String, var url: String, var phone: String = "", var memo: String = "")

    private const val KEY = "boat_sites"

    fun load(c: Context): MutableList<Site> = try {
        val a = JSONArray(c.getSharedPreferences("fishlog", Context.MODE_PRIVATE).getString(KEY, "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Site(it.optString("n"), it.optString("u"), it.optString("p"), it.optString("m")) } }.toMutableList()
    } catch (_: Exception) { mutableListOf() }

    private fun save(c: Context, list: List<Site>) {
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("n", it.name).put("u", it.url).put("p", it.phone).put("m", it.memo)) }
        c.getSharedPreferences("fishlog", Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }

    /** 배 이름이 같은 선사 (출조 기록 화면 바로가기용 · 띄어쓰기 · 대소문자 무시) */
    fun find(c: Context, boat: String): Site? {
        val k = boat.replace(" ", "").lowercase()
        if (k.isBlank()) return null
        return load(c).firstOrNull { it.url.isNotBlank() && it.name.replace(" ", "").lowercase() == k }
    }

    fun openUrl(a: Activity, url: String) {
        val u = url.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addCategory(Intent.CATEGORY_BROWSABLE)) }
        catch (_: Exception) { Ui.toast(a, "인터넷 창을 열 수 없어요 · 주소를 확인해 주세요") }
    }

    private fun call(a: Activity, phone: String) {
        try { a.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.filter { it.isDigit() || it == '+' }))) }
        catch (_: Exception) { Ui.toast(a, "전화를 걸 수 없어요") }
    }

    fun show(a: Activity) {
        fun dp(v: Int) = Ui.dp(a, v)
        var list = load(a)
        val dlg = Dialog(a, R.style.FlFullUp)
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG); setPadding(dp(14), dp(12), dp(14), dp(10)) }
        val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.text(a, 20f, Ui.TEXT, true, "⛴ 자주 가는 선사"), LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(Ui.text(a, 13f, Ui.TEXT, true, "닫기").apply {
            gravity = Gravity.CENTER; setPadding(dp(16), dp(8), dp(16), dp(8))
            background = Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE); setOnClickListener { dlg.dismiss() }
        })
        root.addView(top)
        root.addView(Ui.text(a, 12f, Ui.SUB, false, "🌐 열기를 누르면 그 선사 예약 사이트가 인터넷 창(크롬 등)으로 열려요"), Ui.lp(a, MATCH, WRAP, top = 6))
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(a).apply { addView(box) }, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })
        lateinit var render: () -> Unit

        fun et(h: String, v: String, type: Int) = EditText(a).apply {
            hint = h; setText(v); setTextColor(Ui.TEXT); setHintTextColor(Ui.SUB); setSingleLine(); inputType = type
        }

        fun edit(s: Site?) {
            val f = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), 0) }
            val name = android.widget.AutoCompleteTextView(a).apply {
                hint = "배(선사) 이름 (예: 오천 OO호)"; setText(s?.name ?: ""); threshold = 0
                setTextColor(Ui.TEXT); setHintTextColor(Ui.SUB); setSingleLine()
                setAdapter(android.widget.ArrayAdapter(a, android.R.layout.simple_dropdown_item_1line, Store.boats()))
                setOnClickListener { showDropDown() }
            }
            val url = et("예약 사이트 주소 (예: www.OO.com)", s?.url ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            val phone = et("전화번호 (선택)", s?.phone ?: "", InputType.TYPE_CLASS_PHONE)
            val memo = et("메모 (선택 · 예: 쭈갑 · 오천항 · 새벽 5시)", s?.memo ?: "", InputType.TYPE_CLASS_TEXT)
            f.addView(name); f.addView(url); f.addView(phone); f.addView(memo)
            AlertDialog.Builder(a, R.style.FlAlert).setTitle(if (s == null) "＋ 선사 추가" else "선사 수정").setView(f)
                .setPositiveButton("저장") { _, _ ->
                    val n = name.text.toString().trim()
                    if (n.isBlank()) { Ui.toast(a, "배(선사) 이름을 적어 주세요"); return@setPositiveButton }
                    val t = s ?: Site("", "").also { list.add(it) }
                    t.name = n; t.url = url.text.toString().trim(); t.phone = phone.text.toString().trim(); t.memo = memo.text.toString().trim()
                    save(a, list); render()
                }
                .setNegativeButton("취소", null).show()
        }

        fun chip(label: String, color: Int, click: () -> Unit) = Ui.text(a, 13f, color, true, label).apply {
            gravity = Gravity.CENTER; setPadding(dp(10), 0, dp(10), 0)
            background = Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE); setOnClickListener { click() }
        }

        render = {
            box.removeAllViews()
            if (list.isEmpty()) box.addView(Ui.text(a, 14f, Ui.SUB, false, "아직 저장한 선사가 없어요.\n아래 '＋ 선사 추가'로 배 이름과 예약 사이트 주소를 넣어 보세요.").apply {
                gravity = Gravity.CENTER; setPadding(0, dp(30), 0, dp(10))
            }, Ui.lp(a, MATCH, WRAP))
            list.forEachIndexed { i, s ->
                val card = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); background = Ui.bg(a, Ui.CARD, 16)
                }
                card.addView(Ui.text(a, 17f, Ui.TEXT, true, "🚤 " + s.name))
                if (s.memo.isNotBlank()) card.addView(Ui.text(a, 12.5f, Ui.SUB, false, s.memo), Ui.lp(a, MATCH, WRAP, top = 2))
                card.addView(Ui.text(a, 11.5f, if (s.url.isBlank()) Ui.SUB else Ui.BLUE, false, s.url.ifBlank { "예약 사이트 주소 없음 (✎ 수정에서 넣기)" }).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                }, Ui.lp(a, MATCH, WRAP, top = 2))
                val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                if (s.url.isNotBlank()) row.addView(Ui.text(a, 14f, Color.parseColor("#04222A"), true, "🌐 열기").apply {
                    gravity = Gravity.CENTER; background = Ui.bg(a, Ui.BLUE, 99); setOnClickListener { openUrl(a, s.url) }
                }, LinearLayout.LayoutParams(0, dp(40), 1f))
                else row.addView(android.view.View(a), LinearLayout.LayoutParams(0, dp(40), 1f))
                if (s.phone.isNotBlank()) row.addView(chip("📞", Ui.TEXT) { call(a, s.phone) }, LinearLayout.LayoutParams(dp(46), dp(40)).apply { leftMargin = dp(6) })
                row.addView(chip("✎", Ui.TEXT) { edit(s) }, LinearLayout.LayoutParams(dp(46), dp(40)).apply { leftMargin = dp(6) })
                row.addView(chip("🗑", Ui.TEXT) {
                    AlertDialog.Builder(a, R.style.FlAlert).setMessage("'${s.name}'을(를) 지울까요?")
                        .setPositiveButton("삭제") { _, _ -> list.remove(s); save(a, list); render() }.setNegativeButton("취소", null).show()
                }, LinearLayout.LayoutParams(dp(46), dp(40)).apply { leftMargin = dp(6) })
                row.addView(chip("▲", if (i == 0) Ui.SUB else Ui.TEXT) {
                    if (i > 0) { list.removeAt(i); list.add(i - 1, s); save(a, list); render() }
                }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(6) })
                row.addView(chip("▼", if (i == list.size - 1) Ui.SUB else Ui.TEXT) {
                    if (i < list.size - 1) { list.removeAt(i); list.add(i + 1, s); save(a, list); render() }
                }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(4) })
                card.addView(row, Ui.lp(a, MATCH, WRAP, top = 10))
                box.addView(card, Ui.lp(a, MATCH, WRAP, top = if (i == 0) 0 else 10))
            }
            box.addView(Ui.button(a, "＋ 선사 추가") { edit(null) }, Ui.lp(a, MATCH, WRAP, top = 14))
        }
        render()
        dlg.setContentView(root)
        Ui.edge(dlg.window)
        dlg.show()
    }
}
