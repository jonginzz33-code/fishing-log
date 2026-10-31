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
        var names: Array<String>,
        val catches: MutableList<Catch>,
        val track: MutableList<Pt> = mutableListOf(),
        var uploaded: Boolean = false,
        var boat: String = "",
        var port: String = "",
        val photos: MutableList<Photo> = mutableListOf(),
        var backedUp: Boolean = false,
        var cat: String = "",
        var pub: Boolean = false,
        /** 전체 공개 위치 방법: "exact" 정확 / "approx" 대략 / "" 예전 공개 */
        var pubPrec: String = "",
        /** 이용료: null = 안 적음, false = 무료, true = 유료 */
        var paid: Boolean? = null,
        /** 이용료 금액 (원) */
        var fee: Int = 0,
        /** 꼭 필요한 물건 */
        val gear: MutableList<String> = mutableListOf()
    ) {
        fun costLabel(): String = Store.costLabel(paid, fee)
        fun count(slot: Int) = catches.count { it.slot == slot }
        fun name(slot: Int) = names.getOrElse(slot - 1) { "${slot}번" }
        /** 이 출조의 버튼 번호들 (1·2 + 퀵 버튼 3번 이상) */
        fun slots(): List<Int> = ((1..maxOf(2, names.size)).toList() + catches.map { it.slot }).distinct().sorted()
        /** 잡은 어종만 (1·2번은 0마리여도) */
        fun shownSlots(): List<Int> = slots().filter { it <= 2 || count(it) > 0 }
        /** "주꾸미 10 · 갑오징어 49 · 낙지 3" */
        fun line(sep: String = " · ", unit: String = ""): String = shownSlots().joinToString(sep) { "${name(it)} ${count(it)}$unit" }
        fun ensureSlot(slot: Int, label: String) {
            if (slot > names.size) names = Array(slot) { i -> names.getOrElse(i) { if (i == slot - 1) label else "${i + 1}번" } }
        }
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

    private lateinit var tripDir: java.io.File

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("fishlog", Context.MODE_PRIVATE)
        tripDir = java.io.File(ctx.applicationContext.filesDir, "trips").apply { mkdirs() }
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
    // 관리자가 '낚시 종류 관리'에서 추가·이름 바꾸기·순서·숨기기 (서버 config/cats → 폰에 저장)
    class Cat(val id: String, val label: String, val short: String, val hidden: Boolean = false)
    private val BASE_CATS = listOf(Cat("boat", "선상낚시", "선상"), Cat("night", "해루질", "해루질"), Cat("walk", "워킹낚시", "워킹"))

    /** 모든 종류 (숨긴 것 포함, 이름 찾기용) */
    private var catsCache: Pair<String?, List<Cat>>? = null
    fun allCats(): List<Cat> {
        val saved = if (::prefs.isInitialized) prefs.getString("catsJson", null) else null
        catsCache?.let { if (it.first == saved) return it.second }
        val r = parseCats(saved)
        catsCache = saved to r
        return r
    }

    private fun parseCats(saved: String?): List<Cat> {
        if (saved.isNullOrBlank()) return BASE_CATS
        return try {
            val a = JSONArray(saved)
            val l = (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Cat(o.getString("id"), o.optString("label", ""), o.optString("short", ""), o.optBoolean("hidden", false))
            }.filter { it.id.isNotBlank() && it.label.isNotBlank() }
            if (l.isEmpty()) BASE_CATS else l
        } catch (_: Exception) { BASE_CATS }
    }

    /** 고를 수 있는 종류 (숨긴 것 빼고, 관리자가 정한 순서) */
    val CATS: List<String> get() = allCats().filter { !it.hidden }.map { it.id }

    /** 관리자가 지운 종류는 "(지운 종류)" */
    fun catLabel(c: String): String = if (c.isBlank()) "" else allCats().firstOrNull { it.id == c }?.label ?: "(지운 종류)"
    fun catShort(c: String): String = if (c.isBlank()) "" else allCats().firstOrNull { it.id == c }?.let { it.short.ifBlank { it.label } } ?: "(지운 종류)"

    fun catsToJson(list: List<Cat>): String {
        val a = JSONArray()
        list.forEach { c -> a.put(JSONObject().put("id", c.id).put("label", c.label).put("short", c.short).put("hidden", c.hidden)) }
        return a.toString()
    }

    /** 서버에서 받은 종류 목록 저장 */
    fun applyServerCats(list: List<Cat>) {
        if (list.isEmpty()) return
        val js = catsToJson(list)
        if (prefs.getString("catsJson", null) == js) return
        prefs.edit().putString("catsJson", js).apply()
        fireChanged()
    }
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

    /** 종류가 비어 있는 출조: 어종 이름으로 낚시 종류 짐작 (주꾸미·갑오징어 → 선상, 낙지·골뱅이 → 해루질). 모르면 "" */
    fun guessCat(names: List<String>): String {
        val ns = names.map { it.trim() }.filter { it.isNotBlank() && !it.endsWith("번") }
        if (ns.isEmpty()) return ""
        val scored = CATS.map { c ->
            val list = (species(c) + (DEFAULT_SPECIES[c] ?: emptyList())).toSet()
            c to ns.count { it in list }
        }
        val best = scored.maxByOrNull { it.second } ?: return ""
        return if (best.second > 0) best.first else ""
    }

    /** 지난 출조의 낚시 종류 바꾸기 (그룹·백업엔 다시 올려야 반영) */
    fun setTripCat(tripId: Long, cat: String) {
        val t = find(tripId) ?: return
        t.cat = cat
        t.uploaded = false; t.backedUp = false
        changed()
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

    /** 최근에 고른 관측소 (최대 3곳) */
    fun recentStations(): List<String> =
        prefs.getString("recentStations", "")!!.split(",").filter { it.isNotBlank() }

    fun rememberStation(code: String) {
        val l = (listOf(code) + recentStations().filter { it != code }).take(3)
        prefs.edit().putString("recentStations", l.joinToString(",")).apply()
    }

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

    // ---- 퀵 버튼 (3번 이상, 종류마다 따로) · 리모컨은 1·2번만 ----
    /** size: 0 작게, 1 보통, 2 크게 · color: 색 번호 */
    class Quick(val slot: Int, var name: String, var size: Int = 1, var color: Int = 0)
    val QUICK_COLORS = listOf("#7C6BD6", "#4CAF7A", "#C9A43A", "#B5567E", "#3F8FD6", "#D9604A", "#5A8F8A", "#8A6A4F")

    fun quick(cat: String = category): MutableList<Quick> = try {
        val a = JSONArray(prefs.getString("qb_$cat", "[]"))
        (0 until a.length()).map { i -> val o = a.getJSONObject(i); Quick(o.getInt("s"), o.optString("n"), o.optInt("z", 1), o.optInt("c", 0)) }.toMutableList()
    } catch (_: Exception) { mutableListOf() }

    fun saveQuick(list: List<Quick>, cat: String = category) {
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("s", it.slot).put("n", it.name).put("z", it.size).put("c", it.color)) }
        prefs.edit().putString("qb_$cat", a.toString()).apply()
        changed(false)
    }

    /** 새 퀵 버튼 번호 (지운 번호는 다시 안 씀 → 예전 기록과 안 섞임) */
    fun newQuickSlot(cat: String = category): Int {
        val used = (quick(cat).map { it.slot } + listOf(2)).maxOrNull() ?: 2
        val n = maxOf(prefs.getInt("qb_next_$cat", 3), used + 1, (current?.names?.size ?: 2) + 1)
        prefs.edit().putInt("qb_next_$cat", n + 1).apply()
        return n
    }

    /** 버튼 색 (1 주황 · 2 하늘 · 3번 이상은 고른 색) */
    fun slotColor(slot: Int): Int = when (slot) {
        1 -> android.graphics.Color.parseColor("#F28C38"); 2 -> android.graphics.Color.parseColor("#5BC8DF")
        else -> android.graphics.Color.parseColor(QUICK_COLORS[(quick().firstOrNull { it.slot == slot }?.color ?: (slot - 3)).mod(QUICK_COLORS.size)])
    }

    /** 다음 출조에 쓸 버튼 이름 (종류마다 따로 기억) */
    fun defaultName(slot: Int, cat: String = category): String =
        (if (slot >= 3) quick(cat).firstOrNull { it.slot == slot }?.name else null)
            ?: prefs.getString("name${slot}_$cat", null)
            ?: (if (cat == "boat") prefs.getString("name$slot", null) else null)
            ?: species(cat).getOrNull(slot - 1) ?: "${slot}번"

    /** 갤럭시 워치 음악 컨트롤러로 기록 */
    var watchMode: Boolean
        get() = prefs.getBoolean("watch", false)
        set(v) { prefs.edit().putBoolean("watch", v).apply(); fireChanged() }

    /** 기록 방식(리모컨·터치·워치·배터리 절약)을 한 번이라도 골랐는지 */
    var methodsChosen: Boolean
        get() = prefs.getBoolean("methodsChosen", false)
        set(v) { prefs.edit().putBoolean("methodsChosen", v).apply() }

    /** 개인 설정: 출조 중 잠금 화면·AOD에 아이콘·마릿수·+1 버튼 보이기 */
    /** 처음 안내(그림 설명)를 봤는지 */
    var guideSeen: Boolean
        get() = prefs.getBoolean("guideSeen", false)
        set(v) { prefs.edit().putBoolean("guideSeen", v).apply() }

    /** 같이 낚시 중 거리 기준 (m). 0 = 거리 상관없이 같은 그룹 출조 중이면 모두 */
    var mateRange: Int
        get() = prefs.getInt("mateRange", 1000)
        set(v) { prefs.edit().putInt("mateRange", v).apply() }

    /** 물때 그래프에 보일 것 (비트): 1 cm 눈금 · 2 만조·간조 · 4 높이 차 · 8 일출·일몰·밤 · 16 지금 */
    var tideLayers: Int
        get() = prefs.getInt("tideLayers", 31)
        set(v) { prefs.edit().putInt("tideLayers", v).apply() }

    var lockScreenShow: Boolean
        get() = prefs.getBoolean("lockShow", true)
        set(v) { prefs.edit().putBoolean("lockShow", v).apply(); fireChanged() }

    /** 개인 설정: 같이 낚시 중 친구 돌파 알림 받기 */
    var friendAlerts: Boolean
        get() = prefs.getBoolean("friendAlerts", true)
        set(v) { prefs.edit().putBoolean("friendAlerts", v).apply() }

    /** 지도 지난 기록 보기: "mine" / "g:그룹id" / "allg" / "public" (null = 지금 그룹) */
    var pastMode: String?
        get() = prefs.getString("pastMode", null)
        set(v) { prefs.edit().putString("pastMode", v).apply() }

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
        val maxSlot = maxOf(2, quick().maxOfOrNull { it.slot } ?: 2)
        current = Trip(now, now, 0L, Array(maxSlot) { defaultName(it + 1) }, mutableListOf(),
            boat = boat.trim(), port = port.trim(), cat = category)
        remember("boats", boat.trim()); remember("ports", port.trim())
        // 폰이 기억하던 예전 위치(2분보다 오래된 것)는 출발 지점으로 쓰지 않음 → GPS가 새로 잡은 첫 위치가 출발 지점
        Tracker.last?.takeIf { now - it.time < 2 * 60_000L }?.let { current?.track?.add(Pt(it.time, it.latitude, it.longitude)) }
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
        t.ensureSlot(slot, defaultName(slot))
        t.catches.add(Catch(now, slot, loc?.latitude, loc?.longitude))
        changed(false)
        Milestone.onCount(slot, t.count(slot))
        try { Live.pushSoon() } catch (_: Exception) {}
        return t.count(slot)
    }

    /** 그 버튼의 마지막 기록 하나 지우기 (퀵 버튼 꾹 누르기) */
    fun removeLastOf(slot: Int): Boolean {
        val t = current ?: return false
        val i = t.catches.indexOfLast { it.slot == slot }
        if (i < 0) return false
        t.catches.removeAt(i)
        changed(false)
        try { Live.pushSoon() } catch (_: Exception) {}
        return true
    }

    fun undoLast(): Int {
        val t = current ?: return 0
        if (t.catches.isEmpty()) return 0
        val c = t.catches.removeAt(t.catches.size - 1)
        changed(false)
        try { Live.pushSoon() } catch (_: Exception) {}
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
        if (slot >= 3) quick().let { l -> l.firstOrNull { it.slot == slot }?.let { q -> q.name = v; saveQuick(l) } }
        current?.let { it.ensureSlot(slot, v); it.names[slot - 1] = v }
        changed(false)
    }

    fun renameInTrip(tripId: Long, slot: Int, name: String) {
        val t = find(tripId) ?: return
        if (t === current) { rename(slot, name); return }
        t.ensureSlot(slot, name)
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

    /** 전체 게시판 공개 여부 표시 (exact = 위치 공개 방법, null 이면 그대로) */
    fun setPublic(tripId: Long, v: Boolean, exact: Boolean? = null) {
        find(tripId)?.let { t ->
            t.pub = v
            if (!v) t.pubPrec = "" else if (exact != null) t.pubPrec = if (exact) "exact" else "approx"
        }
        changed()
    }

    /** 공개할 때 마지막으로 고른 위치 공개 방법 (true = 정확) */
    var pubExact: Boolean
        get() = prefs.getBoolean("pubExact", true)
        set(v) { prefs.edit().putBoolean("pubExact", v).apply() }

    // ---- 이미 올린 사진 (같은 사진을 두 번 안 올리려고) ----
    // 키: "그룹id|출조id" 또는 "public|출조id" → { 파일 이름: 올릴 때 조과 연결 }
    private val photoLock = Any()
    private fun photoUpAll(): JSONObject = try { JSONObject(prefs.getString("photoUp", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

    fun photosUp(key: String, tripId: Long): Map<String, String> = synchronized(photoLock) {
        val o = photoUpAll().optJSONObject("$key|$tripId") ?: return emptyMap()
        o.keys().asSequence().associateWith { o.optString(it) }
    }

    fun photoUpAdd(key: String, tripId: Long, file: String, sig: String) = synchronized(photoLock) {
        val all = photoUpAll()
        val o = all.optJSONObject("$key|$tripId") ?: JSONObject()
        o.put(file, sig); all.put("$key|$tripId", o)
        prefs.edit().putString("photoUp", all.toString()).apply()
    }

    fun photoUpRemove(key: String, tripId: Long, file: String) = synchronized(photoLock) {
        val all = photoUpAll()
        all.optJSONObject("$key|$tripId")?.let { it.remove(file) }
        prefs.edit().putString("photoUp", all.toString()).apply()
    }

    fun photoUpClear(key: String, tripId: Long) = synchronized(photoLock) {
        val all = photoUpAll()
        all.remove("$key|$tripId")
        prefs.edit().putString("photoUp", all.toString()).apply()
    }

    // ---- 출조를 올린 그룹 (출조 id → 그룹 id 목록) ----
    private fun sharedMap(): JSONObject = try { JSONObject(prefs.getString("shared", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

    fun sharedGroups(tripId: Long): Set<String> {
        val a = sharedMap().optJSONArray(tripId.toString()) ?: return emptySet()
        return (0 until a.length()).map { a.getString(it) }.toSet()
    }

    private fun setShared(tripId: Long, gids: Set<String>) {
        val o = sharedMap()
        if (gids.isEmpty()) o.remove(tripId.toString())
        else o.put(tripId.toString(), JSONArray().apply { gids.forEach { put(it) } })
        prefs.edit().putString("shared", o.toString()).apply()
    }

    fun addShared(tripId: Long, gid: String) { setShared(tripId, sharedGroups(tripId) + gid); fireChanged() }
    fun removeShared(tripId: Long, gid: String) { setShared(tripId, sharedGroups(tripId) - gid); fireChanged() }

    /** 서버에서 확인한 결과로 바꾸기. checked = 확인한 그룹들, found = 그중 올라가 있는 (출조 id → 그룹들) */
    /** 서버와 한 번이라도 맞춰 봤는지 (안 맞춰 봤으면 예전 '그룹에 올림' 표시로 대신) */
    val sharedSynced: Boolean get() = prefs.getBoolean("sharedSynced", false)

    fun applySharedFromServer(checked: Set<String>, found: Map<Long, Set<String>>) {
        if (checked.isNotEmpty()) prefs.edit().putBoolean("sharedSynced", true).apply()
        val o = sharedMap()
        val ids = (o.keys().asSequence().mapNotNull { it.toLongOrNull() }.toSet() + found.keys)
        ids.forEach { id ->
            val now = sharedGroups(id)
            val next = (now - checked) + (found[id] ?: emptySet())
            if (next != now) setShared(id, next)
        }
        fireChanged()
    }

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
        o.put("boat", t.boat); o.put("port", t.port); o.put("cat", t.cat); o.put("pub", t.pub); o.put("pp", t.pubPrec)
        t.paid?.let { o.put("paid", it) }
        if (t.fee > 0) o.put("fee", t.fee)
        if (t.gear.isNotEmpty()) o.put("gear", JSONArray().apply { t.gear.forEach { put(it) } })
        o.put("names", JSONArray().apply { t.names.forEach { put(it) } })
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
            Array(maxOf(2, n.length())) { i -> n.optString(i, "${i + 1}번") }, list, track, o.optBoolean("up", false),
            o.optString("boat", ""), o.optString("port", ""), photos, o.optBoolean("bk", false), o.optString("cat", ""),
            o.optBoolean("pub", false), o.optString("pp", ""),
            if (o.has("paid")) o.getBoolean("paid") else null, o.optInt("fee", 0),
            o.optJSONArray("gear")?.let { g -> (0 until g.length()).map { g.getString(it) }.toMutableList() } ?: mutableListOf())
    }

    private fun saveCurrent() {
        val cur = current
        val e = prefs.edit()
        if (cur == null) e.remove("current") else e.putString("current", tripToJson(cur).toString())
        e.apply()
    }

    // ---- 지난 출조는 출조마다 파일 하나 (바뀐 출조만 다시 씀) ----
    private val savedSig = HashMap<Long, Int>()

    /** 저장할 내용이 바뀌었는지 빨리 보는 값 (전체를 JSON으로 만들지 않고) */
    private fun signature(t: Trip): Int = listOf(
        t.end, t.names.joinToString("|"), t.catches.size, t.catches.lastOrNull()?.time, t.track.size, t.track.lastOrNull()?.time,
        t.photos.size, t.photos.sumOf { (it.file.hashCode().toLong() + (it.catchTime ?: 0L)) },
        t.boat, t.port, t.cat, t.pub, t.pubPrec, t.uploaded, t.backedUp, t.paid, t.fee, t.gear.joinToString("|")
    ).hashCode()

    private fun tripFile(id: Long) = java.io.File(tripDir, "$id.json")

    private fun writeTrip(t: Trip) {
        val tmp = java.io.File(tripDir, "${t.id}.tmp")
        tmp.writeText(tripToJson(t).toString())
        if (!tmp.renameTo(tripFile(t.id))) { tripFile(t.id).delete(); tmp.renameTo(tripFile(t.id)) }
        savedSig[t.id] = signature(t)
    }

    private fun save() {
        val e = prefs.edit()
        val cur = current
        if (cur == null) e.remove("current") else e.putString("current", tripToJson(cur).toString())
        e.apply()
        try {
            history.forEach { t -> if (savedSig[t.id] != signature(t)) writeTrip(t) }
            // 지운 출조는 파일도 지움
            val ids = history.map { it.id }.toSet()
            savedSig.keys.filter { it !in ids }.forEach { id -> tripFile(id).delete(); savedSig.remove(id) }
        } catch (_: Exception) {}
    }

    private fun load() {
        try {
            prefs.getString("current", null)?.let { current = tripFromJson(JSONObject(it)) }
        } catch (_: Exception) {}
        history.clear()
        // 예전 버전: 모든 기록이 한 덩어리로 저장돼 있음 → 출조마다 파일로 옮기기 (한 번만)
        val old = prefs.getString("history", null)
        if (old != null) {
            try {
                val h = JSONArray(old)
                for (i in 0 until h.length()) try { history.add(tripFromJson(h.getJSONObject(i))) } catch (_: Exception) {}
                history.forEach { writeTrip(it) }
                prefs.edit().remove("history").commit()
            } catch (_: Exception) {}
        } else {
            tripDir.listFiles()?.filter { it.name.endsWith(".json") }?.forEach { f ->
                try {
                    val t = tripFromJson(JSONObject(f.readText()))
                    history.add(t)
                    savedSig[t.id] = signature(t)
                } catch (_: Exception) { /* 손상된 파일은 건너뜀 */ }
            }
        }
        history.sortByDescending { it.start }
        // 종류가 비어 있는 예전 출조: 어종으로 한 번 자동으로 채움
        history.filter { it.cat.isBlank() }.forEach { t ->
            val g = guessCat(t.names.toList())
            if (g.isNotBlank()) { t.cat = g; t.backedUp = false; try { writeTrip(t) } catch (_: Exception) {} }
        }
    }

    // ---- 이용료 · 준비물 ----
    /** 50000 → "5만원", 15000 → "15,000원" */
    fun feeText(fee: Int): String =
        if (fee >= 10_000 && fee % 10_000 == 0) "${fee / 10_000}만원" else String.format(java.util.Locale.KOREA, "%,d원", fee)

    fun costLabel(paid: Boolean?, fee: Int): String = when (paid) {
        true -> if (fee > 0) "유료 " + feeText(fee) else "유료"
        false -> "무료"
        null -> ""
    }

    private val DEFAULT_GEAR = mapOf(
        "night" to listOf("장화", "헤드랜턴", "집게", "뜰채", "장갑", "바구니", "여분 배터리"),
        "boat" to listOf("에기", "봉돌", "채비", "구명조끼", "멀미약", "쿨러", "장갑"),
        "walk" to listOf("낚싯대", "채비", "미끼", "구명조끼", "갯바위 신발", "장갑", "쿨러")
    )

    /** 고를 수 있는 준비물 (기본 + 내가 적은 것) */
    fun gearOptions(cat: String): List<String> = (DEFAULT_GEAR[cat].orEmpty() + readList("gear_$cat")).distinct()

    fun addGearOption(cat: String, name: String) {
        val n = name.trim()
        if (n.isEmpty() || n in gearOptions(cat)) return
        val l = readList("gear_$cat"); l.add(n)
        val a = JSONArray(); l.forEach { a.put(it) }
        prefs.edit().putString("gear_$cat", a.toString()).apply()
    }

    class Cost(val paid: Boolean?, val fee: Int, val gear: List<String>)

    private fun costKey(place: String, cat: String) = place.ifBlank { "종류:$cat" }

    /** 같은 장소(배·항구)에서 지난번에 적은 이용료·준비물 */
    fun lastCost(place: String, cat: String): Cost? {
        val o = try { JSONObject(prefs.getString("costByPlace", "{}") ?: "{}") } catch (_: Exception) { return null }
        val c = o.optJSONObject(costKey(place, cat)) ?: return null
        val g = c.optJSONArray("g")
        return Cost(if (c.has("p")) c.getBoolean("p") else null, c.optInt("f", 0),
            if (g == null) emptyList() else (0 until g.length()).map { g.getString(it) })
    }

    /** 출조에 이용료·준비물 저장 (+ 같은 장소 다음번에 미리 채우게 기억) */
    fun setCost(tripId: Long, c: Cost) {
        val t = find(tripId) ?: return
        t.paid = c.paid; t.fee = c.fee
        t.gear.clear(); t.gear.addAll(c.gear)
        t.backedUp = false
        val o = try { JSONObject(prefs.getString("costByPlace", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val co = JSONObject()
        c.paid?.let { co.put("p", it) }
        co.put("f", c.fee)
        co.put("g", JSONArray().apply { c.gear.forEach { put(it) } })
        o.put(costKey(t.place(), t.cat), co)
        prefs.edit().putString("costByPlace", o.toString()).apply()
        changed()
    }

    /** 친구 공유·백업용 텍스트 */
    fun exportText(t: Trip): String {
        val df = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.KOREA)
        val sb = StringBuilder()
        sb.append("[조과기록] ").append(df.format(java.util.Date(t.start)))
        if (t.end > 0) sb.append(" ~ ").append(df.format(java.util.Date(t.end)).substring(11))
        if (t.place().isNotBlank()) sb.append("\n").append(t.place())
        sb.append("\n").append(t.line(" / ")).append("\n\n")
        t.catches.forEach { sb.append(df.format(java.util.Date(it.time)).substring(11)).append("  ").append(t.name(it.slot)).append("\n") }
        return sb.toString()
    }
}
