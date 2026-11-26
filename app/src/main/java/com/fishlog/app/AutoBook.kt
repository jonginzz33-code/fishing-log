package com.fishlog.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * v7.15 (382): 🤖 자동 예약 — 선상24 선단(○○.sunsang24.com) · 더피싱 선단(○○.thefishing.kr · 자기 도메인)
 *
 * 2026-11-27 새벽 선단 75곳 예약 화면을 읽어 보고 맞춤 (예약은 안 누름):
 *  선상24형 (GET /mypage/reservation_ready/{일정번호} → `var data = {...}` → POST /mypage/reservation_end · JSON)
 *   - 로그인해야 예약 (is_reservation_login · 33곳 중 19곳) · 휴대폰 인증 · 대회 예약 · 무통장 안 받음 · 출항 임박 → 신청 안 하고 알림만
 *   - 가격 종류(price_option · 예: 1인 · 독배) → 1명짜리 일반 가격만 고름 (독배 · 대여 · 여러 명짜리는 빼기)
 *   - 자리 고르기(select_seat_nos[] · arrange_seating.possible_seat_nos) → 설정한 순서대로 인원만큼
 *  더피싱형 (모듈 v3 · v5.1 · v5.2_seat1 · v5.2_seat2 · 칸 이름은 모두 같음)
 *   - /index.php?mid=bk 그 날 화면에서 그 배 예약 창 모듈 찾기 → popup.step1.php (낚시 종류 PS_N_UID 고르기 · 'SPED_UID..' 글자도 있음)
 *   - popup.step1.ajax.php 로 인원 확인 → 셋째 값이 '확인'일 때만 (대기자 · 인원초과면 안 함)
 *   - 좌석제(seat_img · seat[]) → 인원만큼 자리 고르기 · '[추첨식]' 배는 자리 안 고름
 *   - 보내는 곳(form action)은 선단마다 popu2 · popup 둘 다 있어서 화면에서 읽음
 *  예약자 정보는 이 폰에만 (prefs seat_auto_*)
 */
object AutoBook {
    class Res(val ok: Boolean, val msg: String)
    class Info(val name: String, val tel: String, val payer: String, val memo: String, val seatMode: Int, val seats: String)

    private fun prefs(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)
    fun info(c: Context) = prefs(c).let {
        Info(it.getString("seat_auto_name", "") ?: "", it.getString("seat_auto_tel", "") ?: "", it.getString("seat_auto_payer", "") ?: "",
            it.getString("seat_auto_memo", "") ?: "", it.getInt("seat_auto_seat", 0), it.getString("seat_auto_seats", "") ?: "")
    }
    val SEAT_MODES = listOf("앞 번호부터", "뒤 번호부터", "원하는 번호 먼저")

    /** 자동 예약 되는 곳 */
    fun supported(host: String) = host == "hunter-booking.onrender.com" || Seats.isS24Fleet(host) || Seats.isTf(host)

    /** dry = 마지막 신청 직전까지만 (🧪 미리 확인 · 예약 안 됨) */
    fun book(c: Context, host: String, shipNo: String, schNo: String, date: String, fish: String, need: Int, dry: Boolean = false): Res {
        val inf = info(c)
        if (inf.name.isBlank() || inf.tel.length < 10) return Res(false, "예약자 이름 · 전화번호를 먼저 적어 주세요")
        return try {
            when {
                Seats.isS24Fleet(host) -> s24(host, schNo, need, inf, dry)
                Seats.isTf(host) -> tf(host, shipNo, date, fish, need, inf, dry)
                else -> Res(false, "자동 예약을 맞춰 두지 않은 사이트예요")
            }
        } catch (e: Exception) { Res(false, "사이트 연결 실패 (${e.message?.take(40) ?: "오류"})") }
    }

