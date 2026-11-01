package com.fishlog.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import kotlin.math.cos
import kotlin.math.sin

/**
 * 메인 물때 카드 배경: 카드 색 위에 지금 날씨 그림을 흐리게 (☀ 해 · ⛅ 해+구름 · ☁ 구름 · 🌧 비 · 🌨 눈 · ⚡ 천둥)
 *  - 낮 · 밤 구분 없음 · 카드 높이는 그대로 (배경만 바뀜)
 */
object WxArt {
    const val NONE = 0; const val SUN = 1; const val PARTLY = 2; const val CLOUD = 3; const val RAIN = 4; const val SNOW = 5; const val THUNDER = 6

    /** 예보 한 시간 → 그림 종류 (바람 그림은 쓰지 않고 하늘 · 비 기준) */
    fun kindOf(h: Weather.Hour?): Int {
        if (h == null) return NONE
        val wc = h.wcode
        return when {
            h.thunder || (wc != null && wc >= 95) -> THUNDER
            h.pty != null && h.pty > 0 -> if (h.pty == 3 || h.pty == 7) SNOW else RAIN
            h.sky == 1 -> SUN; h.sky == 3 -> PARTLY; h.sky == 4 -> CLOUD
            wc != null -> when (wc) {
                0, 1 -> SUN; 2 -> PARTLY; 3, 45, 48 -> CLOUD
                in 71..77, 85, 86 -> SNOW
                else -> if (wc >= 51) RAIN else PARTLY
            }
            else -> NONE
        }
    }

    class CardBg(private val c: Context, private val base: Int, private val radiusDp: Int, var kind: Int) : Drawable() {
        private val d = c.resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clip = Path()
        private val rect = RectF()

        override fun draw(cv: Canvas) {
            val b = bounds
            rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            val r = radiusDp * d
            p.style = Paint.Style.FILL; p.color = base; p.pathEffect = null
            cv.drawRoundRect(rect, r, r, p)
            if (kind == NONE) return
            clip.reset(); clip.addRoundRect(rect, r, r, Path.Direction.CW)
            cv.save(); cv.clipPath(clip)
            val h = rect.height()
            // 그림 자리: 가운데 오른쪽 (글씨 · '물때 보기' 버튼과 겹쳐도 흐리게)
            val cx = rect.left + rect.width() * 0.66f
            val cy = rect.top + h * 0.48f
            val u = h / 2.4f
            when (kind) {
                SUN -> sun(cv, cx, cy, u * 1.05f, 70)
                PARTLY -> { sun(cv, cx + u * 0.55f, cy - u * 0.35f, u * 0.75f, 60); cloud(cv, cx - u * 0.1f, cy + u * 0.25f, u, Color.argb(70, 220, 232, 242)) }
                CLOUD -> { cloud(cv, cx + u * 0.6f, cy - u * 0.25f, u * 0.75f, Color.argb(45, 200, 214, 228)); cloud(cv, cx - u * 0.2f, cy + u * 0.15f, u, Color.argb(70, 210, 222, 236)) }
                RAIN -> { cloud(cv, cx, cy - u * 0.25f, u, Color.argb(75, 170, 190, 214)); rain(cv, cx, cy + u * 0.35f, u) }
                SNOW -> { cloud(cv, cx, cy - u * 0.25f, u, Color.argb(75, 214, 226, 240)); snow(cv, cx, cy + u * 0.45f, u) }
                THUNDER -> { cloud(cv, cx, cy - u * 0.3f, u, Color.argb(80, 150, 140, 190)); bolt(cv, cx, cy + u * 0.15f, u); rain(cv, cx + u * 0.15f, cy + u * 0.35f, u * 0.8f) }
            }
            cv.restore()
        }

