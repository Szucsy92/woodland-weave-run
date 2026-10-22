import { describe, expect, it } from "vitest";

import {
  RELOAD_GUARD_KEY,
  STALE_BUNDLE_RECOVERY_SCRIPT,
  STALE_LOAD_PATTERN,
  isStaleLoadMessage,
} from "../lib/stale-bundle-recovery";

describe("stale start-up module recovery", () => {
  it("recognises the blank-screen failure the preview reported", () => {
    expect(
      isStaleLoadMessage(
        "Uncaught TypeError: Failed to fetch dynamically imported module: /node_modules/@tanstack/react-start/dist/plugin/default-entry/client.tsx",
      ),
    ).toBe(true);
  });

  it("does not treat unrelated failures as a stale start-up module", () => {
    expect(isStaleLoadMessage("TypeError: x is not a function")).toBe(false);
    expect(isStaleLoadMessage("")).toBe(false);
  });

  it("keeps the injected guard and the tested rule in sync", () => {
    expect(STALE_BUNDLE_RECOVERY_SCRIPT).toContain(JSON.stringify(STALE_LOAD_PATTERN));
    // The reload is single-shot: without the marker a broken page would loop.
    expect(STALE_BUNDLE_RECOVERY_SCRIPT).toContain(JSON.stringify(RELOAD_GUARD_KEY));
    expect(STALE_BUNDLE_RECOVERY_SCRIPT).toContain("window.location.reload()");
  });
});
