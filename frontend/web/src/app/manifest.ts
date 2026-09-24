import type { MetadataRoute } from "next";

import { BRAND_NAME, BUSINESS_NAME } from "@/lib/brand";

/** Lets a lane be installed as an app, with the Realhive mark as its icon. */
export default function manifest(): MetadataRoute.Manifest {
  return {
    name: BRAND_NAME,
    short_name: "Realhive POS",
    description: `Point of sale and back office for ${BUSINESS_NAME}`,
    start_url: "/",
    display: "standalone",
    background_color: "#ffffff",
    theme_color: "#ffffff",
    icons: [
      { src: "/brand/realhive-logo-192.png", sizes: "192x192", type: "image/png" },
      { src: "/brand/realhive-logo-512.png", sizes: "512x512", type: "image/png" },
      { src: "/brand/realhive-maskable-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
    ],
  };
}
