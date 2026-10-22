import { describe, expect, it } from "vitest";
import { buildFromTag, pickUpdate } from "@/lib/forest/updater";

const rel = (tag: string) => ({ tag_name: tag, assets: [{ name: "app-debug.apk", browser_download_url: "https://x/app.apk" }] });

describe("update check", () => {
  it("reads the build number from the release tag", () => {
    expect(buildFromTag("v1.0.0-build12")).toBe(12);
  });
  it("offers a release with a higher build number", () => {
    expect(pickUpdate(rel("v1.0.0-build13"), 12)?.build).toBe(13);
  });
  it("does not offer the same or older build", () => {
    expect(pickUpdate(rel("v1.0.0-build12"), 12)).toBeNull();
    expect(pickUpdate(rel("v1.0.0-build5"), 12)).toBeNull();
  });
});
