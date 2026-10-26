#!/data/data/com.termux/files/usr/bin/bash
# 사용법: Claude가 준 새 fishing-log.zip 을 다운로드한 뒤
#   bash ~/fishing-log/update.sh
set -e
Z=$(ls -t ~/storage/downloads/fishing-log*.zip | head -1)
echo "▶ 새 코드 적용: $Z"
cd ~ && unzip -o -q "$Z"
cd ~/fishing-log
git add -A
git -c user.name="jonginzz33-code" -c user.email="jonginzz33-code@users.noreply.github.com" \
  commit -q -m "업데이트 $(date +%m%d-%H%M)" || echo "(바뀐 파일 없음)"
git push -q
echo "▶ GitHub 빌드 시작 — 3~5분 걸려요"
sleep 10
RUN=$(gh run list -L1 --json databaseId -q '.[0].databaseId')
gh run watch "$RUN" --exit-status || {
  echo "✗ 빌드 실패 — 아래 내용을 캡처해서 Claude에게 보내주세요"
  gh run view "$RUN" --log-failed | tail -40
  exit 1
}
echo "▶ APK 다운로드"
gh release download latest -p fishing-log.apk -D ~/storage/downloads --clobber
echo "✓ 완료! 파일 앱 → 다운로드 → fishing-log.apk 눌러서 업데이트 설치"
