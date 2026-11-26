/* talla.co.kr 공용 ☰ 메뉴 — 오른쪽 위 버튼 · 누르면 오른쪽에서 메뉴가 나와요.
   칸을 더하려면 아래 MENU 에 한 줄 넣기: [아이콘, 이름, 주소, 꼬리표(없으면 '')] · 묶음 = {t: '제목', items: [...]} */
(function () {
  // v7.01: ☰ 메뉴는 관리자 구글 계정으로 로그인했을 때만 (일반 방문자 · 다른 계정은 안 보임)
  //   로그인은 /admin 에서 · 같은 브라우저면 다른 페이지에서도 로그인 유지 (Firebase)
  var ADMIN = 'jonginzz33@gmail.com';
  try { localStorage.removeItem('talla_me'); } catch (e) {}
  function load(src) { return new Promise(function (ok, no) { var sc = document.createElement('script'); sc.src = src; sc.onload = ok; sc.onerror = no; document.head.appendChild(sc); }); }
  function ready() {
    if (window.firebase && firebase.apps && firebase.apps.length && firebase.auth) return Promise.resolve();
    var p = window.firebase ? Promise.resolve() : load('/__/firebase/10.14.1/firebase-app-compat.js');
    return p.then(function () { return firebase.auth ? null : load('/__/firebase/10.14.1/firebase-auth-compat.js'); })
      .then(function () { return firebase.apps.length ? null : load('/__/firebase/init.js'); });
  }
  ready().then(function () {
    firebase.auth().onAuthStateChanged(function (u) {
      var ok = !!u && (u.email || '').toLowerCase() === ADMIN;
      window.tallaAdmin = ok;
      document.dispatchEvent(new Event(ok ? 'talla-admin' : 'talla-guest'));
      if (ok) build(); else unbuild();
    });
  }).catch(function () { window.tallaAdmin = false; document.dispatchEvent(new Event('talla-guest')); });
  var built = null;
  function unbuild() { if (built) { built.forEach(function (e) { e.remove(); }); built = null; document.documentElement.classList.remove('talla-me'); } }
  function build() {
  if (built) return;
  document.documentElement.classList.add('talla-me');
  var MENU = [
    { t: '', items: [['🏠', '홈', '/', '']] },
    { t: '📱 앱', items: [
      ['🎣', '조과일지', 'https://play.google.com/store/apps/details?id=kr.jogwailji.app', ''],
      ['🌐', '조과일지 웹 (로그인)', '/app', ''],
      ['🚻', '급할 때', 'https://play.google.com/store/apps/details?id=kr.mytoilet.app', '']] },
    { t: '🧪 테스터', items: [
      ['🧪', '비공개 테스트 참여하기', '/#tester', ''],
      ['👥', '테스터 그룹 가입', 'https://groups.google.com/g/mytoilet-testers', '']] },
    { t: '🧰 도구', items: [
      ['🔗', '자주 가는 링크', '/links', ''],
      ['🚤', '선상 빈자리 (홈피판)', '/fleets', '시험 중']] },
    { t: '🔐 관리', items: [
      ['🔐', '관리자 페이지', '/admin', '관리자만'],
      ['📋', '서버 규칙 복사', '/rules', '관리자만']] },
    { t: '📄 개인정보 · 계정 삭제', items: [
      ['🎣', '조과일지 개인정보처리방침', '/jogwa/privacy.html', ''],
      ['🎣', '조과일지 계정 삭제', '/jogwa/delete-account.html', ''],
      ['🚻', '급할 때 개인정보처리방침', '/geuphal/privacy.html', ''],
      ['🚻', '급할 때 계정 삭제', '/geuphal/delete-account.html', '']] }
  ];
  var css = '.tm-btn{position:relative;flex:0 0 auto;margin-right:12px;width:42px;height:42px;border-radius:14px;border:1.5px solid #3A6A88;background:#12283A;color:#EAF2F6;font-size:22px;cursor:pointer;display:flex;align-items:center;justify-content:center;box-shadow:0 4px 14px rgba(0,0,0,.35)}' +
    '.tm-dim{position:fixed;inset:0;background:rgba(0,0,0,.5);z-index:70;opacity:0;pointer-events:none;transition:opacity .2s}' +
    '.tm-dim.on{opacity:1;pointer-events:auto}' +
    '.tm-btn.fx{position:fixed;top:12px;left:14px;z-index:60}' +
    '.tm-pan{position:fixed;top:0;left:0;bottom:0;width:min(330px,86vw);background:#0E1F2C;border-right:1.5px solid #2C4F66;z-index:80;transform:translateX(-105%);transition:transform .22s;overflow-y:auto;font-family:-apple-system,"Apple SD Gothic Neo","Noto Sans KR","Malgun Gothic",sans-serif;color:#EAF2F6}' +
    '.tm-pan.on{transform:none}' +
    '.tm-head{display:flex;align-items:center;justify-content:space-between;padding:16px 16px 10px;border-bottom:1px solid #1E3446;font-weight:900;font-size:18px}.tm-head span{color:#7FD6FF}' +
    '.tm-x{border:0;background:transparent;color:#9FB4C2;font-size:24px;cursor:pointer}' +
    '.tm-t{padding:14px 18px 4px;font-size:12.5px;color:#9FB4C2;font-weight:800}' +
    '.tm-a{display:flex;align-items:center;gap:12px;padding:11px 18px;color:#EAF2F6;text-decoration:none;font-size:15px}.tm-a:active,.tm-a:hover{background:#16324A}' +
    '.tm-a .i{width:26px;text-align:center;font-size:19px}.tm-a .k{margin-left:auto;font-size:10.5px;font-weight:800;padding:1px 7px;border-radius:8px;background:#4A2A12;color:#FFC98A}' +
    '.tm-a .k.t{background:#16324A;color:#7FD6FF}.tm-a.cur{color:#7FD6FF;font-weight:800}';
  var st = document.createElement('style'); st.textContent = css; document.head.appendChild(st);
  var btn = document.createElement('button'); btn.className = 'tm-btn'; btn.setAttribute('aria-label', '메뉴'); btn.textContent = '☰';
  var dim = document.createElement('div'); dim.className = 'tm-dim';
  var pan = document.createElement('div'); pan.className = 'tm-pan';
  var here = location.pathname.replace(/\.html$/, '').replace(/\/$/, '') || '/';
  var h = '<div class="tm-head"><div><span>talla</span>.co.kr</div><button class="tm-x" aria-label="닫기">✕</button></div>';
  MENU.forEach(function (g) {
    if (g.t) h += '<div class="tm-t">' + g.t + '</div>';
    g.items.forEach(function (x) {
      var u = x[2], ext = /^https?:/.test(u), p = u.replace(/\.html$/, '').replace(/#.*$/, '').replace(/\/$/, '') || '/';
      h += '<a class="tm-a' + (!ext && p === here && u.indexOf('#') < 0 ? ' cur' : '') + '" href="' + u + '"' + (ext ? ' target="_blank" rel="noopener"' : '') + '><span class="i">' + x[0] + '</span>' + x[1] +
        (x[3] ? '<span class="k' + (x[3] === '시험 중' ? ' t' : '') + '">' + x[3] + '</span>' : '') + '</a>';
    });
  });
  h += '<div class="tm-t">⚙</div><a class="tm-a" href="/admin"><span class="i">🔐</span>관리자 페이지 · 로그아웃</a>';
  pan.innerHTML = h + '<div style="height:24px"></div>';
  function open(v) { dim.classList.toggle('on', v); pan.classList.toggle('on', v); }
  btn.onclick = function () { open(true); }; dim.onclick = function () { open(false); };
  pan.querySelector('.tm-x').onclick = function () { open(false); };
  pan.querySelectorAll('a').forEach(function (a) { a.addEventListener('click', function () {
    open(false);
    if (a.getAttribute('href') === '/#tester') { var t = document.getElementById('tester'); if (t) { t.open = true; setTimeout(function () { t.scrollIntoView({ behavior: 'smooth' }); }, 230); } }
  }); });
  // ☰ = 왼쪽 끝 (로고 앞) · 위 줄이 없는 페이지는 왼쪽 위에 떠 있게
  var nav = document.querySelector('nav');
  if (nav) { var lg = nav.firstElementChild; nav.insertBefore(btn, lg); if (lg) lg.style.marginRight = 'auto'; nav.style.justifyContent = 'flex-start'; nav.style.paddingRight = '0'; }
  else { btn.classList.add('fx'); document.body.appendChild(btn); }
  document.body.appendChild(dim); document.body.appendChild(pan);
  if (location.hash === '#tester') { var t = document.getElementById('tester'); if (t) t.open = true; }
  built = [btn, dim, pan];
  }
})();
