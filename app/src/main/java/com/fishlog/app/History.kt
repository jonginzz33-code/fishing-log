package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP

/**
 * 📋 업데이트 내역: 종류별 커튼 · 항목마다 버전 + 날짜 (v3.3부터 쌓음, 최신이 위)
 *  - 새 버전을 만들 때마다 ITEMS 맨 위에 추가
 */
object History {
    class Item(val ver: String, val date: String, val cat: String, val text: String)

    /** 커튼 순서 */
    private val CATS = listOf(
        "🎣 출조 기록 · 카드", "🌊 물때 · 날씨", "👥 그룹 · 그룹 출조 기록", "🟢 지금 출조 중 · 위치 공유",
        "📰 전체 조황 · 포인트 게시판", "🗺 지도 · 이동 경로", "💬 채팅 · 1:1 문의", "🎮 리모컨 · 워치",
        "⚙ 설정 · 설명서 · 앱", "🛠 관리자 · 서버 절약"
    )

    val ITEMS = listOf(
        Item("3.6", "2026.11.01", "📰 전체 조황 · 포인트 게시판", "포인트 게시판 목록을 지역별로 쪼개지 않고 출조 한 건씩 (언제~언제 · 어종 · 1시간 평균 순위, 누르면 지도 · 조과 · 사진 · 날씨)"),
        Item("3.6", "2026.11.01", "📰 전체 조황 · 포인트 게시판", "포인트 지도: '전체 포인트'만 켜도 내가 올린 공개 조과가 보이게 고침"),
        Item("3.6", "2026.11.01", "🎮 리모컨 · 워치", "기록 방식 리모컨 줄에 '(음악 리모컨 전용)' + ? 설명 (되는 리모컨 · 안 되는 리모컨 · 살 때 팁)"),
        Item("3.5", "2026.11.01", "📰 전체 조황 · 포인트 게시판", "포인트 게시판 '🗺 포인트 지도': ☑ 전체 포인트 · ☑ 나만의 포인트(안 올린 내 기록) + ⭕ 원 그려서 그 안 조과 분석 (어종별 잘 나온 물때 · 물세기 · 만조간조 · 시간대 · 수온 · 바람 · 파고 · 달)"),
        Item("3.5", "2026.11.01", "🟢 지금 출조 중 · 위치 공유", "위치 공유한 사람 지도에 그 사람 이동 경로 + 잡은 자리별 마릿수까지 (관리자가 권한 준 사람만) · 전체 공유는 '정확한 위치 · 조과 지도 공유'를 켠 사람만"),
        Item("3.5", "2026.11.01", "🎣 출조 기록 · 카드", "위치 튐 고침: 와이파이 · 기지국 · 오차 큰 위치는 경로 · 조과 보정에 안 씀, 순간이동 점 버림, 대략 위치 조과는 지도에서 흐리게 + 오차 원"),
        Item("3.5", "2026.11.01", "🎣 출조 기록 · 카드", "칭호: '첫 손맛'은 1마리부터 · 10 손맛 봤다 · 30 오늘 좀 되네 · 50 물 만났다 · 70 줄 터졌다(새로) · 100 백마리 클럽 …"),
        Item("3.5", "2026.11.01", "⚙ 설정 · 설명서 · 앱", "기록 방식 '배터리 절약' 옆 ? 버튼 — 켜면 무엇이 좋고 달라지는지 설명"),
        Item("3.5", "2026.11.01", "🛠 관리자 · 서버 절약", "회원 권한에 '실시간 조과 지도 보기' 추가 (Firestore 규칙 변경)"),
        Item("3.4", "2026.11.01", "🎣 출조 기록 · 카드", "조과 위치 정확도: GPS가 없으면 폰 위치로 일단 저장(대략) → 누른 뒤 1분 동안 GPS를 2초마다 재서 정확한 위치로 바꿈 · 그래도 없으면 이동 경로 앞뒤 점으로 다시 계산"),
        Item("3.4", "2026.11.01", "🎮 리모컨 · 워치", "리모컨 버튼 1~8번: 번호마다 [리모컨 버튼]을 누르고 실제 버튼을 눌러 등록 · 이름 붙이기 · 지우기 · 리모컨 N번 = N번째 카드"),
        Item("3.4", "2026.11.01", "🌊 물때 · 날씨", "물때 창에 가까운 관측소의 실측 조류 (유향 · 유속 cm/s · 노트 · 세기)"),
        Item("3.4", "2026.11.01", "🟢 지금 출조 중 · 위치 공유", "이름 옆 꼬리표로 어디에 알리는 중인지 (그룹 이름 · 전체) · 목록에 위치 공유 여부"),
        Item("3.4", "2026.11.01", "🗺 지도 · 이동 경로", "위치 기록이 없는 출조: 안내를 지도 맨 위로 · 지도는 한국 전체 말고 내 현재 위치로"),
        Item("3.4", "2026.11.01", "👥 그룹 · 그룹 출조 기록", "그룹장 카드에서 '✎ 이름' 버튼이 대표 이미지 버튼과 겹쳐 보이던 것 고침"),
        Item("3.4", "2026.11.01", "⚙ 설정 · 설명서 · 앱", "사용 설명서를 기능별 커튼으로 · 업데이트 내역(이 화면) 추가"),
        Item("3.3", "2026.11.01", "🎣 출조 기록 · 카드", "어종 그림 70종을 새 그림으로 (갈치 · 돌돔 · 숭어 · 붕어 · 바지락 · 박하지 등 새로) · 해마 · 불가사리 그림 삭제"),
        Item("3.3", "2026.11.01", "⚙ 설정 · 설명서 · 앱", "앱 아이콘을 바닷속 꽃게 · 문어 · 갑오징어 · 소라게 그림으로")
    )

