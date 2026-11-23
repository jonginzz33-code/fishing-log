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
 *  - 8시간 넘게 켜져 있으면 "아직 출조 중인가요?" 알림
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

    /** v3.9.4 (172 · 173): 밀물 시작 · 일출 · 일몰 30분 전 */
    private fun notifyTide(title: String, text: String) {
        val open = PendingIntent.getActivity(this, 5, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CHANNEL_TIDE)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(TIDE_ID + 1, n)
        Haptics.undo(this)
    }
    private fun checkExtraAlerts(now: Long) {
        if (Store.current == null) return
        if (Store.floodAlert) {
            // 간조 시각이 막 지났으면 (0~10분) = 물이 들어오기 시작
            tideExt.firstOrNull { !it.high && now - it.time in 0..10 * 60_000L && -it.time !in alarmed }?.let { e ->
                alarmed.add(-e.time)
                notifyTide("🌊 밀물 시작 · 물이 들어와요", "간조(${java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date(e.time))})가 지났어요. 갯벌 · 갯바위에 있으면 지금 나오세요 (고립 주의)")
            }
        }
        if (Store.sunAlert) {
            val loc = Tracker.last?.let { it.latitude to it.longitude } ?: Store.current?.anyLocation()
            Sun.times(now, loc?.first, loc?.second)?.let { s ->
                listOf(s.rise to "일출", s.set to "일몰").forEach { (t, label) ->
                    val key = t / 60_000L * 10 + 7
                    if (t - now in 0..30 * 60_000L && key !in alarmed) {
                        alarmed.add(key)
                        notifyTide("🌅 $label ${((t - now) / 60_000L).coerceAtLeast(1)}분 전", "$label ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date(t))}" +
                            if (label == "일몰") " · 곧 어두워져요 (랜턴 · 귀가 준비)" else " · 곧 밝아져요")
                    }
                }
            }
        }
    }

    // v6.37 (298): 출조 중 내 바다 구역에 지진해일 특보 → 알람 (15분마다 · 같은 특보는 한 번)
    private var tsuAt = 0L
    private var tsuLast = ""
    private fun checkTsunami(now: Long) {
        if (Store.current == null || Store.current?.isFresh() == true || now - tsuAt < 15 * 60_000L) return
        tsuAt = now
        val loc = Tracker.last?.let { it.latitude to it.longitude } ?: Store.current?.anyLocation()
        Warn.tsunami(loc?.first, loc?.second) { t ->
            if (t.isNullOrBlank()) { if (t != null) tsuLast = ""; return@tsunami }
            if (t == tsuLast) return@tsunami
            tsuLast = t
            notifyTide("🌊 지진해일 특보", "$t\n바다 · 갯바위 · 갯벌에서 바로 나와 높은 곳으로 피하세요")
            Haptics.undo(this)
        }
    }

    private fun checkTideAlarm() {
        val min = Store.tideAlarmMin
        val now0 = System.currentTimeMillis()
        checkTsunami(now0)
        if (Store.current != null && (Store.floodAlert || Store.sunAlert)) {
            if (Store.floodAlert && Store.current?.isFresh() != true && ((tideExt.isEmpty() && now0 - tideLoadedAt > 5 * 60_000L) || now0 - tideLoadedAt > 3 * 3600_000L)) loadTide()
            checkExtraAlerts(now0)
        }
        // v6.9 (254): 민물 출조 중엔 만조 · 간조 알람 안 울림
        if (Store.current == null || min <= 0 || Store.current?.isFresh() == true) return
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
        try { QuickWidget.updateAll(applicationContext) } catch (_: Exception) {}   // v6.50 (323): 위젯 출조중 칸 바로 🟢
        createChannels()
        setupSession()
        goForeground()
        updateSilence()
        VolumeWatch.trip(true)   // v6.20 (269)
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
        // v6.50 (323): 출조 끝 → 위젯 출조중 칸 바로 (출조 정리가 끝난 뒤)
        val app = applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ try { QuickWidget.updateAll(app) } catch (_: Exception) {} }, 1500L)
        VolumeWatch.trip(false)
        handler.removeCallbacksAndMessages(null)
        if (Store.current == null) try { Live.clear(Store.history.firstOrNull()) } catch (_: Exception) {}
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

    /**
     * 워치 음악 화면 · v6.61 (341) 꺼진 화면(AOD) · 잠금화면 음악 칸에 지금 마릿수
     *  - 제목 = 모든 어종 '주꾸미 13 · 갑오징어 4 · 우럭 2' (길면 이름을 짧게 · 많이 잡힌 순 5개까지)
     *  - 워치 기록을 켰으면 1 · 2번 앞에 ⏮ ⏭ (워치 버튼 안내)
     *  - 가수 = '조과일지 · 총 N마리 · 2시간 10분째' · 앨범 그림 = 가장 많이 잡은 어종 그림
     *  - 셀 때마다(Store 바뀜) 바로 고침
     */
    private var artKey: String? = null
    private var artBmp: android.graphics.Bitmap? = null
    private fun updateMetadata() {
        val t = Store.current ?: return
        val slots = (1..t.names.size).filter { it <= 2 || t.count(it) > 0 }
        val parts = slots.map { sl -> Triple(sl, t.name(sl), t.count(sl)) }
        fun build(short: Boolean): String {
            val ordered = if (parts.size > 5) parts.sortedByDescending { it.third }.take(5) else parts
            return ordered.joinToString("  ·  ") { (sl, nm, n) ->
                val name = if (short && nm.length > 2) nm.take(2) else nm
                val pre = if (Store.watchMode) when (sl) { 1 -> "⏮ "; 2 -> "⏭ "; else -> "" } else ""
                "$pre$name $n"
            }
        }
        val title = build(false).let { if (it.length > 30) build(true) else it }
        val min = ((System.currentTimeMillis() - t.start) / 60000L).coerceAtLeast(0)
        val dur = if (min >= 60) "${min / 60}시간 ${min % 60}분째" else "${min}분째"
        val top = parts.maxByOrNull { it.third }?.takeIf { it.third > 0 }?.second ?: t.name(1)
        if (top != artKey) {
            artKey = top
            artBmp = try {
                SpeciesArt.bitmap(this, top)?.let { src ->
                    // 어두운 바탕 정사각형 320px 가운데에 (음악 칸 그림)
                    val out = android.graphics.Bitmap.createBitmap(320, 320, android.graphics.Bitmap.Config.ARGB_8888)
                    val cv = android.graphics.Canvas(out)
                    cv.drawColor(android.graphics.Color.parseColor("#12283A"))
                    cv.drawBitmap(src, null, SpeciesArt.fitRect(src, 24f, 24f, 296f, 296f), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                    out
                }
            } catch (_: Exception) { null }
        }
        val b = android.media.MediaMetadata.Builder()
            .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "조과일지 · 총 ${t.catches.size}마리 · $dur")
            .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, "조과일지")
        artBmp?.let { b.putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        session?.setMetadata(b.build())
    }

    /** 리모컨을 등록했거나 워치 기록을 켰을 때만 무음 재생 (버튼 우선권 유지 + 워치 음악 화면에 보이기) */
    private fun updateSilence() {
        updateMetadata()
        val want = Store.current != null && (Store.anyKey() || Store.watchMode)
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

    /** 나우바 · 상태바 칩 짧은 글자 (많이 잡은 어종 + 총) */
    private fun nowBarText(t: Store.Trip): String {
        val top = t.slots().filter { t.count(it) > 0 }.maxByOrNull { t.count(it) }
        return if (top == null) "🎣 0" else "🎣 ${t.name(top).take(3)} ${t.count(top)}" + (if (t.catches.size > t.count(top)) "·${t.catches.size}" else "")
    }

    private fun buildNotification(): Notification {
        val t = Store.current
        val counts = if (t == null) "출조 준비" else "${t.line()} · 총 ${t.catches.size}"
        val sub = listOfNotNull("출조 중", t?.place()?.takeIf { it.isNotBlank() }, if (Tracker.running) "위치 기록 중" else null).joinToString(" · ")
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_octo)          // AOD·상태바: 주꾸미 아이콘
            .setColor(Ui.ORANGE)
            .setSubText("조과일지")
            .setContentTitle(counts)                        // 마릿수를 크게
            .setContentText(sub)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // 출조 경과 시간이 알림에 저절로 흘러감
            .setShowWhen(t != null).setUsesChronometer(t != null).setWhen(t?.start ?: System.currentTimeMillis())
            // 개인 설정: 잠금 화면·AOD에 보일지
            .setVisibility(if (Store.lockScreenShow) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_SECRET)
            .setDeleteIntent(servicePi(ACT_REPOST, 3)) // 지우면 바로 다시 띄움
        // v6.63 (348): 안드로이드 16 실시간 알림 — 삼성 나우바 · 상태바 칩에 '🎣 17' (받아 주는 폰만 · 아니면 지금 알림 그대로)
        if (t != null && Build.VERSION.SDK_INT >= 36 && Store.lockScreenShow) {
            try {
                b.setStyle(Notification.BigTextStyle().bigText(sub))
                b.setShortCriticalText(nowBarText(t))
                b.extras.putBoolean("android.requestPromotedOngoing", true)
            } catch (_: Throwable) {}
        }
        if (t != null) {
            b.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_octo),
                "${t.name(1)} +1", servicePi(ACT_ADD1, 1)).build())
            b.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_octo),
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
        if (now - t.start < 8 * 3600_000L || now - lastReminder < 2 * 3600_000L) return
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
