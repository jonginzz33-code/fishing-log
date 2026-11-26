# 382: 자동 예약 판단을 많은 선단에 돌려 보기 (읽기만 · 예약 · 인원확인 POST 안 함)
import json, re, urllib.parse, urllib.request, concurrent.futures as cf, os, collections, random, html as H
R = "https://talla-relay.jonginzz33.workers.dev/?u="
def get(u):
    try:
        rq = urllib.request.Request(R + urllib.parse.quote(u, safe=''), headers={'User-Agent': 'Mozilla/5.0 (Linux; Android 14) Mobile'})
        with urllib.request.urlopen(rq, timeout=40) as r: return r.read().decode('utf-8', 'ignore')
    except Exception as e: return ''
os.makedirs('out/u', exist_ok=True)
F = json.load(open('site/data/fleets.json'))['f']
s24 = [f[0] for f in F if f[4] == '선상24']; tf = [f[0] for f in F if f[4] == '더피싱']
random.seed(23); random.shuffle(s24); random.shuffle(tf)
DATES = ['2026-12-03', '2026-12-05', '2026-12-06', '2026-12-10', '2026-12-13']
KNOWN = {"ship_schedule_no", "petc_data", "person_count", "coupon_no", "phone", "price_option", "name", "deposit_name", "phone1", "phone2", "phone3",
         "memo", "all_check", "agree_rule[]", "pay_method", "reservation_method", "select_seat_nos[]", "cash_receipt_type", "cash_receipt_no", "default_schedule_no"}
def s24one(h):
    for d in DATES:
        day = get(f"https://{h}/ship/schedule_fleet/{d}/0/simple_day1")
        nos = re.findall(r'data-schedule_no="(\d+)"', day)
        for no in nos[:2]:
            html = get(f"https://{h}/mypage/reservation_ready/{no}")
            m = re.search(r'var data = (\{.*?\});\s*\n', html, re.S)
            if not m: continue
            data = json.loads(m.group(1)); sc = data.get('schedule') or {}
            need = 2
            def dec():
                if sc.get('is_reservation_login'): return 'login'
                if sc.get('is_reservation_phone_auth') or 'var is_phone_auth = false' in html: return 'phoneauth'
                if sc.get('is_reservation_contest'): return 'contest'
                if (data.get('reservation_method') or {}).get('is_use', True) is False: return 'nobank'
                if 'var is_imminent_departure = true' in html: return 'imminent'
                if int(sc.get('remain_embarkation_num') or 0) < need: return 'remain'
                per = int(sc.get('person_per_reservation') or 0)
                if 0 < per < need: return 'per'
                i = html.find('id="form-reservation"'); j = html.find('</form>', i); form = html[i:j]
                if 'name="name"' not in form: return 'login2'
                for at in re.findall(r'<input\b([^>]*)>', form):
                    n = re.search(r'name="([^"]+)"', at); t = re.search(r'type="([a-z]+)"', at)
                    if n and n.group(1) not in KNOWN and (t.group(1) if t else 'text') in ('text', 'number', 'tel'): return 'unknown:' + n.group(1)
                opts = [json.loads(x) for x in re.findall(r"data-price_option='(\{[^']*\})'", html)]
                real = [o for o in opts if o.get('no', 0) > 0]
                if real and not any(o.get('required_person') == 1 and not o.get('is_rent') and o.get('price', 0) > 0 for o in real): return 'opt-none'
                return 'OK' + ('+opt' if real else '') + ('+seat' if 'select_seat_nos[]' in form else '')
            return {'h': h, 'd': dec()}
    return {'h': h, 'd': 'nosched'}
