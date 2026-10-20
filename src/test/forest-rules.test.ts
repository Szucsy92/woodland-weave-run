import { describe, expect, it } from "vitest";
import { beginRun, CELL, collide, collideObstacle, DIFFICULTIES, FOG_END, getSteering, groundHeight, IMPACT_DURATION, makeSimulation, MAX_HEADING, orientationValue, RADIUS, stepSimulation, treesAround } from "@/lib/forest/simulation";

describe("Agreed forest rules", () => {
  it("allows two soft collisions and falls on the third", () => {
    const s = makeSimulation(); s.phase = "running";
    for (let i = 0; i < 3; i++) {
      s.x = 0.4; s.z = -i * 10; s.cooldown = 0; s.lastTree = "";
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
  it("uses exactly a 500 ms soft-impact envelope", () => {
    expect(IMPACT_DURATION).toBe(0.5);
    const s = makeSimulation(); s.phase = "running"; s.x = 0.4;
    collide(s, { x: 0, z: 0, scale: 1, id: "one" });
    expect(s.impact).toBe(0.5);
    for (let i = 0; i < 12; i++) stepSimulation(s, 0, 0.05);
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

describe("Difficulty levels", () => {
  it("starts on easy and makes hard faster over the same run", () => {
    expect(makeSimulation().difficulty).toBe("easy");
    const easy = makeSimulation(); easy.phase = "running";
    const hard = makeSimulation(); hard.phase = "running"; hard.difficulty = "hard";
    for (let i = 0; i < 600; i++) { stepSimulation(easy, 0, 1 / 60); stepSimulation(hard, 0, 1 / 60); }
    expect(hard.distance).toBeGreaterThan(easy.distance);
  });
  it("densifies trees on harder levels", () => {
    const easy = treesAround(0, -10, DIFFICULTIES.easy.treeGap).length;
    const hard = treesAround(0, -10, DIFFICULTIES.hard.treeGap).length;
    expect(hard).toBeGreaterThan(easy);
  });
  it("keeps the chosen difficulty across restarts", () => {
    const s = makeSimulation(); s.difficulty = "normal"; s.phase = "falling";
    beginRun(s);
    expect(s.difficulty).toBe("normal");
  });
});

describe("Terrain and obstacles", () => {
  it("rolls in every direction without repeating or jumping", () => {
    for (let i = 0; i < 500; i++) {
      const x = i * 7.3, z = -i * 5.1;
      const h = groundHeight(x, z);
      expect(Math.abs(groundHeight(x + 0.01, z) - h)).toBeLessThan(0.1);
      expect(Math.abs(groundHeight(x, z + 0.01) - h)).toBeLessThan(0.1);
      expect(h).toBeGreaterThan(-1.5); expect(h).toBeLessThan(1.5);
      expect(groundHeight(x, z - 40)).not.toBe(h);
      expect(groundHeight(x + 30, z)).not.toBe(h);
      expect(groundHeight(x - 90, z + 90)).not.toBe(h);
    }
  });
  it("stumbles hard on big rocks, briefly on undergrowth, never falls", () => {
    const s = makeSimulation(); s.phase = "running";
    expect(collideObstacle(s, { x: 0, z: -0.05, big: true, radius: 0.7, id: "r1" })).toBe(true);
    expect(s.stumble).toBeCloseTo(1); expect(s.speedMul).toBeCloseTo(0.65);
    expect(s.phase).toBe("running");
    const before = s.distance;
    for (let i = 0; i < 22; i++) stepSimulation(s, 0, 0.05);
    expect(s.distance).toBeGreaterThan(before);
    expect(s.stumble).toBe(0);
    s.cooldown = 0; s.lastTree = "";
    expect(collideObstacle(s, { x: 0, z: -0.05, big: false, radius: 0.3, id: "p1" })).toBe(true);
    expect(s.stumble).toBeCloseTo(0.5); expect(s.speedMul).toBeCloseTo(0.85);
  });
});
