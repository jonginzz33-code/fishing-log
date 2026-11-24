#!/usr/bin/env python3
"""390: 선상24 배마다 선단 사이트 · 밴드 · 전화번호 모으기 → site/data/seat_sites.json (talla.co.kr 에 올라감)

- 배 목록: api.sunsang24.com/ship/list markers (앞으로 30일 · 오늘 기준)
- 배마다: /ship/simple_info/{no} (이름 · 전화 · 항구) + /ship/fish_board_list?ship_no= (조황 글) → 본문 /ship/fish_board_detail/{no} 최대 3개
- 본문에 가장 많이 나온 ○○.sunsang24.com = 선단 사이트 · band.us/… = 밴드
- 새 배는 다 · 예전 배는 7일 지난 것만 하루 최대 REFRESH 척 (선상24 서버에 부담 안 주게)
"""
import json, os, re, sys, time, datetime, urllib.request, urllib.parse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

API = "https://api.sunsang24.com"
OUT = os.path.join(os.path.dirname(__file__), "..", "site", "data", "seat_sites.json")
REFRESH = int(os.environ.get("REFRESH", "300"))
KEEP_DAYS = 7
SKIP = {"imga", "imgb", "img", "assets", "www", "api", "service", "serviceback", "m", "cdn", "static", "admin", "partner", "pay"}
HOST_RE = re.compile(r"([a-z0-9][a-z0-9-]{1,40})\.sunsang24\.com", re.I)
BAND_RE = re.compile(r"band\.us/(@[A-Za-z0-9._-]+|band/\d+|n/[A-Za-z0-9]+)", re.I)


def get(url, tries=3):
    for i in range(tries):
        try:
            rq = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (seat-sites; talla.co.kr)", "Accept": "application/json"})
            with urllib.request.urlopen(rq, timeout=20) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception:
            if i == tries - 1:
                raise
            time.sleep(1.5 * (i + 1))


def ship_nos():
    today = datetime.date.today()
    rng = f"{today},{today + datetime.timedelta(days=30)}"
    nos = set()
    for q in (f"type=general&page=1&sdate={rng}", "type=general&page=1"):
        try:
            d = get(f"{API}/ship/list?{q}")
            nos |= {int(m["ship_no"]) for m in d.get("markers", []) if str(m.get("ship_no", "")).isdigit()}
        except Exception as e:
            print("list fail", q, e, file=sys.stderr)
    return sorted(nos)


def scan(no):
    r = {}
    try:
        si = get(f"{API}/ship/simple_info/{no}")
        r["n"] = (si.get("name") or "").strip()
        if si.get("tel"): r["t"] = si["tel"].strip()
        if si.get("port_name"): r["p"] = si["port_name"].strip()
    except Exception:
        pass
    hosts, bands = Counter(), Counter()
    try:
        bl = get(f"{API}/ship/fish_board_list?ship_no={no}&page=1")
        for it in (bl.get("list") or [])[:3]:
            try:
                det = get(f"{API}/ship/fish_board_detail/{it.get('no')}")
            except Exception:
                continue
            txt = json.dumps(det, ensure_ascii=False).replace("\\/", "/")
            for h in HOST_RE.findall(txt):
                h = h.lower()
                if h not in SKIP: hosts[h] += 1
            for b in BAND_RE.findall(txt):
                bands[b] += 1
            time.sleep(0.15)
    except Exception:
        pass
    if hosts: r["h"] = hosts.most_common(1)[0][0] + ".sunsang24.com"
    if bands: r["b"] = "band.us/" + bands.most_common(1)[0][0]
    r["at"] = datetime.date.today().strftime("%Y%m%d")
    return no, r


def main():
    old = {}
    try:
        with open(OUT, encoding="utf-8") as f:
            old = json.load(f).get("ships", {})
    except Exception:
        pass
    nos = ship_nos()
    if not nos:
        print("no ships · keep old file"); return
    today = datetime.date.today()
    def age(no):
        o = old.get(str(no))
        if not o: return 10 ** 6
        try: return (today - datetime.datetime.strptime(o.get("at", "20000101"), "%Y%m%d").date()).days
        except Exception: return 10 ** 6
    new = [n for n in nos if str(n) not in old]
    stale = sorted([n for n in nos if str(n) in old and age(n) >= KEEP_DAYS], key=age, reverse=True)[:REFRESH]
    todo = new + stale
    print(f"ships {len(nos)} · new {len(new)} · refresh {len(stale)}")
    ships = dict(old)
    with ThreadPoolExecutor(6) as ex:
        for i, (no, r) in enumerate(ex.map(scan, todo)):
            prev = ships.get(str(no), {})
            # 이번에 못 찾은 칸은 예전 값 그대로
            for k in ("h", "b", "t", "p", "n"):
                if k not in r and k in prev: r[k] = prev[k]
            ships[str(no)] = r
            if i % 100 == 0: print(i, no, r.get("n"), r.get("h"), flush=True)
            if i % 200 == 199: save(ships)   # 중간에 멈춰도 모은 만큼은 남게
    save(ships)
    print("hosts", sum(1 for v in ships.values() if "h" in v), "/", len(ships))


def save(ships):
    out = {"v": 1, "t": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%MZ"), "ships": dict(sorted(ships.items(), key=lambda x: int(x[0])))}
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT + ".tmp", "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(OUT + ".tmp", OUT)


if __name__ == "__main__":
    main()
