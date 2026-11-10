package com.fishlog.app

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent

/**
 * 리모컨/폰 버튼 입력을 한 곳에서 처리.
 * 화면이 켜져 있을 땐 MainActivity, 꺼져 있을 땐 RemoteService 가 여기로 넘겨줌.
 */
object ButtonRouter {

    /** 0 = 평소, N = 리모컨 N번 버튼 기다림 (1~8) */
    var learningStep = 0
        private set

    /** 화면에 안내 문구를 띄우기 위한 콜백 */
    var onMessage: ((String) -> Unit)? = null

    private const val LONG_PRESS_MS = 800L
    private const val DEBOUNCE_MS = 80L          // v4.1 (187): 같은 버튼에서 겹쳐 온 신호만 거름 (따닥 연타는 다 셈)
    private const val DEBOUNCE_SLOW_MS = 350L     // '느리게': 한 번 누를 때 신호를 두 번 보내는 리모컨용
    private const val WATCH_GAP_MS = 80L
    private var watchAt = 0L
    private val lastAtByCode = HashMap<String, Long>()

    // ---- v5.2 (238): 누르고 있으면 한 번만 · 빠르게 두 번 누르면 두 번 ----
    //  볼륨 신호 리모컨은 누르고 있는 동안 신호를 0.05초마다 계속 보냄 → 그 반복은 한 번으로
    private const val HOLD_GAP_MS = 160L          // 이보다 짧은 간격으로 이어지면 '누르고 있는 중'
    private const val HOLD_AFTER_TAP_MS = 1000L   // 한 번 센 뒤 이 안에 시작된 반복은 같은 누름 (반복 시작 전 멈칫하는 시간)
    private val sigAt = HashMap<String, Long>()
    private val countedAt = HashMap<String, Long>()
    private val burstDone = HashSet<String>()
    private val pendingTap = HashMap<String, Pair<Long, Runnable>>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun tap(ctx: Context, code: String) {
        if (learningStep != 0) { handle(ctx, code, false); return }
        val app = ctx.applicationContext
        val now = SystemClock.elapsedRealtime()
        val gap = now - (sigAt[code] ?: 0L)
        sigAt[code] = now
        if (gap < HOLD_GAP_MS) {
            // 누르고 있는 중 (반복 신호)
            if (code in burstDone) return
            burstDone.add(code)
            val start = pendingTap.remove(code)?.let { (t, r) -> main.removeCallbacks(r); t } ?: now
            // 방금 센 누름에 이어진 반복이면 무시, 처음부터 반복으로 오는 리모컨이면 한 번 셈
            if (start - (countedAt[code] ?: Long.MIN_VALUE / 2) > HOLD_AFTER_TAP_MS) { countedAt[code] = start; handle(app, code, false) }
            return
        }
        burstDone.remove(code)
        val r = Runnable { pendingTap.remove(code); countedAt[code] = now; handle(app, code, false) }
        pendingTap[code] = now to r
        main.postDelayed(r, HOLD_GAP_MS)
    }

    fun startLearning(n: Int = 1) {
        learningStep = n.coerceIn(1, Store.MAX_REMOTE)
        onMessage?.invoke("리모컨에서 ${learningStep}번으로 쓸 버튼을 한 번 눌러주세요")
    }

    fun cancelLearning() {
        learningStep = 0
        onMessage?.invoke("버튼 등록을 취소했어요")
    }

    /** 이 키코드를 우리가 처리해야 하는지 (출조 중이거나 등록 중일 때만) */
    fun wants(keyCode: Int): Boolean {
        if (learningStep != 0) return isRemoteLike(keyCode)
        if (Store.current == null) return false
        return Store.remoteNumOf(codeOf(keyCode)) != 0
    }

    /** 리모컨이 보낼 법한 키만 등록 대상으로 받음 (뒤로가기·홈 등 제외) */
    fun isRemoteLike(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_MENU -> false
        else -> true
    }

    fun codeOf(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> "VOL_UP"
        KeyEvent.KEYCODE_VOLUME_DOWN -> "VOL_DOWN"
        // 재생/일시정지 계열은 리모컨마다 다르게 보내서 하나로 묶음
        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> "PLAY_PAUSE"
        else -> "KEY_$keyCode"
    }

