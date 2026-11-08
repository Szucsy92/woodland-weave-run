import { describe, expect, it } from "vitest";
import { buildFromTag, describeUpdateEvent, pickUpdate } from "@/lib/forest/updater";

const rel = (tag: string) => ({ tag_name: tag, assets: [{ name: "app-debug.apk", browser_download_url: "https://x/app.apk" }] });

describe("update check", () => {
  it("reads the build number from the release tag", () => {
    expect(buildFromTag("v1.0.0-build12")).toBe(12);
  });
  it("reads the build number from the tag the CI workflow really publishes", () => {
    expect(buildFromTag("v1.0.0-2026-11-08_11-41-build12")).toBe(12);
  });
  it("ignores releases without an APK or a build number", () => {
    expect(pickUpdate({ tag_name: "v1.0.0-build99", assets: [{ name: "x.ipa", browser_download_url: "https://x/x.ipa" }] }, 1)).toBeNull();
    expect(pickUpdate({ tag_name: "v2.0.0", assets: [{ name: "a.apk", browser_download_url: "https://x/a.apk" }] }, 1)).toBeNull();
  });
  it("offers a release with a higher build number", () => {
    expect(pickUpdate(rel("v1.0.0-build13"), 12)?.build).toBe(13);
  });
  it("does not offer the same or older build", () => {
    expect(pickUpdate(rel("v1.0.0-build12"), 12)).toBeNull();
    expect(pickUpdate(rel("v1.0.0-build5"), 12)).toBeNull();
  });
  it("describes download progress and failures for the player", () => {
    expect(describeUpdateEvent({ state: "progress", percent: 42, message: "" })).toBe("Downloading… 42%");
    expect(describeUpdateEvent({ state: "error", percent: 0, message: "HTTP 404" })).toBe("Update failed: HTTP 404");
  });
});
