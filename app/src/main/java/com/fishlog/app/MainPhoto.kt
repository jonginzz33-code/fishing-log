package com.fishlog.app

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File

/**
 * 대표 조과 사진: 출조 완료 창·출조 기록 화면에서 찍기/앨범/이 출조 사진 중 고르기/빼기
 *  - 목록·게시판 카드 오른쪽에 보이는 작은 사진(mthumb, 320px)은 출조 문서 안에 같이 저장 → 목록 볼 때 따로 안 받음
 *  - 크게 볼 사진: 그룹은 사진 목록의 큰 사진(photosBig/{이름})을 그대로 씀 (mainId), 전체 게시판만 photos/main 에 1080px
 *  - 대표 사진으로만 넣은 사진(찍기·앨범)을 다른 사진으로 바꾸면 예전 것은 지움 (사진이 쌓이지 않게)
 */
object MainPhoto {
    const val REQ_CAM = 7711
    const val REQ_ALBUM = 7712
    private var pendingTrip = 0L
    private var camFile: File? = null
    private val main = Handler(Looper.getMainLooper())

    fun camera(a: Activity, tripId: Long) {
        pendingTrip = tripId
        try {
            val dir = File(a.cacheDir, "cam").apply { mkdirs() }
            val f = File(dir, "main_${System.currentTimeMillis()}.jpg")
            camFile = f
            val uri = FileProvider.getUriForFile(a, a.packageName + ".files", f)
            val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            @Suppress("DEPRECATION") a.startActivityForResult(i, REQ_CAM)
        } catch (e: Exception) { Toast.makeText(a, "카메라를 못 열었어요 · 앨범에서 골라 주세요", Toast.LENGTH_LONG).show() }
    }

    fun album(a: Activity, tripId: Long) {
        pendingTrip = tripId
        val i = if (android.os.Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES)
            else Intent(Intent.ACTION_GET_CONTENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }
        try { @Suppress("DEPRECATION") a.startActivityForResult(i, REQ_ALBUM) }
        catch (_: Exception) { Toast.makeText(a, "앨범을 못 열었어요", Toast.LENGTH_LONG).show() }
    }

    /** onActivityResult 에서 부르기. 처리했으면 true. done = 대표 사진이 바뀐 뒤 */
    fun onResult(a: Activity, requestCode: Int, resultCode: Int, data: Intent?, done: () -> Unit): Boolean {
        if (requestCode != REQ_CAM && requestCode != REQ_ALBUM) return false
        if (resultCode != Activity.RESULT_OK) return true
        val tripId = pendingTrip
        val uri: Uri = if (requestCode == REQ_CAM) {
            val f = camFile ?: return true
            if (!f.exists() || f.length() == 0L) return true
            Uri.fromFile(f)
        } else data?.data ?: return true
        Toast.makeText(a, "대표 사진 넣는 중…", Toast.LENGTH_SHORT).show()
        Thread {
            val p = Photos.import(a, uri, tripId, 99)
            camFile?.delete(); camFile = null
            main.post {
                if (p == null) { Toast.makeText(a, "사진을 못 가져왔어요", Toast.LENGTH_LONG).show(); return@post }
                Store.addPhotos(tripId, listOf(p))
                setMain(a, tripId, p.file)
                done()
            }
        }.start()
        return true
    }

    /** 서버 사진 문서 이름 (Cloud.uploadPhotos 와 같게) */
    fun docId(p: Store.Photo) = p.file.removeSuffix(".jpg")

    /** 대표 사진으로만 넣은 사진 (찍기·앨범으로 넣고 조과에 안 붙은 것) */
    private fun mainOnly(p: Store.Photo) = p.file.endsWith("_99.jpg") && p.catchTime == null

    /** 대표 사진 바꾸기 ("" = 빼기). 대표로만 넣었던 예전 사진은 바꿀 때 지움 → 서버·게시판 반영 */
    fun setMain(c: android.content.Context, tripId: Long, file: String) {
        val t = Store.find(tripId) ?: return
        val old = t.main()
        Store.setMainPhoto(tripId, file)
        if (old != null && old.file != file && file.isNotBlank() && mainOnly(old)) {
            Store.removePhoto(tripId, old)
            try { Photos.file(c, old.file).delete() } catch (_: Exception) {}
        }
        Store.find(tripId)?.let { push(it) }
    }

    /** 작은 대표 사진 (목록용) base64, 없으면 null */
    fun thumbB64(t: Store.Trip): String? {
        val p = t.main() ?: return null
        val b = Photos.smallUpload(Cloud.appContext, p) ?: return null
        return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
    }

    /**
     * 대표 사진이 바뀌면: 올린 그룹들 · 전체 게시판에 한 번만 반영
     *  groups = 그룹 글(작은 사진·mainId, 그룹엔 사진 목록도 다시 올림), pub = 전체 게시판(작은 사진·큰 사진 photos/main)
     */
    fun push(t: Store.Trip, groups: Boolean = true, pub: Boolean = true) {
        val me = Cloud.uid ?: return
        val db = FirebaseFirestore.getInstance()
        val main = t.main()
        Thread {
            val small = thumbB64(t)
            if (groups) Store.sharedGroups(t.id).forEach { gid ->
                val ref = db.collection("groups").document(gid).collection("trips").document("${me}_${t.id}")
                ref.update(mapOf("mthumb" to (small ?: FieldValue.delete()),
                    "mainId" to (main?.let { docId(it) } ?: FieldValue.delete()), "photoCount" to t.photos.size))
                // 새로 넣은 대표 사진(큰 사진 포함)·지운 예전 사진을 사진 목록에 반영 (이미 올린 건 건너뜀)
                Cloud.uploadPhotos(ref, t, gid)
                ref.collection("photosBig").document("main").delete()
            }
            if (pub && t.pub) {
                val ref = db.collection("public").document(PublicBoard.docIdOf(me, t.id))
                val big = main?.let { Photos.bigUpload(Cloud.appContext, it) }?.takeIf { it.size < 720_000 }
                    ?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
                ref.update(mapOf("mthumb" to (small ?: FieldValue.delete()), "mainId" to (main?.let { docId(it) } ?: FieldValue.delete())))
                if (big != null) ref.collection("photos").document("main").set(mapOf("owner" to me, "data" to big, "t" to System.currentTimeMillis(), "main" to true))
                else ref.collection("photos").document("main").delete()
            }
            Cloud.invalidateAll(); PublicBoard.clearCache()
        }.start()
    }

    /** 대표 사진 크게 보기 (그룹·게시판 글): 작은 사진 먼저, 큰 사진 받으면 바꿈 */
    fun showBig(a: Activity, path: String, thumb: String, caption: String) {
        val update = ZoomImageView.show(a, decode(thumb), caption)
        FirebaseFirestore.getInstance().document(path).get().addOnSuccessListener { d ->
            d.getString("data")?.let { decode(it) }?.let { update(it) }
        }
    }

    /** 작은 사진 base64 → 동그란 모서리 이미지뷰용 비트맵 */
    fun decode(b64: String): android.graphics.Bitmap? = try {
        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: Exception) { null }
}
