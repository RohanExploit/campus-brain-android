import type { Metadata, Viewport } from "next";
import ServiceWorkerRegistrar from "@/components/mobile/ServiceWorkerRegistrar";

export const metadata: Metadata = {
  title: "Campus Brain",
  description:
    "Ask your institution's data anything. Routed, cited, and answered by Qwen3 4B running on the laptop.",
};

export const viewport: Viewport = {
  themeColor: "#0A0F1E",
  // Draw under the status bar and the gesture pill; the shell pads itself back
  // out with env(safe-area-inset-*).
  viewportFit: "cover",
  width: "device-width",
  initialScale: 1,
  // Deliberately NOT locking zoom: pinching a student's grade table is exactly
  // the thing a registrar does on a phone.
};

/**
 * The desktop console's chrome is declared in the root layout, which this route
 * cannot edit. Rather than duplicate the root layout, /m suppresses that chrome
 * for as long as it is mounted: an inline <style> (no `precedence`, so React
 * renders it in place and removes it on unmount) collapses the shell grid to a
 * single full-bleed cell and hides the status strip and sidebar. Navigating back
 * to the console restores everything, in dev and in production alike — which a
 * CSS-module `:global` block would not guarantee, since route CSS is kept alive
 * across soft navigations in development.
 */
const SHELL_RESET = `
.app-shell {
  grid-template-columns: 1fr;
  grid-template-rows: 1fr;
  grid-template-areas: "main";
  height: 100dvh;
  min-height: 0;
}
.app-shell > .status-strip,
.app-shell > .sidebar { display: none; }
.app-shell > .main-content {
  overflow: hidden;
  background: var(--color-shell);
  min-width: 0;
}
html, body { overflow: hidden; overscroll-behavior: none; }
/* background-attachment: fixed repaints on every scroll on mobile GPUs. */
body { background-attachment: scroll; }
`;

export default function MobileLayout({ children }: { children: React.ReactNode }) {
  return (
    <>
      <style dangerouslySetInnerHTML={{ __html: SHELL_RESET }} />
      <ServiceWorkerRegistrar />
      {children}
    </>
  );
}
