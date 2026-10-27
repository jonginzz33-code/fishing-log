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
 *  - 경로·배 이름은 안 올림. 위치는 약 150m 칸의 가운데로만 (정확한 자리는 저장 안 함)
 *  - 사진은 따로 골랐을 때만 작은 사진으로
 */
object PublicBoard {

    private val db get() = FirebaseFirestore.getInstance()
    private val main = Handler(Looper.getMainLooper())

    /** 위치 칸 크기 (m) */
    const val CELL_M = 150.0
    private const val LAT_STEP = CELL_M / 111_320.0

    class Cell(val key: String, val lat: Double, val lon: Double)

    /** 정확한 위치 → 150m 칸 (가운데 좌표) */
    fun cellOf(lat: Double, lon: Double): Cell {
        val i = floor(lat / LAT_STEP).toLong()
        val centerLat = (i + 0.5) * LAT_STEP
        val lonStep = CELL_M / (111_320.0 * cos(Math.toRadians(centerLat)))
        val j = floor(lon / lonStep).toLong()
        return Cell("${i}_$j", centerLat, (j + 0.5) * lonStep)
    }

    class PCatch(val time: Long, val slot: Int, val cell: Cell?, val rising: Boolean?, val near: String)

    class PTrip(
        val id: String, val owner: String, val nick: String, val start: Long, val end: Long,
        val cat: String, val names: List<String>, val catches: List<PCatch>, val photoCount: Int
    ) {
        fun name(slot: Int) = names.getOrElse(slot - 1) { "${slot}번" }
        fun count(slot: Int) = catches.count { it.slot == slot }
        fun speciesLine(): String = (1..2).filter { count(it) > 0 }.joinToString(" · ") { "${name(it)} ${count(it)}" }
        fun anyCell(): Cell? = catches.firstNotNullOfOrNull { it.cell }
    }

    private fun docId(t: Store.Trip) = "${Cloud.uid}_${t.id}"

    // ---------------- 올리기 ----------------
    /** 닉네임 정하기 → (사진이 있으면) 사진도 올릴지 → 올리기 */
    fun publishFlow(a: Activity, t: Store.Trip, done: (String?) -> Unit) {
        if (Cloud.user == null) return done("전체 공개는 구글 로그인 후에 할 수 있어요")
        if (t.catches.isEmpty()) return done("조과가 없는 출조는 공개할 수 없어요")
        ensureNick(a) {
            if (t.photos.isEmpty()) publish(a, t, false, done)
            else AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("사진도 같이 공개할까요?")
                .setMessage("작은 사진 ${minOf(t.photos.size, 8)}장이 전체 게시판에 보여요. 사진 속 위치 정보는 올라가지 않아요.")
                .setPositiveButton("사진도 공개") { _, _ -> publish(a, t, true, done) }
                .setNegativeButton("사진은 빼고") { _, _ -> publish(a, t, false, done) }
                .setCancelable(false)
                .show()
        }
    }

