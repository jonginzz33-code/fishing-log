# 카카오 장소 검색으로 바닷가 항구 · 포구 · 선착장 · 부두 · 여객터미널 모으기 (GitHub Actions · KAKAO_REST_KEY)
import json, os, re, sys, time, urllib.request, urllib.parse, math
KEY = os.environ.get("KAKAO_REST_KEY", "")
if not KEY: print("no key"); sys.exit(1)
def get(q, rect, page):
    u = "https://dapi.kakao.com/v2/local/search/keyword.json?" + urllib.parse.urlencode({"query": q, "rect": rect, "page": page, "size": 15})
    r = urllib.request.Request(u, headers={"Authorization": "KakaoAK " + KEY})
    for t in range(3):
        try: return json.load(urllib.request.urlopen(r, timeout=20))
        except Exception as e:
            time.sleep(1 + t)
    return {"documents": [], "meta": {"is_end": True}}
OK_CAT = re.compile(r"(항구|선착장|부두|여객터미널|해운|어항|마리나|낚시터)")
OK_NAME = re.compile(r"(항|포구|선착장|부두|여객터미널|물양장|마리나)(\s|$|\()")
BAD = re.compile(r"(공항|항공|비행|헬기|주차장|식당|횟집|모텔|펜션|카페|편의점|약국|병원|마트|낚시점|낚시프라자|횟집|수산|회센터|정류장|버스|주유소|교회|학교)")
res = {}
qs = ["항", "포구", "선착장", "부두", "여객터미널", "물양장"]
step = 0.2
lat = 33.0; n = 0
while lat < 38.7:
    lon = 124.4
    while lon < 131.0:
        rect = f"{lon},{lat},{lon+step},{lat+step}"
        for q in qs:
            for page in (1, 2, 3):
                d = get(q, rect, page); n += 1
                for doc in d.get("documents", []):
                    nm = doc["place_name"].strip(); cat = doc.get("category_name", "")
                    if BAD.search(nm) or BAD.search(cat): continue
                    if not (OK_CAT.search(cat) or OK_NAME.search(nm + " ")): continue
                    la = float(doc["y"]); lo = float(doc["x"])
                    big = 1 if (re.search(r"항(\s|$)", nm + " ") or "여객터미널" in nm or "항구" in cat) and "선착장" not in nm else 0
                    res[doc["id"]] = [nm, round(la, 5), round(lo, 5), big, doc.get("address_name", "")]
                if d.get("meta", {}).get("is_end", True): break
        lon += step
    lat += step
print("requests", n, "kakao", len(res))
json.dump(list(res.values()), open("kakao_ports.json", "w"), ensure_ascii=False)