    fun show(a: Activity) {
        fun dp(v: Int) = Ui.dp(a, v)
        val now = BuildConfigInfo.VERSION
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(4), dp(14), dp(10)) }
        col.addView(Ui.text(a, 12f, Ui.SUB, false, "지금 버전 v$now · 종류를 누르면 펼쳐져요 (최신이 위)").apply { gravity = Gravity.CENTER },
            Ui.lp(a, MATCH, WRAP, top = 2))
        var first = true
        CATS.forEach { cat ->
            val list = ITEMS.filter { it.cat == cat }
            if (list.isEmpty()) return@forEach   // 아직 내역이 없는 종류는 숨김
            val card = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.bg(a, Ui.CARD, 14, Ui.LINE)
            }
            val head = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(12), dp(12)); isClickable = true
            }
            head.addView(Ui.text(a, 15f, Ui.TEXT, true, cat), LinearLayout.LayoutParams(0, WRAP, 1f))
            head.addView(Ui.text(a, 12f, Ui.SUB, false, "${list.size}개"))
            val arrow = Ui.text(a, 14f, Ui.SUB, true, "▸")
            head.addView(arrow, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
            card.addView(head)
            val inner = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(10), dp(8)) }
            list.forEach { it0 ->
                val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(7), dp(4), dp(7)) }
                val newest = it0.ver == now
                val tag = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
                tag.addView(Ui.text(a, 11f, if (newest) Color.parseColor("#04342C") else Color.parseColor("#BFE0FF"), true, "v${it0.ver}").apply {
                    gravity = Gravity.CENTER; setPadding(dp(6), dp(1), dp(6), dp(1))
                    background = Ui.bg(a, if (newest) Ui.GREEN else Color.parseColor("#2B4A6B"), 8)
                })
                tag.addView(Ui.text(a, 9.5f, Ui.SUB, false, it0.date).apply { gravity = Gravity.CENTER }, Ui.lp(a, WRAP, WRAP, top = 2))
                r.addView(tag, LinearLayout.LayoutParams(dp(70), WRAP))
                r.addView(Ui.text(a, 13f, Ui.TEXT, false, it0.text).apply { setLineSpacing(0f, 1.25f) },
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
                inner.addView(r)
            }
            inner.visibility = if (first) View.VISIBLE else View.GONE
            arrow.text = if (first) "▾" else "▸"
            first = false
            head.setOnClickListener {
                val open = inner.visibility != View.VISIBLE
                inner.visibility = if (open) View.VISIBLE else View.GONE
                arrow.text = if (open) "▾" else "▸"
            }
            card.addView(inner)
            col.addView(card, Ui.lp(a, MATCH, WRAP, top = 8))
        }
        AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("📋 업데이트 내역")
            .setView(ScrollView(a).apply { addView(col) })
            .setPositiveButton("닫기", null).show()
    }
}
