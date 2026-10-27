package com.fishlog.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 조과 기록 저장소. 모든 호출은 메인 스레드에서. */
object Store {

    class Catch(val time: Long, val slot: Int, val lat: Double? = null, val lon: Double? = null)

    /** 이동 경로의 점 하나 */
    class Pt(val time: Long, val lat: Double, val lon: Double)

    /** 사진 하나. file = 앱 폴더 안 파일 이름, catchTime = 붙어 있는 조과 시각 (없으면 null) */
    class Photo(val time: Long, val file: String, var catchTime: Long?)

    class Trip(
        val id: Long,
        val start: Long,
        var end: Long,
        val names: Array<String>,
        val catches: MutableList<Catch>,
        val track: MutableList<Pt> = mutableListOf(),
        var uploaded: Boolean = false,
        var boat: String = "",
        var port: String = "",
        val photos: MutableList<Photo> = mutableListOf(),
        var backedUp: Boolean = false,
        var cat: String = "",
        var pub: Boolean = false
    ) {
        fun count(slot: Int) = catches.count { it.slot == slot }
        fun name(slot: Int) = names[slot - 1]
        fun place(): String = listOf(boat, port).filter { it.isNotBlank() }.joinToString(" · ")
        fun photoOf(c: Catch): Photo? = photos.firstOrNull { it.catchTime == c.time }
        /** 위치가 있는 첫 지점 (물때 조회용) */
        fun anyLocation(): Pair<Double, Double>? =
            catches.firstOrNull { it.lat != null && it.lon != null }?.let { it.lat!! to it.lon!! }
                ?: track.firstOrNull()?.let { it.lat to it.lon }
    }

    private lateinit var prefs: SharedPreferences
    var current: Trip? = null
        private set
    val history = mutableListOf<Trip>()
    private val listeners = mutableSetOf<() -> Unit>()

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("fishlog", Context.MODE_PRIVATE)
        load()
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun fireChanged() = listeners.toList().forEach { it() }
    /** all=false 이면 지금 출조만 저장 (가볍게) */
    private fun changed(all: Boolean = true) { if (all) save() else saveCurrent(); fireChanged() }

    fun find(id: Long): Trip? = if (current?.id == id) current else history.firstOrNull { it.id == id }

    // ---- 리모컨 버튼 등록 ----
    var key1: String?
        get() = prefs.getString("key1", null)
        set(v) { prefs.edit().putString("key1", v).apply(); fireChanged() }
    var key2: String?
        get() = prefs.getString("key2", null)
        set(v) { prefs.edit().putString("key2", v).apply(); fireChanged() }

    // ---- 설정 ----
    // ---- 낚시 종류 (선상 · 해루질 · 워킹) ----
    val CATS = listOf("boat", "night", "walk")
    fun catLabel(c: String): String = when (c) { "boat" -> "선상낚시"; "night" -> "해루질"; "walk" -> "워킹낚시"; else -> "" }
    fun catShort(c: String): String = when (c) { "boat" -> "선상"; "night" -> "해루질"; "walk" -> "워킹"; else -> "" }
    private val DEFAULT_SPECIES = mapOf(
        "boat" to listOf("주꾸미", "갑오징어", "우럭", "광어", "참돔", "쭈갑"),
        "night" to listOf("낙지", "문어", "해삼", "소라", "꽃게", "키조개"),
        "walk" to listOf("우럭", "노래미", "감성돔", "농어", "볼락", "학꽁치")
    )

    var category: String
        get() = prefs.getString("cat", "boat") ?: "boat"
        set(v) {
            prefs.edit().putString("cat", v).apply()
            current?.let { it.cat = v }
            fireChanged()
        }

    fun species(cat: String = category): List<String> {
        val saved = prefs.getString("species_$cat", null) ?: return DEFAULT_SPECIES[cat] ?: emptyList()
        return try { val a = JSONArray(saved); (0 until a.length()).map { a.getString(it) } } catch (_: Exception) { DEFAULT_SPECIES[cat] ?: emptyList() }
    }

