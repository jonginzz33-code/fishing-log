package com.fishlog.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import java.io.ByteArrayOutputStream

/**
 * v6.25 (274): 📷 채팅 · 1:1 문의 사진
 *  - 사진을 앱이 줄여서 JPEG 로 다시 만듦 → 위치(GPS) 등 사진 정보(EXIF)는 안 따라감
 *  - 서버는 Firestore 에 글자(base64)로 (따로 사진 저장소 안 씀)
 *  - 하루 보낼 수 있는 장수 제한 (폰 기준)
 */
object ChatPhoto {
    const val DAY_LIMIT = 20

    fun pickIntent(): Intent = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)

    private fun prefs(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)
    private fun today() = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.KOREA).format(java.util.Date())

    /** 오늘 더 보낼 수 있나 */
    fun canSend(c: Context): Boolean {
        val p = prefs(c)
        return p.getString("chatImgDay", "") != today() || p.getInt("chatImgN", 0) < DAY_LIMIT
    }

    fun countSent(c: Context) {
        val p = prefs(c); val d = today()
        val n = if (p.getString("chatImgDay", "") == d) p.getInt("chatImgN", 0) else 0
        p.edit().putString("chatImgDay", d).putInt("chatImgN", n + 1).apply()
    }

    /** 사진 읽어서 긴 변 maxSide 로 줄이기 (사진 방향 맞춤) */
    fun load(c: Context, uri: Uri, maxSide: Int): Bitmap? = try {
        val cr = c.contentResolver
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val raw = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw IllegalStateException("decode")
        val rot = try {
            cr.openInputStream(uri)?.use {
                when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) { 0f }
        val scale = minOf(1f, maxSide.toFloat() / maxOf(raw.width, raw.height))
        val m = Matrix().apply { postScale(scale, scale); if (rot != 0f) postRotate(rot) }
        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    } catch (_: Exception) { null }

    /** JPEG → base64 (maxBytes 안으로 화질 낮춰 가며) */
    fun b64(b: Bitmap, maxBytes: Int, q0: Int = 75): String {
        var q = q0
        while (true) {
            val out = ByteArrayOutputStream()
            b.compress(Bitmap.CompressFormat.JPEG, q, out)
            if (out.size() <= maxBytes || q <= 30) return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            q -= 10
        }
    }

    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun bmp(s: String?): Bitmap? {
        if (s.isNullOrBlank()) return null
        val k = s.length.toString() + ":" + s.hashCode()
        cache.get(k)?.let { return it }
        return try {
            val by = Base64.decode(s, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(by, 0, by.size)?.also { cache.put(k, it) }
        } catch (_: Exception) { null }
    }

    /** 채팅 말풍선 안 미리보기 */
    fun thumbView(a: Activity, s: String?, maxW: Int, onClick: () -> Unit): ImageView? {
        val b = bmp(s) ?: return null
        return ImageView(a).apply {
            setImageBitmap(b); adjustViewBounds = true; maxWidth = maxW; maxHeight = (maxW * 1.3f).toInt()
            scaleType = ImageView.ScaleType.FIT_CENTER
            clipToOutline = true; background = Ui.bg(a, Color.parseColor("#0E1C28"), 12)
            setOnClickListener { onClick() }
        }
    }

    /** v6.49: 넘겨 보기용 사진 하나 (미리보기 · 설명 · 큰 사진 받기) */
    class Pic(val th: String, val label: String, val loadBig: ((String?) -> Unit) -> Unit)

    /**
     * v6.49: 크게 보기 + 옆으로 밀어 넘기기
     *  왼쪽으로 밀기 = 다음(아래 · 나중) 사진 · 오른쪽으로 밀기 = 이전(위 · 먼저) 사진 · 살짝 누르면 닫힘
     *  위에 'N / 전체' · 보낸 사람 · 시각 · 큰 사진은 볼 때 받아서 기억
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun showGallery(a: Activity, pics: List<Pic>, start: Int) {
        if (pics.isEmpty()) return
        val dlg = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val big = HashMap<Int, Bitmap>()
        var idx = start.coerceIn(0, pics.size - 1)
        val img = ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val root = FrameLayout(a).apply { setBackgroundColor(Color.BLACK) }
        root.addView(img, FrameLayout.LayoutParams(-1, -1))
        val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(a, 16), Ui.dp(a, 10), Ui.dp(a, 6), Ui.dp(a, 10)); setBackgroundColor(Color.argb(110, 0, 0, 0)) }
        val info = Ui.text(a, 13f, Color.WHITE, true, "")
        top.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(Ui.text(a, 13f, Color.WHITE, true, "✕ 닫기").apply { setPadding(Ui.dp(a, 12), Ui.dp(a, 4), Ui.dp(a, 12), Ui.dp(a, 4)); setOnClickListener { dlg.dismiss() } })
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        val hint = Ui.text(a, 12f, Color.parseColor("#B0C4D0"), false, "← 다음 사진 · 이전 사진 →").apply {
            gravity = Gravity.CENTER; setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 14)) }
        root.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        fun show(i: Int) {
            idx = i
            val p = pics[i]
            info.text = "${i + 1} / ${pics.size}  ·  ${p.label}"
            img.setImageBitmap(big[i] ?: bmp(p.th))
            if (big[i] == null) p.loadBig { s -> bmp(s)?.let { b -> big[i] = b; if (dlg.isShowing && idx == i) img.setImageBitmap(b) } }
            // 옆 사진 미리 받아 두기
            listOf(i - 1, i + 1).filter { it in pics.indices && big[it] == null }.forEach { j -> pics[j].loadBig { s -> bmp(s)?.let { big[j] = it } } }
        }
        fun slide(dir: Int) {
            val n = idx + dir
            if (n !in pics.indices) {
                Ui.toast(a, if (dir > 0) "마지막 사진이에요" else "첫 사진이에요")
                img.animate().translationX(0f).setDuration(150).start(); return
            }
            val w = root.width.toFloat().coerceAtLeast(1f)
            img.animate().translationX(-dir * w).setDuration(150).withEndAction {
                show(n); img.translationX = dir * w
                img.animate().translationX(0f).setDuration(170).start()
            }.start()
        }
        var downX = 0f; var downY = 0f; var moved = false
        root.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - downX
                    if (Math.abs(dx) > Ui.dp(a, 8)) { moved = true; img.translationX = dx }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = e.x - downX
                    when {
                        dx < -Ui.dp(a, 60) -> slide(1)
                        dx > Ui.dp(a, 60) -> slide(-1)
                        !moved && Math.abs(e.y - downY) < Ui.dp(a, 10) -> dlg.dismiss()
                        else -> img.animate().translationX(0f).setDuration(150).start()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { img.animate().translationX(0f).setDuration(150).start(); true }
                else -> true
            }
        }
        dlg.setContentView(root)
        Ui.edge(dlg.window)
        dlg.show()
        show(idx)
    }

    /** 크게 보기 (화면 꽉 · 누르면 닫힘). big 이 늦게 오면 그때 바꿔 끼움 */
    fun showFull(a: Activity, first: String?, loadBig: ((String?) -> Unit) -> Unit = { it(null) }) {
        val dlg = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val img = ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_CENTER; bmp(first)?.let { setImageBitmap(it) } }
        val root = FrameLayout(a).apply { setBackgroundColor(Color.BLACK); addView(img, FrameLayout.LayoutParams(-1, -1)); setOnClickListener { dlg.dismiss() } }
        root.addView(Ui.text(a, 13f, Color.WHITE, true, "✕ 닫기").apply { setPadding(Ui.dp(a, 16), Ui.dp(a, 10), Ui.dp(a, 16), Ui.dp(a, 10)) },
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        dlg.setContentView(root)
        Ui.edge(dlg.window)
        dlg.show()
        loadBig { big -> if (dlg.isShowing) bmp(big)?.let { img.setImageBitmap(it) } }
    }
}
