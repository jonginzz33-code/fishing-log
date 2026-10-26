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

    class Trip(
        val id: Long,
        val start: Long,
        var end: Long,
        val names: Array<String>,
        val catches: MutableList<Catch>,
        val track: MutableList<Pt> = mutableListOf(),
        var uploaded: Boolean = false
    ) {
        fun count(slot: Int) = catches.count { it.slot == slot }
        fun name(slot: Int) = names[slot - 1]
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
    private fun changed() { save(); fireChanged() }

    // ---- 리모컨 버튼 등록 ----
    var key1: String?
        get() = prefs.getString("key1", null)
        set(v) { prefs.edit().putString("key1", v).apply(); fireChanged() }
    var key2: String?
        get() = prefs.getString("key2", null)
        set(v) { prefs.edit().putString("key2", v).apply(); fireChanged() }

    // ---- 설정 ----
    /** 다음 출조에 쓸 어종 이름 (출조 전에도 바꿀 수 있음) */
    fun defaultName(slot: Int): String = prefs.getString("name$slot", null) ?: "${slot}번"

    /** 리모컨 없이 카드를 눌러 기록하기 */
    var touchMode: Boolean
        get() = prefs.getBoolean("touch", true)
        set(v) { prefs.edit().putBoolean("touch", v).apply(); fireChanged() }

    /** 그룹에 올릴 때 이동 경로는 빼기 (조과 위치는 올라감) */
    var hideRoute: Boolean
        get() = prefs.getBoolean("hideRoute", false)
        set(v) { prefs.edit().putBoolean("hideRoute", v).apply(); fireChanged() }

    /** 지도 기본 모양: true = 위성 */
    var satellite: Boolean
        get() = prefs.getBoolean("satellite", true)
        set(v) { prefs.edit().putBoolean("satellite", v).apply() }

    // ---- 출조 ----
    fun startTrip() {
        if (current != null) return
        val now = System.currentTimeMillis()
        current = Trip(now, now, 0L, arrayOf(defaultName(1), defaultName(2)), mutableListOf())
        Tracker.last?.let { addTrackPointInternal(it.time, it.latitude, it.longitude) }
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
        changed()
        return t.count(slot)
    }

    /** 마지막 기록 취소. 취소된 기록의 슬롯 번호, 없으면 0 */
    fun undoLast(): Int {
        val t = current ?: return 0
        if (t.catches.isEmpty()) return 0
        val c = t.catches.removeAt(t.catches.size - 1)
        changed()
        return c.slot
    }

    fun addTrackPoint(time: Long, lat: Double, lon: Double) {
        if (current == null) return
        addTrackPointInternal(time, lat, lon)
        save()
    }

    private fun addTrackPointInternal(time: Long, lat: Double, lon: Double) {
        current?.track?.add(Pt(time, lat, lon))
    }

    /** 지금 출조(있으면)와 다음 출조 기본값을 함께 바꿈 */
    fun rename(slot: Int, name: String) {
        val v = name.ifBlank { "${slot}번" }
        prefs.edit().putString("name$slot", v).apply()
        current?.names?.set(slot - 1, v)
        changed()
    }

    fun renameInHistory(tripId: Long, slot: Int, name: String) {
        val t = history.firstOrNull { it.id == tripId } ?: return
        t.names[slot - 1] = name.ifBlank { "${slot}번" }
        t.uploaded = false
        changed()
    }

    fun markUploaded(tripId: Long) {
        history.firstOrNull { it.id == tripId }?.uploaded = true
        changed()
    }

    fun deleteTrip(tripId: Long) {
        history.removeAll { it.id == tripId }
        changed()
    }

    // ---- 저장/불러오기 (JSON) ----
    private fun tripToJson(t: Trip): JSONObject {
        val o = JSONObject()
        o.put("id", t.id); o.put("start", t.start); o.put("end", t.end); o.put("up", t.uploaded)
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
        return o
    }

    private fun tripFromJson(o: JSONObject): Trip {
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
        return Trip(o.getLong("id"), o.getLong("start"), o.optLong("end", 0L),
            arrayOf(n.getString(0), n.getString(1)), list, track, o.optBoolean("up", false))
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
        sb.append("\n").append(t.name(1)).append(" ").append(t.count(1))
            .append(" / ").append(t.name(2)).append(" ").append(t.count(2)).append("\n\n")
        t.catches.forEach { sb.append(df.format(java.util.Date(it.time)).substring(11)).append("  ").append(t.name(it.slot)).append("\n") }
        return sb.toString()
    }
}
