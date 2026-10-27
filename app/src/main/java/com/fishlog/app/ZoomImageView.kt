package com.fishlog.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/** 두 손가락으로 확대·이동, 두 번 탭하면 2.5배/원래대로 */
class ZoomImageView(c: Context) : ImageView(c) {
    private val m = Matrix()
    private var base = 1f
    private var cur = 1f

    init {
        scaleType = ScaleType.MATRIX
        setBackgroundColor(Color.BLACK)
    }

    private val scaler = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val f = d.scaleFactor
            val next = (cur * f).coerceIn(base, base * 6f)
            val real = next / cur
            cur = next
            m.postScale(real, real, d.focusX, d.focusY)
            clamp(); imageMatrix = m
            return true
        }
    })

    private val gestures = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            m.postTranslate(-dx, -dy); clamp(); imageMatrix = m
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (cur > base * 1.1f) fit() else {
                val f = 2.5f
                cur = base * f
                m.postScale(f, f, e.x, e.y); clamp(); imageMatrix = m
            }
            return true
        }
    })

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        post { fit() }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        fit()
    }

    private fun fit() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return
        val s = minOf(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        base = s; cur = s
        m.reset()
        m.postScale(s, s)
        m.postTranslate((width - d.intrinsicWidth * s) / 2, (height - d.intrinsicHeight * s) / 2)
        imageMatrix = m
    }

    private fun clamp() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(r)
        var dx = 0f; var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2 - r.left
        else if (r.left > 0) dx = -r.left else if (r.right < width) dx = width - r.right
        if (r.height() <= height) dy = (height - r.height()) / 2 - r.top
        else if (r.top > 0) dy = -r.top else if (r.bottom < height) dy = height - r.bottom
        m.postTranslate(dx, dy)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (!scaler.isInProgress) gestures.onTouchEvent(e)
        return true
    }

    companion object {
        /** 화면 가득 사진 보기. 반환값으로 나중에 큰 사진으로 바꿔 끼울 수 있음 */
        fun show(a: Activity, first: Bitmap?, caption: String): (Bitmap) -> Unit {
            val dlg = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val frame = FrameLayout(a)
            val iv = ZoomImageView(a)
            if (first != null) iv.setImageBitmap(first)
            frame.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            frame.addView(TextView(a).apply {
                text = caption
                setTextColor(Color.WHITE)
                textSize = 14f
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
                setPadding(Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 16))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            frame.addView(TextView(a).apply {
                text = "닫기"
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER
                minHeight = Ui.dp(a, 44)
                setPadding(Ui.dp(a, 18), 0, Ui.dp(a, 18), 0)
                background = Ui.bg(a, Color.argb(150, 0, 0, 0), 22)
                setOnClickListener { dlg.dismiss() }
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                setMargins(0, Ui.dp(a, 16), Ui.dp(a, 16), 0)
            })
            dlg.setContentView(frame)
            dlg.show()
            return { bmp -> if (dlg.isShowing) iv.setImageBitmap(bmp) }
        }
    }
}
