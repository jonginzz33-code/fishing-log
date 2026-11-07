package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 🏆 미니게임 랭킹 (v4.2.5 · 199)
 *  - 서버 gameRank/{uid}: 이름 + 게임마다 { n 누적 마릿수, best 최대(cm 또는 g), bestSp 그 어종, day 오늘 날짜, dayN 오늘 마릿수 }
 *  - 기록은 내 폰에 먼저 쌓고, 한 마리 잡을 때마다 내 문서 하나만 덮어씀 (서버 쓰기 1번)
 *  - 랭킹은 창을 열 때만 받아옴 (읽기 아끼기)
 */
object GameRank {
    /** game: "bobber" 찌낚시 (cm) · "jj" 쭈꾸미 (g) */
    val GAMES = listOf("bobber" to "🎣 바다찌낚시", "jj" to "🐙 쭈꾸미")
    private fun unit(game: String) = if (game == "jj") "g" else "cm"
    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(Date())
    private fun prefs(c: Context) = c.getSharedPreferences("fishlog", Context.MODE_PRIVATE)

    /** 게임에서 한 마리 잡았을 때 (게임 화면 → FL.gameCatch) */
    fun report(c: Context, game: String, species: String, value: Int) {
        if (game !in GAMES.map { it.first }) return
        val p = prefs(c); val k = "gr_$game"; val d = today()
        val n = p.getInt("${k}_n", 0) + 1
        var best = p.getInt("${k}_best", 0); var bestSp = p.getString("${k}_bestSp", "") ?: ""
        if (species != "장화" && value > best) { best = value; bestSp = species }
        val dayN = if (p.getString("${k}_day", "") == d) p.getInt("${k}_dayN", 0) + 1 else 1
        p.edit().putInt("${k}_n", n).putInt("${k}_best", best).putString("${k}_bestSp", bestSp)
            .putString("${k}_day", d).putInt("${k}_dayN", dayN).apply()
        val me = Cloud.uid ?: return
        FirebaseFirestore.getInstance().collection("gameRank").document(me).set(mapOf(
            "name" to Cloud.displayName, "at" to System.currentTimeMillis(),
            game to mapOf("n" to n, "best" to best, "bestSp" to bestSp, "day" to d, "dayN" to dayN)
        ), SetOptions.merge())
    }

