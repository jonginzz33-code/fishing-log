# 382: 자동 예약 · 선단마다 예약 화면 모양 조사 (중계기로 읽기만 · 예약은 안 함)
import json, re, urllib.parse, urllib.request, concurrent.futures as cf, random, os, collections
R = "https://talla-relay.jonginzz33.workers.dev/?u="
def get(u):
    try:
        with urllib.request.urlopen(urllib.request.Request(R + urllib.parse.quote(u, safe=''), headers={'User-Agent': 'Mozilla/5.0 (Linux; Android 14) Mobile'}), timeout=40) as r: return r.read().decode('utf-8', 'ignore')
    except Exception as e: return ''
F = json.load(open('site/data/fleets.json'))['f']
s24 = [f[0] for f in F if f[4] == '선상24']; tf = [f[0] for f in F if f[4] == '더피싱']
random.seed(7); random.shuffle(s24); random.shuffle(tf)
DATES = ['2026-12-05', '2026-12-06', '2026-12-12']
os.makedirs('out/s', exist_ok=True)
def s24one(h):
    for d in DATES:
        day = get(f"https://{h}/ship/schedule_fleet/{d}/0/simple_day1")
        nos = re.findall(r'data-schedule_no="(\d+)"', day)
        if not nos: continue
        html = get(f"https://{h}/mypage/reservation_ready/{nos[0]}")
        m = re.search(r'var data = (\{.*?\});\s*\n', html, re.S)
        if not m: return {'h': h, 'err': 'no data', 'len': len(html)}
        try: d0 = json.loads(m.group(1))
        except Exception: return {'h': h, 'err': 'json'}
        sc = d0.get('schedule', {}); rm = d0.get('reservation_method', {})
        i = html.find('id="form-reservation"'); j = html.find('</form>', i)
        form = html[i:j] if i > 0 else ''
        names = sorted(set(re.findall(r'name="([^"]+)"', form)))
        r = {'h': h, 'code': d0.get('code'), 'msg': d0.get('message'), 'login': sc.get('is_reservation_login'), 'pauth': sc.get('is_reservation_phone_auth'),
             'contest': sc.get('is_reservation_contest'), 'pname': sc.get('is_person_name_use'), 'small': sc.get('is_small_ship'), 'price': sc.get('price'),
             'opts': len(sc.get('price_option') or []), 'seat': bool(sc.get('arrange_seating')) and isinstance(sc.get('arrange_seating'), dict),
             'rm': rm.get('code'), 'rmuse': rm.get('is_use'), 'bankadd': 'bank_add' in rm, 'names': names}
        key = f"{r['login']}{r['pauth']}{r['contest']}{r['pname']}{r['seat']}{r['opts']>0}{r['small']}"
        fn = f"out/s/s24_{key}.html"
        if not os.path.exists(fn): open(fn, 'w').write(html)
        return r
    return {'h': h, 'err': 'no schedule'}
def tfone(h):
    bk = get(f"https://{h}/index.php?mid=bk&year=2026&month=12&day=05")
    pops = sorted(set(re.findall(r"(/_core/module/[a-z0-9_.]+/[a-z0-9_.]+\.php)\?date=", bk)))
    r = {'h': h, 'len': len(bk), 'pops': pops}
    m = re.search(r"(/_core/module/[a-z0-9_.]+/(?:popup\.step1|online\.new\.1)\.php\?[^'\"]+)", bk)
    if m:
        u = m.group(1).replace('&amp;', '&'); page = get(f"https://{h}{u}")
        r['url'] = u; r['act'] = re.findall(r'<form[^>]*action="([^"]+)"', page)
        r['names'] = sorted(set(re.findall(r'name="([^"]+)"', page)))
        ps = re.findall(r'name="PS_N_UID" value="(\d+)"', page); r['ps'] = len(ps)
        r['seat'] = 'seat[]' in page; r['login'] = bool(re.search(r'로그인', page[:4000]))
        mod = u.split('/')[3]
        fn = f"out/s/tf_{mod}_{u.split('/')[4].split('?')[0]}.html"
        if not os.path.exists(fn): open(fn, 'w').write(page)
    return r
with cf.ThreadPoolExecutor(8) as ex:
    A = list(ex.map(s24one, s24[:45])); B = list(ex.map(tfone, tf[:30]))
json.dump({'s24': A, 'tf': B}, open('out/survey.json', 'w'), ensure_ascii=False, indent=1)
c = collections.Counter(f"login={a.get('login')} pauth={a.get('pauth')} contest={a.get('contest')} pname={a.get('pname')} seat={a.get('seat')} opts={a.get('opts',0)>0} small={a.get('small')} rmuse={a.get('rmuse')}" for a in A if 'err' not in a)
for k, v in c.most_common(): print(v, k)
print('s24 err', collections.Counter(a.get('err') for a in A if 'err' in a))
print('tf', collections.Counter(tuple(b.get('pops', [])) for b in B).most_common(10))
