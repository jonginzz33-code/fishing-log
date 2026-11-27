// 지오팜 재고조회 웹 알림 (talla.co.kr/geo 에서 '이 폰으로 알림 받기'를 누르면 등록됨)
// 서버가 보낸 알림을 페이지가 닫혀 있어도 휴대폰 알림으로 띄움
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));

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
        data: { url: '/geo' },
      });
    }
  })());
});

// 알림을 누르면 재고조회 화면 열기 (이미 열려 있으면 그 탭으로)
self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  e.waitUntil((async () => {
    const all = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    for (const c of all) if (c.url.includes('/geo') && 'focus' in c) return c.focus();
    return self.clients.openWindow('/geo');
  })());
});
