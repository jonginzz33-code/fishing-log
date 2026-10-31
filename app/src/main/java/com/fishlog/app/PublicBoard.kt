package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor

/**
 * 전체 게시판 (앱 쓰는 모든 사람이 보는 조황)
 *  - 출조마다 올리는 사람이 직접 "전체 공개"를 골랐을 때만 올라감
 *  - 경로·배 이름은 안 올림
 *  - 위치 공개 방법을 올리는 사람이 고름
 *      정확(exact) : 잡은 자리 그대로 (xa, xo) + 150m 칸 (포인트 도감 계산용)
 *      대략(approx): 약 1km 칸의 가운데만 (지도엔 반경 500m 원) (정확한 자리는 저장 안 함)
 *      예전 글(prec 없음): 150m 칸의 가운데만
 *  - 사진은 따로 골랐을 때만 작은 사진으로
 */
object PublicBoard {

    private val db get() = FirebaseFirestore.getInstance()
    private val main = Handler(Looper.getMainLooper())

    /** 위치 칸 크기 (m) */
    const val CELL_M = 150.0
    /** 대략 공개 칸 크기 (m) — 지도엔 반경 500m 원 */
    const val APPROX_M = 1000.0

    /** r = 지도에 그릴 원 반지름(m) */
    class Cell(val key: String, val lat: Double, val lon: Double, val r: Double = CELL_M / 2)

    private fun grid(lat: Double, lon: Double, size: Double, prefix: String): Cell {
        val latStep = size / 111_320.0
        val i = floor(lat / latStep).toLong()
        val centerLat = (i + 0.5) * latStep
        val lonStep = size / (111_320.0 * cos(Math.toRadians(centerLat)))
        val j = floor(lon / lonStep).toLong()
        return Cell("$prefix${i}_$j", centerLat, (j + 0.5) * lonStep, size / 2)
    }

    /** 정확한 위치 → 150m 칸 (가운데 좌표) */
    fun cellOf(lat: Double, lon: Double): Cell = grid(lat, lon, CELL_M, "")

    /** 정확한 위치 → 약 1km 칸 (대략 공개) */
    fun approxCellOf(lat: Double, lon: Double): Cell = grid(lat, lon, APPROX_M, "a")

    /** exactLat/exactLon = 정확 공개일 때만 */
    class PCatch(val time: Long, val slot: Int, val cell: Cell?, val rising: Boolean?, val near: String,
                 val exactLat: Double? = null, val exactLon: Double? = null) {
        val hasExact: Boolean get() = exactLat != null && exactLon != null
    }

    class PTrip(
        val id: String, val owner: String, val nick: String, val start: Long, val end: Long,
        val cat: String, val names: List<String>, val catches: List<PCatch>, val photoCount: Int,
        val prec: String = "",
        /** 해루질 유료 자리 (금액·준비물은 안 올라감) */
        val paid: Boolean = false,
        /** 올린 사람이 보이게 고른 그룹 이름 · 작은 그룹 이미지 (없으면 "") */
        val gname: String = "", val gimg: String = "",
        /** 대표 조과 사진 (작은 사진 base64) · 사진 목록에서 대표 사진 문서 이름 */
        val mthumb: String = "", val mainId: String = ""
    ) {
        fun name(slot: Int) = names.getOrElse(slot - 1) { "${slot}번" }
        fun count(slot: Int) = catches.count { it.slot == slot }
        fun speciesLine(): String = ((1..maxOf(2, names.size)).toList() + catches.map { it.slot }).distinct().sorted()
            .filter { count(it) > 0 }.joinToString(" · ") { "${name(it)} ${count(it)}" }
        fun anyCell(): Cell? = catches.firstNotNullOfOrNull { it.cell }
        val exact: Boolean get() = prec == "exact"
        /** 어종 + 출조 종류 (같은 어종도 종류가 다르면 따로 집계) */
        fun key(slot: Int): String = speciesKey(name(slot), cat)
    }

    fun speciesKey(name: String, cat: String) = "$name|$cat"
    fun keyName(key: String) = key.substringBefore("|")
    fun keyCat(key: String) = key.substringAfter("|", "")
    /** "낙지 (해루질)" */
    fun keyLabel(key: String): String = keyName(key) + " (" + Store.catLabel(keyCat(key)).ifBlank { "종류 없음" } + ")"

