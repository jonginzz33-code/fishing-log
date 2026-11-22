package com.fishlog.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * v6.61 (340): 🔊 리모컨으로 셀 때 폰 소리
 *  - 소리는 앱이 직접 만들어 냄 (파일 없음 · 저작권 없음) · 미디어 음량을 따라감
 *  - +1 = 고른 소리 · 취소(−1) = 낮은 '뚱'
 *  - '번호마다 다르게' 켜면 2번 이상 카드는 두 번 울림
 *  - prefs cnt_snd (켬/끔 · 처음 꺼짐) · cnt_snd_kind (0 띵 · 1 뽀롱 · 2 딸랑 · 3 퐁당) · cnt_snd_multi
 */
object CountSound {
    val KINDS = listOf("🔔 띵", "🎵 뽀롱", "🪙 딸랑", "💧 퐁당")
    private const val RATE = 22050

    private fun prefs(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)
    fun on(c: Context) = prefs(c).getBoolean("cnt_snd", false)
    fun setOn(c: Context, v: Boolean) = prefs(c).edit().putBoolean("cnt_snd", v).apply()
    fun kind(c: Context) = prefs(c).getInt("cnt_snd_kind", 0).coerceIn(0, KINDS.size - 1)
    fun setKind(c: Context, k: Int) = prefs(c).edit().putInt("cnt_snd_kind", k).apply()
    fun multi(c: Context) = prefs(c).getBoolean("cnt_snd_multi", false)
    fun setMulti(c: Context, v: Boolean) = prefs(c).edit().putBoolean("cnt_snd_multi", v).apply()
    fun kindName(c: Context) = KINDS[kind(c)].substringAfter(" ")

    /** 리모컨으로 한 마리 셈 */
    fun count(c: Context, slot: Int) {
        if (!on(c)) return
        val one = make(kind(c))
        play(if (multi(c) && slot >= 2) one + silence(0.09) + one else one)
    }

    /** 리모컨 길게 = 마지막 취소 */
    fun undo(c: Context) {
        if (!on(c)) return
        play(tone(330.0, 0.28, 9.0) + silence(0.02))
    }

    fun preview(k: Int) = play(make(k))

    // ── 소리 만들기 ──
    private fun silence(sec: Double) = ShortArray((RATE * sec).toInt())

