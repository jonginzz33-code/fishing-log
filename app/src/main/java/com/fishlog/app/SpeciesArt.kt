package com.fishlog.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import androidx.core.graphics.PathParser

/**
 * 카운터 카드 오른쪽 아래의 반투명 귀여운 어종 그림 (100×100 좌표로 그림)
 *  - 어종 이름으로 그림을 고르고, 없으면 이름 끝말로 짐작 (○○조개 → 조개, ○○게 → 꽃게 …), 그래도 없으면 물고기
 */
class SpeciesArtView(c: Context) : View(c) {

    var species: String = ""
        set(v) { if (field != v) { field = v; invalidate() } }
    var ink: Int = Color.BLACK
    /** 그림만 연하게 (이름표는 진하게) */
    var artAlpha: Float = 1f
    /** 이마 이름표 띠 색 (0 = 이름표 없음) */
    var labelBg: Int = 0
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val fillInk = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val cheek = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F07AA0"); alpha = 215 }

    init { alpha = 0.45f; isClickable = false }

    override fun onDraw(cv: Canvas) {
        val s = minOf(width, height) / 100f
        cv.save()
        cv.translate(width - 100 * s, height - 100 * s)
        cv.scale(s, s)
        val key = SpeciesArt.keyOf(species)
        if (artAlpha < 1f) {
            cv.saveLayerAlpha(0f, 0f, 100f, 100f, (artAlpha * 255).toInt())
            draw(cv, key)
            cv.restore()
        } else draw(cv, key)
        // 이마 이름표 (C안: 색 띠 + 흰 글씨)
        val name = species.trim()
        if (labelBg != 0 && name.isNotEmpty()) {
            val (lx, ly) = SpeciesArt.forehead(key)
            labelPaint.textSize = if (name.length <= 3) 10f else if (name.length <= 4) 9f else 7.5f
            val w = labelPaint.measureText(name) + 7f
            val h = labelPaint.textSize + 4f
            val left = (lx - w / 2).coerceIn(1f, 99f - w)
            bandPaint.color = labelBg
            cv.drawRoundRect(RectF(left, ly - h / 2, left + w, ly + h / 2), h / 2, h / 2, bandPaint)
            cv.drawText(name, left + w / 2, ly + labelPaint.textSize * 0.36f, labelPaint)
        }
        cv.restore()
    }

    /** 지도 아이콘용: (cx, cy) 가운데에 size 크기로 어종 그림 */
    fun drawArt(cv: Canvas, name: String, cx: Float, cy: Float, size: Float, inkColor: Int) {
        ink = inkColor
        val s = size / 100f
        cv.save(); cv.translate(cx - size / 2, cy - size / 2); cv.scale(s, s)
        draw(cv, SpeciesArt.keyOf(name))
        cv.restore()
    }

    // ---------------- 그리기 도구 ----------------
    private fun p(d: String): Path = SpeciesArt.path(d)
    private fun fill(cv: Canvas, vararg ds: String) = ds.forEach { cv.drawPath(p(it), white) }
    private fun strokeInk(cv: Canvas, d: String, w: Float = 1.5f, a: Int = 95) {
        line.color = ink; line.alpha = a; line.strokeWidth = w; cv.drawPath(p(d), line)
    }
    private fun strokeWhite(cv: Canvas, d: String, w: Float) {
        line.color = Color.WHITE; line.alpha = 255; line.strokeWidth = w; cv.drawPath(p(d), line)
    }
    private fun dot(cv: Canvas, x: Float, y: Float, r: Float, color: Int, a: Int = 255) {
        fillInk.color = color; fillInk.alpha = a; cv.drawCircle(x, y, r, fillInk)
    }
    private fun oval(cv: Canvas, x: Float, y: Float, rx: Float, ry: Float, paint: Paint) =
        cv.drawOval(RectF(x - rx, y - ry, x + rx, y + ry), paint)

