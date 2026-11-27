package com.fishlog.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * v6.56 (332): ⌚ 리모컨으로 셀 때 갤럭시 워치 진동
 *  - 워치 앱 없이 폰 알림으로 (갤럭시 워치는 폰 알림을 받아 진동)
 *  - 1번 = 한 번 · 2번↑ = 두 번 (폰 진동과 같은 규칙) · 취소 = 길게 한 번
 *  - 같은 알림 번호를 지우고 새로 띄워서 쌓이지 않음 · 5초 뒤 저절로 사라짐
 *  - 삼성 기본값: 폰 화면이 꺼져 있을 때만 워치로 보냄
 */
object WearBuzz {
    private const val ID = 7301
    private const val CH1 = "wear_count1"
    private const val CH2 = "wear_count2"
    private const val CHU = "wear_undo"

    private fun prefs(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)
    fun on(c: Context) = prefs(c).getBoolean("wear_buzz", false)
    fun set(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean("wear_buzz", v).apply()
        if (!v) try { c.getSystemService(NotificationManager::class.java)?.cancel(ID) } catch (_: Exception) {}
    }

    private fun channels(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < 26) return
        fun ch(id: String, name: String, pattern: LongArray) {
            if (nm.getNotificationChannel(id) != null) return
            nm.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "리모컨으로 셀 때 워치를 울려요"
                setSound(null, null)
                enableVibration(true); vibrationPattern = pattern
                setShowBadge(false)
            })
        }
        ch(CH1, "⌚ 워치 진동 · 1번", longArrayOf(0, 250))
        ch(CH2, "⌚ 워치 진동 · 2번 이상", longArrayOf(0, 200, 150, 200))
        ch(CHU, "⌚ 워치 진동 · 취소", longArrayOf(0, 700))
    }

    /** 리모컨으로 한 마리 셈 */
    fun count(c: Context, slot: Int, n: Int) {
        if (!on(c)) return
        val name = Store.current?.name(slot) ?: "${slot}번"
        post(c, if (slot == 1) CH1 else CH2, "🎣 $name ${n}마리 (+1)")
    }

    /** 리모컨 길게 = 마지막 취소 */
    fun undo(c: Context, slot: Int) {
        if (!on(c)) return
        val t = Store.current
        val name = t?.name(slot) ?: "${slot}번"
        val n = t?.catches?.count { it.slot == slot } ?: 0
        post(c, CHU, "↩ $name ${n}마리 (취소)")
    }

    private fun post(c: Context, ch: String, text: String) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            channels(nm)
            nm.cancel(ID)   // 지우고 새로 띄워야 워치가 다시 울림
            val b = Notification.Builder(c, ch)
                .setSmallIcon(R.drawable.ic_stat_octo)
                .setContentTitle(text)
                .setContentText("조과일지")
                .setCategory(Notification.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setTimeoutAfter(5_000L)
            nm.notify(ID, b.build())
        } catch (_: Exception) {}
    }
}