    private fun ensureNick(a: Activity, then: () -> Unit) {
        if (Store.nick != null) { then(); return }
        val input = EditText(a).apply {
            setText(Cloud.displayName); setSelection(text.length); inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("전체 게시판에 보일 이름")
            .setMessage("처음 한 번만 정해요. 나중에 전체 게시판 화면에서 바꿀 수 있어요.")
            .setView(input)
            .setPositiveButton("이 이름으로") { _, _ -> Store.nick = input.text.toString().trim().take(20); then() }
            .setNeutralButton("익명") { _, _ -> Store.nick = ""; then() }
            .setNegativeButton("취소", null)
            .show()
    }

    fun nickShown(): String = Store.nick?.ifBlank { "익명" } ?: "익명"

    private fun publish(a: Activity, t: Store.Trip, withPhotos: Boolean, done: (String?) -> Unit) {
        val me = Cloud.uid ?: return done("로그인이 필요해요")
        val app = a.applicationContext
        Thread {
            // 물 흐름 계산 (그날 물때를 받을 수 있을 때만)
            val loc = t.anyLocation()
            val day = if (loc != null) try { Tide.fetchNow(app, loc.first, loc.second, t.start) } catch (_: Exception) { null } else null
            val catches = t.catches.map { c ->
                val m = hashMapOf<String, Any>("t" to c.time, "s" to c.slot)
                if (c.lat != null && c.lon != null) {
                    val cell = cellOf(c.lat, c.lon)
                    m["k"] = cell.key; m["la"] = cell.lat; m["lo"] = cell.lon
                }
                if (day != null) {
                    val (r, near) = Tide.phaseAt(day, c.time)
                    if (r != null) m["r"] = r
                    if (near.isNotBlank()) m["n"] = near
                }
                m
            }
            val photos = if (withPhotos) t.photos.take(8) else emptyList()
            val doc = hashMapOf<String, Any>(
                "owner" to me, "nick" to nickShown(),
                "start" to t.start, "end" to t.end, "cat" to t.cat,
                "names" to t.names.toList(), "catches" to catches,
                "total" to t.catches.size, "photoCount" to photos.size,
                "created" to System.currentTimeMillis()
            )
            val ref = db.collection("public").document(docId(t))
            ref.set(doc)
                .addOnSuccessListener {
                    Store.setPublic(t.id, true)
                    if (photos.isNotEmpty()) Thread {
                        photos.forEach { p ->
                            val f = Photos.file(app, Photos.thumbName(p.file))
                            if (f.exists() && f.length() < 700_000) {
                                val m = hashMapOf<String, Any>("owner" to me, "t" to p.time,
                                    "data" to android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP))
                                p.catchTime?.let { m["catchT"] = it }
                                ref.collection("photos").document(p.file.removeSuffix(".jpg")).set(m)
                            }
                        }
                    }.start()
                    done(null)
                }
                .addOnFailureListener { main.post { done(it.message ?: "공개 실패 (보안 규칙 확인)") } }
        }.start()
    }

    /** 공개 내리기 (내 것 또는 관리자) */
    fun unpublish(id: String, localTripId: Long?, done: (String?) -> Unit) {
        val ref = db.collection("public").document(id)
        ref.collection("photos").get().addOnCompleteListener { q ->
            q.result?.documents?.forEach { it.reference.delete() }
            ref.delete()
                .addOnSuccessListener { localTripId?.let { Store.setPublic(it, false) }; done(null) }
                .addOnFailureListener { done(it.message ?: "내리기 실패") }
        }
    }

    fun unpublishMine(t: Store.Trip, done: (String?) -> Unit) {
        if (Cloud.uid == null) return done("로그인이 필요해요")
        unpublish(docId(t), t.id, done)
    }

    // ---------------- 불러오기 ----------------
    fun load(since: Long, done: (List<PTrip>?, String?) -> Unit) {
        var q: Query = db.collection("public").orderBy("start", Query.Direction.DESCENDING)
        if (since > 0) q = q.whereGreaterThan("start", since)
        q.limit(300).get()
            .addOnSuccessListener { r ->
                done(r.documents.mapNotNull { d ->
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val cs = (d.get("catches") as? List<Map<String, Any?>>).orEmpty().map { m ->
                            val la = (m["la"] as? Number)?.toDouble(); val lo = (m["lo"] as? Number)?.toDouble()
                            val k = m["k"] as? String
                            PCatch((m["t"] as Number).toLong(), (m["s"] as Number).toInt(),
                                if (k != null && la != null && lo != null) Cell(k, la, lo) else null,
                                m["r"] as? Boolean, (m["n"] as? String) ?: "")
                        }
                        PTrip(d.id, d.getString("owner") ?: "", d.getString("nick") ?: "익명",
                            d.getLong("start") ?: 0L, d.getLong("end") ?: 0L, d.getString("cat") ?: "",
                            (d.get("names") as? List<*>)?.map { it.toString() } ?: listOf("1번", "2번"), cs,
                            (d.getLong("photoCount") ?: 0L).toInt())
                    } catch (_: Exception) { null }
                }, null)
            }
            .addOnFailureListener { done(null, it.message ?: "불러오기 실패 (보안 규칙 확인)") }
    }

    fun loadPhotos(id: String, done: (List<Cloud.RemotePhoto>) -> Unit) {
        db.collection("public").document(id).collection("photos").get()
            .addOnSuccessListener { q ->
                done(q.documents.mapNotNull { d ->
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

    /** 어종 하나(species)에 대해 150m 칸별로 묶기. 1시간당 마릿수 순 */
    fun points(trips: List<PTrip>, species: String): List<Point> {
        val hits = trips.flatMap { t ->
            t.catches.filter { it.cell != null && t.name(it.slot) == species }.map { t to it }
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
