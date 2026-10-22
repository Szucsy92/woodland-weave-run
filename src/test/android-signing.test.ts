import { describe, expect, it } from "vitest";
import { mkdtempSync, mkdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { resolve } from "node:path";
import { spawnSync } from "node:child_process";

const script = resolve("scripts/prepare-android-signing.sh");
const names = ["ANDROID_KEYSTORE_BASE64", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD"];

describe("permanent Android signing", () => {
  for (const missing of names) {
    it(`refuses publication without ${missing}`, () => {
      const dir = mkdtempSync(`${tmpdir()}/signing-test-`);
      mkdirSync(`${dir}/android/app`, { recursive: true });
      try {
        const env = { ...process.env };
        for (const name of names) env[name] = "test-placeholder";
        delete env[missing];
        const result = spawnSync("bash", [script], { cwd: dir, env, encoding: "utf8" });
        expect(result.status).toBe(1);
        expect(result.stderr).toContain(`Missing required GitHub Actions secret: ${missing}`);
      } finally {
        rmSync(dir, { recursive: true, force: true });
      }
    });
  }
});