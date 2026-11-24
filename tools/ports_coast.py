# 바다에서 먼 내륙 자리(식당 이름 '○○포구' 등) 빼기 — assets/korea_map.json 육지 그림(권역 지도와 같은 메르카토르) 기준
# 육지 안이고 해안선에서 MAX_KM 넘게 떨어졌으면 뺌 (강 · 호수 나루터도 빠짐)
import json, math, sys
MAX_KM = float(sys.argv[1]) if len(sys.argv) > 1 else 3.0
m = json.load(open("app/src/main/assets/korea_map.json"))
k, tx, ty = m["k"], m["tx"], m["ty"]
def proj(lat, lon):
    return tx + k * math.radians(lon), ty - k * math.log(math.tan(math.pi / 4 + math.radians(lat) / 2))
polys = [[(p[i], p[i + 1]) for i in range(0, len(p) - 1, 2)] for p in m["K"]]
def inside(x, y, poly):
    c = False; n = len(poly)
    for i in range(n):
        x1, y1 = poly[i]; x2, y2 = poly[(i + 1) % n]
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1: c = not c
    return c
def seg(px, py, a, b):
    (x1, y1), (x2, y2) = a, b; dx, dy = x2 - x1, y2 - y1
    t = 0 if dx == dy == 0 else max(0, min(1, ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px - x1 - t * dx, py - y1 - t * dy)
d = json.load(open("app/src/main/assets/ports.json"))
out = []; drop = []
for p in d:
    x, y = proj(p[1], p[2])
    land = [poly for poly in polys if inside(x, y, poly)]
    if not land: out.append(p); continue
    km_per_px = 6371.0 / k * math.cos(math.radians(p[1]))
    dist = min(seg(x, y, poly[i], poly[(i + 1) % len(poly)]) for poly in land for i in range(len(poly))) * km_per_px
    (out if dist <= MAX_KM else drop).append(p)
json.dump(out, open("app/src/main/assets/ports.json", "w"), ensure_ascii=False, separators=(",", ":"))
print("kept", len(out), "dropped", len(drop))
for p in drop[:40]: print(" -", p[0], p[4])
# 이름으로 거르기 (식당 · 가게 · 시설 안 칸)
import re
BAD = re.compile(r"충전소|분양|홍보관|주차장|매표소|안내소|화장실|편의점|식당|횟집|회센터|수산|마트|카페|펜션|모텔|호텔|점$|\S+점\s|^(어항|포구|나루터|선착장|부두)$")
d2 = [p for p in out if not BAD.search(p[0])]
dropped2 = [p[0] for p in out if BAD.search(p[0])]
json.dump(d2, open("app/src/main/assets/ports.json", "w"), ensure_ascii=False, separators=(",", ":"))
print("name-dropped", len(dropped2), dropped2[:40]); print("final", len(d2))
# 같은 이름이 여럿인데 진짜 항구(큰항 1)가 있으면 작은 쪽(가게 이름일 때가 많음)은 뺌
from collections import defaultdict
bigname = {p[0] for p in d2 if p[3] == 1}
d3 = [p for p in d2 if p[3] == 1 or p[0] not in bigname]
print("same-name dropped", len(d2) - len(d3)); print("final", len(d3))
json.dump(d3, open("app/src/main/assets/ports.json", "w"), ensure_ascii=False, separators=(",", ":"))
