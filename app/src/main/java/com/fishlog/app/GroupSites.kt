package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import com.google.firebase.firestore.FirebaseFirestore

/**
 * v6.36 (294): 🔖 그룹 사이트 메모 — 그룹원이 같이 보는 사이트 목록 (내 폰 전용 '🌐 자주 가는 사이트'와 따로)
 *  - 저장 groups/{gid}/sites/{id} {name, url, memo, owner, ownerName, t, order, folder}
 *  - 추가 = 그룹원 누구나 · 수정 · 삭제 = 올린 사람 + 그룹장 · 순서 = 그룹장
 * v6.66 (353): 📁 묶음(커튼)
 *  - 묶음 groups/{gid}/siteFolders/{id} {name, icon, order} · 만들기 · 고치기 · 순서 = 그룹장
 *  - 처음엔 묶음 제목만 · 누르면 펼침 · 펼친 상태 기억(prefs gsite_open_<gid>)
 *  - 사이트 한 줄 = 이름 + 메모 · 누르면 바로 열기 · 꾹 = 메뉴 (열기 · 담기 · 옮기기 · 고치기 · 순서 · 지우기)
 *  - 묶음이 없거나 지워진 묶음의 사이트는 '📌 기타'
 */
object GroupSites {
    class Site(val id: String, val name: String, val url: String, val memo: String, val owner: String, val ownerName: String,
               val t: Long, val order: Long, val folder: String)
    class Folder(val id: String, val name: String, val icon: String, val order: Long)

    val ICONS = listOf("🚤", "🛒", "🎣", "🦑", "🏨", "🍽", "🗺", "🌊", "⛽", "📞", "🎁", "📌")
    private val BOX = Color.parseColor("#12283A")
    private val EDGE = Color.parseColor("#3A6A88")
    private val ICON_BG = listOf("#1F5C7A", "#6A4A1F", "#2F5A35", "#4A3A6A", "#6A2F3A", "#3A4A5A")

    private val db get() = FirebaseFirestore.getInstance()
    private fun col(gid: String) = db.collection("groups").document(gid).collection("sites")
    private fun fcol(gid: String) = db.collection("groups").document(gid).collection("siteFolders")
    private fun prefs(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)

