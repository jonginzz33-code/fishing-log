package com.fishlog.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * v6.7 (252): 💾 폰에 백업 · 불러오기 (서버 X)
 *  - 담는 것: 설정 저장소(가방 · 앱 설정 · 리모컨 버튼 · 즐겨찾기 · 차단 목록 등) + 출조 기록 파일 + 사진 원본
 *             + 오프라인 지역(해루질 · 물때 자료) + 지도 그림 저장분
 *  - 빼는 것: 진행 중인 출조, 임시 캐시, 업데이트 · 데이터 양 기록
 *  - 스토어판은 처음 켤 때 같은 폰의 GitHub판(v6.7+)에서 이 백업을 바로 받아 와요 (BackupProvider)
 */
object Backup {
    /** GitHub판이 스토어판에 넘겨주는 주소 (GitHub판 BackupProvider) */
    const val GITHUB_PKG = "com.fishlog.app"
    const val PROVIDER_URI = "content://com.fishlog.app.backup/backup.zip"

    /** 담을 설정 파일 (나머지는 캐시) */
    private val PREF_FILES = listOf("fishlog", "moderation", "my_trash", "gtrip_seen", "area_names")
    /** "fishlog" 안에서 빼는 값 */
    private fun skipKey(k: String) = k == "current" || k == "history" || k == "minVersion" || k == "storeMove" ||
        k.startsWith("dl_") || k == "imported_from_github" || k == "import_asked" ||
        k.startsWith("wtq_") || k.startsWith("marine_") || k.startsWith("sstoff_")
    /** v6.24 (273): 담을 폴더 — 출조 기록은 늘, 사진 · 받아 둔 지도는 고를 수 있음
     *  🦀 해루질 정보(haeru) · 물때/날씨 저장분(khoa · weather)은 서버에서 다시 받으면 돼서 뺌 */
    class Opts(val photos: Boolean = true, val maps: Boolean = true)

    class Result(val prefs: Int, val files: Int)

    /** 항목별 크기 (바이트) — 폰 안 파일만 셈 (인터넷 안 씀) */
    class Sizes(val settings: Long, val trips: Long, val photos: Long, val maps: Long)

    private fun dirSize(f: File): Long = if (!f.exists()) 0L else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun sizes(c: Context): Sizes {
        val app = c.applicationContext
        return Sizes(prefsJson(app).toString().toByteArray().size.toLong(), dirSize(File(app.filesDir, "trips")),
            dirSize(File(app.filesDir, "photos")), dirSize(File(app.filesDir, "osmdroid")) + dirSize(File(app.cacheDir, "osmdroid-tiles")))
    }

