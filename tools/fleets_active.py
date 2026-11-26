"""메모 426: 앞으로 한 달 일정이 하나도 없는 선단(쉬는 곳 · 문 닫은 곳) 찾기 → site/data/fleets_off.json {t, off:[host]}
선상24 선단 = 한 달 페이지(이번 달 · 15일 넘으면 다음 달도) · 더피싱 선단 = 중계기로 8일 화면 4번 (GitHub 에서 더피싱 직접은 막힘)
못 읽은 선단은 '쉼'으로 안 넣음 (안전하게)"""
import json, os, re, datetime, urllib.parse, urllib.request
from concurrent.futures import ThreadPoolExecutor
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RELAY = "https://talla-relay.jonginzz33.workers.dev/?u="
UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Mobile"
today = datetime.datetime.utcnow() + datetime.timedelta(hours=9)
t0 = today.strftime("%Y-%m-%d")
def get(url, tries=2):
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "ko-KR,ko"}), timeout=40) as r:
                return r.read().decode("utf-8", "ignore")
        except Exception:
            pass
    return None
def s24(h):
    months = [today.strftime("%Y%m")]
    if today.day >= 15:
        nm = (today.replace(day=28) + datetime.timedelta(days=5)).strftime("%Y%m"); months.append(nm)
    got_any = False
    for m in months:
        t = get(f"https://{h}/ship/schedule_fleet/{m}")
        if t is None: continue
        got_any = True
        if any(d >= t0 for d in re.findall(r'data-sdate="(\d{4}-\d{2}-\d{2})"', t)): return h, True
    return h, (None if not got_any else False)
def tf(h):
    got_any = False
    for k in (0, 8, 16, 24):
        d = today + datetime.timedelta(days=k)
        u = f"https://{h}/index.php?mid=bk&year={d.year}&month={d.month:02d}&day={d.day:02d}"
        t = get(RELAY + urllib.parse.quote(u, safe=""))
        if t is None: continue
        got_any = True
        if re.search(r'admin-right-\d{8}-\d+-\d+', t): return h, True
    return h, (None if not got_any else False)
fl = json.load(open(os.path.join(ROOT, "site/data/fleets.json"), encoding="utf-8"))["f"]
jobs = [(f[0], f[4]) for f in fl]
off, on, fail = [], 0, 0
with ThreadPoolExecutor(8) as ex:
    for h, act in ex.map(lambda x: tf(x[0]) if x[1] == "더피싱" else s24(x[0]), jobs):
        if act is False: off.append(h)
        elif act: on += 1
        else: fail += 1
json.dump({"t": today.strftime("%Y-%m-%d %H:%M"), "off": sorted(off)}, open(os.path.join(ROOT, "site/data/fleets_off.json"), "w", encoding="utf-8"), ensure_ascii=False)
print("fleets", len(jobs), "active", on, "off", len(off), "fail", fail)