def tfone(h):
    for d in ['20261205', '20261206', '20261210']:
        bk = get(f"https://{h}/index.php?mid=bk&year={d[:4]}&month={d[4:6]}&day={d[6:]}")
        m = re.search(r"/_core/module/(reservation_boat_[a-z0-9_.]+)/popup\.step1\.php\?date=(\d{8})&(?:amp;)?PA_N_UID=(\d+)", bk)
        if not m: continue
        mod, d8, uid = m.groups(); base = f"https://{h}/_core/module/{mod}"
        p1 = get(f"{base}/popup.step1.php?date={d8}&PA_N_UID={uid}")
        if 'insert_form' not in p1: return {'h': h, 'mod': mod, 'd': 'noform:' + (re.search(r"alert\(['\"](.*?)['\"]", p1) or [None, ''])[1][:40]}
        page = p1; ph = '0'
        phs = re.findall(r'<input type="radio"[^>]*name="PH_N_UID"[^>]*value="(\d+)"', p1)
        if phs and 'class="PS_N_UID"' not in p1:
            ph = phs[0]; page = get(f"{base}/popup.step1.php?date={d8}&PA_N_UID={uid}&PH_N_UID={ph}")
        radios = re.findall(r'<input[^>]*class="PS_N_UID"[^>]*id="([^"]+)"[^>]*value="([^"]+)"', page)
        if radios:
            page = get(f"{base}/popup.step1.php?date={d8}&PA_N_UID={uid}&PH_N_UID={ph}&PS_N_UID={urllib.parse.quote(radios[0][1])}")
        elif 'name="BI_NAME"' not in page: return {'h': h, 'mod': mod, 'd': 'nops'}
        fi = page.find('id="insert_form"'); fj = page.find('</form>', fi); form = page[fi:fj]
        tag = page[page.rfind('<form', 0, fi):fi + 200]
        act = re.search(r'action="([^"]+)"', tag)
        r = {'h': h, 'mod': mod, 'act': act.group(1).split('/')[-1] if act else None,
             'name': 'name="BI_NAME"' in form, 'tel': 'name="BI_TEL2"' in form,
             'pre': bool(re.search(r"var data = '(date=[^']*?)&BI_IN=", page)), 'naun': bool(re.search(r'naun=(\d+)', page)),
             'ajax': bool(re.search(r'url:\s*"(/_core/module/[^"]+popup\.step1\.ajax\.php)"', page)),
             'bus': 'onclick="ticket_seat_bus_click(' in form,
             'seatimg': 'id="seat_img"' in page, 'seatv': len(re.findall(r'<input id="s' + d8 + uid + r'([^"]+)"[^>]*name="seat\[\]"', form)), 'seatopen': len(re.findall(r"onclick=\"ticket_seat(?:_ps)?_click\('\d+',\s*'\d+',\s*'([^']+)'\);?\"", form)),
             'card': 'name="site_cd"' in form or 'name="enc_data"' in form or 'action=""' in tag,
             'seati': len(re.findall(r'name="seat\[\]"', form)), 'paym': 'id="pay_method"' in page, 'ps': len(radios),
             'extra': sorted(set(re.findall(r'name="([^"]+)"', form)) - {'BI_3JA', 'BI_AD', 'BI_BANK', 'BI_IN', 'BI_MEMO', 'BI_NAME', 'BI_TEL1', 'BI_TEL2', 'BI_TEL3', 'PA_N_UID', 'PS_N_UID', 'action', 'agree', 'all_agree', 'insert_form', 'link', 'temp_bi_stat', 'BIP_NAME[]', 'BIP_TEL1[]', 'BIP_TEL2[]', 'BIP_TEL3[]', 'dong_check', 'seat[]', 'select_ud_uid', 'BI_SO_IN', 'PH_N_UID'})}
        if r['card']: r['d'] = 'card'; return r
        ok = r['act'] and r['name'] and r['tel'] and r['pre'] and r['naun'] and not r['bus'] and (not r['seatimg'] or r['seati'] == 0 or r['seatv'] == r['seati'])
        r['d'] = 'OK' if ok else 'NG'
        if r['extra']: open(f"out/u/tf_{h.split('.')[0]}.html", 'w').write(page)
        if not ok: open(f"out/u/ng_{h.split('.')[0]}.html", 'w').write(page)
        return r
    return {'h': h, 'd': 'nosched'}
with cf.ThreadPoolExecutor(10) as ex:
    A = list(ex.map(s24one, s24[200:500])); B = list(ex.map(tfone, tf))
json.dump({'s24': A, 'tf': B}, open('out/survey3.json', 'w'), ensure_ascii=False, indent=1)
print('S24', collections.Counter(a['d'] for a in A).most_common())
print('TF', collections.Counter(b['d'] for b in B).most_common())
print('TFMOD', collections.Counter((b.get('mod'), b['d']) for b in B if 'mod' in b).most_common())
for b in B:
    if b['d'] == 'NG' or b.get('extra') or str(b['d']).startswith('noform'): print('TFX', json.dumps(b, ensure_ascii=False))
