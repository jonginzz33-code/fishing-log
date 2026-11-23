package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.google.firebase.firestore.FirebaseFirestore
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * v6.68 (360): 📌 지도 포인트 핀 — 지도를 꾹 눌러 핀(별 모양) · 이름 · 설명 · 반경(50m~3km) · 어종
 *  공유: 🔒 나만(users/{uid}/pins) · 👥 그룹(groups/{gid}/pins) · 🌐 전체(pins)
 *  색: 내 것 주황 · 그룹 하늘 · 전체 보라 (Ui.MINE/GROUP/ALL)
 */
object Pins {
    const val MINE = 0; const val GROUP = 1; const val ALL = 2
    class Pin(val id: String, val scope: Int, val gid: String, val name: String, val memo: String,
              val lat: Double, val lon: Double, val r: Double, val sp: List<String>,
              val owner: String, val ownerName: String, val t: Long) {
        val point get() = GeoPoint(lat, lon)
    }
    fun color(scope: Int) = when (scope) { GROUP -> Ui.GROUP; ALL -> Ui.ALL; else -> Ui.MINE }
    fun scopeLabel(p: Pin) = when (p.scope) {
        GROUP -> "👥 " + (Cloud.groups.firstOrNull { it.id == p.gid }?.title ?: "그룹")
        ALL -> "🌐 전체 게시판"
        else -> "🔒 나만"
    }

    private val db get() = FirebaseFirestore.getInstance()
    private fun prefs(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)
    /** 지도에서 보일 공유 범위 (기억) */
    fun shown(c: Context): MutableSet<Int> =
        (prefs(c).getString("pin_show", "0,1,2") ?: "0,1,2").split(",").mapNotNull { it.toIntOrNull() }.toMutableSet()
    fun setShown(c: Context, s: Set<Int>) = prefs(c).edit().putString("pin_show", s.joinToString(",")).apply()

    private fun ref(scope: Int, gid: String) = when (scope) {
        GROUP -> db.collection("groups").document(gid).collection("pins")
        ALL -> db.collection("pins")
        else -> db.collection("users").document(Cloud.uid ?: "x").collection("pins")
    }

    private fun parse(d: com.google.firebase.firestore.DocumentSnapshot, scope: Int, gid: String): Pin? {
        val la = d.getDouble("lat") ?: return null; val lo = d.getDouble("lon") ?: return null
        @Suppress("UNCHECKED_CAST")
        return Pin(d.id, scope, gid, d.getString("name") ?: "포인트", d.getString("memo") ?: "", la, lo,
            d.getDouble("r") ?: 300.0, (d.get("sp") as? List<String>) ?: emptyList(),
            d.getString("owner") ?: "", d.getString("ownerName") ?: "", d.getLong("t") ?: 0L)
    }

    /** 고른 범위의 핀 모두 (폰 저장분 먼저 오면 그걸로도 한 번) */
    fun load(c: Context, done: (List<Pin>) -> Unit) {
        val me = Cloud.uid ?: return done(emptyList())
        val show = shown(c)
        val out = java.util.concurrent.ConcurrentHashMap<String, List<Pin>>()
        val jobs = mutableListOf<Pair<String, com.google.firebase.firestore.Query>>()
        if (MINE in show) jobs += "m" to db.collection("users").document(me).collection("pins").limit(300)
        if (GROUP in show) Cloud.groups.forEach { g -> jobs += "g:${g.id}" to db.collection("groups").document(g.id).collection("pins").limit(300) }
        if (ALL in show) jobs += "a" to db.collection("pins").orderBy("t", com.google.firebase.firestore.Query.Direction.DESCENDING).limit(300)
        if (jobs.isEmpty()) return done(emptyList())
        var left = jobs.size
        jobs.forEach { (k, q) ->
            q.get().addOnSuccessListener { s ->
                val scope = when { k == "m" -> MINE; k == "a" -> ALL; else -> GROUP }
                val gid = if (k.startsWith("g:")) k.substring(2) else ""
                out[k] = s.documents.mapNotNull { parse(it, scope, gid) }.filter { !Moderation.isBlocked(it.owner) }
            }.addOnCompleteListener {
                left--
                if (left == 0) done(jobs.flatMap { out[it.first].orEmpty() })
            }
        }
    }