    // ─────────────── 쿠키 들고 다니는 연결 ───────────────
    private class Sess(val base: String, val pcUa: Boolean) {
        val jar = LinkedHashMap<String, String>()
        var last = ""
        fun req(url: String, post: String? = null, ajax: Boolean = false): String {
            fake?.let { return it(url, post) }
            val cn = URL(url).openConnection() as HttpURLConnection
            cn.connectTimeout = 15000; cn.readTimeout = 30000; cn.instanceFollowRedirects = false
            cn.setRequestProperty("User-Agent", if (pcUa) "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
                else "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36")
            cn.setRequestProperty("Accept-Language", "ko-KR,ko")
            if (jar.isNotEmpty()) cn.setRequestProperty("Cookie", jar.entries.joinToString("; ") { "${it.key}=${it.value}" })
            if (last.isNotBlank()) cn.setRequestProperty("Referer", last)
            if (ajax) { cn.setRequestProperty("X-Requested-With", "XMLHttpRequest"); cn.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01") }
            if (post != null) {
                cn.requestMethod = "POST"; cn.doOutput = true
                cn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                cn.setRequestProperty("Origin", base)
                cn.outputStream.use { it.write(post.toByteArray(Charsets.UTF_8)) }
            }
            val code = cn.responseCode
            cn.headerFields["Set-Cookie"]?.forEach { h -> h.substringBefore(';').split('=', limit = 2).let { if (it.size == 2) jar[it[0].trim()] = it[1].trim() } }
            if (code in 300..399) {
                val loc = cn.getHeaderField("Location") ?: ""; cn.disconnect()
                if (loc.isNotBlank()) return req(URL(URL(url), loc).toString())
            }
            val txt = try { ((if (code < 400) cn.inputStream else cn.errorStream) ?: return "").bufferedReader(Charsets.UTF_8).readText() } finally { cn.disconnect() }
            if (code >= 400) throw Exception("HTTP $code")
            if (post == null) last = url
            return txt
        }
    }
    /** 시험용 (단위 시험이 저장해 둔 화면을 돌려줌 · 앱에서는 늘 null) */
    @Volatile internal var fake: ((String, String?) -> String)? = null
    /** 태그 빼고 자주 쓰는 &..; 풀기 (android Html 없이 · 단위 시험에서도 돌게) */
    internal fun plain(x: String): String = x.replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&#039;", "'").replace("&amp;", "&")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }
    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
    private fun body(l: List<Pair<String, String>>) = l.joinToString("&") { enc(it.first) + "=" + enc(it.second) }
    private fun alertOf(html: String) = Regex("alert\\([\"'](.*?)[\"']\\)").find(html)?.groupValues?.get(1)?.replace("\\n", " ")?.trim() ?: ""
    private fun phone3(t: String): Triple<String, String, String> {
        val d = t.filter { it.isDigit() }
        return if (d.length == 11) Triple(d.take(3), d.substring(3, 7), d.substring(7)) else Triple(d.take(3), d.substring(3, d.length - 4), d.takeLast(4))
    }
    private fun won(n: Long) = String.format(java.util.Locale.KOREA, "%,d원", n)

    /** 고를 자리 (설정 순서 · 원하는 번호 먼저) */
    private fun pickSeats(avail: List<String>, need: Int, inf: Info): List<String>? {
        if (avail.size < need) return null
        // 숫자 자리 먼저 · 'A' 'B' 같은 글자 자리(선수 · 선미 끝)는 뒤로
        fun key(v: String) = v.toIntOrNull() ?: (10000 + v.hashCode().and(0xFFFF))
        val want = inf.seats.split(Regex("[^0-9A-Za-z]+")).filter { it.isNotBlank() }.map { it.uppercase() }
        val num = avail.filter { it.toIntOrNull() != null }; val alpha = avail.filter { it.toIntOrNull() == null }
        val order = when (inf.seatMode) {
            1 -> num.sortedByDescending { key(it) } + alpha.sorted()
            2 -> want.mapNotNull { w -> avail.firstOrNull { it.uppercase() == w } } + (num.sortedBy { key(it) } + alpha.sorted()).filter { a -> want.none { it == a.uppercase() } }
            else -> num.sortedBy { key(it) } + alpha.sorted()
        }
        return order.distinct().take(need)
    }

