package com.fishlog.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 그룹 새 소식 (아직 안 본 일정·공지)
 *  - 앱을 열 때 가입한 그룹마다 일정·공지를 받아와서, 내가 '확인'하지 않은 것만 셈
 *  - 확인한 글 번호는 폰에 저장 (확인하면 빨간 숫자가 사라지고, 새 글이 생기면 다시 생김)
 *  - 내가 쓴 글, 이미 지난 일정은 새 소식에서 빠짐
 */
object GroupNews {

    class Item(val gid: String, val kind: String, val id: String, val title: String, val sub: String, val time: Long) {
        val isNotice get() = kind == "notice"
    }

    private val main = Handler(Looper.getMainLooper())
    private val rawEvents = HashMap<String, List<Cloud.Event>>()
    private val rawNotices = HashMap<String, List<Cloud.Notice>>()
    private val rawTrips = HashMap<String, List<Cloud.RemoteTrip>>()
    @Volatile private var unseen: Map<String, List<Item>> = emptyMap()
    private val upcoming = HashMap<String, Int>()
    private val today = HashMap<String, Int>()
    private val tomorrow = HashMap<String, Int>()
    private var loadedAt = 0L
    private var loading = false
    private val waiting = mutableListOf<() -> Unit>()

    private val keyFmt = SimpleDateFormat("yyyyMMdd", Locale.KOREA)
    private val showFmt = SimpleDateFormat("M/d(E)", Locale.KOREA)
    private val agoFmt = SimpleDateFormat("M/d HH:mm", Locale.KOREA)

    private fun prefs() = Cloud.appContext.getSharedPreferences("fishlog", Context.MODE_PRIVATE)

    fun items(gid: String?): List<Item> = gid?.let { unseen[it] }.orEmpty()
    fun count(gid: String?): Int = items(gid).size
    fun total(): Int = Cloud.groups.sumOf { count(it.id) }
    fun upcoming(gid: String?): Int = gid?.let { upcoming[it] } ?: 0
    fun today(gid: String?): Int = gid?.let { today[it] } ?: 0
    fun tomorrow(gid: String?): Int = gid?.let { tomorrow[it] } ?: 0
    /** 안 본 새 일정 / 새 공지 수 */
    fun newEvents(gid: String?): Int = items(gid).count { !it.isNotice }
    fun newNotices(gid: String?): Int = items(gid).count { it.isNotice }
    /** 새로 올라온 그룹 출조 (조과 후기) */
    fun newTrips(gid: String?): Int = items(gid).count { it.kind == "trip" }
    /** 공지 전체 개수 (내가 쓴 것 포함) */
    fun notices(gid: String?): Int = gid?.let { rawNotices[it]?.size } ?: 0

    // ---------------- 실시간 (앱이 켜져 있는 동안) ----------------
    private var watching: List<com.google.firebase.firestore.ListenerRegistration> = emptyList()
    private var watchKey = ""
    private val liveListeners = mutableSetOf<() -> Unit>()

    /** 가입한 그룹마다 일정·공지를 지켜봄. 바뀌면 onChange (메인 스레드) */
    fun startLive(onChange: () -> Unit) {
        liveListeners.add(onChange)
        if (Cloud.user == null || !Cloud.isInitialized()) return
        val key = Cloud.groups.joinToString(",") { it.id }
        if (key == watchKey && watching.isNotEmpty()) return
        stopWatching()
        watchKey = key
        watching = Cloud.groups.flatMap { g ->
            Cloud.watchGroup(g,
                { evs -> rawEvents[g.id] = evs; loadedAt = System.currentTimeMillis(); recompute(); fireLive() },
                { ns -> rawNotices[g.id] = ns; loadedAt = System.currentTimeMillis(); recompute(); fireLive() },
                { ts -> rawTrips[g.id] = ts; recompute(); fireLive() })
        }
    }

    fun stopLive(onChange: () -> Unit) {
        liveListeners.remove(onChange)
        if (liveListeners.isEmpty()) stopWatching()
    }

    private fun stopWatching() {
        watching.forEach { try { it.remove() } catch (_: Exception) {} }
        watching = emptyList(); watchKey = ""
    }

    private fun fireLive() = main.post { liveListeners.toList().forEach { it() } }

    private fun seenSet(gid: String): Set<String> = prefs().getStringSet("seen_$gid", emptySet()) ?: emptySet()

    /** 처음 보는 그룹은 사흘 전 글부터 새 소식으로 */
    private fun base(gid: String): Long {
        val p = prefs()
        val k = "newsBase_$gid"
        if (!p.contains(k)) p.edit().putLong(k, System.currentTimeMillis() - 3 * 24 * 3_600_000L).apply()
        return p.getLong(k, 0L)
    }

