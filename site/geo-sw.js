// 지오팜 재고조회 웹 알림 (talla.co.kr/geo 에서 '이 폰으로 알림 받기'를 누르면 등록됨)
// 서버가 보낸 알림을 페이지가 닫혀 있어도 휴대폰 알림으로 띄움
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));

self.addEventListener('push', (e) => {
  let d = {};
  try { d = e.data ? e.data.json() : {}; } catch (err) { d = { title: '지오팜 알림', body: e.data ? e.data.text() : '' }; }
  e.waitUntil(self.registration.showNotification(d.title || '지오팜 알림', {
    body: d.body || '',
    icon: '/img/jogwa.png',
    badge: '/img/jogwa.png',
    tag: 'geo-' + (d.t || Date.now()), // 알림마다 따로 (앞 알림을 덮어쓰지 않게)
    renotify: true,
    vibrate: [200, 100, 200],
    data: { url: '/geo' },
  }));
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
