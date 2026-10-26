package com.fishlog.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max

/** 사진 가져오기 · 촬영 시각 읽기 · 조과와 자동 매칭 */
object Photos {

    private const val MATCH_WINDOW = 10 * 60_000L

    fun dir(ctx: Context): File = File(ctx.filesDir, "photos").apply { mkdirs() }
    fun file(ctx: Context, name: String): File = File(dir(ctx), name)
    fun thumbName(name: String): String = name.removeSuffix(".jpg") + "_t.jpg"

    /** 사진 촬영 시각 (EXIF). 없으면 0 */
    private fun takenTime(ctx: Context, uri: Uri): Pair<Long, Int> {
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                val offset = exif.getAttribute("OffsetTimeOriginal")
                if (raw != null) {
                    val f = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                    f.timeZone = if (offset != null) TimeZone.getTimeZone("GMT$offset") else TimeZone.getDefault()
                    val t = f.parse(raw.trim())?.time ?: 0L
                    return t to orientation
                }
                return 0L to orientation
            }
        } catch (_: Exception) {}
        return 0L to ExifInterface.ORIENTATION_NORMAL
    }

    private fun decode(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun scaleRotate(src: Bitmap, maxSide: Int, orientation: Int): Bitmap {
        val m = Matrix()
        val s = maxSide.toFloat() / max(src.width, src.height)
        if (s < 1f) m.postScale(s, s)
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun saveJpeg(b: Bitmap, f: File, quality: Int) {
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }

    /** 사진 한 장을 앱 폴더로 복사 (큰 사진 1600px + 미리보기 360px). 실패하면 null */
    fun import(ctx: Context, uri: Uri, tripId: Long, index: Int): Store.Photo? {
        return try {
            val (taken, orientation) = takenTime(ctx, uri)
            val src = decode(ctx, uri, 1600) ?: return null
            val name = "${tripId}_${System.currentTimeMillis()}_$index.jpg"
            val big = scaleRotate(src, 1600, orientation)
            saveJpeg(big, file(ctx, name), 85)
            val thumb = scaleRotate(src, 360, orientation)
            saveJpeg(thumb, file(ctx, thumbName(name)), 60)
            Store.Photo(taken, name, null)
        } catch (_: Exception) {
            null
        }
    }

    /** 촬영 시각과 가장 가까운 조과(앞뒤 10분)에 붙이기. 이미 사진이 붙은 조과는 피함 */
    fun autoMatch(t: Store.Trip, photos: List<Store.Photo>) {
        val used = t.photos.mapNotNull { it.catchTime }.toMutableSet()
        photos.sortedBy { it.time }.forEach { p ->
            if (p.time <= 0L) return@forEach
            val best = t.catches
                .filter { abs(it.time - p.time) <= MATCH_WINDOW && it.time !in used }
                .minByOrNull { abs(it.time - p.time) }
            if (best != null) { p.catchTime = best.time; used.add(best.time) }
        }
    }

    fun thumbBitmap(ctx: Context, p: Store.Photo): Bitmap? =
        try { BitmapFactory.decodeFile(file(ctx, thumbName(p.file)).absolutePath) } catch (_: Exception) { null }
}