    /** 눈 두 개 + 볼터치 + 웃는 입 */
    private fun face(cv: Canvas, x: Float, y: Float, k: Float = 1f, gap: Float = 18f) {
        val g = gap * k / 2
        dot(cv, x - g, y, 4.6f * k, ink); dot(cv, x + g, y, 4.6f * k, ink)
        dot(cv, x - g + 1.5f * k, y - 1.5f * k, 1.5f * k, Color.WHITE); dot(cv, x + g + 1.5f * k, y - 1.5f * k, 1.5f * k, Color.WHITE)
        oval(cv, x - g - 6 * k, y + 8 * k, 3.6f * k, 2.2f * k, cheek); oval(cv, x + g + 6 * k, y + 8 * k, 3.6f * k, 2.2f * k, cheek)
        line.color = ink; line.alpha = 255; line.strokeWidth = 1.9f * k
        cv.drawPath(Path().apply { moveTo(x - 4 * k, y + 8 * k); quadTo(x, y + 12 * k, x + 4 * k, y + 8 * k) }, line)
    }

    /** 옆모습 물고기: 눈 하나 + 볼 + 입 */
    private fun fish(cv: Canvas, body: String = "M14 52 C 22 30, 62 26, 78 46 C 62 70, 24 72, 14 52 Z",
                     fin: String? = null, ex: Float = 34f, ey: Float = 48f, eye: Float = 0.85f, extra: (() -> Unit)? = null) {
        fin?.let { fill(cv, it) }
        fill(cv, body, "M76 48 L 94 34 L 90 50 L 94 66 Z")
        extra?.invoke()
        dot(cv, ex, ey, 5 * eye, ink); dot(cv, ex + 1.5f, ey - 1.5f, 1.6f, Color.WHITE)
        oval(cv, ex + 2, ey + 10, 3.6f, 2.1f, cheek)
        line.color = ink; line.alpha = 255; line.strokeWidth = 1.9f
        cv.drawPath(Path().apply { moveTo(ex - 16, ey + 6); quadTo(ex - 12, ey + 10, ex - 8, ey + 6) }, line)
    }

