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
    /** 같이 낚시하는 그룹원: 5분마다 올리고 받아오기 */
    private val liveTicker = object : Runnable {
        override fun run() {
            if (Store.current != null) try { Live.sync() } catch (_: Exception) {}
            handler.postDelayed(this, Live.EVERY_MS)
        }
    }

    // ---------------- 만조·간조 알람 ----------------
    @Volatile private var tideExt: List<Tide.Extreme> = emptyList()
    private var tideLoadedAt = 0L
    private var tideLoading = false
    private val alarmed = HashSet<Long>()
    private val tideChecker = object : Runnable {
        override fun run() { checkTideAlarm(); handler.postDelayed(this, 60_000L) }
    }

    private fun loadTide() {
        if (tideLoading) return
        val loc = Tracker.last?.let { it.latitude to it.longitude } ?: Store.current?.anyLocation() ?: return
        tideLoading = true
        tideLoadedAt = System.currentTimeMillis()
        val app = applicationContext
        Thread {
            val now = System.currentTimeMillis()
            val list = listOfNotNull(Tide.fetchNow(app, loc.first, loc.second, now),
                Tide.fetchNow(app, loc.first, loc.second, now + 24 * 3600_000L))
                .flatMap { Tide.extremes(it) }.distinctBy { it.time / 60_000L }.sortedBy { it.time }
            handler.post { if (list.isNotEmpty()) tideExt = list; tideLoading = false }
        }.start()
    }

    private fun checkTideAlarm() {
        val min = Store.tideAlarmMin
        if (Store.current == null || min <= 0) return
        val now = System.currentTimeMillis()
        if ((tideExt.isEmpty() && now - tideLoadedAt > 5 * 60_000L) || now - tideLoadedAt > 3 * 3600_000L) loadTide()
        val lead = min * 60_000L
        val e = tideExt.firstOrNull { it.time > now && it.time - now <= lead && it.time !in alarmed } ?: return
        alarmed.add(e.time)
        val left = ((e.time - now) / 60_000L).coerceAtLeast(1)
        val at = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date(e.time))
        val kind = if (e.high) "만조" else "간조"
        val open = PendingIntent.getActivity(this, 5, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CHANNEL_TIDE)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("${kind} ${left}분 전 ($at)")
            .setContentText(String.format(java.util.Locale.KOREA, "곧 %s이에요 · 물높이 %.0fcm", kind, e.value * 100))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(TIDE_ID, n)
        Haptics.undo(this)
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
        handler.postDelayed(tideChecker, 20_000L)
        handler.postDelayed(liveTicker, 30_000L)
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
        if (Store.current == null) try { Live.clear() } catch (_: Exception) {}
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
            if (Cloud.active != null && ended.catches.isNotEmpty()) Cloud.uploadTrip(ended) { }
            Cloud.backupPending()
        }
        stopSelf()
    }

    // ---------------- 리모컨 ----------------
    private fun setupSession() {
        val s = MediaSession(this, "FishLog")
        s.setCallback(object : MediaSession.Callback() {
            private val downAt = HashMap<Int, Long>()

            // 갤럭시 워치 음악 컨트롤러는 버튼 신호 대신 이쪽으로 들어옴
            override fun onSkipToNext() { ButtonRouter.watch(this@RemoteService, 2) }
            override fun onSkipToPrevious() { ButtonRouter.watch(this@RemoteService, 1) }
            override fun onPlay() { keepPlaying() }
            override fun onPause() { keepPlaying() }

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

    private fun keepPlaying() {
        session?.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
            .setState(PlaybackState.STATE_PLAYING, 0L, 1f).build())
    }

    /** 워치 음악 화면에 지금 마릿수 보이기 (제목 = 1번, 가수 = 2번) */
    private fun updateMetadata() {
        val t = Store.current ?: return
        session?.setMetadata(android.media.MediaMetadata.Builder()
            .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "⏮ ${t.name(1)} ${t.count(1)}  ·  ⏭ ${t.name(2)} ${t.count(2)}")
            .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "조과기록 · 총 ${t.catches.size}마리")
            .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, "조과기록")
            .build())
    }

    /** 리모컨을 등록했거나 워치 기록을 켰을 때만 무음 재생 (버튼 우선권 유지 + 워치 음악 화면에 보이기) */
    private fun updateSilence() {
        updateMetadata()
        val want = Store.current != null && (Store.key1 != null || Store.key2 != null || Store.watchMode)
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
        if (nm.getNotificationChannel(CHANNEL_TIDE) == null) {
            val ch = NotificationChannel(CHANNEL_TIDE, "만조·간조 알람", NotificationManager.IMPORTANCE_HIGH)
            ch.enableVibration(true)
            ch.vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
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
        private const val CHANNEL_TIDE = "tide"
        private const val TIDE_ID = 9
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