        private fun sun(cv: Canvas, x: Float, y: Float, s: Float, alpha: Int) {
            p.style = Paint.Style.FILL
            p.color = Color.argb(alpha / 2, 242, 193, 78); cv.drawCircle(x, y, s * 0.85f, p)
            p.color = Color.argb(alpha, 242, 193, 78); cv.drawCircle(x, y, s * 0.55f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.09f; p.strokeCap = Paint.Cap.ROUND
            for (i in 0 until 10) {
                val ang = Math.toRadians(i * 36.0)
                val r1 = s * 0.98f; val r2 = s * 1.3f
                cv.drawLine(x + (cos(ang) * r1).toFloat(), y + (sin(ang) * r1).toFloat(), x + (cos(ang) * r2).toFloat(), y + (sin(ang) * r2).toFloat(), p)
            }
        }

        private fun cloud(cv: Canvas, x: Float, y: Float, s: Float, col: Int) {
            p.style = Paint.Style.FILL; p.color = col
            cv.drawCircle(x - s * 0.55f, y + s * 0.1f, s * 0.42f, p)
            cv.drawCircle(x, y - s * 0.15f, s * 0.6f, p)
            cv.drawCircle(x + s * 0.6f, y + s * 0.08f, s * 0.45f, p)
            cv.drawRoundRect(RectF(x - s * 0.95f, y + s * 0.05f, x + s * 1.05f, y + s * 0.5f), s * 0.22f, s * 0.22f, p)
        }

        private fun rain(cv: Canvas, x: Float, y: Float, s: Float) {
            p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.09f; p.strokeCap = Paint.Cap.ROUND
            p.color = Color.argb(85, 120, 190, 255)
            for (i in -2..2) {
                val sx = x + i * s * 0.36f; val sy = y + (if (i % 2 == 0) 0f else s * 0.18f)
                cv.drawLine(sx, sy, sx - s * 0.14f, sy + s * 0.42f, p)
            }
        }

        private fun snow(cv: Canvas, x: Float, y: Float, s: Float) {
            p.style = Paint.Style.FILL; p.color = Color.argb(95, 240, 246, 255)
            for (i in -2..2) {
                val sx = x + i * s * 0.36f; val sy = y + (if (i % 2 == 0) 0f else s * 0.22f)
                cv.drawCircle(sx, sy, s * 0.09f, p)
            }
        }

        private fun bolt(cv: Canvas, x: Float, y: Float, s: Float) {
            p.style = Paint.Style.FILL; p.color = Color.argb(110, 255, 214, 74)
            val path = Path()
            path.moveTo(x + s * 0.05f, y - s * 0.35f); path.lineTo(x - s * 0.28f, y + s * 0.18f); path.lineTo(x - s * 0.02f, y + s * 0.18f)
            path.lineTo(x - s * 0.15f, y + s * 0.7f); path.lineTo(x + s * 0.3f, y + s * 0.02f); path.lineTo(x + s * 0.02f, y + s * 0.02f); path.close()
            cv.drawPath(path, p)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** '출조 시작' 앞 작은 분홍 동그라미 ▶ */
    class PlayIcon(c: Context, sizeDp: Int) : Drawable() {
        private val px = (sizeDp * c.resources.displayMetrics.density).toInt()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        init { setBounds(0, 0, px, px) }
        override fun draw(cv: Canvas) {
            val b = bounds
            val r = b.width() / 2f
            val cx = b.left + r; val cy = b.top + b.height() / 2f
            p.style = Paint.Style.FILL; p.color = Color.parseColor("#FF8FB8"); cv.drawCircle(cx, cy, r, p)
            p.color = Color.parseColor("#FFC7DA"); cv.drawCircle(cx - r * 0.35f, cy - r * 0.38f, r * 0.16f, p)   // 반짝
            p.color = Color.WHITE
            val t = Path()
            t.moveTo(cx - r * 0.28f, cy - r * 0.42f); t.lineTo(cx + r * 0.48f, cy); t.lineTo(cx - r * 0.28f, cy + r * 0.42f); t.close()
            cv.drawPath(t, p)
        }
        override fun getIntrinsicWidth() = px
        override fun getIntrinsicHeight() = px
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** 이중 테두리 (바깥 주황 · 안쪽 하늘) */
    fun doubleBorder(c: Context, fill: Int): Drawable {
        val d = c.resources.displayMetrics.density
        val outer = GradientDrawable().apply { setColor(fill); cornerRadius = 14 * d; setStroke((2.5f * d).toInt(), Ui.ORANGE) }
        val inner = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = 11 * d; setStroke((2 * d).toInt(), Ui.BLUE) }
        return LayerDrawable(arrayOf(outer, inner)).apply {
            val ins = (3.5f * d).toInt()
            setLayerInset(1, ins, ins, ins, ins)
        }
    }
}