    /** 사인파 + 조금의 배음 · 지수로 줄어듦 */
    private fun tone(freq: Double, sec: Double, decay: Double, vol: Double = 0.55): ShortArray {
        val n = (RATE * sec).toInt()
        return ShortArray(n) { i ->
            val t = i.toDouble() / RATE
            val attack = (i / (RATE * 0.004)).coerceAtMost(1.0)
            val env = attack * exp(-decay * t)
            val v = sin(2 * PI * freq * t) * 0.8 + sin(2 * PI * freq * 2 * t) * 0.2
            (v * env * vol * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /** 높낮이가 미끄러지는 소리 (퐁당) */
    private fun sweep(f0: Double, f1: Double, sec: Double, decay: Double): ShortArray {
        val n = (RATE * sec).toInt()
        var ph = 0.0
        return ShortArray(n) { i ->
            val t = i.toDouble() / RATE
            val f = f0 + (f1 - f0) * (t / sec)
            ph += 2 * PI * f / RATE
            val attack = (i / (RATE * 0.003)).coerceAtMost(1.0)
            (sin(ph) * attack * exp(-decay * t) * 0.6 * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun make(k: Int): ShortArray = when (k) {
        1 -> tone(880.0, 0.11, 14.0) + tone(1318.5, 0.26, 10.0)
        2 -> tone(1975.5, 0.07, 18.0, 0.45) + tone(2637.0, 0.32, 8.0, 0.45)
        3 -> sweep(1100.0, 320.0, 0.2, 9.0)
        else -> tone(1568.0, 0.4, 7.5)
    }

    private val main = Handler(Looper.getMainLooper())

    private fun play(data: ShortArray) {
        Thread {
            try {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(data.size * 2)
                    .build()
                track.write(data, 0, data.size)
                track.play()
                val ms = data.size * 1000L / RATE + 300
                main.postDelayed({ try { track.stop() } catch (_: Exception) {}; try { track.release() } catch (_: Exception) {} }, ms)
            } catch (_: Exception) {
            }
        }.start()
    }

    // ── 꾹 눌렀을 때: 소리 고르기 ──
    fun openSheet(a: Activity, changed: () -> Unit) {
        fun dp(v: Int) = Ui.dp(a, v)
        val sh = Sheet(a, 0.62f)
        sh.head.addView(Ui.text(a, 17f, Ui.TEXT, true, "🔊 조과 소리"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        sh.body.addView(box)
        fun paint() {
            box.removeAllViews()
            fun sw(title: String, sub: String, v: Boolean, click: () -> Unit) = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(10), dp(4), dp(10))
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.text(a, 15f, Ui.TEXT, true, title))
                    addView(Ui.text(a, 12f, Ui.SUB, false, sub))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(a).apply {
                    text = if (v) "켬" else "끔"; gravity = Gravity.CENTER; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (v) Color.parseColor("#0B2A17") else Color.parseColor("#C9D3DE"))
                    background = if (v) Ui.bg(a, Color.parseColor("#43C46B"), 99) else Ui.bg(a, Color.parseColor("#2A3E4F"), 99)
                }, LinearLayout.LayoutParams(dp(56), dp(30)))
                setOnClickListener { click() }
            }
            box.addView(sw("리모컨으로 셀 때 소리", "리모컨 · 워치로 센 것만 울려요 (터치는 안 울림)", on(a)) { setOn(a, !on(a)); paint(); changed() })
            box.addView(Ui.text(a, 14f, Ui.TEXT, true, "소리 고르기  ").apply {
                append(android.text.SpannableStringBuilder("누르면 미리 들어요").apply {
                    setSpan(android.text.style.AbsoluteSizeSpan(11, true), 0, length, 0)
                    setSpan(android.text.style.ForegroundColorSpan(Ui.SUB), 0, length, 0)
                })
            }, Ui.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 8))
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
            KINDS.forEachIndexed { i, label ->
                val sel = i == kind(a)
                row.addView(TextView(a).apply {
                    text = label; gravity = Gravity.CENTER; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(9), 0, dp(9))
                    setTextColor(if (sel) Color.parseColor("#06303A") else Color.parseColor("#C9D3DE"))
                    background = if (sel) Ui.bg(a, Color.parseColor("#5BC8E8"), 99) else Ui.bg(a, Color.parseColor("#12293A"), 99, Color.parseColor("#3A5A70"))
                    setOnClickListener { setKind(a, i); preview(i); paint(); changed() }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = dp(6) })
            }
            box.addView(row, Ui.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 8))
            box.addView(sw("번호마다 다르게", "1번 = 한 번 · 2번 이상 = 두 번 · 주머니 속에서도 몇 번 눌렸는지 알게", multi(a)) {
                setMulti(a, !multi(a)); paint(); if (multi(a)) play(make(kind(a)) + silence(0.09) + make(kind(a)))
            }, Ui.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 8))
            box.addView(Ui.text(a, 12f, Ui.SUB, false,
                "• 취소(리모컨 길게)는 낮은 '뚱' 소리예요.\n• 크기는 폰 '미디어 음량'을 따라가요. 무음 · 진동 모드여도 미디어 음량이 켜져 있으면 울려요."),
                Ui.lp(a, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, top = 10))
        }
        paint()
        val ok = Ui.button(a, "확인", true) { sh.close() }
        sh.foot.visibility = android.view.View.VISIBLE
        sh.foot.addView(ok, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sh.show()
    }
}
