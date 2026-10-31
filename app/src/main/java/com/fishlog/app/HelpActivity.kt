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

        col.addView(Ui.button(this, "▶ 처음 안내 다시 보기 (그림 6장)", true) { Guide.show(this) }, Ui.lp(this, MATCH, WRAP, top = 14))
        col.addView(Ui.text(this, 12f, Ui.SUB, false, "처음이면 위 버튼으로 그림 안내를 먼저 보세요. 아래는 기능별 자세한 설명이에요."),
            Ui.lp(this, MATCH, WRAP, top = 6))

        section(col, HelpPic.REMOTE, "1. 출조 시작 · 카드 눌러 +1",
            "• '출조 시작'을 누르고 배·항구를 적어요 (한 번 적으면 다음부터 목록에서 골라요).\n" +
            "• 주황 카드 = 1번 어종, 파란 카드 = 2번 어종. 카드를 누를 때마다 +1, 시각이 초 단위로 저장돼요.\n" +
            "• 카드 오른쪽 위 연필(✎)을 누르면 어종을 바꿔요. 출조 중에도, 끝난 뒤에도 돼요.\n" +
            "• 화면을 꺼도 기록돼요. 알림창의 +1 버튼으로도 기록할 수 있어요.\n" +
            "• 출조 중엔 버튼이 빨간 '■ 출조 종료'로 바뀌어요.\n" +
            "• 출조 중 카드 아래 '같이 출조 중' 칸: 같이 출조한 그룹원의 마릿수가 실시간으로 나와요 (아래 두 번째 그림 ③). 끝낸 사람은 1시간 동안 '종료했어요'로 보여요.",
            R.drawable.guide_g1_start, R.drawable.guide_g7_live)

        section(col, HelpPic.REMOTE, "2. 리모컨 · 갤럭시 워치",
            "• 블루투스 리모컨 버튼 2개를 '리모컨 버튼 등록'으로 1번·2번에 정해요.\n" +
            "• 진동: 1번은 짧게 1번, 2번은 짧게 2번. 버튼을 1초 길게 누르면 마지막 기록 취소.\n" +
            "• 갤럭시 워치: '갤럭시 워치로 기록'을 켜고, 출조 중 워치의 미디어 컨트롤러(음악) 화면에서 ⏮ (왼쪽) = 1번, ⏭ (오른쪽) = 2번. 워치 화면에 지금 마릿수가 보여요. (워치 옆 버튼은 삼성이 막아 둬서 쓸 수 없어요)\n" +
            "• 워치는 연달아 빠르게 눌러도 누른 만큼 셀 수 있어요 (한 번에 3마리면 ⏮ 3번). 워치·블루투스가 늦으면 가끔 빠질 수 있으니 워치 화면 숫자로 확인하세요.")

        section(col, HelpPic.TIDE, "3. 물때 · 날씨 (시간별 / 주간)",
            "• '물때 보기 ›' 또는 물때 한 줄을 누르면: 물높이 그래프, 만조·간조, 일출·일몰 (국립해양조사원 예보). ◀ ▶ 또는 날짜를 눌러 다른 날을 봐요.\n" +
            "• 맨 위에서 관측소를 바꾸거나 검색(예: 무창포)하고, 만조·간조 알람(10~60분 전)을 정해요. 출조 중일 때 소리·진동으로 알려줘요.\n" +
            "• '시간별' 탭: 하늘, 기온, 바람(16방위·풍속 m/s·최대 돌풍), 파도(파향·파고 m·주기 s), 수온이 세로 목록으로 나와요 (기상청 단기예보). 내 위치 칸이 육지면 가까운 바다 칸의 기상청 파고를 써요.\n" +
            "• 날씨 그림: ⚡ 뇌우(낙뢰) · 💨 강풍 10노트↑ · 🌀 돌풍 20노트↑ · 🌧 비 · ☀ 맑음 …\n" +
            "• '주간 (7일)' 탭: 날마다 날씨·최고/최저 기온·바람·파고·물때·물세기 %. 하늘·기온·비·파고는 기상청(오늘~3일 뒤 단기예보, 그 뒤 중기예보)이고, 날짜 아래 초록 '기상청'으로 표시돼요. 파고 아래 작은 숫자는 기상청 해상예보 범위예요.\n" +
            "• 수온: 지난 시간은 근처 관측소의 '실측'(초록으로 강조), 앞으로는 '예상'이에요.\n" +
            "• 물세기: 오늘 조차가 사리 때의 몇 %인지. 그래프 아래 막대는 시간별 물흐름 세기 (높고 주황일수록 물살이 셈).\n" +
            "• '그래프에 보기' 칩으로 cm 눈금·만조·간조·일출·일몰·지금 표시를 켜고 꺼요 (고른 건 저장).\n" +
            "• 물때(몇 물)는 실제 그믐 시각으로 계산해요 (서해 7물때식). 출조를 시작하면 미리 받아 둬서 바다에서 인터넷이 안 돼도 보여요.", R.drawable.guide_g2_tide)

        section(col, HelpPic.GROUP, "4. 그룹 · 공지 · 일정",
            "• 구글 로그인 → 그룹 만들기, 또는 친구가 보낸 초대 코드로 가입.\n" +
            "• 메인 맨 아래 '그룹' 버튼을 누르면 큰 창이 열려요. 맨 위에 '지금 ○○ 그룹에서 쓰는 중'이 크게 보여요.\n" +
            "• 큰 창에서: 내 그룹 바꾸기, 출조 종류(선상·해루질·워킹·원투 …) 고르기, 공지, 일정, 그룹 출조 기록, 그룹 관리.\n" +
            "• 빨간 숫자 = 아직 안 본 새 공지·일정·출조 기록. '확인'이나 '모두 확인'을 누르면 사라지고, 새 글이 오면 바로 다시 떠요.\n" +
            "• 출조 전날엔 그룹 버튼과 일정 버튼이 초록으로 '내일 출조!'를 알려줘요.\n" +
            "• 일정은 그룹원 누구나 올리고 고치고 지울 수 있어요 (내가 쓴 것). 공지는 그룹장이 써요.\n" +
            "• 그룹 출조 기록: 기간·사람별로 모아 보고, 어종별 합계와 많이 잡은 사람 순위. 새로 올라온 기록은 강조돼요.\n" +
            "• 그룹장(왕관): 이름·공지 수정, 우리 그룹 출조 종류 추가·빼기, 초대 코드 새로 만들기, 멤버 내보내기, 멤버 출조 내리기, 그룹장 넘겨주기, 그룹 삭제.", R.drawable.guide_g3_group)

        section(col, HelpPic.GROUP, "5. 같이 출조 중 (실시간)",
            "• 같은 그룹 사람이 같이 출조 중이고 가까우면 (기본 1km, ⚙ 개인 설정에서 바꿈), 카드 아래에 그 사람 마릿수가 보여요.\n" +
            "• 잡으면 몇 초 안에 서로 반영돼요. '3분 전'처럼 언제 바뀌었는지도 나와요.\n" +
            "• 출조를 끝낸 사람은 1시간 동안 '○○님은 출조를 종료했어요 · 시각 · 마릿수'로 보여요.\n" +
            "• 지도에도 같이 출조 중인 사람이 이름표와 함께 보여요. 종료한 사람은 흐리게.\n" +
            "• 친구가 어종 칭호를 넘으면 알림 (⚙에서 끌 수 있어요).", R.drawable.guide_g7_live, R.drawable.guide_g6_mates)

        section(col, HelpPic.CAL, "6. 출조 종료 · 기록 보기",
            "• 빨간 '■ 출조 종료'를 누르면 기록 화면이 열려요. 그룹에 자동으로 올라가요 (0마리면 안 올라감, 경로 숨기기 가능).\n" +
            "• 기록 화면에서 이용료(무료/유료·금액)와 준비물을 바로 적고 고쳐요. 같은 배·항구로 다음에 가면 미리 채워져요.\n" +
            "• 시간대별 요약: 몇 시에 몇 마리, 사진 몇 장인지 한눈에.\n" +
            "• 금액과 준비물은 내 기록과 그룹에만 보이고, 전체 조황에는 '유료' 여부만 올라가요.", R.drawable.guide_g4_end)

        section(col, HelpPic.CAL, "7. ☰ 메뉴 · 지난 출조",
            "• ☰ 메뉴: 개인 설정, 사용 설명서, 지난 출조, 전체 조황·포인트 도감, 지금 그룹, 경로 저장, 버전.\n" +
            "• 지난 출조: 날짜는 연도까지 (예: 2026년 10월 28일) + 옆에 '1년 6개월 전'처럼 작은 표시. 몇 년 전 같은 달이면 노랑.\n" +
            "• 여러 개 지우기: 지난 출조 오른쪽 위 '☑ 선택' → 체크(전체 선택·오늘 것만·이 날짜·10마리 이하) → '선택한 N개 삭제'. 폰·서버 백업·올린 그룹·전체 게시판에서 같이 지워져요.\n" +
            "• 기록은 폰에 저장되고, 로그인하면 서버에도 백업돼서 폰을 바꿔도 복원돼요.\n" +
            "• 카드 오른쪽 위 배지: 초록 '그룹 (N)' = N개 그룹에 공유, 주황 '전체' = 전체 게시판 공개, 회색 '나만'. '그룹 (N)'을 누르면 공유 그룹을 바꿀 수 있어요.", R.drawable.guide_g5_menu)

        section(col, HelpPic.MAP, "8. 지도",
            "• 왼쪽 위 '지도 (그룹 · 현위치)' 버튼: 이동 경로와 고기 잡은 자리를 점으로. 겹친 점은 숫자로 묶여요.\n" +
            "• 출조 중에는 내 위치가 작은 배 모양 (뱃머리 = 가는 방향). 같이 출조 중인 그룹원도 보여요.\n" +
            "• 지난 기록 겹치기로 내 예전 포인트와 그룹 친구 포인트를 반투명으로 봐요.\n" +
            "• 이동 거리(km·해리), 출발지에서의 직선거리, 포인트까지 '북동쪽 320m'처럼 방향·거리.\n" +
            "• '내 위치로'를 누르면 내 위치로 지도가 옮겨가요.")

        section(col, HelpPic.PHOTO, "9. 사진 올리기",
            "• 출조 기록 화면에서 '사진 추가'로 여러 장을 한 번에 골라요.\n" +
            "• 찍은 시각에 가장 가까운 조과 옆에 작은 사진이 자동으로 붙어요.\n" +
            "• 그룹에 올리면 친구들도 보고, 누르면 크게 확대돼요.")
        tip(col, "꿀팁: 잘 먹히는 에기(색·무게)가 있으면 그 시간대에 에기 사진을 한 장 찍어 두세요. " +
            "나중에 사진을 올리면 그 시간 조과 옆에 에기 사진이 붙어서 \"몇 시에 어떤 에기로 잘 나왔는지\" 한눈에 보여요.")

        section(col, HelpPic.MAP, "10. 전체 조황 · 포인트 도감",
            "• 출조를 끝낼 때 '전체 게시판에도 공개'를 체크하면 모든 사람이 보는 게시판에 올라가요 (기본은 꺼짐).\n" +
            "• 올라가는 건 날짜·종류·어종별 마릿수·시각·물때·위치. 이동 경로와 배 이름은 안 올라가요.\n" +
            "• 위치는 '정확한 위치' 또는 '대략적으로만'(반경 약 500m 원) 중에 골라요. 사진은 고를 때만, 이름은 닉네임이나 익명.\n" +
            "• '포인트 도감': 어종을 고르면 잘 나온 자리 순위(1시간당 마릿수)와 잘 나온 물때·시간대·달.\n" +
            "• 같은 어종도 출조 종류가 다르면 따로 집계돼요. 예: 쭈꾸미 (선상낚시), 쭈꾸미 (해루질).\n" +
            "• 장난 기록은 눌러서 '이 사람 차단'. 여러 사람이 차단하면 '⚠ 신고 N'이 보이고, 기준 이상이면 모든 집계에서 빠져요.\n" +
            "• 공개는 출조 기록 화면에서 언제든 내릴 수 있어요.")

        section(col, HelpPic.FISH, "11. ⚙ 개인 설정 · 닉네임 · 돌파 칭호",
            "• ☰ 메뉴 → '⚙ 개인 설정': 닉네임, 꺼진 화면·잠금 화면 표시, 친구 돌파 알림, 같이 출조 거리.\n" +
            "• 닉네임은 언제든 바로 바꿀 수 있어요 (다른 사람과 같은 이름은 안 돼요). 다른 사람에겐 구글 실명 대신 닉네임이 보여요.\n" +
            "• 어종마다 10·30·50·70·100·120·150·200·250·300마리를 넘으면 칭호·테두리·색종이·진동으로 축하해 줘요. 지난 출조엔 🏅 도장이 남아요.")

        section(col, HelpPic.TIDE, "12. 일출·일몰 · 입질 시간 메모",
            "• 물때 칸과 그래프에 일출·일몰이 보여요. 해 뜨기 전·해 진 뒤는 어둡게 칠해져요.\n" +
            "• 출조 기록 화면의 '입질 시간 메모': 어종마다 30분 안에 가장 많이 잡힌 때를 '일출 후 40분 ~ 1시간 10분'처럼.\n" +
            "• 지난 출조 맨 위 '어종별 낮·밤': 내 기록으로 낮 어종·밤 어종과 잘 잡힌 때 (출조 3번부터).")

        section(col, HelpPic.FISH, "13. 출조 종류 · 어종 (관리자)",
            "• 출조 종류(선상·해루질·워킹·원투 …)와 어종 목록은 관리자가 추가·이름 바꾸기·삭제를 하고, 모두의 앱에 똑같이 들어가요.\n" +
            "• 그룹장은 그중에서 우리 그룹에서 쓸 종류만 골라요. 종류가 하나면 자동으로 정해져요.")

        section(col, HelpPic.GROUP, "14. 💬 채팅 (전체 · 그룹)",
            "• 맨 위 지도 옆 '💬 채팅' 버튼: 아래 작은 글씨는 지금 앱을 켜 둔 사람 수예요.\n" +
            "• 채팅 창 맨 위 탭: '전체'(앱 쓰는 모든 사람) + 내가 가입한 그룹마다 하나씩. 초록 탭이 지금 보는 방이에요.\n" +
            "• 알림은 오지 않아요. 안 읽은 글은 채팅 버튼·탭·그룹 창의 '그룹 채팅'에 빨간(노란) 숫자로 보여요.\n" +
            "• 전체방은 '바꾸기 ✎'로 채팅 이름을 바꿀 수 있고, '닉네임으로'를 누르면 다시 닉네임으로 보여요. 그룹방은 닉네임 그대로예요.\n" +
            "• 욕설은 자동으로 ** 로 가려져요. 내 글은 꾹 눌러 수정·삭제, 남의 글은 꾹 눌러 신고·차단(내 화면에서 안 보임).\n" +
            "• 그룹장은 그룹방 글을 지울 수 있어요. 최근 3일 글만 보여요.\n" +
            "• 운영자는 채팅 이름을 바꾼 사람도 실제 닉네임이 보이고, 글 삭제·채팅 금지를 할 수 있어요.")

        section(col, HelpPic.GROUP, "15. ✉ 1:1 문의 · 🗑 휴지통 · 📦 재고표",
            "• 맨 위 '문의': 운영자에게 1:1 문의 (나와 운영자만 보는 비공개 방, 운영자가 지우기 전까지 보관). 답장이 오면 빨간 숫자.\n" +
            "• 🗑 휴지통 (☰ 메뉴): 운영자·그룹장이 내 조과를 지우면 알림이 오고, 30일 안에 복구할 수 있어요. 30일이 지나면 자동으로 버려져요.\n" +
            "• 📦 나만의 재고표 (☰ 메뉴): 품목 → 종류 → 호수. 누르면 펼쳐지고, −/＋로 수량을 바로 바꿔요. ✎ 수정 · 🗑 삭제.\n" +
            "• 그룹: 그룹 이름 옆 작고 흐린 AK1 같은 3자리 번호로 같은 이름 그룹을 구분해요. 그룹장은 대표 이미지를 올릴 수 있어요. 그룹원은 그룹 화면 맨 아래 빨간 '그룹 탈퇴'로 나갈 수 있어요 (그룹 채팅에 알림).\n" +
            "• 그룹 만들기는 운영자가 허용한 사람만 할 수 있어요 (✉ 문의로 요청). 초대 코드 가입은 누구나.\n" +
            "• 그룹 채팅방은 한 달 보관, 그룹장은 그룹 채팅방을 비울 수 있어요.\n" +
            "• 물때 그래프(오늘): '지금' 선이 가운데에 고정되고 앞뒤 12시간이 보여요. 손가락으로 밀면 앞뒤로 움직이고 '↺ 지금으로'로 돌아와요.\n" +
            "• 지난 출조·전체 조황: 카드를 꾹 누르면 체크박스가 생겨요. 맨 위 '전체 선택', 맨 아래 '선택한 N개 삭제(내리기)'.\n" +
            "• 처음 켤 때 위치·개인정보 동의가 나와요. ⚙ 개인 설정에서 다시 보고, '같이 출조 중 공유'는 끌 수 있어요.\n" +
            "• 설명서는 ☰ 메뉴의 '사용 설명서'에서 열어요.")

        section(col, HelpPic.GROUP, "16. 퀵 버튼 · 그룹 이미지 (v2.2)",
            "• 1·2번 큰 카드 아래 '＋ 어종 추가'로 어종 버튼을 원하는 만큼 만들어요 (해루질·원투용 5~6개 등). 출조 종류마다 따로 저장돼요.\n" +
            "• '⚙ 퀵 버튼 관리'에서 버튼마다 크기(작게·보통·크게)·색·순서(▲▼)를 바꾸고, ✎로 어종 변경, 🗑로 삭제해요.\n" +
            "• 퀵 버튼은 터치로만 기록해요 (리모컨·워치는 1·2번만). 꾹 누르면 그 어종 마지막 기록이 −1 돼요.\n" +
            "• 그룹장이 올린 대표 이미지가 메인 맨 위(조과기록 왼쪽), 그룹 창, 그룹 목록, 채팅 탭에 보여요. 이미지가 없으면 🚤 그림.\n" +
            "• 그룹 창의 '현재 그룹 변경'을 눌러 펼치면 그룹을 바꿀 수 있어요. 그룹원은 맨 아래 빨간 '그룹 탈퇴'로 나갈 수 있어요.\n" +
            "• 출조 종료 창에서 올릴 그룹을 하나씩 체크해 원하는 그룹에만 올려요 (기본: 지금 그룹).\n" +
            "• 재고표: 품목 → 종류 → 호수 3단계. 폰에 바로 저장되고, 폰 바꿀 때를 대비해 '서버에 저장하기'를 눌러 두세요.")

        section(col, HelpPic.GROUP, "17. 출조 시작·완료 · 그날 날씨 (v2.3)",
            "• 출조 시작 창에서 지금 그룹의 출조 종류를 눌러 고르고, 배 이름·포인트와 항구·주요 장소를 적어요.\n" +
            "• 출조 완료 창: 올릴 그룹을 체크하고, 전체 게시판에 올릴 땐 이름(익명/닉네임)과 함께 보일 그룹을 골라요. 아래 미리보기 그대로 올라가요.\n" +
            "• '취소'는 창만 닫고 출조는 계속돼요. 초록 '✓ 완료'를 누르면 출조가 끝나요.\n" +
            "• 완료할 때 그 시간대 날씨·바람·파고·수온(실측 포함)이 출조 기록에 저장돼요. 지난 출조 화면의 '그날 날씨'에서 언제든 다시 봐요.\n" +
            "• 지도의 '📍 내 위치 기록 확인'은 출조 없이 내가 움직인 길을 그려 보는 테스트 기능이에요. 앱을 닫거나 내리면 자동으로 꺼지고 지워져요.")

        section(col, HelpPic.GROUP, "18. 대표 사진 · 휴지통 · 수온 보정 (v2.4)",
            "• 출조 완료 창의 '📸 대표 조과 사진'에서 찍거나 앨범에서 골라요. 나중에 출조 기록 화면에서 바꾸거나 이 출조 사진 중 골라도 돼요.\n" +
            "• 대표 사진은 지난 출조·그룹 출조 기록·전체 조황 카드 오른쪽에 보이고, 누르면 크게 봐요.\n" +
            "• 내가 지운 조과도 휴지통(☰ 메뉴 → 🗑 휴지통)에 30일 보관돼요. '복구'하면 그룹·전체 게시판까지 다시 올라가요.\n" +
            "• 물때 창 수온: 실측 = 관측소에서 잰 값, 보정 = 예상에 그날 실측 차이를 더한 값, 예상 = 먼 바다 예측.\n" +
            "• 물때 그래프에서 낮은 밝게, 밤은 어둡게 보여요.")

        section(col, HelpPic.GROUP, "19. 지금 출조 중 · 게시판 (v2.5)",
            "• 그룹원이 출조 중이면 메인 그룹 줄과 그룹 창에 '🟢 지금 출조 중'으로 이름·종류·마릿수가 보여요. 누르면 지도.\n" +
            "• 출조 시작 창의 '내 출조 알리기'로 알리지 않기 / 지금 그룹만 / 내 그룹 모두를 골라요. 출조 중에도 메인의 초록 칩을 눌러 바꿀 수 있어요.\n" +
            "• 메인 맨 아래 '🌊 전체 조황 · 포인트 게시판'을 누르면 위쪽 탭으로 조황 게시판과 포인트 게시판을 오가요.\n" +
            "• 수온 칸 아래 작은 글씨는 실측한 관측소예요. 노란색이면 가장 가까운 관측소가 끊겨 다음 관측소 값을 쓴 거예요.")

        section(col, HelpPic.UPDATE, "20. 업데이트",
            "• 새 버전이 나오면 큰 창이 떠서 '이번에 바뀐 것'을 보여줘요. '지금 업데이트' → 창 안에서 받기(진행 %) → '설치하기'.\n" +
            "• 처음이면 '출처를 알 수 없는 앱' 허용을 켜고 돌아와 '설치하기'를 다시 눌러요. 기록은 그대로 남아요.\n" +
            "• '나중에'를 눌러도 ☰ 메뉴 맨 아래 버전 줄에 '새 버전 있음'이 보이고, 누르면 다시 창이 떠요.\n" +
            "• 관리자가 예전 버전을 막으면 업데이트해야 쓸 수 있어요. 출조 중엔 막지 않고, 출조가 끝난 뒤에 떠요.")

        col.addView(Ui.text(this, 11f, Ui.SUB, false, "버전 ${BuildConfigInfo.VERSION}"), Ui.lp(this, MATCH, WRAP, top = 20))
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(col) })
    }

    private fun section(col: LinearLayout, pic: Int, title: String, body: String, image: Int = 0, image2: Int = 0) {
        val c = Ui.card(this)
        val r = Ui.row(this)
        r.addView(HelpPic(this, pic), LinearLayout.LayoutParams(dp(64), dp(64)))
        r.addView(Ui.text(this, 17f, Ui.TEXT, true, title), LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(12) })
        c.addView(r)
        if (image != 0) c.addView(Guide.image(this, image), Ui.lp(this, MATCH, WRAP, top = 10))
        if (image2 != 0) c.addView(Guide.image(this, image2), Ui.lp(this, MATCH, WRAP, top = 8))
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
