package com.fishlog.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * v5.1 (231 · 232): 물때·날씨 지점
 *  - 카카오 장소 검색으로 고른 곳 (예: 신도) → 물때는 가장 가까운 관측소, 날씨·바람은 그 자리 기준
 *  - ⭐ 즐겨찾기 지점 (추가 · 이름 바꾸기 · 삭제)
 *  - 기준 위치: 고른 지점 → 직접 고른 관측소 자리 → 내 위치(GPS)
 *  - 해상 기준점: 해안을 따라 확실히 바다인 곳 (바람·파도·수온은 가장 가까운 기준점 값)
 */
object Spot {

    class Place(val name: String, val lat: Double, val lon: Double, val sub: String = "") {
        fun toJson(): JSONObject = JSONObject().put("n", name).put("la", lat).put("lo", lon).put("s", sub)
        companion object {
            fun from(o: JSONObject?): Place? = try {
                if (o == null) null else Place(o.getString("n"), o.getDouble("la"), o.getDouble("lo"), o.optString("s"))
            } catch (_: Exception) { null }
        }
    }

    // ---------------- 지금 고른 지점 ----------------
    var current: Place?
        get() = try { Store.wxPlace?.let { Place.from(JSONObject(it)) } } catch (_: Exception) { null }
        set(v) { Store.wxPlace = v?.toJson()?.toString() }

    /** 물때·날씨를 볼 기준 위치: 고른 지점 → 직접 고른 관측소 → 내 위치 */
    fun base(ctx: Context, lat: Double?, lon: Double?): Pair<Double, Double>? {
        current?.let { return it.lat to it.lon }
        Store.tideStation?.let { code -> Tide.stations(ctx).firstOrNull { it.code == code }?.let { return it.lat to it.lon } }
        if (lat == null || lon == null) return null
        return lat to lon
    }

    fun base(ctx: Context, loc: Pair<Double, Double>?): Pair<Double, Double>? = base(ctx, loc?.first, loc?.second)

