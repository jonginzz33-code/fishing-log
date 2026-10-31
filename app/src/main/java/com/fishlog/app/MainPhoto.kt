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
 *  - 크게 볼 사진은 photosBig/main (그룹), photos/main (전체 게시판) 에 1080px
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
                Store.setMainPhoto(tripId, p.file)
                Store.find(tripId)?.let { push(it) }
                done()
            }
        }.start()
        return true
    }

    /** 작은 대표 사진 (목록용) base64, 없으면 null */
    fun thumbB64(t: Store.Trip): String? {
        val p = t.main() ?: return null
        val b = Photos.smallUpload(Cloud.appContext, p) ?: return null
        return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
    }

    /** 대표 사진이 바뀌면: 올린 그룹들 · 전체 게시판에도 바로 반영 */
    fun push(t: Store.Trip) {
        val me = Cloud.uid ?: return
        val db = FirebaseFirestore.getInstance()
        Thread {
            val small = thumbB64(t)
            val big = t.main()?.let { Photos.bigUpload(Cloud.appContext, it) }?.takeIf { it.size < 720_000 }
                ?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
            val refs = Store.sharedGroups(t.id).map { gid -> db.collection("groups").document(gid).collection("trips").document("${me}_${t.id}") to "photosBig" } +
                (if (t.pub) listOf(db.collection("public").document(PublicBoard.docIdOf(me, t.id)) to "photos") else emptyList())
            refs.forEach { (ref, sub) ->
                ref.update("mthumb", small ?: FieldValue.delete())
                if (big != null) ref.collection(sub).document("main").set(mapOf("owner" to me, "data" to big, "t" to System.currentTimeMillis(), "main" to true))
                else ref.collection(sub).document("main").delete()
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
