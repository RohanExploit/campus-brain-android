import type { Metadata } from "next";
import "./globals.css";
import StatusStrip from "@/components/StatusStrip";
import Sidebar from "@/components/Sidebar";

export const metadata: Metadata = {
  title: "Campus Brain — Admin",
  description: "Admin dashboard for Campus Brain multi-tenant RAG system",
  // PWA install metadata. Declared at the root because the <link rel="manifest">
  // has to be in <head>, and the root layout owns it — but the manifest's
  // start_url is /m, so "Add to Home screen" installs the phone client, not this
  // console. Static file: public/manifest.webmanifest.
  manifest: "/manifest.webmanifest",
  // iOS ignores the manifest icons; `icon` is left to the app/favicon.ico
  // file convention so this does not disturb the desktop tab icon.
  icons: { apple: "/apple-touch-icon.png" },
  appleWebApp: { capable: true, title: "Campus Brain", statusBarStyle: "black-translucent" },
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body suppressHydrationWarning>
        <div className="app-shell">
          <StatusStrip />
          <Sidebar />
          <main className="main-content">
            {children}
          </main>
        </div>
      </body>
    </html>
  );
}
