# 조과일지 (fishing-log) 작업 설명서

안드로이드 낚시 조과 기록 앱. 코드는 `app/src/main/java/com/fishlog/app/` (Kotlin, 화면은 코드로 그림 · XML 레이아웃 거의 없음).

## 작업 규칙
- 사용자는 개발자가 아니에요. 답은 쉬운 한국어로 짧게.
- 플레이 콘솔 · Firebase · GitHub 등 **어떤 화면으로 가라고 할 때는 답 맨 위에 바로 가는 주소(링크)를 먼저** 줘요.
  - 주소는 **복사 버튼이 붙게 코드 블록(```)에 한 줄씩** 넣어서 줘요 (사용자가 복사해서 써요).
  - 플레이 콘솔 개발자 ID `7087559415015330997` (홈: https://play.google.com/console/u/0/developers/7087559415015330997/home)
  - 조과일지 스토어 앱 ID `4973437766947210735` (내부 테스트: https://play.google.com/console/u/0/developers/7087559415015330997/app/4973437766947210735/tracks/internal-testing)
  - **테스터 참여 링크** (그룹원에게 보내는 것): https://play.google.com/apps/internaltest/4700601757102863820 · 스토어 주소 https://play.google.com/store/apps/details?id=kr.jogwailji.app
  - Firebase 프로젝트 `fishing-log-9109d` (설정: https://console.firebase.google.com/project/fishing-log-9109d/settings/general)
  - GitHub 비밀값: https://github.com/jonginzz33-code/fishing-log/settings/secrets/actions
- 사용자가 **"만들어줘"** 라고 할 때만 코드를 만들어요. 그 전에는 메모(번호)로만 정리.
- 새 버전을 만들 때마다:
  - `app/build.gradle.kts` 의 `versionCode` +1, `versionName` 올리기
  - `README.md` **맨 끝**에 `## v새버전` + 바뀐 것 몇 줄 (GitHub 빌드가 이 마지막 부분을 앱 '이번에 바뀐 것'으로 보여줘요)
  - `History.kt` 의 `ITEMS` **맨 위**에 항목 추가 (앱 '업데이트 내역')
  - 설명서가 바뀌면 `HelpActivity.kt` 도 같이
- `main` 에 올리면 GitHub Actions(`.github/workflows/build.yml`)가 **스토어판 AAB만** 만들어 플레이 콘솔 내부 테스트에 자동으로 올려요. **GitHub판(APK)은 v6.8(89)에서 업데이트 중단** (v6.7 · v6.8은 백업 때문에 예외로 더 올림 · 예외로 올릴 땐 워크플로를 `git show c7ddb1c^:.github/workflows/build.yml` 로 잠깐 되돌렸다가 `c7ddb1c` 판으로 복구) (사용자 결정 2026-11-13) — Releases의 `fishing-log.apk`/`version.txt` 는 그대로 둬요. 메모만 바꿀 땐 커밋 메시지에 `[skip ci]`.
- 올리기 전에 빌드 확인: `gradle assembleGithubDebug assemblePlayDebug --no-daemon` (Android SDK 필요, `local.properties` 에 `sdk.dir`).

## 두 가지 판 (v6.0 · 242-2)
- `github`: 앱 안 업데이트 있음 — **v6.8에서 업데이트 중단**. 옮기기 창이 뜨면 폰 백업 파일(다운로드/조과일지/조과일지-백업-자동.zip) + 서버 백업 자동. 관리자 스위치 '📦 스토어로 옮기기'(서버 `config/app.storeMove`)를 켜면 옮기기 창만 떠요. 코드는 계속 빌드되게만 유지(`assembleGithubDebug`).
- `play`: 플레이 스토어용. 패키지 이름 **`kr.jogwailji.app`** (`com.fishlog.app`은 스토어에서 이미 다른 사람이 써서 · GitHub판과 폰에 같이 깔림 · Firebase에도 따로 등록함, `app/google-services.json`에 두 앱 다 있음). 앱 안 업데이트 · 설치 권한 없음 (`BuildConfig.SELF_UPDATE = false`, `app/src/play/AndroidManifest.xml`)

## 화면 (v6.0 · 242-1 · 243)
- 안드로이드 16(API 36) 기준이라 화면이 상태바 · 하단 버튼 밑까지 그려져요. 여백은 `Ui.edge()` 가 모든 액티비티(자동)와 전체 창(`Dialog` 만든 뒤 `Ui.edge(dlg.window)`)에 넣어요. **새 전체 창을 만들면 꼭 `Ui.edge` 호출.**
- 칸 크기를 화면 폭으로 계산할 땐 `displayMetrics.widthPixels` 대신 `Ui.contentW()` (펼친 폴드 · 태블릿은 가운데 600dp).
- 큰 AlertDialog 폭은 `Ui.dialogW()`, 체크박스는 `Ui.checkFit(CheckBox(...))`.

## 남은 메모
- ~~242-4 업로드 키 + AAB~~ — v6.2에서 함. 키는 `keystore/play-upload.p12.enc` (AES-256 · pbkdf2 200000), 비밀값 `PLAY_KEY_PASS` 로 풀어서 GitHub 빌드가 `bundlePlayRelease` → Releases `latest` 에 `jogwa-play-<versionCode>.aab`. 업로드 키 SHA-1 `D0:EA:F0:08:08:4B:DA:5F:27:06:0C:5D:24:12:BD:58:0A:30:4F:50` (Play 앱 서명 키 SHA-1은 플레이 콘솔에서 따로 → 둘 다 Firebase에 등록해야 스토어판 구글 로그인 됨)
- **스토어 진행 상황 (2026-11-13):** 내부 테스트 v6.2(83) 올림 · 본인 폰 설치 · 구글 로그인 OK (Firebase '조과일지 스토어'에 앱 서명 키 SHA-1 `4f:f0:f1:5e:...:86:2f` 등록). 새 버전 AAB는 GitHub 빌드가 비밀값 `PLAY_SERVICE_ACCOUNT_JSON`(서비스 계정 `play-upload@fishing-log-9109d.iam.gserviceaccount.com` · 2026-11-13 연결 확인됨)으로 내부 테스트에 자동 업로드 (`r0adkll/upload-google-play`, 실패해도 APK는 계속 · 같은 versionCode 다시 올리면 실패하는 게 정상). 비밀값 없으면 Releases의 AAB를 직접 올려야 함. 다음: 비공개 테스트 — 구글 그룹('누구나 가입 가능')을 테스터로 (내부 테스트는 이메일 목록만 됨).
- 스토어 준비물(올리기로 정하면): 개인정보처리방침 웹페이지 · 탈퇴 안내 페이지, 개발자 계정, 비공개 테스트 12명 14일, 리모컨 '음악 재생' 서비스 사용 이유 설명
- ~~244 게시판 글 조과 반영~~ — v6.1에서 함
- ~~245~~ 물세기 평균 방식 — v6.2에서 함. 예전 설명: 물세기 % 계산을 평균 방식으로 — 그날(0~24시) 이어진 만조↔간조 높이 차들의 **평균** ÷ 관측소 1년 중 가장 큰 사리 조차 (`Tide.kt` `strengthOf` · 지금은 그날 최고−최저라 세게 나옴). 예: 11/13 안흥 387·458·541 → 평균 462cm ≈ 65% ('물때와 날씨' 64%, 지금 앱 76%). 물때 시각 · 높이는 이미 같음.
- ~~246~~ (v6.3에서 함) 남의 '지금 출조 중' 지도(`MapDialog.showPerson`)에 내 지도(`MapDialog.show`)처럼 **'지난 기록'** 버튼 — 예전 조과 자리 반투명 점 겹치기 (내 기록만 / + 그룹 친구 / + 전체 게시판 3개월), 점 누르면 그날 상세. — 사용자 확인함 (이 방식 맞음). 다음 업데이트에 같이.
- ~~247~~ (v6.3에서 함) 사용 설명서(`HelpActivity.kt` '2. 리모컨' 섹션)에 리모컨 고르는 법을 자세히 — 리모컨 줄 ? 창(`MainActivity.kt` "🎮 어떤 리모컨이 되나요?") 내용 그대로: 되는 것(음악 · 미디어 리모컨, VOL± ⏮ ⏭ ⏯, 핸들용) · 안 되는 것(틱톡 · 쇼츠 넘기기, 셀카 셔터, 마우스 모드) · 살 때 팁('Music' 'Media' '볼륨' '이전 · 다음 곡') · 재생 버튼 길게 = 음성 비서 주의. 다른 곳(처음 안내 등)은 안 바꿈.
- ~~248~~ (v6.6에서 함 · 스위치는 관리자 페이지 '📦 스토어로 옮기기', 아직 꺼짐): GitHub판 **마지막 업데이트 v6.6** — 관리자 페이지 '스토어로 옮기기' 스위치(서버 `config/app` 에 `storeMove: true`) 켜면 GitHub판(`SELF_UPDATE=true`)은 닫을 수 없는 창: "플레이 스토어로 옮겼어요 · 이 버전은 더 이상 쓸 수 없어요" + ① 서버 백업 자동(`Cloud.backupPending`, 끝나면 ✓) ② [플레이 스토어에서 받기] https://play.google.com/store/apps/details?id=kr.jogwailji.app ③ 스토어판에서 같은 계정 로그인 → 지난 출조 → 복원 안내. ④ "스토어에서 '항목을 찾을 수 없습니다'가 나오면 아직 테스터 등록 전 → 관리자에게 구글 이메일을 알려 주세요" + [✉ 관리자에게 문의] 버튼(InquiryActivity, 이 창이 떠 있어도 문의는 가능). 관리자는 닫고 쓸 수 있음. 스토어판에는 안 뜸. **그 뒤 GitHub판 업데이트 중단** — 워크플로가 스토어판 AAB만 만들고 Releases의 `fishing-log.apk`/`version.txt` 는 v6.6에서 멈춤. 켜기 전에 그룹원 이메일을 내부 테스트 목록에 넣어야 함(아니면 스토어에서 못 받음). 사진 원본 · 가방 · 리모컨 등록 · 설정은 폰에만 있어 안 넘어감.
- ~~249~~ (v6.4에서 함 · 위치는 사용자 요청으로 **메인 ⚙ 왼쪽**, `StoreUpdate` 가 src/play · src/github 에 따로) 더보기 버전 줄 옆 **[🔄 업데이트 확인]** 버튼 — 스토어판: Play In-App Updates(`com.google.android.play:app-update-ktx`, `playImplementation` + `src/play` · `src/github` 에 같은 이름 클래스로 나눔) 로 확인 → 있으면 업데이트 창(스토어 열기 또는 앱 안에서 받기) · 없으면 '최신 버전이에요' · 켤 때 몰래 확인해서 빨간 점. GitHub판: 지금 Updater 창 바로 띄우기 / 없으면 '최신 버전이에요'. 스토어 주소 https://play.google.com/store/apps/details?id=kr.jogwailji.app (안 바뀜)
- ~~250~~ (v6.6에서 함) 그룹원 '지금 출조 중' 지도(`MapDialog.showPerson` watchMap)에 이동 거리 줄 — `distanceText(pts)` (이동 km · 해리 · 출발지에서 km) 를 설명 첫 줄에, 경로 갱신 때마다. 내 지도(show · showTrip)에는 이미 있음.
- ~~252~~ (v6.7에서 함 · `Backup.kt`, GitHub판 `src/github/.../BackupProvider.kt`, 스토어판 `MainActivity.checkGithubImport`): ① 두 판 모두 ⚙ 개인 설정(`SettingsActivity`)에 '💾 백업' — [폰에 백업하기] = 다운로드/조과일지/조과일지-백업-날짜.zip (MediaStore), [백업 파일에서 불러오기] = 파일 고르기(SAF). 담는 것: SharedPreferences "fishlog"(가방 · 설정 · 리모컨 키 · 즐겨찾기 등, 캐시 키 제외) + 출조 파일(filesDir/trips) + 사진 원본 + 오프라인 지도 타일. ② 스토어판 첫 실행 때 같은 폰에 GitHub판(com.fishlog.app, v6.7+)이 있으면 **자동으로 가져오기** — GitHub판에 내보내기용 ContentProvider(호출한 앱이 kr.jogwailji.app + Play 앱 서명 SHA-1 `4f:f0:f1:5e:19:6f:95:6c:85:44:0b:96:e3:ac:9a:e9:05:93:86:2f` 인지 확인), 스토어판 매니페스트에 `<queries>` com.fishlog.app. 없으면 "백업 파일 불러올까요?". **GitHub판도 v6.7 한 번 더 올림(예외)** — 워크플로 APK 단계 잠깐 되살리기. 옮기기 창 ①에도 '폰에 백업' 안내.
- **251 (제안만):** 관리자 메뉴 목록에 '📦 스토어로 옮기기' 바로 넣기 (지금은 관리자 페이지 → 👥 사용자 목록 · 권한 · 최소 버전 · 정지 → 최소 버전 아래).
- **253 (제안만):** 어종 이름 오타 알아보기 ('니ㅏㄱ지' → 낙지 · 자판 꼬임) + 나만의 어종 추가 때 "혹시 '낙지'인가요?". (벅스님 기록에 '니ㅏㄱ지'로 들어가 그림이 안 나왔음 · 낙지 그림 sp_nakji 는 있음)
- **254 ('만들어줘' 대기 · 사용자 확정: 민물 종류는 '민물낚시' 하나, 배스 포함):** ① `Store.Cat` 에 fresh(🏞 민물) — 서버 config/cats JSON `"fresh":true`, 관리자 '출조 종류 관리'에 [🌊 바다/🏞 민물], '민물낚시'(짧게 '민물', 기본 어종 배스 · 붕어 · 잉어 · 쏘가리 · 송어 · 블루길) 자동 추가. 판단 함수 하나(종류 fresh, 또는 어종이 전부 민물 고기면 민물 · 예전 기록 포함). ② 민물이면 숨김: TripActivity 물때 칸 · 제목 물때, BiteMemo 물때 문구, MapDialog.showDay 물때 줄, GroupTripsActivity 카드 물때, BoardActivity 글 · 카드 물때 · 들물날물, 게시판 통계 '물때 단계'는 바다 글만, Analysis 물때 · 물세기 계산에서 민물 제외, PublicBoard.buildCatches 물때 계산 생략, RemoteService 만조간조 알람 끔, 출조 중 메인 물때 카드 → 날씨 카드. 그대로: 메인 물때 창 · 출조추천 달력 · 위젯. ③ 대신 (사용자: 배스 · 붕어는 일출 · 일몰이 중요): 민물이면 '물때' 칸 → '🌅 해 · 시간대' 칸 — 일출 · 일몰 시각(출조 자리), 골든타임(일출 · 일몰 전후 1시간) 표시 + 시간대별 막대에 노랗게, 일출 · 일몰 기준 조과(일출 전 1h · 후 1h · 한낮 · 일몰 전 1h · 일몰 후), BiteMemo '일출 직후' · '일몰 30분 전', 게시판 글 · 지도 하루 상세의 들물날물 자리에 일출 · 일몰 기준 마릿수, Analysis 민물 어종은 일출 · 일몰 기준 '잘 나온 때', 출조 중 메인 카드 '일몰까지 1시간 20분 · 저녁 골든타임 곧'. 그 밖에 기압+변화(Open-Meteo surface_pressure) · 달 모양. ④ v6.9 스토어판만.
- **255 (사용자 확인함 · '만들어줘' 대기):** 전체 게시판 글 상세(BoardActivity 팝업 · 지도 '지난 기록' › 포함)를 그룹 출조와 같은 **TripActivity 보기 전용 큰 화면**으로 — 총 조과 · 어종별 · 그날 날씨 · 물때 그래프 · 시간대별 기록 · 사진 · 입질 한마디. 게시판이라 경로 · 머문 곳 · 배 이름 없음, 잡은 자리는 대략이면 원, 이용료는 '유료'만. 아래 [이 사람 차단] [공개 내리기] 유지. (사용자: "조과 보는 창이 너무 달라")
- **256 (사용자 고르기 대기 · 추천 ①②):** 지도 '지난 기록' 점 → 창(`MapDialog.showCluster`) 개선. ① 날짜 카드마다 [🗺 이 출조만 보기] → 그 출조만 진하게(나머지 흐리게) + 이동 경로 · 이동 거리(distanceText) + 잡은 순서 번호 · 시각, '× 다 보기'. 게시판 글은 경로 없음(잡은 자리 · 시각만). ② 출조(날짜)마다 다른 색 + 지도 위 색 범례 줄(누르면 ①), 최근 6개만 색 · 나머지 회색. ③ 겹친 점 꽃잎처럼 펼치기. ④ 걸러 보기(기간 · 같은 물때만 · 어종).
- **195:** 내용 확인 필요 (예전 대화방 메모)
- 아이디어: 보기 전용 웹사이트 (Firebase Hosting · 같은 로그인 · 그룹 출조 기록 · 게시판 · 물때)
- ~~229 메타버스~~ — 삭제함 (2026-11-11)
