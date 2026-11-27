// 단가 비교 계산 엔진 (talla.co.kr/danga) — 브라우저 · node 같이 씀
// 단위: 만원. 단가표 자료는 이 파일에 없음(브라우저에만 저장).
(function (root) {
  var BANDS = [
    { id: 'B115', label: '115·130' },
    { id: 'B105', label: '105' },
    { id: 'B95', label: '95' },
    { id: 'B85', label: '75·85' },
    { id: 'B61', label: '61·70' },
    { id: 'B55', label: '47·55' },
    { id: 'B44', label: '44' },
    { id: 'B37', label: '33·37' },
    { id: 'B28', label: '28이하' }
  ];
  var TYPES = [{ id: 'new', label: '신규' }, { id: 'mnp', label: 'MNP' }, { id: 'chg', label: '기변' }];

  // 조건(변수) — 규칙의 cond 에 쓰는 이름. '!이름' = 아닐 때.
  var CONDS = {
    sel: { label: '선택약정', f: function (s) { return s.yak === 'sel'; } },
    gong: { label: '공시지원금(이동사지원금약정)', f: function (s) { return s.yak === 'gong'; } },
    sup20: { label: '유통망지원금 20만↑', f: function (s) { return s.sup === 'hi'; } },
    supLow: { label: '유통망지원금 10만↓', f: function (s) { return s.sup === 'low'; } },
    vas1: { label: '교보SAM+구글원 유치', f: function (s) { return !!s.vas1; } },
    vas2: { label: 'V컬러링+벨링 유치', f: function (s) { return !!s.vas2; } },
    insSwap: { label: '폰교체패스 유치', f: function (s) { return s.ins === 'swap'; } },
    insSafe: { label: '폰안심패스 유치', f: function (s) { return s.ins === 'safe'; } },
    ins: { label: '보험 유치', f: function (s) { return s.ins === 'swap' || s.ins === 'safe'; } },
    disney: { label: '디즈니+ 유치', f: function (s) { return !!s.disney; } },
    uoneNo: { label: 'U+ONE 미로그인', f: function (s) { return !!s.uoneNo; } },
    cgrade: { label: '기변 C등급', f: function (s) { return !!s.cgrade; } },
    edocNo: { label: '전자서식 미접수', f: function (s) { return !!s.edocNo; } },
    junior: { label: '주니어 요금제', f: function (s) { return !!s.junior; } },
    senior: { label: '시니어 요금제', f: function (s) { return !!s.senior; } },
    combo: { label: '당월 유무선 결합', f: function (s) { return !!s.combo; } },
    kids: { label: '키즈폰 결합', f: function (s) { return !!s.kids; } },
    d30: { label: '30일 이내 기변', f: function (s) { return !!s.d30; } }
  };
  var DEFAULT_SC = { yak: 'sel', sup: 'mid', vas1: false, vas2: false, ins: 'none', disney: false, uoneNo: false, cgrade: false, edocNo: false, junior: false, senior: false, combo: false, kids: false, d30: false };

  function norm(s) { return String(s || '').toUpperCase().replace(/\s+/g, ''); }
  function normSt(s) {
    s = norm(s).replace(/GB?$/, '').replace(/TB?$/, 'T');
    if (s === '1024') s = '1T';
    return s;
  }

  // 'SM-S94' · 'SM-S948N:1T' (콜론 뒤 = 용량)
  function matchPat(p, key, st) {
    p = norm(p); if (!p) return false;
    var i = p.indexOf(':'), kp = i < 0 ? p : p.slice(0, i), sp = i < 0 ? '' : normSt(p.slice(i + 1));
    if (kp.slice(-1) === '$') { if (key !== kp.slice(0, -1)) return false; }   // 'UIP17$' = 그 모델만
    else if (key.indexOf(kp) !== 0) return false;
    return !sp || sp === st;
  }

  function condOk(conds, sc) {
    for (var i = 0; i < (conds || []).length; i++) {
      var c = String(conds[i]).trim(); if (!c) continue;
      var neg = c[0] === '!'; if (neg) c = c.slice(1);
      var d = CONDS[c]; var v = d ? d.f(sc) : !!(sc.extra && sc.extra[c]);
      if (neg ? v : !v) return false;
    }
    return true;
  }

  function ruleHits(r, key, st, band, type, sc) {
    if (r.off) return false;
    if (r.types && r.types.length && r.types.indexOf(type) < 0) return false;
    if (r.bands && r.bands.length && r.bands.indexOf(band) < 0) return false;
    if (r.models && r.models.length && !r.models.some(function (p) { return matchPat(p, key, st); })) return false;
    if (r.ex && r.ex.some(function (p) { return matchPat(p, key, st); })) return false;
    return condOk(r.cond, sc);
  }

  // 단가표 기본값 찾기
  function base(ag, key, st, band, type) {
    var ti = type === 'new' ? 0 : type === 'mnp' ? 1 : 2;
    var secs = ag.sections || [];
    for (var s = 0; s < secs.length; s++) {
      var sec = secs[s];
      for (var r = 0; r < (sec.rows || []).length; r++) {
        var row = sec.rows[r];
        if (norm(row.key) !== key) continue;
        var sts = (row.st || []).map(normSt);
        if (st && sts.length && sts.indexOf(st) < 0) continue;
        for (var c = 0; c < sec.cols.length; c++) {
          var col = sec.cols[c];
          if (!col.bands || !(band in col.bands)) continue;
          var cell = row.v && row.v[c];
          var x = cell ? cell[ti] : null;
          if (x === null || x === undefined || x === '' || isNaN(+x)) return { none: true, row: row, col: col };
          var adj = +col.bands[band] || 0;
          return { v: +x + adj, raw: +x, adj: adj, row: row, col: col };
        }
      }
    }
    return null;
  }

  // 최종 단가 = 기본 + 정책 + 구두
  function price(ag, key, st, band, type, sc) {
    var b = base(ag, key, st, band, type);
    if (!b || b.none) return null;
    var parts = [{ name: '기본 (' + b.col.label + ')', amt: b.raw }];
    if (b.adj) parts.push({ name: b.col.label + ' → ' + bandLabel(band) + ' 조정', amt: b.adj });
    var tot = b.v;
    (ag.rules || []).forEach(function (r) {
      if (ruleHits(r, key, st, band, type, sc)) { tot += +r.amt || 0; parts.push({ name: r.name, amt: +r.amt || 0 }); }
    });
    (ag.verbal || []).forEach(function (r) {
      if (ruleHits(r, key, st, band, type, sc)) { tot += +r.amt || 0; parts.push({ name: '🗣 ' + r.name, amt: +r.amt || 0, verbal: true }); }
    });
    return { v: Math.round(tot * 10) / 10, parts: parts, name: b.row.name, price: b.row.price };
  }

  function bandLabel(id) { for (var i = 0; i < BANDS.length; i++) if (BANDS[i].id === id) return BANDS[i].label + '군'; return id; }

  // 모든 단가표의 모델 모으기 (모델코드 + 용량)
  function items(ags) {
    var map = {}, specified = {};
    ags.forEach(function (ag) {
      (ag.sections || []).forEach(function (sec) {
        (sec.rows || []).forEach(function (row) {
          var k = norm(row.key); if (!k) return;
          var sts = (row.st || []).map(normSt);
          if (sts.length) { specified[k] = 1; sts.forEach(function (s) { add(k, s, row); }); }
          else add(k, '', row);
        });
      });
    });
    function add(k, s, row) {
      var id = k + '|' + s;
      if (!map[id]) map[id] = { key: k, st: s, name: row.name || k, price: row.price || null };
      else if (!map[id].price && row.price) map[id].price = row.price;
    }
    return Object.keys(map).map(function (id) { return map[id]; })
      .filter(function (it) { return it.st || !specified[it.key]; })
      .map(function (it) {
        var nm = String(it.name).replace(/\s*\(.*?\)\s*/g, ' ').trim();
        if (it.st && nm.toUpperCase().indexOf(it.st) < 0) nm += ' ' + (it.st === '1T' ? '1TB' : it.st);
        it.label = nm;
        it.code = it.key + (it.st ? (/^UIP/.test(it.key) ? '-' : '') + it.st : '');   // 모델명 (단가표 '모델명' 칸처럼 SM-S942N256 · UIP16E-128)
        return it;
      })
      .sort(function (a, b) { return a.key < b.key ? -1 : a.key > b.key ? 1 : (a.st < b.st ? -1 : 1); });
  }

  // 비교: 한 요금제 · 유형에서 모델마다 업체별 단가
  function compare(ags, band, type, sc) {
    return items(ags).map(function (it) {
      var vals = ags.map(function (ag) { return price(ag, it.key, it.st, band, type, sc); });
      var best = null, bi = -1;
      vals.forEach(function (p, i) { if (p && (best === null || p.v > best)) { best = p.v; bi = i; } });
      var cnt = vals.filter(function (p) { return p; }).length;
      var tie = cnt > 1 && vals.filter(function (p) { return p && p.v === best; }).length > 1;
      return { it: it, vals: vals, best: bi, tie: tie, cnt: cnt };
    });
  }

  var api = { BANDS: BANDS, TYPES: TYPES, CONDS: CONDS, DEFAULT_SC: DEFAULT_SC, base: base, price: price, items: items, compare: compare, ruleHits: ruleHits, bandLabel: bandLabel, norm: norm, normSt: normSt };
  if (typeof module !== 'undefined' && module.exports) module.exports = api; else root.Danga = api;
})(this);
