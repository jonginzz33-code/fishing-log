package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog

/**
 * v6.28 (253): 어종 이름 오타 알아보기 — '니ㅏㄱ지' → 낙지 (자판 꼬임 · 한 글자 틀림)
 *  - 글자를 자음 · 모음으로 풀어서 알고 있는 어종 이름과 얼마나 다른지 셈
 *  - 새 어종을 추가할 때 "혹시 '낙지'인가요?" · 그림 고를 때는 풀린 자음 · 모음(ㄱ ㅏ 같은)이 섞인 이름만 몰래 고쳐서 그림 찾기
 */
object SpeciesFix {
    private val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private val JUNG = listOf("ㅏ", "ㅐ", "ㅑ", "ㅒ", "ㅓ", "ㅔ", "ㅕ", "ㅖ", "ㅗ", "ㅗㅏ", "ㅗㅐ", "ㅗㅣ", "ㅛ", "ㅜ", "ㅜㅓ", "ㅜㅔ", "ㅜㅣ", "ㅠ", "ㅡ", "ㅡㅣ", "ㅣ")
    private val JONG = listOf("", "ㄱ", "ㄲ", "ㄱㅅ", "ㄴ", "ㄴㅈ", "ㄴㅎ", "ㄷ", "ㄹ", "ㄹㄱ", "ㄹㅁ", "ㄹㅂ", "ㄹㅅ", "ㄹㅌ", "ㄹㅍ", "ㄹㅎ",
        "ㅁ", "ㅂ", "ㅂㅅ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ")
    /** 겹모음 · 겹받침 낱자(ㅘ ㄳ 등)도 풀어서 */
    private val COMPAT = mapOf('ㅘ' to "ㅗㅏ", 'ㅙ' to "ㅗㅐ", 'ㅚ' to "ㅗㅣ", 'ㅝ' to "ㅜㅓ", 'ㅞ' to "ㅜㅔ", 'ㅟ' to "ㅜㅣ", 'ㅢ' to "ㅡㅣ",
        'ㄳ' to "ㄱㅅ", 'ㄵ' to "ㄴㅈ", 'ㄶ' to "ㄴㅎ", 'ㄺ' to "ㄹㄱ", 'ㄻ' to "ㄹㅁ", 'ㄼ' to "ㄹㅂ", 'ㄽ' to "ㄹㅅ", 'ㄾ' to "ㄹㅌ", 'ㄿ' to "ㄹㅍ", 'ㅀ' to "ㄹㅎ", 'ㅄ' to "ㅂㅅ")

    /** 그림이 있거나 흔한 어종 (그림 찾기용 · 폰 설정 없이도) */
    val COMMON = listOf(
        "주꾸미", "갑오징어", "오징어", "한치", "낙지", "문어", "군소", "우럭", "광어", "도다리", "참돔", "감성돔", "돌돔", "노래미", "쥐노래미",
        "놀래기", "볼락", "농어", "삼치", "학꽁치", "갈치", "쥐치", "전갱이", "복어", "숭어", "게르치", "대구", "부시리", "방어", "붉바리",
        "능성어", "민어", "옥돔", "열기", "성대", "양태", "보리멸", "뱅어", "갯장어", "붕장어", "붕어", "잉어", "배스", "메기", "가물치",
        "맛조개", "조개", "백합", "피조개", "바지락", "동죽", "홍합", "키조개", "굴", "가리비", "전복", "소라", "골뱅이", "꽃게", "박하지",
        "대게", "홍게", "칠게", "갯가재", "성게", "멍게", "대하", "보리새우", "쏙", "해삼", "쏘가리", "송어", "블루길", "고등어", "가자미", "쭈갑")

    fun jamo(s: String): String = buildString {
        for (ch in s) {
            if (ch == ' ') continue
            val code = ch.code - 0xAC00
            if (code in 0 until 11172) {
                append(CHO[code / 588]); append(JUNG[(code % 588) / 28]); append(JONG[code % 28])
            } else append(COMPAT[ch] ?: ch.toString())
        }
    }

    private fun dist(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val t = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = t
            }
        }
        return d[b.length]
    }

    /** 풀린 자음 · 모음이 섞여 있나 (예: '니ㅏㄱ지') */
    fun hasLooseJamo(s: String) = s.any { it in 'ㄱ'..'ㅣ' }

    /** 가장 비슷한 어종 · 없으면 null (같은 이름이면 null) */
    fun guess(name: String, known: Collection<String> = COMMON): String? {
        val n = name.replace(" ", "")
        if (n.isEmpty() || known.any { it.replace(" ", "") == n }) return null
        val j = jamo(n)
        val loose = hasLooseJamo(n)
        // 풀린 자음 · 모음이 있으면 2칸까지, 아니면 1칸까지 (짧은 이름은 엄격하게)
        val limit = if (loose) 2 else if (j.length >= 6) 1 else 0
        if (limit == 0) return null
        return known.distinct().map { it to dist(j, jamo(it)) }.filter { it.second in 1..limit }
            .minWithOrNull(compareBy<Pair<String, Int>> { it.second }.thenBy { kotlin.math.abs(it.first.length - n.length) })?.first
    }

    /** 그림 찾기용: 풀린 자음 · 모음이 있을 때만 고쳐서 (아니면 그대로) */
    fun forArt(name: String): String = if (hasLooseJamo(name)) guess(name) ?: name else name

    /** 모든 출조 종류의 어종 + 흔한 어종 */
    fun known(): List<String> = (COMMON + Store.CATS.flatMap { Store.species(it) }).distinct()

    /** 새 어종을 추가하기 전에: 비슷한 게 있으면 "혹시 '낙지'인가요?" → 고른 이름으로 [then] */
    fun check(a: Activity, name: String, then: (String) -> Unit) {
        val g = guess(name, known()) ?: return then(name)
        AlertDialog.Builder(a, R.style.FlAlert)
            .setTitle("혹시 '$g'인가요?")
            .setMessage("'$name'은(는) '$g'과(와) 비슷해서 오타일 수 있어요.\n(자판이 꼬이면 '니ㅏㄱ지'처럼 들어가요)")
            .setPositiveButton("'$g'(으)로") { _, _ -> then(g) }
            .setNegativeButton("그대로 '$name'") { _, _ -> then(name) }
            .show()
    }
}
