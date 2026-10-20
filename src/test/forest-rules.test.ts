import { describe, expect, it } from "vitest";
import { beginRun, CELL, collide, FOG_END, getSteering, IMPACT_DURATION, makeSimulation, MAX_HEADING, orientationValue, RADIUS, stepSimulation, treesAround } from "@/lib/forest/simulation";

describe("Agreed forest rules", () => {
  it("allows two soft collisions and falls on the third", () => {
    const s = makeSimulation(); s.phase = "running";
    for (let i = 0; i < 3; i++) {
      s.x = 0.4; s.z = -i * 10; s.cooldown = 0;
      expect(collide(s, { x: 0, z: -i * 10, scale: 1, id: String(i) })).toBe(true);
      expect(s.phase).toBe(i === 2 ? "falling" : "running");
    }
    expect(s.hits).toBe(3);
  });
  it("slides away from the edge without repeating damage on the same trunk", () => {
    const s = makeSimulation(); s.phase = "running"; s.x = 0.5;
    const tree = { x: 0, z: 0, scale: 1, id: "one" };
    collide(s, tree); expect(s.x).toBeGreaterThan(0.67); expect(s.pushX).toBeGreaterThan(0);
    s.x = 0.5; s.cooldown = 0; collide(s, tree); expect(s.hits).toBe(1);
  });
  it("uses exactly a 250 ms soft-impact envelope", () => {
    expect(IMPACT_DURATION).toBe(0.25);
    const s = makeSimulation(); s.phase = "running"; s.x = 0.4;
    collide(s, { x: 0, z: 0, scale: 1, id: "one" });
    expect(s.impact).toBe(0.25);
    for (let i = 0; i < 6; i++) stepSimulation(s, 0, 0.05);
    expect(s.impact).toBe(0);
  });
  it("never turns or runs backwards, including sustained steering", () => {
    const s = makeSimulation(); s.phase = "running";
    for (let i = 0; i < 1000; i++) {
      const z = s.z; stepSimulation(s, 1, 1 / 60);
      expect(s.z).toBeLessThan(z); expect(Math.abs(s.heading)).toBeLessThanOrEqual(MAX_HEADING);
    }
  });
  it("ignores screen presses until touch mode is explicitly enabled", () => {
    expect(getSteering(false, 1, 0)).toBe(0);
    expect(getSteering(false, -1, 0.5)).toBe(0.5);
    expect(getSteering(true, -1, 0.5)).toBe(-1);
  });
  it("supports both landscape directions and portrait tilt", () => {
    expect(orientationValue(12.5, 0, 0)).toBe(0.5);
    expect(orientationValue(0, 12.5, 90)).toBe(0.5);
    expect(orientationValue(0, 12.5, -90)).toBe(-0.5);
  });
  it("keeps entering generation rows beyond the fully opaque fog", () => {
    expect((RADIUS - 1) * CELL).toBeGreaterThan(FOG_END + 20);
    const old = new Set(treesAround(0, -10).map(t => t.id));
    const entering = treesAround(0, -11).filter(t => !old.has(t.id));
    expect(entering.length).toBeGreaterThan(0);
    expect(entering.every(t => Math.abs(t.z - (-11 * CELL)) > FOG_END)).toBe(true);
  });
  it("keeps the fallen view until restarting, then resets impacts", () => {
    const s = makeSimulation(); s.phase = "falling"; s.hits = 3;
    for (let i = 0; i < 100; i++) stepSimulation(s, 0, 0.05);
    expect(s.phase).toBe("fallen");
    beginRun(s); expect(s.phase).toBe("awakening"); expect(s.hits).toBe(0); expect(s.distance).toBe(0);
  });
});