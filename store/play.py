"""
v6.19+ (268): 플레이 콘솔 자동 작업 — GitHub Actions(store.yml)에서 돌아요.
  python store/play.py listing          → 스토어 등록정보(글 · 아이콘 · 그래픽 · 스크린샷) 올리기
  python store/play.py closed [그룹메일]  → 내부 테스트 최신 버전을 비공개 테스트(alpha)로 + 테스터 구글 그룹
비밀값 PLAY_SERVICE_ACCOUNT_JSON (서비스 계정) 필요.
"""
import json, os, re, sys, glob
import requests
from google.oauth2 import service_account
from google.auth.transport.requests import AuthorizedSession

PKG = "kr.jogwailji.app"
LANG = "ko-KR"
API = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PKG}"
UP = f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PKG}"
HERE = os.path.dirname(os.path.abspath(__file__))
# 스토어에 올릴 스크린샷 (순서대로) — 3번은 다른 사람 경로가 보여서 사용자 확인 전까지 뺌
SHOTS = ["screenshot-1.png", "screenshot-2.png", "screenshot-4.png"]

def session():
    info = json.loads(os.environ["PLAY_SERVICE_ACCOUNT_JSON"])
    cred = service_account.Credentials.from_service_account_info(info, scopes=["https://www.googleapis.com/auth/androidpublisher"])
    return AuthorizedSession(cred)

def errmsg(r):
    try: return r.json().get("error", {}).get("message", r.text)
    except Exception: return r.text
def check(r, what):
    if r.status_code >= 300:
        # 비밀값 가리기(***)에 안 걸리게 중괄호 없이 메시지만
        print(f"::error::{what} 실패 ({r.status_code}): " + errmsg(r)[:600].replace("{", "(").replace("}", ")"))
        sys.exit(1)
    return r.json() if r.text else {}

def read(name):
    return open(os.path.join(HERE, "listing", LANG, name), encoding="utf-8").read().strip()

def release_notes():
    """README 맨 끝 '## v…' 부분 → '이번에 바뀐 것' (500자)"""
    txt = open(os.path.join(HERE, "..", "README.md"), encoding="utf-8").read()
    last = txt.split("\n## ")[-1]
    lines = [l.lstrip("- ").strip() for l in last.splitlines()[1:] if l.strip()]
    return "\n".join(lines)[:500]

def listing(s, eid):
    body = {"language": LANG, "title": read("title.txt"), "shortDescription": read("short.txt"), "fullDescription": read("full.txt")}
    assert len(body["title"]) <= 30 and len(body["shortDescription"]) <= 80 and len(body["fullDescription"]) <= 4000
    check(s.put(f"{API}/edits/{eid}/listings/{LANG}", json=body), "등록정보 글")
    print("✓ 앱 이름 · 설명")
    def images(kind, files):
        check(s.delete(f"{API}/edits/{eid}/listings/{LANG}/{kind}"), f"{kind} 지우기")
        for f in files:
            with open(os.path.join(HERE, f), "rb") as fh:
                check(s.post(f"{UP}/edits/{eid}/listings/{LANG}/{kind}?uploadType=media", data=fh.read(),
                             headers={"Content-Type": "image/png"}), f"{kind} {f}")
        print(f"✓ {kind}: {', '.join(files)}")
    images("icon", ["icon-512.png"])
    images("featureGraphic", ["feature-1024x500.png"])
    images("phoneScreenshots", SHOTS)

def closed(s, eid, group, status="completed"):
    tr = check(s.get(f"{API}/edits/{eid}/tracks/internal"), "내부 테스트 읽기")
    codes = []
    for rel in tr.get("releases", []):
        codes += [int(c) for c in rel.get("versionCodes", [])]
    if not codes:
        print("::error::내부 테스트에 올라간 버전이 없어요"); sys.exit(1)
    top = str(max(codes))
    body = {"track": "alpha", "releases": [{"versionCodes": [top], "status": status,
            "releaseNotes": [{"language": LANG, "text": release_notes()}]}]}
    check(s.put(f"{API}/edits/{eid}/tracks/alpha", json=body), "비공개 테스트(alpha)에 올리기")
    print(f"✓ 비공개 테스트(alpha) ← 버전 {top} ({status})")
    if group:
        check(s.put(f"{API}/edits/{eid}/testers/alpha", json={"googleGroups": [group]}), "테스터 그룹")
        print(f"✓ 테스터 그룹: {group}")

def status(s, eid):
    """트랙별 출시 상태 · 테스터 (읽기만)"""
    for t in ["internal", "alpha"]:
        tr = s.get(f"{API}/edits/{eid}/tracks/{t}")
        if tr.status_code >= 300: print(f"{t}: 읽기 실패 ({tr.status_code})"); continue
        for rel in tr.json().get("releases", []):
            print(f"{t}: 버전 {','.join(rel.get('versionCodes', []))} · 상태 {rel.get('status')} · 이름 {rel.get('name', '')}")
    te = s.get(f"{API}/edits/{eid}/testers/alpha")
    if te.status_code < 300: print("alpha 테스터 그룹:", te.json().get("googleGroups", []))

def main():
    what = sys.argv[1] if len(sys.argv) > 1 else "listing"
    s = session()
    eid = check(s.post(f"{API}/edits"), "편집 시작")["id"]
    if what == "listing":
        listing(s, eid)
    elif what == "status":
        status(s, eid); s.delete(f"{API}/edits/{eid}"); print("✓ 확인 끝 (바꾼 것 없음)"); return
    elif what == "closed":
        group = sys.argv[2].strip() if len(sys.argv) > 2 and sys.argv[2].strip() else None
        closed(s, eid, group)
    else:
        print("::error::listing 또는 closed"); sys.exit(1)
    r = s.post(f"{API}/edits/{eid}:commit")
    if r.status_code >= 300 and "changesNotSentForReview" in r.text:
        r = s.post(f"{API}/edits/{eid}:commit?changesNotSentForReview=true")
    # 아직 한 번도 공개 안 된 앱(초안)은 출시를 '초안'으로만 만들 수 있음 → 초안으로 다시
    if r.status_code >= 300 and what == "closed" and "draft" in errmsg(r).lower():
        print("… 앱이 아직 초안이라 비공개 테스트 출시를 '초안'으로 만들어요")
        eid = check(s.post(f"{API}/edits"), "편집 다시 시작")["id"]
        closed(s, eid, group, "draft")
        r = s.post(f"{API}/edits/{eid}:commit")
    check(r, "저장(커밋)")
    print("✓ 플레이 콘솔에 저장했어요")

if __name__ == "__main__":
    main()