    private fun draw(cv: Canvas, key: String) {
        when (key) {
            "주꾸미" -> {
                oval(cv, 50f, 38f, 26f, 24f, white)
                fill(cv, "M30 52 C 22 66,16 74,10 80 C 18 80,26 72,34 60Z", "M38 58 C 34 72,30 82,24 92 C 32 90,38 80,42 64Z",
                    "M48 60 C 48 74,46 86,44 96 C 52 92,54 80,54 62Z", "M58 60 C 62 74,64 84,68 94 C 74 88,68 74,64 60Z",
                    "M66 56 C 74 68,80 76,90 82 C 90 74,80 66,72 52Z")
                face(cv, 50f, 38f)
            }
            "갑오징어" -> {
                fill(cv, "M20 40 C 20 22,80 22,82 40 C 84 56,22 58,20 40Z", "M22 36 C 16 32,12 34,10 40 C 12 46,16 48,22 44Z",
                    "M80 34 C 88 30,94 36,92 44 C 88 50,82 48,80 44Z", "M30 54 C 28 66,26 74,22 84 C 30 82,34 72,36 56Z",
                    "M42 56 C 42 70,40 80,38 90 C 46 88,48 76,48 56Z", "M54 56 C 56 70,58 80,62 90 C 68 84,62 72,60 56Z",
                    "M66 54 C 70 64,74 72,80 80 C 84 74,76 64,72 52Z")
                strokeInk(cv, "M30 32 q4 -4 8 0 q4 4 8 0 q4 -4 8 0 q4 4 8 0 q4 -4 8 0", 1.4f, 110)
                face(cv, 50f, 44f)
            }
            "쭈갑" -> {
                cv.save(); cv.translate(-4f, 10f); cv.scale(0.62f, 0.62f); draw(cv, "주꾸미"); cv.restore()
                cv.save(); cv.translate(42f, 22f); cv.scale(0.62f, 0.62f); draw(cv, "갑오징어"); cv.restore()
            }
            "낙지" -> {
                oval(cv, 50f, 30f, 16f, 22f, white)
                fill(cv, "M38 44 C 30 64,22 80,12 94 C 22 92,32 76,42 52Z", "M46 48 C 44 68,40 84,36 98 C 44 96,48 80,50 52Z",
                    "M54 48 C 58 68,62 84,68 98 C 74 94,64 76,58 50Z", "M62 44 C 70 62,80 78,90 92 C 92 84,78 66,66 42Z")
                face(cv, 50f, 32f, 0.85f, 14f)
            }
            "문어" -> {
                oval(cv, 50f, 34f, 30f, 26f, white)
                fill(cv, "M24 50 C 10 60,6 74,14 80 C 20 84,22 74,18 70 C 16 64,24 60,32 56Z",
                    "M34 56 C 26 72,26 86,34 90 C 40 92,40 84,36 80 C 34 74,38 66,42 60Z",
                    "M58 60 C 62 66,66 74,64 80 C 60 84,60 92,66 90 C 74 86,74 72,66 56Z",
                    "M68 54 C 76 60,84 64,86 70 C 84 76,88 82,92 78 C 98 70,90 58,76 50Z")
                face(cv, 50f, 36f)
            }
            "해삼" -> {
                fill(cv, "M10 56 C 10 34,90 34,92 54 C 94 74,10 78,10 56Z")
                listOf(24f to 40f, 40f to 36f, 56f to 36f, 72f to 38f).forEach { (x, y) -> cv.drawCircle(x, y, 4f, white) }
                cv.drawCircle(84f, 44f, 3.5f, white)
                face(cv, 30f, 54f, 0.8f, 12f)
            }
            "소라" -> {
                fill(cv, "M50 10 C 58 12,62 18,64 24 C 80 28,90 42,88 58 C 86 76,68 88,48 88 C 28 88,14 76,14 60 C 14 44,26 30,40 26 C 40 18,44 12,50 10Z",
                    "M64 30 L 74 20 L 72 34Z", "M78 40 L 92 34 L 84 48Z", "M84 58 L 98 58 L 86 66Z",
                    "M40 30 L 32 18 L 46 26Z", "M26 38 L 12 30 L 22 46Z")
                strokeInk(cv, "M44 22 C 52 20,60 24,62 30"); strokeInk(cv, "M26 44 C 44 34,70 36,84 50"); strokeInk(cv, "M18 62 C 40 52,70 54,86 66")
                fillInk.color = ink; fillInk.alpha = 46; oval(cv, 34f, 72f, 14f, 11f, fillInk)
                face(cv, 52f, 58f, 1f, 16f)
            }
            "골뱅이" -> {
                fill(cv, "M50 4 C 54 10,56 16,58 22 C 64 28,68 34,70 42 C 76 52,76 66,68 76 C 64 82,58 86,56 96 L 50 98 C 48 90,44 86,38 82 C 28 74,24 60,30 46 C 32 36,38 28,42 22 C 44 14,46 8,50 4Z")
                strokeInk(cv, "M44 18 C 50 20,54 20,57 18"); strokeInk(cv, "M40 28 C 48 32,58 32,64 28")
                strokeInk(cv, "M33 42 C 46 48,62 48,71 40"); strokeInk(cv, "M29 58 C 46 64,64 64,74 56")
                fillInk.color = ink; fillInk.alpha = 40; cv.drawPath(p("M58 62 C 66 66,66 78,58 86 C 54 80,54 70,58 62Z"), fillInk)
                face(cv, 50f, 66f, 0.95f, 14f)
            }
            "꽃게" -> {
                fill(cv, "M18 56 C 18 36,82 36,82 56 C 82 72,18 72,18 56Z", "M20 46 C 10 40,6 28,14 20 C 18 26,22 24,22 18 C 30 24,28 36,26 46Z",
                    "M80 46 C 90 40,94 28,86 20 C 82 26,78 24,78 18 C 70 24,72 36,74 46Z")
                listOf("M24 64 L 10 76", "M30 68 L 20 84", "M76 64 L 90 76", "M70 68 L 80 84").forEach { strokeWhite(cv, it, 4f) }
                face(cv, 50f, 52f)
            }
            "작은게" -> {
                // 박하지·민꽃게·돌게 등: 동글고 작은 게
                cv.save(); cv.translate(50f, 58f); cv.scale(0.78f, 0.78f); cv.translate(-50f, -52f)
                oval(cv, 50f, 54f, 30f, 20f, white)
                fill(cv, "M24 46 C 14 42,10 32,16 26 C 20 30,24 30,24 24 C 32 28,30 38,28 46Z",
                    "M76 46 C 86 42,90 32,84 26 C 80 30,76 30,76 24 C 68 28,70 38,72 46Z")
                listOf("M26 62 L 14 72", "M32 66 L 24 80", "M74 62 L 86 72", "M68 66 L 76 80").forEach { strokeWhite(cv, it, 4.5f) }
                face(cv, 50f, 52f, 1.05f, 16f)
                cv.restore()
            }
            "키조개" -> {
                fill(cv, "M50 10 L 88 82 C 70 92,30 92,12 82Z")
                strokeInk(cv, "M50 14 L 30 84"); strokeInk(cv, "M50 14 L 50 88"); strokeInk(cv, "M50 14 L 70 84")
                face(cv, 50f, 60f, 0.85f, 16f)
            }
            "맛조개" -> {
                cv.drawRoundRect(RectF(8f, 36f, 86f, 62f), 13f, 13f, white)
                fill(cv, "M84 42 C 94 42,96 56,86 56Z")
                strokeInk(cv, "M16 44 L 78 44", 1.4f, 90)
                face(cv, 46f, 50f, 0.8f, 14f)
            }
            "조개" -> {
                fill(cv, "M12 64 C 12 26,88 26,88 64 C 70 76,30 76,12 64Z")
                cv.drawRoundRect(RectF(40f, 68f, 60f, 78f), 4f, 4f, white)
                listOf("M50 72 L 26 38", "M50 72 L 40 32", "M50 72 L 60 32", "M50 72 L 74 38").forEach { strokeInk(cv, it) }
                face(cv, 50f, 54f, 0.8f, 16f)
            }
            "광어" -> {
                oval(cv, 46f, 52f, 36f, 24f, white)
                fill(cv, "M80 52 L 96 40 L 96 64Z")
                line.color = Color.WHITE; line.alpha = 255; line.strokeWidth = 5f
                line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(2f, 4f), 0f)
                cv.drawPath(p("M12 52 C 14 30,78 30,80 52 C 78 74,14 74,12 52Z"), line)
                line.pathEffect = null
                dot(cv, 30f, 42f, 4.4f, ink); dot(cv, 40f, 38f, 4.4f, ink)
                dot(cv, 31.4f, 40.6f, 1.4f, Color.WHITE); dot(cv, 41.4f, 36.6f, 1.4f, Color.WHITE)
                oval(cv, 44f, 52f, 3.6f, 2.1f, cheek)
                line.color = ink; line.strokeWidth = 1.9f
                cv.drawPath(Path().apply { moveTo(22f, 54f); quadTo(26f, 58f, 30f, 54f) }, line)
            }
            "우럭" -> fish(cv, "M10 52 C 16 28,62 24,78 46 C 62 74,18 76,10 52Z",
                "M28 32 L 32 18 L 38 30 L 44 16 L 50 30 L 56 18 L 60 32Z", 32f, 46f)
            "참돔" -> fish(cv, "M12 54 C 16 22,66 18,78 50 C 64 80,20 82,12 54Z", "M30 26 C 40 10,58 12,66 28Z", 34f, 46f)
            "노래미" -> fish(cv, "M8 52 C 18 36,66 34,80 48 C 66 64,18 66,8 52Z", "M26 40 C 38 30,60 30,70 42Z", 28f, 48f) {
                listOf(Triple(50f, 48f, 3f), Triple(60f, 54f, 2.5f), Triple(44f, 56f, 2.5f)).forEach { (x, y, r) -> dot(cv, x, y, r, ink, 64) }
            }
            "감성돔" -> fish(cv, "M12 54 C 16 24,66 22,78 50 C 64 80,20 80,12 54Z",
                "M30 28 L 34 18 L 40 26 L 46 16 L 52 26 L 58 18 L 62 30Z", 32f, 46f) {
                strokeInk(cv, "M48 32 L 48 72", 2f, 72); strokeInk(cv, "M58 34 L 58 70", 2f, 72)
            }
            "농어" -> fish(cv, "M6 50 C 18 34,70 32,82 48 C 70 64,18 66,6 50Z", "M30 38 L 36 26 L 42 36 L 54 28 L 60 38Z", 24f, 46f)
            "볼락" -> fish(cv, "M14 54 C 18 28,64 26,78 48 C 64 74,22 78,14 54Z",
                "M30 30 L 34 20 L 40 28 L 46 18 L 52 28 L 58 22 L 60 32Z", 34f, 46f, 1.3f)
            "학꽁치" -> {
                fill(cv, "M14 50 C 30 40,64 40,82 48 C 64 58,30 58,14 50Z", "M80 48 L 96 38 L 92 48 L 96 58Z", "M14 52 L 2 56 L 14 49Z")
                dot(cv, 26f, 47f, 3.6f, ink); dot(cv, 27f, 46f, 1.2f, Color.WHITE)
                oval(cv, 30f, 54f, 3f, 1.8f, cheek)
            }
            else -> fish(cv)
        }
    }
}

