package com.fishlog.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock

/**
 * v6.20 (269): 리모컨 볼륨 버튼을 '음량 바뀜'으로 알아듣기
 *  블루투스 음악 리모컨 중에는 볼륨 버튼이 키 신호가 아니라 폰 음량을 직접 바꾸는 신호(AVRCP 절대 음량)로 와서
 *  앱이 버튼을 못 받는 경우가 있음 → 버튼 등록 중 · 출조 중에 음악 음량이 바뀌면 VOL_UP / VOL_DOWN 으로 처리하고
 *  음량은 바로 원래대로 되돌림. 키 신호로 이미 받은 건 음량이 안 바뀌므로 두 번 세지 않음.
 *  출조 중에는 등록된 볼륨 버튼일 때만 (아니면 그냥 음량 조절로 둠).
 */
object VolumeWatch {
    private const val ACTION = "android.media.VOLUME_CHANGED_ACTION"
    private const val EX_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
    private const val EX_NEW = "android.media.EXTRA_VOLUME_STREAM_VALUE"
    private const val EX_PREV = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"

    private var learning = false
    private var trip = false
    private var receiver: BroadcastReceiver? = null
    /** 시작할 때 음량이 끝(0 · 최대)이라 한 칸 옮겼으면 원래 값 (끝나면 되돌림) */
    private var nudgedFrom = -1
    /** 우리가 되돌린 음량 (이 값으로 오는 알림은 무시) */
    private var expect = -1
    private var expectUntil = 0L

    fun learning(on: Boolean) { learning = on; update() }
    fun trip(on: Boolean) { trip = on; update() }

    private fun ctx(): Context? = Cloud.appContext

    private fun update() {
        val c = ctx() ?: return
        val want = learning || trip
        if (want && receiver == null) start(c) else if (!want && receiver != null) stop(c)
    }

    private fun am(c: Context) = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun start(c: Context) {
        try {
            val am = am(c)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val v = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            // 최대면 더 올라가지 않고, 0이면 더 내려가지 않아 신호가 안 옴 → 한 칸만 옮겨 두기
            val to = when { max > 1 && v >= max -> max - 1; max > 1 && v <= 0 -> 1; else -> -1 }
            if (to >= 0) { nudgedFrom = v; quietSet(am, to) }
        } catch (_: Exception) {}
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) { onChange(ctx, i) }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(r, IntentFilter(ACTION), Context.RECEIVER_EXPORTED)
            else c.registerReceiver(r, IntentFilter(ACTION))
            receiver = r
        } catch (_: Exception) {}
    }

    private fun stop(c: Context) {
        try { receiver?.let { c.unregisterReceiver(it) } } catch (_: Exception) {}
        receiver = null
        if (nudgedFrom >= 0) {
            try { quietSet(am(c), nudgedFrom) } catch (_: Exception) {}
            nudgedFrom = -1
        }
    }

    private fun quietSet(am: AudioManager, v: Int) {
        expect = v; expectUntil = SystemClock.elapsedRealtime() + 1500
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
    }

    private fun onChange(c: Context, i: Intent) {
        if (i.getIntExtra(EX_TYPE, -1) != AudioManager.STREAM_MUSIC) return
        val nv = i.getIntExtra(EX_NEW, -1); val pv = i.getIntExtra(EX_PREV, -1)
        if (nv < 0 || pv < 0 || nv == pv) return
        val now = SystemClock.elapsedRealtime()
        if (nv == expect && now < expectUntil) { expect = -1; return }   // 우리가 되돌린 것
        val code = if (nv > pv) "VOL_UP" else "VOL_DOWN"
        val forUs = ButtonRouter.learningStep != 0 || (Store.current != null && Store.remoteNumOf(code) != 0)
        if (!forUs) return
        try { quietSet(am(c), pv) } catch (_: Exception) {}
        ButtonRouter.onPress(c, code)
    }
}
