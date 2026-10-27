package com.fishlog.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent

/**
 * 출조 중에 계속 떠 있는 서비스
 *  - 리모컨 버튼 받기 (미디어 세션). 무음 재생은 리모컨을 등록했을 때만 (배터리 절약)
 *  - 알림: 지워도 바로 다시 뜸, 작은 +1 버튼 두 개, 잠금화면에서도 보임
 *  - 10시간 넘게 켜져 있으면 "아직 출조 중인가요?" 알림
 */
class RemoteService : Service() {

    private var session: MediaSession? = null
    private var silence: AudioTrack? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastReminder = 0L
    private val storeListener: () -> Unit = { updateNotification(); updateSilence() }
    private val checker = object : Runnable {
        override fun run() { checkLongTrip(); handler.postDelayed(this, 30 * 60_000L) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        createChannels()
        setupSession()
        goForeground()
        updateSilence()
        Store.addListener(storeListener)
        handler.postDelayed(checker, 60_000L)
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if (Tracker.hasPermission(this)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            try {
                startForeground(NOTI_ID, n, type)
            } catch (_: Exception) {
                startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            }
        } else {
            startForeground(NOTI_ID, n)
        }
        Tracker.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_ADD1 -> if (Store.current != null) { Store.addCatch(1); Haptics.slot(this, 1) }
            ACT_ADD2 -> if (Store.current != null) { Store.addCatch(2); Haptics.slot(this, 2) }
            ACT_END -> { endFromNotification(); return START_NOT_STICKY }
            ACT_REPOST -> {}
        }
        if (Store.current == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Tracker.running && Tracker.hasPermission(this)) goForeground()
        updateNotification()
        updateSilence()
        session?.isActive = true
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        Tracker.stop()
        Store.removeListener(storeListener)
        session?.isActive = false
        session?.release()
        session = null
        stopSilence()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(REMIND_ID)
        super.onDestroy()
    }

    private fun endFromNotification() {
        val ended = Store.endTrip()
        if (ended != null && Cloud.user != null) {
            if (Cloud.active != null) Cloud.uploadTrip(ended) { }
            Cloud.backupPending()
        }
        stopSelf()
    }

    // ---------------- 리모컨 ----------------
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
                    KeyEvent.ACTION_DOWN -> if (ev.repeatCount == 0) downAt[ev.keyCode] = ev.eventTime
                    KeyEvent.ACTION_UP -> {
                        val start = downAt.remove(ev.keyCode) ?: ev.downTime
                        ButtonRouter.onKeyUp(this@RemoteService, ev.keyCode, ev.eventTime - start)
                    }
                }
                return true
            }
        })
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

    /** 리모컨을 등록한 경우에만 무음 재생 (화면 꺼짐 상태에서 버튼 우선권 유지용) */
    private fun updateSilence() {
        val want = Store.current != null && (Store.key1 != null || Store.key2 != null)
        if (want && silence == null) startSilence()
        if (!want && silence != null) stopSilence()
    }

    private fun startSilence() {
        try {
            val rate = 8000
            val samples = rate
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
        }
    }

    private fun stopSilence() {
        silence?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        silence = null
    }

    // ---------------- 알림 ----------------
    private fun createChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "출조 중", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            ch.enableVibration(false)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
        }
        if (nm.getNotificationChannel(CHANNEL_REMIND) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_REMIND, "출조 종료 깜빡임 알림", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun servicePi(action: String, code: Int): PendingIntent =
        PendingIntent.getForegroundService(
            this, code, Intent(this, RemoteService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(): Notification {
        val t = Store.current
        val text = if (t == null) "출조 준비" else
            "${t.name(1)} ${t.count(1)} · ${t.name(2)} ${t.count(2)} · 총 ${t.catches.size}" +
                if (Tracker.running) " · 위치 기록 중" else ""
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("출조 중" + (t?.place()?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setDeleteIntent(servicePi(ACT_REPOST, 3)) // 지우면 바로 다시 띄움
        if (t != null) {
            b.addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_input_add),
                "${t.name(1)} +1", servicePi(ACT_ADD1, 1)).build())
            b.addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_input_add),
                "${t.name(2)} +1", servicePi(ACT_ADD2, 2)).build())
        }
        return b.build()
    }

    private fun updateNotification() {
        if (Store.current == null) { stopSelf(); return }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTI_ID, buildNotification())
    }

    private fun checkLongTrip() {
        val t = Store.current ?: return
        val now = System.currentTimeMillis()
        if (now - t.start < 10 * 3600_000L || now - lastReminder < 2 * 3600_000L) return
        lastReminder = now
        val hours = (now - t.start) / 3600_000L
        val n = Notification.Builder(this, CHANNEL_REMIND)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("아직 출조 중인가요?")
            .setContentText("${hours}시간째 기록 중이에요. 끝났으면 종료해 주세요 (배터리 절약)")
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "출조 종료", servicePi(ACT_END, 4)).build())
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(REMIND_ID, n)
    }

    companion object {
        private const val CHANNEL = "trip"
        private const val CHANNEL_REMIND = "remind"
        private const val NOTI_ID = 7
        private const val REMIND_ID = 8
        private const val ACT_ADD1 = "com.fishlog.app.ADD1"
        private const val ACT_ADD2 = "com.fishlog.app.ADD2"
        private const val ACT_END = "com.fishlog.app.END"
        private const val ACT_REPOST = "com.fishlog.app.REPOST"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, RemoteService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RemoteService::class.java))
        }
    }
}
