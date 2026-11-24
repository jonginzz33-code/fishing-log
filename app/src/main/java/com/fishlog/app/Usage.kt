package com.fishlog.app

import android.content.Context
import android.net.TrafficStats
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v6.86 (387): 📊 사용자별 인터넷 사용량 (앱 전체 · 대략)
 *  - 폰이 이 앱이 쓴 데이터(받기 + 보내기 · TrafficStats)를 날마다 KB 로 셈 → users/{uid}.usage {yyyyMMdd: KB} (최근 14일)
 *  - 서버(Firebase) 읽기 횟수는 앱이 셀 방법이 없어서, 그 대신 '이 사람 폰이 인터넷을 얼마나 썼나'로 봄 (지도 · 날씨 · 사진 포함)
 */
object Usage {
    private val dayF = SimpleDateFormat("yyyyMMdd", Locale.KOREA)
    private fun prefs(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)

    @Synchronized fun tick(c: Context) {
        val uid = android.os.Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid); val tx = TrafficStats.getUidTxBytes(uid)
        if (rx < 0 || tx < 0) return
        val now = rx + tx
        val p = prefs(c)
        val last = p.getLong("u_last", -1L)
        val delta = when { last < 0 -> 0L; now >= last -> now - last; else -> now }   // 폰 다시 켜면 0부터
        val o = try { JSONObject(p.getString("u_days", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val today = dayF.format(Date())
        o.put(today, o.optLong(today) + delta)
        val cut = dayF.format(Date(System.currentTimeMillis() - 14 * 86400_000L))
        o.keys().asSequence().toList().filter { it < cut }.forEach { o.remove(it) }
        p.edit().putLong("u_last", now).putString("u_days", o.toString()).apply()
    }

    /** 날짜 → KB */
    fun days(c: Context): Map<String, Long> {
        val o = try { JSONObject(prefs(c).getString("u_days", "{}") ?: "{}") } catch (_: Exception) { return emptyMap() }
        return o.keys().asSequence().associateWith { o.optLong(it) / 1024 }
    }

    fun mb(kb: Long): String = if (kb >= 1024) "%.1fMB".format(kb / 1024.0) else "${kb}KB"
}
