"use client";

import { useEffect } from "react";

/**
 * Registers public/sw.js so Chrome on Android treats /m as installable and
 * offers "Install app" (a WebAPK) instead of a plain home-screen shortcut.
 * Renders nothing; scoped to /m via the mobile layout.
 */
export default function ServiceWorkerRegistrar() {
  useEffect(() => {
    if (!("serviceWorker" in navigator)) return;
    navigator.serviceWorker.register("/sw.js", { scope: "/" }).catch(() => {
      // Installability is a nice-to-have; the page works either way.
    });
  }, []);

  return null;
}