    /** 속성 값 (큰따옴표 · 작은따옴표 · 따옴표 없음 · 예: value='{"mode":"p"}') */
    private fun attr(at: String, k: String): String? =
        Regex("(?<![\\w-])$k\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE).find(at)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } } }
    /** 폼 하나를 브라우저처럼 (hidden · text 값 · 체크된 것 · select 고른 것) */
    private fun formFields(form: String): MutableList<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        Regex("<(input|select|textarea)\\b([^>]*)>(.*?)(?=<(?:input|select|textarea)\\b|</form>|$)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(form).forEach { m ->
            val tag = m.groupValues[1].lowercase(); val at = m.groupValues[2]
            val name = attr(at, "name")?.takeIf { it.isNotBlank() } ?: return@forEach
            if (Regex("\\bdisabled\\b").containsMatchIn(at)) return@forEach
            val value = attr(at, "value")?.let { plain(it) } ?: ""
            when (tag) {
                "input" -> {
                    val type = Regex("type=[\"']?([a-zA-Z]+)").find(at)?.groupValues?.get(1)?.lowercase() ?: "text"
                    when (type) {
                        "checkbox", "radio" -> if (Regex("\\bchecked\\b").containsMatchIn(at)) out.add(name to value.ifEmpty { "on" })
                        "button", "submit", "image", "reset", "file" -> {}
                        else -> out.add(name to value)
                    }
                }
                "select" -> {
                    val inner = m.groupValues[3].substringBefore("</select>")
                    val opts = Regex("<option\\b([^>]*)>", RegexOption.IGNORE_CASE).findAll(inner).toList()
                    val sel = opts.firstOrNull { Regex("\\bselected\\b").containsMatchIn(it.groupValues[1]) } ?: opts.firstOrNull()
                    out.add(name to (sel?.groupValues?.get(1)?.let { attr(it, "value") } ?: ""))
                }
                "textarea" -> out.add(name to "")
            }
        }
        return out
    }
    private fun MutableList<Pair<String, String>>.set(k: String, v: String) { removeAll { it.first == k }; add(k to v) }

