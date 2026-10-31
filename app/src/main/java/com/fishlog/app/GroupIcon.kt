package com.fishlog.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * 그룹 대표 이미지 (동그라미). 이미지를 안 올린 그룹은 기본 그림(🚤 등).
 *  - 폰에 저장해 두고 바로 보여 준 뒤, 서버에서 새로 받아 바꿈
 */
object GroupIcon {
    private val mem = HashMap<String, Bitmap?>()
    private fun prefs() = Cloud.appContext.getSharedPreferences("group_img", Context.MODE_PRIVATE)

    private fun decode(b64: String?): Bitmap? = try {
        if (b64.isNullOrBlank()) null else {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    } catch (_: Exception) { null }

    fun cached(gid: String): Bitmap? {
        if (mem.containsKey(gid)) return mem[gid]
        val b = decode(prefs().getString(gid, null)); mem[gid] = b; return b
    }

    /** 대표 이미지를 바꾸거나 지웠을 때 */
    fun put(gid: String, b64: String?) {
        mem[gid] = decode(b64)
        prefs().edit().apply { if (b64 == null) remove(gid) else putString(gid, b64) }.apply()
    }

    /** 동그랗게 자른 비트맵 */
    fun circle(b: Bitmap, px: Int): Bitmap {
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(out)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        cv.drawCircle(px / 2f, px / 2f, px / 2f, p)
        p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        val side = minOf(b.width, b.height)
        val src = android.graphics.Rect((b.width - side) / 2, (b.height - side) / 2, (b.width + side) / 2, (b.height + side) / 2)
        cv.drawBitmap(b, src, android.graphics.Rect(0, 0, px, px), p)
        return out
    }

    /** 글자 앞에 붙이는 작은 그룹 그림 (이미지 없으면 기본 그림 글자) */
    fun prefix(c: Context, g: Cloud.Group, sizeDp: Int, fallback: String = "🚤"): CharSequence {
        val b = cached(g.id) ?: return "$fallback "
        val px = Ui.dp(c, sizeDp)
        val d = android.graphics.drawable.BitmapDrawable(c.resources, circle(b, px)).apply { setBounds(0, 0, px, px) }
        return android.text.SpannableStringBuilder("\u00A0 ").apply {
            setSpan(android.text.style.ImageSpan(d, android.text.style.DynamicDrawableSpan.ALIGN_BOTTOM), 0, 1, 0)
        }
    }

    /** 동그란 그룹 아이콘. ring = 테두리 색 (null 이면 없음) */
    fun view(c: Context, g: Cloud.Group, sizeDp: Int, fallback: String = "🚤", ring: Int? = Color.WHITE): View {
        val px = Ui.dp(c, sizeDp)
        val f = FrameLayout(c)
        val emoji = TextView(c).apply {
            text = fallback; gravity = Gravity.CENTER; textSize = sizeDp * 0.42f
            background = Ui.bg(c, Color.parseColor("#13283A"), sizeDp, Color.parseColor("#2F4258"))
        }
        f.addView(emoji, FrameLayout.LayoutParams(px, px))
        val img = ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setOval(0, 0, v.width, v.height) }
            }
            clipToOutline = true
            if (ring != null) {
                val pad = Ui.dp(c, if (sizeDp >= 40) 2 else 1)
                setPadding(pad, pad, pad, pad)
                background = Ui.bg(c, ring, sizeDp)
            }
            visibility = View.GONE
        }
        f.addView(img, FrameLayout.LayoutParams(px, px))
        fun show(b: Bitmap?) {
            if (b == null) { img.visibility = View.GONE; emoji.visibility = View.VISIBLE }
            else { img.setImageBitmap(b); img.visibility = View.VISIBLE; emoji.visibility = View.INVISIBLE }
        }
        show(cached(g.id))
        Cloud.groupImage(g.id) { b64 ->
            val old = prefs().getString(g.id, null)
            if (b64 != old) put(g.id, b64)
            show(cached(g.id))
        }
        return f
    }
}
