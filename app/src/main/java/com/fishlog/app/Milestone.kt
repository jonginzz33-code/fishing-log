package com.fishlog.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import kotlin.random.Random

/**
 * 돌파 이스터에그: 어종마다 10/30/50/70/100/120/150/200/250/300 마리를 넘는 순간
 *  - 칭호 · 카드 테두리 · 색종이 · 특별 진동 · 출조 도장
 */
object Milestone {

    val STEPS = listOf(10, 30, 50, 70, 100, 120, 150, 200, 250, 300)
    private val TITLES = listOf("첫 손맛", "손맛 봤다", "오늘 좀 되네", "물 만났다", "백마리 클럽",
        "장인", "명인", "전설", "살아있는 전설", "바다의 신")
    private val BORDERS = listOf("#9ADBFF", "#C88A5A", "#D7DEE4", "#F5D98A", "#F2C14E", "#F2C14E", "#E0A526")
    private val RAINBOW = intArrayOf(Color.parseColor("#F07AA0"), Color.parseColor("#F2C14E"), Color.parseColor("#5FD39A"),
        Color.parseColor("#5BC8DF"), Color.parseColor("#B9A6FF"))

    /** 마릿수 → 단계 (0~9), 10마리 미만이면 -1 */
    fun tier(count: Int): Int = STEPS.indexOfLast { count >= it }

    fun title(tier: Int): String = TITLES.getOrNull(tier) ?: ""
    /** "♛ 장인" (120마리 이상은 왕관) */
    fun titleWithCrown(tier: Int): String = if (tier < 0) "" else (if (tier >= 5) "♛ " else "") + title(tier)
    /** 출조 도장: "주꾸미 장인 120" */
    fun stamp(species: String, count: Int): String? {
        val t = tier(count); if (t < 0) return null
        return "$species ${title(t)} ${STEPS[t]}"
    }
    fun tierColor(tier: Int): Int = when {
        tier < 0 -> Color.TRANSPARENT
        tier < BORDERS.size -> Color.parseColor(BORDERS[tier])
        else -> RAINBOW[0]
    }

    /** 카드 바탕 + 단계 테두리 (200마리부터 무지개) */
    fun cardBackground(c: Context, color: Int, tier: Int): Drawable {
        val d = c.resources.displayMetrics.density
        val r = 22 * d
        if (tier < 0) return GradientDrawable().apply { setColor(color); cornerRadius = r }
        val w = ((if (tier >= 5) 5 else if (tier >= 3) 4 else 3) * d).toInt()
        val outer = if (tier >= 7) GradientDrawable(GradientDrawable.Orientation.TL_BR, RAINBOW).apply { cornerRadius = r }
        else GradientDrawable().apply { setColor(tierColor(tier)); cornerRadius = r }
        val inner = GradientDrawable().apply { setColor(color); cornerRadius = r - w }
        return LayerDrawable(arrayOf(outer, inner)).apply { setLayerInset(1, w, w, w, w) }
    }

    // ---------------- 돌파 순간 ----------------
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(Int, Int, Int) -> Unit>()
    fun addListener(l: (Int, Int, Int) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Int, Int, Int) -> Unit) { listeners.remove(l) }

    /** 마릿수가 늘 때마다 (Store.addCatch) — 딱 기준 마릿수가 된 순간만 */
    fun onCount(slot: Int, count: Int) {
        val i = STEPS.indexOf(count)
        if (i < 0) return
        listeners.toList().forEach { it(slot, count, i) }
        if (Cloud.isInitialized()) main.postDelayed({ vibrate(Cloud.appContext, i) }, 350L)
    }

    /** 특별 진동: 단계가 높을수록 길고 화려하게 (화면이 꺼져 있어도 느낄 수 있게) */
    fun vibrate(c: Context, tier: Int) {
        val pattern = when {
            tier >= 7 -> longArrayOf(0, 120, 80, 120, 80, 120, 200, 500, 150, 500)
            tier >= 4 -> longArrayOf(0, 150, 90, 150, 90, 400)
            else -> longArrayOf(0, 90, 70, 90, 70, 250)
        }
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {}
    }
}

/** 색종이: start() 하면 2.5초 동안 떨어지고 사라짐 */
class ConfettiView(c: Context) : View(c) {
    private class P(var x: Float, var y: Float, val vx: Float, val vy: Float, var rot: Float, val vr: Float, val w: Float, val color: Int)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var ps: List<P> = emptyList()
    private var anim: ValueAnimator? = null
    private val colors = intArrayOf(Color.parseColor("#F2C14E"), Color.parseColor("#5FD39A"), Color.parseColor("#F07AA0"),
        Color.parseColor("#5BC8DF"), Color.WHITE, Color.parseColor("#B9A6FF"))

    init { isClickable = false; visibility = GONE }

    fun start(amount: Int) {
        val d = resources.displayMetrics.density
        val w = width.toFloat().coerceAtLeast(100f); val h = height.toFloat().coerceAtLeast(100f)
        ps = (0 until amount).map {
            P(Random.nextFloat() * w, -Random.nextFloat() * h * 0.4f, (Random.nextFloat() - 0.5f) * 0.25f * d,
                (0.25f + Random.nextFloat() * 0.35f) * d, Random.nextFloat() * 360f, (Random.nextFloat() - 0.5f) * 0.6f,
                (3 + Random.nextFloat() * 4) * d, colors[it % colors.size])
        }
        visibility = VISIBLE
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2500
            var last = 0L
            addUpdateListener { va ->
                val now = va.currentPlayTime
                val dt = (now - last).coerceIn(0, 50).toFloat(); last = now
                ps.forEach { p -> p.x += p.vx * dt; p.y += p.vy * dt; p.rot += p.vr * dt }
                alpha = if (va.animatedFraction > 0.75f) (1 - va.animatedFraction) * 4 else 1f
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { visibility = GONE; alpha = 1f }
            })
            start()
        }
    }

    override fun onDraw(cv: Canvas) {
        ps.forEach { p ->
            paint.color = p.color
            cv.save(); cv.rotate(p.rot, p.x, p.y)
            cv.drawRect(p.x, p.y, p.x + p.w, p.y + p.w * 1.6f, paint)
            cv.restore()
        }
    }
}

/** 출조 도장 줄: "🏅 주꾸미 장인 120" (금색 칩) */
fun stampRow(c: android.content.Context, names: List<String>, counts: List<Int>): android.view.View? {
    val list = names.indices.mapNotNull { i -> Milestone.stamp(names[i], counts[i])?.let { it to Milestone.tier(counts[i]) } }
    if (list.isEmpty()) return null
    val row = android.widget.LinearLayout(c).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
    list.forEach { (txt, tier) ->
        row.addView(Ui.chip(c, "🏅 $txt", if (tier >= 7) android.graphics.Color.parseColor("#F07AA0") else Milestone.tierColor(tier)),
            android.widget.LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { rightMargin = Ui.dp(c, 6) })
    }
    return android.widget.HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(row) }
}