    fun setSpecies(cat: String, list: List<String>) {
        val a = JSONArray(); list.forEach { a.put(it) }
        prefs.edit().putString("species_$cat", a.toString()).apply()
        fireChanged()
    }

    /** 서버(관리자)가 정한 어종 목록 받기 */
    fun applyServerSpecies(map: Map<String, List<String>?>) {
        val e = prefs.edit()
        map.forEach { (c, l) ->
            if (l != null && l.isNotEmpty()) {
                val a = JSONArray(); l.forEach { a.put(it) }
                e.putString("species_$c", a.toString())
            }
        }
        e.apply()
        fireChanged()
    }

    // ---- 물때 지역 (null = 내 위치에서 가장 가까운 관측소) ----
    var tideStation: String?
        get() = prefs.getString("tideStation", null)
        set(v) { prefs.edit().putString("tideStation", v).apply(); fireChanged() }

    /** 만조·간조 몇 분 전 알람 (0 = 끔) */
    var tideAlarmMin: Int
        get() = prefs.getInt("tideAlarm", 0)
        set(v) { prefs.edit().putInt("tideAlarm", v).apply(); fireChanged() }

    fun addSpecies(cat: String, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        val l = species(cat).toMutableList()
        if (n !in l) { l.add(n); setSpecies(cat, l) }
    }

    /** 다음 출조에 쓸 버튼 이름 (종류마다 따로 기억) */
    fun defaultName(slot: Int, cat: String = category): String =
        prefs.getString("name${slot}_$cat", null)
            ?: (if (cat == "boat") prefs.getString("name$slot", null) else null)
            ?: species(cat).getOrNull(slot - 1) ?: "${slot}번"

    /** 갤럭시 워치 음악 컨트롤러로 기록 */
    var watchMode: Boolean
        get() = prefs.getBoolean("watch", false)
        set(v) { prefs.edit().putBoolean("watch", v).apply(); fireChanged() }

    /** 메인 화면 아래 메뉴 펼침 */
    var menuOpen: Boolean
        get() = prefs.getBoolean("menuOpen", false)
        set(v) { prefs.edit().putBoolean("menuOpen", v).apply() }

    var touchMode: Boolean
        get() = prefs.getBoolean("touch", true)
        set(v) { prefs.edit().putBoolean("touch", v).apply(); fireChanged() }

    var hideRoute: Boolean
        get() = prefs.getBoolean("hideRoute", false)
        set(v) { prefs.edit().putBoolean("hideRoute", v).apply(); fireChanged() }

    /** 배터리 절약 모드: 경로를 3분 간격으로 */
    var saver: Boolean
        get() = prefs.getBoolean("saver", false)
        set(v) { prefs.edit().putBoolean("saver", v).apply(); Tracker.reapply(); fireChanged() }

    var satellite: Boolean
        get() = prefs.getBoolean("satellite", true)
        set(v) { prefs.edit().putBoolean("satellite", v).apply() }

    // ---- 배·항구 목록 (한 번 적은 이름은 저장) ----
    private fun readList(key: String): MutableList<String> {
        val out = mutableListOf<String>()
        try {
            val a = JSONArray(prefs.getString(key, "[]"))
            for (i in 0 until a.length()) out.add(a.getString(i))
        } catch (_: Exception) {}
        return out
    }

    private fun remember(key: String, v: String) {
        if (v.isBlank()) return
        val l = readList(key)
        l.remove(v)
        l.add(0, v)
        val a = JSONArray()
        l.take(50).forEach { a.put(it) }
        prefs.edit().putString(key, a.toString()).apply()
    }

    fun boats(): List<String> = readList("boats")
    fun ports(): List<String> = readList("ports")

