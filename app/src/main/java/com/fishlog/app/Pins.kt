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
 * v6.68 (360): 📌 지도 포인트 핀 — [📌 포인트] 누르면 가운데 핀 → 끌어 옮기고 [💾 저장] (v6.72 · 꾹 누르기 없앰) · 이름 · 설명 · 반경(50m~5km) · 어종
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
                // v6.80 (384): 여러 그룹에 같이 올린 핀은 한 번만
                if (left == 0) done(jobs.flatMap { out[it.first].orEmpty() }.distinctBy { if (it.scope == GROUP) "g:" + it.id else "${it.scope}:${it.id}" })
            }
        }
    }

    /** v6.74 (362 · 372): 한 그룹의 포인트만 (그룹 지도 · 새 소식) */
    fun loadGroup(gid: String, done: (List<Pin>?) -> Unit) {
        if (Cloud.uid == null) return done(null)
        db.collection("groups").document(gid).collection("pins").limit(300).get()
            .addOnSuccessListener { s -> done(s.documents.mapNotNull { parse(it, GROUP, gid) }.filter { !Moderation.isBlocked(it.owner) }) }
            .addOnFailureListener { done(null) }
    }

    /**
     * v6.80 (384): 그룹 여러 개 — 고른 그룹마다 같은 id 로 한 장씩 (규칙 안 바꿈) · 고칠 때 빠진 그룹 것은 지움
     *  gids 가 비어 있으면 예전처럼 p.gid 하나
     */
    fun saveMulti(p: Pin, old: Pin?, gids: Set<String>, done: (String?) -> Unit) {
        val me = Cloud.uid ?: return done("로그인이 필요해요")
        if (p.scope != GROUP || gids.size <= 1 && (old == null || old.scope != GROUP)) {
            return save(Pin(p.id, p.scope, if (p.scope == GROUP) (gids.firstOrNull() ?: p.gid) else "", p.name, p.memo, p.lat, p.lon, p.r, p.sp, p.owner, p.ownerName, p.t), old, done)
        }
        if (old != null && old.owner != me && (gids.size > 1 || old.scope != GROUP)) return done("남의 핀은 공유 범위를 못 바꿔요")
        val m = hashMapOf<String, Any>("name" to p.name.take(30), "memo" to p.memo.take(200), "lat" to p.lat, "lon" to p.lon,
            "r" to p.r.coerceIn(50.0, 5000.0), "sp" to p.sp.take(10), "owner" to (old?.owner ?: me),
            "ownerName" to (old?.ownerName ?: Cloud.displayName.take(30)), "t" to (old?.t ?: System.currentTimeMillis()))
        if (old != null) m["edited"] = System.currentTimeMillis()
        val id = old?.id ?: db.collection("pins").document().id
        val tasks = ArrayList<com.google.android.gms.tasks.Task<Void>>()
        gids.forEach { g -> tasks.add(ref(GROUP, g).document(id).set(m)) }
        com.google.android.gms.tasks.Tasks.whenAllComplete(tasks).addOnCompleteListener {
            val okN = tasks.count { it.isSuccessful }
            if (okN == 0) { done("저장 못 했어요"); return@addOnCompleteListener }
            if (old != null) {
                // 예전 범위가 나만 · 전체였으면 지움 · 그룹이었으면 빠진 그룹 것 지움 (없으면 그냥 실패)
                if (old.scope != GROUP) ref(old.scope, old.gid).document(old.id).delete()
                else if (old.owner == me) Cloud.groups.filter { it.id !in gids }.forEach { g -> ref(GROUP, g.id).document(id).delete() }
            }
            done(if (okN < tasks.size) "일부 그룹엔 못 올렸어요 (${okN}/${tasks.size})" else null)
        }
    }
    /** v6.80 (384): 이 핀(같은 id)이 올라가 있는 내 그룹들 */
    fun groupsHaving(id: String, done: (Set<String>) -> Unit) {
        val gs = Cloud.groups; if (gs.isEmpty() || id.isBlank()) return done(emptySet())
        val out = java.util.Collections.synchronizedSet(HashSet<String>()); var left = gs.size
        gs.forEach { g -> ref(GROUP, g.id).document(id).get().addOnSuccessListener { if (it.exists()) out.add(g.id) }
            .addOnCompleteListener { if (--left == 0) done(HashSet(out)) } }
    }

    fun save(p: Pin, old: Pin?, done: (String?) -> Unit) {
        val me = Cloud.uid ?: return done("로그인이 필요해요")
        val m = hashMapOf<String, Any>("name" to p.name.take(30), "memo" to p.memo.take(200), "lat" to p.lat, "lon" to p.lon,
            "r" to p.r.coerceIn(50.0, 5000.0), "sp" to p.sp.take(10), "owner" to (old?.owner ?: me),
            "ownerName" to (old?.ownerName ?: Cloud.displayName.take(30)), "t" to (old?.t ?: System.currentTimeMillis()))
        if (old != null) m["edited"] = System.currentTimeMillis()
        val moved = old != null && (old.scope != p.scope || old.gid != p.gid)
        val target = if (old != null && !moved) ref(p.scope, p.gid).document(old.id) else ref(p.scope, p.gid).document()
        if (moved && old!!.owner != me) return done("남의 핀은 공유 범위를 못 바꿔요")
        target.set(m).addOnSuccessListener {
            if (moved) ref(old!!.scope, old.gid).document(old.id).delete()
            done(null)
        }.addOnFailureListener { done(if (it.message?.contains("PERMISSION") == true) (if (p.r > 3000) "3km 넘는 반경은 서버 규칙 게시 뒤에 저장돼요 · 3km 이하로 줄여 저장해 주세요" else "저장 못 했어요 (서버 규칙 업데이트 전일 수 있어요)") else it.message ?: "저장 못 했어요") }
    }

    fun delete(p: Pin, done: (String?) -> Unit) {
        // v6.80 (384): 내 그룹 포인트는 다른 그룹에 같이 올린 것도 같이 지움
        if (p.scope == GROUP && p.owner == Cloud.uid) Cloud.groups.filter { it.id != p.gid }.forEach { g -> ref(GROUP, g.id).document(p.id).delete() }
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

    /** 지도 위 핀 · 반경 원 · 이름표 · v6.72: 꽂는 중인 핀은 손가락으로 끌어 옮기고 위 [💾 저장] [✕ 취소] */
    class PinOverlay(ctx: Context, private val onTap: (Pin) -> Unit) : Overlay() {
        var pins: List<Pin> = emptyList()
        /** 만들거나 고치는 중인 핀 (반경 미리보기) */
        var draft: Pin? = null
        /** 꽂는 중(끌어 옮기기 · 저장/취소 버튼 보임) — 저장 창이 떠 있으면 false */
        var placing = false
        var onSave: (() -> Unit)? = null
        var onCancel: (() -> Unit)? = null
        private val saveR = android.graphics.RectF(); private val cancelR = android.graphics.RectF()
        private var dragging = false; private var grabDx = 0f; private var grabDy = 0f
        // v6.73: 원 안에서 두 손가락 벌리기/좁히기 = 반경 (원 밖 = 지도 확대/축소)
        private var resizing = false; private var span0 = 1f; private var r0 = 300.0
        private fun span(e: MotionEvent): Float { val dx = e.getX(0) - e.getX(1); val dy = e.getY(0) - e.getY(1); return kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f) }
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
            val dp0 = draft
            if (placing && dp0 != null) {
                // 핀 위 [💾 저장] [✕ 취소] (핀을 따라 다님)
                val pt = map.projection.toPixels(dp0.point, null)
                val x = pt.x.toFloat(); val top = pt.y.toFloat() - 12 * d - 13 * d * 1.8f - 30 * d
                txt.textSize = 14f * d
                val w1 = txt.measureText("💾 저장") + 26 * d; val w2 = txt.measureText("✕ 취소") + 26 * d; val h = 36 * d; val gap = 8 * d
                val left = x - (w1 + gap + w2) / 2
                saveR.set(left, top - h, left + w1, top); cancelR.set(left + w1 + gap, top - h, left + w1 + gap + w2, top)
                fill.color = Color.argb(90, 0, 0, 0)
                c.drawRoundRect(saveR.left, saveR.top + 3 * d, saveR.right, saveR.bottom + 3 * d, h / 2, h / 2, fill)
                c.drawRoundRect(cancelR.left, cancelR.top + 3 * d, cancelR.right, cancelR.bottom + 3 * d, h / 2, h / 2, fill)
                fill.color = Color.parseColor("#5FD39A"); c.drawRoundRect(saveR, h / 2, h / 2, fill)
                fill.color = Color.WHITE; c.drawRoundRect(cancelR, h / 2, h / 2, fill)
                txt.color = Color.parseColor("#04222A"); c.drawText("💾 저장", saveR.centerX(), saveR.centerY() + 5 * d, txt)
                txt.color = Color.parseColor("#14232E"); c.drawText("✕ 취소", cancelR.centerX(), cancelR.centerY() + 5 * d, txt)
                // 반경 + 안내 (핀 아래)
                val hint = "반경 ${rLabel(dp0.r)} · 원 안에서 두 손가락으로 크기"
                txt.textSize = 12f * d
                val hw = txt.measureText(hint) + 20 * d; val hy = pt.y.toFloat() + 22 * d
                fill.color = Color.argb(215, 10, 24, 34); c.drawRoundRect(x - hw / 2, hy, x + hw / 2, hy + 26 * d, 13 * d, 13 * d, fill)
                txt.color = if (resizing) Color.parseColor("#FFD34D") else Color.WHITE
                c.drawText(hint, x, hy + 17.5f * d, txt)
                txt.textSize = 11f * d
            }
        }

        /** 꽂는 중인 핀을 손가락으로 끌기 (핀 근처를 잡았을 때만 · 아니면 지도가 움직임) */
        override fun onTouchEvent(e: MotionEvent, map: MapView): Boolean {
            val dp0 = draft
            if (!placing || dp0 == null) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                    val pt = map.projection.toPixels(dp0.point, null)
                    val rr = maxOf(map.projection.metersToPixels(dp0.r.toFloat()), 60 * d)
                    val mx = (e.getX(0) + e.getX(1)) / 2 - pt.x; val my = (e.getY(0) + e.getY(1)) / 2 - pt.y
                    if (mx * mx + my * my <= rr * rr) {
                        dragging = false; resizing = true; span0 = span(e); r0 = dp0.r; map.invalidate(); return true
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> if (resizing) { resizing = false; map.invalidate(); return true }
                MotionEvent.ACTION_DOWN -> {
                    resizing = false
                    val pt = map.projection.toPixels(dp0.point, null)
                    val sx = pt.x.toFloat(); val sy = pt.y.toFloat() - 12 * d - 13 * d * 0.8f
                    val near = (e.x - sx) * (e.x - sx) + (e.y - sy) * (e.y - sy) < (40 * d) * (40 * d) ||
                        (e.x - pt.x) * (e.x - pt.x) + (e.y - pt.y) * (e.y - pt.y) < (30 * d) * (30 * d)
                    if (near && !saveR.contains(e.x, e.y) && !cancelR.contains(e.x, e.y)) {
                        dragging = true; grabDx = pt.x - e.x; grabDy = pt.y - e.y; return true
                    }
                }
                MotionEvent.ACTION_MOVE -> if (resizing && e.pointerCount >= 2) {
                    val nr = (r0 * span(e) / span0).coerceIn(50.0, 5000.0)
                    val rounded = if (nr < 1000) Math.round(nr / 10.0) * 10.0 else Math.round(nr / 50.0) * 50.0
                    draft = Pin(dp0.id, dp0.scope, dp0.gid, dp0.name, dp0.memo, dp0.lat, dp0.lon, rounded, dp0.sp, dp0.owner, dp0.ownerName, dp0.t)
                    map.invalidate(); return true
                } else if (resizing) return true
                else if (dragging) {
                    val g = map.projection.fromPixels((e.x + grabDx).toInt(), (e.y + grabDy).toInt()) as GeoPoint
                    draft = Pin(dp0.id, dp0.scope, dp0.gid, dp0.name, dp0.memo, g.latitude, g.longitude, dp0.r, dp0.sp, dp0.owner, dp0.ownerName, dp0.t)
                    map.invalidate(); return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (resizing) { resizing = false; map.invalidate(); return false }   // 지도도 손가락 뗀 것 알게
                    if (dragging) { dragging = false; return true }
                }
            }
            return false
        }

        override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
            if (placing && draft != null) {
                if (saveR.contains(e.x, e.y)) { onSave?.invoke(); return true }
                if (cancelR.contains(e.x, e.y)) { onCancel?.invoke(); return true }
                return false
            }
            if (draft != null) return false
            val hit = spots.minByOrNull { (_, s) -> (s.x - e.x) * (s.x - e.x) + (s.y - e.y) * (s.y - e.y) } ?: return false
            val dx = hit.second.x - e.x; val dy = hit.second.y - e.y
            if (dx * dx + dy * dy > (26 * d) * (26 * d)) return false
            onTap(hit.first); return true
        }

    }

    /** v6.72: 지도 가운데(또는 고칠 핀 자리)에 핀 → 끌어서 옮기기 → 핀 위 [💾 저장] 누르면 저장 창 */
    fun place(a: Activity, map: MapView, ov: PinOverlay, old: Pin?, scope0: Int = MINE, gid0: String = "", after: () -> Unit) {
        if (Cloud.uid == null) { Ui.toast(a, "로그인하면 포인트를 꽂을 수 있어요"); return }
        val at = old?.point ?: (map.mapCenter as GeoPoint)
        // v6.74 (372): 그룹 지도에서 꽂으면 처음부터 그 그룹 공유
        ov.draft = Pin(old?.id ?: "_draft", old?.scope ?: scope0, old?.gid ?: gid0, old?.name ?: "새 포인트", old?.memo ?: "",
            at.latitude, at.longitude, old?.r ?: 300.0, old?.sp ?: emptyList(), old?.owner ?: "", old?.ownerName ?: "", old?.t ?: 0L)
        ov.placing = true
        if (old != null) map.controller.animateTo(at)
        ov.onCancel = { ov.placing = false; ov.draft = null; map.invalidate() }
        ov.onSave = {
            val d = ov.draft
            if (d != null) { ov.placing = false; map.invalidate(); edit(a, map, ov, d.point, old, after, d) }
        }
        map.invalidate()
        Ui.toast(a, "핀을 끌어서 원하는 자리로 옮기고 위 [💾 저장]을 눌러요")
    }

    /** 핀 만들기 · 고치기 창 (밑에서 올라옴 · 움직이면 지도 원도 같이) */
    fun edit(a: Activity, map: MapView, ov: PinOverlay, at: GeoPoint, old: Pin?, after: () -> Unit, from: Pin? = null) {
        if (Cloud.uid == null) { Ui.toast(a, "로그인하면 포인트를 꽂을 수 있어요"); return }
        val dp = { v: Int -> Ui.dp(a, v) }
        var scope = from?.scope ?: old?.scope ?: MINE
        var gid = (from?.gid ?: old?.gid)?.ifBlank { null } ?: Cloud.active?.id ?: ""
        val gids = LinkedHashSet<String>().apply { if (gid.isNotBlank()) add(gid) }   // v6.80 (384) 여러 그룹
        var r = from?.r ?: old?.r ?: 300.0
        val sp = (from?.sp ?: old?.sp ?: emptyList()).toMutableList()
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
        // 반경 50m ~ 5km (칸: 50 · 100 · 200 · 300 · 500 · 700 · 1km · 1.5 · 2 · 3 · 4 · 5 · v6.91 406)
        val steps = doubleArrayOf(50.0, 100.0, 200.0, 300.0, 500.0, 700.0, 1000.0, 1500.0, 2000.0, 3000.0, 4000.0, 5000.0)
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
        var openSpecies: () -> Unit = {}
        // v6.73: 자주 잡은 어종 + 고른 것 줄 · '＋ 어종 더' = 전체 목록(찾기 · 직접 입력)
        val mine = Store.history.flatMap { t -> t.slots().filter { t.count(it) > 0 }.map { t.name(it) } }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        fun allNames(): List<String> = (mine + Store.CATS.flatMap { Store.species(it) } + SpeciesFix.COMMON + sp)
            .map { it.trim() }.filter { it.isNotBlank() && !it.endsWith("번") }.distinct()
        fun paintSp() {
            spWrap.removeAllViews()
            val quick = (sp + mine.take(8)).ifEmpty { listOf("주꾸미", "갑오징어", "우럭", "광어", "농어", "낙지") }.distinct()
            quick.forEach { n ->
                val on = n in sp
                spWrap.addView(Ui.pill(a, (if (on) "✓ " else "") + n, on, Ui.GREEN, small = true) {
                    if (n in sp) sp.remove(n) else if (sp.size < 10) sp.add(n); paintSp()
                }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
            }
            spWrap.addView(Ui.pill(a, "＋ 어종 더 ▾", false, Ui.GREEN, small = true) { openSpecies() },
                LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        }
        openSpecies = {
            val ss = Sheet(a, 0.75f)
            ss.head.addView(Ui.text(a, 17f, Ui.TEXT, true, "🐟 어종 고르기 (10개까지)"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val q = EditText(a).apply {
                hint = "찾기 · 없으면 그대로 직접 추가"; isSingleLine = true; textSize = 15f
                setTextColor(Ui.TEXT); setHintTextColor(Ui.SUB); filters = arrayOf(android.text.InputFilter.LengthFilter(12))
                background = Ui.bg(a, Color.parseColor("#0F2231"), 12, Ui.LINE); setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            ss.body.addView(q, Ui.lp(a, -1, -2))
            val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            ss.body.addView(grid, Ui.lp(a, -1, -2, top = 6))
            fun paintGrid() {
                grid.removeAllViews()
                val key = q.text.toString().trim()
                val names = allNames().filter { key.isEmpty() || it.contains(key) }
                val views = mutableListOf<android.view.View>()
                if (key.isNotEmpty() && key !in names) views += Ui.pill(a, "＋ '$key' 추가", false, Ui.GREEN, small = true) {
                    SpeciesFix.check(a, key) { n -> if (n !in sp && sp.size < 10) sp.add(n); q.setText(""); paintSp(); paintGrid() }
                }
                names.forEach { n ->
                    val on = n in sp
                    views += Ui.pill(a, (if (on) "✓ " else "") + n, on, Ui.GREEN, small = true) {
                        if (n in sp) sp.remove(n) else if (sp.size < 10) sp.add(n) else Ui.toast(a, "10개까지 고를 수 있어요")
                        paintSp(); paintGrid()
                    }
                }
                var row: LinearLayout? = null; var k = 0
                views.forEach { v ->
                    if (row == null || k == 3) { row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }; grid.addView(row, Ui.lp(a, -1, -2, top = 6)); k = 0 }
                    row!!.addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) }); k++
                }
                while (k in 1..2) { row!!.addView(android.view.View(a), LinearLayout.LayoutParams(0, 1, 1f).apply { rightMargin = dp(6) }); k++ }
                if (views.isEmpty()) grid.addView(Ui.text(a, 13f, Ui.SUB, false, "어종 이름을 적어 보세요"))
            }
            q.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { paintGrid() }
                override fun beforeTextChanged(x: CharSequence?, st: Int, c: Int, af: Int) {}
                override fun onTextChanged(x: CharSequence?, st: Int, b: Int, c: Int) {}
            })
            paintGrid()
            ss.body.addView(Ui.pill(a, "✓ 다 골랐어요", true, Ui.GREEN) { ss.close() }, Ui.lp(a, -1, -2, top = 14))
            ss.show()
        }
        sh.body.addView(Ui.hscroll(a, spWrap), Ui.lp(a, -1, -2, top = 4))
        paintSp()   // v6.85 (391): 처음부터 어종 칩 보이게
        // 누구에게
        sh.body.addView(label("누구에게 보일까요?"), Ui.lp(a, -1, -2, top = 12))
        val scWrap = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        val scLp = { LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) } }
        fun paintScope() {
            scWrap.removeAllViews()
            scWrap.addView(Ui.pill(a, "🔒 나만", scope == MINE, Ui.MINE, small = true) { scope = MINE; paintScope(); ov.draft = draftNow(nameE.text.toString()); map.invalidate() }, scLp())
            if (Cloud.groups.isNotEmpty()) {
                gids.retainAll(Cloud.groups.map { it.id }.toSet())
                val first = Cloud.groups.firstOrNull { it.id in gids }?.title ?: Cloud.groups.first().title
                val gt = if (gids.size > 1) "$first 외 ${gids.size - 1}" else first
                scWrap.addView(Ui.pill(a, "👥 $gt ▾", scope == GROUP, Ui.GROUP, small = true) {
                    if (scope == GROUP && Cloud.groups.size > 1) {
                        val dupe = Cloud.groups.groupingBy { it.title }.eachCount()
                        val names = Cloud.groups.map { g -> if ((dupe[g.title] ?: 0) > 1) g.label() else g.title }.toTypedArray()
                        val checked = Cloud.groups.map { it.id in gids }.toBooleanArray()
                        AlertDialog.Builder(a, R.style.FlAlert).setTitle("어느 그룹에 보일까요? (여러 개)")
                            .setMultiChoiceItems(names, checked) { _, w, on -> checked[w] = on }
                            .setPositiveButton("확인") { _, _ ->
                                val pick = Cloud.groups.filterIndexed { i, _ -> checked[i] }.map { it.id }
                                if (pick.isEmpty()) Ui.toast(a, "그룹을 하나 이상 골라 주세요")
                                else { gids.clear(); gids.addAll(pick); gid = pick.first(); paintScope() }
                            }
                            .setNegativeButton("닫기", null).show()
                    }
                    scope = GROUP; if (Cloud.groups.none { it.id == gid }) gid = Cloud.groups.first().id
                    if (gids.isEmpty()) gids.add(gid)
                    paintScope(); ov.draft = draftNow(nameE.text.toString()); map.invalidate()
                }, scLp())
            }
            scWrap.addView(Ui.pill(a, "🌐 전체 게시판", scope == ALL, Ui.ALL, small = true) { scope = ALL; paintScope(); ov.draft = draftNow(nameE.text.toString()); map.invalidate() }, scLp())
        }
        paintScope()
        // 고칠 때: 이 핀이 올라가 있는 그룹들을 미리 체크
        if (old != null && old.scope == GROUP) groupsHaving(old.id) { have -> if (have.isNotEmpty()) a.runOnUiThread { gids.clear(); gids.addAll(have); gid = have.first(); paintScope() } }
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
                saveMulti(p, old, if (scope == GROUP) gids else emptySet()) { err ->
                    if (err != null) { Ui.toast(a, err); isEnabled = true; text = "💾 저장"; return@saveMulti }
                    saved = true; Ui.toast(a, "📌 포인트를 저장했어요"); sh.close()
                }
            }
        }, Ui.lp(a, -1, -2))
        // v6.72: 저장 안 하고 닫으면 다시 꽂는 중으로 (핀 옮기거나 취소)
        sh.onClose = {
            if (saved) { ov.placing = false; ov.draft = null; map.invalidate(); after() }
            else if (from != null) { ov.draft = draftNow(nameE.text.toString()); ov.placing = true; map.invalidate() }
            else { ov.draft = null; map.invalidate() }
        }
        sh.show()
    }

    /** 핀 누르면: 설명 카드 + 이 범위 안 지난 조과 */
    fun card(a: Activity, map: MapView, ov: PinOverlay, p: Pin, extraDots: List<MapDot>, after: () -> Unit) {
        val dp = { v: Int -> Ui.dp(a, v) }
        if (p.scope == GROUP) GroupNews.markPinSeen(p.gid, p.id)   // v6.74 (362): 열어 보면 새 소식에서 빠짐
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
        if (canEdit(p)) row2.addView(btn("✎ 고치기 · 옮기기 · 공유") { sh.close(); place(a, map, ov, p, after = after) }, LinearLayout.LayoutParams(0, -2, 1f))
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
