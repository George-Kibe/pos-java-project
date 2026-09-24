/// <reference lib="webworker" />
import {
  CacheFirst,
  ExpirationPlugin,
  NetworkFirst,
  NetworkOnly,
  type PrecacheEntry,
  Serwist,
  type SerwistGlobalConfig,
  type SerwistPlugin,
} from "serwist";

/**
 * The lane's service worker: what lets a till that lost its network be reloaded and keep selling.
 *
 * Deliberately narrow. The app's JavaScript and CSS are precached; the lane's own pages fall back
 * to their last good copy when the network is gone. API calls are never cached - they carry
 * people's data, and a cached answer would make an offline till look online. Everything else goes
 * to the network as if there were no worker.
 */
declare global {
  interface WorkerGlobalScope extends SerwistGlobalConfig {
    __SW_MANIFEST: (PrecacheEntry | string)[] | undefined;
  }
}
declare const self: ServiceWorkerGlobalScope;

/** Only a page that actually rendered: never a redirect to the login page, never an error. */
const renderedPagesOnly: SerwistPlugin = {
  cacheWillUpdate: async ({ response }) => (response.status === 200 && !response.redirected ? response : null),
};

const lanePath = (pathname: string) => pathname === "/lane" || pathname.startsWith("/lane/");

const serwist = new Serwist({
  precacheEntries: self.__SW_MANIFEST,
  skipWaiting: true,
  clientsClaim: true,
  navigationPreload: false,
  runtimeCaching: [
    {
      matcher: ({ url, sameOrigin }) => sameOrigin && url.pathname.startsWith("/api/"),
      handler: new NetworkOnly(),
    },
    {
      matcher: ({ request, url, sameOrigin }) => sameOrigin && request.mode === "navigate" && lanePath(url.pathname),
      handler: new NetworkFirst({ cacheName: "lane-pages", networkTimeoutSeconds: 5, plugins: [renderedPagesOnly] }),
    },
    {
      matcher: ({ request, url, sameOrigin }) => sameOrigin && request.headers.get("RSC") === "1" && lanePath(url.pathname),
      handler: new NetworkFirst({ cacheName: "lane-rsc", networkTimeoutSeconds: 5, plugins: [renderedPagesOnly] }),
    },
    {
      matcher: ({ url, sameOrigin }) => sameOrigin && url.pathname.startsWith("/_next/static/"),
      handler: new CacheFirst({
        cacheName: "next-static",
        plugins: [new ExpirationPlugin({ maxEntries: 512, maxAgeSeconds: 30 * 24 * 60 * 60 })],
      }),
    },
  ],
});

serwist.addEventListeners();