    // ─────────────── 선상24 선단 ───────────────
    internal fun s24(host: String, schNo: String, need: Int, inf: Info, dry: Boolean): Res {
        if (schNo.toLongOrNull()?.let { it > 0 } != true) return Res(false, "일정 번호를 몰라요 · 직접 예약해 주세요")
        val base = "https://$host"
        val ss = Sess(base, false)
        val html = ss.req("$base/mypage/reservation_ready/$schNo")
        val dm = Regex("var data = (\\{.*?\\});\\s*\\n", RegexOption.DOT_MATCHES_ALL).find(html)
            ?: return Res(false, alertOf(html).ifBlank { "예약 화면을 못 읽었어요" })
        val data = JSONObject(dm.groupValues[1])
        val sc = data.optJSONObject("schedule") ?: return Res(false, "예약 화면을 못 읽었어요")
        if (sc.optBoolean("is_reservation_login")) return Res(false, "로그인해야 예약되는 선단이라 자동 신청 못 해요")
        if (sc.optBoolean("is_reservation_phone_auth") || html.contains("var is_phone_auth = false")) return Res(false, "휴대폰 인증이 필요한 선단이라 자동 신청 못 해요")
        if (sc.optBoolean("is_reservation_contest")) return Res(false, "대회 예약이라 자동 신청 못 해요")
        if (data.optJSONObject("reservation_method")?.optBoolean("is_use", true) == false) return Res(false, "무통장 예약을 안 받는 선단이에요 (카드만)")
        if (html.contains("var is_imminent_departure = true")) return Res(false, "출항 임박 예약이라 자동 신청 안 해요")
        val remain = sc.optInt("remain_embarkation_num", 0)
        if (remain < need) return Res(false, "남은 자리가 ${remain}자리라 ${need}명 신청 못 해요")
        val per = sc.optInt("person_per_reservation", 0)
        if (per in 1 until need) return Res(false, "한 번에 ${per}명까지만 예약되는 배예요")
        val fi = html.indexOf("id=\"form-reservation\""); val fj = html.indexOf("</form>", fi)
        if (fi < 0 || fj < 0) return Res(false, "예약 칸을 못 찾았어요")
        val form = html.substring(fi, fj)
        if (!form.contains("name=\"name\"") || !form.contains("name=\"phone2\"")) return Res(false, "로그인해야 예약되는 선단이라 자동 신청 못 해요")
        // 모르는 글 칸이 있으면 (대회 · 생년월일 등) 안 함
        val known = setOf("ship_schedule_no", "petc_data", "person_count", "coupon_no", "phone", "price_option", "name", "deposit_name", "phone1", "phone2", "phone3",
            "memo", "all_check", "agree_rule[]", "pay_method", "reservation_method", "select_seat_nos[]", "cash_receipt_type", "cash_receipt_no", "default_schedule_no")
        Regex("<input\\b([^>]*)>").findAll(form).forEach { m ->
            val at = m.groupValues[1]; val n = Regex("name=\"([^\"]+)\"").find(at)?.groupValues?.get(1) ?: return@forEach
            val t = Regex("type=\"([a-z]+)\"").find(at)?.groupValues?.get(1) ?: "text"
            if (n !in known && t in setOf("text", "number", "tel")) return Res(false, "따로 적을 칸('$n')이 있는 선단이라 직접 예약해 주세요")
        }
        // 가격 종류: 1명짜리 일반 가격 (독배 · 대여 · 여러 명짜리 빼기)
        var priceOpt = ""; var unit = sc.optLong("price", 0)
        val opts = Regex("data-price_option='(\\{[^']*\\})'").findAll(html).mapNotNull { try { JSONObject(it.groupValues[1]) } catch (_: Exception) { null } }.toList()
        val real = opts.filter { it.optLong("no") > 0 }
        if (real.isNotEmpty()) {
            val o = real.firstOrNull { it.optInt("required_person") == 1 && !it.optBoolean("is_rent") && it.optLong("price") > 0 &&
                    (it.optInt("pmax") == 0 || it.optInt("pmax") >= need) && it.optInt("pmin") <= need }
                ?: return Res(false, "가격 종류를 골라야 하는 배라 직접 예약해 주세요")
            priceOpt = "${o.optLong("no")}/$need"; unit = o.optLong("price")
        }
        // 자리 고르기
        val seatBoxes = Regex("name=\"select_seat_nos\\[\\]\"[^>]*value=\"(\\d+)\"").findAll(form).map { it.groupValues[1].toInt() }.toList()
        val seats = if (seatBoxes.isEmpty()) emptyList() else {
            val poss = sc.optJSONObject("arrange_seating")?.optJSONArray("possible_seat_nos")?.let { a -> (0 until a.length()).map { a.optInt(it) } }?.filter { it in seatBoxes } ?: seatBoxes
            pickSeats(poss.ifEmpty { seatBoxes }.map { it.toString() }, need, inf) ?: return Res(false, "고를 수 있는 자리가 ${need}개가 안 돼요")
        }
        val (p1, p2, p3) = phone3(inf.tel)
        val f = formFields(form).filter { it.first != "select_seat_nos[]" && it.first != "agree_rule[]" && it.first != "all_check" }.toMutableList()
        f.set("ship_schedule_no", schNo)
        f.set("person_count", need.toString()); f.set("coupon_no", "0"); f.set("price_option", priceOpt)
        f.set("phone", ""); f.set("name", inf.name); f.set("deposit_name", inf.payer.ifBlank { inf.name })
        f.set("phone1", p1); f.set("phone2", p2); f.set("phone3", p3); f.set("memo", inf.memo)
        f.set("pay_method", ""); f.set("reservation_method", "bank")
        if (f.none { it.first == "petc_data" }) f.add("petc_data" to "{\"mode\":\"p\"}")
        f.add("all_check" to "on"); repeat(Regex("name=\"agree_rule\\[\\]\"").findAll(form).count().coerceAtLeast(3)) { f.add("agree_rule[]" to "on") }
        seats.forEach { f.add("select_seat_nos[]" to it.toString()) }
        if (dry) return Res(true, "🧪 신청 직전까지 OK · ${need}명${if (seats.isNotEmpty()) " · 자리 ${seats.joinToString(",")}번" else ""} · ${if (unit > 0) won(unit * need) else "금액 모름"}${if (priceOpt.isNotBlank()) " (가격 종류 $priceOpt)" else ""}")
        val r = ss.req("$base/mypage/reservation_end", body(f), ajax = true)
        val j = try { JSONObject(r) } catch (_: Exception) { return Res(false, "사이트 답을 못 읽었어요 · 사이트에서 예약됐는지 꼭 확인") }
        val no = j.opt("reservation_no")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            ?: return Res(false, j.optString("message").ifBlank { "예약이 안 됐어요" })
        val pay = data.optJSONObject("payment")
        val rate = data.optJSONObject("reservation_method")?.optJSONObject("bank_add")?.optInt("deposit_rate", -1)?.takeIf { it > 0 } ?: pay?.optInt("deposit_rate", 100) ?: 100
        val total = unit * need
        val bank = pay?.let { listOf(it.optString("bank_name"), it.optString("bank_account"), it.optString("bank_owner")).filter { s -> s.isNotBlank() && s != "null" }.joinToString(" ") } ?: ""
        val seatTxt = if (seats.isNotEmpty()) " · 자리 ${seats.joinToString(",")}번" else ""
        return Res(true, "예약번호 $no$seatTxt · 입금 ${if (total > 0) won(total * rate / 100) else "금액은 사이트 확인"}${if (rate in 1..99) " (예약금 $rate%)" else ""}${if (bank.isNotBlank()) " · $bank" else ""}")
    }