object SpeciesArt {
    private val cache = HashMap<String, Path>()
    fun path(d: String): Path = cache.getOrPut(d) { PathParser.createPathFromPathData(d) }

    private val NAMES = listOf("주꾸미", "갑오징어", "쭈갑", "낙지", "문어", "해삼", "소라", "골뱅이", "꽃게", "키조개", "맛조개", "조개",
        "광어", "우럭", "참돔", "노래미", "감성돔", "농어", "볼락", "학꽁치", "작은게")

    /** 그림마다 이마(눈 위) 자리 (100×100 좌표) */
    fun forehead(key: String): Pair<Float, Float> = when (key) {
        "주꾸미" -> 50f to 26f; "갑오징어" -> 50f to 32f; "쭈갑" -> 50f to 14f; "낙지" -> 50f to 19f; "문어" -> 50f to 22f
        "해삼" -> 60f to 46f; "소라" -> 52f to 44f; "골뱅이" -> 50f to 50f; "꽃게" -> 50f to 42f; "작은게" -> 50f to 46f
        "키조개" -> 50f to 46f; "맛조개" -> 48f to 30f; "조개" -> 50f to 44f; "광어" -> 58f to 47f
        "학꽁치" -> 50f to 38f; "감성돔" -> 40f to 34f
        else -> 52f to 40f // 물고기: 눈 위 몸통
    }