    fun label(code: String?): String = when {
        code == null -> "미등록"
        code == "VOL_UP" -> "볼륨 +"
        code == "VOL_DOWN" -> "볼륨 -"
        code == "PLAY_PAUSE" -> "재생/정지"
        code == "KEY_${KeyEvent.KEYCODE_MEDIA_NEXT}" -> "다음 곡"
        code == "KEY_${KeyEvent.KEYCODE_MEDIA_PREVIOUS}" -> "이전 곡"
        code == "KEY_${KeyEvent.KEYCODE_ENTER}" -> "엔터"
        code.startsWith("KEY_") -> {
            val n = code.removePrefix("KEY_").toIntOrNull()
            if (n != null) KeyEvent.keyCodeToString(n).removePrefix("KEYCODE_") else code
        }
        else -> code
    }

    /** 키를 뗐을 때 호출. 누른 시간으로 짧게/길게 구분 */
    fun onKeyUp(ctx: Context, keyCode: Int, heldMs: Long) {
        if (heldMs >= LONG_PRESS_MS) handle(ctx, codeOf(keyCode), true)
        else tap(ctx, codeOf(keyCode))
    }

    /** 갤럭시 워치 음악 컨트롤러: ⏮ (왼쪽) = 1번, ⏭ (오른쪽) = 2번 */
    fun watch(ctx: Context, slot: Int) {
        if (!Store.watchMode || Store.current == null || learningStep != 0) return
        // 워치는 한 번 누르면 신호도 한 번 → 연달아 눌러도 다 셈 (겹쳐 온 같은 신호만 0.08초 거름)
        val now = SystemClock.elapsedRealtime()
        if (now - watchAt < WATCH_GAP_MS) return
        watchAt = now
        val n = Store.addCatch(slot)
        Haptics.slot(ctx, slot)
        onMessage?.invoke("워치 ${if (slot == 1) "⏮" else "⏭"} → ${Store.current?.name(slot) ?: "${slot}번"} ${n}마리")
    }

    /** 누름/뗌 구분이 없는 입력 (볼륨 조절 신호) */
    fun onPress(ctx: Context, code: String) = tap(ctx, code)

    private fun handle(ctx: Context, code: String, longPress: Boolean) {
        val now = SystemClock.elapsedRealtime()
        // 리모컨이 한 번 누를 때 신호를 두 개 보내는 경우 대비
        val gap = if (Store.remoteSlow) DEBOUNCE_SLOW_MS else DEBOUNCE_MS
        if (now - (lastAtByCode[code] ?: 0L) < gap) return
        lastAtByCode[code] = now

        if (learningStep != 0) {
            val n = learningStep
            val was = Store.remoteNumOf(code)
            if (was != 0 && was != n) Store.setKey(was, null)   // 다른 번호에 쓰던 버튼이면 이 번호로 옮김
            Store.setKey(n, code)
            if (n > Store.remoteRows) Store.remoteRows = n
            learningStep = 0
            Haptics.slot(ctx, if (n % 2 == 1) 1 else 2)
            onMessage?.invoke("리모컨 ${n}번 = ${label(code)} 등록!" + if (was != 0 && was != n) " (${was}번에서 옮겼어요)" else "")
            return
        }

        if (Store.current == null) return
        val num = Store.remoteNumOf(code)
        if (num == 0) return
        val slot = Store.slotForRemote(num)
        if (slot == null) {
            if (!longPress) { Haptics.error(ctx); onMessage?.invoke("리모컨 ${num}번에 맞는 ${num}번 카드가 없어요 (＋ 어종 추가로 카드를 만들어 주세요)") }
            return
        }

        if (longPress) {
            val undone = Store.undoLast()
            if (undone != 0) {
                Haptics.undo(ctx)
                onMessage?.invoke("마지막 기록을 취소했어요")
            } else {
                Haptics.error(ctx)
            }
            return
        }

        Store.addCatch(slot)
        Haptics.slot(ctx, slot)
    }
}
