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

    private var tripId = 0L
    private lateinit var root: LinearLayout
    private var map: MapView? = null
    private lateinit var account: AccountUi
    private val main = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { render() }
    private var tideDay: Tide.Day? = null
    private var tideMsg = "물때 불러오는 중…"

    private val tf = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    private val hm = SimpleDateFormat("HH:mm", Locale.KOREA)
    private val df = SimpleDateFormat("M월 d일 (E)", Locale.KOREA)

    private fun dp(v: Int) = Ui.dp(this, v)
    private val trip: Store.Trip? get() = Store.find(tripId)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        account = AccountUi(this)
        tripId = intent.getLongExtra("id", 0L)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        root = Ui.column(this)
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(root) })
        loadTide()
        if (savedInstanceState == null && intent.getBooleanExtra("publish", false)) {
            trip?.let { t -> root.post { doPublish(t) } }
        }
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
        if (loc == null) { tideMsg = "위치 기록이 없어서 조위 그래프는 못 불러와요"; return }
        Tide.fetch(this, loc.first, loc.second, t.start) { day, err ->
            tideDay = day
            tideMsg = err ?: ""
            render()
        }
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
        root.addView(top)
        val endTime = if (t.end > 0) t.end else System.currentTimeMillis()
        root.addView(Ui.text(this, 13f, Ui.SUB, false,
            (if (t.end > 0) "출조 완료" else "출조 중") + " · ${hm.format(Date(t.start))} ~ ${hm.format(Date(endTime))}"),
            Ui.lp(this, MATCH, WRAP, top = 14))
        root.addView(Ui.text(this, 22f, Ui.TEXT, true, df.format(Date(t.start)) + " " +
            (if (t.place().isBlank()) "배·항구 입력 ✎" else t.place() + " ✎")).apply {
            setOnClickListener { askPlace(t) }
        }, Ui.lp(this, MATCH, WRAP, top = 2))
        root.addView(Ui.text(this, 13f, Ui.SUB, false,
            (if (t.cat.isNotBlank()) Store.catLabel(t.cat) + " · " else "") + Tide.mulName(t.start)), Ui.lp(this, MATCH, WRAP, top = 4))

        // 합계
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stats.addView(Ui.stat(this, "총 조과", t.catches.size.toString()), LinearLayout.LayoutParams(0, WRAP, 1f))
        stats.addView(Ui.stat(this, t.name(1), t.count(1).toString(), Ui.ORANGE), LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        stats.addView(Ui.stat(this, t.name(2), t.count(2).toString(), Ui.BLUE), LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        root.addView(stats, Ui.lp(this, MATCH, WRAP, top = 16))

        // 지도
        section("이동 경로 · 잡은 위치")
        val m = MapDialog.preview(this, t) { MapDialog.showTrip(this, t) }
        map = m
        root.addView(m, Ui.lp(this, MATCH, dp(240), top = 10))
        m.onResume()
        root.addView(Ui.text(this, 12f, Ui.SUB, false,
            "주황 ${t.name(1)} · 하늘 ${t.name(2)} · 흰 선 이동 경로 · 지도를 누르면 크게 보기"), Ui.lp(this, MATCH, WRAP, top = 6))

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

        // 물때
        section("물때")
        val tideCard = Ui.card(this)
        val d = tideDay
        if (d != null) {
            val tv = TideView(this)
            tv.set(d, t)
            tideCard.addView(tv, Ui.lp(this, MATCH, dp(240)))
            tideCard.addView(Ui.text(this, 13f, Ui.TEXT, false, TideDialog.describe(d, t)), Ui.lp(this, MATCH, WRAP, top = 6))
            tideCard.addView(Ui.text(this, 11f, Ui.SUB, false, d.source), Ui.lp(this, MATCH, WRAP, top = 4))
        } else {
            tideCard.addView(Ui.text(this, 13f, Ui.SUB, false, tideMsg))
        }
        root.addView(tideCard, Ui.lp(this, MATCH, WRAP, top = 10))

        // 시간대별 입질
        if (t.catches.isNotEmpty()) {
            section("시간대별 입질")
            val bc = Ui.card(this)
            val bars = BarView(this)
            bars.set(t)
            bc.addView(bars, Ui.lp(this, MATCH, dp(110)))
            root.addView(bc, Ui.lp(this, MATCH, WRAP, top = 10))
        }

        // 사진
        section("사진 ${t.photos.size}장")
        root.addView(Ui.button(this, "사진 추가 (촬영 시각으로 자동 매칭)") { pickPhotos() }, Ui.lp(this, MATCH, WRAP, top = 10))
        if (t.photos.isNotEmpty()) {
            val grid = GridLayout(this).apply { columnCount = 4 }
            val size = (resources.displayMetrics.widthPixels - dp(40) - dp(18)) / 4
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

        // 전체 기록
        section("전체 기록")
        val list = Ui.card(this)
        if (t.catches.isEmpty()) list.addView(Ui.text(this, 14f, Ui.SUB, false, "기록이 없어요"))
        t.catches.asReversed().forEach { c ->
            val r = Ui.row(this).apply { setPadding(0, dp(6), 0, dp(6)) }
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
            r.addView(View(this).apply { background = Ui.bg(this@TripActivity, Ui.SLOT[c.slot - 1], 99) },
                LinearLayout.LayoutParams(dp(9), dp(9)).apply { leftMargin = dp(10) })
            r.addView(Ui.text(this, 14f, Ui.TEXT, false, t.name(c.slot) + if (c.lat != null) "" else "  (위치 없음)"),
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
            list.addView(r)
        }
        root.addView(list, Ui.lp(this, MATCH, WRAP, top = 10))

        // 동작
        section("메뉴")
        root.addView(Ui.button(this, if (t.uploaded) "그룹에 다시 올리기" else "그룹에 올리기", true) { account.upload(t) }, Ui.lp(this, MATCH, WRAP, top = 10))
        if (t.uploaded) root.addView(Ui.button(this, "그룹에서 내리기 (내 폰 기록은 그대로)") { askUnpublish(t) }, Ui.lp(this, MATCH, WRAP, top = 8))
        if (t.end > 0 && t.catches.isNotEmpty()) {
            if (!t.pub) root.addView(Ui.button(this, "전체 게시판에 공개 (위치는 150m 범위로만)") { doPublish(t) }, Ui.lp(this, MATCH, WRAP, top = 8))
            else {
                root.addView(Ui.button(this, "전체 게시판에 다시 올리기 (고친 내용 반영)") { doPublish(t) }, Ui.lp(this, MATCH, WRAP, top = 8))
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
        if (t.end > 0) root.addView(Ui.button(this, "이 출조 삭제") { askDelete(t) }, Ui.lp(this, MATCH, WRAP, top = 8))
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
        if (requestCode != 10 || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { cd -> for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri) }
        if (uris.isEmpty()) data.data?.let { uris.add(it) }
        if (uris.isEmpty()) return
        val progressText = Ui.text(this, 15f, Ui.TEXT, false, "0 / ${uris.size}장").apply {
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        val progress = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("배와 항구")
            .setView(box)
            .setPositiveButton("저장") { _, _ -> Store.setPlace(t.id, boat.text.toString(), port.text.toString()) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun askRename(t: Store.Trip, slot: Int) {
        val cat = t.cat.ifBlank { Store.category }
        val list = Store.species(cat)
        BigList.show(this, "버튼 $slot 어종 (${Store.catLabel(cat)})", list, listOf("+ 새 어종 추가")) { i, label ->
            if (i < list.size) { Store.renameInTrip(t.id, slot, label); return@show }
            val input = EditText(this).apply { hint = "어종 이름"; inputType = InputType.TYPE_CLASS_TEXT }
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("${Store.catLabel(cat)} 어종 추가")
                .setView(input)
                .setPositiveButton("추가") { _, _ ->
                    val n = input.text.toString().trim()
                    if (n.isNotEmpty()) { Store.addSpecies(cat, n); Store.renameInTrip(t.id, slot, n) }
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    private fun askUnpublish(t: Store.Trip) {
        val myId = Cloud.uid
        if (myId == null || Cloud.active == null) { Toast.makeText(this, "로그인하고 그룹을 선택해 주세요", Toast.LENGTH_LONG).show(); return }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("이 출조 기록을 삭제할까요?")
            .setMessage("폰에서만 지워져요 (그룹·백업에 올라간 건 그대로)")
            .setPositiveButton("삭제") { _, _ -> Store.deleteTrip(t.id); finish() }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun share(t: Store.Trip) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, Store.exportText(t))
        }, "조과 공유"))
    }
}
