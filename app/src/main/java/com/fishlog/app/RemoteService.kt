package com.fishlog.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.view.KeyEvent

/**
 * 출조 중에 계속 떠 있는 서비스.
 * 화면이 꺼져 있어도 블루투스 리모컨 버튼을 받기 위해
 * "음악 재생 앱"처럼 미디어 세션을 잡고, 소리 없는 오디오를 계속 틀어둔다.
 */
class RemoteService : Service() {

    private var session: MediaSession? = null
    private var silence: AudioTrack? = null
    private val storeListener: () -> Unit = { updateNotification() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        createChannel()
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTI_ID, n)
        }
        setupSession()
        startSilence()
        Store.addListener(storeListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Store.current == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 다른 음악 앱이 미디어 버튼을 가져갔을 수 있으니 다시 활성화
        session?.isActive = true
        return START_STICKY
    }

    override fun onDestroy() {
        Store.removeListener(storeListener)
        session?.isActive = false
        session?.release()
        session = null
        silence?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        silence = null
        super.onDestroy()
    }

    private fun setupSession() {
        val s = MediaSession(this, "FishLog")
        s.setCallback(object : MediaSession.Callback() {
            private val downAt = HashMap<Int, Long>()

            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val ev: KeyEvent = if (Build.VERSION.SDK_INT >= 33) {
                    mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                } ?: return false

                when (ev.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (ev.repeatCount == 0) downAt[ev.keyCode] = ev.eventTime
                    }
                    KeyEvent.ACTION_UP -> {
                        val start = downAt.remove(ev.keyCode) ?: ev.downTime
                        ButtonRouter.onKeyUp(this@RemoteService, ev.keyCode, ev.eventTime - start)
                    }
                }
                return true
            }
        })
        // 셔터 리모컨처럼 "볼륨 +" 신호를 보내는 리모컨용
        s.setPlaybackToRemote(object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
            override fun onAdjustVolume(direction: Int) {
                if (direction > 0) ButtonRouter.onPress(this@RemoteService, "VOL_UP")
                else if (direction < 0) ButtonRouter.onPress(this@RemoteService, "VOL_DOWN")
            }
        })
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP
                )
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                .build()
        )
        s.isActive = true
        session = s
    }

    /** 소리 없는 오디오를 반복 재생해서 미디어 버튼 우선권을 유지 */
    private fun startSilence() {
        try {
            val rate = 8000
            val samples = rate // 1초
            val data = ShortArray(samples)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples * 2)
                .build()
            track.write(data, 0, data.size)
            track.setLoopPoints(0, samples, -1)
            track.setVolume(0f)
            track.play()
            silence = track
        } catch (_: Exception) {
            // 오디오가 안 돼도 화면 켜진 상태 입력은 동작함
        }
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "출조 중", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            ch.enableVibration(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val t = Store.current
        val text = if (t == null) "출조 준비" else
            "${t.name(1)} ${t.count(1)} · ${t.name(2)} ${t.count(2)} · 총 ${t.catches.size}"
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("출조 중 · 리모컨 대기")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification() {
        if (Store.current == null) { stopSelf(); return }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTI_ID, buildNotification())
    }

    companion object {
        private const val CHANNEL = "trip"
        private const val NOTI_ID = 7

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, RemoteService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RemoteService::class.java))
        }
    }
}