    /** 랭킹 창: 게임 고르기 · 많이 / 대어 / 오늘 */
    fun show(a: Activity, game0: String = "bobber") {
        fun dp(v: Int) = Ui.dp(a, v)
        var game = game0
        var tab = 0  // 0 많이 잡은 · 1 대어 · 2 오늘
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(10)) }
        val gameRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val tabRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val note = Ui.text(a, 11.5f, Ui.SUB, false, "")
        box.addView(gameRow); box.addView(tabRow, Ui.lp(a, MATCH, WRAP, top = 8))
        box.addView(list, Ui.lp(a, MATCH, WRAP, top = 8)); box.addView(note, Ui.lp(a, MATCH, WRAP, top = 8))
        lateinit var load: () -> Unit
        fun chip(t: String, on: Boolean, click: () -> Unit) = Ui.text(a, 13f, if (on) Ui.BG else Ui.TEXT, true, t).apply {
            gravity = Gravity.CENTER; minHeight = dp(36)
            background = if (on) Ui.bg(a, Ui.TEXT, 99) else Ui.bg(a, Color.TRANSPARENT, 99, Ui.LINE)
            setOnClickListener { click() }
        }
        fun paintChips() {
            gameRow.removeAllViews(); tabRow.removeAllViews()
            GAMES.forEachIndexed { i, (g, label) ->
                gameRow.addView(chip(label, g == game) { game = g; paintChips(); load() },
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) leftMargin = dp(6) })
            }
            listOf("많이 잡은", "🐟 대어", "오늘").forEachIndexed { i, t ->
                tabRow.addView(chip(t, i == tab) { tab = i; paintChips(); load() },
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) leftMargin = dp(6) })
            }
        }
        load = {
            list.removeAllViews()
            list.addView(Ui.text(a, 13f, Ui.SUB, false, "불러오는 중…"))
            val g = game; val t = tab; val d = today()
            val field = when (t) { 1 -> "$g.best"; 2 -> "$g.dayN"; else -> "$g.n" }
            FirebaseFirestore.getInstance().collection("gameRank")
                .orderBy(field, Query.Direction.DESCENDING).limit(if (t == 2) 60 else 30).get()
                .addOnSuccessListener { qs ->
                    if (g != game || t != tab) return@addOnSuccessListener
                    list.removeAllViews()
                    class Row(val uid: String, val name: String, val v: Long, val sub: String)
                    val rows = qs.documents.mapNotNull { doc ->
                        @Suppress("UNCHECKED_CAST")
                        val m = doc.get(g) as? Map<String, Any?> ?: return@mapNotNull null
                        val name = doc.getString("name")?.ifBlank { null } ?: "회원 ${doc.id.takeLast(4)}"
                        when (t) {
                            1 -> (m["best"] as? Number)?.toLong()?.takeIf { it > 0 }?.let { Row(doc.id, name, it, (m["bestSp"] as? String) ?: "") }
                            2 -> if (m["day"] == d) (m["dayN"] as? Number)?.toLong()?.let { Row(doc.id, name, it, "") } else null
                            else -> (m["n"] as? Number)?.toLong()?.let { Row(doc.id, name, it, "") }
                        }
                    }.take(20)
                    if (rows.isEmpty()) list.addView(Ui.text(a, 13f, Ui.SUB, false, if (t == 2) "오늘은 아직 아무도 안 잡았어요" else "아직 기록이 없어요 · 한 마리 잡아 보세요"))
                    rows.forEachIndexed { i, r ->
                        val mine = r.uid == Cloud.uid
                        val row = LinearLayout(a).apply {
                            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(10), dp(8), dp(12), dp(8))
                            background = Ui.bg(a, if (mine) Color.parseColor("#22324A") else Ui.CARD, 12, if (mine) Ui.BLUE else Color.TRANSPARENT)
                        }
                        row.addView(Ui.text(a, 15f, if (i < 3) Color.parseColor("#F2C14E") else Ui.SUB, true,
                            when (i) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "${i + 1}" }).apply { gravity = Gravity.CENTER },
                            LinearLayout.LayoutParams(dp(34), WRAP))
                        row.addView(Ui.text(a, 14.5f, Ui.TEXT, mine, r.name + if (mine) "  (나)" else "").apply { maxLines = 1 },
                            LinearLayout.LayoutParams(0, WRAP, 1f))
                        val value = when (t) { 1 -> "%,d".format(r.v) + unit(g) + (if (r.sub.isNotBlank()) " · ${r.sub}" else ""); else -> "${r.v}마리" }
                        row.addView(Ui.text(a, 14f, Color.parseColor("#F2C14E"), true, value))
                        list.addView(row, Ui.lp(a, MATCH, WRAP, top = 6))
                    }
                    note.text = when (t) { 1 -> "한 번에 잡은 가장 큰 고기 (" + (if (g == "jj") "무게 g" else "길이 cm") + ")"; 2 -> "오늘 잡은 마릿수"; else -> "지금까지 잡은 마릿수" } +
                        " · 이름은 닉네임으로 보여요"
                }
                .addOnFailureListener { e ->
                    list.removeAllViews()
                    list.addView(Ui.text(a, 13f, Color.parseColor("#FF8A92"), false,
                        if ((e.message ?: "").contains("PERMISSION", true)) "랭킹을 못 읽었어요 · Firestore 규칙에 gameRank 를 추가해 주세요"
                        else "랭킹을 못 불러왔어요: ${e.message?.take(80)}"))
                }
        }
        paintChips(); load()
        AlertDialog.Builder(a, R.style.FlAlert).setTitle("🏆 미니게임 랭킹")
            .setView(ScrollView(a).apply { addView(box) })
            .setNegativeButton("닫기", null).show()
    }
}
