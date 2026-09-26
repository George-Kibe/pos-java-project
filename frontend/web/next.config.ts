import { withSerwist } from "@serwist/turbopack";
import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // A self-contained server in .next/standalone: the image carries only what it runs.
  output: "standalone",
  poweredByHeader: false,
  // The manual PDFs: pdfmake reads its fonts from its own folder, so it is required, not bundled.
  serverExternalPackages: ["pdfmake"],
  outputFileTracingIncludes: { "/api/manuals/*": ["./node_modules/pdfmake/fonts/Roboto/*.ttf"] },
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          // The lane must not be framed by another site (clickjacking a till is a real attack).
          { key: "X-Frame-Options", value: "DENY" },
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
          // USB and serial stay allowed for this site alone: the receipt printer is reached that way.
          { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=(), usb=(self), serial=(self)" },
        ],
      },
    ];
  },
};

// withSerwist keeps esbuild out of the server bundle; the worker itself is built by its route.
export default withSerwist(nextConfig);
