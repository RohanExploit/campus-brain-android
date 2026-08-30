// Minimal service worker for PWA installability.
//
// Chrome on Android only offers a true "Install app" (a WebAPK, which lands in
// the launcher as a real package) when the page controls a service worker with
// a fetch handler. Without one, the menu degrades to "Add to Home screen",
// which some OEM launchers (vivo/Funtouch among them) silently drop.
//
// This worker deliberately does NOT cache app responses. Answers come from the
// local API and must never be served stale — a cached grade table is a wrong
// grade table. It only satisfies the fetch-handler requirement and serves the
// static shell assets that are safe to keep.

const SHELL_CACHE = "campus-brain-shell-v1";
const SHELL_ASSETS = [
  "/manifest.webmanifest",
  "/icon-192.png",
  "/icon-512.png",
  "/icon-maskable-512.png",
  "/apple-touch-icon.png",
];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(SHELL_CACHE)
      .then((cache) => cache.addAll(SHELL_ASSETS))
      .then(() => self.skipWaiting())
      .catch(() => self.skipWaiting()),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(
          keys.filter((key) => key !== SHELL_CACHE).map((key) => caches.delete(key)),
        ),
      )
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (event) => {
  const { request } = event;
  if (request.method !== "GET") return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;

  // Network-first for everything the app actually asks questions with.
  if (!SHELL_ASSETS.includes(url.pathname)) {
    event.respondWith(fetch(request));
    return;
  }

  event.respondWith(
    caches.match(request).then((hit) => hit || fetch(request)),
  );
});
