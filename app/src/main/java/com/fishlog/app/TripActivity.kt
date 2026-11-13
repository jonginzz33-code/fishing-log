package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import org.osmdroid.views.MapView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** 출조 기록 화면 (디자인 시안 2번) */
class TripActivity : Activity() {
    /** v3.9.2: 화면 크기 자동 맞춤 (Ui.fit) */
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(Ui.fit(newBase)) }


    private var tripId = 0L
    private lateinit var root: LinearLayout
    private var map: MapView? = null
    private lateinit var account: AccountUi
    private val main = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { render() }
    private var tideDay: Tide.Day? = null
    private var tideStrength: Tide.Strength? = null
    private var tideMsg = "물때 불러오는 중…"

    private val tf = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    private val hm = SimpleDateFormat("HH:mm", Locale.KOREA)
    private val df = SimpleDateFormat("yyyy년 M월 d일 (E)", Locale.KOREA)

    private fun dp(v: Int) = Ui.dp(this, v)
    private val trip: Store.Trip? get() = remoteTrip ?: Store.find(tripId)

    // ---- v5.2 (239): 그룹 출조 기록에서 남의 출조 보기 (보기 전용) ----
    companion object {
        /** 그룹 출조 목록에서 넘겨주는 출조 (Intent로 넘기기엔 커서 잠깐 보관) */
        var remoteView: Cloud.RemoteTrip? = null
        const val REQ_REMOTE = 7239
    }
    private var remote: Cloud.RemoteTrip? = null
    private var remoteTrip: Store.Trip? = null
    private val viewOnly get() = remote != null
    /** v6.9 (255): 전체 게시판 글을 보는 중 (그룹 기록이 아님) */
    private val isPub get() = remote?.pubId?.isNotBlank() == true

    private fun toTrip(r: Cloud.RemoteTrip): Store.Trip = Store.Trip(
        id = r.start, start = r.start, end = if (r.end > 0) r.end else r.start,
        names = r.names.toTypedArray(),
        catches = r.catches.map { Store.Catch(it.time, it.slot, it.lat, it.lon) }.sortedBy { it.time }.toMutableList(),
        track = r.track.toMutableList(),
        boat = r.boat, port = r.port, cat = r.cat, paid = r.paid, fee = r.fee,
        gear = r.gear.toMutableList(), wx = r.wx)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        account = AccountUi(this)
        tripId = intent.getLongExtra("id", 0L)
        if (intent.getBooleanExtra("remote", false)) {
            remote = remoteView
            remote?.let { r ->
                remoteTrip = toTrip(r)
                // 이동 경로는 따로 받기 (목록에는 경로가 없음)
                // v6.10 (257): 게시판 글 경로
                // (경로를 안 올린 예전 글도 같은 그룹에 올라간 출조면 그 경로를 가져와요)
                if (r.pubId.isNotBlank() && r.pubExact) PublicBoard.loadTrack(r.pubId) { pts ->
                    val cur = remote
                    if (!isFinishing && pts.size >= 2 && cur != null) { remote = cur.withTrack(pts); remoteTrip = toTrip(remote!!); render() }
                }
                // v6.15 (260): 같은 그룹에 같은 출조가 있으면 그룹 기록의 어종 이름 (관리자가 고친 이름이 게시판엔 아직 안 바뀌었을 수 있어서)
                if (r.pubId.isNotBlank()) Cloud.groupNamesOf(r.pubId) { ns ->
                    val cur = remote
                    if (!isFinishing && cur != null && ns != null && ns.isNotEmpty() && ns != cur.names) {
                        remote = cur.withNames(ns, cur.editAt, cur.editName); remoteTrip = toTrip(remote!!); render()
                    }
                }
                else if (r.hasTrack && r.track.isEmpty()) Cloud.loadTracks(listOf(r)) { l ->
                    l.firstOrNull()?.let { full -> if (!isFinishing && full.track.isNotEmpty()) { remote = full; remoteTrip = toTrip(full); loadTide(); render() } }
                }
            }
        }
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        root = Ui.column(this)
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(root) })
        loadTide()
        if (savedInstanceState == null && intent.getBooleanExtra("publish", false)) {
            trip?.let { t -> root.post { doPublish(t) } }
        }
    }

    /** 이미 올린 글의 이름(익명/닉네임)·그룹 표시 바꾸기 → 다시 올리기 */
    private fun changeDisplay(t: Store.Trip) {
        val names = arrayOf("익명", "닉네임 (${Cloud.nick ?: "아직 없음"})")
        android.app.AlertDialog.Builder(this, R.style.FlAlert)
            .setTitle("게시판에 보일 이름")
            .setItems(names) { _, i ->
                if (i == 1 && Cloud.nick == null) { Toast.makeText(this, "닉네임이 없어요 · ⚙ 개인 설정에서 먼저 정해 주세요", Toast.LENGTH_LONG).show(); return@setItems }
                Store.nick = if (i == 0) "" else "me"
                val gs = Cloud.groups
                val items = arrayOf("그룹 표시 안 함") + gs.map { it.title }
                android.app.AlertDialog.Builder(this, R.style.FlAlert)
                    .setTitle("같이 보일 그룹")
                    .setItems(items) { _, j -> Store.pubGroup = if (j == 0) "" else gs[j - 1].id; doPublish(t) }
                    .show()
            }.show()
    }

    private fun doPublish(t: Store.Trip) {
        Toast.makeText(this, "전체 게시판에 올리는 중…", Toast.LENGTH_SHORT).show()
        PublicBoard.publishFlow(this, t) { err ->
            Toast.makeText(this, err ?: "전체 게시판에 공개했어요", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() { super.onResume(); Store.addListener(listener); render(); map?.onResume() }
    override fun onPause() { map?.onPause(); Store.removeListener(listener); super.onPause() }

    private fun loadTide() {
        val t = trip ?: return
        val loc = t.anyLocation()
        if (t.isFresh()) { tideMsg = ""; return }   // v6.9 (254): 민물은 물때 안 받음
        if (loc == null) { tideMsg = "위치 기록이 없어서 조위 그래프는 못 불러와요"; return }
        Tide.fetch(this, loc.first, loc.second, t.start) { day, err ->
            tideDay = day
            tideMsg = err ?: ""
            render()
            if (day != null) Tide.strength(this, day) { st -> tideStrength = st; if (!isFinishing) { render(); loadNearTides(t) } }
        }
    }

    private val openHours = mutableSetOf<String>()

    // ---------------- v6.16 (261-2): 조과마다 가장 가까운 관측소로 물살 % ----------------
    /** 출조 기준과 다른 관측소의 물때 (관측소 코드 → 그날 물때 · 물세기) · 출조 하나에 최대 2곳 더 (총 3곳) */
    private val nearTides = HashMap<String, Pair<Tide.Day, Tide.Strength>>()
    private val nearNames = LinkedHashMap<String, String>()
    private fun nearCode(lat: Double, lon: Double): String? = try { Tide.stationsNear(this, lat, lon).firstOrNull()?.code } catch (_: Exception) { null }

    /** 잡은 때 물살 % — 그 조과 자리에서 가장 가까운 관측소 기준 (못 받았으면 출조 기준 관측소) */
    private fun catchFlow(c: Store.Catch): Int? {
        val main = BiteMemo.flowAt(tideDay, tideStrength, c.time)
        val la = c.lat ?: return main; val lo = c.lon ?: return main
        val code = nearCode(la, lo) ?: return main
        if (code == tideDay?.stationCode) return main
        val e = nearTides[code] ?: return main
        return BiteMemo.flowAt(e.first, e.second, c.time) ?: main
    }

    private var nearLoading = false
    private fun loadNearTides(t: Store.Trip) {
        // 물때 창에서 관측소를 직접 골라 둔 경우엔 그 관측소만 씀 (기존 방식 그대로)
        if (nearLoading || t.isFresh() || Store.tideStation != null) return
        val mainCode = tideDay?.stationCode ?: return
        val groups = t.catches.filter { it.lat != null && it.lon != null }
            .groupBy { nearCode(it.lat!!, it.lon!!) }
            .filterKeys { it != null && it != mainCode && it !in nearTides }
            .entries.sortedByDescending { it.value.size }.take(2)
        if (groups.isEmpty()) return
        nearLoading = true
        val app = applicationContext
        Thread {
            groups.forEach { (code, cs) ->
                val c0 = cs.first()
                val d = try { Tide.fetchNow(app, c0.lat!!, c0.lon!!, t.start) } catch (_: Exception) { null }
                if (d != null && d.stationCode == code) {
                    val st = try { Tide.strengthOf(app, d) } catch (_: Exception) { null }
                    val name = try { Tide.stations(app).firstOrNull { it.code == code }?.name } catch (_: Exception) { null }
                    if (st != null) runOnUiThread { nearTides[code!!] = d to st; if (name != null) nearNames[code] = name }
                }
            }
            runOnUiThread { nearLoading = false; if (!isFinishing) render() }
        }.start()
    }

    // ---------------- v5.2 (240): 출조 후 조과 고치기 (올린 사람 · 관리자만) ----------------
    private var editMode = false
    private val picked: MutableSet<Store.Catch> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

    private fun canEdit(t: Store.Trip): Boolean {
        val r = remote
        if (r != null && r.pubId.isNotBlank()) return false   // 게시판 글은 여기서 못 고침 (내 기록에서 고치면 같이 바뀜)
        if (r != null) return Cloud.user != null && (r.owner == Cloud.uid || Cloud.isAdmin)
        return t.end > 0 && t !== Store.current
    }

    private fun keepScroll(block: () -> Unit) {
        val sv = root.parent as? ScrollView
        val y = sv?.scrollY ?: 0
        block()
        sv?.post { sv.scrollTo(0, y) }
    }

    /** 고친 조과 저장: 내 폰 기록(있으면) → 올린 그룹들 / 관리자는 그룹 기록 + 올린 사람 폰에 나중에 반영 */
    private fun commitCatches(t: Store.Trip, list: List<Store.Catch>, msg: String) {
        val r = remote
        val sorted = list.sortedBy { it.time }
        picked.clear()
        fun ok(e: String?) {
            Toast.makeText(this, e ?: msg, Toast.LENGTH_SHORT).show()
        }
        if (r == null) {
            Store.replaceCatches(t.id, sorted)
            Store.find(t.id)?.let { tt -> Cloud.pushCatchEdit(tt) { e -> if (!isFinishing) ok(e?.let { "폰 기록은 고쳤어요 · 그룹 · 게시판 기록은 못 고쳤어요 ($it)" }) } }
                ?: ok(null)
            keepScroll { render() }
            return
        }
        val mine = r.owner == Cloud.uid
        val local = if (mine) r.id.substringAfter("_").toLongOrNull()?.let { Store.find(it) } else null
        val after = { e: String? ->
            if (!isFinishing) {
                if (e == null) {
                    remote = r.withCatches(sorted.map { Cloud.RemoteCatch(it.time, it.slot, it.lat, it.lon) },
                        System.currentTimeMillis(), if (mine) Cloud.displayName else "관리자")
                    remoteTrip?.catches?.let { cs -> cs.clear(); cs.addAll(sorted) }
                    setResult(RESULT_OK, Intent().putExtra("edited", true))
                    keepScroll { render() }
                }
                ok(e)
            }
        }
        if (local != null) {
            Store.replaceCatches(local.id, sorted)
            Cloud.pushCatchEdit(Store.find(local.id) ?: local) { e -> after(e) }
        } else {
            val g = Cloud.active ?: return ok("그룹을 못 찾았어요")
            Cloud.updateCatches(g.id, r.id, sorted, if (mine) null else r.owner) { e -> after(e) }
        }
    }

    /** 한 마리 고치기 / 추가 (c == null 이면 추가) */
    private fun editCatchDialog(t: Store.Trip, c: Store.Catch?) {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = c?.time ?: (t.catches.lastOrNull()?.time ?: (if (t.end > 0) t.end else t.start)) }
        var slot = c?.slot ?: (t.catches.lastOrNull()?.slot ?: 1)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), 0) }
        val timeBtn = Ui.button(this, "") {}
        fun paintTime() { timeBtn.text = "시각  " + SimpleDateFormat("MM.dd HH:mm", Locale.KOREA).format(cal.time) + "  ✎" }
        paintTime()
        timeBtn.setOnClickListener {
            android.app.TimePickerDialog(this, { _, h, m ->
                cal.set(java.util.Calendar.HOUR_OF_DAY, h); cal.set(java.util.Calendar.MINUTE, m); paintTime()
            }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true).show()
        }
        col.addView(timeBtn, Ui.lp(this, MATCH, WRAP))
        col.addView(Ui.text(this, 13f, Ui.SUB, true, "어종"), Ui.lp(this, MATCH, WRAP, top = 12))
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val slots = ((1..maxOf(2, t.names.size)).toList() + t.catches.map { it.slot }).distinct().sorted()
        fun paintChips() {
            chips.removeAllViews()
            slots.forEach { sl ->
                val on = sl == slot
                val color = if (sl in 1..Ui.SLOT.size) Ui.SLOT[sl - 1] else Store.slotColor(sl)
                chips.addView(Ui.text(this, 14f, if (on) android.graphics.Color.parseColor("#111111") else Ui.TEXT, true, t.name(sl)).apply {
                    gravity = Gravity.CENTER; setPadding(dp(14), dp(8), dp(14), dp(8))
                    background = if (on) Ui.bg(this@TripActivity, color, 99) else Ui.bg(this@TripActivity, android.graphics.Color.TRANSPARENT, 99, Ui.LINE)
                    setOnClickListener { slot = sl; paintChips() }
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })
            }
        }
        paintChips()
        col.addView(Ui.hscroll(this, chips), Ui.lp(this, MATCH, WRAP, top = 6))
        if (c == null) col.addView(Ui.text(this, 11.5f, Ui.SUB, false, "추가한 조과는 위치 없이 들어가요"), Ui.lp(this, MATCH, WRAP, top = 10))
        AlertDialog.Builder(this, R.style.FlAlert)
            .setTitle(if (c == null) "＋ 조과 추가" else "✎ 한 마리 고치기")
            .setView(col)
            .setPositiveButton("저장") { _, _ ->
                val list = t.catches.toMutableList()
                if (c == null) {
                    list.add(Store.Catch(cal.timeInMillis, slot))
                    commitCatches(t, list, "${t.name(slot)} 1마리 추가했어요")
                } else {
                    val i = list.indexOfFirst { it === c }
                    if (i >= 0) list[i] = Store.Catch(cal.timeInMillis, slot, c.lat, c.lon, c.acc, c.q)
                    commitCatches(t, list, "고쳤어요")
                }
            }
            .setNegativeButton("취소", null).show()
    }

    private fun askDelete(t: Store.Trip, what: Collection<Store.Catch>) {
        if (what.isEmpty()) return
        val by = what.groupingBy { it.slot }.eachCount().entries.joinToString(" · ") { "${t.name(it.key)} ${it.value}" }
        AlertDialog.Builder(this, R.style.FlAlert)
            .setTitle("${what.size}마리 지울까요?")
            .setMessage("$by\n\n지우면 그룹 기록 · 순위에도 같이 반영돼요")
            .setPositiveButton("지우기") { _, _ ->
                val set: MutableSet<Store.Catch> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
                set.addAll(what)
                commitCatches(t, t.catches.filter { it !in set }, "${what.size}마리 지웠어요")
            }
            .setNegativeButton("취소", null).show()
    }

    /** 고치기 모드의 한 줄: ☐ 시각 · 어종 · ✎ · 🗑 */
    private fun editRow(t: Store.Trip, c: Store.Catch): View {
        val r = Ui.row(this).apply { setPadding(dp(4), dp(4), 0, dp(4)) }
        val on = c in picked
        r.addView(Ui.text(this, 14f, android.graphics.Color.WHITE, true, if (on) "✓" else "").apply {
            gravity = Gravity.CENTER
            background = if (on) Ui.bg(this@TripActivity, android.graphics.Color.parseColor("#FF6B6B"), 6)
                else Ui.bg(this@TripActivity, android.graphics.Color.TRANSPARENT, 6, Ui.SUB)
            setOnClickListener { if (on) picked.remove(c) else picked.add(c); keepScroll { render() } }
        }, LinearLayout.LayoutParams(dp(26), dp(26)))
        r.addView(Ui.text(this, 14f, Ui.TEXT, false, tf.format(Date(c.time))).apply { typeface = android.graphics.Typeface.MONOSPACE },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(10) })
        r.addView(View(this).apply { background = Ui.bg(this@TripActivity, (if (c.slot in 1..Ui.SLOT.size) Ui.SLOT[c.slot - 1] else Store.slotColor(c.slot)), 99) },
            LinearLayout.LayoutParams(dp(9), dp(9)).apply { leftMargin = dp(8) })
        r.addView(Ui.text(this, 14f, Ui.TEXT, false, t.name(c.slot)), LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        fun ic(label: String, color: Int, click: () -> Unit) = Ui.text(this, 13f, color, true, label).apply {
            gravity = Gravity.CENTER
            background = Ui.bg(this@TripActivity, android.graphics.Color.TRANSPARENT, 99, Ui.LINE)
            setOnClickListener { click() }
        }
        r.addView(ic("✎", Ui.TEXT) { editCatchDialog(t, c) }, LinearLayout.LayoutParams(dp(40), dp(32)).apply { leftMargin = dp(6) })
        r.addView(ic("🗑", android.graphics.Color.parseColor("#FF8A92")) { askDelete(t, listOf(c)) }, LinearLayout.LayoutParams(dp(40), dp(32)).apply { leftMargin = dp(6) })
        return r
    }

    /** 기록 한 줄: 사진 · 시각 · 어종 */
    private fun catchRow(t: Store.Trip, c: Store.Catch): View {
        val r = Ui.row(this).apply { setPadding(dp(6), dp(6), 0, dp(6)) }
        val ph = t.photoOf(c)
        if (ph != null) {
            r.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(Photos.thumbBitmap(this@TripActivity, ph))
                setOnClickListener { openPhoto(t, ph) }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
        } else {
            r.addView(View(this).apply { background = Ui.bg(this@TripActivity, android.graphics.Color.TRANSPARENT, 8, Ui.LINE) },
                LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        r.addView(Ui.text(this, 14f, Ui.TEXT, false, tf.format(Date(c.time))).apply { typeface = android.graphics.Typeface.MONOSPACE },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(12) })
        r.addView(View(this).apply { background = Ui.bg(this@TripActivity, (if (c.slot in 1..Ui.SLOT.size) Ui.SLOT[c.slot - 1] else Store.slotColor(c.slot)), 99) },
            LinearLayout.LayoutParams(dp(9), dp(9)).apply { leftMargin = dp(10) })
        r.addView(Ui.text(this, 14f, Ui.TEXT, false, t.name(c.slot) + if (c.lat != null) "" else "  (위치 없음)"),
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        // v6.15 (259): 잡은 때 물살 % (그날 물세기 기준)
        if (!t.isFresh()) catchFlow(c)?.let { f ->
            val fc = when (BiteMemo.flowLevel(f)) { 2 -> Ui.ORANGE; 1 -> Ui.BLUE; else -> android.graphics.Color.parseColor("#8E9AA3") }
            r.addView(Ui.text(this, 12f, fc, true, "💧$f%"), LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
        return r
    }

    /** 전체 보기: 큰 창에 모든 기록 */
    private fun showAllCatches(t: Store.Trip) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(4), dp(18), dp(8)) }
        col.addView(Ui.text(this, 15f, Ui.TEXT, true, "${t.line()} · 총 ${t.catches.size}마리"))
        t.catches.asReversed().forEach { c -> col.addView(catchRow(t, c)) }
        val dlg = AlertDialog.Builder(this, R.style.FlAlert)
            .setTitle("전체 기록")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("닫기", null)
            .show()
        dlg.window?.setLayout(Ui.dialogW(this), (resources.displayMetrics.heightPixels * 0.92).toInt())
    }

    private var wxAsked = false

    private fun renderWx(t: Store.Trip, card: LinearLayout) {
        card.removeAllViews()
        val snap = if (t.wx.isNotBlank()) Weather.snapFromJson(t.wx) else null
        if (snap == null && viewOnly) { card.addView(Ui.text(this, 13f, Ui.SUB, false, "이 출조에는 저장된 날씨가 없어요")); return }
        if (snap == null) {
            if (t.end <= 0) { card.addView(Ui.text(this, 13f, Ui.SUB, false, "출조를 완료하면 그 시간대 날씨가 여기에 저장돼요")); return }
            card.addView(Ui.text(this, 13f, Ui.SUB, false, if (wxAsked) "날씨를 못 받았어요 (인터넷 확인)" else "그날 날씨 불러오는 중… (한 번만 받아서 기록에 저장해요)"))
            if (!wxAsked) {
                wxAsked = true
                Weather.snapshot(this, t) { wx -> if (wx != null) Store.setWx(t.id, wx) else if (!isFinishing) renderWx(t, card) }
            }
            return
        }
        val hf = java.text.SimpleDateFormat("HH시", java.util.Locale.KOREA)
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun hc(txt: String, w: Float) = Ui.text(this, 11f, Ui.SUB, true, txt).apply { gravity = Gravity.CENTER }.also { head.addView(it, LinearLayout.LayoutParams(0, WRAP, w)) }
        hc("시각", 1f); hc("하늘", 0.8f); hc("기온", 0.9f); hc("바람", 1.5f); hc("파고", 1.3f); hc("수온", 1f)
        card.addView(head)
        snap.hours.forEach { h ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, dp(5)) }
            fun c(txt: String, w: Float, col: Int = Ui.TEXT) = Ui.text(this, 12.5f, col, false, txt).apply { gravity = Gravity.CENTER; maxLines = 1 }
                .also { r.addView(it, LinearLayout.LayoutParams(0, WRAP, w)) }
            val inTrip = h.time >= t.start - 1_800_000L && h.time <= (if (t.end > 0) t.end else h.time) + 1_800_000L
            c(hf.format(Date(h.time)), 1f, if (inTrip) Ui.TEXT else Ui.SUB)
            c(h.skyIcon().ifBlank { "-" }, 0.8f)
            c(h.temp?.let { String.format(java.util.Locale.KOREA, "%.0f°", it) } ?: "-", 0.9f)
            c(h.wind?.let { String.format(java.util.Locale.KOREA, "%.1fm/s", it) + (h.dir?.let { d -> " " + Weather.dir16(d) } ?: "") } ?: "-", 1.5f)
            c(h.wave?.let { String.format(java.util.Locale.KOREA, "%.1fm", it) + (h.period?.let { p -> String.format(java.util.Locale.KOREA, " %.0fs", p) } ?: "") } ?: "-", 1.3f)
            val ms = snap.measured[h.time]
            c((ms ?: h.sst)?.let { String.format(java.util.Locale.KOREA, "%.1f°", it) + if (ms != null) "실" else "" } ?: "-", 1f,
                if (ms != null) Ui.GREEN else Ui.TEXT)
            card.addView(r)
        }
        card.addView(Ui.text(this, 11f, Ui.SUB, false,
            "출조 기록에 저장된 값이에요 (다시 안 불러와요) · 수온 '실' = 관측소 실측" +
                (if (snap.station.isNotBlank()) " (${snap.station})" else "") + (if (snap.note.isNotBlank()) "\n" + snap.note else "")),
            Ui.lp(this, MATCH, WRAP, top = 6))
    }

    private fun section(title: String) =
        root.addView(Ui.text(this, 16f, Ui.TEXT, true, title), Ui.lp(this, MATCH, WRAP, top = 22))

    private fun render() {
        val t = trip
        map?.onPause(); map?.onDetach(); map = null
        root.removeAllViews()
        if (t == null) {
            root.addView(Ui.text(this, 16f, Ui.SUB, false, "기록을 찾을 수 없어요"))
            return
        }

        // 상단
        val top = Ui.row(this)
        top.addView(Ui.button(this, "← 뒤로") { finish() }.apply { minHeight = dp(44) }, LinearLayout.LayoutParams(WRAP, WRAP))
        remote?.let { r ->
            top.addView(Ui.text(this, 17f, Ui.TEXT, true, r.ownerName).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(12) })
            top.addView(Ui.text(this, 11.5f, android.graphics.Color.parseColor("#F2C14E"), true, if (r.pubId.isNotBlank()) "📰 게시판 · 보기 전용" else "👀 보기 전용").apply {
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = Ui.bg(this@TripActivity, android.graphics.Color.TRANSPARENT, 99, android.graphics.Color.parseColor("#F2C14E"))
            })
        }
        root.addView(top)
        val endTime = if (t.end > 0) t.end else System.currentTimeMillis()
        root.addView(Ui.text(this, 13f, Ui.SUB, false,
            (if (t.end > 0) "출조 완료" else "출조 중") + " · ${hm.format(Date(t.start))} ~ ${hm.format(Date(endTime))}"),
            Ui.lp(this, MATCH, WRAP, top = 14))
        root.addView(Ui.agoChip(this, t.start), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = Ui.dp(this@TripActivity, 4) })
        root.addView(Ui.text(this, 22f, Ui.TEXT, true, df.format(Date(t.start)) + " " +
            (if (viewOnly) t.place() else if (t.place().isBlank()) "배·항구 입력 ✎" else t.place() + " ✎")).apply {
            if (!viewOnly) setOnClickListener { askPlace(t) }
        }, Ui.lp(this, MATCH, WRAP, top = 2))
        val fresh = t.isFresh()
        root.addView(Ui.text(this, 13f, Ui.SUB, false,
            (if (t.cat.isNotBlank()) Store.catLabel(t.cat) + " · " else if (fresh) "🏞 민물 · " else "") +
                if (fresh) SunPhase.riseSet(t.start, t.anyLocation()?.first, t.anyLocation()?.second) else Tide.mulName(t.start)), Ui.lp(this, MATCH, WRAP, top = 4))

        // 합계
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stats.addView(Ui.stat(this, "총 조과", t.catches.size.toString()), LinearLayout.LayoutParams(0, WRAP, 1f))
        stats.addView(Ui.stat(this, t.name(1), t.count(1).toString(), Ui.ORANGE), LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        stats.addView(Ui.stat(this, t.name(2), t.count(2).toString(), Ui.BLUE), LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        root.addView(stats, Ui.lp(this, MATCH, WRAP, top = 16))
        // 퀵 버튼(3번 이상)으로 잡은 어종
        val extra = t.slots().filter { it >= 3 && t.count(it) > 0 }
        extra.chunked(3).forEach { ch ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            ch.forEachIndexed { i, sl -> r.addView(Ui.stat(this, t.name(sl), t.count(sl).toString(), Store.slotColor(sl)),
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) leftMargin = dp(8) }) }
            repeat(3 - ch.size) { r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(8) }) }
            root.addView(r, Ui.lp(this, MATCH, WRAP, top = 8))
        }

        // 이용료 · 준비물
        // 이용료 · 준비물: 펼친 채로 바로 고치기 (안 적었으면 같은 배·항구 지난번 것으로 미리 채움)
        // 그날 날씨 (출조 기록에 저장된 값 · 없으면 한 번만 받아서 저장)
        section("그날 날씨")
        val wxCard = Ui.card(this)
        root.addView(wxCard, Ui.lp(this, MATCH, WRAP, top = 8))
        renderWx(t, wxCard)

        if (isPub) {
            // 게시판은 유료 여부만 (금액 · 준비물은 안 올라감)
            if (t.paid == true) {
                section("이용료")
                val cc = Ui.card(this)
                cc.addView(Ui.text(this, 14f, Ui.TEXT, false, "💳 유료 자리 (금액은 게시판에 안 올라가요)"))
                root.addView(cc, Ui.lp(this, MATCH, WRAP, top = 10))
            }
        } else {
        section("이용료 · 준비물")
        if (viewOnly) {
            val cc = Ui.card(this)
            cc.addView(Ui.text(this, 14f, Ui.TEXT, false, listOf(
                t.costLabel().ifBlank { "이용료 안 적음" }.let { "💳 $it" },
                if (t.gear.isEmpty()) "" else "🎒 " + t.gear.joinToString(" · ")).filter { it.isNotBlank() }.joinToString("\n")))
            cc.addView(Ui.text(this, 11f, Ui.SUB, false, "그룹원만 보여요"), Ui.lp(this, MATCH, WRAP, top = 4))
            root.addView(cc, Ui.lp(this, MATCH, WRAP, top = 10))
        } else {
        val costCard = Ui.card(this)
        val tripCat = t.cat.ifBlank { Store.category }
        val hasCost = t.paid != null || t.gear.isNotEmpty()
        val ed = CostUi.editor(this, tripCat, if (hasCost) Store.Cost(t.paid, t.fee, t.gear.toList()) else Store.lastCost(t.place(), tripCat))
        costCard.addView(ed.view)
        val saveMsg = Ui.text(this, 12f, Ui.GREEN, false, if (hasCost) "저장됨: " + t.costLabel().ifBlank { "이용료 안 적음" } else "아직 저장 안 했어요")
        costCard.addView(Ui.button(this, "이용료 · 준비물 저장", true) {
            CostUi.save(t, ed.read())
            saveMsg.text = "저장했어요" + if (Store.sharedGroups(t.id).isNotEmpty()) " · 올린 그룹에도 반영했어요" else ""
        }.apply { minHeight = dp(46) }, Ui.lp(this, MATCH, WRAP, top = 12))
        costCard.addView(saveMsg, Ui.lp(this, MATCH, WRAP, top = 6))
        root.addView(costCard, Ui.lp(this, MATCH, WRAP, top = 10))
        }
        }

        // 지도
        section("이동 경로 · 잡은 위치")
        val m = MapDialog.preview(this, t) { MapDialog.showTrip(this, t) }
        map = m
        root.addView(m, Ui.lp(this, MATCH, dp(240), top = 10))
        m.onResume()
        root.addView(Ui.text(this, 12f, Ui.SUB, false,
            if (isPub) (if (remote?.pubExact == true) "📍 정확한 위치 공개" else "◯ 대략 공개 · 점 근처(약 500m 안) 어딘가에서 잡았어요") +
                " · 주황 ${t.name(1)} · 하늘 ${t.name(2)} · " +
                (if (t.track.size >= 2) "분홍 선 이동 경로 · ${MapDialog.distanceText(t.track.map { org.osmdroid.util.GeoPoint(it.lat, it.lon) })}"
                 else if (remote?.pubExact == true) "이동 경로 찾는 중… (없으면 올린 사람이 공개하지 않은 거예요)" else "대략 공개 글은 이동 경로가 올라가지 않아요") +
                " · 지도를 누르면 크게 보기"
            else if (viewOnly && t.track.size < 2) "주황 ${t.name(1)} · 하늘 ${t.name(2)} · " +
                (if (remote?.hasTrack == true) "이동 경로 불러오는 중…" else "올린 사람이 경로를 숨겼거나 위치 기록이 없어요") + " · 지도를 누르면 크게 보기"
            else "주황 ${t.name(1)} · 하늘 ${t.name(2)} · 분홍 선 이동 경로 · 지도를 누르면 크게 보기"), Ui.lp(this, MATCH, WRAP, top = 6))

        // 머문 곳
        val stays = stays(t)
        if (stays.isNotEmpty()) {
            section("머문 곳")
            stays.forEachIndexed { i, s ->
                val c = Ui.card(this)
                val r = Ui.row(this)
                r.addView(Ui.text(this, 13f, Ui.BG, true, "${i + 1}").apply {
                    gravity = Gravity.CENTER
                    background = Ui.bg(this@TripActivity, Ui.TEXT, 99)
                }, LinearLayout.LayoutParams(dp(28), dp(28)))
                val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val mins = (s.second - s.first) / 60000
                mid.addView(Ui.text(this, 14f, Ui.TEXT, false, "${hm.format(Date(s.first))} – ${hm.format(Date(s.second))} · ${mins / 60}시간 ${mins % 60}분"))
                val inStay = t.catches.filter { it.time in s.first..s.second }
                mid.addView(Ui.text(this, 13f, Ui.SUB, false,
                    "${t.name(1)} ${inStay.count { it.slot == 1 }} · ${t.name(2)} ${inStay.count { it.slot == 2 }}"))
                r.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(12) })
                c.addView(r)
                root.addView(c, Ui.lp(this, MATCH, WRAP, top = 8))
            }
        }

        // v6.9 (254): 민물이면 물때 대신 '🌅 해 · 시간대'
        if (fresh) renderSun(t) else {
        // 물때
        section("물때")
        val tideCard = Ui.card(this)
        val d = tideDay
        if (d != null) {
            val tv = TideView(this)
            tv.set(d, t)
            tideCard.addView(TideLayers.view(this) { tv.invalidate() }, Ui.lp(this, MATCH, WRAP))
            tideCard.addView(tv, Ui.lp(this, MATCH, dp(296)))
            tideStrength?.let { st ->
                tideCard.addView(Ui.text(this, 14f, Ui.TEXT, true, TideDialog.strengthLine(d, st)), Ui.lp(this, MATCH, WRAP, top = 8))
                val flow = FlowBarView(this)
                flow.set(d, st)
                tideCard.addView(flow, Ui.lp(this, MATCH, dp(64), top = 2))
                tideCard.addView(Ui.text(this, 11f, Ui.SUB, false, "막대 = 시간별 물흐름 세기 (높고 주황일수록 물살이 셈)"), Ui.lp(this, MATCH, WRAP, top = 2))
            }
            tideCard.addView(Ui.text(this, 13f, Ui.TEXT, false, TideDialog.describe(d, t)), Ui.lp(this, MATCH, WRAP, top = 6))
            tideCard.addView(Ui.text(this, 11f, Ui.SUB, false, d.source), Ui.lp(this, MATCH, WRAP, top = 4))
            if (nearNames.isNotEmpty()) tideCard.addView(Ui.text(this, 11f, Ui.SUB, false,
                "💧 잡은 때 물살 %는 조과마다 가장 가까운 관측소 기준 (그 밖에 " + nearNames.values.joinToString(" · ") + ")"), Ui.lp(this, MATCH, WRAP, top = 2))
        } else {
            tideCard.addView(Ui.text(this, 13f, Ui.SUB, false, tideMsg))
        }
        root.addView(tideCard, Ui.lp(this, MATCH, WRAP, top = 10))
        }

        // 입질 시간 메모 (일출·일몰 기준)
        val peaks = BiteMemo.forTrip(t, if (fresh) null else tideDay, if (fresh) null else tideStrength, if (fresh) null else { c -> catchFlow(c) })
        if (peaks.isNotEmpty()) {
            section("입질 시간 메모 (일출·일몰 기준)")
            Sun.times(t.start, t.anyLocation()?.first, t.anyLocation()?.second)?.let { s ->
                root.addView(Ui.text(this, 12f, Ui.SUB, false, "일출 ${hm.format(Date(s.rise))} · 일몰 ${hm.format(Date(s.set))}" +
                    (if (t.anyLocation() == null) " (서해 중부 기준)" else "")), Ui.lp(this, MATCH, WRAP, top = 4))
            }
            BiteMemo.tripCards(this, peaks).forEach { root.addView(it, Ui.lp(this, MATCH, WRAP, top = 8)) }
        }

        // 시간대별 입질
        if (t.catches.isNotEmpty()) {
            section("시간대별 입질")
            val bc = Ui.card(this)
            val bars = BarView(this)
            bars.set(t)
            bc.addView(bars, Ui.lp(this, MATCH, dp(110)))
            root.addView(bc, Ui.lp(this, MATCH, WRAP, top = 10))
        }

        // v5.2: 보기 전용이면 서버 사진
        remote?.let { r ->
            if (r.mthumb.isNotBlank() || r.photoCount > 0) section("📸 사진 ${r.photoCount}장")
            if (r.mthumb.isNotBlank()) root.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                background = Ui.bg(this@TripActivity, android.graphics.Color.parseColor("#0F2233"), 12, Ui.LINE)
                MainPhoto.decode(r.mthumb)?.let { setImageBitmap(it) }
                setOnClickListener {
                    if (r.pubId.isNotBlank()) MainPhoto.showBig(this@TripActivity, "public/${r.pubId}/photos/main", r.mthumb, "${r.ownerName} · ${r.line()}")
                    else Cloud.active?.let { g -> MainPhoto.showBig(this@TripActivity, r.bigMainPath(g.id), r.mthumb, "${r.ownerName} · ${r.line()}") }
                }
            }, LinearLayout.LayoutParams(dp(140), dp(140)).apply { topMargin = dp(8) })
            if (r.photoCount > 0 && r.pubId.isNotBlank()) {
                // 게시판 사진: 옆으로 밀어서 보기
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                root.addView(Ui.hscroll(this, row), Ui.lp(this, MATCH, WRAP, top = 10))
                PublicBoard.loadPhotos(r.pubId) { ps ->
                    if (isFinishing) return@loadPhotos
                    ps.filter { it.id != r.mainId }.forEach { p ->
                        row.addView(ImageView(this).apply {
                            setImageBitmap(p.bitmap); scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                            background = Ui.bg(this@TripActivity, Ui.CARD, 10)
                            setOnClickListener { ZoomImageView.show(this@TripActivity, p.bitmap, hm.format(Date(p.time))) }
                        }, LinearLayout.LayoutParams(dp(110), dp(140)).apply { rightMargin = dp(6) })
                    }
                }
            } else if (r.photoCount > 0) root.addView(Ui.button(this, "사진 모두 보기 (${r.photoCount}장)") { account.showPhotos(r) }, Ui.lp(this, MATCH, WRAP, top = 10))
        }
        if (!viewOnly) {
        // 대표 조과 사진: 바꾸기(찍기·앨범) · 이 출조 사진 중 고르기 · 빼기
        section("📸 대표 조과 사진")
        val mr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val mainP = t.main()
        val mb = mainP?.let { Photos.thumbBitmap(this, it) }
        mr.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
            background = Ui.bg(this@TripActivity, android.graphics.Color.parseColor("#0F2233"), 12, Ui.LINE)
            if (mb != null) setImageBitmap(mb)
            if (mainP != null) setOnClickListener { openPhoto(t, mainP) }
        }, LinearLayout.LayoutParams(dp(110), dp(110)))
        val mbc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun mbt(label: String, col: Int = Ui.TEXT, click: () -> Unit) = mbc.addView(Ui.button(this, label) { click() }.apply {
            minHeight = dp(36); textSize = 12.5f; setTextColor(col)
        }, Ui.lp(this, MATCH, WRAP, top = 4))
        mbt(if (mainP == null) "📷 찍어서 넣기" else "📷 다시 찍기") { MainPhoto.camera(this, t.id) }
        mbt("🖼 앨범에서 고르기") { MainPhoto.album(this, t.id) }
        if (t.photos.isNotEmpty()) mbt("⭐ 이 출조 사진 중에서 고르기") { pickMainFromTrip(t) }
        if (mainP != null) mbt("🗑 대표 사진 빼기", android.graphics.Color.parseColor("#FF8A92")) {
            MainPhoto.setMain(this@TripActivity, t.id, "")
        }
        mr.addView(mbc, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(10) })
        root.addView(mr, Ui.lp(this, MATCH, WRAP, top = 8))
        root.addView(Ui.text(this, 11f, Ui.SUB, false, "지난 출조·그룹 출조 기록·전체 조황 카드 오른쪽에 크게 보여요"), Ui.lp(this, MATCH, WRAP, top = 4))

        // 사진
        section("사진 ${t.photos.size}장")
        root.addView(Ui.button(this, "사진 추가 (촬영 시각으로 자동 매칭)") { pickPhotos() }, Ui.lp(this, MATCH, WRAP, top = 10))
        if (t.photos.isNotEmpty()) {
            val grid = GridLayout(this).apply { columnCount = 4 }
            val size = (Ui.contentW(this) - dp(40) - dp(18)) / 4
            t.photos.forEach { p ->
                val iv = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(Photos.thumbBitmap(this@TripActivity, p))
                    setOnClickListener { openPhoto(t, p) }
                    if (p.catchTime == null) alpha = 0.55f
                }
                grid.addView(iv, GridLayout.LayoutParams().apply { width = size; height = size; setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
            root.addView(grid, Ui.lp(this, MATCH, WRAP, top = 10))
            val un = t.photos.count { it.catchTime == null }
            if (un > 0) root.addView(Ui.text(this, 12f, Ui.SUB, false, "흐린 사진 ${un}장은 아직 조과에 안 붙었어요 · 눌러서 직접 지정"), Ui.lp(this, MATCH, WRAP, top = 6))
        }
        }

        // 기록: 시간대별 요약 → 누르면 그 시간 상세 → 전체 보기
        // v5.2 (240): 제목 줄 오른쪽 '✎ 조과 고치기' (올린 사람 · 관리자만)
        val canEd = canEdit(t)
        val secRow = Ui.row(this)
        secRow.addView(Ui.text(this, 16f, Ui.TEXT, true, "시간대별 기록"), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (canEd) secRow.addView(Ui.text(this, 13f, if (editMode) Ui.TEXT else android.graphics.Color.parseColor("#06303A"), true,
            if (editMode) "✓ 다 고쳤어요" else "✎ 조과 고치기").apply {
            gravity = Gravity.CENTER; setPadding(dp(14), dp(7), dp(14), dp(7))
            background = if (editMode) Ui.bg(this@TripActivity, android.graphics.Color.TRANSPARENT, 99, Ui.LINE)
                else Ui.bg(this@TripActivity, android.graphics.Color.parseColor("#5BC8E8"), 99)
            setOnClickListener { editMode = !editMode; picked.clear(); keepScroll { render() } }
        })
        root.addView(secRow, Ui.lp(this, MATCH, WRAP, top = 22))
        val edInfo = listOfNotNull(
            if (editMode) (if (viewOnly && remote?.owner != Cloud.uid) "관리자로 고치는 중 · 올린 사람 폰 기록에도 나중에 반영돼요" else "고치면 올린 그룹 기록 · 순위에도 같이 반영돼요") else null,
            remote?.takeIf { it.editAt > 0 }?.let { "수정됨 · ${it.editName} · " + SimpleDateFormat("MM.dd HH:mm", Locale.KOREA).format(Date(it.editAt)) })
        if (edInfo.isNotEmpty()) root.addView(Ui.text(this, 11.5f, android.graphics.Color.parseColor("#F2C14E"), false, edInfo.joinToString("\n")),
            Ui.lp(this, MATCH, WRAP, top = 4))
        // v6.12: 고치는 중 — 어종 이름 한 번에 바꾸기 (잘못 고른 어종 · 오타, 예: '니ㅏㄱ지' → 낙지)
        if (editMode && canEd) {
            val sp = t.slots().filter { t.count(it) > 0 }
            if (sp.isNotEmpty()) {
                val box = Ui.card(this)
                box.addView(Ui.text(this, 13.5f, Ui.TEXT, true, "🐟 어종 이름 한 번에 바꾸기"))
                box.addView(Ui.text(this, 11.5f, Ui.SUB, false, "잘못 고른 어종이나 오타를 그 어종 전부 한 번에 고쳐요 (그룹 기록 · 게시판에도 같이)"),
                    Ui.lp(this, MATCH, WRAP, top = 2))
                sp.forEach { sl ->
                    box.addView(Ui.button(this, "✎ ${t.name(sl)} ${t.count(sl)}마리 → 다른 어종으로") { renameSpecies(t, sl) }
                        .apply { minHeight = dp(42); textSize = 14f }, Ui.lp(this, MATCH, WRAP, top = 8))
                }
                root.addView(box, Ui.lp(this, MATCH, WRAP, top = 8))
            }
        }
        val list = Ui.card(this)
        if (t.catches.isEmpty()) list.addView(Ui.text(this, 14f, Ui.SUB, false, "기록이 없어요"))
        val hourKey = java.text.SimpleDateFormat("yyyyMMddHH", java.util.Locale.KOREA)
        val byHour = t.catches.groupBy { hourKey.format(Date(it.time)) }.toSortedMap()
        val maxN = byHour.values.maxOfOrNull { it.size } ?: 0
        byHour.forEach { (k, cs) ->
            val hour = cs.first().time
            val perSlot = cs.groupingBy { it.slot }.eachCount().toSortedMap()
            val photos = cs.count { t.photoOf(it) != null }
            val open = k in openHours
            val top = cs.size == maxN && maxN > 1
            val head = Ui.row(this).apply {
                setPadding(dp(12), dp(10), dp(10), dp(10))
                background = Ui.bg(this@TripActivity, android.graphics.Color.parseColor("#0F2233"), 12, if (top) Ui.ORANGE else Ui.LINE)
                isClickable = true
                setOnClickListener {
                    if (open) openHours.remove(k) else openHours.add(k)
                    // 펼쳐도 보던 자리 그대로
                    val sv = root.parent as? ScrollView
                    val y = sv?.scrollY ?: 0
                    render()
                    sv?.post { sv.scrollTo(0, y) }
                }
            }
            head.addView(Ui.text(this, 16f, Ui.TEXT, true, java.text.SimpleDateFormat("HH시", java.util.Locale.KOREA).format(Date(hour))),
                LinearLayout.LayoutParams(dp(48), WRAP))
            head.addView(Ui.text(this, 14f, Ui.TEXT, true, perSlot.entries.joinToString(" · ") { "${t.name(it.key)} ${it.value}" } +
                (if (photos > 0) "  📷 $photos" else "")), LinearLayout.LayoutParams(0, WRAP, 1f))
            head.addView(Ui.text(this, 12f, Ui.SUB, true, if (open) "접기 ▲" else "상세 ▼"))
            list.addView(head, Ui.lp(this, MATCH, WRAP, top = 6))
            if (open && editMode) {
                val all = cs.all { it in picked }
                list.addView(Ui.text(this, 12.5f, android.graphics.Color.parseColor("#FF8A92"), true,
                    (if (all) "☑ " else "☐ ") + java.text.SimpleDateFormat("HH시", java.util.Locale.KOREA).format(Date(hour)) + " 전부 고르기").apply {
                    setPadding(dp(12), dp(5), dp(12), dp(5))
                    background = Ui.bg(this@TripActivity, android.graphics.Color.TRANSPARENT, 99, android.graphics.Color.parseColor("#FF6B6B"))
                    setOnClickListener { if (all) picked.removeAll(cs.toSet()) else picked.addAll(cs); keepScroll { render() } }
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8); leftMargin = dp(4) })
                cs.forEach { c -> list.addView(editRow(t, c)) }
            } else if (open) cs.asReversed().forEach { c -> list.addView(catchRow(t, c)) }
        }
        if (editMode) {
            if (picked.isNotEmpty()) list.addView(Ui.button(this, "🗑 고른 ${picked.size}마리 지우기") { askDelete(t, picked.toList()) }.apply {
                setTextColor(android.graphics.Color.parseColor("#FF8A92"))
            }, Ui.lp(this, MATCH, WRAP, top = 10))
            list.addView(Ui.button(this, "＋ 조과 추가 (시각 · 어종)") { editCatchDialog(t, null) }, Ui.lp(this, MATCH, WRAP, top = 8))
            list.addView(Ui.text(this, 11.5f, Ui.SUB, false, "시간대를 눌러 펼친 뒤 ✎ 고치기 · 🗑 지우기 · 체크해서 여러 마리 한 번에"), Ui.lp(this, MATCH, WRAP, top = 6))
        }
        if (t.catches.size > 1) list.addView(Ui.button(this, "전체 보기 (${t.catches.size}) ›") { showAllCatches(t) }.apply { minHeight = dp(42); textSize = 14f },
            Ui.lp(this, MATCH, WRAP, top = 10))
        root.addView(list, Ui.lp(this, MATCH, WRAP, top = 10))

        // v6.9 (255): 게시판 글 메뉴 — 이 사람 차단 · 공개 내리기 (본인 · 관리자)
        remote?.takeIf { it.pubId.isNotBlank() }?.let { r ->
            section("메뉴")
            val mine = r.owner == Cloud.uid
            val local = if (mine) r.pubId.substringAfter("_").toLongOrNull()?.let { Store.find(it) } else null
            if (local != null) root.addView(Ui.button(this, "✎ 내 출조 기록 열기 (수정)") {
                startActivity(Intent(this, TripActivity::class.java).putExtra("id", local.id))
            }, Ui.lp(this, MATCH, WRAP, top = 10))
            if (!mine && Cloud.user != null) root.addView(Ui.button(this, "🚫 이 사람 차단") {
                AlertDialog.Builder(this, R.style.FlAlert)
                    .setTitle("이 사람을 차단할까요?")
                    .setMessage("이 사람이 올린 출조가 내 조황 목록, 어종 마릿수, 지도, 포인트 계산에서 모두 빠져요.\n상대에게는 알림이 가지 않아요. '차단 목록'에서 언제든 해제할 수 있어요.")
                    .setPositiveButton("차단") { _, _ ->
                        Moderation.block(r.owner, "${r.ownerName} · ${df.format(Date(r.start))} 글에서 차단") { err ->
                            Toast.makeText(this, err ?: "차단했어요", Toast.LENGTH_LONG).show()
                            if (err == null) { setResult(RESULT_OK, Intent().putExtra("pubChanged", true)); finish() }
                        }
                    }.setNegativeButton("취소", null).show()
            }, Ui.lp(this, MATCH, WRAP, top = 8))
            if (mine || Cloud.isAdmin) root.addView(Ui.button(this, if (mine) "🗑 전체 공개 내리기" else "🗑 이 글 지우기 (관리자)") {
                AlertDialog.Builder(this, R.style.FlAlert)
                    .setTitle(if (mine) "내 출조 공개를 내릴까요?" else "관리자: 이 글을 지울까요?")
                    .setMessage("전체 게시판에서만 사라지고, 폰의 기록은 그대로예요")
                    .setPositiveButton("내리기") { _, _ ->
                        PublicBoard.unpublish(r.pubId, local?.id) { err ->
                            Toast.makeText(this, err ?: "내렸어요", Toast.LENGTH_LONG).show()
                            if (err == null) { setResult(RESULT_OK, Intent().putExtra("pubChanged", true)); finish() }
                        }
                    }.setNegativeButton("취소", null).show()
            }.apply { setTextColor(android.graphics.Color.parseColor("#FF8A92")) }, Ui.lp(this, MATCH, WRAP, top = 8))
            root.addView(Ui.text(this, 12f, Ui.SUB, false, "게시판 글은 이동 경로 · 배 이름 · 이용료 금액이 안 올라가요").apply { gravity = Gravity.CENTER },
                Ui.lp(this, MATCH, WRAP, top = 10))
            return
        }
        // v5.2: 보기 전용 메뉴 — 함께한 출조 · (올린 사람 · 그룹장 · 관리자) 그룹에서 내리기
        remote?.let { r ->
            section("메뉴")
            root.addView(Ui.button(this, "🗺 함께한 출조 지도 · 순위", true) { account.showTogether(r) }, Ui.lp(this, MATCH, WRAP, top = 10))
            val mine = r.owner == Cloud.uid
            val local = if (mine) r.id.substringAfter("_").toLongOrNull()?.let { Store.find(it) } else null
            if (local != null) root.addView(Ui.button(this, "✎ 내 출조 기록 열기 (수정)") {
                startActivity(Intent(this, TripActivity::class.java).putExtra("id", local.id))
            }, Ui.lp(this, MATCH, WRAP, top = 8))
            val delLabel = when { mine -> "🗑 그룹에서 내리기"; Cloud.isOwner -> "🗑 그룹에서 내리기 (그룹장)"; Cloud.isAdmin -> "🗑 그룹에서 내리기 (관리자)"; else -> null }
            if (delLabel != null) root.addView(Ui.button(this, delLabel) {
                setResult(RESULT_OK, Intent().putExtra("remove", r.id)); finish()
            }.apply { setTextColor(android.graphics.Color.parseColor("#FF8A92")) }, Ui.lp(this, MATCH, WRAP, top = 8))
            root.addView(Ui.text(this, 12f, Ui.SUB, false, "조과 고치기는 올린 사람 · 관리자만 할 수 있어요").apply { gravity = Gravity.CENTER },
                Ui.lp(this, MATCH, WRAP, top = 10))
            return
        }
        // 동작
        section("메뉴")
        root.addView(Ui.button(this, "출조 종류: " + Store.catLabel(t.cat).ifBlank { "없음" } + "  (바꾸기)") { askCat(t) },
            Ui.lp(this, MATCH, WRAP, top = 10))
        root.addView(Ui.button(this, if (t.uploaded) "그룹에 다시 올리기" else "그룹에 올리기", true) { account.upload(t) }, Ui.lp(this, MATCH, WRAP, top = 10))
        if (t.uploaded) root.addView(Ui.button(this, "그룹에서 내리기 (내 폰 기록은 그대로)") { askUnpublish(t) }, Ui.lp(this, MATCH, WRAP, top = 8))
        if (t.end > 0 && t.catches.isNotEmpty()) {
            if (!t.pub) root.addView(Ui.button(this, "전체 게시판에 공개 (위치 정확/대략 선택)") { doPublish(t) }, Ui.lp(this, MATCH, WRAP, top = 8))
            else {
                root.addView(Ui.button(this, "전체 게시판에 다시 올리기 (고친 내용 반영)") { doPublish(t) }, Ui.lp(this, MATCH, WRAP, top = 8))
                val exactNow = t.pubPrec == "exact"
                root.addView(Ui.button(this, when (t.pubPrec) {
                    "exact" -> "공개 위치: 정확 → 대략으로 바꾸기"
                    "approx" -> "공개 위치: 대략 → 정확으로 바꾸기"
                    else -> "공개 위치를 정확하게 바꾸기"
                }) {
                    Toast.makeText(this, "바꾸는 중…", Toast.LENGTH_SHORT).show()
                    PublicBoard.changeMinePrecision(this, t, !exactNow) { err ->
                        Toast.makeText(this, err ?: if (!exactNow) "정확한 위치로 공개해요" else "대략적인 위치로만 공개해요", Toast.LENGTH_LONG).show()
                    }
                }, Ui.lp(this, MATCH, WRAP, top = 8))
                root.addView(Ui.button(this, "게시판 표시 방법 바꾸기 (이름 · 그룹)") { changeDisplay(t) }, Ui.lp(this, MATCH, WRAP, top = 8))
                root.addView(Ui.button(this, "전체 공개 내리기") {
                    PublicBoard.unpublishMine(t) { err -> Toast.makeText(this, err ?: "전체 공개를 내렸어요", Toast.LENGTH_LONG).show() }
                }, Ui.lp(this, MATCH, WRAP, top = 8))
            }
        }
        root.addView(Ui.button(this, "함께한 출조 보기") { account.openTogetherFor(t) }, Ui.lp(this, MATCH, WRAP, top = 8))
        root.addView(Ui.button(this, "카톡 등으로 텍스트 공유") { share(t) }, Ui.lp(this, MATCH, WRAP, top = 8))
        val names = Ui.row(this)
        names.addView(Ui.button(this, "${t.name(1)} 이름") { askRename(t, 1) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        names.addView(Ui.button(this, "${t.name(2)} 이름") { askRename(t, 2) }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        root.addView(names, Ui.lp(this, MATCH, WRAP, top = 8))
        extra.forEach { sl -> root.addView(Ui.button(this, "${t.name(sl)} 이름") { askRename(t, sl) }, Ui.lp(this, MATCH, WRAP, top = 8)) }
        if (t.end > 0) root.addView(Ui.button(this, "이 출조 삭제") { askDelete(t) }, Ui.lp(this, MATCH, WRAP, top = 8))
    }

    /** v6.9 (254): 민물 출조 — 일출 · 일몰 · 골든타임 · 해 기준 마릿수 */
    private fun renderSun(t: Store.Trip) {
        section("🌅 해 · 시간대")
        val card = Ui.card(this)
        val loc = t.anyLocation()
        val la = loc?.first; val lo = loc?.second
        card.addView(Ui.text(this, 15f, Ui.TEXT, true, SunPhase.riseSet(t.start, la, lo) + if (loc == null) " (서해 중부 기준)" else ""))
        card.addView(Ui.text(this, 12.5f, android.graphics.Color.parseColor("#F2C14E"), false, SunPhase.goldenText(t.start, la, lo)),
            Ui.lp(this, MATCH, WRAP, top = 4))
        if (t.catches.isNotEmpty()) {
            card.addView(Ui.text(this, 13f, Ui.SUB, true, "해 기준 조과"), Ui.lp(this, MATCH, WRAP, top = 12))
            val rows = SunPhase.counts(t.catches.map { it.time }, la, lo)
            val max = rows.maxOf { it.second }.coerceAtLeast(1)
            rows.forEach { (k, v) ->
                val gold = SunPhase.golden(k)
                val r = Ui.row(this)
                r.addView(Ui.text(this, 13f, if (v > 0) Ui.TEXT else Ui.SUB, gold && v > 0, k), LinearLayout.LayoutParams(dp(118), WRAP))
                val track = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                track.addView(View(this).apply { background = Ui.bg(this@TripActivity, if (gold) android.graphics.Color.parseColor("#F2C14E") else Ui.BLUE, 3) },
                    LinearLayout.LayoutParams(0, dp(10), v.toFloat() / max + 0.0001f))
                track.addView(View(this), LinearLayout.LayoutParams(0, dp(10), 1f - v.toFloat() / max + 0.0001f))
                r.addView(track, LinearLayout.LayoutParams(0, WRAP, 1f))
                r.addView(Ui.text(this, 13f, Ui.TEXT, true, "$v").apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(30), WRAP).apply { leftMargin = dp(6) })
                card.addView(r, Ui.lp(this, MATCH, WRAP, top = 5))
            }
            val g = rows.filter { SunPhase.golden(it.first) }.sumOf { it.second }
            card.addView(Ui.text(this, 12.5f, if (g > 0) android.graphics.Color.parseColor("#F2C14E") else Ui.SUB, g > 0,
                "골든타임(일출 · 일몰 전후 1시간)에 ${g}마리 · 전체의 ${g * 100 / t.catches.size}%"), Ui.lp(this, MATCH, WRAP, top = 8))
        }
        card.addView(Ui.text(this, 11f, Ui.SUB, false, "민물 출조라 물때 대신 해 기준으로 보여요 (노란 막대 = 골든타임)"), Ui.lp(this, MATCH, WRAP, top = 6))
        root.addView(card, Ui.lp(this, MATCH, WRAP, top = 10))
    }

    /** 200m 안에 20분 이상 머문 구간 */
    private fun stays(t: Store.Trip): List<Pair<Long, Long>> {
        val tr = t.track
        val out = mutableListOf<Pair<Long, Long>>()
        var i = 0
        val r = FloatArray(1)
        while (i < tr.size) {
            var j = i + 1
            while (j < tr.size) {
                android.location.Location.distanceBetween(tr[i].lat, tr[i].lon, tr[j].lat, tr[j].lon, r)
                if (r[0] > 200f) break
                j++
            }
            val endT = tr[j - 1].time
            if (endT - tr[i].time >= 20 * 60_000L) out.add(tr[i].time to endT)
            i = j
        }
        return out
    }

    /** 이 출조 사진 중에서 대표 고르기 */
    private fun pickMainFromTrip(t: Store.Trip) {
        val ps = t.photos.toList()
        val grid = GridLayout(this).apply { columnCount = 3; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val dlg = AlertDialog.Builder(this, R.style.FlAlert)
            .setTitle("대표 사진 고르기").setView(ScrollView(this).apply { addView(grid) }).setNegativeButton("취소", null).create()
        val w = (Ui.contentW(this) - dp(80)) / 3
        ps.forEach { p ->
            grid.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                Photos.thumbBitmap(this@TripActivity, p)?.let { setImageBitmap(it) }
                setPadding(dp(2), dp(2), dp(2), dp(2))
                if (p.file == t.mainPhoto) background = Ui.bg(this@TripActivity, Ui.GREEN, 4)
                setOnClickListener { MainPhoto.setMain(this@TripActivity, t.id, p.file); dlg.dismiss() }
            }, GridLayout.LayoutParams().apply { width = w; height = w })
        }
        dlg.show()
    }

    // ---------------- 사진 ----------------
    private fun pickPhotos() {
        // 안드로이드 기본 사진 선택 화면: 체크박스로 톡톡 눌러 여러 장 (최대 30장)
        val i = if (android.os.Build.VERSION.SDK_INT >= 33) {
            Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).apply {
                putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, 30)
            }
        } else {
            Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, 10)
        } catch (_: Exception) {
            val fallback = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(fallback, 10)
        }
    }

    @Deprecated("old api")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (MainPhoto.onResult(this, requestCode, resultCode, data) { render() }) return
        if (requestCode != 10 || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { cd -> for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri) }
        if (uris.isEmpty()) data.data?.let { uris.add(it) }
        if (uris.isEmpty()) return
        val progressText = Ui.text(this, 15f, Ui.TEXT, false, "0 / ${uris.size}장").apply {
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        val progress = AlertDialog.Builder(this, R.style.FlAlert)
            .setTitle("사진 가져오는 중")
            .setView(progressText)
            .setCancelable(false)
            .show()
        Thread {
            val imported = mutableListOf<Store.Photo>()
            uris.forEachIndexed { k, u ->
                Photos.import(this, u, tripId, k)?.let { imported.add(it) }
                main.post { progressText.text = "${k + 1} / ${uris.size}장" }
            }
            main.post {
                progress.dismiss()
                confirmPhotos(imported, uris.size)
            }
        }.start()
    }

    private fun confirmPhotos(list: List<Store.Photo>, picked: Int) {
        val t = trip ?: return
        if (list.isEmpty()) { Toast.makeText(this, "사진을 가져오지 못했어요", Toast.LENGTH_LONG).show(); return }
        Photos.autoMatch(t, list)
        val sb = StringBuilder()
        list.forEach { p ->
            val shot = if (p.time > 0) hm.format(Date(p.time)) else "시각 없음"
            val c = t.catches.firstOrNull { it.time == p.catchTime }
            if (c != null) {
                val gap = abs(p.time - c.time) / 1000
                sb.append("촬영 $shot → ${tf.format(Date(c.time))} ${t.name(c.slot)} (${gap / 60}분 ${gap % 60}초 차이)\n")
            } else {
                sb.append("촬영 $shot → 앞뒤 10분 안에 기록 없음\n")
            }
        }
        val matched = list.count { it.catchTime != null }
        AlertDialog.Builder(this, R.style.FlAlert)
            .setTitle("사진 ${list.size}장 중 ${matched}장 자동 매칭")
            .setMessage(sb.toString().trim() + if (picked > list.size) "\n\n(${picked - list.size}장은 읽지 못했어요)" else "")
            .setPositiveButton("확정") { _, _ -> Store.addPhotos(tripId, list) }
            .setNegativeButton("취소") { _, _ ->
                list.forEach { Photos.file(this, it.file).delete(); Photos.file(this, Photos.thumbName(it.file)).delete(); Photos.file(this, Photos.shareName(it.file)).delete() }
            }
            .setCancelable(false)
            .show()
    }

    private fun openPhoto(t: Store.Trip, p: Store.Photo) {
        val bmp = BitmapFactory.decodeFile(Photos.file(this@TripActivity, p.file).absolutePath)
        val c = t.catches.firstOrNull { it.time == p.catchTime }
        val title = (if (p.time > 0) "촬영 ${tf.format(Date(p.time))}" else "촬영 시각 없음") +
            (if (c != null) " · ${t.name(c.slot)} ${tf.format(Date(c.time))}" else " · 조과 미지정")
        val iv = ImageView(this).apply {
            adjustViewBounds = true
            setImageBitmap(bmp)
            setOnClickListener { ZoomImageView.show(this@TripActivity, bmp, title) }
        }
        AlertDialog.Builder(this, R.style.FlAlert)
            .setTitle(title)
            .setView(iv)
            .setPositiveButton("닫기", null)
            .setNeutralButton("조과 지정") { _, _ -> chooseCatch(t, p) }
            .setNegativeButton("사진 삭제") { _, _ ->
                Photos.file(this, p.file).delete(); Photos.file(this, Photos.thumbName(p.file)).delete(); Photos.file(this, Photos.shareName(p.file)).delete()
                Store.removePhoto(tripId, p)
            }
            .show()
    }

    private fun chooseCatch(t: Store.Trip, p: Store.Photo) {
        if (t.catches.isEmpty()) return
        val sorted = if (p.time > 0) t.catches.sortedBy { abs(it.time - p.time) } else t.catches.toList()
        val labels = sorted.map { "${tf.format(Date(it.time))} ${t.name(it.slot)}" + if (t.photoOf(it) != null) " (사진 있음)" else "" } + "조과에서 떼기"
        AlertDialog.Builder(this, R.style.FlAlert)
            .setTitle("어느 조과의 사진인가요?")
            .setItems(labels.toTypedArray()) { _, i ->
                p.catchTime = if (i < sorted.size) sorted[i].time else null
                Store.photosChanged(tripId)
            }
            .show()
    }

    // ---------------- 기타 ----------------
    private fun askPlace(t: Store.Trip) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val boat = android.widget.AutoCompleteTextView(this).apply {
            hint = "배 이름"; setText(t.boat); threshold = 0
            setAdapter(android.widget.ArrayAdapter(this@TripActivity, android.R.layout.simple_dropdown_item_1line, Store.boats()))
            setOnClickListener { showDropDown() }
        }
        val port = android.widget.AutoCompleteTextView(this).apply {
            hint = "출항지 (항구)"; setText(t.port); threshold = 0
            setAdapter(android.widget.ArrayAdapter(this@TripActivity, android.R.layout.simple_dropdown_item_1line, Store.ports()))
            setOnClickListener { showDropDown() }
        }
        box.addView(boat); box.addView(port)
        AlertDialog.Builder(this, R.style.FlAlert)
            .setTitle("배와 항구")
            .setView(box)
            .setPositiveButton("저장") { _, _ -> Store.setPlace(t.id, boat.text.toString(), port.text.toString()) }
            .setNegativeButton("취소", null)
            .show()
    }

    /** v6.12: 이 출조의 한 어종을 전부 다른 이름으로 (조과 고치기) */
    private fun renameSpecies(t: Store.Trip, slot: Int) {
        val old = t.name(slot); val n = t.count(slot)
        val cat = t.cat.ifBlank { Store.category }
        val list = Store.species(cat).filter { it != old }
        fun confirm(newName: String) {
            if (newName.isBlank() || newName == old) return
            AlertDialog.Builder(this, R.style.FlAlert)
                .setTitle("'$old' ${n}마리를 모두 '$newName'(으)로 바꿀까요?")
                .setMessage("이 출조의 '$old' 기록이 전부 '$newName'(으)로 바뀌어요. 그룹 기록 · 게시판 글에도 같이 반영돼요.")
                .setPositiveButton("바꾸기") { _, _ -> doRenameSpecies(t, slot, old, newName) }
                .setNegativeButton("취소", null).show()
        }
        BigList.show(this, "'$old' ${n}마리 → 어떤 어종으로? (${Store.catLabel(cat)})", list, listOf("+ 직접 쓰기")) { i, label ->
            if (i < list.size) { confirm(label); return@show }
            val input = EditText(this).apply { hint = "어종 이름 (예: 낙지)"; inputType = InputType.TYPE_CLASS_TEXT; setSingleLine() }
            AlertDialog.Builder(this, R.style.FlAlert)
                .setTitle("어종 이름 직접 쓰기")
                .setView(input)
                .setPositiveButton("다음") { _, _ -> confirm(input.text.toString().trim()) }
                .setNegativeButton("취소", null).show()
        }
    }

    private fun doRenameSpecies(t: Store.Trip, slot: Int, old: String, newName: String) {
        val msg = "'$old' → '$newName'(으)로 바꿨어요"
        val r = remote
        if (r == null) {
            Store.renameInTrip(t.id, slot, newName)
            Store.find(t.id)?.let { tt -> Cloud.pushCatchEdit(tt) { e ->
                if (!isFinishing) Toast.makeText(this, e?.let { "폰 기록은 바꿨어요 · 그룹 · 게시판은 못 바꿨어요 ($it)" } ?: msg, Toast.LENGTH_LONG).show() } }
            keepScroll { render() }
            return
        }
        val names = t.names.toMutableList()
        while (names.size < slot) names.add("${names.size + 1}번")
        names[slot - 1] = newName
        val mine = r.owner == Cloud.uid
        val local = if (mine) r.id.substringAfter("_").toLongOrNull()?.let { Store.find(it) } else null
        val after = { e: String? ->
            if (!isFinishing) {
                if (e == null) {
                    remote = r.withNames(names, System.currentTimeMillis(), if (mine) Cloud.displayName else "관리자")
                    remoteTrip = toTrip(remote!!)
                    setResult(RESULT_OK, Intent().putExtra("edited", true))
                    keepScroll { render() }
                }
                Toast.makeText(this, e ?: msg, Toast.LENGTH_LONG).show()
            }
        }
        if (local != null) {
            Store.renameInTrip(local.id, slot, newName)
            Cloud.pushCatchEdit(Store.find(local.id) ?: local) { e -> after(e) }
        } else {
            val g = Cloud.active ?: run { Toast.makeText(this, "그룹을 못 찾았어요", Toast.LENGTH_LONG).show(); return }
            Cloud.updateCatches(g.id, r.id, t.catches.toList(), if (mine) null else r.owner, names) { e -> after(e) }
        }
    }

    private fun askRename(t: Store.Trip, slot: Int) {
        val cat = t.cat.ifBlank { Store.category }
        val list = Store.species(cat)
        BigList.show(this, "버튼 $slot 어종 (${Store.catLabel(cat)})", list, listOf("+ 새 어종 추가")) { i, label ->
            if (i < list.size) { Store.renameInTrip(t.id, slot, label); return@show }
            val input = EditText(this).apply { hint = "어종 이름"; inputType = InputType.TYPE_CLASS_TEXT }
            AlertDialog.Builder(this, R.style.FlAlert)
                .setTitle("${Store.catLabel(cat)} 어종 추가")
                .setView(input)
                .setPositiveButton("추가") { _, _ ->
                    val n = input.text.toString().trim()
                    if (n.isNotEmpty()) { if (Cloud.isAdmin) Store.addSpecies(cat, n) else Store.addMySpecies(cat, n); Store.renameInTrip(t.id, slot, n) }
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    private fun askUnpublish(t: Store.Trip) {
        val myId = Cloud.uid
        if (myId == null || Cloud.active == null) { Toast.makeText(this, "로그인하고 그룹을 선택해 주세요", Toast.LENGTH_LONG).show(); return }
        AlertDialog.Builder(this, R.style.FlAlert)
            .setTitle("'${Cloud.active?.name}' 그룹에서 이 출조를 내릴까요?")
            .setMessage("친구들 화면에서 사라지고, 내 폰 기록과 백업은 그대로예요")
            .setPositiveButton("내리기") { _, _ ->
                Cloud.deleteGroupTrip("${myId}_${t.id}") { err ->
                    Toast.makeText(this, err ?: "그룹에서 내렸어요", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun askDelete(t: Store.Trip) {
        AlertDialog.Builder(this, R.style.FlAlert)
            .setTitle("이 출조 기록을 삭제할까요?")
            .setMessage("내 폰 · 서버 백업 · 올린 그룹 · 전체 게시판에서 같이 지워져요.\n휴지통(☰ 메뉴 → 🗑 휴지통)에 30일 보관돼서 그 안에는 복구할 수 있어요.")
            .setPositiveButton("삭제") { _, _ ->
                if (Cloud.user != null) Cloud.deleteTripsEverywhere(listOf(t)) { err -> if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show() }
                else { Trash.keepMine(listOf(t)); Store.deleteTrip(t.id) }
                finish()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun share(t: Store.Trip) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, Store.exportText(t))
        }, "조과 공유"))
    }

    /** 출조 종류 바꾸기: 저장 → 공개한 출조면 게시판에도, 그룹에 올린 출조면 그룹에도 다시 반영 */
    private fun askCat(t: Store.Trip) {
        val cats = Store.CATS
        val labels = cats.map { (if (it == t.cat) "✓ " else "") + Store.catLabel(it) }
        BigList.show(this, "이 출조의 출조 종류", labels, emptyList()) { i, _ ->
            val wasUploaded = t.uploaded
            Store.setTripCat(t.id, cats[i])
            Toast.makeText(this, "${Store.catLabel(cats[i])}(으)로 바꿨어요", Toast.LENGTH_SHORT).show()
            if (t.pub) PublicBoard.updateCat(t) { err -> if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show() }
            if (wasUploaded && Cloud.active != null) account.upload(t, quiet = true)
            Cloud.backupPending()
        }
    }
}
