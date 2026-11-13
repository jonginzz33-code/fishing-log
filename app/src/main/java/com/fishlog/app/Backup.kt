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
        k.startsWith("dl_") || k == "imported_from_github" || k == "import_asked"
    /** 담을 폴더 (filesDir 아래) */
    private val FILE_DIRS = listOf("trips", "photos", "khoa", "haeru", "osmdroid")

    class Result(val prefs: Int, val files: Int)

    /** 백업 파일 이름: 조과일지-백업-20261113-1530.zip */
    fun fileName(): String = "조과일지-백업-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.KOREA).format(java.util.Date()) + ".zip"

    /** 백업 만들기 (백그라운드 스레드에서) · onProgress(지금까지 담은 파일 수) */
    fun export(c: Context, out: OutputStream, onProgress: (Int) -> Unit = {}): Result {
        val app = c.applicationContext
        var nPref = 0; var nFile = 0
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("meta.json"))
            zip.write(JSONObject().put("app", app.packageName).put("ver", BuildConfig.VERSION_NAME)
                .put("code", BuildConfig.VERSION_CODE).put("at", System.currentTimeMillis()).toString().toByteArray())
            zip.closeEntry()
            PREF_FILES.forEach { name ->
                val all = app.getSharedPreferences(name, Context.MODE_PRIVATE).all
                val o = JSONObject()
                all.forEach { (k, v) ->
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
                    o.put(k, e); nPref++
                }
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
            FILE_DIRS.forEach { d -> addDir(File(app.filesDir, d), "files/$d") }
            // 지도 그림 저장분 (오프라인으로 본 곳)
            addDir(File(app.cacheDir, "osmdroid-tiles"), "cache/osmdroid-tiles")
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
                        val ed = app.getSharedPreferences(pref, Context.MODE_PRIVATE).edit()
                        o.keys().forEach { k ->
                            if (pref == "fishlog" && skipKey(k)) return@forEach
                            val x = o.getJSONObject(k)
                            when (x.getString("t")) {
                                "b" -> ed.putBoolean(k, x.getBoolean("v"))
                                "i" -> ed.putInt(k, x.getInt("v"))
                                "l" -> ed.putLong(k, x.getLong("v"))
                                "f" -> ed.putFloat(k, x.getDouble("v").toFloat())
                                "s" -> ed.putString(k, x.getString("v"))
                                "ss" -> { val a = x.getJSONArray("v"); ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
                            }
                            nPref++
                        }
                        ed.commit()
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

    /** 불러온 뒤: 앱을 처음부터 다시 켜서 가방 · 설정 · 기록을 새로 읽게 */
    fun restart(a: Activity) {
        val i = a.packageManager.getLaunchIntentForPackage(a.packageName) ?: return
        a.startActivity(Intent.makeRestartActivityTask(i.component))
        Runtime.getRuntime().exit(0)
    }
}
