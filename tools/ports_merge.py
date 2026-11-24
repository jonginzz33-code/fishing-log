# OSM(ports.json) + 카카오(kakao_ports.json) 합치기 · 가까운 같은 이름은 하나만 → app/src/main/assets/ports.json  [[이름, 위도, 경도, 큰항(1/0), 주소]]
import json, math, re, os
osm = json.load(open("ports.json")) if os.path.exists("ports.json") else []
kk = json.load(open("kakao_ports.json")) if os.path.exists("kakao_ports.json") else []
def norm(s): return re.sub(r"[\s()]", "", s)
allp = [p + [""] if len(p) == 4 else p for p in kk + osm]
out = []
for p in allp:
    dup = False
    for q in out:
        if abs(q[1] - p[1]) < 0.004 and abs(q[2] - p[2]) < 0.005 and (norm(q[0]) == norm(p[0]) or norm(q[0]) in norm(p[0]) or norm(p[0]) in norm(q[0])):
            if not q[4] and p[4]: q[4] = p[4]
            dup = True; break
    if not dup: out.append(list(p))
out.sort(key=lambda x: (-x[3], x[1]))
os.makedirs("app/src/main/assets", exist_ok=True)
json.dump(out, open("app/src/main/assets/ports.json", "w"), ensure_ascii=False, separators=(",", ":"))
print("merged", len(out), "big", sum(1 for p in out if p[3]))
for p in out:
    if any(s in p[0] for s in ("무창포", "마검포", "오이도", "오천항", "궁평", "안흥", "대천")): print(p)
