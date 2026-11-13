# 조과일지 (fishing-log) 작업 설명서

안드로이드 낚시 조과 기록 앱. 코드는 `app/src/main/java/com/fishlog/app/` (Kotlin, 화면은 코드로 그림 · XML 레이아웃 거의 없음).

## 작업 규칙
- 사용자는 개발자가 아니에요. 답은 쉬운 한국어로 짧게.
- 사용자가 **"만들어줘"** 라고 할 때만 코드를 만들어요. 그 전에는 메모(번호)로만 정리.
- 새 버전을 만들 때마다:
  - `app/build.gradle.kts` 의 `versionCode` +1, `versionName` 올리기
  - `README.md` **맨 끝**에 `## v새버전` + 바뀐 것 몇 줄 (GitHub 빌드가 이 마지막 부분을 앱 '이번에 바뀐 것'으로 보여줘요)
  - `History.kt` 의 `ITEMS` **맨 위**에 항목 추가 (앱 '업데이트 내역')
  - 설명서가 바뀌면 `HelpActivity.kt` 도 같이
- `main` 에 올리면 GitHub Actions(`.github/workflows/build.yml`)가 GitHub판 APK를 만들어 Releases `latest` 에 올리고, 앱이 그걸 보고 업데이트 창을 띄워요.
- 올리기 전에 빌드 확인: `gradle assembleGithubDebug assemblePlayDebug --no-daemon` (Android SDK 필요, `local.properties` 에 `sdk.dir`).

## 두 가지 판 (v6.0 · 242-2)
- `github`: 앱 안 업데이트 있음 (지금 쓰는 사람용, 기본 빌드)
- `play`: 플레이 스토어용. 앱 안 업데이트 · 설치 권한 없음 (`BuildConfig.SELF_UPDATE = false`, `app/src/play/AndroidManifest.xml`)

## 화면 (v6.0 · 242-1 · 243)
- 안드로이드 16(API 36) 기준이라 화면이 상태바 · 하단 버튼 밑까지 그려져요. 여백은 `Ui.edge()` 가 모든 액티비티(자동)와 전체 창(`Dialog` 만든 뒤 `Ui.edge(dlg.window)`)에 넣어요. **새 전체 창을 만들면 꼭 `Ui.edge` 호출.**
- 칸 크기를 화면 폭으로 계산할 땐 `displayMetrics.widthPixels` 대신 `Ui.contentW()` (펼친 폴드 · 태블릿은 가운데 600dp).
- 큰 AlertDialog 폭은 `Ui.dialogW()`, 체크박스는 `Ui.checkFit(CheckBox(...))`.

## 남은 메모
- ~~242-4 업로드 키 + AAB~~ — v6.2에서 함. 키는 `keystore/play-upload.p12.enc` (AES-256 · pbkdf2 200000), 비밀값 `PLAY_KEY_PASS` 로 풀어서 GitHub 빌드가 `bundlePlayRelease` → Releases `latest` 에 `jogwa-play-<versionCode>.aab`. 업로드 키 SHA-1 `D0:EA:F0:08:08:4B:DA:5F:27:06:0C:5D:24:12:BD:58:0A:30:4F:50` (Play 앱 서명 키 SHA-1은 플레이 콘솔에서 따로 → 둘 다 Firebase에 등록해야 스토어판 구글 로그인 됨)
- 스토어 준비물(올리기로 정하면): 개인정보처리방침 웹페이지 · 탈퇴 안내 페이지, 개발자 계정, 비공개 테스트 12명 14일, 리모컨 '음악 재생' 서비스 사용 이유 설명
- ~~244 게시판 글 조과 반영~~ — v6.1에서 함
- ~~245~~ 물세기 평균 방식 — v6.2에서 함. 예전 설명: 물세기 % 계산을 평균 방식으로 — 그날(0~24시) 이어진 만조↔간조 높이 차들의 **평균** ÷ 관측소 1년 중 가장 큰 사리 조차 (`Tide.kt` `strengthOf` · 지금은 그날 최고−최저라 세게 나옴). 예: 11/13 안흥 387·458·541 → 평균 462cm ≈ 65% ('물때와 날씨' 64%, 지금 앱 76%). 물때 시각 · 높이는 이미 같음.
- **195:** 내용 확인 필요 (예전 대화방 메모)
- 아이디어: 보기 전용 웹사이트 (Firebase Hosting · 같은 로그인 · 그룹 출조 기록 · 게시판 · 물때)
- ~~229 메타버스~~ — 삭제함 (2026-11-11)
