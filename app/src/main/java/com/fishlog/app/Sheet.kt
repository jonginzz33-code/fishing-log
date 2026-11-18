package com.fishlog.app

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import kotlin.math.abs

/**
 * v6.28 (278 · 280): 밑에서 올라오는 창 (바텀 시트)
 *  - 처음엔 화면의 [frac] 만큼 · 위 손잡이 · 제목 줄을 위로 끌면 화면 가득 · 아래로 내리거나 바깥(흐린 곳)을 누르면 닫힘
 *  - head = 손잡이 아래 제목 줄(끌 수 있음) · body = 스크롤되는 내용 · foot = 맨 아래 고정 줄(비어 있으면 안 보임)
 */
class Sheet(private val a: Activity, private val frac: Float = 0.66f) {
    private fun dp(v: Int) = Ui.dp(a, v)
    val dlg = Dialog(a, R.style.FlSheet)
    private val root = FrameLayout(a)
    private val panel = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(16), dp(8)) }
    val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(14), dp(16)) }
    val foot = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(6), dp(14), dp(10)); visibility = View.GONE }
    val scroll = ScrollView(a)
    var onClose: (() -> Unit)? = null
    private var closing = false
    private var startH = 0

    init {
        panel.background = GradientDrawable().apply {
            setColor(Color.parseColor("#132B3D"))
            val r = dp(20).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        panel.isClickable = true   // 창 안을 눌러도 닫히지 않게
        val grip = FrameLayout(a)
        grip.addView(View(a).apply { background = Ui.bg(a, Color.parseColor("#5A7A90"), 99) },
            FrameLayout.LayoutParams(dp(44), dp(5), Gravity.CENTER))
        panel.addView(grip, LinearLayout.LayoutParams(MATCH, dp(22)))
        panel.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        scroll.addView(body)
        panel.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        panel.addView(foot, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(panel, FrameLayout.LayoutParams(MATCH, 0, Gravity.BOTTOM))
        root.setOnClickListener { close() }
        drag(grip); drag(head)
        grip.setOnClickListener { toggle() }
        dlg.setContentView(root)
        dlg.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.55f)
        }
        Ui.edge(dlg.window, Color.TRANSPARENT)   // 상태바 · 하단 버튼 여백 · 큰 화면 가운데
        dlg.setOnKeyListener { _, code, ev ->
            if (code == android.view.KeyEvent.KEYCODE_BACK && ev.action == android.view.KeyEvent.ACTION_UP) { close(); true } else false
        }
    }

    private fun fullH() = (root.height - root.paddingTop - root.paddingBottom).coerceAtLeast(dp(200))
    private fun baseH() = (fullH() * frac).toInt()
    private fun setH(h: Int) { panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply { height = h.coerceIn(dp(80), fullH()) } }
    private fun animH(to: Int, end: (() -> Unit)? = null) {
        ValueAnimator.ofInt(panel.height, to).apply {
            duration = 180
            addUpdateListener { setH(it.animatedValue as Int) }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(x: android.animation.Animator) { end?.invoke() } })
            start()
        }
    }
    /** 손잡이 누르기: 반쯤 ↔ 화면 가득 */
    fun toggle() = animH(if (panel.height < fullH() - dp(8)) fullH() else baseH())
    fun full() = animH(fullH())

    @SuppressLint("ClickableViewAccessibility")
    private fun drag(v: View) {
        var y0 = 0f; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { y0 = e.rawY; startH = panel.height; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - y0
                    if (abs(dy) > dp(6)) moved = true
                    if (moved) setH((startH - dy).toInt())
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved) { if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick(); return@setOnTouchListener true }
                    val h = panel.height; val base = baseH()
                    when {
                        h < base * 0.7f -> close()
                        h > (base + fullH()) / 2 -> animH(fullH())
                        else -> animH(base)
                    }
                    true
                }
                else -> false
            }
        }
    }

    fun show() {
        dlg.show()
        panel.visibility = View.INVISIBLE
        root.post {
            setH(baseH())
            panel.post {
                panel.translationY = panel.height.toFloat(); panel.visibility = View.VISIBLE
                panel.animate().translationY(0f).setDuration(220).start()
            }
        }
    }

    fun close() {
        if (closing) return
        closing = true
        panel.animate().translationY(panel.height.toFloat()).setDuration(180).withEndAction {
            try { dlg.dismiss() } catch (_: Exception) {}
            onClose?.invoke()
        }.start()
    }
}
