package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 🌊 물때 관측소 늘리기 (v4.2)
 *  - 앱 안 기본 목록(stations.json) + 관리자가 추가한 관측소(서버 config/stations · 폰에도 기억)
 *  - 관리자: '관측소 찾기' = 국립해양조사원 관측소 번호를 하나씩 조회해서 물때가 나오는데 앱에 없는 곳을 찾아 줌
 *            찾은 곳 ＋ 추가 · 추가한 곳 ✎ 수정 · 🗑 삭제 · 직접 추가 (번호 · 이름 · 위치)
 *  - 서버에 저장하면 모든 사람 앱에 바로 들어감 (앱 업데이트 필요 없음)
 */
object StationAdmin {
    private val main = Handler(Looper.getMainLooper())
    private fun prefs(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)

    fun extras(c: Context): List<Tide.Station> = try {
        val a = JSONArray(prefs(c).getString("extraStations", "[]") ?: "[]")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Tide.Station(o.getString("code"), o.getString("name"), o.getDouble("lat"), o.getDouble("lng"))
        }
    } catch (_: Exception) { emptyList() }

    private fun toJson(l: List<Tide.Station>) = JSONArray().apply {
        l.forEach { put(JSONObject().put("code", it.code).put("name", it.name).put("lat", it.lat).put("lng", it.lon)) }
    }.toString()

    private fun saveLocal(c: Context, l: List<Tide.Station>) {
        prefs(c).edit().putString("extraStations", toJson(l)).apply()
        Tide.resetStations()
    }

    /** 앱 켤 때: 서버에 추가된 관측소 받아 두기 (로그인 안 했으면 조용히 건너뜀) */
    fun sync(c: Context) {
        val app = c.applicationContext
        if (Cloud.user == null) return
        FirebaseFirestore.getInstance().collection("config").document("stations").get()
            .addOnSuccessListener { d ->
                val s = d.getString("list") ?: return@addOnSuccessListener
                if (s != prefs(app).getString("extraStations", null)) {
                    prefs(app).edit().putString("extraStations", s).apply()
                    Tide.resetStations()
                }
            }
    }

    private fun upload(c: Context, l: List<Tide.Station>, done: (String?) -> Unit) {
        FirebaseFirestore.getInstance().collection("config").document("stations")
            .set(mapOf("list" to toJson(l), "updated" to System.currentTimeMillis()))
            .addOnSuccessListener { saveLocal(c, l); done(null) }
            .addOnFailureListener { done(it.message ?: "실패") }
    }

    class Found(val code: String, val name: String, val lat: Double?, val lon: Double?, val n: Int)

    /** 관측소 번호 하나 조회 (백그라운드). 물때가 나오면 Found, 아니면 null */
    private fun probe(code: String): Found? {
        val key = BuildConfig.KHOA_KEY
        if (key.isBlank()) return null
        val ymd = SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(Date())
        val q = "/1192136/tideFcstHghLw/GetTideFcstHghLwApiService?serviceKey=${URLEncoder.encode(key, "UTF-8")}" +
            "&obsCode=$code&reqDate=$ymd&type=json&pageNo=1&numOfRows=10"
        for (scheme in listOf("https", "http")) {
            try {
                val con = java.net.URL("$scheme://apis.data.go.kr$q").openConnection() as java.net.HttpURLConnection
                con.connectTimeout = 10_000; con.readTimeout = 10_000
                if (con.responseCode !in 200..299) return null
                val body = con.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
                if (!body.startsWith("{")) return null
                val root = JSONObject(body).let { if (it.has("response")) it.getJSONObject("response") else it }
                if (root.optJSONObject("header")?.optString("resultCode") != "00") return null
                val itemAny = root.optJSONObject("body")?.optJSONObject("items")?.opt("item")
                val arr = when (itemAny) { is JSONArray -> itemAny; is JSONObject -> JSONArray().put(itemAny); else -> return null }
                if (arr.length() == 0) return null
                val o = arr.getJSONObject(0)
                val name = listOf("obsvtrNm", "obsPostName", "obsvtrName").map { o.optString(it) }.firstOrNull { it.isNotBlank() } ?: code
                val lat = listOf("lat", "obsLat").mapNotNull { o.optString(it).toDoubleOrNull() }.firstOrNull()
                val lon = listOf("lot", "lon", "obsLon").mapNotNull { o.optString(it).toDoubleOrNull() }.firstOrNull()
                return Found(code, name, lat, lon, arr.length())
            } catch (_: Exception) {}
        }
        return null
    }

    /** 찾아볼 관측소 번호: DT_0001~DT_0130 · IE_0060~IE_0064 (해양과학기지) — 앱에 이미 있는 건 빼고 */
    private fun candidates(c: Context): List<String> {
        val have = Tide.stations(c).map { it.code }.toSet()
        return ((1..130).map { "DT_%04d".format(it) } + (60..64).map { "IE_%04d".format(it) }).filter { it !in have }
    }

    // ---------------- 관리자 화면 ----------------
    fun open(a: Activity) {
        fun dp(v: Int) = Ui.dp(a, v)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(6), dp(18), dp(6)) }
        lateinit var paint: () -> Unit
        val found = ArrayList<Found>()
        var scanning = false
        val status = Ui.text(a, 12f, Ui.SUB, false, "")

        fun field(h: String, v: String, num: Boolean = false) = EditText(a).apply {
            hint = h; setText(v); setTextColor(Ui.TEXT); setHintTextColor(Ui.SUB); setSingleLine()
            if (num) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        }

        /** 추가 · 수정 창 (번호 · 이름 · 위도 · 경도) */
        fun edit(st: Tide.Station?, pre: Found?) {
            val f = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), 0) }
            val code = field("관측소 번호 (예: DT_0070)", st?.code ?: pre?.code ?: "")
            val name = field("이름 (예: 무의도)", st?.name ?: pre?.let { p -> p.name.takeIf { it != p.code } } ?: "")
            val lat = field("위도 (예: 37.38)", (st?.lat ?: pre?.lat)?.toString() ?: "", true)
            val lon = field("경도 (예: 126.42)", (st?.lon ?: pre?.lon)?.toString() ?: "", true)
            f.addView(code); f.addView(name); f.addView(lat); f.addView(lon)
            if (st != null) code.isEnabled = false
            f.addView(Ui.text(a, 11f, Ui.SUB, false, "위치를 모르면 지도에서 그 관측소 자리를 꾹 눌러 좌표를 확인해 적어 주세요"), Ui.lp(a, MATCH, WRAP, top = 6))
            AlertDialog.Builder(a, R.style.FlAlert).setTitle(if (st == null) "＋ 관측소 추가" else "관측소 수정")
                .setView(f)
                .setPositiveButton("서버에 저장") { _, _ ->
                    val c = code.text.toString().trim().uppercase(Locale.ROOT)
                    val n = name.text.toString().trim()
                    val la = lat.text.toString().trim().toDoubleOrNull()
                    val lo = lon.text.toString().trim().toDoubleOrNull()
                    if (c.isEmpty() || n.isEmpty() || la == null || lo == null || la !in 32.0..39.5 || lo !in 123.5..132.5) {
                        Toast.makeText(a, "번호 · 이름 · 위도(32~39.5) · 경도(123.5~132.5)를 모두 적어 주세요", Toast.LENGTH_LONG).show(); return@setPositiveButton
                    }
                    if (st == null && Tide.stations(a).any { it.code == c }) { Toast.makeText(a, "이미 있는 관측소예요", Toast.LENGTH_LONG).show(); return@setPositiveButton }
                    val l = extras(a).filter { it.code != c } + Tide.Station(c, n, la, lo)
                    upload(a, l) { err ->
                        Toast.makeText(a, err?.let { "저장 실패: $it" } ?: "저장했어요 · 모든 사람 앱에 들어가요", Toast.LENGTH_LONG).show()
                        found.removeAll { it.code == c }; paint()
                    }
                }.setNegativeButton("취소", null).show()
        }

        fun smallBtn(t: String, col: Int, onClick: () -> Unit) = Ui.text(a, 13f, col, true, t).apply {
            gravity = Gravity.CENTER; background = Ui.bg(a, Color.TRANSPARENT, 10, Ui.LINE); setOnClickListener { onClick() }
        }

        fun scan() {
            if (scanning) return
            if (BuildConfig.KHOA_KEY.isBlank()) { Toast.makeText(a, "인증키(KHOA_KEY)가 앱에 없어요", Toast.LENGTH_LONG).show(); return }
            scanning = true; found.clear(); paint()
            val list = candidates(a)
            Thread {
                list.forEachIndexed { i, code ->
                    main.post { status.text = "찾는 중… ${i + 1}/${list.size} ($code) · 찾은 곳 ${found.size}" }
                    probe(code)?.let { f -> main.post { found.add(f); paint() } }
                    Thread.sleep(120)
                }
                main.post { scanning = false; status.text = "다 찾았어요 · 앱에 없는 관측소 ${found.size}곳"; paint() }
            }.start()
        }

        paint = {
            box.removeAllViews()
            box.addView(Ui.text(a, 12f, Ui.SUB, false, "기본 관측소 ${Tide.stations(a).size - extras(a).size}곳 + 추가한 관측소 ${extras(a).size}곳"))
            box.addView(Ui.button(a, if (scanning) "찾는 중…" else "🔎 앱에 없는 관측소 찾기") { scan() }.apply { minHeight = dp(46) }, Ui.lp(a, MATCH, WRAP, top = 8))
            box.addView(status, Ui.lp(a, MATCH, WRAP, top = 4))
            found.forEach { f ->
                val r = Ui.row(a).apply { setPadding(dp(10), dp(8), dp(8), dp(8)); background = Ui.bg(a, Ui.CARD, 10, Ui.GREEN) }
                val t = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                t.addView(Ui.text(a, 14f, Ui.TEXT, true, "${f.name}"))
                t.addView(Ui.text(a, 11f, Ui.SUB, false, f.code + (if (f.lat != null && f.lon != null) " · %.3f, %.3f".format(f.lat, f.lon) else " · 위치 직접 입력 필요") + " · 오늘 만조·간조 ${f.n}개"))
                r.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
                r.addView(smallBtn("＋ 추가", Ui.GREEN) { edit(null, f) }, LinearLayout.LayoutParams(dp(72), dp(36)))
                box.addView(r, Ui.lp(a, MATCH, WRAP, top = 6))
            }
            box.addView(Ui.text(a, 14f, Ui.TEXT, true, "추가한 관측소"), Ui.lp(a, MATCH, WRAP, top = 14))
            val ex = extras(a)
            if (ex.isEmpty()) box.addView(Ui.text(a, 12f, Ui.SUB, false, "아직 없어요"), Ui.lp(a, MATCH, WRAP, top = 4))
            ex.forEach { s ->
                val r = Ui.row(a).apply { setPadding(dp(10), dp(8), dp(8), dp(8)); background = Ui.bg(a, Ui.CARD, 10) }
                val t = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                t.addView(Ui.text(a, 14f, Ui.TEXT, true, s.name))
                t.addView(Ui.text(a, 11f, Ui.SUB, false, "${s.code} · %.3f, %.3f".format(s.lat, s.lon)))
                r.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
                r.addView(smallBtn("✎ 수정", Ui.TEXT) { edit(s, null) }, LinearLayout.LayoutParams(dp(66), dp(36)))
                r.addView(smallBtn("🗑 삭제", Color.parseColor("#FF8A92")) {
                    AlertDialog.Builder(a, R.style.FlAlert).setMessage("'${s.name}' 관측소를 지울까요? (모든 사람 앱에서 빠져요)")
                        .setPositiveButton("삭제") { _, _ ->
                            upload(a, extras(a).filter { it.code != s.code }) { err ->
                                Toast.makeText(a, err?.let { "삭제 실패: $it" } ?: "지웠어요", Toast.LENGTH_LONG).show(); paint()
                            }
                        }.setNegativeButton("취소", null).show()
                }, LinearLayout.LayoutParams(dp(66), dp(36)).apply { leftMargin = dp(6) })
                box.addView(r, Ui.lp(a, MATCH, WRAP, top = 6))
            }
            box.addView(Ui.button(a, "＋ 번호로 직접 추가") { edit(null, null) }.apply { minHeight = dp(44) }, Ui.lp(a, MATCH, WRAP, top = 10))
            box.addView(Ui.text(a, 11f, Ui.SUB, false, "찾기는 관측소 번호 약 130개를 하나씩 물어봐서 1~2분 걸려요 (공공데이터 하루 사용량 안에서)"), Ui.lp(a, MATCH, WRAP, top = 6))
        }
        paint()
        AlertDialog.Builder(a, R.style.FlAlert).setTitle("🌊 물때 관측소 관리")
            .setView(ScrollView(a).apply { addView(box) })
            .setNegativeButton("닫기", null).show()
    }
}
