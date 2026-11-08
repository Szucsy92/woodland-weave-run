import type { CapacitorConfig } from "@capacitor/cli";

// A játék teljesen offline fut az eszközön: a statikus build-et csomagoljuk be.
const config: CapacitorConfig = {
  appId: "com.woodlandweave.run",
  appName: "Woodland Weave Run",
  webDir: "dist",
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
