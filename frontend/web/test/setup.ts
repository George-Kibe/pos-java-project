import "@testing-library/jest-dom/vitest";

// jsdom has no layout, so it lacks elementFromPoint; input-otp calls it to position its caret.
if (typeof document !== "undefined" && !document.elementFromPoint) {
  document.elementFromPoint = () => null;
}

// Testing Library cleans up between tests on its own only when Vitest globals are enabled.
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

afterEach(cleanup);