    // ---- 출조 ----
    fun startTrip(boat: String = "", port: String = "") {
        if (current != null) return
        val now = System.currentTimeMillis()
        current = Trip(now, now, 0L, arrayOf(defaultName(1), defaultName(2)), mutableListOf(),
            boat = boat.trim(), port = port.trim(), cat = category)
        remember("boats", boat.trim()); remember("ports", port.trim())
        Tracker.last?.let { current?.track?.add(Pt(it.time, it.latitude, it.longitude)) }
        changed()
    }

    fun endTrip(): Trip? {
        val t = current ?: return null
        t.end = System.currentTimeMillis()
        history.add(0, t)
        current = null
        changed()
        return t
    }

    fun addCatch(slot: Int): Int {
        val t = current ?: return 0
        val now = System.currentTimeMillis()
        val loc = Tracker.recent(now)
        t.catches.add(Catch(now, slot, loc?.latitude, loc?.longitude))
        changed(false)
        return t.count(slot)
    }

    fun undoLast(): Int {
        val t = current ?: return 0
        if (t.catches.isEmpty()) return 0
        val c = t.catches.removeAt(t.catches.size - 1)
        changed(false)
        return c.slot
    }

    fun addTrackPoint(time: Long, lat: Double, lon: Double) {
        val t = current ?: return
        t.track.add(Pt(time, lat, lon))
        saveCurrent()
    }

    fun rename(slot: Int, name: String) {
        val v = name.ifBlank { "${slot}번" }
        prefs.edit().putString("name${slot}_$category", v).apply()
        current?.names?.set(slot - 1, v)
        changed(false)
    }

    fun renameInTrip(tripId: Long, slot: Int, name: String) {
        val t = find(tripId) ?: return
        if (t === current) { rename(slot, name); return }
        t.names[slot - 1] = name.ifBlank { "${slot}번" }
        t.uploaded = false; t.backedUp = false
        changed()
    }

    fun setPlace(tripId: Long, boat: String, port: String) {
        val t = find(tripId) ?: return
        t.boat = boat.trim(); t.port = port.trim()
        remember("boats", t.boat); remember("ports", t.port)
        t.uploaded = false; t.backedUp = false
        changed()
    }

    fun addPhotos(tripId: Long, list: List<Photo>) {
        val t = find(tripId) ?: return
        t.photos.addAll(list)
        t.photos.sortBy { it.time }
        t.uploaded = false
        changed()
    }

    /** 사진 연결을 바꾼 뒤 저장 */
    fun photosChanged(tripId: Long) {
        val t = find(tripId) ?: return
        t.photos.sortBy { it.time }
        t.uploaded = false
        changed()
    }

    fun removePhoto(tripId: Long, p: Photo) {
        find(tripId)?.photos?.remove(p)
        changed()
    }

    /** 전체 게시판 공개 여부 표시 */
    fun setPublic(tripId: Long, v: Boolean) { find(tripId)?.pub = v; changed() }

    /** 전체 게시판에 보일 이름 (null = 아직 안 정함, "" = 익명) */
    var nick: String?
        get() = prefs.getString("nick", null)
        set(v) { prefs.edit().putString("nick", v).apply() }

    fun markNotUploaded(tripId: Long) { find(tripId)?.uploaded = false; changed() }

    fun markUploaded(tripId: Long) { find(tripId)?.uploaded = true; changed() }
    fun markBackedUp(tripId: Long) { find(tripId)?.backedUp = true; save() }

    fun deleteTrip(tripId: Long) {
        history.removeAll { it.id == tripId }
        changed()
    }

    /** 서버에서 받아온 기록 합치기. 새로 추가된 개수 */
    fun mergeRestored(list: List<Trip>): Int {
        var added = 0
        list.forEach { r ->
            if (find(r.id) == null) { history.add(r); added++ }
        }
        history.sortByDescending { it.start }
        changed()
        return added
    }

