// talla 중계기 (Cloudflare Workers · 무료) — 412
// 홈피(브라우저) · 앱이 선단 홈페이지를 대신 읽어 오는 곳. 허용한 사이트만 · 5분 기억.
//   GET /?u=https://sungbin.sunsang24.com/ship/schedule_fleet/2026-12-03/0/simple_day1
//   GET /ping → ok
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

const CORS = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': 'GET, OPTIONS' };

// ── 선단 페이지 읽기 (앱 Seats.parse · parseTfFleet 와 같은 규칙) ──
function txt(h) {
  return String(h).replace(/<br\s*\/?>/gi, ' ').replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&#(\d+);/g, (m, n) => String.fromCharCode(+n)).replace(/\s+/g, ' ').trim();
}
function parseS24(html) {
  const tide = {}; let m; const rt = /id="d(\d{4}-\d{2}-\d{2})"[\s\S]{0,600}?class="swell">([^<]*)</g;
  while ((m = rt.exec(html))) tide[m[1]] = m[2].trim();
  const out = [];
  for (const b of html.split('<div class="ship_unit_ship_no_').slice(1)) {
    const g = (re) => { const x = re.exec(b); return x ? x[1] : ''; };
    const date = g(/data-sdate="([^"]+)"/), shipNo = g(/data-ship_no="(\d+)"/);
    if (!date || !shipNo) continue;
    const name = txt(g(/<li class="title">\s*<span[^>]*>([^<]+)<\/span>/)) || '배';
    let fm = null, x; const rf = /<span class="fishspecies"><strong>([^<]*)<\/strong><\/span>\s*\/\s*<span class="way"><strong>([^<]*)<\/strong>/g;
    while ((x = rf.exec(b))) fm = x;
    let tm = ''; const rti = /운항시간\s*:\s*<\/strong><\/span>\s*([^<]+)</g; while ((x = rti.exec(b))) tm = x[1].replace(/&nbsp;/g, ' ').trim();
    const si = b.indexOf('<li class="seat">'), se = si >= 0 ? b.indexOf('</li>', si) : -1;
    const seatH = si >= 0 ? b.substring(si, se < 0 ? b.length : se) : '';
    const seat = seatH.replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ');
    const st = (/data-status_code="(\w+)"/.exec(seatH) || /data-ship_status_code="(\w+)"/.exec(seatH) || [0, 'ING'])[1];
    let rm = +((/남은자리\s*:\s*(\d+)/.exec(seat) || [0, 0])[1]), rs = +((/예약\s*:\s*(\d+)/.exec(seat) || [0, 0])[1]);
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
      let full = '', n0; const rn = /font-size:\s*15px;\s*font-weight:\s*bold;?"\s*>([\s\S]*?)<\/span>/g; while ((n0 = rn.exec(seg))) full = txt(n0[1]);
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
      const html = await Promise.race([getPage(href), new Promise((_, no) => setTimeout(() => no(new Error('timeout')), 20000))]);
      const seen = {};
      out[h] = (tf ? parseTf(html) : parseS24(html)).filter((s) => s.date === d && !seen[s.shipNo + '|' + s.schNo] && (seen[s.shipNo + '|' + s.schNo] = 1));
    } catch (e) { out[h] = null; }
  }));
  return new Response(JSON.stringify(out), { headers: { ...CORS, 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'public, max-age=120' } });
}

export default {
  async fetch(req, env, ctx) {
    if (req.method === 'OPTIONS') return new Response(null, { headers: CORS });
    const url = new URL(req.url);
    if (url.pathname === '/ping') return new Response('ok2', { headers: CORS });
    if (url.pathname === '/day') return day(url, ctx);
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
