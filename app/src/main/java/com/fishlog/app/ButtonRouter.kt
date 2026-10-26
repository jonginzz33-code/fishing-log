package com.fishlog.app

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent

/**
 * 리모컨/폰 버튼 입력을 한 곳에서 처리.
 * 화면이 켜져 있을 땐 MainActivity, 꺼져 있을 땐 RemoteService 가 여기로 넘겨줌.
 */
object ButtonRouter {

    /** 0 = 평소, 1 = 1번 버튼 기다림, 2 = 2번 버튼 기다림 */
    var learningStep = 0
        private set

    /** 화면에 안내 문구를 띄우기 위한 콜백 */
    var onMessage: ((String) -> Unit)? = null

    private const val LONG_PRESS_MS = 800L
    private const val DEBOUNCE_MS = 350L
    private var lastAt = 0L

    fun startLearning() {
        learningStep = 1
        onMessage?.invoke("리모컨의 1번 버튼을 눌러주세요")
    }

    fun cancelLearning() {
        learningStep = 0
        onMessage?.invoke("버튼 등록을 취소했어요")
    }

    /** 이 키코드를 우리가 처리해야 하는지 (출조 중이거나 등록 중일 때만) */
    fun wants(keyCode: Int): Boolean {
        if (learningStep != 0) return isRemoteLike(keyCode)
        if (Store.current == null) return false
        val code = codeOf(keyCode)
        return code == Store.key1 || code == Store.key2
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
        handle(ctx, codeOf(keyCode), heldMs >= LONG_PRESS_MS)
    }

    /** 누름/뗌 구분이 없는 입력 (볼륨 조절 신호) */
    fun onPress(ctx: Context, code: String) = handle(ctx, code, false)

    private fun handle(ctx: Context, code: String, longPress: Boolean) {
        val now = SystemClock.elapsedRealtime()
        // 리모컨이 한 번 누를 때 신호를 두 개 보내는 경우 대비
        if (now - lastAt < DEBOUNCE_MS) return
        lastAt = now

        when (learningStep) {
            1 -> {
                Store.key1 = code
                learningStep = 2
                Haptics.slot(ctx, 1)
                onMessage?.invoke("1번 = ${label(code)} 등록! 이제 2번 버튼을 눌러주세요")
                return
            }
            2 -> {
                if (code == Store.key1) {
                    Haptics.error(ctx)
                    onMessage?.invoke("1번과 같은 신호예요. 다른 버튼을 눌러주세요 (같은 신호만 보내는 리모컨일 수 있어요)")
                    return
                }
                Store.key2 = code
                learningStep = 0
                Haptics.slot(ctx, 2)
                onMessage?.invoke("2번 = ${label(code)} 등록 완료!")
                return
            }
        }

        if (Store.current == null) return
        val slot = when (code) {
            Store.key1 -> 1
            Store.key2 -> 2
            else -> 0
        }
        if (slot == 0) return

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