    /** 어종 이름 → 그림 이름 */
    fun keyOf(name: String): String {
        val n = name.replace(" ", "")
        if (n in NAMES) return n
        return when {
            n.contains("쭈꾸미") || n.contains("주꾸미") -> "주꾸미"
            n.contains("쭈갑") -> "쭈갑"
            n.contains("오징어") || n.contains("한치") || n.contains("무늬") -> "갑오징어"
            n.contains("낙지") -> "낙지"
            n.contains("문어") -> "문어"
            n.contains("해삼") -> "해삼"
            n.contains("소라") -> "소라"
            n.contains("골뱅이") || n.contains("고둥") || n.contains("고동") -> "골뱅이"
            n.contains("맛") && n.contains("조개") -> "맛조개"
            n.contains("키조개") -> "키조개"
            n.contains("조개") || n.contains("바지락") || n.contains("가리비") || n.contains("홍합") || n.contains("굴") -> "조개"
            n.contains("박하지") || n.contains("민꽃게") || n.contains("돌게") || n.contains("칠게") || n.contains("방게") ||
                n.contains("농게") || n.contains("달랑게") || n.contains("깨다시") -> "작은게"
            n.endsWith("게") || n.contains("꽃게") || n.contains("대게") -> "꽃게"
            n.contains("광어") || n.contains("도다리") || n.contains("가자미") || n.contains("넙치") -> "광어"
            n.contains("우럭") || n.contains("쏨뱅이") -> "우럭"
            n.contains("돔") -> if (n.contains("감성")) "감성돔" else "참돔"
            n.contains("볼락") -> "볼락"
            n.contains("노래미") -> "노래미"
            n.contains("농어") -> "농어"
            n.contains("꽁치") -> "학꽁치"
            else -> ""
        }
    }
}
