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
  plugins: {
    // The built-in Capacitor plugin otherwise queues a show() on startup,
    // undoing the activity's immersive policy after its first frame.
    SystemBars: {
      hidden: true,
      insetsHandling: "css",
      initialViewportFitValueHint: "cover",
    },
  },
};

export default config;
