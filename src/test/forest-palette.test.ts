import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

describe("Spore palette", () => {
  it("spores are mostly lemon yellow: a bright yellow token exists", () => {
    const css = readFileSync("src/styles.css", "utf8");
    const match = css.match(/--forest-lemon:\s*(#[0-9a-fA-F]{6})/);
    expect(match).toBeTruthy();
    const hex = match ? match[1] : "";
    if (!hex) throw new Error("missing --forest-lemon token");
    const r = parseInt(hex.slice(1, 3), 16) / 255;
    const g = parseInt(hex.slice(3, 5), 16) / 255;
    const b = parseInt(hex.slice(5, 7), 16) / 255;
    // Lemon yellow: red and green near full, blue clearly low.
    expect(r).toBeGreaterThan(.85);
    expect(g).toBeGreaterThan(.85);
    expect(b).toBeLessThan(.45);
  });

  it("purple and green stay defined for rare spore accents", () => {
    const css = readFileSync("src/styles.css", "utf8");
    expect(css).toMatch(/--forest-purple:\s*#[0-9a-fA-F]{6}/);
    expect(css).toMatch(/--forest-green:\s*#[0-9a-fA-F]{6}/);
  });
});
