import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // The phone client at /m is meant to be opened on a real handset — during a
  // demo that means http://<laptop-LAN-ip>:3000/m, not localhost. Next 16 blocks
  // cross-origin requests to the dev server by default, so without this the page
  // fails to load from the phone even though the API proxy underneath it works
  // fine. Private LAN ranges only; this affects `next dev` and is ignored by
  // `next build && next start`.
  allowedDevOrigins: [
    "192.168.0.0/16",
    "10.0.0.0/8",
    "172.16.0.0/12",
  ],
};

export default nextConfig;
