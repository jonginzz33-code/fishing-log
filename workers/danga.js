// talla-danga 중계기 (Cloudflare Workers · 무료) — 단가 비교(talla.co.kr/danga) 전용
//   GET /ping → ok-danga
//   GET /a2o?c=lg&kind=mnp&plan=115&contract=pub&support=20&ad=d1&m=SM-S942N → a2o 가 계산한 단가 표
//   머리 X-Key = DANGA_KEY 일 때만 · 비밀값 A2O_ID · A2O_PW · DANGA_KEY (Cloudflare 비밀)
// 조과일지 앱 · 선단 보기는 talla-relay 를 써요 (여기와 따로).
const CORS = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': 'GET, POST, OPTIONS', 'Access-Control-Allow-Headers': 'X-Key, X-Tok, Content-Type', 'Access-Control-Max-Age': '86400' };
function txt(h) {
  return String(h).replace(/<br\s*\/?>/gi, ' ').replace(/<img[^>]*>/gi, '').replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&#(\d+);/g, (m, n) => String.fromCharCode(+n)).replace(/\s+/g, ' ').trim();
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
// ── 계정마다 다른 단가 (관리자가 계정별로 줄 금액을 바꿔 둠 · 서버에서 바꿔서 보냄 → 계정은 원래 값을 못 봄) ──
const J = (o, st) => new Response(typeof o === 'string' ? o : JSON.stringify(o), { status: st || 200, headers: { ...CORS, 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' } });
const normL = (s) => String(s || '').replace(/\s+/g, ' ').trim();
const fmtAmt = (v) => { v = Math.round(v * 100) / 100; return (v > 0 ? '+' : v < 0 ? '-' : '±') + Math.abs(v); };
function applyOvr(tb, ovr, showOrig) {
  if (!ovr) return tb;
  for (const r of tb.rows || []) for (const co of Object.keys(r.cells || {})) {
    const c = r.cells[co], o = ovr[co]; if (!c || !o) continue;
    let tot = +c.tot;
    c.brk = c.brk.map((x, i) => {
      if (i === 0 && /단가표/.test(x[0])) return x;
      // 줄 이름 + 원래 금액 (예: 'LG DMB 추가정책(…) @+10') 이 먼저 — 한 줄이 +10 · +5 처럼 조건마다 다를 때 따로 바꾸려고
      let k = normL(x[0]) + ' @' + String(x[1]).trim(); if (!(k in o)) { k = normL(x[0]); if (!(k in o)) return x; }
      const old = +String(x[1]).replace(/[^\d.+-]/g, '') || 0, nv = +o[k];
      tot += nv - old;
      return showOrig ? [x[0], fmtAmt(nv), x[1]] : [x[0], fmtAmt(nv)];
    });
    c.tot = String(Math.round(tot * 100) / 100);
  }
  return tb;
}
// 비밀번호: PBKDF2 (무료 요금제 CPU 짧아서 횟수는 적당히)
const b64u = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
async function pwHash(pw, salt) {
  const k = await crypto.subtle.importKey('raw', new TextEncoder().encode(pw), 'PBKDF2', false, ['deriveBits']);
  return b64u(await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt: new TextEncoder().encode(salt), iterations: 5000 }, k, 256));
}
async function hmac(env, s) {
  const k = await crypto.subtle.importKey('raw', new TextEncoder().encode('tok:' + env.DANGA_KEY), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return b64u(await crypto.subtle.sign('HMAC', k, new TextEncoder().encode(s)));
}
async function makeTok(env, id) { const p = b64u(new TextEncoder().encode(JSON.stringify({ u: id, e: Date.now() + 30 * 86400000 }))); return p + '.' + await hmac(env, p); }
async function readTok(env, t) {
  const [p, s] = String(t || '').split('.'); if (!p || !s || s !== await hmac(env, p)) return null;
  try { const o = JSON.parse(atob(p.replace(/-/g, '+').replace(/_/g, '/'))); return o.e > Date.now() ? o.u : null; } catch (e) { return null; }
}
const getUser = async (env, id) => { const v = await env.KV.get('user:' + id); return v ? JSON.parse(v) : null; };
// 누가 부르나: 관리자(열쇠) · 계정(토큰). 관리자가 as=계정 이면 그 계정 화면으로 (원래 값 같이)
async function who(req, url, env) {
  if (env.DANGA_KEY && req.headers.get('X-Key') === env.DANGA_KEY) {
    const as = url.searchParams.get('as'); const u = as ? await getUser(env, as) : null;
    return { admin: true, user: u };
  }
  const id = await readTok(env, req.headers.get('X-Tok') || url.searchParams.get('t'));
  if (!id) return null;
  const u = await getUser(env, id); return u ? { admin: false, user: u } : null;
}
// 계정에게는 원래 정책 글(+12 같은 것)이 든 자료를 안 보냄 — 모델 · 부가 목록만
function safeRaw(data) {
  let d = {}; try { d = JSON.parse(data); } catch (e) {}
  const o = { models: d.models, addons: d.addons, groups: d.groups, vas: d.vas, vasnames: d.vasnames };
  ['dream', 'dmb', 'dmb2', 'gg', 'yuan', 'jm', 'ms'].forEach((k) => { if (d[k] && d[k].date) o[k] = { date: d[k].date }; });
  return o;
}
async function a2o(req, url, env, w) {
  if (!w) return J({ error: 'key' }, 403);
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
      if (url.searchParams.get('raw') === '1') return J(w.admin && !w.user ? pg.data : JSON.stringify(safeRaw(pg.data)));   // 업체 · 부가 목록 등
      const tb = applyOvr(a2oTable(pg.html, pg.data, want), w.user && w.user.ovr, w.admin);
      if (w.user) tb.as = w.user.id;
      return J(tb);
    }
    return J({ error: 'login or page failed' }, 502);
  } catch (e) { return J({ error: String(e.message || e) }, 502); }
}


// 단가표 이미지 (a2o 거래처 단가표 창과 같은 것)
//   GET /a2o/sheet?s=dream  (X-Key) → { label, date, at, images:[경로…] }
//   GET /a2o/img?p=/board/img/…&k=열쇠 → 그림 그대로 (img 태그는 머리를 못 보내서 열쇠를 주소에)
let CK = null, CK_T = 0;   // 로그인 쿠키 잠깐 기억 (그림 여러 장 → 로그인 한 번)
async function cookie(env, fresh) {
  if (!fresh && CK && Date.now() - CK_T < 10 * 60000) return CK;
  for (let i = 0; i < 2; i++) { const c = await a2oLogin(env); if (c) { CK = c; CK_T = Date.now(); return c; } }
  return null;
}
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0 Safari/537.36';
async function a2oSheet(url, env) {
  const J = (o, st) => new Response(JSON.stringify(o), { status: st || 200, headers: { ...CORS, 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' } });
  const s = url.searchParams.get('s') || '';
  if (!/^[a-z0-9_]{1,20}$/.test(s)) return J({ error: 'bad s' }, 400);
  for (let i = 0; i < 2; i++) {
    const ck = await cookie(env, i > 0); if (!ck) break;
    const r = await fetch('https://jjj.a2o.co.kr/compare/sheet/' + s, { headers: { Cookie: ck, 'User-Agent': UA }, redirect: 'manual' });
    if (r.status !== 200) continue;
    let d; try { d = await r.json(); } catch (e) { continue; }
    if (!d.ok) return J({ error: d.err || 'no sheet' }, 404);
    return J({ name: d.name, label: d.label, date: d.date, at: d.at, images: (d.images || []).filter((p) => /^\/board\/img\/[\w./-]+$/.test(p)) });
  }
  return J({ error: 'login or page failed' }, 502);
}
async function a2oImg(url, env) {
  const p = url.searchParams.get('p') || '';
  if (!/^\/board\/img\/[\w./-]+\.(jpe?g|png|gif|webp)$/i.test(p) || p.includes('..')) return new Response('bad path', { status: 400, headers: CORS });
  for (let i = 0; i < 2; i++) {
    const ck = await cookie(env, i > 0); if (!ck) break;
    const r = await fetch('https://jjj.a2o.co.kr' + p, { headers: { Cookie: ck, 'User-Agent': UA }, redirect: 'manual' });
    const ct = r.headers.get('Content-Type') || '';
    if (r.status === 200 && /^image\//.test(ct)) return new Response(r.body, { headers: { ...CORS, 'Content-Type': ct, 'Cache-Control': 'private, max-age=86400' } });
  }
  return new Response('image failed', { status: 502, headers: CORS });
}

// 관리자: 계정 만들기 · 고치기 · 지우기 · 줄 금액 바꾸기
//   GET  /admin/users                      → [{id, memo, ovr}]
//   POST /admin/user {id, pw?, memo?}       → 만들기 / 비밀번호 · 메모 바꾸기
//   POST /admin/del  {id}
//   POST /admin/ovr  {id, co, label, amt}  → amt 가 null 이면 그 줄 원래대로
async function admin(req, url, env) {
  if (!(env.DANGA_KEY && req.headers.get('X-Key') === env.DANGA_KEY)) return J({ error: 'key' }, 403);
  const p = url.pathname;
  // 계정 목록은 'index' 한 칸에 (KV list 는 새로 만든 게 늦게 보여서)
  const idx = async () => { const v = await env.KV.get('index'); if (v) return JSON.parse(v); const l = await env.KV.list({ prefix: 'user:' }); return l.keys.map((k) => k.name.slice(5)); };
  const setIdx = (a) => env.KV.put('index', JSON.stringify([...new Set(a)].sort()));
  if (p === '/admin/users') {
    const out = [];
    for (const id of await idx()) { const u = await getUser(env, id); if (u) out.push({ id: u.id, memo: u.memo || '', ovr: u.ovr || {} }); }
    return J(out);
  }
  if (req.method !== 'POST') return J({ error: 'POST' }, 405);
  let b = {}; try { b = await req.json(); } catch (e) { return J({ error: 'json' }, 400); }
  const id = String(b.id || '').trim();
  if (!/^[A-Za-z0-9_.-]{2,30}$/.test(id)) return J({ error: '아이디는 영어 · 숫자 2~30자' }, 400);
  let u = await getUser(env, id);
  if (p === '/admin/user') {
    if (!u) { if (!b.pw) return J({ error: '비밀번호가 필요해요' }, 400); u = { id, ovr: {}, created: Date.now() }; }
    if (b.pw) { if (String(b.pw).length < 4) return J({ error: '비밀번호는 4자 이상' }, 400); u.salt = crypto.randomUUID(); u.hash = await pwHash(String(b.pw), u.salt); }
    if (b.memo != null) u.memo = String(b.memo).slice(0, 60);
    await env.KV.put('user:' + id, JSON.stringify(u)); const ix = await idx(); if (!ix.includes(id)) await setIdx(ix.concat(id)); return J({ ok: true });
  }
  if (!u) return J({ error: '없는 계정' }, 404);
  if (p === '/admin/del') { await env.KV.delete('user:' + id); await setIdx((await idx()).filter((x) => x !== id)); return J({ ok: true }); }
  if (p === '/admin/ovr') {
    const co = String(b.co || ''), label = normL(b.label);
    if (!/^[a-z0-9_]{1,20}$/.test(co) || !label) return J({ error: 'bad' }, 400);
    u.ovr = u.ovr || {}; u.ovr[co] = u.ovr[co] || {};
    if (b.amt == null || b.amt === '') delete u.ovr[co][label]; else { const v = Number(b.amt); if (!isFinite(v)) return J({ error: '숫자' }, 400); u.ovr[co][label] = v; }
    if (!Object.keys(u.ovr[co]).length) delete u.ovr[co];
    await env.KV.put('user:' + id, JSON.stringify(u)); return J({ ok: true, ovr: u.ovr });
  }
  return J({ error: 'not found' }, 404);
}
// 계정 로그인: POST /login {id, pw} → {tok}
async function login(req, env) {
  if (req.method !== 'POST') return J({ error: 'POST' }, 405);
  let b = {}; try { b = await req.json(); } catch (e) { return J({ error: 'json' }, 400); }
  const u = await getUser(env, String(b.id || '').trim());
  if (!u || !u.hash || await pwHash(String(b.pw || ''), u.salt) !== u.hash) { await new Promise((r) => setTimeout(r, 600)); return J({ error: '아이디 또는 비밀번호가 맞지 않아요' }, 401); }
  return J({ tok: await makeTok(env, u.id), id: u.id });
}

export default {
  async fetch(req, env0) {
    const env = { KV: env0.DANGA }; for (const k of ['A2O_ID', 'A2O_PW', 'DANGA_KEY']) env[k] = String(env0[k] || '').trim();   // 비밀값 끝 줄바꿈 무시
    if (req.method === 'OPTIONS') return new Response(null, { headers: CORS });
    const url = new URL(req.url);
    if (url.pathname === '/ping') return new Response('ok-danga2', { headers: CORS });
    if (url.pathname === '/login') return login(req, env);
    if (url.pathname.startsWith('/admin/')) return admin(req, url, env);
    const w = await who(req, url, env);
    if (url.pathname === '/a2o') return a2o(req, url, env, w);
    if (url.pathname === '/a2o/sheet') return w ? a2oSheet(url, env) : J({ error: 'key' }, 403);
    if (url.pathname === '/a2o/img') {   // img 태그: k = 관리자 열쇠 또는 계정 토큰
      const k = url.searchParams.get('k') || '';
      const ok = (env.DANGA_KEY && k === env.DANGA_KEY) || !!(await readTok(env, k));
      return ok ? a2oImg(url, env) : new Response('key', { status: 403, headers: CORS });
    }
    return new Response('not found', { status: 404, headers: CORS });
  },
};