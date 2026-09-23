import { fileURLToPath } from "node:url";

import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
      // `server-only` throws outside the server bundle by design; unit tests run server code directly.
      "server-only": fileURLToPath(new URL("./test/server-only-stub.ts", import.meta.url)),
    },
  },
  test: {
    environment: "node",
    include: ["src/**/*.test.{ts,tsx}"],
    setupFiles: ["./test/setup.ts"],
    env: {
      // A fixed key for tests only; real deployments get one from `make env`.
      WEB_SESSION_SECRET: "test-secret-that-is-at-least-thirty-two-characters",
      GATEWAY_URL: "http://gateway.test",
    },
    restoreMocks: true,
  },
});