    // ─────────────── 더피싱 선단 ───────────────
    internal fun tf(host: String, uid: String, date: String, fish: String, need: Int, inf: Info, dry: Boolean): Res {
        val d8 = date.replace("-", "")
        val err = ArrayList<Exception>()
        for (scheme in listOf("https", "http")) {
            try { return tf0("$scheme://$host", uid, d8, fish, need, inf, dry) } catch (e: Exception) { err.add(e) }
        }
        throw err.first()
    }
    private fun tf0(base: String, uid: String, d8: String, fish: String, need: Int, inf: Info, dry: Boolean): Res {
        val ss = Sess(base, true)
        val bk = ss.req("$base/index.php?mid=bk&year=${d8.take(4)}&month=${d8.substring(4, 6)}&day=${d8.substring(6)}")
        val mod = Regex("/_core/module/(reservation_boat_[a-z0-9_.]+)/popup\\.step1\\.php\\?date=$d8&(?:amp;)?PA_N_UID=$uid\\b").find(bk)?.groupValues?.get(1)
            ?: return Res(false, if (bk.contains("PA_N_UID=$uid")) "예약 창 모양이 달라 자동 신청 못 해요" else "그 날 그 배 예약 버튼이 없어요")
        val m = "/_core/module/$mod"
        val p1 = ss.req("$base$m/popup.step1.php?date=$d8&PA_N_UID=$uid")
        if (!p1.contains("insert_form")) return Res(false, alertOf(p1).ifBlank { "예약 창이 안 열려요 (로그인 필요할 수도)" })
        // 낚시 종류 (이름(N명))
        class Ps(val v: String, val label: String, val left: Int)
        var page = p1
        // 한 배 안에 좌대 · 칸(PH_N_UID)을 먼저 고르는 선단 (낚시 종류가 그다음에 나옴)
        var ph = "0"
        val phs = Regex("<input type=\"radio\"[^>]*name=\"PH_N_UID\"[^>]*value=\"(\\d+)\"").findAll(p1).map { it.groupValues[1] }.toList()
        if (phs.isNotEmpty() && !p1.contains("class=\"PS_N_UID\"")) {
            ph = phs.first()
            page = ss.req("$base$m/popup.step1.php?date=$d8&PA_N_UID=$uid&PH_N_UID=$ph")
        }
        // 낚시 종류 (이름(N명))
        val pg0 = page
        val radios = Regex("<input[^>]*class=\"PS_N_UID\"[^>]*id=\"([^\"]+)\"[^>]*value=\"([^\"]+)\"").findAll(pg0).toList()
        val pss = radios.map { r ->
            val lab = Regex("<label for=\"${Regex.escape(r.groupValues[1])}\"[^>]*>(.*?)</label>", RegexOption.DOT_MATCHES_ALL).find(pg0, r.range.last)?.groupValues?.get(1) ?: ""
            val t = plain(lab).replace(Regex("\\s+"), " ").trim()
            Ps(r.groupValues[2], t, Regex("\\((\\d+)명\\)\\s*$").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: -1)
        }
        if (pss.isNotEmpty()) {
            val ok = pss.filter { it.left < 0 || it.left >= need }
            if (ok.isEmpty()) return Res(false, "낚시 종류마다 남은 자리가 ${need}자리가 안 돼요")
            val words = fish.split(Regex("[,/·\\s]+")).map { it.trim() }.filter { it.length >= 2 }
            val pick = ok.firstOrNull { p -> words.any { p.label.contains(it) } } ?: ok.first()
            page = ss.req("$base$m/popup.step1.php?date=$d8&PA_N_UID=$uid&PH_N_UID=$ph&PS_N_UID=${enc(pick.v)}")
        } else if (!page.contains("name=\"BI_NAME\"")) return Res(false, "고를 수 있는 낚시 종류가 없어요 (마감일 수도)")
        val fi = page.indexOf("id=\"insert_form\""); val fj = page.indexOf("</form>", fi)
        if (fi < 0 || fj < 0) return Res(false, "예약 칸을 못 찾았어요")
        val formTag = page.substring(page.lastIndexOf("<form", fi), fi + 200)
        val form = page.substring(fi, fj)
        if (form.contains("name=\"site_cd\"") || form.contains("name=\"enc_data\"") || Regex("action=\"\"").containsMatchIn(formTag)) return Res(false, "카드 결제로 예약받는 선단이라 자동 신청 못 해요")
        val action = Regex("action=\"([^\"]+)\"").find(formTag)?.groupValues?.get(1) ?: return Res(false, "보내는 곳을 못 찾았어요")
        if (!form.contains("name=\"BI_NAME\"") || !form.contains("name=\"BI_TEL2\"")) return Res(false, "예약 칸 모양이 달라 자동 신청 못 해요")
        // 인원
        val biIn = Regex("<select[^>]*name=\"BI_IN\"[^>]*>(.*?)</select>", RegexOption.DOT_MATCHES_ALL).find(form)?.groupValues?.get(1)
            ?.let { s -> Regex("value=\"([^\"]*)\"").findAll(s).map { it.groupValues[1] }.toList() } ?: emptyList()
        if (biIn.isNotEmpty() && need.toString() !in biIn) return Res(false, "${need}명은 고를 수 없는 배예요")
        val soIn = Regex("<select[^>]*name=\"BI_SO_IN\"[^>]*>(.*?)</select>", RegexOption.DOT_MATCHES_ALL).find(form)?.groupValues?.get(1)
            ?.let { s -> Regex("value=\"([^\"]*)\"").findAll(s).map { it.groupValues[1] }.toList() }
        val so = soIn?.let { if ("0" in it) "0" else it.firstOrNull() ?: "" }
        // 인원 확인 (셋째 값 '확인'일 때만)
        val ajaxUrl = Regex("url:\\s*\"(/_core/module/[^\"]+popup\\.step1\\.ajax\\.php)\"").find(page)?.groupValues?.get(1) ?: "$m/action/popup.step1.ajax.php"
        val pre = Regex("var data = '(date=[^']*?)&BI_IN=").find(page)?.groupValues?.get(1) ?: return Res(false, "인원 확인 칸을 못 찾았어요")
        val naun = Regex("naun=(\\d+)").find(page)?.groupValues?.get(1) ?: "0"
        val pm = if (page.contains("id=\"pay_method\"")) "" else "undefined"
        val ar = ss.req(base + ajaxUrl, "$pre&BI_IN=$need&BI_SO_IN=${so ?: "N"}&pay_method=$pm&naun=$naun", ajax = true)
        val stat = ar.split("|^|").getOrNull(2)?.trim() ?: ""
        if (stat != "확인") return Res(false, when {
            stat.contains("대기") -> "대기 예약만 되는 상태라 신청 안 했어요"
            stat.contains("초과") -> "인원이 남은 자리보다 많대요"
            else -> "인원 확인이 안 됐어요" + (if (stat.isNotBlank()) " ($stat)" else "")
        })
        // 좌석제 → 인원만큼 자리
        // 자리 = seat[] 칸(id 끝이 자리 이름 · 'A' 같은 글자도) · 누를 수 있는 칸(onclick)만 빈자리
        val seatAll = Regex("<input id=\"s$d8$uid([^\"]*)\"[^>]*name=\"seat\\[\\]\"").findAll(form).map { it.groupValues[1] }.toList()
        val seatInputs = Regex("name=\"seat\\[\\]\"").findAll(form).count()
        val seatOpen = Regex("onclick=\"ticket_seat(?:_ps)?_click\\('\\d+',\\s*'\\d+',\\s*'([^']+)'\\);?\"").findAll(form).map { it.groupValues[1] }.toSet()
        if (form.contains("onclick=\"ticket_seat_bus_click(")) return Res(false, "버스식 좌석 배라 직접 예약해 주세요")
        val chosen = if (page.contains("id=\"seat_img\"") && seatInputs > 0) {
            if (seatAll.size != seatInputs) return Res(false, "좌석표를 못 읽었어요")
            pickSeats(seatAll.filter { it in seatOpen }, need, inf) ?: return Res(false, "고를 수 있는 자리가 ${need}개가 안 돼요")
        } else emptyList()
        val (t1, t2, t3) = phone3(inf.tel)
        val f = formFields(form).filter { it.first != "seat[]" && it.first != "dong_check" }.toMutableList()
        f.set("temp_bi_stat", "확인"); f.set("BI_IN", need.toString()); so?.let { f.set("BI_SO_IN", it) }
        if (pss.isNotEmpty()) f.set("PS_N_UID", Regex("PS_N_UID=([^&]+)").find(pre)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: "")
        f.set("PA_N_UID", uid)
        f.set("BI_NAME", inf.name); f.set("BI_BANK", inf.payer.ifBlank { inf.name })
        f.set("BI_TEL1", t1); f.set("BI_TEL2", t2); f.set("BI_TEL3", t3); f.set("BI_MEMO", inf.memo)
        f.set("agree", "Y"); f.set("BI_3JA", "1"); f.set("BI_AD", "0"); f.removeAll { it.first == "all_agree" }
        if (seatInputs > 0) seatAll.forEach { v -> f.add("seat[]" to if (v in chosen) v else "") }
        if (ph != "0" || f.any { it.first == "PH_N_UID" }) f.set("PH_N_UID", ph)
        if (dry) return Res(true, "🧪 신청 직전까지 OK · ${mod.removePrefix("reservation_boat_")} · 낚시 종류 ${pss.firstOrNull { p -> f.any { it.first == "PS_N_UID" && it.second == p.v } }?.label ?: "-"} · ${need}명${if (chosen.isNotEmpty()) " · 자리 ${chosen.joinToString(",")}번" else ""}")
        val r = ss.req(URL(URL(base + m + "/"), action).toString(), body(f))
        val al = alertOf(r)
        if (al.isNotBlank() && Regex("실패|초과|마감|없|불가|오류|다시|선택|입력").containsMatchIn(al) && !al.contains("완료")) return Res(false, al)
        val seatTxt = if (chosen.isNotEmpty()) " · 자리 ${chosen.joinToString(",")}번" else ""
        return Res(true, (al.ifBlank { "예약 신청을 보냈어요" }) + seatTxt + " · 입금 안내는 사이트 · 문자 확인")
    }
}
