package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 🦀 해루질 참고용 구역 (마을어장 · 양식장 · 수산자원관리수면 · 사고 위험 구역)
 *  - 지도에 ☐ 해루질 참고용 을 켰을 때만 그림 (끄면 아무것도 안 보임 · 알림도 없음)
 *  - 데이터: 관리자가 공공데이터포털에서 받은 CSV(공간정보 · 위경도)를 앱에서 올림 + 지도에서 직접 그려 추가·수정·삭제
 *  - 서버: haeruZones/{칸}_{번호} (0.25° 칸마다 한 문서, 구역을 한 줄씩 글자로) · haeruMeta/info (판 번호 · 개수)
 *  - 폰: 칸마다 파일로 기억, 판 번호가 바뀌면 다시 받음 (읽기 절약)
 *  - 출조 중 + 켜짐: 구역 안에 들어가면 진동 + 알림 (참고용)
 */
object Zones {
    class Zone(
        val id: String, val type: String, val name: String,
        /** 다각형 꼭짓점 (원이면 비어 있음) */
        val pts: List<GeoPoint>,
        /** 원: 가운데 + 반지름(m) */
        val center: GeoPoint? = null, val radiusM: Double = 0.0,
        /** 1~12 = 그 달만 (사고 위험 예측), 0 = 항상 */
        val month: Int = 0,
        val note: String = ""
    ) {
        val minLat = if (center != null) center.latitude - radiusM / 111_000.0 else pts.minOf { it.latitude }
        val maxLat = if (center != null) center.latitude + radiusM / 111_000.0 else pts.maxOf { it.latitude }
        val minLon = if (center != null) center.longitude - radiusM / 88_000.0 else pts.minOf { it.longitude }
        val maxLon = if (center != null) center.longitude + radiusM / 88_000.0 else pts.maxOf { it.longitude }
        val manual get() = id.startsWith("m")

        fun contains(lat: Double, lon: Double): Boolean {
            if (lat < minLat || lat > maxLat || lon < minLon || lon > maxLon) return false
            if (center != null) {
                val r = FloatArray(1)
                android.location.Location.distanceBetween(lat, lon, center.latitude, center.longitude, r)
                return r[0] <= radiusM
            }
            var inside = false
            var j = pts.size - 1
            for (i in pts.indices) {
                val yi = pts[i].latitude; val xi = pts[i].longitude
                val yj = pts[j].latitude; val xj = pts[j].longitude
                if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            return inside
        }

        fun activeNow(): Boolean = month == 0 || month == java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1

        fun encode(): String {
            val geo = if (center != null) String.format(Locale.US, "c:%.5f,%.5f,%.0f", center.latitude, center.longitude, radiusM)
            else pts.joinToString(";") { String.format(Locale.US, "%.5f,%.5f", it.latitude, it.longitude) }
            return listOf(id, type, clean(name), month.toString(), clean(note), geo).joinToString("|")
        }
    }

    /** 종류: 코드 → 이름 · 색 */
    val TYPES = linkedMapOf(
        "village" to "마을어장", "farm" to "양식장", "res" to "수산자원관리수면",
        "risk" to "사고 위험 구역", "ban" to "낚시 · 레저 금지 · 제한", "etc" to "기타 어장"
    )
    fun typeColor(t: String): Int = when (t) {
        "village" -> Color.parseColor("#FF8A3D")
        "farm" -> Color.parseColor("#4FB3FF")
        "res" -> Color.parseColor("#5FD39A")
        "risk" -> Color.parseColor("#FF4D5E")
        "ban" -> Color.parseColor("#FF4FA8")
        else -> Color.parseColor("#C9A6FF")
    }
    fun typeIcon(t: String): String = when (t) {
        "village" -> "🐚"; "farm" -> "🦪"; "res" -> "🐟"; "risk" -> "⚠"; "ban" -> "🚫"; else -> "📍"
    }
    fun typeWarn(t: String): String = when (t) {
        "village" -> "어촌계가 관리하는 마을어장일 수 있어요. 패류 · 해조류 등 채취는 분쟁 · 처벌될 수 있으니 확인하세요."
        "farm" -> "면허 양식장일 수 있어요. 양식 생물 채취 · 시설 훼손은 처벌될 수 있어요."
        "res" -> "수산자원관리수면일 수 있어요. 기간 · 어종 · 방법 제한이 있을 수 있어요."
        "risk" -> "연안 사고 위험이 높은 곳이에요. 밀물 고립 · 갯골 · 너울에 주의하세요."
        "ban" -> "낚시 · 수중레저 등이 금지되거나 제한된 구역이에요. 기간 · 방법은 현장 안내판을 확인하세요."
        else -> "어업 면허 구역일 수 있어요. 채취 전에 확인하세요."
    }

    const val CELL = 4.0   // 1° 를 4칸 = 0.25°
    const val MIN_ZOOM = 11.0
    private const val COL = "haeruZones"
    private const val CHUNK = 300_000

    private val db get() = FirebaseFirestore.getInstance()
    private val main = Handler(Looper.getMainLooper())

    /** 칸 → 구역들 (폰에 기억된 것) */
    private val cells = HashMap<String, List<Zone>>()
    private val loading = HashSet<String>()
    private var serverVer = -1L
    private var verAt = 0L
    /** 구역을 저장할 때마다 +1 → 열린 지도들이 다시 불러옴 (v3.7.3) */
    @Volatile var gen = 0

    private fun prefs(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)
    /** ☐ 해루질 참고용 (기본 꺼짐) */
    fun on(c: Context) = prefs(c).getBoolean("haeru_ref", false)
    fun setOn(c: Context, v: Boolean) { prefs(c).edit().putBoolean("haeru_ref", v).apply(); if (!v) insideIds.clear() }

    fun cellOf(lat: Double, lon: Double) = "${floor(lat * CELL).toInt()}_${floor(lon * CELL).toInt()}"
    fun cellsFor(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, cap: Int = 36): List<String> {
        val out = ArrayList<String>()
        var y = floor(minLat * CELL).toInt()
        while (y <= floor(maxLat * CELL).toInt()) {
            var x = floor(minLon * CELL).toInt()
            while (x <= floor(maxLon * CELL).toInt()) { out.add("${y}_$x"); if (out.size >= cap) return out; x++ }
            y++
        }
        return out
    }

    private fun clean(s: String) = s.replace("|", "/").replace("\n", " ").replace("\r", " ").trim()

    fun decodeLine(l: String): Zone? = try {
        val p = l.split("|")
        if (p.size < 6) null else {
            val geo = p[5]
            if (geo.startsWith("c:")) {
                val v = geo.substring(2).split(",").map { it.toDouble() }
                Zone(p[0], p[1], p[2], emptyList(), GeoPoint(v[0], v[1]), v[2], p[3].toIntOrNull() ?: 0, p[4])
            } else {
                val pts = geo.split(";").mapNotNull { s -> val v = s.split(","); if (v.size == 2) GeoPoint(v[0].toDouble(), v[1].toDouble()) else null }
                if (pts.size < 3) null else Zone(p[0], p[1], p[2], pts, null, 0.0, p[3].toIntOrNull() ?: 0, p[4])
            }
        }
    } catch (_: Exception) { null }

    // ---------------- 불러오기 (폰 파일 → 서버) ----------------
    private fun dir(c: Context) = File(c.filesDir, "haeru").apply { mkdirs() }
    private fun localVer(c: Context) = prefs(c).getLong("haeru_ver", -1L)

    /** 서버 판 번호 (30분마다 한 번만 읽음) → 바뀌었으면 폰 기억 비움 */
    private fun checkVer(c: Context, done: () -> Unit) {
        val now = System.currentTimeMillis()
        if (serverVer >= 0 && now - verAt < 30 * 60_000L) return done()
        db.collection("haeruMeta").document("info").get()
            .addOnSuccessListener { d ->
                serverVer = d.getLong("ver") ?: 0L; verAt = System.currentTimeMillis()
                if (serverVer != localVer(c)) {
                    dir(c).listFiles()?.forEach { it.delete() }
                    cells.clear()
                    prefs(c).edit().putLong("haeru_ver", serverVer).apply()
                }
                done()
            }
            .addOnFailureListener { verAt = System.currentTimeMillis(); if (serverVer < 0) serverVer = localVer(c); done() }
    }

    /** 이 칸들 준비 (폰에 있으면 바로, 없으면 서버에서) */
    fun ensure(c: Context, want: List<String>, done: () -> Unit) {
        appCtx = c.applicationContext
        if (!Cloud.isInitialized() || Cloud.user == null) return done()
        checkVer(c) {
            val miss = ArrayList<String>()
            want.forEach { id ->
                if (cells.containsKey(id) || id in loading) return@forEach
                val f = File(dir(c), "$id.txt")
                if (f.exists()) cells[id] = f.readLines().mapNotNull { decodeLine(it) } else miss.add(id)
            }
            if (miss.isEmpty()) return@checkVer done()
            loading.addAll(miss)
            var left = (miss.size + 29) / 30
            miss.chunked(30).forEach { part ->
                db.collection(COL).whereIn("cell", part).get()
                    .addOnSuccessListener { q ->
                        val by = HashMap<String, StringBuilder>()
                        part.forEach { by[it] = StringBuilder() }
                        q.documents.forEach { d ->
                            val id = d.getString("cell") ?: return@forEach
                            val z = d.getString("z") ?: ""
                            if (z.isNotBlank()) by.getOrPut(id) { StringBuilder() }.append(z).append('\n')
                        }
                        by.forEach { (id, sb) ->
                            val txt = sb.toString().trim()
                            try { File(dir(c), "$id.txt").writeText(txt) } catch (_: Exception) {}
                            cells[id] = txt.lines().mapNotNull { decodeLine(it) }
                        }
                        loading.removeAll(part.toSet())
                        left--; if (left == 0) main.post { done() }
                    }
                    .addOnFailureListener {
                        loading.removeAll(part.toSet())
                        left--; if (left == 0) main.post { done() }
                    }
            }
        }
    }

    /** 지금 기억된 것 중 이 범위에 걸치는 구역 (같은 구역이 여러 칸에 있어도 하나로) */
    /** 앱 context (개방海 기억을 꺼낼 때) */
    @Volatile private var appCtx: Context? = null

    fun zonesIn(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<Zone> {
        val seen = HashSet<String>()
        val out = ArrayList<Zone>()
        val ids = cellsFor(minLat, maxLat, minLon, maxLon, 200)
        fun take(z: Zone) {
            if (z.activeNow() && z.maxLat >= minLat && z.minLat <= maxLat && z.maxLon >= minLon && z.minLon <= maxLon && seen.add(z.id)) out.add(z)
        }
        // v3.9.1: 개방海 어장을 켜 두면, 관리자가 CSV · API로 올린 어장(마을 · 양식 · 기타)은 겹치니 숨김 (직접 그린 구역 · 다른 종류는 그대로)
        val c0 = appCtx
        val khoaFarm = c0 != null && Khoa.key(c0).isNotBlank() && Khoa.groupOn(c0, "farm")
        ids.forEach { id -> cells[id]?.forEach { z ->
            if (khoaFarm && !z.manual && !z.id.startsWith("k") && z.type in setOf("village", "farm", "etc")) return@forEach
            take(z)
        } }
        // v3.9: 개방海 (WFS) 레이어
        c0?.let { c -> Khoa.zonesOf(c, ids).forEach { take(it) } }
        return out
    }

    /** UTM-K (EPSG:5179) → 위경도 (개방海 응답) */
    fun fromUtmK(x: Double, y: Double): GeoPoint = tmInv(x, y, 127.5, 0.9996, 1_000_000.0, 2_000_000.0)
    fun simplifyPts(p: List<GeoPoint>, tol: Double): List<GeoPoint> = simplify(p, tol).let { if (it.size > 500) simplify(p, tol * 8) else it }

    fun zonesAt(lat: Double, lon: Double): List<Zone> =
        zonesIn(lat, lat, lon, lon).filter { it.contains(lat, lon) }

    // ---------------- 출조 중 들어가면 알림 ----------------
    private val insideIds = HashSet<String>()
    private var lastCheck = 0L

    /** Tracker 가 정확한 위치를 받을 때마다 */
    fun onFix(c: Context, lat: Double, lon: Double) {
        if (!on(c) || Store.current == null) { insideIds.clear(); return }
        val now = System.currentTimeMillis()
        if (now - lastCheck < 15_000L) return
        lastCheck = now
        val near = cellsFor(lat - 0.01, lat + 0.01, lon - 0.01, lon + 0.01)
        appCtx = c.applicationContext
        Khoa.ensure(c, near) {}
        ensure(c, near) {
            val here = zonesAt(lat, lon)
            val ids = here.map { it.id }.toSet()
            val fresh = here.filter { it.id !in insideIds }
            insideIds.clear(); insideIds.addAll(ids)
            if (fresh.isNotEmpty()) alert(c, fresh)
        }
    }

    private fun alert(c: Context, list: List<Zone>) {
        val z = list.first()
        // v3.9.1: 여러 구역에 한꺼번에 들어가면 알림 하나로 (예: 🐚 마을어장 · ⚠ 사고 위험 구역 안이에요)
        val kinds = list.map { it.type }.distinct()
        val title = kinds.joinToString(" · ") { "${typeIcon(it)} ${TYPES[it] ?: "구역"}" } + " 안이에요 (해루질 참고용)"
        val text = list.distinctBy { it.type }.joinToString("\n") { (if (it.name.isNotBlank()) it.name + " · " else "") + typeWarn(it.type) }
        // v4.1 (183): 구역마다 다른 진동 + 음성 경고
        val kind = kindOf(list)
        vibrateKind(c, kind)
        speak(c, VOICE[kind] ?: "")
        try {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (nm.getNotificationChannel("haeruZone") == null)
                nm.createNotificationChannel(android.app.NotificationChannel("haeruZone", "해루질 참고 구역 알림", android.app.NotificationManager.IMPORTANCE_HIGH))
            val open = android.app.PendingIntent.getActivity(c, 13, Intent(c, MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            val n = android.app.Notification.Builder(c, "haeruZone")
                .setSmallIcon(R.drawable.ic_stat_octo)
                .setColor(typeColor(z.type))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text + "\n※ 참고용 자료예요. 실제 경계 · 허용 여부는 현장 안내판 · 어촌계 · 지자체에 확인하세요."))
                .setContentIntent(open).setAutoCancel(true)
                .build()
            nm.notify(31, n)
        } catch (_: Exception) {}
        listener?.invoke(title)
    }
    /** 앱이 켜져 있으면 화면 아래 안내도 */
    var listener: ((String) -> Unit)? = null

    // ---------------- 🔊 음성 경고 · 구역별 진동 (v4.1 · 183) ----------------
    fun voiceOn(c: Context) = prefs(c).getBoolean("haeru_voice", true)
    fun setVoice(c: Context, v: Boolean) = prefs(c).edit().putBoolean("haeru_voice", v).apply()
    /** accident 인명사고 · tidal 갯골 · 위험 · ban 금지 · farm 어장 (위험한 것 먼저) */
    private fun kindOf(list: List<Zone>): String = when {
        list.any { it.type == "risk" && it.name.startsWith("인명사고") } -> "accident"
        list.any { it.type == "risk" } -> "tidal"
        list.any { it.type == "ban" } -> "ban"
        else -> "farm"
    }
    private val VOICE = mapOf(
        "accident" to "주의. 인명사고가 있었던 곳이에요. 물때와 주변을 꼭 확인하세요.",
        "tidal" to "주의. 갯골 구역에 들어왔어요. 물이 들어오면 빠르게 찹니다.",
        "ban" to "낚시 금지 또는 제한 구역이에요. 안내판을 확인하세요.",
        "farm" to "어장 구역이에요. 허가 없이 채취하면 안 될 수 있어요.")
    private val PATTERN = mapOf(
        "accident" to longArrayOf(0, 150, 100, 150, 100, 150),
        "tidal" to longArrayOf(0, 150, 100, 150, 100, 150),
        "farm" to longArrayOf(0, 250, 150, 250),
        "ban" to longArrayOf(0, 600, 200, 600))
    private fun vibrateKind(c: Context, kind: String) {
        try {
            val v = c.getSystemService(android.os.Vibrator::class.java) ?: return
            v.vibrate(android.os.VibrationEffect.createWaveform(PATTERN[kind] ?: longArrayOf(0, 400), -1))
        } catch (_: Exception) { try { Haptics.undo(c) } catch (_: Exception) {} }
    }
    private var tts: android.speech.tts.TextToSpeech? = null
    @Volatile private var ttsReady = false
    private var pendingSay = ""
    private fun speak(c: Context, text: String) {
        if (text.isBlank() || !voiceOn(c)) return
        val t = tts
        if (t != null && ttsReady) { t.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "haeru"); return }
        pendingSay = text
        if (t == null) tts = android.speech.tts.TextToSpeech(c.applicationContext) { st ->
            if (st == android.speech.tts.TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN; ttsReady = true
                if (pendingSay.isNotBlank()) { tts?.speak(pendingSay, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "haeru"); pendingSay = "" }
            }
        }
    }
    /** 커튼에서 '들어보기' */
    fun sampleVoice(c: Context) { vibrateKind(c, "tidal"); speak(c, VOICE["tidal"]!!) }

    // ---------------- 지도 위에 그리기 ----------------
    class ZoneOverlay(private val a: Activity, private val isOn: () -> Boolean) : Overlay() {
        private val d = a.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; style = Paint.Style.STROKE; color = Color.parseColor("#0B1A26") }
        private val path = Path()
        private val pt = android.graphics.Point()
        private var lastKey = ""
        /** 그리기 모드 (관리자): 찍은 꼭짓점 */
        val drawing = ArrayList<GeoPoint>()
        var drawMode = false
        var onTapZones: ((List<Zone>) -> Unit)? = null
        var onDrawChange: (() -> Unit)? = null
        var hint: ((String) -> Unit)? = null
        private var lastHint = ""
        private fun say(s: String) { if (s != lastHint) { lastHint = s; hint?.let { h -> a.runOnUiThread { h(s) } } } }

        override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
            if (shadow) return
            if (drawMode) drawDraft(c, map)
            if (!isOn()) { say(""); return }
            if (map.zoomLevelDouble < MIN_ZOOM) { say("🦀 해루질 참고 구역은 조금 더 확대하면 보여요"); return }
            val box = map.boundingBox
            val want = cellsFor(box.latSouth, box.latNorth, box.lonWest, box.lonEast)
            val key = want.joinToString(",") + "#" + gen + "#" + Khoa.GROUPS.keys.joinToString("") { if (Khoa.groupOn(a, it)) "1" else "0" } + Khoa.key(a).length
            if (key != lastKey) {
                lastKey = key
                ensure(a, want) { map.invalidate() }
                Khoa.ensure(a, want) { map.invalidate() }
            }
            val list = zonesIn(box.latSouth, box.latNorth, box.lonWest, box.lonEast)
            val err = if (Cloud.isAdmin && Khoa.lastErr.isNotBlank()) "\n(관리자) 개방海 오류: ${Khoa.lastErr}" else ""
            val busy = Khoa.pending()
            say((if (list.isEmpty()) (if (busy > 0) "🦀 해루질 참고 구역" else "🦀 이 화면엔 등록된 해루질 참고 구역이 없어요") else "🦀 해루질 참고 구역 ${list.size}곳 (참고용 · 누르면 설명)") +
                (if (busy > 0) " · 🌊 개방海 받는 중… ${busy}" else "") + err)
            val pj = map.projection
            val zoom = map.zoomLevelDouble
            val showName = zoom >= 14.0
            txt.textSize = 11 * d; halo.textSize = 11 * d; halo.strokeWidth = 3 * d
            // v4.1 (189): 같은 글자는 가까이 하나만
            val drawn = ArrayList<Triple<String, Float, Float>>()
            fun label(t: String, x: Float, y: Float, col: Int) {
                if (drawn.any { it.first == t && Math.hypot((it.second - x).toDouble(), (it.third - y).toDouble()) < 140 * d }) return
                drawn.add(Triple(t, x, y)); txt.color = col
                c.drawText(t, x, y, halo); c.drawText(t, x, y, txt)
            }
            list.forEach { z ->
                val col = typeColor(z.type)
                // v3.9.2 (163): 갯골 · 웅덩이는 가늘고 옅게 (지도 덜 가리게)
                val tidal = z.id.startsWith("k") && z.type == "risk" && (z.name.startsWith("갯골") || z.name.startsWith("웅덩이"))
                fill.color = Color.argb(if (tidal) 30 else if (z.type == "risk") 70 else 55, Color.red(col), Color.green(col), Color.blue(col))
                edge.color = if (tidal) Color.argb(150, Color.red(col), Color.green(col), Color.blue(col)) else col
                edge.strokeWidth = (if (tidal) 0.8f else 1.6f) * d
                edge.pathEffect = if (z.type == "risk") android.graphics.DashPathEffect(floatArrayOf(6 * d, 4 * d), 0f) else null
                var cx: Float; var cy: Float
                if (z.center != null && z.id.startsWith("k")) {
                    // v3.9.1: 개방海 점(인명사고 등)은 큰 원 대신 작은 ⚠ 점 · 많이 확대했을 때만
                    if (map.zoomLevelDouble < 13.0) return@forEach
                    pj.toPixels(z.center, pt)
                    fill.color = col; c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 3.5f * d, fill)
                    edge.pathEffect = null; edge.color = Color.WHITE; edge.strokeWidth = 1f * d
                    c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 3.5f * d, edge)
                    // v4.1 (184): 인명사고 등 점은 글자 없이 · 아주 크게 확대했을 때만 짧게 '⚠사고'
                    if (zoom >= 16.0) label(if (z.name.startsWith("인명사고")) "⚠사고" else typeIcon(z.type), pt.x.toFloat(), pt.y.toFloat() - 7 * d, col)
                    return@forEach
                } else if (z.center != null) {
                    pj.toPixels(z.center, pt)
                    val r = pj.metersToPixels(z.radiusM.toFloat())
                    c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, fill); c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, edge)
                    cx = pt.x.toFloat(); cy = pt.y.toFloat()
                } else {
                    path.reset()
                    var sx = 0f; var sy = 0f
                    var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
                    z.pts.forEachIndexed { i, g ->
                        pj.toPixels(g, pt)
                        if (i == 0) path.moveTo(pt.x.toFloat(), pt.y.toFloat()) else path.lineTo(pt.x.toFloat(), pt.y.toFloat())
                        sx += pt.x; sy += pt.y
                        x0 = minOf(x0, pt.x.toFloat()); x1 = maxOf(x1, pt.x.toFloat()); y0 = minOf(y0, pt.y.toFloat()); y1 = maxOf(y1, pt.y.toFloat())
                    }
                    // v4.1 (189): 작은 갯골 · 웅덩이는 많이 확대했을 때만
                    if (tidal && zoom < 16.0 && maxOf(x1 - x0, y1 - y0) < 40 * d) return@forEach
                    path.close()
                    c.drawPath(path, fill); c.drawPath(path, edge)
                    cx = sx / z.pts.size; cy = sy / z.pts.size
                }
                // v4.1 (189): 갯골 · 웅덩이는 글자 없이 선만 (아주 크게 확대했을 때만 '⚠갯골' 하나)
                if (tidal) { if (zoom >= 16.0) label("⚠" + z.name.take(3), cx, cy, col); return@forEach }
                if (showName) {
                    label(typeIcon(z.type) + " " + (z.name.ifBlank { TYPES[z.type] ?: "" }).take(14), cx, cy, col)
                }
            }
        }

        private fun drawDraft(c: Canvas, map: MapView) {
            if (drawing.isEmpty()) return
            val pj = map.projection
            path.reset()
            drawing.forEachIndexed { i, g ->
                pj.toPixels(g, pt)
                if (i == 0) path.moveTo(pt.x.toFloat(), pt.y.toFloat()) else path.lineTo(pt.x.toFloat(), pt.y.toFloat())
            }
            if (drawing.size >= 3) path.close()
            fill.color = Color.argb(60, 255, 79, 168)
            edge.color = Color.parseColor("#FF4FA8"); edge.strokeWidth = 2.5f * d; edge.pathEffect = null
            if (drawing.size >= 3) c.drawPath(path, fill)
            c.drawPath(path, edge)
            drawing.forEach { g ->
                pj.toPixels(g, pt)
                fill.color = Color.WHITE; c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 6 * d, fill)
                c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 6 * d, edge)
            }
        }

        override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
            val g = map.projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
            if (drawMode) { drawing.add(g); onDrawChange?.invoke(); map.invalidate(); return true }
            if (!isOn() || map.zoomLevelDouble < MIN_ZOOM) return false
            // v4.1 (184): 작은 ⚠ 점도 눌리게 (손가락 둘레 약 24dp)
            val pj = map.projection
            val near = if (map.zoomLevelDouble >= 13.0) zonesIn(g.latitude - 0.01, g.latitude + 0.01, g.longitude - 0.01, g.longitude + 0.01)
                .filter { it.center != null && it.id.startsWith("k") }
                .filter { z ->
                    pj.toPixels(z.center!!, pt)
                    val dist: Double = Math.hypot((pt.x - e.x).toDouble(), (pt.y - e.y).toDouble())
                    dist < 24.0 * d
                } else emptyList()
            val hit = (zonesAt(g.latitude, g.longitude) + near).distinctBy { it.id }
            if (hit.isEmpty()) return false
            onTapZones?.invoke(hit); return true
        }
    }

    /** 지도 위 ☐ 해루질 참고용 칩 (지도 창마다) */
    /** v3.9.2 (161): 칩 + 칩 아래 커튼 (보일 구역 체크 · 📥 미리 받기 · 끄기) — 커튼은 부르는 쪽이 버튼 줄 바로 아래에 붙임 */
    class Bar(val pill: TextView, val curtain: LinearLayout)

    fun bar(a: Activity, map: MapView, after: () -> Unit = {}): Bar {
        fun dp(v: Int) = Ui.dp(a, v)
        // v3.9.4 (171): 다른 지도 버튼과 같은 크기 (글자 14 · 높이 40 · 좌우 14)
        val v = Ui.text(a, 14f, Ui.TEXT, false, "").apply {
            gravity = Gravity.CENTER; minHeight = dp(40); setPadding(dp(14), 0, dp(14), 0); maxLines = 1
        }
        val cur = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)); visibility = View.GONE
            background = Ui.bg(a, Color.parseColor("#1F1A14"), 12, Color.parseColor("#FFB27A"))
        }
        val bar = Bar(v, cur)
        var paintRef: () -> Unit = {}
        // v3.9.3 (167): 작게 · 두 줄 (칩 한 줄 + 작은 버튼 한 줄) · 받는 중일 때만 진행 글
        var busy = ""
        var confirmedMobile = false
        fun chip(label: String, on: Boolean, col: Int, onClick: () -> Unit) = Ui.text(a, 12.5f, if (on) Color.parseColor("#1B0E02") else col, true, label).apply {
            gravity = Gravity.CENTER; maxLines = 1; setPadding(dp(10), dp(5), dp(10), dp(5))
            background = if (on) Ui.bg(a, Color.parseColor("#FFB27A"), 99) else Ui.bg(a, Color.TRANSPARENT, 99, col)
            setOnClickListener { onClick() }
        }
        fun paintCurtain() {
            cur.removeAllViews()
            val r1 = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            Khoa.GROUPS.keys.forEach { g ->
                val on = Khoa.groupOn(a, g)
                r1.addView(chip((if (on) "☑ " else "☐ ") + (Khoa.SHORT[g] ?: g), on, Color.parseColor("#FFB27A")) {
                    Khoa.setGroupOn(a, g, !on); paintCurtain(); map.invalidate(); after()
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
            }
            cur.addView(Ui.hscroll(a, r1, Color.parseColor("#1F1A14")))
            val prog = Ui.text(a, 11.5f, Color.parseColor("#FFB27A"), false, busy).apply { visibility = if (busy.isBlank()) View.GONE else View.VISIBLE }
            val r2 = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            fun setBusy(t: String) { busy = t; prog.text = t; prog.visibility = if (t.isBlank()) View.GONE else View.VISIBLE }
            // 📥 이 화면 지역 (164 · 1인당 제한 170)
            r2.addView(chip("📥 이 지역 받기", false, Ui.TEXT) {
                if (Khoa.key(a).isBlank()) { Ui.toast(a, "개방海 인증키가 아직 없어요 (관리자)"); return@chip }
                if (map.zoomLevelDouble < 9.0) { Ui.toast(a, "조금 더 확대해서 받을 지역을 골라 주세요"); return@chip }
                // v4.1 (185): 모바일 데이터면 먼저 물어보기
                if (Ui.metered(a) && !confirmedMobile) {
                    AlertDialog.Builder(a, R.style.FlAlert).setTitle("📶 모바일 데이터예요")
                        .setMessage("구역 자료는 데이터가 꽤 들어요. 와이파이에서 받는 걸 추천해요.\n그래도 지금 받을까요?")
                        .setPositiveButton("받기") { _, _ -> confirmedMobile = true; Ui.toast(a, "한 번 더 '📥 이 지역 받기'를 눌러 주세요") }
                        .setNegativeButton("취소", null).show()
                    return@chip
                }
                val box = map.boundingBox
                val place = Area.name(a, box.centerLatitude, box.centerLongitude).ifBlank { "이 지역" }
                setBusy("📥 받는 중…")
                Khoa.prefetchChecked(a, box.latSouth, box.latNorth, box.lonWest, box.lonEast, place, { d, t -> setBusy("📥 $d / $t") }) { _, fail, err ->
                    if (err != null) { setBusy(""); Ui.toast(a, err) }
                    else setBusy(if (fail == 0) "📥 '$place' 받기 완료 · ⚙ 설정 '오프라인 지역'" else "📥 ${fail}곳 실패 · 인터넷 확인 후 다시")
                    map.invalidate()
                }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
            // 🇰🇷 전국 (관리자만 · 170)
            if (Cloud.isAdmin) r2.addView(chip("🇰🇷 전국 받기", false, Ui.TEXT) {
                if (Khoa.nationalRunning) { Ui.toast(a, "전국 받기가 이미 진행 중이에요"); return@chip }
                if (Ui.metered(a)) { Ui.toast(a, "📶 전국 받기는 와이파이에서만 할 수 있어요"); return@chip }
                AlertDialog.Builder(a, R.style.FlAlert)
                    .setTitle("🇰🇷 전국 해안 미리 받기 (관리자)")
                    .setMessage("우리나라 해안 · 섬 ${Khoa.nationalCells().size}칸을 받아요.\n이미 받은 곳은 건너뛰고, 끊기면 다시 누를 때 이어서 받아요.\n데이터가 많이 들어요 (와이파이 권장) · 시간이 꽤 걸려요.")
                    .setPositiveButton("받기") { _, _ ->
                        setBusy("🇰🇷 전국 받는 중…")
                        Khoa.prefetchNational(a, { d, t -> setBusy("🇰🇷 전국 $d / $t") }) { ok, fail ->
                            setBusy(if (fail == 0) "🇰🇷 전국 받기 완료 (새로 받은 ${ok})" else "🇰🇷 전국 · ${fail}곳 실패 · 다시 누르면 이어 받기")
                            map.invalidate()
                        }
                    }.setNegativeButton("취소", null).show()
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
            val vOn = voiceOn(a)
            r2.addView(chip(if (vOn) "🔊 음성 켜짐" else "🔇 음성 꺼짐", vOn, Ui.TEXT) {
                setVoice(a, !vOn); if (!vOn) sampleVoice(a); paintCurtain()
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
            r2.addView(chip("끄기", false, Color.parseColor("#FF8A92")) {
                setOn(a, false); cur.visibility = View.GONE; paintRef(); map.invalidate(); after()
            })
            cur.addView(Ui.hscroll(a, r2, Color.parseColor("#1F1A14")), Ui.lp(a, MATCH, WRAP, top = 6))
            cur.addView(prog, Ui.lp(a, MATCH, WRAP, top = 4))
        }
        fun paint() {
            val o = on(a)
            val open = cur.visibility == View.VISIBLE
            v.text = (if (o) "☑" else "☐") + " 해루질 참고용" + if (o) (if (open) " ▴" else " ▾") else ""
            v.setTextColor(if (o) Color.parseColor("#1B0E02") else Color.WHITE)
            v.typeface = if (o) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            // 꺼져 있을 땐 다른 버튼과 똑같이 · 켜지면 주황으로 채움
            v.background = if (o) android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.parseColor("#FFB27A")) }
                else android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setStroke(dp(1), Color.parseColor("#3A5A70")) }
        }
        paintRef = { paint() }
        paint()
        v.setOnClickListener {
            if (!on(a)) {
                // v3.9 (157): 켤 때는 '참고용' 안내 → [확인]을 눌러야 켜짐 → 켜지면 커튼 펼침
                confirmOn(a) { paintCurtain(); cur.visibility = View.VISIBLE; paint(); map.invalidate(); after() }
                return@setOnClickListener
            }
            if (cur.visibility == View.VISIBLE) cur.visibility = View.GONE else { paintCurtain(); cur.visibility = View.VISIBLE }
            paint()
        }
        return bar
    }


    /** 켜기 전 안내창 (참고용) + 볼 구역 묶음 체크 → 확인이면 켬 */
    fun confirmOn(a: Activity, done: () -> Unit) {
        fun dp(v: Int) = Ui.dp(a, v)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), dp(4)) }
        box.addView(Ui.text(a, 13.5f, Ui.TEXT, false,
            "• 공공데이터(자료 기준 연도)를 바탕으로 한 참고용이에요. 실제 경계 · 면허 기간과 다를 수 있어요.\n" +
            "• 현장 안내판 · 어촌계 · 지자체 안내가 우선이에요.\n" +
            "• 낚시는 이 구역에서도 할 수 있어요. 해루질(채취) 분쟁과 사고를 피하려는 용도예요.\n" +
            "• 켜 두면 출조 중 구역에 들어갈 때 진동 + 알림이 와요.").apply { setLineSpacing(0f, 1.3f) })
        box.addView(Ui.text(a, 12.5f, Ui.SUB, true, "지도에 보일 구역"), Ui.lp(a, MATCH, WRAP, top = 12))
        val sel = HashMap<String, Boolean>()
        Khoa.GROUPS.forEach { (g, label) ->
            sel[g] = Khoa.groupOn(a, g)
            val t = Ui.text(a, 14f, Ui.TEXT, true, "").apply { setPadding(0, dp(8), 0, dp(8)) }
            fun paintT() { t.text = (if (sel[g] == true) "☑ " else "☐ ") + label; t.setTextColor(if (sel[g] == true) Ui.TEXT else Ui.SUB) }
            paintT()
            t.setOnClickListener { sel[g] = sel[g] != true; paintT() }
            box.addView(t)
        }
        box.addView(Ui.text(a, 11.5f, Ui.SUB, false, "어장 · 갯골 · 금지구역은 국립해양조사원 '개방海' 자료 (관리자가 인증키를 넣었을 때) + 관리자가 올린 구역이에요."),
            Ui.lp(a, MATCH, WRAP, top = 6))
        AlertDialog.Builder(a, R.style.FlAlert)
            .setTitle("🦀 해루질 참고용 — 꼭 읽어 주세요")
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton("확인 — 참고용으로 켜기") { _, _ ->
                sel.forEach { (g, on) -> Khoa.setGroupOn(a, g, on) }
                setOn(a, true); done()
            }
            .setNegativeButton("취소", null).show()
    }

    /** 처음 켤 때 · ? 설명 */
    fun explain(a: Activity) {
        AlertDialog.Builder(a, R.style.FlAlert)
            .setTitle("🦀 해루질 참고용")
            .setMessage("지도에 이런 곳을 색으로 보여줘요 (확대하면 보여요)\n\n" +
                TYPES.entries.joinToString("\n") { "${typeIcon(it.key)} ${it.value}" } +
                "\n\n• 출조 중에 이 구역 안으로 들어가면 진동 + 알림으로 알려줘요\n" +
                "• 낚시는 이 구역에서도 할 수 있어요. 해루질(맨손 · 도구 채취) 분쟁을 피하려는 참고용이에요\n" +
                "• 공공데이터를 바탕으로 해서 실제 경계 · 최신 면허와 다를 수 있어요. 현장 안내판 · 어촌계 · 지자체 안내가 우선이에요\n" +
                "• 끄면(☐) 지도에도 안 보이고 알림도 없어요")
            .setPositiveButton("확인", null).show()
    }

    /** 구역을 눌렀을 때 */
    fun showInfo(a: Activity, list: List<Zone>, map: MapView?) {
        fun dp(v: Int) = Ui.dp(a, v)
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(6), dp(18), dp(4)) }
        var dlg: AlertDialog? = null
        list.forEach { z ->
            val card = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
                background = Ui.bg(a, Ui.CARD, 12, typeColor(z.type))
            }
            card.addView(Ui.text(a, 15f, typeColor(z.type), true, "${typeIcon(z.type)} ${TYPES[z.type] ?: "구역"}" +
                (if (z.month > 0) " (${z.month}월 예측)" else "")))
            if (z.name.isNotBlank()) card.addView(Ui.text(a, 14f, Ui.TEXT, true, z.name), Ui.lp(a, MATCH, WRAP, top = 2))
            if (z.note.isNotBlank()) {
                // v3.9 (156): 줄마다 이름표 · 면허 종료일이면 남은 기간 / 만료
                val txt = z.note.replace("¶", "\n")
                card.addView(Ui.text(a, 12.5f, Ui.TEXT, false, txt).apply { setLineSpacing(0f, 1.3f) }, Ui.lp(a, MATCH, WRAP, top = 4))
                Regex("~\\s*(\\d{4})-(\\d{2})-(\\d{2})").find(txt)?.let { m ->
                    val end = java.util.Calendar.getInstance().apply {
                        set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt(), 23, 59)
                    }.timeInMillis
                    val days = (end - System.currentTimeMillis()) / 86_400_000L
                    val (label, col) = when {
                        days < 0 -> "⏳ 자료상 종료일 지남 (자료 기준 연도 확인 · 재면허 됐을 수 있어요 · 문의처에 확인)" to Ui.SUB
                        days < 60 -> "⏳ 면허 끝나기 ${days}일 전" to Color.parseColor("#F2C14E")
                        days < 730 -> "✅ 면허 남은 기간 약 ${days / 30}개월" to Ui.GREEN
                        else -> "✅ 면허 남은 기간 약 ${days / 365}년 ${(days % 365) / 30}개월" to Ui.GREEN
                    }
                    card.addView(Ui.text(a, 12.5f, col, true, label), Ui.lp(a, MATCH, WRAP, top = 4))
                }
            }
            card.addView(Ui.text(a, 12.5f, Ui.TEXT, false, typeWarn(z.type)).apply { setLineSpacing(0f, 1.2f) }, Ui.lp(a, MATCH, WRAP, top = 6))
            if (Cloud.isAdmin && z.id.startsWith("k")) {
                card.addView(Ui.text(a, 11f, Ui.SUB, false, "(개방海 자료라 여기서 수정 · 삭제는 안 돼요)"), Ui.lp(a, MATCH, WRAP, top = 6))
            } else if (Cloud.isAdmin) {
                val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                r.addView(Ui.button(a, "✎ 수정") {
                    dlg?.dismiss()
                    askMeta(a, "구역 수정", z.type, z.name, z.note) { t, n, no ->
                        replaceZone(a, z, Zone(z.id, t, n, z.pts, z.center, z.radiusM, z.month, no)) { err ->
                            Ui.toast(a, err ?: "고쳤어요"); map?.invalidate()
                        }
                    }
                }.apply { minHeight = dp(40); textSize = 13f }, LinearLayout.LayoutParams(0, WRAP, 1f))
                r.addView(Ui.button(a, "🗑 삭제") {
                    AlertDialog.Builder(a, R.style.FlAlert)
                        .setTitle("이 구역을 지울까요?")
                        .setMessage("${TYPES[z.type]} ${z.name}")
                        .setPositiveButton("삭제") { _, _ ->
                            dlg?.dismiss()
                            replaceZone(a, z, null) { err -> Ui.toast(a, err ?: "지웠어요"); map?.invalidate() }
                        }.setNegativeButton("취소", null).show()
                }.apply { minHeight = dp(40); textSize = 13f; setTextColor(Color.parseColor("#FF8A92")) },
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
                card.addView(r, Ui.lp(a, MATCH, WRAP, top = 8))
            }
            col.addView(card, Ui.lp(a, MATCH, WRAP, top = 8))
        }
        col.addView(Ui.text(a, 11.5f, Color.parseColor("#F2C14E"), false,
            "※ 참고용이에요. 실제 경계 · 허용 여부는 현장 안내판 · 어촌계 · 지자체가 우선이에요. 낚시는 할 수 있어요."),
            Ui.lp(a, MATCH, WRAP, top = 10))
        dlg = AlertDialog.Builder(a, R.style.FlAlert)
            .setTitle("🦀 해루질 참고 구역")
            .setView(ScrollView(a).apply { addView(col) })
            .setPositiveButton("닫기", null).show()
    }

    /** 종류 · 이름 · 메모 입력 */
    private fun askMeta(a: Activity, title: String, type0: String, name0: String, note0: String, done: (String, String, String) -> Unit) {
        fun dp(v: Int) = Ui.dp(a, v)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
        var type = type0
        val chips = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        fun paintChips() {
            chips.removeAllViews()
            TYPES.forEach { (k, v) ->
                val on = k == type
                chips.addView(Ui.text(a, 14f, if (on) Color.parseColor("#1B0E02") else Ui.TEXT, true, (if (on) "✓ " else "") + typeIcon(k) + " " + v).apply {
                    gravity = Gravity.CENTER; setPadding(0, dp(9), 0, dp(9))
                    background = if (on) Ui.bg(a, typeColor(k), 12) else Ui.bg(a, Color.TRANSPARENT, 12, Ui.LINE)
                    setOnClickListener { type = k; paintChips() }
                }, Ui.lp(a, MATCH, WRAP, top = 6))
            }
        }
        paintChips()
        box.addView(chips)
        val name = EditText(a).apply { hint = "이름 (예: OO리 마을어장)"; setText(name0); inputType = InputType.TYPE_CLASS_TEXT; setSingleLine() }
        val note = EditText(a).apply { hint = "메모 (예: 어촌계 연락처 · 기간)"; setText(note0); inputType = InputType.TYPE_CLASS_TEXT; setSingleLine() }
        box.addView(name, Ui.lp(a, MATCH, WRAP, top = 10)); box.addView(note, Ui.lp(a, MATCH, WRAP, top = 4))
        AlertDialog.Builder(a, R.style.FlAlert)
            .setTitle(title)
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton("저장") { _, _ -> done(type, name.text.toString().trim(), note.text.toString().trim()) }
            .setNegativeButton("취소", null).show()
    }

    // ---------------- 관리자: 저장 ----------------
    private fun newId(manual: Boolean): String {
        val cs = "abcdefghijkmnpqrstuvwxyz23456789"
        return (if (manual) "m" else "c") + (1..7).map { cs.random() }.joinToString("")
    }

    /** 구역이 걸치는 칸들 */
    private fun cellsOfZone(z: Zone) = cellsFor(z.minLat, z.maxLat, z.minLon, z.maxLon, 16)

    /** 칸 하나를 통째로 다시 씀 (문서 여러 개로 쪼갬) */
    private fun writeCell(batch: com.google.firebase.firestore.WriteBatch, cell: String, zones: List<Zone>, oldDocs: Int): Int {
        val chunks = ArrayList<String>()
        val sb = StringBuilder()
        zones.forEach { z ->
            val line = z.encode()
            if (sb.length + line.length + 1 > CHUNK && sb.isNotEmpty()) { chunks.add(sb.toString()); sb.setLength(0) }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
        }
        if (sb.isNotEmpty()) chunks.add(sb.toString())
        chunks.forEachIndexed { k, s ->
            batch.set(db.collection(COL).document("${cell}_$k"), mapOf("cell" to cell, "k" to k, "z" to s, "at" to System.currentTimeMillis()))
        }
        for (k in chunks.size until oldDocs) batch.delete(db.collection(COL).document("${cell}_$k"))
        return chunks.size
    }

    /** 서버의 칸 몇 개를 읽어 옴 (관리자 수정용) → 칸 → (구역들, 문서 수) */
    private fun readCells(ids: List<String>, done: (Map<String, Pair<MutableList<Zone>, Int>>?) -> Unit) {
        if (ids.isEmpty()) return done(emptyMap())
        val out = HashMap<String, Pair<MutableList<Zone>, Int>>()
        ids.forEach { out[it] = mutableListOf<Zone>() to 0 }
        var left = (ids.size + 29) / 30
        var failed = false
        ids.chunked(30).forEach { part ->
            db.collection(COL).whereIn("cell", part).get()
                .addOnSuccessListener { q ->
                    q.documents.forEach { d ->
                        val id = d.getString("cell") ?: return@forEach
                        val cur = out[id] ?: (mutableListOf<Zone>() to 0)
                        (d.getString("z") ?: "").lines().mapNotNull { decodeLine(it) }.forEach { cur.first.add(it) }
                        out[id] = cur.first to cur.second + 1
                    }
                    left--; if (left == 0) done(if (failed) null else out)
                }
                .addOnFailureListener { failed = true; left--; if (left == 0) done(null) }
        }
    }

    /** 판 번호 올리기 + 종류별 개수 */
    private fun bumpVer(counts: Map<String, Int>?, delta: Map<String, Int> = emptyMap(), done: (String?) -> Unit) {
        val ver = System.currentTimeMillis()
        val ref = db.collection("haeruMeta").document("info")
        val fin = { err: String? ->
            if (err == null) { serverVer = ver; verAt = System.currentTimeMillis() }
            done(err)
        }
        if (counts != null) {
            ref.set(mapOf("ver" to ver, "counts" to counts), SetOptions.merge())
                .addOnSuccessListener { fin(null) }.addOnFailureListener { fin("저장 실패: ${it.message}") }
        } else {
            // 직접 그린 구역: 종류별 개수도 같이 올리고 내림 (관리 창 '몇 곳' 표시)
            val inc = HashMap<String, Any>()
            delta.filterValues { it != 0 }.forEach { (t, n) -> inc[t] = com.google.firebase.firestore.FieldValue.increment(n.toLong()) }
            ref.set(mapOf("ver" to ver, "counts" to inc), SetOptions.merge())
                .addOnSuccessListener { fin(null) }.addOnFailureListener { fin("저장 실패: ${it.message}") }
        }
    }

    /** 구역 하나 추가 / 수정 / 삭제 (new = null 이면 삭제) */
    fun replaceZone(a: Activity, old: Zone?, new: Zone?, done: (String?) -> Unit) {
        if (!Cloud.isAdmin) return done("관리자만 할 수 있어요")
        val ids = ((old?.let { cellsOfZone(it) } ?: emptyList()) + (new?.let { cellsOfZone(it) } ?: emptyList())).distinct()
        readCells(ids) { got ->
            if (got == null) return@readCells done("서버를 읽지 못했어요")
            val batch = db.batch()
            got.forEach { (cell, pair) ->
                val list = pair.first.filter { it.id != old?.id && it.id != new?.id }.toMutableList()
                if (new != null && cell in cellsOfZone(new)) list.add(new)
                writeCell(batch, cell, list, pair.second)
            }
            batch.commit().addOnSuccessListener {
                ids.forEach { cells.remove(it); File(dir(a), "$it.txt").delete() }
                val delta = HashMap<String, Int>()
                old?.let { delta[it.type] = (delta[it.type] ?: 0) - 1 }
                new?.let { delta[it.type] = (delta[it.type] ?: 0) + 1 }
                bumpVer(null, delta) { err ->
                    // 방금 저장한 판 번호를 폰에도 기억 → 다른 칸 기억은 그대로 두고 바뀐 칸만 다시 받음
                    if (err == null) prefs(a).edit().putLong("haeru_ver", serverVer).apply()
                    gen++
                    // 바뀐 칸을 바로 다시 받아 놓고 → 지도에 바로 보이게
                    ensure(a, ids) { gen++; main.post { done(err) } }
                }
            }.addOnFailureListener { done("저장 실패: ${it.message}") }
        }
    }

    // ---------------- 관리자 메뉴 ----------------
    const val PICK_CSV = 7301

    fun openAdmin(a: Activity) {
        if (!Cloud.isAdmin) return
        db.collection("haeruMeta").document("info").get().addOnCompleteListener { t ->
            @Suppress("UNCHECKED_CAST")
            val counts = ((if (t.isSuccessful) t.result else null)?.get("counts") as? Map<String, Any>)?.mapValues { (it.value as? Number)?.toInt() ?: 0 } ?: emptyMap()
            val total = counts.values.sum()
            val msg = if (total == 0) "아직 올린 구역이 없어요" else
                TYPES.entries.joinToString("\n") { "${typeIcon(it.key)} ${it.value}: ${counts[it.key] ?: 0}곳" } + "\n(올린 CSV 기준 · 직접 그린 구역 포함)"
            val items = arrayOf("📄 CSV 올리기 (공공데이터포털에서 받은 파일)", "✏ 지도에서 직접 그리기 · 고치기 · 지우기",
                "🗑 종류별로 CSV · API 구역 모두 지우기", "📌 어디서 데이터를 받나요?")
            // 안내 글 + 버튼 4개 (setMessage 와 setItems 를 같이 쓰면 버튼이 안 보여서 직접 만듦)
            fun dp(v: Int) = Ui.dp(a, v)
            val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), dp(4)) }
            box.addView(Ui.text(a, 13f, Ui.SUB, false, msg).apply { setLineSpacing(0f, 1.2f) })
            var dlg: AlertDialog? = null
            // v3.9 (158): 🌊 개방海 (자동 · 전국) 카드
            box.addView(khoaCard(a) { dlg?.dismiss() }, Ui.lp(a, MATCH, WRAP, top = 10))
            // v3.9 (155): 🌐 API로 받기 목록 (커튼)
            box.addView(apiCurtain(a) { dlg?.dismiss() }, Ui.lp(a, MATCH, WRAP, top = 8))
            items.forEachIndexed { i, label ->
                box.addView(Ui.button(a, label) {
                    dlg?.dismiss()
                    when (i) {
                        0 -> pickCsv(a)
                        1 -> openAdminMap(a)
                        2 -> askWipe(a)
                        3 -> sources(a)
                    }
                }.apply { textSize = 14f; minHeight = dp(48) }, Ui.lp(a, MATCH, WRAP, top = 8))
            }
            dlg = AlertDialog.Builder(a, R.style.FlAlert)
                .setTitle("🦀 해루질 참고 구역 · 관리")
                .setView(ScrollView(a).apply { addView(box) })
                .setNegativeButton("닫기", null).show()
        }
    }

    private fun sources(a: Activity) {
        AlertDialog.Builder(a, R.style.FlAlert)
            .setTitle("📌 데이터 받는 곳 (공공데이터포털 data.go.kr)")
            .setMessage("검색창에 아래 이름을 넣고 [다운로드] → 이 메뉴의 'CSV 올리기'\n\n" +
                "🐚🦪 해양수산부_공동활용체계_어장정보 (최신 연도) — 마을어업 · 양식 면허 어장 (종류 자동 구분)\n" +
                "🦪 해양수산부_공동활용체계_어업면허양식장\n" +
                "🐚 각 시·군 어업면허대장 (위도 · 경도 있는 것, 예: 부안군)\n" +
                "🐟 수산자원관리수면 · 수산자원보호구역 (시·도 고시, 좌표 있는 파일)\n" +
                "⚠ 해양경찰청_연안안전 예측분석모델 — 사고 위험 격자 (위험등급 골라서)\n" +
                "⚠ 해양수산부_공동활용체계_위험구역해수욕장 — 강한 조류 · 깊은 수심으로 출입 금지된 곳 ('모두 사고 위험 구역' → '더하기')\n\n" +
                "🌐 API로 받기: 활용신청이 승인된 API는 파일을 안 받아도 버튼 한 번으로 받아요 (앱에 들어 있는 공공데이터 인증키 사용). " +
                "다른 API는 '다른 API 주소'에 Swagger 화면의 /숫자/v1/uddi:… 부분을 붙여 넣어요\n\n" +
                "• 좌표가 없는 파일(주소만 있는 면허대장 등)은 쓸 수 없어요 → 지도에서 직접 그리기\n" +
                "• 같은 종류를 다시 올리면 그 종류의 예전 CSV 구역은 새것으로 바뀌고, 직접 그린 구역은 그대로예요")
            .setPositiveButton("확인", null).show()
    }

    private fun pickCsv(a: Activity) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel", "application/octet-stream"))
        }
        try {
            @Suppress("DEPRECATION")
            a.startActivityForResult(i, PICK_CSV)
        } catch (_: Exception) {
            @Suppress("DEPRECATION")
            a.startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }, PICK_CSV)
        }
    }

    private fun askWipe(a: Activity) {
        val keys = TYPES.keys.toList()
        AlertDialog.Builder(a, R.style.FlAlert)
            .setTitle("어떤 종류의 CSV 구역을 지울까요?")
            .setItems(keys.map { "${typeIcon(it)} ${TYPES[it]}" }.toTypedArray()) { _, i ->
                val t = keys[i]
                AlertDialog.Builder(a, R.style.FlAlert)
                    .setTitle("${TYPES[t]} CSV 구역을 모두 지울까요?")
                    .setMessage("직접 그린 구역은 남아요. 되돌릴 수 없어요.")
                    .setPositiveButton("지우기") { _, _ ->
                        val pd = progress(a, "지우는 중…")
                        replaceAll(a, setOf(t), emptyList()) { err -> pd.dismiss(); Ui.toast(a, err ?: "지웠어요") }
                    }.setNegativeButton("취소", null).show()
            }.setNegativeButton("취소", null).show()
    }

    private fun progress(a: Activity, msg: String): AlertDialog =
        AlertDialog.Builder(a, R.style.FlAlert).setMessage(msg).setCancelable(false).show()

    /** 이 종류들의 CSV 구역을 통째로 새것으로 (직접 그린 것은 그대로) */
    /** 마지막으로 올릴 때 중복이라 건너뛴 개수 */
    @Volatile var lastDupSkipped = 0

    private fun replaceAll(a: Activity, types: Set<String>, fresh: List<Zone>, done: (String?) -> Unit) {
        db.collection(COL).get().addOnSuccessListener { q ->
            val byCell = HashMap<String, MutableList<Zone>>()
            val docs = HashMap<String, Int>()
            q.documents.forEach { d ->
                val id = d.getString("cell") ?: return@forEach
                docs[id] = (docs[id] ?: 0) + 1
                val l = byCell.getOrPut(id) { mutableListOf() }
                (d.getString("z") ?: "").lines().mapNotNull { decodeLine(it) }.forEach { z ->
                    if (z.manual || z.type !in types) l.add(z)
                }
            }
            // v3.9.1: 이미 있는 것과 종류 · 모양이 같은 구역은 건너뜀 (같은 파일을 두 번 '더하기' 해도 중복 안 생김)
            fun sig(z: Zone) = z.type + "|" + (z.center?.let { String.format(Locale.US, "%.4f,%.4f", it.latitude, it.longitude) }
                ?: String.format(Locale.US, "%.4f,%.4f,%d", z.pts[0].latitude, z.pts[0].longitude, z.pts.size))
            val have = HashSet<String>()
            byCell.values.forEach { l -> l.forEach { have.add(sig(it)) } }
            lastDupSkipped = 0
            fresh.forEach { z ->
                if (!have.add(sig(z))) { lastDupSkipped++; return@forEach }
                cellsOfZone(z).forEach { byCell.getOrPut(it) { mutableListOf() }.add(z) }
            }
            // 종류별 개수 (중복 없이)
            val uniq = HashMap<String, Zone>()
            byCell.values.forEach { l -> l.forEach { uniq[it.id] = it } }
            val counts = TYPES.keys.associateWith { t -> uniq.values.count { it.type == t } }
            // 한 번에 400개씩 나눠 저장
            // 한 번에 보내는 양: 문서 400개 · 약 4MB 까지
            val cellsAll = (byCell.keys + docs.keys).distinct()
            val parts = ArrayList<List<String>>()
            var curPart = ArrayList<String>(); var curSize = 0; var curOps = 0
            cellsAll.forEach { cell ->
                val size = (byCell[cell] ?: emptyList()).sumOf { it.encode().length + 1 }
                val ops = size / CHUNK + 1 + (docs[cell] ?: 0)
                if (curPart.isNotEmpty() && (curSize + size > 4_000_000 || curOps + ops > 400)) { parts.add(curPart); curPart = ArrayList(); curSize = 0; curOps = 0 }
                curPart.add(cell); curSize += size; curOps += ops
            }
            if (curPart.isNotEmpty()) parts.add(curPart)
            var idx = 0
            fun next() {
                if (idx >= parts.size) {
                    cells.clear(); dir(a).listFiles()?.forEach { it.delete() }
                    bumpVer(counts) { err ->
                        if (err == null) prefs(a).edit().putLong("haeru_ver", serverVer).apply() else { verAt = 0L; serverVer = -1L }
                        gen++
                        main.post { done(err) }
                    }
                    return
                }
                val batch = db.batch()
                parts[idx].forEach { cell -> writeCell(batch, cell, byCell[cell] ?: emptyList(), docs[cell] ?: 0) }
                idx++
                batch.commit().addOnSuccessListener { next() }.addOnFailureListener { main.post { done("저장 실패: ${it.message}") } }
            }
            next()
        }.addOnFailureListener { done("서버를 읽지 못했어요: ${it.message}") }
    }

    // ---------------- CSV 읽기 ----------------
    /** MainActivity.onActivityResult 에서 부름 */
    fun onResult(a: Activity, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != PICK_CSV) return false
        val uri = data?.data ?: return true
        if (resultCode != Activity.RESULT_OK) return true
        val pd = progress(a, "파일 읽는 중…")
        Thread {
            val res = try { readCsv(a, uri) } catch (e: Exception) { null }
            main.post {
                pd.dismiss()
                if (res == null || res.rows.isEmpty()) { Ui.toast(a, "CSV를 읽지 못했어요 (쉼표로 나뉜 CSV 파일인지 확인)"); return@post }
                askImport(a, res)
            }
        }.start()
        return true
    }

    class Csv(val head: List<String>, val rows: List<List<String>>, val fileName: String)

    // ---------------- 🌐 공공데이터포털 API로 받기 (v3.8) ----------------
    /** 해양수산부_어장정보 (2023.06.02) — 활용신청 승인됨 */
    const val FARM_API = "/15114195/v1/uddi:1899391e-d671-4438-b3a4-ed0f547fdc52"
    private fun apiPrefs(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)

    // ---- v3.9 (155) API 목록: 이름 · 주소 · 종류 (폰에 저장) ----
    class ApiItem(val name: String, val path: String, val type: String)
    private fun apis(c: Context): MutableList<ApiItem> = try {
        val arr = JSONArray(apiPrefs(c).getString("haeru_apis", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> ApiItem(o.optString("n"), o.optString("p"), o.optString("t", "auto")) } }.toMutableList()
    } catch (_: Exception) { mutableListOf() }
    private fun saveApis(c: Context, l: List<ApiItem>) {
        val arr = JSONArray(); l.forEach { arr.put(JSONObject().put("n", it.name).put("p", it.path).put("t", it.type)) }
        apiPrefs(c).edit().putString("haeru_apis", arr.toString()).apply()
    }
    private fun typeLabel(t: String) = if (t == "auto") "🤖 자동" else "${typeIcon(t)} ${TYPES[t] ?: t}"

    private fun apiCurtain(a: Activity, close: () -> Unit): View {
        fun dp(v: Int) = Ui.dp(a, v)
        val card = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = Ui.bg(a, Color.TRANSPARENT, 14, Ui.BLUE) }
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)) }
        val title = Ui.text(a, 14.5f, Ui.TEXT, true, "")
        val arrow = Ui.text(a, 14f, Ui.SUB, true, "▾")
        head.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f)); head.addView(arrow)
        val inner = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(10)); visibility = View.GONE }
        card.addView(head); card.addView(inner)
        fun paint() {
            val l = apis(a)
            title.text = "🌐 API로 받기 (${l.size}개)"
            inner.removeAllViews()
            l.forEachIndexed { idx, it0 ->
                val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
                val tb = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                tb.addView(Ui.text(a, 14f, Ui.TEXT, true, it0.name).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                tb.addView(Ui.text(a, 11f, Ui.SUB, false, typeLabel(it0.type) + " · " + it0.path.take(28) + if (it0.path.length > 28) "…" else "").apply { maxLines = 1 })
                r.addView(tb, LinearLayout.LayoutParams(0, WRAP, 1f))
                r.addView(Ui.chip(a, "받기").apply { setOnClickListener { close(); fetchApi(a, it0.path, it0.name, it0.type) } })
                r.addView(Ui.text(a, 16f, Ui.TEXT, true, "✎").apply { setPadding(dp(10), dp(4), dp(6), dp(4)); setOnClickListener {
                    editApi(a, it0) { ni -> val all = apis(a); all[idx] = ni; saveApis(a, all); paint() } } })
                r.addView(Ui.text(a, 16f, Color.parseColor("#FF8A92"), true, "✕").apply { setPadding(dp(6), dp(4), 0, dp(4)); setOnClickListener {
                    AlertDialog.Builder(a, R.style.FlAlert).setTitle("'${it0.name}'을(를) 목록에서 지울까요?")
                        .setMessage("목록에서만 지워져요. 이미 올린 구역은 그대로예요.")
                        .setPositiveButton("지우기") { _, _ -> val all = apis(a); all.removeAt(idx); saveApis(a, all); paint() }
                        .setNegativeButton("취소", null).show() } })
                inner.addView(r)
            }
            if (l.isEmpty()) inner.addView(Ui.text(a, 12f, Ui.SUB, false, "아직 없어요 · 공공데이터포털 Swagger 화면의 /숫자/v1/uddi:… 주소로 추가해요"))
            inner.addView(Ui.text(a, 14f, Ui.BLUE, true, "＋ API 추가 (이름 · 주소 · 종류)").apply {
                gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10))
                background = Ui.bg(a, Color.TRANSPARENT, 12, Ui.BLUE)
                setOnClickListener { editApi(a, null) { ni -> val all = apis(a); all.add(ni); saveApis(a, all); paint() } }
            }, Ui.lp(a, MATCH, WRAP, top = 6))
        }
        paint()
        head.setOnClickListener {
            val open = inner.visibility != View.VISIBLE
            inner.visibility = if (open) View.VISIBLE else View.GONE; arrow.text = if (open) "▴" else "▾"
        }
        return card
    }

    private fun editApi(a: Activity, old: ApiItem?, done: (ApiItem) -> Unit) {
        fun dp(v: Int) = Ui.dp(a, v)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
        val name = EditText(a).apply { hint = "이름 (예: 위험구역 해수욕장)"; setSingleLine(); setText(old?.name ?: "") }
        val path = EditText(a).apply { hint = "/숫자/v1/uddi:…"; setSingleLine(); setText(old?.path ?: ""); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        box.addView(name); box.addView(path, Ui.lp(a, MATCH, WRAP, top = 4))
        box.addView(Ui.text(a, 12.5f, Ui.SUB, true, "종류"), Ui.lp(a, MATCH, WRAP, top = 10))
        var type = old?.type ?: "auto"
        val chips = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        fun paintChips() {
            chips.removeAllViews()
            (listOf("auto") + TYPES.keys).forEach { k ->
                val on = k == type
                chips.addView(Ui.text(a, 13.5f, if (on) Color.parseColor("#1B0E02") else Ui.TEXT, true, (if (on) "✓ " else "") + typeLabel(k)).apply {
                    gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8))
                    background = if (on) Ui.bg(a, if (k == "auto") Ui.BLUE else typeColor(k), 12) else Ui.bg(a, Color.TRANSPARENT, 12, Ui.LINE)
                    setOnClickListener { type = k; paintChips() }
                }, Ui.lp(a, MATCH, WRAP, top = 5))
            }
        }
        paintChips(); box.addView(chips)
        AlertDialog.Builder(a, R.style.FlAlert)
            .setTitle(if (old == null) "＋ API 추가" else "✎ API 수정")
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton("저장") { _, _ ->
                var p = path.text.toString().trim()
                p = p.substringAfter("api.odcloud.kr/api", p).substringBefore("?")
                if (!p.startsWith("/")) p = "/$p"
                if (!p.contains("/v1/") && !p.contains("uddi")) { Ui.toast(a, "주소 모양이 달라요 (/숫자/v1/uddi:… 형식)"); return@setPositiveButton }
                if (apis(a).any { it.path == p && it.path != old?.path }) { Ui.toast(a, "이미 목록에 있는 API 주소예요"); return@setPositiveButton }
                done(ApiItem(name.text.toString().trim().ifBlank { p.split("/").getOrNull(1) ?: "API" }, p, type))
            }
            .setNegativeButton("취소", null).show()
    }

    /** 🌊 개방海 카드: 인증키 넣기 · 연결 확인 */
    private fun khoaCard(a: Activity, close: () -> Unit): View {
        fun dp(v: Int) = Ui.dp(a, v)
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
            background = Ui.bg(a, Color.parseColor("#102A3A"), 14, Ui.GREEN)
        }
        val has = Khoa.key(a).isNotBlank()
        card.addView(Ui.text(a, 14.5f, Ui.GREEN, true, "🌊 개방海 자동 받기 (전국)"))
        card.addView(Ui.text(a, 12f, Ui.SUB, false,
            (if (has) "✅ 인증키 들어 있음 · " else "⚠ 인증키가 아직 없어요 · ") +
            "어장정보(23년, 면허기간) · 양식장 · 갯골 · 웅덩이 · 인명사고 · 낚시금지/제한 · 수중레저금지를 지도에 보이는 곳만 그때그때 받아요 (파일 올리기 필요 없음)").apply { setLineSpacing(0f, 1.2f) },
            Ui.lp(a, MATCH, WRAP, top = 4))
        val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        r.addView(Ui.button(a, "🔑 인증키 넣기") {
            val et = EditText(a).apply { hint = "개방海 '해양공간정보' 인증키"; setSingleLine(); setText(Khoa.key(a)) }
            AlertDialog.Builder(a, R.style.FlAlert)
                .setTitle("🔑 개방海 인증키")
                .setMessage("khoa.go.kr → 오픈 API 신청 → 사용구분 '해양공간정보'의 인증키를 붙여 넣어요 (해아름 키 말고). 모든 사용자가 같이 써요.")
                .setView(LinearLayout(a).apply { setPadding(dp(20), 0, dp(20), 0); addView(et, LinearLayout.LayoutParams(MATCH, WRAP)) })
                .setPositiveButton("저장") { _, _ ->
                    Khoa.saveKey(a, et.text.toString()) { err -> Ui.toast(a, err ?: "저장했어요 · '연결 확인'을 눌러 보세요"); if (err == null) { close(); openAdmin(a) } }
                }.setNegativeButton("취소", null).show()
        }.apply { textSize = 13f; minHeight = dp(42) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(Ui.button(a, "🔌 연결 확인") {
            Ui.toast(a, "개방海에 물어보는 중…")
            Khoa.test(a) { msg ->
                AlertDialog.Builder(a, R.style.FlAlert).setTitle("🌊 개방海 연결 확인")
                    .setMessage(msg + "\n\n(❌가 나오면 이 화면을 캡처해서 보내 주세요)").setPositiveButton("확인", null).show()
            }
        }.apply { textSize = 13f; minHeight = dp(42) }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        card.addView(r, Ui.lp(a, MATCH, WRAP, top = 8))
        // v3.9.3 (170): 1인당 미리 받기 제한 (관리자는 제한 없음)
        val lim = Ui.button(a, "📏 1인당 미리 받기 제한: 하루 ${Khoa.dayLimit(a)}지역 · 최대 ${Khoa.maxRegions(a)}곳") {}.apply { textSize = 13f; minHeight = dp(42) }
        lim.setOnClickListener {
            val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), 0) }
            fun num(h: String, v: Int) = EditText(a).apply { hint = h; setText(v.toString()); inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine() }
            box.addView(Ui.text(a, 12.5f, Ui.SUB, false, "하루에 미리 받을 수 있는 지역 수"))
            val d1 = num("예: 10", Khoa.dayLimit(a)); box.addView(d1)
            box.addView(Ui.text(a, 12.5f, Ui.SUB, false, "한 사람이 저장해 둘 수 있는 지역 수"), Ui.lp(a, MATCH, WRAP, top = 8))
            val d2 = num("예: 20", Khoa.maxRegions(a)); box.addView(d2)
            AlertDialog.Builder(a, R.style.FlAlert).setTitle("📏 1인당 미리 받기 제한")
                .setView(box)
                .setPositiveButton("저장") { _, _ ->
                    val x = d1.text.toString().toIntOrNull()?.coerceIn(1, 999) ?: Khoa.dayLimit(a)
                    val y = d2.text.toString().toIntOrNull()?.coerceIn(1, 999) ?: Khoa.maxRegions(a)
                    Khoa.saveLimits(a, x, y) { err ->
                        Ui.toast(a, err ?: "저장했어요 · 하루 ${x}지역 · 최대 ${y}곳")
                        lim.text = "📏 1인당 미리 받기 제한: 하루 ${Khoa.dayLimit(a)}지역 · 최대 ${Khoa.maxRegions(a)}곳"
                    }
                }
                .setNeutralButton("기본값 (10 · 20)") { _, _ ->
                    Khoa.saveLimits(a, 10, 20) { err -> Ui.toast(a, err ?: "기본값으로 돌렸어요"); lim.text = "📏 1인당 미리 받기 제한: 하루 10지역 · 최대 20곳" }
                }
                .setNegativeButton("취소", null).show()
        }
        card.addView(lim, Ui.lp(a, MATCH, WRAP, top = 8))
        // v3.9.4 (174 · 175): 갯벌체험지수 · 바다낚시지수 연결 확인 · 주소 바꾸기
        val ir = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        ir.addView(Ui.button(a, "📈 지수 연결 확인") {
            Ui.toast(a, "갯벌체험지수 · 바다낚시지수 물어보는 중…")
            Idx.test(a) { msg ->
                AlertDialog.Builder(a, R.style.FlAlert).setTitle("📈 지수 API 연결 확인")
                    .setMessage(msg + "\n\n(❌가 나오면 이 화면을 캡처해서 보내 주세요)").setPositiveButton("확인", null).show()
            }
        }.apply { textSize = 13f; minHeight = dp(42) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        ir.addView(Ui.button(a, "✎ 지수 주소") {
            val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), 0) }
            box.addView(Ui.text(a, 12f, Ui.SUB, false, "Swagger 화면의 요청 주소 (? 앞까지) · 비우면 기본값"))
            box.addView(Ui.text(a, 12.5f, Ui.TEXT, true, "바다낚시지수"), Ui.lp(a, MATCH, WRAP, top = 6))
            val f1 = EditText(a).apply { setText(Idx.fishUrl(a)); textSize = 12f }
            box.addView(f1)
            box.addView(Ui.text(a, 12.5f, Ui.TEXT, true, "갯벌체험지수"), Ui.lp(a, MATCH, WRAP, top = 6))
            val f2 = EditText(a).apply { setText(Idx.mudUrl(a)); textSize = 12f }
            box.addView(f2)
            AlertDialog.Builder(a, R.style.FlAlert).setTitle("✎ 지수 API 주소")
                .setView(box)
                .setPositiveButton("저장") { _, _ -> Idx.saveUrls(a, f1.text.toString(), f2.text.toString()); Ui.toast(a, "저장했어요 · '📈 지수 연결 확인'으로 확인해 보세요") }
                .setNeutralButton("기본값") { _, _ -> Idx.saveUrls(a, "", ""); Ui.toast(a, "기본 주소로 돌렸어요") }
                .setNegativeButton("취소", null).show()
        }.apply { textSize = 13f; minHeight = dp(42) }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        card.addView(ir, Ui.lp(a, MATCH, WRAP, top = 8))
        return card
    }

    private fun askApiPath(a: Activity) {
        fun dp(v: Int) = Ui.dp(a, v)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
        box.addView(Ui.text(a, 12.5f, Ui.SUB, false,
            "공공데이터포털 → 마이페이지 → 활용 신청한 API → Swagger 화면의 GET 옆 주소를 그대로 붙여 넣어요\n예: /15114195/v1/uddi:1899391e-…").apply { setLineSpacing(0f, 1.2f) })
        val path = EditText(a).apply {
            hint = "/숫자/v1/uddi:…"; setSingleLine(); setText(apiPrefs(a).getString("haeru_api_path", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        box.addView(path, Ui.lp(a, MATCH, WRAP, top = 8))
        AlertDialog.Builder(a, R.style.FlAlert)
            .setTitle("🌐 다른 API 주소로 받기")
            .setView(box)
            .setPositiveButton("받기") { _, _ ->
                var p = path.text.toString().trim()
                p = p.substringAfter("api.odcloud.kr/api", p).substringBefore("?")
                if (!p.startsWith("/")) p = "/$p"
                if (!p.contains("/v1/") && !p.contains("uddi")) { Ui.toast(a, "주소 모양이 달라요 (/숫자/v1/uddi:… 형식)"); return@setPositiveButton }
                apiPrefs(a).edit().putString("haeru_api_path", p).apply()
                fetchApi(a, p, p.split("/").getOrNull(1) ?: "API")
            }
            .setNegativeButton("취소", null).show()
    }

    /** 페이지마다 1000줄씩 받아서 CSV와 같은 모양으로 → 그다음은 CSV 올리기와 똑같이 (종류 고르기 · 확인 · 바꾸기/더하기) */
    fun fetchApi(a: Activity, path: String, label: String, preset: String? = null) {
        val key = BuildConfig.KHOA_KEY
        if (key.isBlank()) { Ui.toast(a, "앱에 공공데이터 인증키가 없어요 (GitHub 비밀값 KHOA_KEY)"); return }
        val pd = progress(a, "🌐 $label 받는 중…")
        Thread {
            var err: String? = null
            val head = LinkedHashSet<String>()
            val maps = ArrayList<JSONObject>()
            try {
                var page = 1
                var total = Int.MAX_VALUE
                while (maps.size < total && page <= 60) {
                    val url = "https://api.odcloud.kr/api$path?page=$page&perPage=1000&returnType=JSON&serviceKey=" +
                        java.net.URLEncoder.encode(key, "UTF-8")
                    val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 15_000; c.readTimeout = 60_000
                    val code = c.responseCode
                    val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                    if (code !in 200..299) { err = "API 오류 $code: " + body.take(200); break }
                    val o = JSONObject(body)
                    total = o.optInt("totalCount", o.optInt("matchCount", 0))
                    val arr = o.optJSONArray("data") ?: JSONArray()
                    if (arr.length() == 0) break
                    for (i in 0 until arr.length()) {
                        val r = arr.getJSONObject(i)
                        r.keys().forEach { head.add(it) }
                        maps.add(r)
                    }
                    page++
                    main.post { pd.setMessage("🌐 $label 받는 중… ${maps.size} / ${if (total == Int.MAX_VALUE) "?" else total}") }
                }
            } catch (e: Exception) { err = "받지 못했어요: ${e.message}" }
            val h = head.toList()
            val rows = maps.map { m -> h.map { k -> if (m.isNull(k)) "" else m.opt(k)?.toString() ?: "" } }
            main.post {
                pd.dismiss()
                if (rows.isEmpty()) {
                    AlertDialog.Builder(a, R.style.FlAlert)
                        .setTitle("🌐 받지 못했어요")
                        .setMessage((err ?: "받은 자료가 0줄이에요") + "\n\n• 활용신청이 '승인'인지 · 승인 뒤 1~2시간 지났는지 확인해 주세요\n• 이 화면을 캡처해서 보내 주시면 고칠게요")
                        .setPositiveButton("확인", null).show()
                    return@post
                }
                askImport(a, Csv(h, rows, "🌐 $label"), preset)
            }
        }.start()
    }

    private fun readCsv(a: Activity, uri: Uri): Csv {
        val bytes = a.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        var text = String(bytes, Charsets.UTF_8)
        if (text.count { it == '�' } > 3) text = String(bytes, charset("MS949"))
        text = text.removePrefix("﻿")
        val rows = parseCsv(text)
        var name = "파일"
        try {
            a.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
            }
        } catch (_: Exception) {}
        return Csv(rows.firstOrNull()?.map { it.trim() } ?: emptyList(), rows.drop(1).filter { r -> r.any { it.isNotBlank() } }, name)
    }

    /** 따옴표 안의 쉼표 · 줄바꿈까지 처리하는 CSV */
    fun parseCsv(s: String): List<List<String>> {
        val out = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cur = StringBuilder()
        var q = false
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (q) {
                if (ch == '"') { if (i + 1 < s.length && s[i + 1] == '"') { cur.append('"'); i++ } else q = false }
                else cur.append(ch)
            } else when (ch) {
                '"' -> q = true
                ',' -> { row.add(cur.toString()); cur.setLength(0) }
                '\n' -> { row.add(cur.toString()); cur.setLength(0); out.add(row); row = ArrayList() }
                '\r' -> {}
                else -> cur.append(ch)
            }
            i++
        }
        if (cur.isNotEmpty() || row.isNotEmpty()) { row.add(cur.toString()); out.add(row) }
        return out
    }

    private fun col(head: List<String>, vararg keys: String): Int {
        keys.forEach { k -> head.indexOfFirst { it.equals(k, true) }.let { if (it >= 0) return it } }
        keys.forEach { k -> head.indexOfFirst { it.contains(k, true) }.let { if (it >= 0) return it } }
        return -1
    }

    private fun askImport(a: Activity, csv: Csv, preset: String? = null) {
        val h = csv.head
        val cGrade = col(h, "위험등급", "등급")
        // v3.8.1: 칸 이름 말고 값으로 확인 (도형 칸 또는 위경도 · 평면좌표를 읽을 수 있는 줄이 하나라도 있는지)
        val loc = locator(csv)
        if (geomCol(csv) < 0 && sampleRows(csv).none { loc(it) != null }) {
            AlertDialog.Builder(a, R.style.FlAlert)
                .setTitle("좌표를 찾지 못했어요")
                .setMessage("도형(공간정보)이나 위도 · 경도 값을 읽을 수 있는 칸이 없어요.\n이 화면을 캡처해서 보내 주시면 맞춰 고칠게요 ↓ 받은 자료 첫 줄\n\n" + sampleText(csv))
                .setPositiveButton("확인", null).show()
            return
        }
        val keys = listOf("auto") + TYPES.keys
        val labels = listOf("🤖 자동 (어업면허명 · 어업방법으로 마을어장 · 양식장 · 기타 구분)") + TYPES.entries.map { "${typeIcon(it.key)} 모두 ${it.value}" }
        fun go(type: String) {
                if (cGrade >= 0) {
                    val vals = csv.rows.mapNotNull { it.getOrNull(cGrade)?.trim()?.takeIf { v -> v.isNotBlank() } }.groupingBy { it }.eachCount()
                        .entries.sortedBy { it.key }.take(12)
                    val names = vals.map { "${it.key} (${it.value})" }.toTypedArray()
                    val checked = BooleanArray(vals.size) { idx -> vals[idx].key.contains("높") || vals[idx].key.contains("위험") || vals[idx].key == vals.last().key }
                    AlertDialog.Builder(a, R.style.FlAlert)
                        .setTitle("어느 위험등급만 넣을까요?")
                        .setMultiChoiceItems(names, checked) { _, w, on -> checked[w] = on }
                        .setPositiveButton("다음") { _, _ ->
                            val keep = vals.filterIndexed { idx, _ -> checked[idx] }.map { it.key }.toSet()
                            build(a, csv, type, keep)
                        }.setNegativeButton("취소", null).show()
                } else build(a, csv, type, null)
        }
        // v3.9 (155): API 목록에 종류를 정해 둔 경우엔 바로
        if (preset != null && preset in keys) return go(preset)
        AlertDialog.Builder(a, R.style.FlAlert)
            .setTitle("어떤 구역인가요? (${csv.rows.size}줄)")
            .setItems(labels.toTypedArray()) { _, i -> go(keys[i]) }
            .setNegativeButton("취소", null).show()
    }

    private fun build(a: Activity, csv: Csv, type: String, keepGrades: Set<String>?) {
        val pd = progress(a, "구역 만드는 중…")
        Thread {
            val r = try { convert(csv, type, keepGrades) } catch (e: Exception) { null }
            main.post {
                pd.dismiss()
                if (r == null) { Ui.toast(a, "변환하지 못했어요"); return@post }
                val (zones, skipped) = r
                val by = zones.groupingBy { it.type }.eachCount()
                if (zones.isEmpty()) {
                    AlertDialog.Builder(a, R.style.FlAlert)
                        .setTitle("그릴 수 있는 구역이 없어요")
                        .setMessage("못 쓴 줄 ${skipped}개 (좌표가 비었거나 한국 밖)\n\n이 화면을 캡처해서 보내 주시면 맞춰 고칠게요 ↓ 받은 자료 첫 줄\n\n" + sampleText(csv))
                        .setPositiveButton("확인", null).show()
                    return@post
                }
                val types = if (type == "auto") setOf("village", "farm", "etc") else setOf(type)
                AlertDialog.Builder(a, R.style.FlAlert)
                    .setTitle("서버에 올릴까요?")
                    .setMessage(by.entries.joinToString("\n") { "${typeIcon(it.key)} ${TYPES[it.key]}: ${it.value}곳" } +
                        "\n못 쓴 줄: ${skipped}개" +
                        "\n\n• 바꾸기: 이 종류의 예전 CSV · API 구역을 지우고 이걸로 (직접 그린 구역은 그대로)" +
                        "\n• 더하기: 예전 구역은 그대로 두고 이걸 더함 (다른 자료를 합칠 때, 예: 위험구역 해수욕장 + 사고 위험 격자)")
                    .setPositiveButton("바꾸기") { _, _ ->
                        val p2 = progress(a, "올리는 중… (구역이 많으면 1~2분)")
                        replaceAll(a, types, zones) { err -> p2.dismiss(); Ui.toast(a, err ?: "올렸어요 · ${zones.size}곳") }
                    }
                    .setNeutralButton("더하기") { _, _ ->
                        val p2 = progress(a, "올리는 중… (구역이 많으면 1~2분)")
                        replaceAll(a, emptySet(), zones) { err -> p2.dismiss()
                            Ui.toast(a, err ?: ("더했어요 · ${zones.size - lastDupSkipped}곳" + if (lastDupSkipped > 0) " · 이미 있는 ${lastDupSkipped}곳은 뺐어요" else "")) }
                    }
                    .setNegativeButton("취소", null).show()
            }
        }.start()
    }

    private fun autoType(text: String): String = when {
        text.contains("마을") -> "village"
        text.contains("양식") -> "farm"
        text.contains("자원관리") || text.contains("보호수면") || text.contains("자원보호") -> "res"
        else -> "etc"
    }

    /** CSV 줄들 → 구역들 · 못 쓴 줄 수 */
    private fun convert(csv: Csv, type: String, keepGrades: Set<String>?): Pair<List<Zone>, Int> {
        val h = csv.head
        // v3.8.1: 칸 이름만 보지 않고 '값'을 보고 좌표 칸을 찾음 (도·분·초 글자 · 도/분/초 따로 칸 · X/Y 평면좌표)
        val cGeo = geomCol(csv)
        val locate = locator(csv)
        val cArea = col(h, "면적", "area")
        val cMonth = h.indexOfFirst { it.trim() == "월" || it.trim().equals("month", true) }
        val cGrade = col(h, "위험등급", "등급")
        val cKind = listOf(col(h, "어업면허명"), col(h, "어업방법명"), col(h, "어업종류"), col(h, "면허종류"), col(h, "구분"), col(h, "종류")).filter { it >= 0 }
        val cName = listOf(col(h, "어장위치설명"), col(h, "해양공간객체한글명"), col(h, "명칭"), col(h, "이름"), col(h, "어장명"), col(h, "수면명"), col(h, "구역명"), col(h, "name"), col(h, "소재지"), col(h, "위치")).firstOrNull { it >= 0 } ?: -1
        val cNote = listOf(col(h, "양식대상종명"), col(h, "어종"), col(h, "어장면허종료일자"), col(h, "면허기간"), col(h, "기간")).filter { it >= 0 }
        val cSigungu = col(h, "시군구명", "시군구")
        val cGrid = col(h, "격자번호")

        val out = ArrayList<Zone>()
        var skipped = 0
        csv.rows.forEach { row ->
            fun v(i: Int) = if (i >= 0) row.getOrNull(i)?.trim() ?: "" else ""
            if (keepGrades != null && cGrade >= 0 && v(cGrade) !in keepGrades) return@forEach
            val kindText = cKind.joinToString(" ") { v(it) }
            val t = if (type == "auto") autoType(kindText) else type
            var name = v(cName).ifBlank { v(cSigungu) }
            if (t == "risk" && name.isBlank()) name = "위험등급 " + v(cGrade)
            if (cGrid >= 0 && t == "risk" && v(cGrade).isNotBlank()) name = "위험등급 " + v(cGrade)
            // v3.9 (156): 이름표 붙인 자세한 정보 (¶ = 줄바꿈)
            val nl = ArrayList<String>()
            val cS = col(h, "면허시작일자", "시작일"); val cE = col(h, "면허종료일자", "종료일")
            if (v(cS).isNotBlank() || v(cE).isNotBlank()) nl.add("📅 면허기간 ${v(cS)} ~ ${v(cE)}")
            nl.add(listOfNotNull(v(col(h, "면허번호")).takeIf { it.isNotBlank() }?.let { "🔖 면허번호 $it" },
                v(cArea).takeIf { it.isNotBlank() }?.let { "📐 면적 $it" }).joinToString(" · "))
            nl.add(listOf(kindText, v(col(h, "어업방법명")), v(col(h, "양식대상종명"))).flatMap { it.split(" ") }.filter { it.isNotBlank() }.distinct().joinToString(" ").let { if (it.isBlank()) "" else "🎣 $it" })
            nl.add(listOf(v(col(h, "관련부서명")), v(cSigungu)).filter { it.isNotBlank() }.joinToString(" · ").let { if (it.isBlank()) "" else "🏛 $it (문의처)" })
            v(col(h, "개정년도")).takeIf { it.isNotBlank() }?.let { nl.add("자료 $it") }
            val note = nl.filter { it.isNotBlank() }.joinToString("¶").take(300)
            val month = if (cMonth >= 0) (v(cMonth).filter { it.isDigit() }.toIntOrNull() ?: 0).let { if (it in 1..12) it else 0 } else 0
            val ref = locate(row) ?: (if (cGeo >= 0) pointOf(v(cGeo)) else null)
            val refLat = ref?.first; val refLon = ref?.second
            val rings = if (cGeo >= 0) parseGeom(v(cGeo), refLat, refLon) else emptyList()
            if (rings.isNotEmpty()) {
                rings.forEach { ring ->
                    val simp = simplify(ring, 0.00003).let { if (it.size > 400) simplify(ring, 0.0002) else it }
                    if (simp.size >= 3 && inKorea(simp[0])) out.add(Zone(newId(false), t, name.take(40), simp, null, 0.0, month, note))
                    else skipped++
                }
            } else if (refLat != null && refLon != null && inKorea(GeoPoint(refLat, refLon))) {
                // 점만 있으면: 면적으로 원 (면적을 모르면 100m)
                var area = v(cArea).replace(",", "").toDoubleOrNull() ?: 0.0
                if (area in 0.0001..500.0) area *= 10_000.0   // ha 로 보임
                val r = if (area > 0) sqrt(area / Math.PI).coerceIn(30.0, 3000.0) else 100.0
                out.add(Zone(newId(false), t, name.take(40), emptyList(), GeoPoint(refLat, refLon), r, month, note))
            } else skipped++
        }
        return out to skipped
    }

    private fun inKorea(g: GeoPoint) = g.latitude in 32.5..39.5 && g.longitude in 123.5..132.5

    // ---------------- v3.8.1 좌표 칸 찾기 ----------------
    private val NUM = Regex("[0-9]+(?:\\.[0-9]+)?")
    /** '34.6368' · '34-38-12.5' · '34°38'12"' · 'N34 38 12' · '34도 38분 12초' → 소수 도 */
    fun parseCoord(s0: String?): Double? {
        val s = s0?.trim()?.replace(",", "") ?: return null
        if (s.isEmpty()) return null
        s.toDoubleOrNull()?.let { return it }
        val n = NUM.findAll(s).map { it.value.toDouble() }.toList()
        return when {
            n.isEmpty() -> null
            n.size == 1 -> n[0]
            n.size == 2 -> if (n[1] < 60) n[0] + n[1] / 60.0 else null
            else -> if (n[1] < 60 && n[2] < 60) n[0] + n[1] / 60.0 + n[2] / 3600.0 else null
        }
    }

    private fun sampleRows(csv: Csv): List<List<String>> =
        if (csv.rows.size <= 300) csv.rows else (0 until 300).map { csv.rows[it * csv.rows.size / 300] }

    /** 공간정보(도형) 칸: 값에 POLYGON · POINT · {"type" 이 들어 있는 칸 */
    private fun geomCol(csv: Csv): Int {
        val rows = sampleRows(csv)
        var best = -1; var bestN = 0
        csv.head.indices.forEach { i ->
            val n = rows.count { r -> val v = r.getOrNull(i)?.trim()?.uppercase(Locale.US) ?: ""
                v.contains("POLYGON") || v.contains("POINT") || v.startsWith("{\"TYPE") || v.startsWith("{\"GEOMETRY") || v.contains("\"COORDINATES\"") }
            if (n > bestN) { bestN = n; best = i }
        }
        return if (bestN > 0) best else -1
    }

    /** 한 줄에서 위도·경도(소수 도)를 꺼내는 함수 · 못 찾으면 null 돌려줌 */
    private fun locator(csv: Csv): (List<String>) -> Pair<Double, Double>? {
        val h = csv.head.map { it.trim() }
        val rows = sampleRows(csv)
        fun v(r: List<String>, i: Int) = r.getOrNull(i)?.trim() ?: ""
        // ① 도 · 분 · 초가 따로 있는 칸 (예: 위도_도, 위도_분, 위도_초)
        fun parts(key: List<String>): Triple<Int, Int, Int>? {
            val cols = h.indices.filter { i -> key.any { h[i].contains(it, true) } }
            if (cols.size < 2) return null
            val mi = cols.firstOrNull { h[it].contains("분") || h[it].contains("min", true) } ?: return null
            val si = cols.firstOrNull { h[it].contains("초") || h[it].contains("sec", true) } ?: -1
            val di = cols.firstOrNull { it != mi && it != si } ?: return null
            return Triple(di, mi, si)
        }
        val latP = parts(listOf("위도", "lat")); val lonP = parts(listOf("경도", "lon", "lng"))
        if (latP != null && lonP != null) {
            fun dms(r: List<String>, p: Triple<Int, Int, Int>): Double? {
                val d = parseCoord(v(r, p.first)) ?: return null
                val m = parseCoord(v(r, p.second)) ?: 0.0
                val sec = if (p.third >= 0) parseCoord(v(r, p.third)) ?: 0.0 else 0.0
                return d + m / 60.0 + sec / 3600.0
            }
            val f = { r: List<String> -> val la = dms(r, latP); val lo = dms(r, lonP); if (la != null && lo != null) la to lo else null }
            if (rows.count { f(it)?.let { p -> inKorea(GeoPoint(p.first, p.second)) } == true } > rows.size / 5) return f
        }
        // ② 값이 위도 범위(32.5~39.5) · 경도 범위(123.5~132.5)인 칸 (이름에 위도·경도가 있으면 가산점)
        fun score(i: Int, lo: Double, hi: Double, names: List<String>): Double {
            val ok = rows.count { r -> parseCoord(v(r, i))?.let { it in lo..hi } == true }
            if (ok == 0) return 0.0
            val bonus = if (names.any { h[i].contains(it, true) }) 1.5 else 1.0
            val penalty = if (h[i].contains("최소") || h[i].contains("최대") || h[i].contains("min", true) || h[i].contains("max", true)) 0.5 else 1.0
            return ok * bonus * penalty
        }
        val latC = h.indices.maxByOrNull { score(it, 32.5, 39.5, listOf("위도", "lat")) } ?: -1
        val lonC = h.indices.filter { it != latC }.maxByOrNull { score(it, 123.5, 132.5, listOf("경도", "lon", "lng")) } ?: -1
        if (latC >= 0 && lonC >= 0 && score(latC, 32.5, 39.5, listOf("위도")) > 0 && score(lonC, 123.5, 132.5, listOf("경도")) > 0) {
            return { r -> val la = parseCoord(v(r, latC)); val lo = parseCoord(v(r, lonC)); if (la != null && lo != null) la to lo else null }
        }
        // ③ X · Y 평면좌표 (TM · UTM-K) 칸
        fun xy(keys: List<String>) = h.indices.filter { i -> keys.any { h[i].contains(it, true) } }
            .filterNot { h[it].contains("최소") || h[it].contains("최대") || h[it].contains("min", true) || h[it].contains("max", true) }
            .firstOrNull { i -> rows.count { (parseCoord(v(it, i)) ?: 0.0) > 1000 } > rows.size / 3 }
        val xc = xy(listOf("x좌표", "엑스", "x_", "_x", "coord_x", "x")); val yc = xy(listOf("y좌표", "와이", "y_", "_y", "coord_y", "y"))
        if (xc != null && yc != null && xc != yc) {
            return { r ->
                val x = parseCoord(v(r, xc)); val y = parseCoord(v(r, yc))
                if (x == null || y == null) null else pickProjection(x, y, null, null)?.invoke(x, y)?.let { it.latitude to it.longitude }
            }
        }
        return { _ -> null }
    }

    /** 'POINT (x y)' → 위도·경도 */
    private fun pointOf(g: String): Pair<Double, Double>? {
        if (!g.uppercase(Locale.US).contains("POINT")) return null
        val n = NUM.findAll(g.substringAfter("(")).map { it.value.toDouble() }.toList()
        if (n.size < 2) return null
        return pickProjection(n[0], n[1], null, null)?.invoke(n[0], n[1])?.let { it.latitude to it.longitude }
    }

    /** 0곳일 때: 받은 자료 첫 줄을 '칸 이름: 값' 으로 (캡처해서 보내면 맞춰 고침) */
    private fun sampleText(csv: Csv): String {
        val r = csv.rows.firstOrNull { row -> row.count { it.isNotBlank() } >= 3 } ?: return "(빈 자료)"
        return csv.head.indices.take(25).joinToString("\n") { i -> "${csv.head[i]}: ${(r.getOrNull(i) ?: "").take(60)}" }
    }

    // ---- 공간정보: WKT (POLYGON / MULTIPOLYGON) · GeoJSON · 좌표계 자동 ----
    private fun parseGeom(s0: String, refLat: Double?, refLon: Double?): List<List<GeoPoint>> {
        val s = s0.trim()
        if (s.isBlank()) return emptyList()
        val raw: List<List<DoubleArray>> = if (s.startsWith("{")) geoJsonRings(s) else wktRings(s)
        if (raw.isEmpty()) return emptyList()
        val sample = raw[0][0]
        val conv = pickProjection(sample[0], sample[1], refLat, refLon) ?: return emptyList()
        return raw.map { r -> r.map { conv(it[0], it[1]) } }
    }

    /** 바깥 테두리만 (구멍은 무시) */
    private fun wktRings(s: String): List<List<DoubleArray>> {
        val u = s.uppercase(Locale.US)
        val body = s.substring(s.indexOf('(').coerceAtLeast(0))
        val out = ArrayList<List<DoubleArray>>()
        // 다각형마다 "((" 로 시작 · 첫 고리만
        val polys = if (u.contains("MULTIPOLYGON")) body.split(Regex("\\)\\s*\\)\\s*,\\s*\\(\\s*\\(")) else listOf(body)
        polys.forEach { p ->
            val first = p.replace("(", " ").split(")")[0]
            val pts = first.split(",").mapNotNull { pair ->
                val n = pair.trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
                if (n.size >= 2) doubleArrayOf(n[0], n[1]) else null
            }
            if (pts.size >= 3) out.add(pts)
        }
        return out
    }

    private fun geoJsonRings(s: String): List<List<DoubleArray>> {
        val o = JSONObject(s)
        val g = if (o.has("geometry")) o.getJSONObject("geometry") else o
        val c = g.getJSONArray("coordinates")
        fun ring(a: JSONArray) = (0 until a.length()).map { i -> val p = a.getJSONArray(i); doubleArrayOf(p.getDouble(0), p.getDouble(1)) }
        return when (g.optString("type")) {
            "Polygon" -> listOf(ring(c.getJSONArray(0)))
            "MultiPolygon" -> (0 until c.length()).map { ring(c.getJSONArray(it).getJSONArray(0)) }
            else -> emptyList()
        }
    }

    /** x, y 가 어느 좌표계인지 보고 위경도로 바꾸는 함수 */
    private fun pickProjection(x: Double, y: Double, refLat: Double?, refLon: Double?): ((Double, Double) -> GeoPoint)? {
        if (x in 123.0..133.0 && y in 32.0..40.0) return { a, b -> GeoPoint(b, a) }            // 경도, 위도
        if (y in 123.0..133.0 && x in 32.0..40.0) return { a, b -> GeoPoint(a, b) }            // 위도, 경도
        if (x > 1.2e7 && x < 1.5e7) return { a, b -> mercInv(a, b) }                            // 웹 메르카토르
        val cands = ArrayList<(Double, Double) -> GeoPoint>()
        if (y > 1.2e6 && y < 2.4e6 && x > 6e5 && x < 1.5e6) cands.add { a, b -> tmInv(a, b, 127.5, 0.9996, 1_000_000.0, 2_000_000.0) } // UTM-K (5179)
        if (y > 0 && y < 1.2e6) {
            listOf(127.0, 129.0, 125.0, 131.0).forEach { lon0 ->
                cands.add { a, b -> tmInv(a, b, lon0, 1.0, 200_000.0, 600_000.0) }               // 5186 · 5187 · 5185 · 5188
                cands.add { a, b -> tmInv(a, b, lon0, 1.0, 200_000.0, 500_000.0) }               // 옛 (5181 계열)
            }
        }
        if (cands.isEmpty()) return null
        if (refLat != null && refLon != null && refLat in 32.0..40.0) {
            return cands.minByOrNull { f -> val g = f(x, y); abs(g.latitude - refLat) + abs(g.longitude - refLon) }
        }
        return cands.firstOrNull { f -> inKorea(f(x, y)) }
    }

    private fun mercInv(x: Double, y: Double): GeoPoint {
        val lon = x / 6378137.0 * 180.0 / Math.PI
        val lat = (2 * atan(exp(y / 6378137.0)) - Math.PI / 2) * 180.0 / Math.PI
        return GeoPoint(lat, lon)
    }

    /** 횡메르카토르 역변환 (GRS80, 원점 위도 38°) */
    private fun tmInv(x: Double, y: Double, lon0: Double, k0: Double, fe: Double, fn: Double): GeoPoint {
        val a = 6378137.0; val f = 1 / 298.257222101
        val e2 = f * (2 - f); val ep2 = e2 / (1 - e2)
        val lat0 = Math.toRadians(38.0)
        fun mArc(p: Double) = a * ((1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2 * e2 * e2 / 256) * p -
            (3 * e2 / 8 + 3 * e2 * e2 / 32 + 45 * e2 * e2 * e2 / 1024) * sin(2 * p) +
            (15 * e2 * e2 / 256 + 45 * e2 * e2 * e2 / 1024) * sin(4 * p) - (35 * e2 * e2 * e2 / 3072) * sin(6 * p))
        val m = mArc(lat0) + (y - fn) / k0
        val mu = m / (a * (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2 * e2 * e2 / 256))
        val e1 = (1 - sqrt(1 - e2)) / (1 + sqrt(1 - e2))
        val p1 = mu + (3 * e1 / 2 - 27 * e1 * e1 * e1 / 32) * sin(2 * mu) + (21 * e1 * e1 / 16 - 55 * e1 * e1 * e1 * e1 / 32) * sin(4 * mu) +
            (151 * e1 * e1 * e1 / 96) * sin(6 * mu) + (1097 * e1 * e1 * e1 * e1 / 512) * sin(8 * mu)
        val c1 = ep2 * cos(p1) * cos(p1); val t1 = tan(p1) * tan(p1)
        val n1 = a / sqrt(1 - e2 * sin(p1) * sin(p1))
        val r1 = a * (1 - e2) / Math.pow(1 - e2 * sin(p1) * sin(p1), 1.5)
        val dd = (x - fe) / (n1 * k0)
        val lat = p1 - (n1 * tan(p1) / r1) * (dd * dd / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * ep2) * dd * dd * dd * dd / 24 +
            (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * ep2 - 3 * c1 * c1) * Math.pow(dd, 6.0) / 720)
        val lon = (dd - (1 + 2 * t1 + c1) * dd * dd * dd / 6 + (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * ep2 + 24 * t1 * t1) * Math.pow(dd, 5.0) / 120) / cos(p1)
        return GeoPoint(Math.toDegrees(lat), lon0 + Math.toDegrees(lon))
    }

    /** 점 줄이기 (더글러스-포이커) */
    private fun simplify(p: List<GeoPoint>, tol: Double): List<GeoPoint> {
        if (p.size < 5) return p
        val keep = BooleanArray(p.size); keep[0] = true; keep[p.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>(); stack.add(0 to p.size - 1)
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            var best = -1; var bd = 0.0
            val ax = p[s].longitude; val ay = p[s].latitude; val bx = p[e].longitude; val by = p[e].latitude
            val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
            for (i in s + 1 until e) {
                val px = p[i].longitude; val py = p[i].latitude
                val dist = if (len == 0.0) sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay)) else abs((bx - ax) * (ay - py) - (ax - px) * (by - ay)) / len
                if (dist > bd) { bd = dist; best = i }
            }
            if (best > 0 && bd > tol) { keep[best] = true; stack.add(s to best); stack.add(best to e) }
        }
        return p.filterIndexed { i, _ -> keep[i] }.let { r -> if (r.size > 1 && r.first() == r.last()) r.dropLast(1) else r }
    }

    // ---------------- 관리자: 지도에서 직접 그리기 ----------------
    fun openAdminMap(a: Activity) {
        fun dp(v: Int) = Ui.dp(a, v)
        val dlg = Dialog(a, R.style.FlFull)
        val map = MapDialog.newMap(a)
        val ov = ZoneOverlay(a) { true }
        map.overlays.add(ov)
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0B1A26")) }
        root.addView(Ui.text(a, 17f, Color.WHITE, true, "🦀 해루질 참고 구역 · 직접 그리기").apply { setPadding(dp(16), dp(10), dp(16), dp(2)) })
        val info = Ui.text(a, 12f, Ui.SUB, false, "").apply { setPadding(dp(16), dp(2), dp(16), dp(6)) }
        root.addView(info)
        val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), 0, dp(12), dp(8)) }
        val hs = Ui.hscroll(a, bar)
        root.addView(hs)
        val frame = FrameLayout(a)
        frame.addView(map, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f))
        fun pill(label: String, color: Int, onClick: () -> Unit) = Ui.text(a, 13f, color, true, label).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), dp(7), dp(12), dp(7)); maxLines = 1
            background = Ui.bg(a, Color.TRANSPARENT, 99, color); setOnClickListener { onClick() }
        }
        var hintText = ""
        ov.hint = { hintText = it }
        fun paint() {
            bar.removeAllViews()
            fun add(v: View) = bar.addView(v, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
            if (!ov.drawMode) {
                add(MapSearch.pill(a, map))
                add(pill("✏ 새 구역 그리기", Color.parseColor("#FF4FA8")) { ov.drawMode = true; ov.drawing.clear(); paint(); map.invalidate() })
                add(pill(if (Store.satellite) "지도로" else "위성으로", Ui.TEXT) { MapDialog.setSatellite(map, !Store.satellite); paint() })
                info.text = "구역을 누르면 ✎ 수정 · 🗑 삭제 · 확대해야 보여요"
            } else {
                add(pill("↶ 한 점 취소", Ui.TEXT) { if (ov.drawing.isNotEmpty()) ov.drawing.removeAt(ov.drawing.size - 1); paint(); map.invalidate() })
                add(pill("✓ 완료 (${ov.drawing.size}점)", Ui.GREEN) {
                    if (ov.drawing.size < 3) { Ui.toast(a, "꼭짓점을 3개 이상 찍어 주세요"); return@pill }
                    val pts = ArrayList(ov.drawing)
                    askMeta(a, "새 구역", "village", "", "") { t, n, no ->
                        replaceZone(a, null, Zone(newId(true), t, n, pts)) { err ->
                            Ui.toast(a, err ?: "추가했어요"); ov.drawMode = false; ov.drawing.clear(); paint(); map.invalidate()
                        }
                    }
                })
                add(pill("✕ 그만", Color.parseColor("#FF8A92")) { ov.drawMode = false; ov.drawing.clear(); paint(); map.invalidate() })
                info.text = "지도를 눌러 꼭짓점을 차례로 찍어요 (3개 이상) → ✓ 완료"
            }
        }
        ov.onDrawChange = { paint() }
        ov.onTapZones = { showInfo(a, it, map) }
        paint()
        dlg.setContentView(root)
        dlg.setOnDismissListener { map.onPause(); map.onDetach() }
        dlg.show()
        map.onResume()
        map.post {
            map.controller.setZoom(13.0)
            map.controller.setCenter(Tracker.last?.let { GeoPoint(it.latitude, it.longitude) } ?: GeoPoint(37.3, 126.6))
        }
    }
}