    fun save(p: Pin, old: Pin?, done: (String?) -> Unit) {
        val me = Cloud.uid ?: return done("로그인이 필요해요")
        val m = hashMapOf<String, Any>("name" to p.name.take(30), "memo" to p.memo.take(200), "lat" to p.lat, "lon" to p.lon,
            "r" to p.r.coerceIn(50.0, 3000.0), "sp" to p.sp.take(10), "owner" to (old?.owner ?: me),
            "ownerName" to (old?.ownerName ?: Cloud.displayName.take(30)), "t" to (old?.t ?: System.currentTimeMillis()))
        if (old != null) m["edited"] = System.currentTimeMillis()
        val moved = old != null && (old.scope != p.scope || old.gid != p.gid)
        val target = if (old != null && !moved) ref(p.scope, p.gid).document(old.id) else ref(p.scope, p.gid).document()
        if (moved && old!!.owner != me) return done("남의 핀은 공유 범위를 못 바꿔요")
        target.set(m).addOnSuccessListener {
            if (moved) ref(old!!.scope, old.gid).document(old.id).delete()
            done(null)
        }.addOnFailureListener { done(if (it.message?.contains("PERMISSION") == true) "저장 못 했어요 (서버 규칙 업데이트 전일 수 있어요)" else it.message ?: "저장 못 했어요") }
    }

    fun delete(p: Pin, done: (String?) -> Unit) {
        ref(p.scope, p.gid).document(p.id).delete().addOnSuccessListener { done(null) }.addOnFailureListener { done(it.message ?: "못 지웠어요") }
    }

    fun canEdit(p: Pin): Boolean {
        val me = Cloud.uid ?: return false
        return p.owner == me || (p.scope == GROUP && Cloud.groups.firstOrNull { it.id == p.gid }?.owner == me)
    }
    fun canDelete(p: Pin): Boolean = canEdit(p) || (p.scope == ALL && Cloud.isAdmin)

