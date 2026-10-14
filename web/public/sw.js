// Service worker: lets the field officer app open with no signal. The app shell (index.html and the hashed assets)
// is cached; API calls always go to the network (visits captured offline wait in the app's encrypted store).
const SHELL = 'rhythm-shell-v1'

self.addEventListener('install', e => {
  e.waitUntil(caches.open(SHELL).then(c => c.addAll(['/', '/index.html', '/manifest.webmanifest', '/icon.svg'])).then(() => self.skipWaiting()))
})

self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== SHELL).map(k => caches.delete(k)))).then(() => self.clients.claim()))
})

self.addEventListener('fetch', e => {
  const req = e.request
  const url = new URL(req.url)
  if (req.method !== 'GET' || url.origin !== self.location.origin || url.pathname.startsWith('/api/') || url.pathname.startsWith('/actuator/')) return
  if (req.mode === 'navigate') {
    // network first so new versions arrive at once; the cached shell when offline
    e.respondWith(fetch(req).then(r => { const copy = r.clone(); caches.open(SHELL).then(c => c.put('/index.html', copy)); return r })
      .catch(() => caches.match('/index.html')))
    return
  }
  if (url.pathname.startsWith('/assets/')) {
    // file names carry a content hash: cache first
    e.respondWith(caches.match(req).then(hit => hit || fetch(req).then(r => { const copy = r.clone(); caches.open(SHELL).then(c => c.put(req, copy)); return r })))
  }
})
