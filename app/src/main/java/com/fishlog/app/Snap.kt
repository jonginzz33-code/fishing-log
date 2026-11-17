package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v6.32 (287): 📷 화면 통째로 찍기 — 스크롤해야 보이는 아래까지 한 장의 긴 사진
 *  - 위에 [heads](제목 줄 등) → [body](스크롤 안 전체) → 맨 아래 작은 글 [footer]
 *  - 갤러리 '사진/조과일지' 폴더에 저장 → '📤 보내기'(카톡 · 문자 등)
 */
object Snap {
    fun capture(a: Activity, heads: List<View>, body: View, footer: String, fileTag: String) {
        try {
            val w = body.width.takeIf { it > 0 } ?: return toast(a, "화면이 아직 준비 안 됐어요 · 잠시 뒤 다시 눌러 주세요")
            val d = a.resources.displayMetrics.density
            val pad = (16 * d).toInt()
            val headH = heads.sumOf { it.height }
            val footH = (34 * d).toInt()
            var total = pad + headH + pad / 2 + body.height + footH
            // 너무 길면(메모리) 줄여서
            val scale = if (total > 14000) 14000f / total else 1f
            val bmp = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (total * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            cv.scale(scale, scale)
            cv.drawColor(Ui.BG)
            var y = pad.toFloat()
            heads.forEach { v ->
                cv.save(); cv.translate(pad.toFloat(), y); v.draw(cv); cv.restore()
                y += v.height
            }
            y += pad / 2f
            cv.save(); cv.translate(0f, y); body.draw(cv); cv.restore()
            y += body.height
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.SUB; textSize = 12 * d; textAlign = Paint.Align.CENTER }
            cv.drawText(footer, w / 2f, y + footH * 0.6f, p)
            save(a, bmp, fileTag)
        } catch (e: OutOfMemoryError) {
            toast(a, "사진이 너무 커서 못 찍었어요")
        } catch (e: Exception) {
            toast(a, "찍기 실패: ${e.message}")
        }
    }

    private fun save(a: Activity, bmp: Bitmap, tag: String) {
        val name = "조과일지-$tag-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.KOREA).format(Date()) + ".jpg"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/조과일지")
        }
        val uri: Uri = a.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return toast(a, "갤러리에 저장하지 못했어요")
        a.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bmp.recycle()
        AlertDialog.Builder(a, R.style.FlAlert)
            .setTitle("📷 찍었어요")
            .setMessage("갤러리 '조과일지' 폴더에 저장했어요.\n카톡 · 문자로 바로 보낼까요?")
            .setPositiveButton("📤 보내기") { _, _ ->
                a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "물때 사진 보내기"))
            }
            .setNegativeButton("닫기", null).show()
    }

    private fun toast(a: Activity, s: String) = Toast.makeText(a, s, Toast.LENGTH_LONG).show()
}