    // ---- 저장/불러오기 (JSON) ----
    fun tripToJson(t: Trip): JSONObject {
        val o = JSONObject()
        o.put("id", t.id); o.put("start", t.start); o.put("end", t.end)
        o.put("up", t.uploaded); o.put("bk", t.backedUp)
        o.put("boat", t.boat); o.put("port", t.port); o.put("cat", t.cat); o.put("pub", t.pub)
        o.put("names", JSONArray().put(t.names[0]).put(t.names[1]))
        val arr = JSONArray()
        t.catches.forEach {
            val c = JSONArray().put(it.time).put(it.slot)
            if (it.lat != null && it.lon != null) c.put(it.lat).put(it.lon)
            arr.put(c)
        }
        o.put("catches", arr)
        val tr = JSONArray()
        t.track.forEach { tr.put(JSONArray().put(it.time).put(it.lat).put(it.lon)) }
        o.put("track", tr)
        val ph = JSONArray()
        t.photos.forEach { ph.put(JSONArray().put(it.time).put(it.file).put(it.catchTime ?: -1L)) }
        o.put("photos", ph)
        return o
    }

    fun tripFromJson(o: JSONObject): Trip {
        val n = o.getJSONArray("names")
        val arr = o.getJSONArray("catches")
        val list = mutableListOf<Catch>()
        for (i in 0 until arr.length()) {
            val c = arr.getJSONArray(i)
            if (c.length() >= 4) list.add(Catch(c.getLong(0), c.getInt(1), c.getDouble(2), c.getDouble(3)))
            else list.add(Catch(c.getLong(0), c.getInt(1)))
        }
        val track = mutableListOf<Pt>()
        o.optJSONArray("track")?.let { tr ->
            for (i in 0 until tr.length()) {
                val p = tr.getJSONArray(i)
                track.add(Pt(p.getLong(0), p.getDouble(1), p.getDouble(2)))
            }
        }
        val photos = mutableListOf<Photo>()
        o.optJSONArray("photos")?.let { ph ->
            for (i in 0 until ph.length()) {
                val p = ph.getJSONArray(i)
                val ct = p.getLong(2)
                photos.add(Photo(p.getLong(0), p.getString(1), if (ct < 0) null else ct))
            }
        }
        return Trip(o.getLong("id"), o.getLong("start"), o.optLong("end", 0L),
            arrayOf(n.getString(0), n.getString(1)), list, track, o.optBoolean("up", false),
            o.optString("boat", ""), o.optString("port", ""), photos, o.optBoolean("bk", false), o.optString("cat", ""),
            o.optBoolean("pub", false))
    }

    private fun saveCurrent() {
        val cur = current
        val e = prefs.edit()
        if (cur == null) e.remove("current") else e.putString("current", tripToJson(cur).toString())
        e.apply()
    }

    private fun save() {
        val e = prefs.edit()
        val cur = current
        if (cur == null) e.remove("current") else e.putString("current", tripToJson(cur).toString())
        val h = JSONArray()
        history.forEach { h.put(tripToJson(it)) }
        e.putString("history", h.toString())
        e.commit()
    }

    private fun load() {
        try {
            prefs.getString("current", null)?.let { current = tripFromJson(JSONObject(it)) }
            history.clear()
            prefs.getString("history", null)?.let {
                val h = JSONArray(it)
                for (i in 0 until h.length()) history.add(tripFromJson(h.getJSONObject(i)))
            }
        } catch (e: Exception) {
            // 손상된 데이터는 건너뜀
        }
    }

    /** 친구 공유·백업용 텍스트 */
    fun exportText(t: Trip): String {
        val df = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.KOREA)
        val sb = StringBuilder()
        sb.append("[조과기록] ").append(df.format(java.util.Date(t.start)))
        if (t.end > 0) sb.append(" ~ ").append(df.format(java.util.Date(t.end)).substring(11))
        if (t.place().isNotBlank()) sb.append("\n").append(t.place())
        sb.append("\n").append(t.name(1)).append(" ").append(t.count(1))
            .append(" / ").append(t.name(2)).append(" ").append(t.count(2)).append("\n\n")
        t.catches.forEach { sb.append(df.format(java.util.Date(it.time)).substring(11)).append("  ").append(t.name(it.slot)).append("\n") }
        return sb.toString()
    }
}
