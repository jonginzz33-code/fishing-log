package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * v6.4 (249): 🔄 업데이트 확인 — 플레이 스토어판
 *  구글 공식 '앱 업데이트' 기능으로 스토어에 새 버전이 있는지 물어보고, 있으면 스토어 화면으로
 */
object StoreUpdate {
    /** 마지막으로 확인했을 때 새 버전이 있었나 (메인 🔄 빨간 점) */
    @Volatile var available = false

    /** done(새 버전 있음, 오류 문구) */
    fun check(a: Activity, done: (Boolean, String?) -> Unit) {
        try {
            AppUpdateManagerFactory.create(a.applicationContext).appUpdateInfo
                .addOnSuccessListener { info ->
                    val has = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE ||
                        info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
                    available = has
                    done(has, null)
                }
                .addOnFailureListener { done(false, "플레이 스토어에 물어보지 못했어요 (인터넷 · 플레이 스토어 로그인 확인)") }
        } catch (_: Exception) {
            done(false, "플레이 스토어에 물어보지 못했어요")
        }
    }

    /** 새 버전 있음 → 스토어 화면 열기 */
    fun open(a: Activity) {
        AlertDialog.Builder(a, R.style.FlAlert)
            .setTitle("🎣 새 버전이 있어요")
            .setMessage("플레이 스토어에서 업데이트할 수 있어요.")
            .setPositiveButton("플레이 스토어 열기") { _, _ -> openStore(a) }
            .setNegativeButton("나중에", null)
            .show()
    }

    fun openStore(a: Activity) {
        val id = a.packageName
        try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id"))) }
        catch (_: Exception) {
            try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id"))) } catch (_: Exception) {}
        }
    }
}
