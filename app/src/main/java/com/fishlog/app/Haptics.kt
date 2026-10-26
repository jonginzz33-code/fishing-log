package com.fishlog.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object Haptics {
    private fun vibrator(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private fun play(ctx: Context, pattern: LongArray) {
        vibrator(ctx)?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    /** 1번 = 짧게 1번, 2번 = 짧게 2번 */
    fun slot(ctx: Context, slot: Int) {
        if (slot == 1) play(ctx, longArrayOf(0, 120))
        else play(ctx, longArrayOf(0, 120, 120, 120))
    }

    /** 취소 = 길게 1번 */
    fun undo(ctx: Context) = play(ctx, longArrayOf(0, 600))

    /** 등록 안 된 버튼/오류 = 아주 짧게 3번 */
    fun error(ctx: Context) = play(ctx, longArrayOf(0, 50, 60, 50, 60, 50))
}