    /** 공개한 내 출조의 출조 종류만 고치기 */
    fun updateCat(t: Store.Trip, done: (String?) -> Unit) {
        if (Cloud.uid == null || !t.pub) return done(null)
        db.collection("public").document(docId(t)).update("cat", t.cat)
            .addOnSuccessListener { done(null) }
            .addOnFailureListener { done(it.message ?: "게시판 반영 실패") }
    }

    private fun docId(t: Store.Trip) = "${Cloud.uid}_${t.id}"
    fun docIdOf(uid: String, tripId: Long) = "${uid}_$tripId"

    // ---------------- 올리기 ----------------
    /** 닉네임 정하기 → (사진이 있으면) 사진도 올릴지 → 올리기 */
    fun publishFlow(a: Activity, t: Store.Trip, done: (String?) -> Unit) {
        if (Cloud.user == null) return done("전체 공개는 구글 로그인 후에 할 수 있어요")
        if (t.catches.isEmpty()) return done("조과가 없는 출조는 공개할 수 없어요")
        ensureNick(a) {
            askPrecision(a, t) { exact ->
                if (t.photos.isEmpty()) publish(a, t, false, exact, done)
                else AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("사진도 같이 공개할까요?")
                    .setMessage("작은 사진 ${minOf(t.photos.size, 8)}장이 전체 게시판에 보여요. 사진 속 위치 정보는 올라가지 않아요.")
                    .setPositiveButton("사진도 공개") { _, _ -> publish(a, t, true, exact, done) }
                    .setNegativeButton("사진은 빼고") { _, _ -> publish(a, t, false, exact, done) }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    /** 위치 공개 방법 고르기 (마지막에 고른 게 기본) */
    private fun askPrecision(a: Activity, t: Store.Trip, then: (Boolean) -> Unit) {
        if (t.catches.none { it.lat != null && it.lon != null }) { then(Store.pubExact); return }
        val box = android.widget.LinearLayout(a).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(Ui.dp(a, 20), Ui.dp(a, 4), Ui.dp(a, 20), 0)
        }
        val group = android.widget.RadioGroup(a)
        val exactBtn = android.widget.RadioButton(a).apply {
            id = android.view.View.generateViewId()
            text = "정확한 위치 공개\n잡은 자리마다 지도에 점이 정확히 찍혀요"
            setTextColor(Ui.TEXT); textSize = 15f
            buttonTintList = android.content.res.ColorStateList.valueOf(Ui.BLUE)
        }
        val approxBtn = android.widget.RadioButton(a).apply {
            id = android.view.View.generateViewId()
            text = "대략적으로만 공개\n반경 약 500m 원으로만 보여요 (정확한 자리는 서버에도 안 올라가요)"
            setTextColor(Ui.TEXT); textSize = 15f
            buttonTintList = android.content.res.ColorStateList.valueOf(Ui.BLUE)
        }
        group.addView(exactBtn); group.addView(approxBtn, android.widget.RadioGroup.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(a, 10) })
        group.check(if (Store.pubExact) exactBtn.id else approxBtn.id)
        box.addView(group)
        AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("위치를 어떻게 공개할까요?")
            .setView(box)
            .setPositiveButton("다음") { _, _ ->
                val exact = group.checkedRadioButtonId == exactBtn.id
                Store.pubExact = exact
                then(exact)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 게시판 이름 = 내 닉네임 (개인 설정에서 정함) 또는 익명 */
    private fun ensureNick(a: Activity, then: () -> Unit) {
        if (Cloud.nick != null || Store.nick == "") { then(); return }
        AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("전체 게시판에 보일 이름")
            .setMessage("아직 닉네임이 없어요. 개인 설정(⚙)에서 닉네임을 정하거나, 익명으로 올릴 수 있어요.")
            .setPositiveButton("닉네임 정하기") { _, _ -> a.startActivity(android.content.Intent(a, SettingsActivity::class.java)) }
            .setNeutralButton("익명으로 올리기") { _, _ -> Store.nick = ""; then() }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 게시판에 같이 올리는 아주 작은 그룹 이미지 (64px) */
    fun smallB64(b: android.graphics.Bitmap): String? = try {
        val sm = android.graphics.Bitmap.createScaledBitmap(b, 64, 64, true)
        val bos = java.io.ByteArrayOutputStream()
        sm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, bos)
        android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
    } catch (_: Exception) { null }

    /** "" = 익명으로 올리기, 그 외 = 내 닉네임 */
    fun nickShown(): String = if (Store.nick == "" || Cloud.nick == null) "익명" else Cloud.displayName

    /** 서버에 올릴 조과 목록 (백그라운드 스레드에서) */
    private fun buildCatches(app: android.content.Context, t: Store.Trip, exact: Boolean): List<Map<String, Any>> {
        // 물 흐름 계산 (그날 물때를 받을 수 있을 때만)
        val loc = t.anyLocation()
        val day = if (loc != null) try { Tide.fetchNow(app, loc.first, loc.second, t.start) } catch (_: Exception) { null } else null
        return t.catches.map { c ->
            val m = hashMapOf<String, Any>("t" to c.time, "s" to c.slot)
            if (c.lat != null && c.lon != null) {
                val cell = if (exact) cellOf(c.lat, c.lon) else approxCellOf(c.lat, c.lon)
                m["k"] = cell.key; m["la"] = cell.lat; m["lo"] = cell.lon
                if (exact) { m["xa"] = c.lat; m["xo"] = c.lon }
            }
            if (day != null) {
                val (r, near) = Tide.phaseAt(day, c.time)
                if (r != null) m["r"] = r
                if (near.isNotBlank()) m["n"] = near
            }
            m
        }
    }

    private fun publish(a: Activity, t: Store.Trip, withPhotos: Boolean, exact: Boolean, done: (String?) -> Unit) {
        val me = Cloud.uid ?: return done("로그인이 필요해요")
        val app = a.applicationContext
        Thread {
            val catches = buildCatches(app, t, exact)
            val photos = if (withPhotos) t.photos.take(8) else emptyList()
            val doc = hashMapOf<String, Any>(
                "owner" to me, "nick" to nickShown(),
                "start" to t.start, "end" to t.end, "cat" to t.cat,
                "names" to t.names.toList(), "catches" to catches,
                "total" to t.catches.size, "photoCount" to photos.size,
                "prec" to (if (exact) "exact" else "approx"),
                "created" to System.currentTimeMillis(), "upd" to System.currentTimeMillis()
            )
            // 전체 조황엔 유료 여부만 (금액·준비물은 안 올림)
            if (t.paid == true) doc["paid"] = true
            // 대표 조과 사진 (작은 사진은 글 안에, 큰 사진은 photos/main)
            MainPhoto.thumbB64(t)?.let { doc["mthumb"] = it }
            t.main()?.let { doc["mainId"] = MainPhoto.docId(it) }
            // 고른 그룹 표시 (이름 + 아주 작은 그룹 이미지)
            Cloud.groups.firstOrNull { it.id == Store.pubGroup }?.let { g ->
                doc["gname"] = g.title
                GroupIcon.cached(g.id)?.let { b -> smallB64(b)?.let { doc["gimg"] = it } }
            }
            val ref = db.collection("public").document(docId(t))
            ref.set(doc)
                .addOnSuccessListener {
                    Store.setPublic(t.id, true, exact)
                    // 작은 사진만 (이미 올린 건 건너뜀, 사진을 빼고 올리면 예전 사진은 지움)
                    Cloud.uploadPhotos(ref, t, "public", limit = if (withPhotos) 8 else 0, withBig = false)
                    // 큰 대표 사진만 (작은 사진은 위 글 안에 이미 넣음 · 그룹 글은 안 건드림)
                    if (t.main() != null) MainPhoto.push(t, groups = false)
                    cache.clear()
                    done(null)
                }
                .addOnFailureListener { main.post { done(it.message ?: "공개 실패 (보안 규칙 확인)") } }
        }.start()
    }

    /** 공개 내리기 (내 것 또는 관리자) */
    fun clearCache() = cache.clear()

    fun unpublish(id: String, localTripId: Long?, done: (String?) -> Unit) {
        // 운영자가 남의 글을 내리면: 휴지통에 보관 + 올린 사람에게 알림
        val owner = id.substringBefore("_")
        if (owner.isNotBlank() && owner != Cloud.uid) {
            Trash.backup(db.collection("public").document(id), owner, "public", "", listOf("photos")) { unpublishNow(id, localTripId, done) }
            return
        }
        unpublishNow(id, localTripId, done)
    }

    private fun unpublishNow(id: String, localTripId: Long?, done: (String?) -> Unit) {
        val ref = db.collection("public").document(id)
        ref.collection("photos").get().addOnCompleteListener { q ->
            q.result?.documents?.forEach { it.reference.delete() }
            ref.delete()
                .addOnSuccessListener {
                    localTripId?.let { Store.setPublic(it, false); Store.photoUpClear("public", it) }
                    cache.clear()
                    done(null)
                }
                .addOnFailureListener { done(it.message ?: "내리기 실패") }
        }
    }

    /**
     * 이미 공개한 출조의 위치 공개 방법 바꾸기 (정확 ↔ 대략)
     *  정확으로 바꾸려면 이 폰에 그 출조 기록(정확한 위치)이 있어야 함
     */
    fun changePrecision(a: Activity, docIdStr: String, exact: Boolean, done: (String?) -> Unit) {
        val me = Cloud.uid ?: return done("로그인이 필요해요")
        if (!docIdStr.startsWith(me + "_")) return done("내 글만 바꿀 수 있어요")
        val t = docIdStr.removePrefix(me + "_").toLongOrNull()?.let { Store.find(it) }
            ?: return done("이 폰에 이 출조 기록이 없어서 바꿀 수 없어요 (기록 복원 후 다시)")
        val app = a.applicationContext
        Thread {
            val catches = buildCatches(app, t, exact)
            db.collection("public").document(docIdStr)
                .update(mapOf("catches" to catches, "prec" to (if (exact) "exact" else "approx"), "upd" to System.currentTimeMillis()))
                .addOnSuccessListener { Store.setPublic(t.id, true, exact); cache.clear(); done(null) }
                .addOnFailureListener { main.post { done(it.message ?: "바꾸기 실패") } }
        }.start()
    }

    fun changeMinePrecision(a: Activity, t: Store.Trip, exact: Boolean, done: (String?) -> Unit) {
        if (Cloud.uid == null) return done("로그인이 필요해요")
        changePrecision(a, docId(t), exact, done)
    }

    fun unpublishMine(t: Store.Trip, done: (String?) -> Unit) {
        if (Cloud.uid == null) return done("로그인이 필요해요")
        unpublish(docId(t), t.id, done)
    }

    // ---------------- 불러오기 ----------------
    /** 잠깐 저장 (3분): 같은 화면을 다시 열어도 또 받지 않게 */
    private val cache = HashMap<Long, Pair<Long, List<PTrip>>>()

    fun load(since: Long, done: (List<PTrip>?, String?) -> Unit) {
        // 한 번 받은 목록(최근 300개)을 10분 기억 → 기간은 폰에서 거름 (기간을 바꿔도 다시 안 받음)
        cache[0L]?.let { (at, list) ->
            if (System.currentTimeMillis() - at < 10 * 60_000L) { done(list.filter { since <= 0 || it.start > since }, null); return }
        }
        loadServer(0L) { list, err ->
            if (list != null) cache[0L] = System.currentTimeMillis() to list
            done(list?.filter { since <= 0 || it.start > since }, err)
        }
    }

    private fun loadServer(since: Long, done: (List<PTrip>?, String?) -> Unit) {
        var q: Query = db.collection("public").orderBy("start", Query.Direction.DESCENDING)
        if (since > 0) q = q.whereGreaterThan("start", since)
        // 폰에 저장된 글 + 그 뒤에 새로 올라오거나 바뀐 글만 서버에서 (3시간마다 한 번 전체)
        Cloud.smartList(db.collection("public"), q.limit(300), "upd", "public_$since") { docs, err ->
            if (docs == null) { done(null, err ?: "불러오기 실패 (보안 규칙 확인)"); return@smartList }
            done(docs.mapNotNull { d ->
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val prec = d.getString("prec") ?: ""
                        val cs = (d.get("catches") as? List<Map<String, Any?>>).orEmpty().map { m ->
                            val la = (m["la"] as? Number)?.toDouble(); val lo = (m["lo"] as? Number)?.toDouble()
                            val k = m["k"] as? String
                            val r = if (k != null && k.startsWith("a")) APPROX_M / 2 else CELL_M / 2
                            PCatch((m["t"] as Number).toLong(), (m["s"] as Number).toInt(),
                                if (k != null && la != null && lo != null) Cell(k, la, lo, r) else null,
                                m["r"] as? Boolean, (m["n"] as? String) ?: "",
                                if (prec == "exact") (m["xa"] as? Number)?.toDouble() else null,
                                if (prec == "exact") (m["xo"] as? Number)?.toDouble() else null)
                        }
                        PTrip(d.id, d.getString("owner") ?: "", d.getString("nick") ?: "익명",
                            d.getLong("start") ?: 0L, d.getLong("end") ?: 0L,
                            // 종류가 비어 있으면 어종으로 짐작 (선상/해루질/워킹)
                            (d.getString("cat") ?: "").ifBlank {
                                Store.guessCat((d.get("names") as? List<*>)?.map { it.toString() } ?: emptyList())
                            },
                            (d.get("names") as? List<*>)?.map { it.toString() } ?: listOf("1번", "2번"), cs,
                            (d.getLong("photoCount") ?: 0L).toInt(), prec, d.getBoolean("paid") == true,
                            d.getString("gname") ?: "", d.getString("gimg") ?: "", d.getString("mthumb") ?: "", d.getString("mainId") ?: "")
                    } catch (_: Exception) { null }
                }.sortedByDescending { it.start }, null)
        }
    }

    fun loadPhotos(id: String, done: (List<Cloud.RemotePhoto>) -> Unit) {
        db.collection("public").document(id).collection("photos").get()
            .addOnSuccessListener { q ->
                // 'main' = 대표 사진 큰 사진 (오른쪽·맨 위 대표 자리에만, 사진 목록엔 안 넣음)
                done(q.documents.filter { it.id != "main" }.mapNotNull { d ->
                    try {
                        val bytes = android.util.Base64.decode(d.getString("data") ?: return@mapNotNull null, android.util.Base64.DEFAULT)
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@mapNotNull null
                        Cloud.RemotePhoto(d.id, d.getLong("t") ?: 0L, d.getLong("catchT"), bmp)
                    } catch (_: Exception) { null }
                }.sortedBy { it.time })
            }
            .addOnFailureListener { done(emptyList()) }
    }

    // ---------------- 포인트 도감 계산 ----------------
    class Point(val cell: Cell, val total: Int, val trips: List<PTrip>, val hours: Double,
                val catches: List<Pair<PTrip, PCatch>>) {
        val rate: Double get() = if (hours > 0) total / hours else total.toDouble()
    }

    /** 어종 하나(key = 어종|종류)에 대해 칸별로 묶기. 1시간당 마릿수 순 */
    fun points(trips: List<PTrip>, key: String): List<Point> {
        val hits = trips.flatMap { t ->
            t.catches.filter { it.cell != null && t.key(it.slot) == key }.map { t to it }
        }
        return hits.groupBy { it.second.cell!!.key }.map { (_, list) ->
            val byTrip = list.groupBy { it.first.id }
            // 그 자리에 머문 시간 ≈ 첫 조과 ~ 마지막 조과 + 30분 (최소 30분)
            val hours = byTrip.values.sumOf { l ->
                val ts = l.map { it.second.time }
                ((ts.max() - ts.min()) / 3_600_000.0 + 0.5).coerceAtLeast(0.5)
            }
            Point(list[0].second.cell!!, list.size, byTrip.values.map { it[0].first }, hours, list)
        }.sortedWith(compareByDescending<Point> { it.trips.size >= 2 }.thenByDescending { it.rate })
    }

    private val monthFmt = SimpleDateFormat("M월", Locale.KOREA)

    /** "잘 나온 때: 7물 · 들물 · 10월" */
    fun bestWhen(p: Point): String {
        val parts = mutableListOf<String>()
        p.catches.groupingBy { Tide.mulName(it.second.time).substringBefore(" ·") }.eachCount()
            .maxByOrNull { it.value }?.let { parts.add(it.key) }
        val up = p.catches.count { it.second.rising == true }; val down = p.catches.count { it.second.rising == false }
        if (up + down > 0) parts.add(if (up >= down) "들물" else "날물")
        p.catches.groupingBy { monthFmt.format(Date(it.second.time)) }.eachCount()
            .maxByOrNull { it.value }?.let { parts.add(it.key) }
        return parts.joinToString(" · ")
    }
}
