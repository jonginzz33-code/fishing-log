// talla 중계기 (Cloudflare Workers · 무료) — 412
// 홈피(브라우저) · 앱이 선단 홈페이지를 대신 읽어 오는 곳. 허용한 사이트만 · 5분 기억.
//   GET /?u=https://sungbin.sunsang24.com/ship/schedule_fleet/2026-12-03/0/simple_day1
//   GET /ping → ok
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

export default {
  async fetch(req, env, ctx) {
    if (req.method === 'OPTIONS') return new Response(null, { headers: CORS });
    const url = new URL(req.url);
    if (url.pathname === '/ping') return new Response('ok', { headers: CORS });
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
