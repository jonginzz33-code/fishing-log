# 382: 자동 예약 조사 2 · 더피싱 낚시종류 고른 화면 · 선상24 빈 응답 (읽기만 · 예약 안 함)
import json, re, urllib.parse, urllib.request, concurrent.futures as cf, os, collections
R = "https://talla-relay.jonginzz33.workers.dev/?u="
def get(u):
    try:
        rq = urllib.request.Request(R + urllib.parse.quote(u, safe=''), headers={'User-Agent': 'Mozilla/5.0 (Linux; Android 14) Mobile'})
        with urllib.request.urlopen(rq, timeout=40) as r: return r.read().decode('utf-8', 'ignore')
    except Exception as e: return 'ERR ' + str(e)
os.makedirs('out/t', exist_ok=True)
S = json.load(open('out0/survey.json'))
def tfps(b):
    u = b.get('url')
    if not u or 'popup.step1' not in u: return None
    h = b['h']; page = get(f"https://{h}{u}")
    ps = re.findall(r'name="PS_N_UID" value="([^"]+)"', page)
    lab = re.findall(r'<label[^>]*for="(?:SPED_UID|PS_N_UID)[^"]*"[^>]*>(.*?)</label>', page, re.S)
    r = {'h': h, 'mod': u.split('/')[3], 'ps': ps, 'lab': [re.sub(r'<[^>]+>|\s+', ' ', x).strip()[:60] for x in lab]}
    if ps:
        u2 = re.sub(r'&PS_N_UID=[^&]*', '', u.replace('&amp;', '&'))
        if 'PH_N_UID' not in u2: u2 += '&PH_N_UID=0'
        p2 = get(f"https://{h}{u2}&PS_N_UID={urllib.parse.quote(ps[0])}")
        i = p2.find('id="insert_form"'); j = p2.find('</form>', i)
        fm = p2[i:j]
        r['fields'] = sorted(set(re.findall(r'name="([^"]+)"', fm)))
        r['seats_open'] = len(re.findall(r"ticket_seat_click\(", fm))
        r['seat_inputs'] = len(re.findall(r'name="seat\[\]"', fm))
        r['seat_img'] = 'id="seat_img"' in p2
        r['bi_in'] = re.findall(r'<option[^>]*value="([^"]*)"', fm[fm.find('BI_IN'):fm.find('BI_IN') + 3000])[:12]
        r['naun'] = re.findall(r'naun=(\d+)', p2)[:1]
        r['ajax'] = re.findall(r'url:"(/_core/module/[^"]+ajax[^"]*)"', p2)[:2]
        open(f"out/t/{r['mod']}_{h.split('.')[0]}.html", 'w').write(p2)
    return r
def s24err(a):
    if a.get('err') != 'no data': return None
    h = a['h']
    for d in ['2026-12-05', '2026-12-06', '2026-12-12']:
        day = get(f"https://{h}/ship/schedule_fleet/{d}/0/simple_day1")
        nos = re.findall(r'data-schedule_no="(\d+)"', day)
        if nos:
            raw = get(f"https://{h}/mypage/reservation_ready/{nos[0]}")
            return {'h': h, 'body': raw[:300]}
    return {'h': h, 'body': 'no sched'}
def tfnopop(b):
    if b.get('pops'): return None
    bk = get(f"https://{b['h']}/index.php?mid=bk&year=2026&month=12&day=05")
    links = sorted(set(re.findall(r"(?:href|onclick)=\"([^\"]*(?:reserv|popup|예약)[^\"]*)\"", bk)))[:15]
    return {'h': b['h'], 'len': len(bk), 'links': links, 'txt': re.sub(r'<[^>]+>|\s+', ' ', bk[bk.find('new-div'):bk.find('new-div') + 2500])[:800]}
with cf.ThreadPoolExecutor(8) as ex:
    T = [x for x in ex.map(tfps, S['tf']) if x]
    E = [x for x in ex.map(s24err, S['s24']) if x]
    N = [x for x in ex.map(tfnopop, S['tf']) if x]
json.dump({'tf': T, 's24err': E, 'nopop': N}, open('out/survey2.json', 'w'), ensure_ascii=False, indent=1)
for t in T: print(t['mod'], t['h'], len(t['ps']), t.get('seats_open'), t.get('seat_inputs'), t.get('seat_img'), t.get('bi_in'), t.get('naun'))
print(collections.Counter(tuple(t.get('fields', [])) for t in T).most_common())
for e in E: print('S24ERR', e)
for n in N: print('NOPOP', n['h'], n['len'], n['links'])
