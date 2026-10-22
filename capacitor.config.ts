import type { CapacitorConfig } from "@capacitor/cli";

// The Android app bundles the fully static, prerendered web build — the game
// runs entirely on-device, with no server or network dependency.
const config: CapacitorConfig = {
  appId: "com.woodlandweave.run",
  appName: "Woodland Weave Run",
  webDir: "dist/client",
  android: {
    allowMixedContent: false,
  },
};

export default config;