    fun distM(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = 6371000.0; val dLa = Math.toRadians(la2 - la1); val dLo = Math.toRadians(lo2 - lo1)
        val h = sin(dLa / 2) * sin(dLa / 2) + cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(dLo / 2) * sin(dLo / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }
    fun rLabel(r: Double) = if (r >= 1000) String.format(Locale.KOREA, "%.1fkm", r / 1000) else "${r.toInt()}m"

    /** 지도 위 핀 · 반경 원 · 이름표 (꾹 누르면 새 핀) */
    class PinOverlay(ctx: Context, private val onTap: (Pin) -> Unit, private val onLong: (GeoPoint) -> Unit) : Overlay() {
        var pins: List<Pin> = emptyList()
        /** 만들거나 고치는 중인 핀 (반경 미리보기) */
        var draft: Pin? = null
        private val d = ctx.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = 11f * d }
        private val spots = mutableListOf<Pair<Pin, android.graphics.PointF>>()

        private fun star(cx: Float, cy: Float, R: Float): Path {
            val p = Path(); val r = R * 0.45f
            for (i in 0 until 10) {
                val ang = Math.toRadians(-90.0 + i * 36.0)
                val rr = if (i % 2 == 0) R else r
                val x = cx + (rr * cos(ang)).toFloat(); val y = cy + (rr * sin(ang)).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close(); return p
        }

        private fun drawPin(c: Canvas, map: MapView, p: Pin, draftOne: Boolean) {
            val pt = map.projection.toPixels(p.point, null)
            val x = pt.x.toFloat(); val y = pt.y.toFloat()
            val col = color(p.scope)
            // 반경 원 (반투명 + 점선 테두리)
            val rr = map.projection.metersToPixels(p.r.toFloat())
            if (rr > 6 * d) {
                fill.color = col; fill.alpha = if (draftOne) 70 else 45; c.drawCircle(x, y, rr, fill)
                stroke.color = col; stroke.alpha = 230; stroke.strokeWidth = 2f * d
                stroke.pathEffect = DashPathEffect(floatArrayOf(8 * d, 5 * d), 0f); c.drawCircle(x, y, rr, stroke)
                stroke.pathEffect = null
            }
            // 막대 (끝 = 정확한 자리) + 별
            val stick = 12 * d; val R = 13 * d
            stroke.color = Color.WHITE; stroke.alpha = 255; stroke.strokeWidth = 3.5f * d; stroke.strokeCap = Paint.Cap.ROUND
            c.drawLine(x, y, x, y - stick, stroke)
            stroke.color = col; stroke.strokeWidth = 2f * d; c.drawLine(x, y, x, y - stick, stroke)
            fill.color = Color.WHITE; fill.alpha = 255; c.drawCircle(x, y, 2.6f * d, fill)
            val sp = star(x, y - stick - R * 0.8f, R)
            stroke.color = Color.WHITE; stroke.strokeWidth = 2.5f * d; stroke.strokeJoin = Paint.Join.ROUND
            fill.color = col; c.drawPath(sp, fill); c.drawPath(sp, stroke)
            // 흰 이름표 (별 위)
            val label = p.name.take(14)
            val w = txt.measureText(label) + 12 * d
            val ty = y - stick - R * 1.8f - 4 * d
            fill.color = Color.WHITE; c.drawRoundRect(x - w / 2, ty - 16 * d, x + w / 2, ty, 8 * d, 8 * d, fill)
            txt.color = Color.parseColor("#14232E"); c.drawText(label, x, ty - 4.5f * d, txt)
            if (!draftOne) spots += p to android.graphics.PointF(x, y - stick - R * 0.8f)
        }

        override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
            if (shadow) return
            spots.clear()
            pins.filter { it.id != draft?.id }.forEach { drawPin(c, map, it, false) }
            draft?.let { drawPin(c, map, it, true) }
        }

        override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
            if (draft != null) return false
            val hit = spots.minByOrNull { (_, s) -> (s.x - e.x) * (s.x - e.x) + (s.y - e.y) * (s.y - e.y) } ?: return false
            val dx = hit.second.x - e.x; val dy = hit.second.y - e.y
            if (dx * dx + dy * dy > (26 * d) * (26 * d)) return false
            onTap(hit.first); return true
        }

