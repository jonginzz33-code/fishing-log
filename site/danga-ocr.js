// 단가표 사진 → 숫자 (무료 글자인식 Tesseract.js · 키 없음 · 브라우저 안에서만)
// 영역(숫자 칸 전체) 안의 표 선을 찾아 칸을 나누고, 줄마다 숫자만 읽어 칸에 넣어요.
(function (root) {
  var CDN = {
    lib: 'https://cdn.jsdelivr.net/npm/tesseract.js@5.1.1/dist/tesseract.min.js',
    worker: 'https://cdn.jsdelivr.net/npm/tesseract.js@5.1.1/dist/worker.min.js',
    core: 'https://cdn.jsdelivr.net/npm/tesseract.js-core@5.1.1',
    lang: 'https://cdn.jsdelivr.net/npm/@tesseract.js-data/eng@1.0.0/4.0.0_best_int'
  };
  var workerP = null;
  function loadLib() {
    if (root.Tesseract) return Promise.resolve();
    return new Promise(function (res, rej) {
      var s = document.createElement('script'); s.src = CDN.lib; s.onload = res; s.onerror = function () { rej(new Error('글자인식 프로그램을 못 받았어요 (인터넷 확인)')); };
      document.head.appendChild(s);
    });
  }
  function getWorker(log) {
    if (workerP) return workerP;
    workerP = loadLib().then(function () {
      return root.Tesseract.createWorker('eng', 1, { workerPath: CDN.worker, corePath: CDN.core, langPath: CDN.lang, logger: function (m) { if (log && m.status) log(m.status, m.progress); } });
    }).then(function (w) {
      return w.setParameters({ tessedit_char_whitelist: '0123456789-', preserve_interword_spaces: '1' }).then(function () { return w; });
    });
    workerP.catch(function () { workerP = null; });
    return workerP;
  }

  // 회색조 · 밝기 배열
  function gray(img, x0, y0, w, h) {
    var c = document.createElement('canvas'); c.width = w; c.height = h;
    var g = c.getContext('2d'); g.drawImage(img, x0, y0, w, h, 0, 0, w, h);
    var d = g.getImageData(0, 0, w, h).data, L = new Uint8Array(w * h);
    for (var i = 0, j = 0; i < d.length; i += 4, j++) L[j] = (d[i] * 299 + d[i + 1] * 587 + d[i + 2] * 114) / 1000;
    return { L: L, w: w, h: h };
  }
  var DARK = 150, LINE = 205;   // 글자 = 150 아래 · 표 선 = 205 아래 (연한 회색 선도)
  // 선 찾기: 가로(축='y') 또는 세로 줄의 어두운 비율이 높은 곳 → [{a,b}] (시작 · 끝 픽셀)
  function lines(G, axis, minFrac) {
    // 선 = 양옆(2px)보다 눈에 띄게 어두운 픽셀 · 또는 아주 어두운 픽셀(두꺼운 검은 띠) — 연한 바탕색은 선으로 안 봄
    var n = axis === 'y' ? G.h : G.w, m = axis === 'y' ? G.w : G.h, out = [], cur = null, W = G.w, L = G.L;
    function at(i, k) { return axis === 'y' ? L[i * W + k] : L[k * W + i]; }
    for (var i = 0; i < n; i++) {
      var dark = 0;
      for (var k = 0; k < m; k++) {
        var v = at(i, k);
        if (v < 70) { dark++; continue; }
        var p1 = i >= 2 ? at(i - 2, k) : 255, p2 = i + 2 < n ? at(i + 2, k) : 255, q1 = i >= 1 ? at(i - 1, k) : 255, q2 = i + 1 < n ? at(i + 1, k) : 255;
        if (v < Math.min(p1, p2) - 12 || v < Math.min(q1, q2) - 12 && v < 215 || v < Math.max(p1, p2) - 25 && v < 218) dark++;
      }
      var is = dark / m >= minFrac;
      if (is) { if (cur) cur.b = i; else cur = { a: i, b: i }; } else if (cur) { out.push(cur); cur = null; }
    }
    if (cur) out.push(cur);
    return out;
  }
  // n 칸 경계 만들기: 선이 맞게 나오면 그대로, 아니면 고르게 나눈 뒤 가까운 선에 붙이기
  function bounds(ls, size, n) {
    var edges = [0];
    ls.forEach(function (l) { var c = (l.a + l.b) / 2; if (c > 2 && c < size - 2) edges.push(c); });
    edges.push(size);
    // 너무 가까운 경계 합치기
    var e2 = [edges[0]];
    for (var i = 1; i < edges.length; i++) if (edges[i] - e2[e2.length - 1] > size / n * 0.35) e2.push(edges[i]); else e2[e2.length - 1] = (e2[e2.length - 1] + edges[i]) / 2;
    if (e2.length === n + 1) return { b: e2, exact: true };
    var step = size / n, out = [];
    for (var j = 0; j <= n; j++) {
      var want = j * step, best = want, bd = step * 0.45;
      ls.forEach(function (l) { var c = (l.a + l.b) / 2; if (Math.abs(c - want) < bd) { bd = Math.abs(c - want); best = c; } });
      out.push(j === 0 ? Math.min(best, 0) || 0 : j === n ? size : best);
    }
    return { b: out, exact: false };
  }
  // 영역을 실제 표 선에 맞추기 (사용자가 대충 찍어도) — 바깥쪽으로 조금 넓혀 가장 가까운 선
  function snap(img, rg) {
    var W = img.naturalWidth, H = img.naturalHeight;
    var x0 = rg.x0 * W, x1 = rg.x1 * W, y0 = rg.y0 * H, y1 = rg.y1 * H, pad = Math.max(14, Math.min(W, H) * 0.02);
    var X0 = Math.max(0, Math.floor(x0 - pad)), Y0 = Math.max(0, Math.floor(y0 - pad)), X1 = Math.min(W, Math.ceil(x1 + pad)), Y1 = Math.min(H, Math.ceil(y1 + pad));
    var G = gray(img, X0, Y0, X1 - X0, Y1 - Y0);
    function near(ls, v, lim) { var best = v, bd = lim; ls.forEach(function (l) { var c = (l.a + l.b) / 2; if (Math.abs(c - v) < bd) { bd = Math.abs(c - v); best = c; } }); return best; }
    var hy = lines(G, 'y', 0.5), vx = lines(G, 'x', 0.7);
    return { x0: (X0 + near(vx, x0 - X0, pad * 1.6)) / W, x1: (X0 + near(vx, x1 - X0, pad * 1.5)) / W, y0: (Y0 + near(hy, y0 - Y0, pad * 1.5)) / H, y1: (Y0 + near(hy, y1 - Y0, pad * 1.5)) / H };
  }
  // 칸 나누기 (화면에 격자 보여 주기에도 씀)
  function grid(img, rg, nRows, nCols) {
    var W = img.naturalWidth, H = img.naturalHeight;
    var X0 = Math.round(rg.x0 * W), Y0 = Math.round(rg.y0 * H), w = Math.round((rg.x1 - rg.x0) * W), h = Math.round((rg.y1 - rg.y0) * H);
    var G = gray(img, X0, Y0, w, h);
    var rb = bounds(lines(G, 'y', 0.45), h, nRows), cb = bounds(lines(G, 'x', 0.7), w, nCols);
    return { X0: X0, Y0: Y0, w: w, h: h, G: G, rows: rb.b, cols: cb.b, exact: rb.exact && cb.exact };
  }

  function num(t) {
    t = String(t || '').replace(/[^\d\-]/g, '');
    if (!t || t === '-') return null;
    var neg = t[0] === '-'; t = t.replace(/-/g, '');
    if (!t) return null;
    return (neg ? -1 : 1) * parseInt(t, 10);
  }

  // 한 표 읽기 → v[줄][열][신규·MNP·기변]
  function readSection(img, rg, nRows, nCols3, log) {
    var gd = grid(img, rg, nRows, nCols3);
    var G = gd.G, scale = 3;
    // 깨끗한 회색 그림: 표 선 · 연한 배경색은 흰색, 글자는 진하게
    var lineR = {}, lineC = {};
    for (var y0 = 0; y0 < G.h; y0++) { var d0 = 0; for (var x0 = 0; x0 < G.w; x0++) if (G.L[y0 * G.w + x0] < LINE) d0++; if (d0 / G.w > 0.5) lineR[y0] = 1; }
    for (var x1 = 0; x1 < G.w; x1++) { var d1 = 0; for (var y1 = 0; y1 < G.h; y1++) if (G.L[y1 * G.w + x1] < LINE) d1++; if (d1 / G.h > 0.85) lineC[x1] = 1; }
    var CLEAN = document.createElement('canvas'); CLEAN.width = G.w; CLEAN.height = G.h;
    var cg = CLEAN.getContext('2d'), cid = cg.createImageData(G.w, G.h), CD = cid.data;
    for (var q = 0; q < CD.length; q++) CD[q] = 255;
    // 칸마다 그 칸 바탕을 흰색 · 가장 진한 글자를 검정으로 늘리기 (작은 사진의 흐린 '1' 도 살게)
    for (var rr = 0; rr < nRows; rr++) for (var cc = 0; cc < nCols3; cc++) {
      var ya = Math.round(gd.rows[rr]), yb = Math.round(gd.rows[rr + 1]), xa = Math.round(gd.cols[cc]), xb = Math.round(gd.cols[cc + 1]), hist = [], mn = 255;
      for (var y = ya; y < yb; y++) { if (lineR[y]) continue; for (var x = xa; x < xb; x++) { if (lineC[x] || lineC[x - 1] || lineC[x + 1] || x - xa < 2 || xb - x < 2) continue; var v = G.L[y * G.w + x]; hist.push(v); if (v < mn) mn = v; } }
      if (!hist.length) continue;
      hist.sort(function (a, b) { return a - b; });
      var bg = hist[Math.floor(hist.length * 0.6)];
      if (bg - mn < 40) continue;   // 글자 없음
      for (var y2 = ya; y2 < yb; y2++) { if (lineR[y2]) continue; for (var x2 = xa; x2 < xb; x2++) {
        if (lineC[x2] || lineC[x2 - 1] || lineC[x2 + 1] || x2 - xa < 2 || xb - x2 < 2) continue;
        var i2 = y2 * G.w + x2, v2 = G.L[i2];
        var t = (v2 - mn) / (bg - mn), o2 = i2 * 4;
        var out2 = t >= 0.85 ? 255 : Math.round(Math.pow(Math.max(0, t), 1.6) * 255);
        CD[o2] = CD[o2 + 1] = CD[o2 + 2] = out2;
      } }
    }
    cg.putImageData(cid, 0, 0);
    return getWorker(log).then(function (worker) {
      var out = [], miss = [], unsure = [];
      var chain = Promise.resolve();
      for (var r = 0; r < nRows; r++) (function (r) {
        chain = chain.then(function () {
          if (log) log('줄 읽는 중', r / nRows);
          var ya = Math.round(gd.rows[r]), yb = Math.round(gd.rows[r + 1]), hh = Math.max(4, yb - ya);
          var colDark = [];
          for (var k = 0; k < nCols3; k++) {
            var xa = gd.cols[k], xb = gd.cols[k + 1], dk = 0, tot = 0;
            for (var y = ya + 2; y < yb - 2; y++) for (var x = Math.round(xa) + 2; x < xb - 2; x++) { tot++; if (G.L[y * G.w + x] < DARK) dk++; }
            colDark.push(tot ? dk / tot : 0);
          }
          function col(x) { for (var q = 0; q < nCols3; q++) if (x >= gd.cols[q] && x < gd.cols[q + 1]) return q; return -1; }
          // 깨끗한 그림에서 이 줄만 3배로 부드럽게 · 검은 칸은 흰색으로
          var c = document.createElement('canvas'); c.width = gd.w * scale + 40; c.height = hh * scale + 40;
          var g = c.getContext('2d'); g.fillStyle = '#fff'; g.fillRect(0, 0, c.width, c.height);
          g.imageSmoothingEnabled = true; g.imageSmoothingQuality = 'high';
          g.drawImage(CLEAN, 0, ya, gd.w, hh, 20, 20, gd.w * scale, hh * scale);
          for (var kb = 0; kb < nCols3; kb++) if (colDark[kb] > 0.55) g.fillRect(20 + gd.cols[kb] * scale, 0, (gd.cols[kb + 1] - gd.cols[kb]) * scale, c.height);
          var pc = c; 
          return worker.setParameters({ tessedit_pageseg_mode: '7' }).then(function () { return worker.recognize(pc); }).then(function (res) {
            var cells = [], confs = []; for (var k3 = 0; k3 < nCols3; k3++) { cells.push(colDark[k3] > 0.55 ? 'X' : ''); confs.push(100); }
            (res.data.words || []).forEach(function (wd) {
              var cx = ((wd.bbox.x0 + wd.bbox.x1) / 2 - 20) / scale, ci = col(cx);
              if (ci < 0) return;
              // 한 낱말이 여러 칸에 걸치면(붙어 읽힘) 칸마다 나누지 않고 표시만
              var span = col((wd.bbox.x1 - 20) / scale - 1) - col((wd.bbox.x0 - 20) / scale + 1);
              if (span > 0) { miss.push([r, ci]); return; }
              if (cells[ci] !== 'X') { cells[ci] += wd.text; confs[ci] = Math.min(confs[ci], wd.confidence); }
            });
            var row = [];
            for (var k4 = 0; k4 < nCols3; k4++) {
              var tx = cells[k4];
              if (tx === 'X') { row.push(null); continue; }
              var n = num(tx);
              if (n === null && colDark[k4] > 0.015) miss.push([r, k4]);
              else if (n !== null && (confs[k4] < 80 || Math.abs(n) > 150 || blobs(G, gd, r, k4, lineR) !== String(tx).replace(/[^\d\-]/g, '').length)) miss.push([r, k4]);
              row.push(n);
            }
            out.push(row);
          });
        });
      })(r);
      // 칸 하나를 여러 방법으로 읽을 때까지 (확대 · 굵기 · 대비 · 읽기 방식) → 숫자 개수 맞는 답 · 여럿이면 많이 나온 답
      var VARIANTS = [
        { sc: 4, psm: '8', mode: 'clean' }, { sc: 5, psm: '7', mode: 'clean' }, { sc: 3, psm: '8', mode: 'bold' },
        { sc: 6, psm: '8', mode: 'hard' }, { sc: 4, psm: '13', mode: 'bold' }, { sc: 5, psm: '6', mode: 'hard' },
        { sc: 8, psm: '10', mode: 'clean', one: true }, { sc: 7, psm: '10', mode: 'bold', one: true }, { sc: 8, psm: '8', mode: 'clean' }, { sc: 6, psm: '7', mode: 'bold' }
      ];
      function cellCanvas(r, k, v) {
        var ya = Math.round(gd.rows[r]) + 1, yb = Math.round(gd.rows[r + 1]) - 1, xa = Math.round(gd.cols[k]) + 2, xb = Math.round(gd.cols[k + 1]) - 2;
        var w = xb - xa, h = yb - ya, sc = v.sc, c = document.createElement('canvas'); c.width = w * sc + 60; c.height = h * sc + 60;
        var g = c.getContext('2d'); g.fillStyle = '#fff'; g.fillRect(0, 0, c.width, c.height);
        if (v.mode === 'clean') { g.imageSmoothingEnabled = true; g.imageSmoothingQuality = 'high'; g.drawImage(CLEAN, xa, ya, w, h, 30, 30, w * sc, h * sc); return c; }
        // bold = 가는 획 굵게 (옆 픽셀 중 진한 것) · hard = 흑백 딱 나누기
        var id = g.getImageData(0, 0, c.width, c.height), D = id.data, cd = cg.getImageData(xa, ya, w, h).data;
        function px(x, y) { return cd[(y * w + x) * 4]; }
        for (var y = 0; y < h * sc; y++) for (var x = 0; x < w * sc; x++) {
          var sx = Math.floor(x / sc), sy = Math.floor(y / sc), val = px(sx, sy);
          if (v.mode === 'bold') { if (sx > 0) val = Math.min(val, px(sx - 1, sy)); if (sx < w - 1) val = Math.min(val, px(sx + 1, sy)); val = val < 200 ? Math.round(val * 0.6) : 255; }
          else val = val < 170 ? 0 : 255;
          var o = ((y + 30) * c.width + x + 30) * 4; D[o] = D[o + 1] = D[o + 2] = val;
        }
        g.putImageData(id, 0, 0);
        return c;
      }
      function retryCell(r, k) {
        var want = blobs(G, gd, r, k, lineR), votes = {}, i = 0, cur = out[r][k];
        if (cur !== null && String(cur).replace('-', '').length === want) votes[cur] = 1;
        function next() {
          if (i >= VARIANTS.length) return Promise.resolve();
          var v = VARIANTS[i++];
          if (v.one && want !== 1) return next();   // 한 글자 읽기는 한 자리 숫자 칸만
          return worker.setParameters({ tessedit_pageseg_mode: v.psm }).then(function () { return worker.recognize(cellCanvas(r, k, v)); }).then(function (res) {
            var t = String(res.data.text || '').replace(/[^\d\-]/g, '');
            if (t.length < want && want <= 2 && /^1+$/.test(t)) t = '11';   // '11' 을 '1' 로 읽는 버릇
            var n = num(t);
            if (n !== null && String(n).replace('-', '').length === want) {
              votes[n] = (votes[n] || 0) + 1;
              if (votes[n] >= 2) return;   // 두 번 같게 읽으면 확정
            }
            return next();
          });
        }
        return next().then(function () {
          var best = null, bc = 0; Object.keys(votes).forEach(function (kk) { if (votes[kk] > bc) { bc = votes[kk]; best = +kk; } });
          if (best !== null) out[r][k] = best;
          if (best === null ? want > 0 : bc < 2) unsure.push([r, k]);
          else if (Math.abs(out[r][k]) > 150) unsure.push([r, k]);
        });
      }
      // 놓친 칸은 칸 하나씩 다시
      return chain.then(function () {
        var c2 = Promise.resolve();
        var seen = {};
        miss.forEach(function (m) {
          var key = m[0] + '_' + m[1]; if (seen[key]) return; seen[key] = 1;
          c2 = c2.then(function () { return retryCell(m[0], m[1]); });
        });
        return c2.then(function () { return { v: out, exact: gd.exact, missed: Object.keys(seen).length, unsure: unsure }; });
      });
    });
  }

  // ── 숫자 표 자동으로 찾기 ──
  // 사진마다(카톡 캡처 · 잘린 사진 · 크기 다름) 위치가 달라도, 고르게 늘어선 표 선 묶음을 찾아 영역을 정해요.
  // hint = 예전/기본 영역(사진 안 내용 상자 기준 비율) · nRows 줄 · nCols3 칸
  function contentBox(G) {
    // 밝은(종이) 부분 = 단가표 · 검은 테두리 · 상태바 빼기
    function frac(i, axis) { var n = axis === 'y' ? G.w : G.h, b = 0; for (var k = 0; k < n; k++) { var v = axis === 'y' ? G.L[i * G.w + k] : G.L[k * G.w + i]; if (v > 170) b++; } return b / n; }
    var y0 = 0, y1 = G.h - 1, x0 = 0, x1 = G.w - 1;
    while (y0 < y1 && frac(y0, 'y') < 0.45) y0++;
    while (y1 > y0 && frac(y1, 'y') < 0.45) y1--;
    while (x0 < x1 && frac(x0, 'x') < 0.3) x0++;
    while (x1 > x0 && frac(x1, 'x') < 0.3) x1--;
    return { x0: x0, y0: y0, x1: x1 + 1, y1: y1 + 1 };
  }
  // 줄 목록에서 n 칸(n+1 선)이 고르게 늘어선 묶음 찾기 → 기대 시작 · 끝에 가장 가까운 것
  function evenRun(cs, n, want0, want1) {
    var best = null, bs = 1e18;
    for (var i = 0; i + n < cs.length; i++) {
      var seg = cs.slice(i, i + n + 1), gaps = [];
      for (var k = 1; k < seg.length; k++) gaps.push(seg[k] - seg[k - 1]);
      var sorted = gaps.slice().sort(function (a, b) { return a - b; }), med = sorted[Math.floor(sorted.length / 2)];
      if (med < 6) continue;
      var ok = gaps.every(function (g) { return g > med * 0.6 && g < med * 1.45; });
      if (!ok) continue;
      // 더 큰 표의 일부(위 · 아래로 같은 간격 줄이 더 이어짐)면 버림
      var cont = function (g) { return g > med * 0.8 && g < med * 1.25; };
      if (i > 0 && cont(seg[0] - cs[i - 1])) continue;
      if (i + n + 1 < cs.length && cont(cs[i + n + 1] - seg[n])) continue;
      var sc = Math.abs(seg[0] - want0) + Math.abs(seg[n] - want1);
      if (sc < bs) { bs = sc; best = seg; }
    }
    return best;
  }
  function autoRegion(img, hint, nRows, nCols3) {
    var W = img.naturalWidth, H = img.naturalHeight, G = gray(img, 0, 0, W, H), cb = contentBox(G);
    var cw = cb.x1 - cb.x0, ch = cb.y1 - cb.y0;
    if (cw < W * 0.3 || ch < H * 0.2) cb = { x0: 0, y0: 0, x1: W, y1: H }, cw = W, ch = H;
    var hx0 = cb.x0 + hint.x0 * cw, hx1 = cb.x0 + hint.x1 * cw, hy0 = cb.y0 + hint.y0 * ch, hy1 = cb.y0 + hint.y1 * ch;
    // 가로선: 기대 영역 가로 범위에서
    var bx0 = Math.max(0, Math.round(hx0 - cw * 0.05)), bx1 = Math.min(W, Math.round(hx1 + cw * 0.02));
    var GH = gray(img, bx0, 0, bx1 - bx0, H);
    var hc = lines(GH, 'y', 0.5).map(function (l) { return (l.a + l.b) / 2; });
    var rows = evenRun(hc, nRows, hy0, hy1);
    if (!rows) return null;
    var y0 = rows[0], y1 = rows[nRows];
    var GV = gray(img, 0, Math.round(y0), W, Math.max(4, Math.round(y1 - y0)));
    var vc = lines(GV, 'x', 0.7).map(function (l) { return (l.a + l.b) / 2; });
    var cols = evenRun(vc, nCols3, hx0, hx1);
    if (!cols) return null;
    return { x0: cols[0] / W, x1: cols[nCols3] / W, y0: y0 / H, y1: y1 / H, auto: true };
  }
  // 기대 위치 근처 표의 실제 줄 수 (줄 수가 다를 때 알려 주기용)
  function rowCount(img, hint) {
    var W = img.naturalWidth, H = img.naturalHeight, G = gray(img, 0, 0, W, H), cb = contentBox(G), cw = cb.x1 - cb.x0, ch = cb.y1 - cb.y0;
    if (cw < W * 0.3 || ch < H * 0.2) cb = { x0: 0, y0: 0, x1: W, y1: H }, cw = W, ch = H;
    var hx0 = cb.x0 + hint.x0 * cw, hx1 = cb.x0 + hint.x1 * cw, hy0 = cb.y0 + hint.y0 * ch;
    var bx0 = Math.max(0, Math.round(hx0 - cw * 0.05)), bx1 = Math.min(W, Math.round(hx1 + cw * 0.02));
    var cs = lines(gray(img, bx0, 0, bx1 - bx0, H), 'y', 0.5).map(function (l) { return (l.a + l.b) / 2; });
    var best = null, bd = 1e9;
    for (var i = 0; i < cs.length - 3; i++) {
      var j = i + 1, med = cs[i + 1] - cs[i];
      if (med < 6) continue;
      while (j + 1 < cs.length) { var g = cs[j + 1] - cs[j]; if (g > med * 0.6 && g < med * 1.45) j++; else break; }
      if (j - i >= 3 && Math.abs(cs[i] - hy0) < bd) { bd = Math.abs(cs[i] - hy0); best = j - i; }
    }
    return best;
  }
  // 저장해 둔 영역(사진 비율)을 내용 상자 기준 비율로 (다음 사진에 힌트로)
  function toHint(img, rg) {
    var W = img.naturalWidth, H = img.naturalHeight, cb = contentBox(gray(img, 0, 0, W, H)), cw = cb.x1 - cb.x0, ch = cb.y1 - cb.y0;
    if (cw < W * 0.3 || ch < H * 0.2) return { x0: rg.x0, x1: rg.x1, y0: rg.y0, y1: rg.y1 };
    return { x0: (rg.x0 * W - cb.x0) / cw, x1: (rg.x1 * W - cb.x0) / cw, y0: (rg.y0 * H - cb.y0) / ch, y1: (rg.y1 * H - cb.y0) / ch };
  }

  // 칸 안 글자 덩어리 수 (숫자 개수 확인용)
  function blobs(G, gd, r, k, skip) {
    var ya = Math.round(gd.rows[r]) + 2, yb = Math.round(gd.rows[r + 1]) - 2, xa = Math.round(gd.cols[k]) + 3, xb = Math.round(gd.cols[k + 1]) - 3, n = 0, prev = false;
    for (var x = xa; x < xb; x++) {
      var ink = 0, tot = 0;
      for (var y = ya; y < yb; y++) { if (skip && skip[y]) continue; tot++; if (G.L[y * G.w + x] < 110) ink++; }
      var on = ink > 0 && ink < tot * 0.9;
      if (on && !prev) n++; prev = on;
    }
    return n;
  }
  function debugGrid(img, rg, nR, nC) { var g = grid(img, rg, nR, nC); return { rows: g.rows.map(Math.round), cols: g.cols.map(Math.round), exact: g.exact, hy: lines(g.G, 'y', 0.45).length, vx: lines(g.G, 'x', 0.7).length }; }
  root.DangaOCR = { rowCount: rowCount, autoRegion: autoRegion, toHint: toHint, contentBox: function (img) { return contentBox(gray(img, 0, 0, img.naturalWidth, img.naturalHeight)); }, _lines: lines, _gray: gray, debugGrid: debugGrid, CDN: CDN, readSection: readSection, snap: snap, grid: grid, getWorker: getWorker };
})(this);
