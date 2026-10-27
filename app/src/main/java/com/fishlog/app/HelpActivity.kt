package com.fishlog.app

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.fishlog.app.Ui.MATCH
import com.fishlog.app.Ui.WRAP

/** 사용 설명서: 기능마다 간단한 그림 + 설명 */
class HelpActivity : Activity() {

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        val col = Ui.column(this)
        val top = Ui.row(this)
        top.addView(Ui.button(this, "← 뒤로") { finish() }.apply { minHeight = dp(44) }, LinearLayout.LayoutParams(WRAP, WRAP))
        top.addView(Ui.text(this, 20f, Ui.TEXT, true, "사용 설명서"), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(14) })
        col.addView(top)

        section(col, HelpPic.REMOTE, "1. 출조 시작 · 리모컨으로 기록",
            "• '출조 시작'을 누르고 배·항구를 적어요 (한 번 적으면 다음부터 목록에서 골라요).\n" +
            "• 블루투스 리모컨 버튼 2개를 '리모컨 버튼 등록'으로 1번·2번에 정해요.\n" +
            "• 1번 = 주황 카드 어종, 2번 = 파란 카드 어종. 누를 때마다 +1, 시각이 초 단위로 저장돼요.\n" +
            "• 화면을 꺼도 기록돼요. 진동: 1번은 짧게 1번, 2번은 짧게 2번.\n" +
            "• 버튼을 1초 길게 누르면 마지막 기록 취소.\n" +
            "• 리모컨이 없으면 '터치로 기록'을 켜고 카드를 눌러요. 알림창의 +1 버튼도 돼요.\n" +
            "• 갤럭시 워치: '갤럭시 워치로 기록'을 켜고, 출조 중에 워치의 미디어 컨트롤러(음악) 화면에서 ⏮ (왼쪽) = 1번, ⏭ (오른쪽) = 2번을 눌러요. 워치 화면에 지금 마릿수가 보여요. (워치 옆 버튼은 삼성이 막아 둬서 쓸 수 없어요)")

        section(col, HelpPic.FISH, "2. 낚시 종류와 어종",
            "• 위쪽에서 선상낚시 · 해루질 · 워킹낚시를 골라요 (그룹에 정해진 종류만 보여요).\n" +
            "• 카드의 흰 칸(어종 이름)을 누르면 목록에서 어종을 바꿔요. 출조 중에도, 끝난 뒤에도 돼요.\n" +
            "• 어종 목록 추가·삭제는 관리자만 할 수 있고, 모두의 앱에 똑같이 들어가요.")

        section(col, HelpPic.TIDE, "3. 물때 · 만조·간조 알람",
            "• '물때' 버튼: 물높이 그래프와 만조·간조 시각 (국립해양조사원 예보). 위쪽 ◀ ▶ 또는 날짜를 눌러 원하는 날을 볼 수 있어요.\n" +
            "• 같은 화면 아래에 그날 바람(방향·풍속), 파고, 수온이 3시간마다 나와요. 바람은 15일 뒤까지, 파도·수온 예측은 7일 뒤까지 볼 수 있어요.\n" +
            "• 물때(몇 물)는 실제 그믐 시각으로 계산해요 (서해 7물때식).\n" +
            "• '물때 지역'을 누르면 다른 관측소를 직접 고를 수 있어요. 기본은 가장 가까운 곳.\n" +
            "• '설정 (만조·간조 알람)'에서 10~60분 전을 고르면, 출조 중일 때 소리·진동으로 알려줘요.\n" +
            "• 출조를 시작하면 물때를 미리 받아 둬서 바다에서 인터넷이 안 돼도 보여요.\n" +
            "• 물세기: 오늘 조차가 가까운 사리(7물) 때의 몇 %인지 보여줘요. 물때 그래프 아래 막대는 시간별 물흐름 세기예요 (높고 주황일수록 물살이 셈, 초록 테두리 = 지금).")

        section(col, HelpPic.MAP, "4. 지도",
            "• '지도' 버튼: 이동 경로와 고기 잡은 자리를 점으로 보여줘요. 겹친 점은 숫자로 묶여요.\n" +
            "• 출조 중에는 내 위치가 작은 배 모양 (뱃머리 = 가는 방향).\n" +
            "• 지난 기록 겹치기로 내 예전 포인트와 그룹 친구 포인트도 반투명으로 볼 수 있어요.\n" +
            "• 이동 거리(km·해리)와 출발지에서의 직선거리도 표시돼요.\n" +
            "• 모든 지도에 지금 내 위치가 파란 점으로 나와요 (뾰족한 쪽 = 폰이 향한 방향). 포인트가 있는 지도는 점선과 '북동쪽 320m'처럼 방향·거리를 보여줘서 그대로 걸어가면 돼요.\n" +
            "• '내 위치로'를 누르면 내 위치로 지도가 옮겨가요.")

        section(col, HelpPic.PHOTO, "5. 사진 올리기",
            "• 출조 기록 화면에서 '사진 추가'로 여러 장을 한 번에 골라요.\n" +
            "• 사진을 찍은 시각을 보고, 그 시각에 가장 가까운 조과 옆에 작은 사진이 자동으로 붙어요.\n" +
            "• 그룹에 올리면 친구들도 그 사진을 볼 수 있고, 누르면 크게 확대돼요.")
        tip(col, "꿀팁: 잘 먹히는 에기(색·무게)가 있으면 그 시간대에 에기 사진을 한 장 찍어 두세요. " +
            "나중에 사진을 올리면 그 시간 조과 옆에 에기 사진이 붙어서 \"몇 시에 어떤 에기로 잘 나왔는지\" 한눈에 보여요.")

        section(col, HelpPic.GROUP, "6. 그룹 · 친구와 공유",
            "• 구글 로그인 → 그룹 만들기, 또는 친구가 보낸 초대 코드로 가입.\n" +
            "• 출조를 끝내면 그룹에 자동으로 올라가요 (0마리면 안 올라감). 경로 숨기기도 가능.\n" +
            "• 그룹 화면: 멤버, 출조 달력, 날짜별 출조, 같이 간 사람 비교.\n" +
            "• 여러 그룹에 가입했으면 메인 맨 위 '조과기록' 옆 그룹 이름을 옆으로 밀어 보고 눌러서 바꿔요.\n" +
            "• 그룹장(왕관)은 이름·종류·공지 수정, 초대 코드 새로 만들기, 멤버 내보내기, 멤버 출조 내리기, 그룹장 넘겨주기, 그룹 삭제를 할 수 있어요.")

        section(col, HelpPic.CAL, "7. 그룹 일정 · 지난 출조",
            "• '그룹 일정 확인': 몇 월 며칠, 몇 시, 어디로 가는지 올리고 다 같이 봐요. 그룹원 누구나 올릴 수 있어요.\n" +
            "• '지난 출조 · 내 기록 보관함': 전체 보기 또는 날짜별 보기(작은 달력, 년도·월 선택).\n" +
            "• 기록은 폰에 저장되고, 로그인하면 서버에도 백업돼서 폰을 바꿔도 복원돼요.\n" +
            "• 출조를 종료할 때 이용료(무료/유료·금액)와 꼭 필요한 물건을 적어 둘 수 있어요. 같은 배·항구로 다음에 가면 미리 채워져요. 출조 기록 화면에서 고칠 수 있어요.\n" +
            "• 금액과 준비물은 내 기록과 그룹에만 보이고, 전체 조황에는 '유료' 여부만 올라가요. 해루질 유료 자리는 지도에 작게 '유료'라고 표시돼요.\n" +
            "• 지난 출조 카드 오른쪽 위 배지: 초록 '그룹 (N)' = N개 그룹에 공유, 주황 '전체' = 전체 게시판 공개, 회색 '나만' = 나만 봄.\n" +
            "• '그룹 (N)'을 누르면 어느 그룹에 공유했는지 나오고, '공유 그룹 바꾸기'로 그룹을 더하거나 뺄 수 있어요.")

        section(col, HelpPic.MAP, "8. 전체 조황 · 포인트 도감",
            "• 출조를 종료할 때 '전체 게시판에도 공개'를 체크하면, 앱을 쓰는 모든 사람이 볼 수 있는 게시판에 올라가요 (기본은 꺼짐).\n" +
            "• 올라가는 건 날짜·종류·어종별 마릿수·시각·물때·위치예요. 이동 경로와 배 이름은 안 올라가요.\n" +
            "• 위치는 공개할 때 골라요: '정확한 위치'는 잡은 자리에 점이 찍히고, '대략적으로만'은 반경 약 500m 원으로만 보여요. 나중에 바꿀 수 있어요.\n" +
            "• 사진은 따로 고를 때만 작은 사진으로 올라가요. 이름은 닉네임이나 익명으로 보여요.\n" +
            "• '포인트 도감'에서 어종을 고르면 잘 나온 자리 순위(1시간당 마릿수)와 잘 나온 물때·시간대·달을 볼 수 있어요.\n" +
            "• 같은 어종도 낚시 종류가 다르면 따로 집계돼요. 예: 쭈꾸미 (선상낚시), 쭈꾸미 (해루질).\n" +
            "• 공개 출조를 누르면 지도에 잡은 자리와 내 위치가 같이 나와요. '크게 · 길찾기'로 큰 지도에서 따라가요.\n" +
            "• 장난 기록이 보이면 그 글을 눌러 '이 사람 차단'. 익명이어도 그 사람 글이 내 조황·포인트 계산에서 모두 빠져요. '차단 목록'에서 해제할 수 있어요.\n" +
            "• 여러 사람이 차단한 사람은 카드에 '⚠ 신고 N'이 보이고, 기준 수 이상이면 모든 사람의 집계에서 빠져요.\n" +
            "• 공개는 출조 기록 화면에서 언제든 내릴 수 있어요.")

        section(col, HelpPic.UPDATE, "9. 업데이트",
            "• 새 버전이 나오면 맨 위에 초록 줄이 떠요. 누르면 바로 설치돼요. 기록은 그대로 남아요.")

        col.addView(Ui.text(this, 11f, Ui.SUB, false, "버전 ${BuildConfigInfo.VERSION}"), Ui.lp(this, MATCH, WRAP, top = 20))
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(col) })
    }

    private fun section(col: LinearLayout, pic: Int, title: String, body: String) {
        val c = Ui.card(this)
        val r = Ui.row(this)
        r.addView(HelpPic(this, pic), LinearLayout.LayoutParams(dp(64), dp(64)))
        r.addView(Ui.text(this, 17f, Ui.TEXT, true, title), LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(12) })
        c.addView(r)
        c.addView(Ui.text(this, 14f, Ui.TEXT, false, body).apply { setLineSpacing(0f, 1.35f) }, Ui.lp(this, MATCH, WRAP, top = 10))
        col.addView(c, Ui.lp(this, MATCH, WRAP, top = 14))
    }

    private fun tip(col: LinearLayout, s: String) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.bg(this@HelpActivity, Color.parseColor("#2A2310"), 14, Ui.ORANGE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        c.addView(HelpPic(this, HelpPic.EGI), LinearLayout.LayoutParams(dp(56), dp(56)))
        c.addView(Ui.text(this, 14f, Color.parseColor("#FFE2C2"), false, s).apply { setLineSpacing(0f, 1.3f) },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(12) })
        col.addView(c, Ui.lp(this, MATCH, WRAP, top = 8))
    }
}

