package com.fishlog.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 조과 기록 저장소. 모든 호출은 메인 스레드에서. */
object Store {

    class Catch(val time: Long, val slot: Int)

    class Trip(
        val id: Long,
        val start: Long,
        var end: Long,
        val names: Array<String>,
        val catches: MutableList<Catch>
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
    private fun changed() { save(); listeners.toList().forEach { it() } }

    // ---- 리모컨 버튼 등록 ----
    var key1: String?
        get() = prefs.getString("key1", null)
        set(v) { prefs.edit().putString("key1", v).apply(); listeners.toList().forEach { it() } }
    var key2: String?
        get() = prefs.getString("key2", null)
        set(v) { prefs.edit().putString("key2", v).apply(); listeners.toList().forEach { it() } }

    // ---- 출조 ----
    fun startTrip() {
        if (current != null) return
        val now = System.currentTimeMillis()
        current = Trip(now, now, 0L, arrayOf("1번", "2번"), mutableListOf())
        changed()
    }

    fun endTrip() {
        val t = current ?: return
        t.end = System.currentTimeMillis()
        history.add(0, t)
        current = null
        changed()
    }

    fun addCatch(slot: Int): Int {
        val t = current ?: return 0
        t.catches.add(Catch(System.currentTimeMillis(), slot))
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

    fun rename(slot: Int, name: String) {
        val t = current ?: return
        t.names[slot - 1] = name.ifBlank { "${slot}번" }
        changed()
    }

    fun renameInHistory(tripId: Long, slot: Int, name: String) {
        val t = history.firstOrNull { it.id == tripId } ?: return
        t.names[slot - 1] = name.ifBlank { "${slot}번" }
        changed()
    }

    fun deleteTrip(tripId: Long) {
        history.removeAll { it.id == tripId }
        changed()
    }

    // ---- 저장/불러오기 (JSON) ----
    private fun tripToJson(t: Trip): JSONObject {
        val o = JSONObject()
        o.put("id", t.id); o.put("start", t.start); o.put("end", t.end)
        o.put("names", JSONArray().put(t.names[0]).put(t.names[1]))
        val arr = JSONArray()
        t.catches.forEach { arr.put(JSONArray().put(it.time).put(it.slot)) }
        o.put("catches", arr)
        return o
    }

    private fun tripFromJson(o: JSONObject): Trip {
        val n = o.getJSONArray("names")
        val arr = o.getJSONArray("catches")
        val list = mutableListOf<Catch>()
        for (i in 0 until arr.length()) {
            val c = arr.getJSONArray(i)
            list.add(Catch(c.getLong(0), c.getInt(1)))
        }
        return Trip(o.getLong("id"), o.getLong("start"), o.optLong("end", 0L),
            arrayOf(n.getString(0), n.getString(1)), list)
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
