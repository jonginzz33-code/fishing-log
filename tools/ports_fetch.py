# 오픈스트리트맵(Overpass)에서 우리나라 항구 · 포구 · 선착장 · 마리나 이름과 위치를 받아 ports.json 으로 (GitHub Actions 에서만 돌림)
import json, re, sys, urllib.request, urllib.parse, time
Q = '''[out:json][timeout:180];
area["ISO3166-1"="KR"][admin_level=2]->.kr;
(
  nwr["harbour"](area.kr);
  nwr["leisure"="marina"](area.kr);
  nwr["seamark:type"~"^(harbour|small_craft_facility)$"](area.kr);
  nwr["amenity"="ferry_terminal"](area.kr);
  nwr["man_made"="pier"]["name"~"(항|포구|선착장|부두|방파제)"](area.kr);
  nwr["name"~"(항|포구|선착장)$"]["name"!~"(공항|비행|항공|하항|대항$)"]["place"](area.kr);
);
out center tags;'''
urls = ["https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter", "https://overpass.private.coffee/api/interpreter"]
data = None
for u in urls:
    try:
        req = urllib.request.Request(u, data=urllib.parse.urlencode({"data": Q}).encode(), headers={"User-Agent": "fishlog-ports/1.0"})
        data = json.load(urllib.request.urlopen(req, timeout=240)); print("ok", u, len(data["elements"])); break
    except Exception as e:
        print("fail", u, e); time.sleep(5)
if not data: sys.exit(1)
out = {}
for el in data["elements"]:
    t = el.get("tags", {})
    nm = (t.get("name:ko") or t.get("name") or "").strip()
    if not nm or re.search(r"(공항|비행장|항공|헬기)", nm): continue
    if not re.search(r"[가-힣]", nm): continue
    la = el.get("lat") or el.get("center", {}).get("lat"); lo = el.get("lon") or el.get("center", {}).get("lon")
    if la is None or not (33 <= la <= 38.7 and 124 <= lo <= 131.9): continue
    kind = "big" if (t.get("harbour") in ("yes", "port") or nm.endswith("항") or t.get("amenity") == "ferry_terminal") and "선착장" not in nm else "small"
    k = nm
    if k in out:
        # 같은 이름이 가까이 여럿이면 하나만
        p = out[k]
        if abs(p[1] - la) < 0.05 and abs(p[2] - lo) < 0.05: continue
        k = nm + "#" + str(len(out))
    out[k] = [nm, round(la, 5), round(lo, 5), 1 if kind == "big" else 0]
arr = sorted(out.values(), key=lambda x: (x[1], x[2]))
json.dump(arr, open("ports.json", "w"), ensure_ascii=False, separators=(",", ":"))
print("ports", len(arr), "big", sum(1 for a in arr if a[3]))
for a in arr:
    if any(s in a[0] for s in ("무창포", "마검포", "오이도", "오천", "대천", "안흥", "궁평")): print(a)
