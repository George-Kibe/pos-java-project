import { createSerwistRoute } from "@serwist/turbopack";

/** Serves the service worker at /serwist/sw.js, built from src/app/sw.ts with the precache list. */
export const { dynamic, dynamicParams, revalidate, generateStaticParams, GET } = createSerwistRoute({
  swSrc: "src/app/sw.ts",
  useNativeEsbuild: true,
});
