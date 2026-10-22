import { describe, expect, it, vi } from "vitest";

import {
  RELOAD_GUARD_KEY,
  STALE_BUNDLE_RECOVERY_SCRIPT,
  STALE_LOAD_PATTERN,
  isStaleLoadMessage,
  recoverCaughtStaleLoad,
} from "../lib/stale-bundle-recovery";

describe("stale start-up module recovery", () => {
  it("recovers a router-caught split module failure only once within the cooldown", () => {
    const listeners = new Map<string, () => void>();
    const reload = vi.fn();
    const values = new Map<string, string>();
    const fakeWindow = {
      location: { reload },
      addEventListener: (name: string, listener: () => void) => listeners.set(name, listener),
    };
    const storage = {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
    };
    new Function("window", "sessionStorage", "setTimeout", STALE_BUNDLE_RECOVERY_SCRIPT)(fakeWindow, storage, vi.fn());
    const forward = () => listeners.get("woodland-stale-module")?.();
    window.addEventListener("woodland-stale-module", forward);
    try {
      const error = new Error("Failed to fetch dynamically imported module: /src/routes/index.tsx?tsr-split=component");
      recoverCaughtStaleLoad(error);
      recoverCaughtStaleLoad(error);
      expect(reload).toHaveBeenCalledTimes(1);
      expect(values.has(RELOAD_GUARD_KEY)).toBe(true);
    } finally {
      window.removeEventListener("woodland-stale-module", forward);
    }
  });

  it("does not reload for a router-caught unrelated error", () => {
    const listener = vi.fn();
    window.addEventListener("woodland-stale-module", listener);
    try {
      recoverCaughtStaleLoad(new Error("Invalid game state"));
      expect(listener).not.toHaveBeenCalled();
    } finally {
      window.removeEventListener("woodland-stale-module", listener);
    }
  });
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
