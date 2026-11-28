// talla 중계기 (Cloudflare Workers · 무료) — 412
// 홈피(브라우저) · 앱이 선단 홈페이지를 대신 읽어 오는 곳. 허용한 사이트만 · 5분 기억.
//   GET /?u=https://sungbin.sunsang24.com/ship/schedule_fleet/2026-12-03/0/simple_day1
//   GET /ping → ok
//   GET /a2o?c=lg&kind=mnp&plan=115&contract=pub&support=20&ad=d1&m=SM-S942N → a2o 가 계산한 단가 표 (raw=1 이면 원래 자료 · X-Key 필요 · 비밀값 A2O_ID · A2O_PW · DANGA_KEY)
//   GET /s24?d=2026-12-03&area=보령인근,… → 선상24 목록(지역 · 날짜 배와 남은 자리)을 우리 서버가 받아 둠 (433 · 5분 같이 기억 · 앱은 선상24에 직접 안 감)
//   GET /day?d=2026-12-03&h=a.sunsang24.com,b.thefishing.kr,… → 여러 선단 그 날 일정을 한 번에 읽어 정리한 JSON (선단 40곳까지 · 5분 기억)
// 선상24 · 더피싱 원 홈페이지(www · api 등)는 막아요 (410).
const SKIP = ['www', 'api', 'm', 'img', 'imga', 'imgb', 'assets', 'static', 'cdn', 'admin', 'pay', 'partner', 'service', 'serviceback', 'js', 'css', 'image', 'theimage'];
const TF_DOMAINS = ['djfishing.com', 'jnaksi.com', 'morningstarho.com', 'raraho.kr', 'teammansu.kr', 'woorinaksi.com', 'www.haejinho.com', 'xn--9m1bx7rzqh1yhbmj.com', 'youngdong2ho.net'];
const OTHERS = ['hunter-booking.onrender.com'];

function allowed(host) {
  host = host.toLowerCase();
  if (TF_DOMAINS.includes(host) || OTHERS.includes(host)) return true;
  for (const base of ['.sunsang24.com', '.thefishing.kr']) {
    if (host.endsWith(base)) {
      const sub = host.slice(0, -base.length);
      return /^[a-z0-9-]{2,40}$/.test(sub) && !SKIP.includes(sub);
    }
  }
  return false;
}

const CORS = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': 'GET, OPTIONS', 'Access-Control-Allow-Headers': 'X-Key' };

