package com.fishlog.app

import android.app.Activity

/**
 * v6.4 (249): 🔄 업데이트 확인 — GitHub판
 *  지금처럼 GitHub 최신 버전을 확인하고, 있으면 앱 안 업데이트 창
 */
object StoreUpdate {
    val available: Boolean get() = Updater.latestCode != null

    /** done(새 버전 있음, 오류 문구) */
    fun check(a: Activity, done: (Boolean, String?) -> Unit) {
        Updater.check(a) { v -> done(v != null, null) }
    }

    fun open(a: Activity) = Updater.showDialog(a, false)
}
