"""412: 홈피 배 선단 보기 · 앱용 선단 모음 site/data/fleets.json
선상24 선단(seat_sites.json h · p) + 더피싱 선단(tf_sites.json) → 가까운 지역(앱 Seats.AREAS 와 같은 규칙)
{t, areas:[[이름, 묶음]], f:[[host, 이름, 항구, 지역, 출처]]}"""
import json, math, os, re, datetime
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AREAS = [("인천인근",37.45,126.55,"서해안"),("평택인근",36.98,126.80,"서해안"),("태안인근",36.70,126.25,"서해안"),("보령인근",36.35,126.52,"서해안"),
 ("군산인근",35.95,126.60,"서해안"),("영광인근",35.30,126.40,"서해안"),("목포인근",34.78,126.38,"서해안"),("진도인근",34.45,126.25,"서해안"),
 ("완도인근",34.33,126.75,"남해안"),("여수인근",34.72,127.72,"남해안"),("삼천포인근",34.93,128.07,"남해안"),("통영인근",34.84,128.43,"남해안"),
 ("진해인근",35.12,128.70,"남해안"),("부산인근",35.10,129.05,"남해안"),("포항인근",36.03,129.40,"동해안"),("후포인근",36.68,129.45,"동해안"),
 ("울진인근",36.99,129.41,"동해안"),("삼척인근",37.44,129.17,"동해안"),("강릉인근",37.77,128.95,"동해안"),("주문진인근",37.89,128.83,"동해안"),
 ("속초인근",38.20,128.60,"동해안"),("고성인근",38.38,128.48,"동해안"),("제주인근",33.52,126.53,"제주도"),("서귀포인근",33.24,126.56,"제주도"),
 ("모슬포인근",33.21,126.25,"제주도"),("성산포인근",33.46,126.93,"제주도")]
AREA_EXTRA = {"보령인근":["오천","대천","무창포","홍성","남당"],"군산인근":["비응","야미도","신시도","선유도","홍원","서천","격포","위도"],
 "태안인근":["마검포","안흥","신진도","모항","드르니","백사장","영목","구매","서산","삼길포"],"인천인근":["영흥","연안부두","남항","인천","대부","오이도","시흥","거북섬"],
 "평택인근":["궁평","전곡","화성","당진","평택","장고항","한진"],"여수인근":["여수","국동","소호","돌산","광양"],"통영인근":["통영","거제","장승포","지세포"]}
AREA_KW = {"인천인근":["인천","영흥도"],"평택인근":["평택","화성","당진"],"태안인근":["태안","안면도","서산"],"보령인근":["보령","홍성"],"군산인근":["군산","서천","격포"],
 "영광인근":["영광"],"목포인근":["목포"],"진도인근":["진도","해남"],"여수인근":["여수"],"통영인근":["통영"],"삼천포인근":["사천","삼천포"],"완도인근":["완도","고흥","녹동"],
 "제주인근":["제주"]}
SKIP = {"www","api","m","img","imga","imgb","assets","static","cdn","admin","pay","partner","service","serviceback"}
def km(a1,o1,a2,o2):
    r=math.pi/180; x=(o2-o1)*r*math.cos((a1+a2)/2*r); y=(a2-a1)*r; return math.hypot(x,y)*6371
def nearest(la,lo):
    a=min(AREAS,key=lambda z:km(la,lo,z[1],z[2])); return a[0] if km(la,lo,a[1],a[2])<70 else None
def norm(p): return re.sub(r"(항|포구)$","",p.replace(" ","")).lower()
def by_word(x):
    for ar,ks in list(AREA_EXTRA.items())+list(AREA_KW.items()):
        if any(k in x for k in ks): return ar
    return None
ports=json.load(open(os.path.join(ROOT,"app/src/main/assets/ports.json"),encoding="utf-8"))
idx={}
for p in ports:
    k=norm(p[0])
    if len(k)>=2 and (k not in idx or p[3]): idx[k]=p
def by_port(s):
    for t in re.split(r"[,/ ()>·]",s):
        t=norm(t.strip())
        if len(t)<2: continue
        p=idx.get(t) or next((v for k,v in idx.items() if k.startswith(t)),None)
        if p:
            a=nearest(p[1],p[2])
            if a: return a
    return None
out=[]
st=json.load(open(os.path.join(ROOT,"site/data/seat_sites.json"),encoding="utf-8"))["ships"]
g={}
for v in st.values():
    h=v.get("h")
    if not h or not h.endswith(".sunsang24.com") or h.split(".")[0] in SKIP: continue
    g.setdefault(h,[]).append(v)
for h,l in g.items():
    ps=[x.get("p","") for x in l if x.get("p")]
    port=max(set(ps),key=ps.count) if ps else ""
    ar=by_port(port) or by_word(port)
    if not ar: continue
    names=[]
    for x in l:
        n=x.get("n","")
        if n and n not in names: names.append(n)
    out.append([h," · ".join(names[:3]),port,ar,"선상24"])
tf=json.load(open(os.path.join(ROOT,"site/data/tf_sites.json"),encoding="utf-8"))["j"]
tg={}
for r in tf:
    if r[6]: tg.setdefault(r[6],[]).append(r)
for h,l in tg.items():
    a0=l[0][2]; ar=by_word(a0) or by_port(a0.split(" > ")[-1])
    if not ar: continue
    nm=(l[0][3] or "").split(" (")[0].strip() or " · ".join(dict.fromkeys(x[1] for x in l))[:40]
    out.append([h,nm,a0.split(" > ")[-1],ar,"더피싱"])
res={"t":datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%MZ"),"areas":[[a[0],a[3]] for a in AREAS],"f":sorted(out,key=lambda x:(x[3],x[0]))}
json.dump(res,open(os.path.join(ROOT,"site/data/fleets.json"),"w",encoding="utf-8"),ensure_ascii=False,separators=(",",":"))
from collections import Counter
print(len(out),"fleets",Counter(x[3] for x in out).most_common(8))