// ── 선단 페이지 읽기 (앱 Seats.parse · parseTfFleet 와 같은 규칙) ──
function txt(h) {
  return String(h).replace(/<br\s*\/?>/gi, ' ').replace(/<img[^>]*>/gi, '').replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&#(\d+);/g, (m, n) => String.fromCharCode(+n)).replace(/\s+/g, ' ').trim();
}
function parseS24(html) {
  const tide = {}; let m; const rt = /id="d(\d{4}-\d{2}-\d{2})"[\s\S]{0,600}?class="swell">([^<]*)</g;
  while ((m = rt.exec(html))) tide[m[1]] = m[2].trim();
  const out = [];
  for (const b of html.split(/<(?:div|table) class="ship_unit_ship_no_/).slice(1)) {
    const g = (re) => { const x = re.exec(b); return x ? x[1] : ''; };
    // 두 가지 모양: A <div …> <li class="seat"> · B <table …> <div class="title"> · id="fish" · li.shiptime · 남은자리 N명
    const date = g(/data-sdate="([^"]+)"/), shipNo = g(/data-ship_no="(\d+)"/) || g(/^(\d+)/);
    if (!date || !shipNo) continue;
    const name = txt(g(/<li class="title">\s*<span[^>]*>([^<]+)<\/span>/) || g(/<div class="title">\s*([^<]+?)\s*<\/div>/)) || '배';
    let fm = null, x; const rf = /<span class="fishspecies"><strong>([^<]*)<\/strong><\/span>\s*\/\s*<span class="way"><strong>([^<]*)<\/strong>/g;
    while ((x = rf.exec(b))) fm = x;
    if (!fm) fm = /id="fish">([^<]*)<\/div>\s*\/?\s*([^<]*)</.exec(b);
    let tm = ''; const rti = /운항시간\s*:\s*<\/strong><\/span>\s*([^<]+)</g; while ((x = rti.exec(b))) tm = x[1].replace(/&nbsp;/g, ' ').trim();
    if (!tm) { const tb = /class="shiptime">[\s\S]*?class="detail">\s*([^<]+?)\s*</.exec(b); if (tb) tm = tb[1].trim(); }
    const si = b.indexOf('<li class="seat">'), se = si >= 0 ? b.indexOf('</li>', si) : -1;
    const seatH = si >= 0 ? b.substring(si, se < 0 ? b.length : se) : b;
    const seat = seatH.replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ');
    const st = (/data-status_code="(\w+)"/.exec(seatH) || /data-ship_status_code="(\w+)"/.exec(seatH) || [0, 'ING'])[1];
    let rm = +((/남은자리\s*:?\s*(\d+)/.exec(seat) || [0, 0])[1]), rs = +((/예약\s*:\s*(\d+)/.exec(seat) || [0, 0])[1]);
    if (st === 'END') { const c = +((/(\d+)\s*명/.exec(seat) || [0, rs])[1]); rs = Math.max(rs, c); rm = 0; }
    out.push({ shipNo, schNo: g(/data-schedule_no="(\d+)"/), ship: name, date, fish: fm ? fm[1].trim() : '', way: fm ? fm[2].trim() : '', time: tm, remain: rm, reserved: rs, status: st, tide: tide[date] || '' });
  }
  return out;
}
function parseTf(html) {
  const out = [], secs = []; let m; const rs = /id="new-div-(\d{8})"/g;
  while ((m = rs.exec(html))) secs.push(m);
  secs.forEach((s, i) => {
    const body = html.substring(s.index + s[0].length, i + 1 < secs.length ? secs[i + 1].index : html.length);
    let prev = 0, r; const rr = /admin-right-(\d{8})-(\d+)-\d+"[^>]*>\s*<img[^>]*alt="([^"]*)"/g;
    while ((r = rr.exec(body))) {
      const seg = body.substring(prev, r.index); prev = r.index + r[0].length;
      let full = '', n0; const rn = /font-size:\s*15px;\s*font-weight:\s*bold;?[^"]*"\s*>([\s\S]*?)<\/span>/g; while ((n0 = rn.exec(seg))) full = txt(n0[1]);
      const ship = full.split('[')[0].split('(')[0].trim() || full || ('배 ' + r[2]);
      const cell = (alt) => { const c = new RegExp('alt="' + alt + '"></td>\\s*<td[^>]*>([\\s\\S]*?)</td>').exec(seg); return c ? txt(c[1]) : ''; };
      const alt = r[3], n = (/(\d+)\s*명/.exec(alt) || [0, null])[1];
      const st = alt.indexOf('남은') >= 0 && n != null ? (+n > 0 ? 'ING' : 'END') : /완료|마감/.test(alt) ? 'END' : /기상/.test(alt) ? 'BAD_WEATHER' : /정비|점검/.test(alt) ? 'CHECK' : 'NONE';
      const d8 = r[1];
      out.push({ shipNo: r[2], schNo: r[2], ship, date: d8.slice(0, 4) + '-' + d8.slice(4, 6) + '-' + d8.slice(6), fish: cell('낚시종류').replace(/★/g, '').trim() || cell('공지').slice(0, 30),
        way: full.slice(ship.length).trim(), time: '', remain: n == null ? 0 : +n, reserved: 0, status: st, tide: '' });
    }
  });
  return out;
}
function isTfHost(h) { return h.endsWith('.thefishing.kr') || TF_DOMAINS.includes(h); }
// 무료 요금제는 요청 하나에 바깥 요청 50번까지 → 캐시도 fetch 안에서 (cf.cacheTtl · 5분)
async function getPage(href) {
  const tf = isTfHost(new URL(href).hostname);
  const r = await fetch(href, { headers: { 'User-Agent': tf ? 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36' : 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Mobile', 'Accept-Language': 'ko-KR,ko' },
    redirect: 'follow', cf: { cacheTtl: 300, cacheEverything: true } });
  return await r.text();
}
async function day(url, ctx) {
  const d = url.searchParams.get('d') || '';
  if (!/^\d{4}-\d{2}-\d{2}$/.test(d)) return new Response('bad date', { status: 400, headers: CORS });
  const hosts = (url.searchParams.get('h') || '').split(',').map((x) => x.trim().toLowerCase()).filter((h) => h && allowed(h)).slice(0, 45);
  const out = {};
  await Promise.all(hosts.map(async (h) => {
    const tf = isTfHost(h);
    const href = tf ? 'https://' + h + '/index.php?mid=bk&year=' + d.slice(0, 4) + '&month=' + d.slice(5, 7) + '&day=' + d.slice(8, 10)
      : 'https://' + h + '/ship/schedule_fleet/' + d + '/0/simple_day1';
    try {
      const html = await Promise.race([getPage(href), new Promise((_, no) => setTimeout(() => no(new Error("timeout")), 10000))]);
      const seen = {};
      out[h] = (tf ? parseTf(html) : parseS24(html)).filter((s) => s.date === d && !seen[s.shipNo + '|' + s.schNo] && (seen[s.shipNo + '|' + s.schNo] = 1));
    } catch (e) { out[h] = null; }
  }));
  return new Response(JSON.stringify(out), { headers: { ...CORS, 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'public, max-age=120' } });
}

// v7.08 (433): 선상24 목록 — 한 쪽 30척 · 6쪽(180척)까지 · 가까운 날 5분 · 먼 날 15분 같이 기억
const S24_KEEP = ['schedule_no', 'sdate', 'stime', 'etime', 'fish_type', 'fishing_method', 'price', 'tide_water', 'port_name', 'embarkation_num', 'remain_embarkation_num', 'schedule_status_code', 'status_code', 'is_reservation_possible'];
const SHIP_KEEP = ['no', 'name', 'image', 'board_count', 'embarkation_num', 'area_main', 'area_sub', 'port_name'];
function pick(o, keys) { const r = {}; if (o) for (const k of keys) if (o[k] !== undefined) r[k] = o[k]; return r; }
async function s24(url) {
  const d = url.searchParams.get('d') || '';
  if (!/^\d{4}-\d{2}-\d{2}$/.test(d)) return new Response('bad date', { status: 400, headers: CORS });
  const area = (url.searchParams.get('area') || '').slice(0, 300), fish = (url.searchParams.get('fish') || '').slice(0, 300);
  const days = (Date.parse(d) - Date.now()) / 86400000;
  const ttl = days < 2 ? 300 : 900;
  const one = async (page) => {
    let q = 'https://api.sunsang24.com/ship/list?type=general&page=' + page + '&sdate=' + d + ',' + d;
    if (area) q += '&area=' + encodeURIComponent(area);
    if (fish) q += '&fish=' + encodeURIComponent(fish);
    const r = await fetch(q, { headers: { 'User-Agent': 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Mobile', 'Accept-Language': 'ko-KR,ko', 'Accept': 'application/json' },
      cf: { cacheTtl: ttl, cacheEverything: true } });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    return await r.json();
  };
  try {
    const first = await one(1);
    const total = +first.total || 0;
    const pages = Math.min(6, Math.ceil(total / 30));
    const rest = await Promise.all(Array.from({ length: Math.max(0, pages - 1) }, (_, i) => one(i + 2).catch(() => ({ list: [] }))));
    const list = [first, ...rest].flatMap((o) => o.list || []).map((x) => ({ ...pick(x, S24_KEEP), ship: pick(x.ship, SHIP_KEEP) }));
    return new Response(JSON.stringify({ t: Date.now(), total, list }), { headers: { ...CORS, 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'public, max-age=' + Math.min(ttl, 120) } });
  } catch (e) {
    return new Response(JSON.stringify({ error: String(e.message || e) }), { status: 502, headers: { ...CORS, 'Content-Type': 'application/json' } });
  }
}


// 단가 비교 (talla.co.kr/danga) — jjj.a2o.co.kr 에 대신 로그인해서 비교 자료(cmpdata)를 가져와요.
//   GET /a2o?c=lg|sk|kt  · 머리 X-Key = DANGA_KEY 일 때만 · 아이디 · 비밀번호는 Cloudflare 비밀값(A2O_ID · A2O_PW)
async function a2oLogin(env) {
  const body = new URLSearchParams({ uid: env.A2O_ID, pw: env.A2O_PW });
  const r = await fetch('https://jjj.a2o.co.kr/jjj/login', {
    method: 'POST', redirect: 'manual', body,
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0 Safari/537.36' },
  });
  const sc = typeof r.headers.getSetCookie === 'function' ? r.headers.getSetCookie() : [r.headers.get('set-cookie') || ''];
  const cookie = sc.map((x) => x.split(';')[0]).filter(Boolean).join('; ');
  const txt = r.status === 200 ? await r.text() : '';
  if (/아이디 또는 비밀번호/.test(txt)) return null;
  return cookie || null;
}
// 비교 화면 그대로: 조건(kind · plan · contract · support · ad)을 넘기면 a2o 가 계산한 표를 읽어 JSON 으로
const A2O_ONE = { kind: /^(new|mnp|re)$/, plan: /^\d{2,3}$/, contract: /^[a-z]{2,6}$/, support: /^\d{1,2}$/ };
async function a2oPage(c, cookie, url) {
  const q = new URLSearchParams({ c, st: '1', pf: '0' });
  for (const [k, re] of Object.entries(A2O_ONE)) { const v = url.searchParams.get(k); if (v && re.test(v)) q.set(k, v); }
  url.searchParams.getAll('ad').filter((v) => /^[a-z]{1,3}\d{1,3}$/.test(v)).slice(0, 40).forEach((v) => q.append('ad', v));
  const r = await fetch('https://jjj.a2o.co.kr/compare?' + q.toString(), {
    headers: { Cookie: cookie, 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0 Safari/537.36' }, redirect: 'manual',
  });
  if (r.status !== 200) return null;
  const html = await r.text();
  const m = html.match(/<script[^>]*id=["']cmpdata["'][^>]*>([\s\S]*?)<\/script>/);
  return m ? { html, data: m[1] } : null;
}
function a2oTable(html, data, want) {
  let d = {}; try { d = JSON.parse(data); } catch (e) {}
  const cos = []; let m; const rh = /<th[^>]*data-k="(\w+)"/g; while ((m = rh.exec(html))) if (!cos.includes(m[1])) cos.push(m[1]);
  const dates = {}; cos.forEach((k) => { if (d[k] && d[k].date) dates[k] = d[k].date; });
  const names = {}; (d.models || []).forEach((x) => { if (Array.isArray(x)) names[x[0]] = x[1]; });
  const rows = [];
  for (const b of html.split(/<tr[^>]*data-id="/).slice(1)) {
    const id = b.slice(0, b.indexOf('"'));
    if (want.length && !want.some((w) => id === w || id.startsWith(w))) continue;
    const body = b.slice(0, (b.indexOf('</tr>') + 1 || b.length + 1) - 1);
    const cells = {}; let i = 0, t; const rt = /<td[^>]*data-s="([^"]*)"[^>]*>([\s\S]*?)<\/td>/g;
    while ((t = rt.exec(body))) {
      const k = cos[i++] || ('c' + i), h = t[2];
      if (/class="na"/.test(h) && !/class="tot"/.test(h)) { cells[k] = null; continue; }
      const tot = (/class="tot"[^>]*>([^<]*)</.exec(h) || [0, t[1]])[1].trim();
      const brk = []; let x; const rb = /<div>\s*<span>([\s\S]*?)<\/span>\s*<span[^>]*>([^<]*)<\/span>\s*<\/div>/g;
      while ((x = rb.exec(h))) brk.push([txt(x[1]), x[2].trim()]);
      cells[k] = { tot, brk };
    }
    rows.push({ id, name: names[id] || '', cells });
  }
  return { t: Date.now(), cos, dates, rows };
}
async function a2o(req, url, env) {
  const J = (o, st) => new Response(typeof o === 'string' ? o : JSON.stringify(o), { status: st || 200, headers: { ...CORS, 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' } });
  if (!env.DANGA_KEY || req.headers.get('X-Key') !== env.DANGA_KEY) return J({ error: 'key' }, 403);
  if (!env.A2O_ID || !env.A2O_PW) return J({ error: 'no login secrets' }, 500);
  const c = url.searchParams.get('c') || 'lg';
  if (!/^(lg|sk|kt)$/.test(c)) return J({ error: 'bad c' }, 400);
  const want = (url.searchParams.get('m') || '').split(',').map((x) => x.trim()).filter(Boolean).slice(0, 20);
  try {
    for (let i = 0; i < 2; i++) {          // 가끔 첫 로그인이 실패해요 → 한 번 더
      const ck = await a2oLogin(env);
      if (!ck) continue;
      const pg = await a2oPage(c, ck, url);
      if (!pg) continue;
      if (url.searchParams.get('raw') === '1') return J(pg.data);   // 업체 · 부가 목록 등 원래 자료
      return J(a2oTable(pg.html, pg.data, want));
    }
    return J({ error: 'login or page failed' }, 502);
  } catch (e) { return J({ error: String(e.message || e) }, 502); }
}

export default {
  async fetch(req, env, ctx) {
    if (req.method === 'OPTIONS') return new Response(null, { headers: CORS });
    const url = new URL(req.url);
    if (url.pathname === '/ping') return new Response('ok4', { headers: CORS });
    if (url.pathname === '/day') return day(url, ctx);
    if (url.pathname === '/s24') return s24(url);
    if (url.pathname === '/a2o') return a2o(req, url, env);
    const u = url.searchParams.get('u');
    let target;
    try { target = new URL(u); } catch (e) { return new Response('bad url', { status: 400, headers: CORS }); }
    if (!/^https?:$/.test(target.protocol) || !allowed(target.hostname)) return new Response('not allowed', { status: 403, headers: CORS });

    const cache = caches.default;
    const key = new Request('https://cache.talla/' + encodeURIComponent(target.href));
    const hit = await cache.match(key);
    if (hit) return hit;

    const tf = target.hostname.endsWith('thefishing.kr') || TF_DOMAINS.includes(target.hostname);
    let r;
    try {
      r = await fetch(target.href, {
        headers: {
          'User-Agent': tf ? 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36'
                           : 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Mobile',
          'Accept-Language': 'ko-KR,ko',
        },
        redirect: 'follow',
      });
    } catch (e) {
      return new Response('fetch fail: ' + e.message, { status: 502, headers: CORS });
    }
    const body = await r.arrayBuffer();
    const out = new Response(body, {
      status: r.status,
      headers: { ...CORS, 'Content-Type': r.headers.get('Content-Type') || 'text/html; charset=utf-8', 'Cache-Control': 'public, max-age=300' },
    });
    if (r.ok) ctx.waitUntil(cache.put(key, out.clone()));
    return out;
  },
};