    // ---------------- ⭐ 즐겨찾기 ----------------
    fun favorites(): List<Place> = try {
        val arr = JSONArray(Store.favPlaces)
        (0 until arr.length()).mapNotNull { Place.from(arr.optJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    private fun saveFavs(l: List<Place>) { Store.favPlaces = JSONArray().apply { l.forEach { put(it.toJson()) } }.toString() }

    fun isFav(p: Place) = favorites().any { same(it, p) }
    fun same(a: Place, b: Place) = Math.abs(a.lat - b.lat) < 0.0005 && Math.abs(a.lon - b.lon) < 0.0005

    fun addFav(p: Place): Boolean {
        val l = favorites()
        if (l.any { same(it, p) }) return false
        saveFavs(l + p)
        return true
    }
    fun renameFav(i: Int, name: String) {
        val l = favorites().toMutableList()
        if (i !in l.indices || name.isBlank()) return
        val o = l[i]
        l[i] = Place(name.trim(), o.lat, o.lon, o.sub)
        saveFavs(l)
        // 지금 고른 지점이면 이름도 같이
        current?.let { c -> if (same(c, o)) current = l[i] }
    }
    fun deleteFav(i: Int) {
        val l = favorites().toMutableList()
        if (i !in l.indices) return
        l.removeAt(i); saveFavs(l)
    }

    // ---------------- 🔍 카카오 장소 검색 ----------------
    class SearchResult(val places: List<Place>, val error: String)

    /** 백그라운드 스레드에서 호출 */
    fun kakao(q: String): SearchResult {
        val key = BuildConfig.KAKAO_KEY
        if (key.isBlank()) return SearchResult(emptyList(), "카카오 검색 키가 앱에 없어요 (GitHub 비밀값 KAKAO_REST_KEY 확인)")
        return try {
            val c = URL("https://dapi.kakao.com/v2/local/search/keyword.json?size=15&query=" + URLEncoder.encode(q, "UTF-8"))
                .openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            c.setRequestProperty("Authorization", "KakaoAK $key")
            val code = c.responseCode
            if (code == 401 || code == 403) return SearchResult(emptyList(), "카카오 검색: 키 또는 '카카오맵 사용 설정(ON)'을 확인해 주세요 ($code)")
            if (code == 429) return SearchResult(emptyList(), "카카오 검색: 오늘 사용량을 다 썼어요 (내일 다시)")
            if (code !in 200..299) return SearchResult(emptyList(), "카카오 검색을 못 했어요 ($code)")
            val docs = JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }).optJSONArray("documents") ?: JSONArray()
            val out = (0 until docs.length()).mapNotNull { i ->
                val o = docs.getJSONObject(i)
                val la = o.optString("y").toDoubleOrNull() ?: return@mapNotNull null
                val lo = o.optString("x").toDoubleOrNull() ?: return@mapNotNull null
                val addr = o.optString("address_name").ifBlank { o.optString("road_address_name") }
                val cat = o.optString("category_name").split(" > ").lastOrNull().orEmpty()
                Place(o.optString("place_name").ifBlank { q }, la, lo, listOf(addr, cat).filter { it.isNotBlank() }.joinToString(" · "))
            }
            SearchResult(out, "")
        } catch (_: Exception) { SearchResult(emptyList(), "카카오 검색은 인터넷이 필요해요") }
    }

    // ---------------- 🌊 해상 기준점 ----------------
    class SeaRef(val name: String, val lat: Double, val lon: Double)

    /** 해안을 따라 확실히 바다인 곳 (섬·갯벌을 피해 몇 km 앞바다) */
    val SEA_REFS = listOf(
        // 서해 (경기 · 인천)
        SeaRef("강화·석모도 앞바다", 37.62, 126.20),
        SeaRef("신도·장봉도 앞바다", 37.52, 126.25),
        SeaRef("인천항·팔미도 앞바다", 37.33, 126.47),
        SeaRef("영흥도·자월도 앞바다", 37.20, 126.38),
        SeaRef("덕적도 앞바다", 37.15, 126.05),
        SeaRef("화성 제부도·궁평 앞바다", 37.10, 126.48),
        SeaRef("평택·당진 앞바다", 36.98, 126.45),
        // 서해 (충남 · 전북)
        SeaRef("태안 학암포 앞바다", 36.90, 126.15),
        SeaRef("태안 만리포 앞바다", 36.78, 126.08),
        SeaRef("안면도 앞바다", 36.50, 126.25),
        SeaRef("오천·원산도 앞바다", 36.40, 126.30),
        SeaRef("보령·대천 앞바다", 36.30, 126.45),
        SeaRef("서천·장항 앞바다", 36.05, 126.55),
        SeaRef("군산·고군산 앞바다", 35.80, 126.30),
        SeaRef("부안·위도 앞바다", 35.60, 126.35),
        // 서해 (전남)
        SeaRef("영광·법성 앞바다", 35.40, 126.30),
        SeaRef("신안·임자도 앞바다", 35.10, 126.00),
        SeaRef("목포 앞바다", 34.70, 126.15),
        SeaRef("흑산도 앞바다", 34.68, 125.35),
        SeaRef("진도 앞바다", 34.40, 126.10),
        // 남해
        SeaRef("해남·땅끝 앞바다", 34.25, 126.55),
        SeaRef("완도 앞바다", 34.25, 126.80),
        SeaRef("고흥·나로도 앞바다", 34.40, 127.35),
        SeaRef("여수 앞바다", 34.60, 127.70),
        SeaRef("남해 앞바다", 34.68, 128.00),
        SeaRef("삼천포·사천 앞바다", 34.85, 128.10),
        SeaRef("통영 앞바다", 34.75, 128.45),
        SeaRef("거제 앞바다", 34.72, 128.75),
        SeaRef("부산 앞바다", 35.05, 129.10),
        SeaRef("기장 앞바다", 35.25, 129.30),
        // 동해
        SeaRef("울산 앞바다", 35.50, 129.45),
        SeaRef("포항·구룡포 앞바다", 36.00, 129.62),
        SeaRef("영덕 앞바다", 36.40, 129.45),
        SeaRef("울진 앞바다", 36.95, 129.48),
        SeaRef("삼척·동해 앞바다", 37.40, 129.30),
        SeaRef("강릉 앞바다", 37.80, 129.00),
        SeaRef("속초·양양 앞바다", 38.15, 128.70),
        SeaRef("고성 앞바다", 38.40, 128.55),
        SeaRef("울릉도 앞바다", 37.48, 130.95),
        // 제주
        SeaRef("제주 북쪽 앞바다", 33.58, 126.50),
        SeaRef("제주 차귀도 앞바다", 33.30, 126.10),
        SeaRef("서귀포 앞바다", 33.18, 126.55),
        SeaRef("성산 앞바다", 33.45, 127.00),
        SeaRef("추자도 앞바다", 33.95, 126.30),
    )

    class SeaPick(val ref: SeaRef, val km: Int)

    fun km(la1: Double, lo1: Double, la2: Double, lo2: Double): Double =
        Math.hypot((la2 - la1) * 111.0, (lo2 - lo1) * 111.0 * Math.cos(Math.toRadians(la1)))

    /** 가장 가까운 해상 기준점 */
    fun nearestSea(lat: Double, lon: Double): SeaPick {
        val r = SEA_REFS.minByOrNull { km(lat, lon, it.lat, it.lon) }!!
        return SeaPick(r, Math.round(km(lat, lon, r.lat, r.lon)).toInt())
    }
}