        override fun onLongPress(e: MotionEvent, mapView: MapView): Boolean {
            if (!isEnabled || draft != null) return false
            val g = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
            onLong(g); return true
        }
    }

    /** 핀 만들기 · 고치기 창 (밑에서 올라옴 · 움직이면 지도 원도 같이) */
    fun edit(a: Activity, map: MapView, ov: PinOverlay, at: GeoPoint, old: Pin?, after: () -> Unit) {
        if (Cloud.uid == null) { Ui.toast(a, "로그인하면 포인트를 꽂을 수 있어요"); return }
        val dp = { v: Int -> Ui.dp(a, v) }
        var scope = old?.scope ?: MINE
        var gid = old?.gid?.ifBlank { null } ?: Cloud.active?.id ?: ""
        var r = old?.r ?: 300.0
        val sp = (old?.sp ?: emptyList()).toMutableList()
        fun draftNow(name: String) = Pin(old?.id ?: "_draft", scope, gid, name.ifBlank { "새 포인트" }, "", at.latitude, at.longitude, r, sp, "", "", 0L)
        ov.draft = draftNow(old?.name ?: ""); map.invalidate()
        val sh = Sheet(a, 0.72f)
        sh.head.addView(Ui.text(a, 17f, Ui.TEXT, true, if (old == null) "📌 포인트 꽂기" else "✎ 포인트 고치기"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        fun label(t: String) = Ui.text(a, 12.5f, Ui.SUB, true, t)
        fun field(hint: String, v: String, max: Int, lines: Int) = EditText(a).apply {
            setText(v); this.hint = hint; setTextColor(Ui.TEXT); setHintTextColor(Ui.SUB); textSize = 15f
            filters = arrayOf(android.text.InputFilter.LengthFilter(max))
            if (lines > 1) { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 2; maxLines = lines } else isSingleLine = true
            background = Ui.bg(a, Color.parseColor("#0F2231"), 12, Ui.LINE); setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val nameE = field("이름 (예: 오천항 쭈 포인트)", old?.name ?: "", 30, 1)
        val memoE = field("설명 (예: 물 빠질 때 · 바닥 뻘 · 수심 15m)", old?.memo ?: "", 200, 4)
        nameE.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { ov.draft = draftNow(s.toString()); map.invalidate() }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        sh.body.addView(label("이름"), Ui.lp(a, -1, -2, top = 4)); sh.body.addView(nameE, Ui.lp(a, -1, -2, top = 4))
        sh.body.addView(label("설명"), Ui.lp(a, -1, -2, top = 10)); sh.body.addView(memoE, Ui.lp(a, -1, -2, top = 4))
        // 반경 50m ~ 3km (칸: 50 · 100 · 200 · 300 · 500 · 700 · 1km · 1.5 · 2 · 3)
        val steps = doubleArrayOf(50.0, 100.0, 200.0, 300.0, 500.0, 700.0, 1000.0, 1500.0, 2000.0, 3000.0)
        val rTxt = label("반경 · ${rLabel(r)}")
        sh.body.addView(rTxt, Ui.lp(a, -1, -2, top = 12))
        sh.body.addView(SeekBar(a).apply {
            max = steps.size - 1; progress = steps.indices.minByOrNull { kotlin.math.abs(steps[it] - r) } ?: 3
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { r = steps[v]; rTxt.text = "반경 · ${rLabel(r)}"; ov.draft = draftNow(nameE.text.toString()); map.invalidate() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }, Ui.lp(a, -1, -2, top = 2))
        // 어종 (선택 · 여러 개)
        sh.body.addView(label("어종 (골라도 되고 안 골라도 돼요)"), Ui.lp(a, -1, -2, top = 10))
        val spWrap = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        val spNames = (Store.history.flatMap { t -> t.slots().filter { t.count(it) > 0 }.map { t.name(it) } } + sp)
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.distinct().take(12)
            .ifEmpty { listOf("주꾸미", "갑오징어", "우럭", "광어", "농어", "낙지") }
        fun paintSp() {
            spWrap.removeAllViews()
            spNames.forEach { n ->
                val on = n in sp
                spWrap.addView(Ui.pill(a, n, on, Ui.GREEN, small = true) {
                    if (n in sp) sp.remove(n) else if (sp.size < 10) sp.add(n); paintSp()
                }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
            }
        }
        paintSp()
        sh.body.addView(Ui.hscroll(a, spWrap), Ui.lp(a, -1, -2, top = 4))
        // 누구에게
        sh.body.addView(label("누구에게 보일까요?"), Ui.lp(a, -1, -2, top = 12))
        val scWrap = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        val scLp = { LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) } }
        fun paintScope() {
            scWrap.removeAllViews()
            scWrap.addView(Ui.pill(a, "🔒 나만", scope == MINE, Ui.MINE, small = true) { scope = MINE; paintScope(); ov.draft = draftNow(nameE.text.toString()); map.invalidate() }, scLp())
            if (Cloud.groups.isNotEmpty()) {
                val gt = Cloud.groups.firstOrNull { it.id == gid }?.title ?: Cloud.groups.first().title
                scWrap.addView(Ui.pill(a, "👥 $gt ▾", scope == GROUP, Ui.GROUP, small = true) {
                    if (scope == GROUP && Cloud.groups.size > 1) {
                        AlertDialog.Builder(a, R.style.FlAlert).setTitle("어느 그룹에 보일까요?")
                            .setItems(Cloud.groups.map { it.title }.toTypedArray()) { _, w -> gid = Cloud.groups[w].id; paintScope() }
                            .setNegativeButton("닫기", null).show()
                    }
                    scope = GROUP; if (Cloud.groups.none { it.id == gid }) gid = Cloud.groups.first().id
                    paintScope(); ov.draft = draftNow(nameE.text.toString()); map.invalidate()
                }, scLp())
            }
            scWrap.addView(Ui.pill(a, "🌐 전체 게시판", scope == ALL, Ui.ALL, small = true) { scope = ALL; paintScope(); ov.draft = draftNow(nameE.text.toString()); map.invalidate() }, scLp())
        }
        paintScope()
        sh.body.addView(Ui.hscroll(a, scWrap), Ui.lp(a, -1, -2, top = 4))
        sh.body.addView(Ui.text(a, 11.5f, Ui.SUB, false, "그룹 · 전체 포인트는 다른 사람도 봐요. 전체 게시판 포인트는 누구나 볼 수 있으니 개인 정보는 적지 마세요."), Ui.lp(a, -1, -2, top = 6))
        var saved = false
        sh.foot.visibility = android.view.View.VISIBLE
        sh.foot.addView(Ui.text(a, 15f, Ui.BG, true, "💾 저장").apply {
            gravity = Gravity.CENTER; minHeight = dp(48); background = Ui.bg(a, Ui.GREEN, 99)
            setOnClickListener {
                val nm = nameE.text.toString().trim()
                if (nm.isEmpty()) { Ui.toast(a, "이름을 적어 주세요"); return@setOnClickListener }
                if (Cloud.fakeAdmin(nm)) { Ui.toast(a, "관리자 · 운영자 같은 이름은 쓸 수 없어요"); return@setOnClickListener }
                isEnabled = false; text = "저장 중…"
                val p = Pin(old?.id ?: "", scope, if (scope == GROUP) gid else "", Profanity.mask(nm), Profanity.mask(memoE.text.toString().trim()),
                    at.latitude, at.longitude, r, sp.toList(), "", "", 0L)
                save(p, old) { err ->
                    if (err != null) { Ui.toast(a, err); isEnabled = true; text = "💾 저장"; return@save }
                    saved = true; Ui.toast(a, "📌 포인트를 저장했어요"); sh.close()
                }
            }
        }, Ui.lp(a, -1, -2))
        sh.onClose = { ov.draft = null; map.invalidate(); if (saved) after() }
        sh.show()
    }

    /** 핀 누르면: 설명 카드 + 이 범위 안 지난 조과 */
    fun card(a: Activity, map: MapView, ov: PinOverlay, p: Pin, extraDots: List<MapDot>, after: () -> Unit) {
        val dp = { v: Int -> Ui.dp(a, v) }
        val sh = Sheet(a, 0.6f)
        sh.head.addView(Ui.text(a, 17f, Ui.TEXT, true).apply {
            val sb = android.text.SpannableStringBuilder()
            val st = sb.length; sb.append("★ "); sb.setSpan(android.text.style.ForegroundColorSpan(color(p.scope)), st, sb.length, 0)
            sb.append(p.name); text = sb; maxLines = 2
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val df = SimpleDateFormat("yyyy.M.d", Locale.KOREA)
        sh.body.addView(Ui.text(a, 12.5f, color(p.scope), true,
            scopeLabel(p) + " · 반경 ${rLabel(p.r)}" + (if (p.ownerName.isNotBlank()) " · ${p.ownerName}" else "") + " · ${df.format(Date(p.t))}"), Ui.lp(a, -1, -2))
        if (p.memo.isNotBlank()) sh.body.addView(Ui.text(a, 14.5f, Ui.TEXT, false, p.memo), Ui.lp(a, -1, -2, top = 8))
        if (p.sp.isNotEmpty()) sh.body.addView(Ui.text(a, 13f, Ui.SUB, false, "🐟 " + p.sp.joinToString(" · ")), Ui.lp(a, -1, -2, top = 6))
        // 이 범위 안 지난 조과 (내 기록 + 지도에 받아 둔 지난 기록)
        val mine = Analysis.mine("", 0L).filter { distM(p.lat, p.lon, it.lat, it.lon) <= p.r }
        val others = extraDots.filter { it.src != 0 && distM(p.lat, p.lon, it.point.latitude, it.point.longitude) <= p.r }
        val trips = mine.map { it.trip }.distinct().size + others.map { it.trip.ifBlank { it.who + it.time / 86_400_000L } }.distinct().size
        val bySp = (mine.map { it.species } + others.map { it.species.ifBlank { it.kind } }).groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        val (cBox, cIn) = Ui.panel(a, "📊 이 범위 안 지난 조과", "내 기록 + 지도 '지난 기록'에 받아 둔 것")
        cIn.addView(Ui.text(a, 14f, Ui.TEXT, true,
            if (bySp.isEmpty()) "아직 이 범위 안 조과가 없어요" else "출조 ${trips}번 · " + bySp.take(6).joinToString(" · ") { "${it.key} ${it.value}" }), Ui.lp(a, -1, -2, top = 4))
        sh.body.addView(cBox, Ui.lp(a, -1, -2, top = 12))
        fun btn(t: String, col: Int = Ui.TEXT, click: () -> Unit) = Ui.text(a, 13.5f, col, true, t).apply {
            gravity = Gravity.CENTER; minHeight = dp(Ui.PILL_M); setPadding(dp(10), 0, dp(10), 0)
            background = Ui.bg(a, android.graphics.Color.TRANSPARENT, 99, Ui.LINE); setOnClickListener { click() }
        }
        val row1 = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        row1.addView(btn("🌊 물때 · 날씨") { sh.close(); TideDialog.show(a, p.lat, p.lon, System.currentTimeMillis(), null) }, LinearLayout.LayoutParams(0, -2, 1f))
        row1.addView(btn("📊 원 분석") {
            if (mine.isEmpty()) { Ui.toast(a, "이 범위 안 내 조과가 없어요"); return@btn }
            sh.close(); PointMap.showAnalysis(a, mine, p.name, false, true)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        sh.body.addView(row1, Ui.lp(a, -1, -2, top = 12))
        val row2 = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        if (canEdit(p)) row2.addView(btn("✎ 고치기 · 공유 바꾸기") { sh.close(); edit(a, map, ov, p.point, p, after) }, LinearLayout.LayoutParams(0, -2, 1f))
        if (canDelete(p)) row2.addView(btn("🗑 지우기", Color.parseColor("#FF8A80")) {
            AlertDialog.Builder(a, R.style.FlAlert).setTitle("'${p.name}' 포인트를 지울까요?")
                .setPositiveButton("지우기") { _, _ -> delete(p) { e -> if (e != null) Ui.toast(a, e) else { sh.close(); after() } } }
                .setNegativeButton("취소", null).show()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = if (row2.childCount > 0) dp(8) else 0 })
        if (p.scope == ALL && p.owner != Cloud.uid && p.owner.isNotBlank()) row2.addView(btn("🚫 신고 · 차단", Color.parseColor("#FF8A80")) {
            AlertDialog.Builder(a, R.style.FlAlert).setTitle("이 사람을 차단할까요?")
                .setMessage("차단하면 신고도 같이 돼요. 이 사람 포인트 · 글이 안 보여요.")
                .setPositiveButton("차단") { _, _ -> Moderation.block(p.owner, p.ownerName.ifBlank { "포인트" }) { e -> Ui.toast(a, e ?: "차단했어요"); sh.close(); after() } }
                .setNegativeButton("취소", null).show()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = if (row2.childCount > 0) dp(8) else 0 })
        if (row2.childCount > 0) sh.body.addView(row2, Ui.lp(a, -1, -2, top = 8))
        sh.show()
    }
}
