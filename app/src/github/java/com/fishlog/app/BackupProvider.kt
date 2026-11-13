package com.fishlog.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File
import java.security.MessageDigest

/**
 * v6.7 (252): GitHub판 → 스토어판으로 가방 · 설정 · 지도 · 기록 넘겨주기
 *  - 같은 폰의 스토어판(kr.jogwailji.app)이 처음 켤 때 여기서 백업 파일을 받아 가요
 *  - 우리 스토어판(구글 플레이 서명 · 업로드 키 서명)만 받을 수 있고, 다른 앱은 막아요
 */
class BackupProvider : ContentProvider() {
    private val allowedPkg = "kr.jogwailji.app"
    /** 스토어판 서명 SHA-1 (플레이 앱 서명 키 · 업로드 키) */
    private val allowedSha1 = setOf(
        "4FF0F15E196F956C85440B96E3AC9AE90593862F",
        "D0EAF008084BDA5F27060C5D2412BD580A304F50"
    )

    override fun onCreate() = true

    private fun callerOk(): Boolean {
        val c = context ?: return false
        val pkg = callingPackage ?: return false
        if (pkg != allowedPkg) return false
        return try {
            val pm = c.packageManager
            val certs: List<ByteArray> = if (Build.VERSION.SDK_INT >= 28) {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                val s = info.signingInfo ?: return false
                (if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory).map { it.toByteArray() }
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures?.map { it.toByteArray() } ?: emptyList()
            }
            certs.any { sha1(it) in allowedSha1 }
        } catch (_: Exception) { false }
    }

    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02X".format(it) }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (!callerOk()) throw SecurityException("not allowed")
        val c = context ?: return null
        val out = File(c.cacheDir, "export-backup.zip")
        out.outputStream().use { Backup.export(c, it) }
        return ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "application/zip"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
