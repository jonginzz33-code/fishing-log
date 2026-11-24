#!/usr/bin/env python3
"""더피싱 선단 목록 CSV(UID,선사명,개인사이트주소) → app/src/main/assets/tf_sites.json (앱 기본 더피싱 목록)
쓰는 법: python3 tools/tf_sites_build.py 목록.csv app/src/main/assets/tf_sites.json
"""
import csv,re,json,sys,time,collections
src,out=sys.argv[1],sys.argv[2]
r=list(csv.reader(open(src,encoding='utf-8-sig')))[1:]
AREA=re.compile(r'([가-힣]+권(?: > [^ >]+)+)')
PORT=('항','포구','선착장','부두','항구','포')
rows=[];custom=set()
for uid,name,url in r:
    h=re.sub(r'^https?://','',url.strip()).split('/')[0].lower()
    h=h.encode('idna').decode() if h else h
    if not h: continue
    if not h.endswith('.thefishing.kr'): custom.add(h)
    s=re.sub(r'\s+',' ',name.replace('온라인 승선명부','')).strip()
    m=AREA.search(s); area=fleet=ship=fish=''; seat=''
    sm=re.search(r'(\d+)인승',s); seat=(sm.group(1)+'인승') if sm else ''
    if m:
        area=m.group(1)
        nx=s[m.end():].strip().split(' ')
        if nx and nx[0].endswith(PORT) and not re.search(r'\d',nx[0]) and len(nx)>1: area+=' '+nx[0]
        pre=s[:m.start()].strip(); post=s[m.start()+len(area):].strip()
        if pre:   # 형식 A: 선단 배 지역 N인승 종일배낚시 어종
            t=pre.split(' ')
            fleet=' '.join(t[:2]) if len(t)>2 else t[0]
            ship=' '.join(t[2:]) if len(t)>2 else (t[1] if len(t)>1 else t[0])
            fish=re.sub(r'^\d+인승\s*','',post); fish=re.sub(r'^종일배낚시\s*','',fish)
        else:     # 형식 B: 지역 배 어종 외 N종 선단(종일배낚시) 캐시적립 N인승
            b=re.sub(r'\s*캐시적립.*$','',post); b=re.sub(r'\s*\d+인승$','',b)
            fm=re.search(r'^(\S+)\s+(.*?외 \d+종)\s+(.*)$',b)
            if fm: ship,fish,fleet=fm.group(1),fm.group(2),fm.group(3)
            else:
                t=b.split(' ',1); ship=t[0]; fleet=t[1] if len(t)>1 else ''
            fleet=fleet.replace('(종일배낚시)','').strip()
    else: ship=s
    rows.append([uid,ship.strip(),area,fleet.strip(),fish.strip()[:40],seat,h])
# 같은 주소 선단 이름: 가장 많이 나온 것
fc=collections.defaultdict(collections.Counter)
for x in rows:
    if x[3]: fc[x[6]][x[3]]+=1
for x in rows:
    if fc[x[6]]: x[3]=fc[x[6]].most_common(1)[0][0]
json.dump({"t":int(time.time()*1000),"n":len(rows),"j":rows},open(out,'w',encoding='utf-8'),ensure_ascii=False,separators=(',',':'))
print(len(rows),'hosts',len({x[6] for x in rows}),'custom',sorted(custom))
for x in rows[:6]+rows[300:304]: print(x)
