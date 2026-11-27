// 지오팜 재고조회 웹 알림 (talla.co.kr/geo 에서 '이 폰으로 알림 받기'를 누르면 등록됨)
// 서버가 보낸 알림을 페이지가 닫혀 있어도 휴대폰 알림으로 띄움
const VERSION = '5 (복사 버튼 · 띠링 2.5초)'; // 바꿀 때마다 올리기 → 홈페이지 '이 기기' 칸에 표시
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));
self.addEventListener('message', (e) => {
  if (e.data && e.data.type === 'ver' && e.source) e.source.postMessage({ type: 'geo-ver', v: VERSION });
});

const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));
self.addEventListener('push', (e) => {
  let d = {};
  try { d = e.data ? e.data.json() : {}; } catch (err) { d = { title: '지오팜 알림', body: e.data ? e.data.text() : '' }; }
  // rings = 띠링 몇 번 (홈페이지 '이 기기' 칸에서 1~5번). 2.5초 간격으로 다시 띄워서 그때마다 소리 · 진동
  const rings = Math.min(10, Math.max(1, d.rings | 0 || 1));
  const tag = 'geo-' + (d.t || Date.now()); // 알림마다 따로 (앞 알림을 덮어쓰지 않게)
  e.waitUntil((async () => {
    for (let i = 0; i < rings; i++) {
      if (i) {
        await sleep(2500); // 띠링 간격 2.5초
        // 같은 알림을 지우고 다시 띄우면 또 울림 (알림창엔 하나만 남음)
        (await self.registration.getNotifications({ tag })).forEach((n) => n.close());
      }
      await self.registration.showNotification(d.title || '지오팜 알림', {
        body: d.body || '',
        icon: '/geo-icon.png',   // 알약 아이콘 (지오팜 전용)
        badge: '/geo-badge.png', // 상태바에 뜨는 작은 흰 알약
        tag,
        renotify: true,
        silent: false,
        requireInteraction: true, // 눌러서 지울 때까지 알림 남겨 두기
        vibrate: [500, 200, 500], // 진동 (안드로이드는 휴대폰 알림 설정의 진동이 켜져 있어야 울림)
        data: { url: '/geo', copy: d.copy || '' },
        actions: d.copy ? [{ action: 'copy', title: '📋 제품명 복사' }, { action: 'open', title: '열기' }] : [],
      });
    }
  })());
});

// 알림(또는 📋 제품명 복사)을 누르면 재고조회 화면을 열고 제품명을 복사하게 함
// (서비스워커는 클립보드를 못 써서, 화면이 ?copy= 를 받아 복사함)
self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  const copy = (e.notification.data && e.notification.data.copy) || '';
  const url = '/geo' + (copy && e.action !== 'open' ? '?copy=' + encodeURIComponent(copy) : '');
  e.waitUntil((async () => {
    const all = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    for (const c of all) {
      if (c.url.includes('/geo')) {
        if (copy && e.action !== 'open') c.postMessage({ type: 'geo-copy', copy }); // 이미 열린 화면이면 거기서 복사
        if ('focus' in c) return c.focus();
      }
    }
    return self.clients.openWindow(url);
  })());
});