    fun show(a: Activity, g: Cloud.Group) {
        fun dp(v: Int) = Ui.dp(a, v)
        val dlg = Dialog(a, R.style.FlFullUp)
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG); setPadding(dp(14), dp(12), dp(14), dp(10)) }
        val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.text(a, 19f, Ui.TEXT, true, "🔖 그룹 사이트 메모"), LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(Ui.text(a, 13f, Ui.TEXT, true, "닫기").apply {
            gravity = Gravity.CENTER; setPadding(dp(14), dp(6), dp(14), dp(6)); background = Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE)
            setOnClickListener { dlg.dismiss() }
        })
        root.addView(top)
        root.addView(Ui.text(a, 12f, Ui.SUB, false, "${g.title} 그룹원이 같이 보는 목록 · 줄 누르면 바로 열림 · 꾹 누르면 더보기"), Ui.lp(a, MATCH, WRAP, top = 4))
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(a).apply { addView(box) }, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })
        lateinit var load: () -> Unit
        val isBoss = g.owner == Cloud.uid
        var sites: List<Site> = emptyList()
        var folders: List<Folder> = emptyList()
        val openKey = "gsite_open_${g.id}"
        val open = HashSet(prefs(a).getStringSet(openKey, null) ?: emptySet())
        if (prefs(a).getStringSet(openKey, null) == null) open.add("*first")   // 처음엔 첫 묶음 펼침

        fun et(h: String, v: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(a).apply {
            hint = h; setText(v); setTextColor(Ui.TEXT); setHintTextColor(Ui.SUB); inputType = type
        }
        fun folderOf(s: Site) = if (folders.any { it.id == s.folder }) s.folder else ""
        fun chip(t: String, on: Boolean, dashed: Boolean = false, click: () -> Unit) = TextView(a).apply {
            text = t; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(dp(11), dp(6), dp(11), dp(6))
            setTextColor(if (on) Color.parseColor("#06303A") else if (dashed) Ui.SUB else Ui.TEXT)
            background = if (on) Ui.bg(a, Color.parseColor("#5BC8E8"), 99) else Ui.bg(a, Color.TRANSPARENT, 99, Color.parseColor("#3A5A70"))
            setOnClickListener { click() }
        }
        fun wrapRow(): LinearLayout = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        /** 칩 여러 개를 폭에 맞춰 줄 바꿈 */
        fun flow(container: LinearLayout, views: List<View>) {
            container.removeAllViews()
            val maxW = Ui.contentW(a) - dp(60)
            var row: LinearLayout? = null; var used = 0
            views.forEach { v ->
                v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                val w = v.measuredWidth + dp(6)
                if (row == null || used + w > maxW) {
                    row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
                    container.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) }); used = 0
                }
                row!!.addView(v, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) }); used += w
            }
        }

        // ── 묶음 만들기 / 고치기 (그룹장) ──
        fun editFolder(f: Folder?, done: (String?) -> Unit = {}) {
            val form = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), 0) }
            val n = et("묶음 이름 (예: 배 선단 · 채비 구입)", f?.name ?: "")
            form.addView(n)
            var icon = f?.icon ?: ICONS[folders.size % ICONS.size]
            val iconBox = wrapRow()
            fun paintIcons() { flow(iconBox, ICONS.map { ic -> chip(ic, ic == icon) { icon = ic; paintIcons() } }) }
            paintIcons()
            form.addView(Ui.text(a, 12f, Ui.SUB, false, "그림 고르기"), Ui.lp(a, MATCH, WRAP, top = 10))
            form.addView(iconBox)
            AlertDialog.Builder(a, R.style.FlAlert).setTitle(if (f == null) "📁 새 묶음" else "📁 묶음 고치기").setView(form)
                .setPositiveButton("저장") { _, _ ->
                    val nm = n.text.toString().trim().take(30)
                    if (nm.isEmpty()) { Ui.toast(a, "묶음 이름을 적어 주세요"); return@setPositiveButton }
                    val ref = if (f == null) fcol(g.id).document() else fcol(g.id).document(f.id)
                    ref.set(mapOf("name" to nm, "icon" to icon, "order" to (f?.order ?: ((folders.maxOfOrNull { it.order } ?: 0L) + 1))))
                        .addOnSuccessListener { open.add(ref.id); done(ref.id); load() }
                        .addOnFailureListener { Ui.toast(a, "저장 실패 (보안 규칙 확인): ${it.message}"); done(null) }
                }.setNegativeButton("취소") { _, _ -> done(null) }.show()
        }
        fun manageFolders() {
            val sh = Sheet(a, 0.7f)
            sh.head.addView(Ui.text(a, 17f, Ui.TEXT, true, "📁 묶음 관리"), LinearLayout.LayoutParams(0, WRAP, 1f))
            fun paint() {
                sh.body.removeAllViews()
                if (folders.isEmpty()) sh.body.addView(Ui.text(a, 13f, Ui.SUB, false, "아직 묶음이 없어요 · 아래 '＋ 새 묶음'으로 만들어 보세요 (예: 🚤 배 선단 · 🛒 채비 구입)"))
                folders.forEachIndexed { i, f ->
                    val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(8), dp(4), dp(8)) }
                    r.addView(Ui.text(a, 15f, Ui.TEXT, true, "${f.icon} ${f.name}  ").apply {
                        append(android.text.SpannableStringBuilder("· ${sites.count { folderOf(it) == f.id }}").apply {
                            setSpan(android.text.style.ForegroundColorSpan(Ui.SUB), 0, length, 0)
                        })
                    }, LinearLayout.LayoutParams(0, WRAP, 1f))
                    fun b(t: String, col: Int, on: () -> Unit) = r.addView(Ui.text(a, 13f, col, true, t).apply {
                        gravity = Gravity.CENTER; background = Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE); setOnClickListener { on() }
                    }, LinearLayout.LayoutParams(dp(36), dp(34)).apply { leftMargin = dp(5) })
                    fun swap(j: Int) {
                        val o = folders[j]
                        fcol(g.id).document(f.id).update("order", o.order)
                        fcol(g.id).document(o.id).update("order", f.order).addOnCompleteListener { load(); sh.close() ; a.window.decorView.postDelayed({ manageFolders() }, 350) }
                    }
                    if (i > 0) b("▲", Ui.SUB) { swap(i - 1) }
                    if (i < folders.size - 1) b("▼", Ui.SUB) { swap(i + 1) }
                    b("✎", Ui.TEXT) { sh.close(); editFolder(f) }
                    b("🗑", Color.parseColor("#FF8A92")) {
                        val n = sites.count { folderOf(it) == f.id }
                        AlertDialog.Builder(a, R.style.FlAlert).setMessage("'${f.name}' 묶음을 지울까요?" + if (n > 0) "\n안에 있던 사이트 ${n}개는 '📌 기타'로 가요." else "")
                            .setPositiveButton("지우기") { _, _ ->
                                sites.filter { it.folder == f.id }.forEach { col(g.id).document(it.id).update("folder", "") }
                                fcol(g.id).document(f.id).delete().addOnCompleteListener { sh.close(); load() }
                            }.setNegativeButton("취소", null).show()
                    }
                    sh.body.addView(r)
                }
                sh.body.addView(Ui.text(a, 14f, Ui.TEXT, true, "＋ 새 묶음").apply {
                    gravity = Gravity.CENTER; minHeight = dp(44); background = Ui.bg(a, Color.TRANSPARENT, 14, Ui.LINE)
                    setOnClickListener { sh.close(); editFolder(null) }
                }, Ui.lp(a, MATCH, WRAP, top = 10))
                sh.body.addView(Ui.text(a, 11.5f, Ui.SUB, false, "묶음을 지우면 안의 사이트는 '📌 기타'로 가요 (사이트는 안 지워져요)"), Ui.lp(a, MATCH, WRAP, top = 6))
            }
            paint()
            sh.show()
        }

        // ── 사이트 추가 / 고치기 (밑에서 올라오는 창) ──
        fun edit(s: Site?, nextOrder: Long, preFolder: String = "") {
            val sh = Sheet(a, 0.8f)
            sh.head.addView(Ui.text(a, 17f, Ui.TEXT, true, if (s == null) "＋ 사이트 추가" else "✎ 사이트 고치기"), LinearLayout.LayoutParams(0, WRAP, 1f))
            fun lab(t: String) = sh.body.addView(Ui.text(a, 12f, Ui.SUB, false, t), Ui.lp(a, MATCH, WRAP, top = 8))
            lab("이름"); val n = et("예: 헌터호 예약", s?.name ?: ""); sh.body.addView(n)
            lab("주소"); val u = et("예: www.OO.com", s?.url ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI); sh.body.addView(u)
            lab("메모 (한 줄 · 선택)"); val m = et("예: 쭈갑 · 오천항", s?.memo ?: ""); sh.body.addView(m)
            lab("묶음")
            var fold = s?.let { folderOf(it) } ?: preFolder
            val fb = wrapRow()
            fun paintF() {
                val v = ArrayList<View>()
                folders.forEach { f -> v.add(chip("${f.icon} ${f.name}", fold == f.id) { fold = f.id; paintF() }) }
                v.add(chip("📌 기타", fold == "") { fold = ""; paintF() })
                if (isBoss) v.add(chip("＋ 새 묶음", false, true) {
                    editFolder(null) { id -> if (id != null) { fold = id; a.window.decorView.postDelayed({ paintF() }, 600) } }
                })
                flow(fb, v)
            }
            paintF()
            sh.body.addView(fb)
            sh.foot.visibility = View.VISIBLE
            sh.foot.addView(Ui.button(a, "저장", true) {
                val nm = n.text.toString().trim().take(40); val url = u.text.toString().trim().take(300)
                if (nm.isEmpty() || url.isEmpty()) { Ui.toast(a, "이름과 주소를 적어 주세요"); return@button }
                val me = Cloud.uid ?: return@button
                val ref = if (s == null) col(g.id).document() else col(g.id).document(s.id)
                ref.set(mapOf("name" to nm, "url" to url, "memo" to m.text.toString().trim().take(200),
                    "owner" to (s?.owner ?: me), "ownerName" to (s?.ownerName ?: Cloud.displayName),
                    "t" to (s?.t ?: System.currentTimeMillis()), "order" to (s?.order ?: nextOrder), "folder" to fold))
                    .addOnSuccessListener { open.add(fold.ifEmpty { "_etc" }); sh.close(); load() }
                    .addOnFailureListener { Ui.toast(a, "저장 실패 (보안 규칙 확인): ${it.message}") }
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            sh.show()
        }

        // ── 꾹 누르면 ──
        fun menu(s: Site) {
            val canEdit = s.owner == Cloud.uid || isBoss
            val items = ArrayList<Pair<String, () -> Unit>>()
            items.add("🌐 인터넷 창으로 열기" to { Boats.openUrl(a, s.url) })
            items.add("＋ 내 사이트에 담기" to {
                Ui.toast(a, if (Boats.addMine(a, Boats.Site(s.name, s.url, "", s.memo))) "내 '🌐 자주 가는 사이트'에 담았어요" else "이미 내 사이트에 있어요")
            })
            items.add("📋 주소 복사" to {
                (a.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("url", s.url)); Ui.toast(a, "주소를 복사했어요")
            })
            if (canEdit && (folders.isNotEmpty())) items.add("📁 다른 묶음으로 옮기기" to {
                val opts = folders.map { "${it.icon} ${it.name}" to it.id } + ("📌 기타" to "")
                AlertDialog.Builder(a, R.style.FlAlert).setTitle("어느 묶음으로?")
                    .setItems(opts.map { it.first }.toTypedArray()) { _, i ->
                        col(g.id).document(s.id).update("folder", opts[i].second)
                            .addOnSuccessListener { open.add(opts[i].second.ifEmpty { "_etc" }); load() }
                            .addOnFailureListener { Ui.toast(a, "옮기기 실패 (보안 규칙 확인): ${it.message}") }
                    }.show()
            })
            if (canEdit) items.add("✎ 고치기" to { edit(s, s.order) })
            if (isBoss) {
                val same = sites.filter { folderOf(it) == folderOf(s) }
                val i = same.indexOfFirst { it.id == s.id }
                fun swap(j: Int) {
                    val o = same[j]
                    col(g.id).document(s.id).update("order", o.order)
                    col(g.id).document(o.id).update("order", s.order).addOnCompleteListener { load() }
                }
                if (i > 0) items.add("▲ 위로" to { swap(i - 1) })
                if (i in 0 until same.size - 1) items.add("▼ 아래로" to { swap(i + 1) })
            }
            if (canEdit) items.add("🗑 지우기" to {
                AlertDialog.Builder(a, R.style.FlAlert).setMessage("'${s.name}'을(를) 그룹 목록에서 지울까요?")
                    .setPositiveButton("삭제") { _, _ -> col(g.id).document(s.id).delete().addOnSuccessListener { load() }.addOnFailureListener { Ui.toast(a, "삭제 실패: ${it.message}") } }
                    .setNegativeButton("취소", null).show()
            })
            val head = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(16), dp(22), dp(4)) }
            head.addView(Ui.text(a, 16f, Ui.TEXT, true, s.name))
            head.addView(Ui.text(a, 11.5f, Color.parseColor("#7FA3B8"), false, "${s.url}\n올린 사람 ${s.ownerName}"), Ui.lp(a, MATCH, WRAP, top = 2))
            AlertDialog.Builder(a, R.style.FlAlert).setCustomTitle(head)
                .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
        }

        fun saveOpen() { prefs(a).edit().putStringSet(openKey, HashSet(open)).apply() }

        fun render() {
            box.removeAllViews()
            val groups = folders.map { it.id to it } + listOf("" to Folder("", "기타", "📌", Long.MAX_VALUE))
            if (open.remove("*first")) { open.add(groups.firstOrNull { (id, _) -> sites.any { folderOf(it) == id } }?.first?.ifEmpty { "_etc" } ?: "_etc"); saveOpen() }
            if (sites.isEmpty() && folders.isEmpty())
                box.addView(Ui.text(a, 14f, Ui.SUB, false, "아직 없어요 · 아래 '＋ 사이트 추가'로 예약 사이트 · 선사 · 카페 주소를 같이 모아 보세요"), Ui.lp(a, MATCH, WRAP, top = 8))
            groups.forEachIndexed { gi, (fid, f) ->
                val list = sites.filter { folderOf(it) == fid }
                if (fid == "" && list.isEmpty() && folders.isNotEmpty()) return@forEachIndexed   // 빈 '기타'는 숨김
                val key = fid.ifEmpty { "_etc" }
                val isOpen = key in open
                val card = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = Ui.bg(a, BOX, 16, EDGE); clipToOutline = true }
                val h = LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(11), dp(14), dp(11)); isClickable = true
                    setOnClickListener { if (!open.remove(key)) open.add(key); saveOpen(); render() }
                    if (isBoss && fid.isNotEmpty()) setOnLongClickListener { editFolder(f); true }
                }
                h.addView(Ui.text(a, 17f, Ui.TEXT, false, f.icon).apply {
                    gravity = Gravity.CENTER; background = Ui.bg(a, Color.parseColor(if (fid.isEmpty()) "#3A4A5A" else ICON_BG[gi % ICON_BG.size]), 10)
                }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(10) })
                h.addView(Ui.text(a, 15.5f, Ui.TEXT, true, f.name).apply {
                    append(android.text.SpannableStringBuilder(" · ${list.size}").apply {
                        setSpan(android.text.style.ForegroundColorSpan(Ui.SUB), 0, length, 0)
                        setSpan(android.text.style.AbsoluteSizeSpan(12, true), 0, length, 0)
                    })
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                h.addView(Ui.text(a, 14f, Color.parseColor("#7FD3F5"), true, if (isOpen) "▲" else "▼"))
                card.addView(h)
                if (isOpen) {
                    if (list.isEmpty()) card.addView(Ui.text(a, 12.5f, Ui.SUB, false, "비어 있어요 · '＋ 사이트 추가'에서 이 묶음을 고르세요").apply {
                        setPadding(dp(14), dp(10), dp(14), dp(12)); background = Ui.bg(a, Color.TRANSPARENT, 0)
                        setOnClickListener { edit(null, (sites.maxOfOrNull { it.order } ?: 0L) + 1, fid) }
                    })
                    list.forEach { s ->
                        card.addView(View(a).apply { setBackgroundColor(Color.parseColor("#1F3A4E")) }, LinearLayout.LayoutParams(MATCH, dp(1)))
                        val r = LinearLayout(a).apply {
                            orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(14), dp(10), dp(14), dp(10)); isClickable = true
                            setOnClickListener { Boats.openUrl(a, s.url) }
                            setOnLongClickListener { menu(s); true }
                        }
                        val tc = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                        tc.addView(Ui.text(a, 14.5f, Ui.TEXT, true, s.name).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                        if (s.memo.isNotBlank()) tc.addView(Ui.text(a, 12f, Color.parseColor("#9FB4C2"), false, s.memo).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                        // v6.67 (354): 🌐 주소 한 줄 (https:// · www. 빼고 · 길면 …)
                        val shortUrl = s.url.trim().replace(Regex("^(?i)https?://"), "").replace(Regex("^(?i)www\\."), "").trimEnd('/')
                        if (shortUrl.isNotBlank()) tc.addView(Ui.text(a, 11.5f, Color.parseColor("#7FD3F5"), false, "🌐 $shortUrl").apply {
                            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(1) })
                        r.addView(tc, LinearLayout.LayoutParams(0, WRAP, 1f))
                        r.addView(Ui.text(a, 18f, Color.parseColor("#7FD3F5"), true, "›"), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
                        card.addView(r)
                    }
                }
                box.addView(card, Ui.lp(a, MATCH, WRAP, top = if (gi == 0) 2 else 9))
            }
            fun dashed(t: String, click: () -> Unit) = box.addView(Ui.text(a, 14f, Ui.SUB, true, t).apply {
                gravity = Gravity.CENTER; minHeight = dp(46); background = Ui.bg(a, Color.TRANSPARENT, 14, Color.parseColor("#3A5A70"))
                setOnClickListener { click() }
            }, Ui.lp(a, MATCH, WRAP, top = 10))
            dashed("＋ 사이트 추가") { edit(null, (sites.maxOfOrNull { it.order } ?: 0L) + 1) }
            if (isBoss) dashed("📁 묶음 만들기 · 순서 바꾸기") { manageFolders() }
            box.addView(Ui.text(a, 11.5f, Ui.SUB, false, "추가는 그룹원 누구나 · 고치기 · 지우기는 올린 사람과 그룹장 · 묶음 · 순서는 그룹장").apply { gravity = Gravity.CENTER }, Ui.lp(a, MATCH, WRAP, top = 6))
        }
        load = {
            var got = 0
            fun done() { if (++got == 2 && dlg.isShowing) render() }
            fcol(g.id).get().addOnSuccessListener { q ->
                folders = q.documents.map { d -> Folder(d.id, d.getString("name") ?: "", d.getString("icon") ?: "📁", d.getLong("order") ?: 0L) }
                    .sortedWith(compareBy({ it.order }, { it.name }))
                // v6.67 (354): 묶음이 하나도 없는 그룹은 그룹장이 처음 열 때 '🚤 선단 사이트' 한 번만 만들어 줌 (지운 뒤엔 다시 안 만듦)
                val defKey = "gsite_def_${g.id}"
                if (isBoss && folders.isEmpty() && !prefs(a).getBoolean(defKey, false)) {
                    prefs(a).edit().putBoolean(defKey, true).apply()
                    val ref = fcol(g.id).document()
                    ref.set(mapOf("name" to "선단 사이트", "icon" to "🚤", "order" to 1L))
                    folders = listOf(Folder(ref.id, "선단 사이트", "🚤", 1L))
                } else if (folders.isNotEmpty()) prefs(a).edit().putBoolean(defKey, true).apply()
                done()
            }.addOnFailureListener { folders = emptyList(); done() }   // 규칙 게시 전이면 묶음 없이
            col(g.id).get().addOnSuccessListener { q ->
                sites = q.documents.map { d ->
                    Site(d.id, d.getString("name") ?: "", d.getString("url") ?: "", d.getString("memo") ?: "",
                        d.getString("owner") ?: "", d.getString("ownerName") ?: "", d.getLong("t") ?: 0L, d.getLong("order") ?: 0L,
                        d.getString("folder") ?: "")
                }.sortedWith(compareBy({ it.order }, { it.t }))
                done()
            }.addOnFailureListener {
                if (!dlg.isShowing) return@addOnFailureListener
                box.removeAllViews()
                box.addView(Ui.text(a, 13f, Ui.ORANGE, false, "불러오지 못했어요 · 서버 규칙이 아직 게시 전일 수 있어요 (${it.message})"), Ui.lp(a, MATCH, WRAP, top = 8))
            }
        }
        box.addView(Ui.text(a, 13f, Ui.SUB, false, "불러오는 중…"))
        dlg.setContentView(root)
        Ui.edge(dlg.window)
        dlg.show()
        load()
    }
}