/** 설명서용 간단한 그림 */
class HelpPic(c: Context, private val kind: Int) : View(c) {
    companion object { const val REMOTE = 0; const val FISH = 1; const val TIDE = 2; const val MAP = 3; const val PHOTO = 4; const val GROUP = 5; const val CAL = 6; const val UPDATE = 7; const val EGI = 8 }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val u = w / 64f
        fill.color = Color.parseColor("#1C3A4F")
        cv.drawRoundRect(RectF(0f, 0f, w, h), 16 * u, 16 * u, fill)
        line.strokeWidth = 3 * u
        when (kind) {
            REMOTE -> {
                fill.color = Color.parseColor("#2E4F66"); cv.drawRoundRect(RectF(20 * u, 8 * u, 44 * u, 56 * u), 10 * u, 10 * u, fill)
                fill.color = Ui.ORANGE; cv.drawCircle(32 * u, 22 * u, 7 * u, fill)
                fill.color = Ui.BLUE; cv.drawCircle(32 * u, 40 * u, 7 * u, fill)
            }
            FISH, EGI -> {
                fill.color = if (kind == EGI) Ui.ORANGE else Ui.BLUE
                val p = Path().apply {
                    moveTo(10 * u, 32 * u); quadTo(28 * u, 14 * u, 46 * u, 32 * u); quadTo(28 * u, 50 * u, 10 * u, 32 * u); close()
                    moveTo(44 * u, 32 * u); lineTo(56 * u, 22 * u); lineTo(56 * u, 42 * u); close()
                }
                cv.drawPath(p, fill)
                if (kind == EGI) { // 에기 줄무늬 + 바늘
                    line.color = Color.parseColor("#7A3A10")
                    for (x in listOf(20, 28, 36)) cv.drawLine(x * u, 26 * u, x * u, 38 * u, line)
                    line.color = Color.WHITE; line.strokeWidth = 2 * u
                    cv.drawLine(10 * u, 32 * u, 5 * u, 28 * u, line); cv.drawLine(10 * u, 32 * u, 5 * u, 36 * u, line)
                } else {
                    fill.color = Ui.BG; cv.drawCircle(19 * u, 30 * u, 2.5f * u, fill)
                }
            }
            TIDE -> {
                line.color = Ui.BLUE
                val p = Path().apply { moveTo(6 * u, 36 * u); cubicTo(18 * u, 10 * u, 26 * u, 10 * u, 32 * u, 32 * u); cubicTo(38 * u, 54 * u, 46 * u, 54 * u, 58 * u, 28 * u) }
                cv.drawPath(p, line)
                line.color = Ui.GREEN; line.strokeWidth = 2 * u
                cv.drawLine(40 * u, 10 * u, 40 * u, 54 * u, line)
            }
            MAP -> {
                line.color = Ui.SUB; line.strokeWidth = 2.5f * u
                val p = Path().apply { moveTo(10 * u, 52 * u); quadTo(18 * u, 24 * u, 32 * u, 34 * u); quadTo(46 * u, 44 * u, 54 * u, 12 * u) }
                cv.drawPath(p, line)
                fill.color = Ui.GREEN; cv.drawCircle(10 * u, 52 * u, 4 * u, fill)
                fill.color = Ui.ORANGE; cv.drawCircle(30 * u, 33 * u, 6 * u, fill)
                fill.color = Ui.BLUE; cv.drawCircle(48 * u, 30 * u, 5 * u, fill)
            }
            PHOTO -> {
                fill.color = Color.parseColor("#2E4F66"); cv.drawRoundRect(RectF(10 * u, 18 * u, 54 * u, 50 * u), 6 * u, 6 * u, fill)
                fill.color = Color.parseColor("#2E4F66"); cv.drawRect(24 * u, 12 * u, 40 * u, 20 * u, fill)
                fill.color = Ui.BLUE; cv.drawCircle(32 * u, 34 * u, 9 * u, fill)
                fill.color = Ui.BG; cv.drawCircle(32 * u, 34 * u, 4 * u, fill)
            }
            GROUP -> {
                fill.color = Ui.ORANGE; cv.drawCircle(22 * u, 24 * u, 7 * u, fill); cv.drawRoundRect(RectF(11 * u, 34 * u, 33 * u, 52 * u), 8 * u, 8 * u, fill)
                fill.color = Ui.BLUE; cv.drawCircle(42 * u, 24 * u, 7 * u, fill); cv.drawRoundRect(RectF(31 * u, 34 * u, 53 * u, 52 * u), 8 * u, 8 * u, fill)
            }
            CAL -> {
                fill.color = Color.parseColor("#2E4F66"); cv.drawRoundRect(RectF(10 * u, 14 * u, 54 * u, 52 * u), 6 * u, 6 * u, fill)
                fill.color = Ui.ORANGE; cv.drawRect(10 * u, 14 * u, 54 * u, 22 * u, fill)
                fill.color = Ui.SUB
                for (r in 0..2) for (k in 0..3) cv.drawCircle((17 + k * 10) * u, (30 + r * 8) * u, 2 * u, fill)
                fill.color = Ui.GREEN; cv.drawCircle(37 * u, 38 * u, 4 * u, fill)
            }
            UPDATE -> {
                line.color = Ui.GREEN
                cv.drawArc(RectF(14 * u, 14 * u, 50 * u, 50 * u), 30f, 300f, false, line)
                fill.color = Ui.GREEN
                val p = Path().apply { moveTo(46 * u, 12 * u); lineTo(52 * u, 26 * u); lineTo(38 * u, 24 * u); close() }
                cv.drawPath(p, fill)
            }
        }
    }
}
