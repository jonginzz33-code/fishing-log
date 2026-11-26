package com.fishlog.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLDecoder

/**
 * v7.15 (382): 자동 예약 — 저장해 둔 선단 예약 화면으로 마지막에 보낼 내용 확인 (실제 신청 없음)
 * 화면 모음은 저장소에 안 넣음: -Dbook.dir=폴더 (s24_*.html · reservation_boat_*.html) · 없으면 건너뜀
 */
class AutoBookTest {
    private val dir = System.getProperty("book.dir")?.let { File(it) }?.takeIf { it.isDirectory }
    private val inf = AutoBook.Info("홍길동", "01012345678", "홍입금", "왼쪽 자리 부탁", 0, "")

    private fun parse(body: String): List<Pair<String, String>> = body.split('&').filter { it.isNotEmpty() }.map {
        val (k, v) = it.split('=', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    @Test fun s24Pages() {
        val d = dir ?: return
        d.listFiles { f -> f.name.startsWith("s24_") }!!.sorted().forEach { f ->
            val html = f.readText()
            var sent: String? = null
            AutoBook.fake = { url, post ->
                when {
                    post == null -> html
                    url.endsWith("/mypage/reservation_end") -> { sent = post; "{\"reservation_no\":\"123456\"}" }
                    else -> ""
                }
            }
            val no = Regex("name=\"ship_schedule_no\" value=\"(\\d+)\"").find(html)!!.groupValues[1]
            val r = AutoBook.s24("x.sunsang24.com", no, 2, inf, false)
            println("${f.name}: ${r.ok} ${r.msg}")
            val login = f.name.startsWith("s24_True")
            if (login) { assertTrue(!r.ok); assertTrue(sent == null); return@forEach }
            val p = parse(sent ?: "")
            if (!r.ok) { println("  (안 함) ${r.msg}"); return@forEach }
            fun one(k: String) = p.filter { it.first == k }.map { it.second }
            assertEquals(listOf(no), one("ship_schedule_no"))
            assertEquals(listOf("2"), one("person_count"))
            assertEquals(listOf("{\"mode\":\"p\"}"), one("petc_data"))
            assertEquals(listOf("홍길동"), one("name")); assertEquals(listOf("홍입금"), one("deposit_name"))
            assertEquals(listOf("010", "1234", "5678"), one("phone1") + one("phone2") + one("phone3"))
            assertEquals(listOf("bank"), one("reservation_method"))
            assertEquals(3, one("agree_rule[]").size)
            if (html.contains("type=\"checkbox\" name=\"select_seat_nos[]\"")) assertEquals(2, one("select_seat_nos[]").size)
            println("  보냄: " + p.joinToString(" ") { "${it.first}=${it.second}" })
        }
        AutoBook.fake = null
    }

    @Test fun tfPages() {
        val d = dir ?: return
        d.listFiles { f -> f.name.startsWith("reservation_boat_") }!!.sorted().forEach { f ->
            val html = f.readText()
            val m = Regex("var data = 'date=(\\d{8})&PA_N_UID=(\\d+)").find(html) ?: return@forEach
            val d8 = m.groupValues[1]; val uid = m.groupValues[2]
            val mod = f.name.substringBefore("_").let { Regex("^(reservation_boat_v[0-9.]+(?:_seat\\d)?)").find(f.name)!!.groupValues[1] }
            var sent: String? = null; var ajax: String? = null; var actUrl = ""
            AutoBook.fake = { url, post ->
                when {
                    post == null && url.contains("mid=bk") -> "<a onclick=\"f_popup('/_core/module/$mod/popup.step1.php?date=$d8&PA_N_UID=$uid', 'x')\">예약</a>"
                    post == null -> html
                    url.contains("ajax") -> { ajax = post; "10만원|^|10만원|^|확인" }
                    else -> { sent = post; actUrl = url; "<script>alert('예약신청이 완료되었습니다.');location.replace('/');</script>" }
                }
            }
            val date = "${d8.take(4)}-${d8.substring(4, 6)}-${d8.substring(6)}"
            val r = AutoBook.tf("${f.name.substringAfterLast('_').removeSuffix(".html")}.thefishing.kr", uid, date, "쭈꾸미", 2, inf, false)
            println("${f.name}: ${r.ok} ${r.msg}")
            if (!r.ok) return@forEach
            val p = parse(sent!!)
            fun one(k: String) = p.filter { it.first == k }.map { it.second }
            assertTrue(actUrl, actUrl.contains("/action/") && actUrl.endsWith("step1.action.php"))
            assertEquals(listOf("insert"), one("action"))
            assertEquals(listOf("확인"), one("temp_bi_stat"))
            assertEquals(listOf("2"), one("BI_IN"))
            assertEquals(listOf(uid), one("PA_N_UID"))
            assertEquals(1, one("PS_N_UID").size); assertTrue(one("PS_N_UID")[0].isNotBlank())
            assertEquals(listOf("홍길동"), one("BI_NAME")); assertEquals(listOf("홍입금"), one("BI_BANK"))
            assertEquals(listOf("010", "1234", "5678"), one("BI_TEL1") + one("BI_TEL2") + one("BI_TEL3"))
            assertEquals(listOf("Y"), one("agree")); assertEquals(listOf("1"), one("BI_3JA"))
            assertTrue(ajax!!.contains("BI_IN=2") && ajax!!.contains("naun="))
            if (html.contains("id=\"seat_img\"") && html.contains("name=\"seat[]\"")) assertEquals(2, one("seat[]").count { it.isNotEmpty() })
            println("  보냄: " + p.filter { !it.first.startsWith("BIP_") }.joinToString(" ") { "${it.first}=${it.second}" })
        }
        AutoBook.fake = null
    }
}
