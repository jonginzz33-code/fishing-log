package com.fishlog.app

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * "지금 들어와 있는 그룹" 띠: 그룹 이름(크게) + 낚시 종류
 *  - 메뉴 안, 공지·일정·그룹 출조 기록·그룹 관리 화면 맨 위
 *  - 종류마다 색·그림이 달라서 어느 그룹인지 한눈에
 */
object GroupBand {

    private val COLORS = listOf("#5FD39A", "#B9A6FF", "#5BC8DF", "#F2994A", "#F07AA0", "#F2C14E").map { Color.parseColor(it) }
    private val INKS = listOf("#08311F", "#26215C", "#06303A", "#3A1A04", "#4B1528", "#3A2A02").map { Color.parseColor(it) }

    private fun idx(cat: String): Int = Store.allCats().indexOfFirst { it.id == cat }.coerceAtLeast(0) % COLORS.size
    fun catColor(cat: String): Int = COLORS[idx(cat)]
    fun catInk(cat: String): Int = INKS[idx(cat)]
    fun catIcon(cat: String): String = when (cat) { "boat" -> "🚤"; "night" -> "🌙"; "walk" -> "🥾"; else -> "🎣" }

    /** 지금 그룹에서 쓰는 종류: 내가 고른 종류가 그룹 종류에 있으면 그것, 아니면 그룹의 첫 종류 */
    fun currentCat(g: Cloud.Group?): String {
        val types = g?.types.orEmpty().filter { it in Store.CATS }
        val sel = Store.current?.cat?.ifBlank { null } ?: Store.category
        return if (types.isEmpty() || sel in types) sel else types.first()
    }

    private fun groupLabel(g: Cloud.Group) = if (g.name.trim().endsWith("그룹")) g.name else "${g.name} 그룹"

    /**
     * 종류 색으로 꽉 찬 띠. big = 크게, filled = 그룹 창 맨 위용("지금 여기에서 설정 중이에요" + 역할)
     * onClick = 눌렀을 때 (null 이면 안 눌림)
     */
    fun view(c: Context, big: Boolean, onClick: (() -> Unit)? = null, filled: Boolean = false): View? {
        val g = (if (Cloud.user == null) null else Cloud.active) ?: return null
        val cat = currentCat(g)
        val col = catColor(cat)
        val ink = catInk(cat)
        fun dp(v: Int) = Ui.dp(c, v)
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(if (big) 14 else 10), dp(14), dp(if (big) 14 else 10))
            background = Ui.bg(c, col, 16)
            if (onClick != null) { isClickable = true; setOnClickListener { onClick() } }
        }
        box.addView(Ui.text(c, if (big) 30f else 22f, ink, true, catIcon(cat)).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(dp(if (big) 48 else 38), dp(if (big) 48 else 38)))
        val txt = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        if (filled) txt.addView(Ui.text(c, 12f, ink, true, "지금 여기에서 설정 중이에요").apply { alpha = 0.8f })
        txt.addView(Ui.text(c, if (big) 24f else 18f, ink, true, groupLabel(g)).apply { maxLines = 1 })
        val others = g.types.filter { it in Store.CATS && it != cat }
        val role = if (filled) (if (g.owner == Cloud.uid) " · 그룹장" else " · 그룹원") else ""
        txt.addView(Ui.text(c, 13f, ink, true,
            Store.catLabel(cat) + role + (if (others.isNotEmpty()) "  (그룹 종류: " + (listOf(cat) + others).joinToString(" · ") { Store.catShort(it) } + ")" else "")),
            Ui.lp(c, Ui.MATCH, Ui.WRAP, top = 2))
        box.addView(txt, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = dp(12) })
        if (onClick != null) box.addView(Ui.text(c, 12f, ink, true, "열기 ›"))
        return box
    }
}