    /** 설정 저장소 전체를 JSON 하나로 { 파일이름: { 키: {t, v} } } */
    fun prefsJson(c: Context): JSONObject {
        val root = JSONObject()
        PREF_FILES.forEach { name ->
            val o = JSONObject()
            c.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                if (name == "fishlog" && skipKey(k)) return@forEach
                val e = when (v) {
                    is Boolean -> JSONObject().put("t", "b").put("v", v)
                    is Int -> JSONObject().put("t", "i").put("v", v)
                    is Long -> JSONObject().put("t", "l").put("v", v)
                    is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                    is String -> JSONObject().put("t", "s").put("v", v)
                    is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(v.filterIsInstance<String>()))
                    else -> null
                } ?: return@forEach
                o.put(k, e)
            }
            root.put(name, o)
        }
        return root
    }

    /** prefsJson 으로 만든 걸 되살리기 · 되살린 개수 */
    fun applyPrefs(c: Context, pref: String, o: JSONObject): Int {
        if (pref !in PREF_FILES) return 0
        var n = 0
        val ed = c.getSharedPreferences(pref, Context.MODE_PRIVATE).edit()
        o.keys().forEach { k ->
            if (pref == "fishlog" && skipKey(k)) return@forEach
            val x = o.optJSONObject(k) ?: return@forEach
            when (x.optString("t")) {
                "b" -> ed.putBoolean(k, x.getBoolean("v"))
                "i" -> ed.putInt(k, x.getInt("v"))
                "l" -> ed.putLong(k, x.getLong("v"))
                "f" -> ed.putFloat(k, x.getDouble("v").toFloat())
                "s" -> ed.putString(k, x.getString("v"))
                "ss" -> { val a = x.getJSONArray("v"); ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
                else -> return@forEach
            }
            n++
        }
        ed.commit()
        return n
    }

    // ---------------- v6.24 (273): 작은 것(설정 · 가방 · 사이트 · 리모컨 등)은 서버에도 ----------------
    //  users/{uid} 문서의 prefsBak (글자 하나 · 수십 KB) — 규칙 바꾸기 없이 내 문서에 (가방 inv2 와 같은 곳)
    fun uploadSettings(c: Context, done: (String?) -> Unit) {
        val me = Cloud.uid ?: return done("로그인이 안 돼 있어요")
        val txt = prefsJson(c.applicationContext).toString()
        if (txt.length > 700_000) return done("설정이 너무 커서 서버에 못 올렸어요")
        com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("users").document(me)
            .set(mapOf("prefsBak" to txt, "prefsBakAt" to System.currentTimeMillis()), com.google.firebase.firestore.SetOptions.merge())
            .addOnSuccessListener { done(null) }.addOnFailureListener { done(it.message ?: "서버 저장 실패") }
    }

    /** 서버에 저장된 설정 되살리기 · done(되살린 개수, 저장 시각, 오류) */
    fun downloadSettings(c: Context, done: (Int, Long, String?) -> Unit) {
        val me = Cloud.uid ?: return done(0, 0, "로그인이 안 돼 있어요")
        com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("users").document(me).get()
            .addOnSuccessListener { d ->
                val txt = d.getString("prefsBak") ?: return@addOnSuccessListener done(0, 0, "서버에 저장된 설정이 없어요")
                try {
                    val root = JSONObject(txt); var n = 0
                    root.keys().forEach { pref -> root.optJSONObject(pref)?.let { n += applyPrefs(c.applicationContext, pref, it) } }
                    done(n, d.getLong("prefsBakAt") ?: 0L, null)
                } catch (e: Exception) { done(0, 0, "서버 설정을 읽지 못했어요") }
            }.addOnFailureListener { done(0, 0, it.message ?: "불러오기 실패") }
    }

    /** 백업 파일 이름: 조과일지-백업-20261113-1530.zip */
    fun fileName(): String = "조과일지-백업-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.KOREA).format(java.util.Date()) + ".zip"

    /** 백업 만들기 (백그라운드 스레드에서) · onProgress(지금까지 담은 파일 수) */
    fun export(c: Context, out: OutputStream, opts: Opts = Opts(), onProgress: (Int) -> Unit = {}): Result {
        val app = c.applicationContext
        var nPref = 0; var nFile = 0
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("meta.json"))
            zip.write(JSONObject().put("app", app.packageName).put("ver", BuildConfig.VERSION_NAME)
                .put("code", BuildConfig.VERSION_CODE).put("at", System.currentTimeMillis()).toString().toByteArray())
            zip.closeEntry()
            val pj = prefsJson(app)
            PREF_FILES.forEach { name ->
                val o = pj.optJSONObject(name) ?: JSONObject()
                nPref += o.length()
                zip.putNextEntry(ZipEntry("prefs/$name.json"))
                zip.write(o.toString().toByteArray())
                zip.closeEntry()
            }
            fun addDir(base: File, prefix: String) {
                if (!base.exists()) return
                base.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { f ->
                    val rel = f.relativeTo(base).invariantSeparatorsPath
                    try {
                        zip.putNextEntry(ZipEntry("$prefix/$rel"))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        nFile++
                        if (nFile % 20 == 0) onProgress(nFile)
                    } catch (_: Exception) {}
                }
            }
            addDir(File(app.filesDir, "trips"), "files/trips")
            if (opts.photos) addDir(File(app.filesDir, "photos"), "files/photos")
            if (opts.maps) {
                addDir(File(app.filesDir, "osmdroid"), "files/osmdroid")
                // 지도 그림 저장분 (오프라인으로 본 곳)
                addDir(File(app.cacheDir, "osmdroid-tiles"), "cache/osmdroid-tiles")
            }
        }
        onProgress(nFile)
        return Result(nPref, nFile)
    }

    /** 백업 불러오기 (백그라운드 스레드에서) — 끝나면 restart() 로 앱 다시 시작 */
    fun import(c: Context, input: InputStream, onProgress: (Int) -> Unit = {}): Result {
        val app = c.applicationContext
        var nPref = 0; var nFile = 0
        val filesRoot = app.filesDir.canonicalFile
        val cacheRoot = app.cacheDir.canonicalFile
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                if (e.isDirectory || name.contains("..")) continue
                when {
                    name.startsWith("prefs/") && name.endsWith(".json") -> {
                        val pref = name.removePrefix("prefs/").removeSuffix(".json")
                        if (pref !in PREF_FILES) continue
                        val o = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                        nPref += applyPrefs(app, pref, o)
                    }
                    name.startsWith("files/") || name.startsWith("cache/osmdroid-tiles/") -> {
                        val dest = if (name.startsWith("files/")) File(filesRoot, name.removePrefix("files/"))
                                   else File(cacheRoot, name.removePrefix("cache/"))
                        val canon = dest.canonicalFile
                        // 앱 폴더 밖으로는 절대 안 씀
                        if (!canon.path.startsWith(filesRoot.path + File.separator) && !canon.path.startsWith(cacheRoot.path + File.separator)) continue
                        canon.parentFile?.mkdirs()
                        val tmp = File(canon.path + ".part")
                        tmp.outputStream().use { zip.copyTo(it) }
                        if (!tmp.renameTo(canon)) { canon.delete(); tmp.renameTo(canon) }
                        nFile++
                        if (nFile % 20 == 0) onProgress(nFile)
                    }
                }
            }
        }
        onProgress(nFile)
        return Result(nPref, nFile)
    }

    /** v6.8: 자동 백업 파일 이름 · 폴더 (다운로드/조과일지) */
    const val AUTO_NAME = "조과일지-백업-자동.zip"
    const val AUTO_PLACE = "다운로드/조과일지/조과일지-백업-자동.zip"

    /**
     * v6.8: 누르지 않아도 다운로드/조과일지 폴더에 백업 파일 저장 (같은 이름이면 새로 덮어씀)
     *  백그라운드 스레드에서 · 성공하면 Result, 실패하면 null
     */
    fun autoToDownloads(c: Context, onProgress: (Int) -> Unit = {}): Result? = try {
        val app = c.applicationContext
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val cr = app.contentResolver
            val col = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val rel = android.os.Environment.DIRECTORY_DOWNLOADS + "/조과일지"
            // 예전에 이 앱이 만든 자동 백업은 지우고 새로
            try {
                cr.delete(col, "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${android.provider.MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(AUTO_NAME, "$rel/"))
            } catch (_: Exception) {}
            val v = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, AUTO_NAME)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, rel)
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(col, v) ?: throw java.io.IOException("insert")
            val res = cr.openOutputStream(uri)?.use { export(app, it, onProgress = onProgress) } ?: throw java.io.IOException("open")
            v.clear(); v.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            cr.update(uri, v, null, null)
            res
        } else {
            val dir = File(app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "조과일지").apply { mkdirs() }
            File(dir, AUTO_NAME).outputStream().use { export(app, it, onProgress = onProgress) }
        }
    } catch (_: Exception) { null }

    /** 불러온 뒤: 앱을 처음부터 다시 켜서 가방 · 설정 · 기록을 새로 읽게 */
    fun restart(a: Activity) {
        val i = a.packageManager.getLaunchIntentForPackage(a.packageName) ?: return
        a.startActivity(Intent.makeRestartActivityTask(i.component))
        Runtime.getRuntime().exit(0)
    }
}