    private fun recompute() {
        val me = Cloud.uid
        val nowKey = keyFmt.format(Date())
        val tomKey = keyFmt.format(Date(System.currentTimeMillis() + 24 * 3_600_000L))
        val out = HashMap<String, List<Item>>()
        Cloud.groups.forEach { g ->
            val seen = seenSet(g.id)
            val b = base(g.id)
            val list = mutableListOf<Item>()
            rawEvents[g.id]?.let { evs ->
                upcoming[g.id] = evs.count { it.date >= nowKey }
                today[g.id] = evs.count { it.date == nowKey }
                tomorrow[g.id] = evs.count { it.date == tomKey }
                evs.filter { it.owner != me && it.date >= nowKey && it.created > b && it.id !in seen }.forEach { e ->
                    val day = try { showFmt.format(keyFmt.parse(e.date)!!) } catch (_: Exception) { e.date }
                    list.add(Item(g.id, "event", e.id,
                        listOf(day, e.time, e.place.ifBlank { e.boat }).filter { it.isNotBlank() }.joinToString(" "),
                        listOf(e.ownerName, agoFmt.format(Date(e.created)), Store.catShort(e.cat)).filter { it.isNotBlank() }.joinToString(" · "),
                        e.created))
                }
            }
            rawNotices[g.id]?.let { ns ->
                ns.filter { it.owner != me && maxOf(it.created, it.edited) > b && it.id !in seen }.forEach { n ->
                    list.add(Item(g.id, "notice", n.id, n.title,
                        n.ownerName + " · " + agoFmt.format(Date(maxOf(n.created, n.edited))), maxOf(n.created, n.edited)))
                }
            }
            rawTrips[g.id]?.let { ts ->
                ts.filter { it.owner != me && it.end > 0 && it.end > b && it.id !in seen }.forEach { t ->
                    val counts = t.slots().filter { t.count(it) > 0 }.joinToString(" · ") { "${t.name(it)} ${t.count(it)}" }
                    list.add(Item(g.id, "trip", t.id, "${t.ownerName} · ${showFmt.format(Date(t.start))} " + counts.ifBlank { "출조" },
                        listOfNotNull(t.place().ifBlank { null }, if (t.photoCount > 0) "사진 ${t.photoCount}장" else null).joinToString(" · ").ifBlank { "그룹 출조 기록" },
                        t.end))
                }
            }
            out[g.id] = list.sortedByDescending { it.time }
        }
        unseen = out
    }

    /** 서버에서 다시 받기 (force=false 면 1분 안엔 저장된 것으로) */
    fun load(force: Boolean = false, done: (() -> Unit)? = null) {
        if (Cloud.user == null || !Cloud.isInitialized()) { done?.invoke(); return }
        val gs = Cloud.groups
        if (gs.isEmpty()) { unseen = emptyMap(); done?.invoke(); return }
        if (!force && System.currentTimeMillis() - loadedAt < 60_000L) { recompute(); done?.invoke(); return }
        done?.let { waiting.add(it) }
        if (loading) return
        loading = true
        var left = gs.size * 2
        fun step() {
            left--
            if (left == 0) {
                loading = false
                loadedAt = System.currentTimeMillis()
                recompute()
                val ws = waiting.toList(); waiting.clear()
                main.post { ws.forEach { it() } }
            }
        }
        gs.forEach { g ->
            Cloud.loadEventsOf(g) { list, _ -> if (list != null) rawEvents[g.id] = list; step() }
            Cloud.loadNoticesOf(g) { list, _ -> if (list != null) rawNotices[g.id] = list; step() }
        }
    }

    /** 확인: 이 글들은 다시 새 소식에 안 나옴 */
    fun markSeen(list: List<Item>) {
        list.groupBy { it.gid }.forEach { (gid, its) ->
            val s = seenSet(gid).toMutableSet()
            s.addAll(its.map { it.id })
            prefs().edit().putStringSet("seen_$gid", s).apply()
        }
        recompute()
    }

    /** 일정/공지 화면을 열면 그 그룹의 그 종류는 모두 확인 */
    fun markKindSeen(gid: String?, kind: String) {
        if (gid == null) return
        markSeen(items(gid).filter { it.kind == kind })
    }

    /** 공지 화면에서 받아온 최신 목록으로 바꾸기 */
    fun putNotices(gid: String, list: List<Cloud.Notice>) { rawNotices[gid] = list; recompute() }
    fun putEvents(gid: String, list: List<Cloud.Event>) { rawEvents[gid] = list; recompute() }
}
