import { describe, expect, it } from "vitest";
import { hash, obstaclesAround, OBSTACLE_DENSITY } from "@/lib/forest/simulation";
import { beginRun, CELL, collide, collideObstacle, DIFFICULTIES, FOG_END, getSteering, groundHeight, IMPACT_DURATION, makeSimulation, MAX_HEADING, orientationValue, placeSpore, RADIUS, sporeOpacity, stepSimulation, treesAround } from "@/lib/forest/simulation";

describe("World-depth spores", () => {
  it("scatters visible spores to both sides and multiple depths, including beside the runner", () => {
    const spores = Array.from({ length: 72 }, (_, i) => placeSpore(0, 0, i, 0, true));
    expect(spores.filter(p => p.x < -5).length).toBeGreaterThan(10);
    expect(spores.filter(p => p.x > 5).length).toBeGreaterThan(10);
    expect(spores.some(p => Math.abs(p.z) < 5)).toBe(true);
    expect(spores.some(p => p.z < -35)).toBe(true);
    expect(sporeOpacity(40, 0)).toBeGreaterThan(.1);
  });
  it("does not disappear while the runner passes nearby", () => {
    for (const distance of [0, .5, 1.2, 3.5, 10]) {
      expect(sporeOpacity(distance, 0)).toBeGreaterThan(.2);
    }
  });
});

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
  it("caps the total turning arc at 75 degrees", () => {
    expect((MAX_HEADING * 2 * 180) / Math.PI).toBeCloseTo(75, 5);
  });
  it("never turns or runs backwards, including sustained steering", () => {
    const s = makeSimulation(); s.phase = "running";
    for (let i = 0; i < 1000; i++) {
      const z = s.z; stepSimulation(s, 1, 1 / 60);
      expect(s.z).toBeLessThan(z); expect(Math.abs(s.heading)).toBeLessThanOrEqual(MAX_HEADING);
    }
  });
  it("never auto-centres, and counter-steering at the turn limit turns back at once", () => {
    const s = makeSimulation(); s.phase = "running"; s.heading = MAX_HEADING;
    for (let i = 0; i < 60; i++) stepSimulation(s, 0, 1 / 60);
    expect(s.heading).toBeCloseTo(MAX_HEADING, 6);
    for (let i = 0; i < 30; i++) stepSimulation(s, 1, 1 / 60);
    expect(s.heading).toBeLessThan(MAX_HEADING * 0.3);
  });
  it("ignores screen presses until touch mode is explicitly enabled", () => {
    expect(getSteering(false, 1, 0)).toBe(0);
    expect(getSteering(false, -1, 0.5)).toBe(0.5);
    expect(getSteering(true, -1, 0.5)).toBe(-1);
  });
  it("supports both landscape directions and portrait tilt", () => {
    expect(orientationValue(12.5, 0, 0)).toBeCloseTo(0.5, 2);
    expect(orientationValue(0, 12.5, 90)).toBeCloseTo(0.5, 2);
    expect(orientationValue(0, 12.5, -90)).toBeCloseTo(-0.5, 2);
  });
  it("keeps tilt steady when the phone passes upright (no jump or flip)", () => {
    // Same physical pose reported both sides of the sensor's 90° flip.
    expect(orientationValue(88, 10, 90)).toBeCloseTo(orientationValue(88, 170, 90), 6);
    expect(orientationValue(88, 10, 90)).toBeGreaterThan(0.3);
    let prev = orientationValue(60, -40, 90);
    for (let beta = -40; beta <= 40; beta += 2) {
      const v = orientationValue(80, beta, 90);
      expect(Math.abs(v - prev)).toBeLessThan(0.2);
      prev = v;
    }
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
  it("halves both bushes and rocks on every difficulty without moving retained obstacles", () => {
    expect(OBSTACLE_DENSITY).toBe(0.5);
    for (const level of Object.values(DIFFICULTIES)) {
      let oldBushes = 0, oldRocks = 0, bushes = 0, rocks = 0;
      for (const cx of [-60, 0, 60]) {
        const cz = -60;
        const actual = obstaclesAround(cx, cz, level.obstacleGap);
        const expected: string[] = [];
        for (let i = -RADIUS; i <= RADIUS; i++) for (let j = -RADIUS; j <= RADIUS; j++) {
          const x = cx + i, z = cz + j;
          if (hash(x, z, 9) <= level.obstacleGap) continue;
          if (hash(x, z, 13) > 0.86) oldRocks++; else oldBushes++;
          if (hash(x, z, 15) < 0.5) expected.push(`o${x}:${z}`);
        }
        expect(actual.map(o => o.id)).toEqual(expected);
        for (const o of actual) {
          if (o.big) rocks++; else bushes++;
          const [x, z] = o.id.slice(1).split(":").map(Number);
          expect(o.x).toBe(x * CELL + hash(x, z, 11) * CELL);
          expect(o.z).toBe(z * CELL + hash(x, z, 12) * CELL);
        }
      }
      expect(bushes / oldBushes).toBeGreaterThan(0.4);
      expect(bushes / oldBushes).toBeLessThan(0.6);
      expect(rocks / oldRocks).toBeGreaterThan(0.35);
      expect(rocks / oldRocks).toBeLessThan(0.65);
    }
  });
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
    s.x = 0; s.z = -0.05;
    expect(collideObstacle(s, { x: 0, z: -0.05, big: false, radius: 0.3, id: "p1" })).toBe(true);
    expect(s.stumble).toBeCloseTo(0.5); expect(s.speedMul).toBeCloseTo(0.85);
  });
});

import { daylightFor } from "@/lib/forest/simulation";
describe("Forest mood", () => {
  it("night and day are fixed", () => { expect(daylightFor("night", 12)).toBe(0); expect(daylightFor("day", 0)).toBe(1); });
  it("auto is dark at night, bright at noon, gradual at dawn and dusk", () => {
    expect(daylightFor("auto", 2)).toBe(0);
    expect(daylightFor("auto", 12)).toBe(1);
    expect(daylightFor("auto", 6.5)).toBeCloseTo(.5);
    expect(daylightFor("auto", 18.5)).toBeCloseTo(.5);
  });
});

import { BASE_SPEED, strideMultiplier, STRIDE_MAX_MUL, STRIDE_RATE } from "@/lib/forest/simulation";
describe("Stride rhythm follows the pace", () => {
  it("keeps the agreed rhythm at the start of a run", () => {
    const s = makeSimulation(); s.phase = "running";
    for (let i = 0; i < 60; i++) stepSimulation(s, 0, 1 / 60);
    expect(s.stride).toBeGreaterThan(STRIDE_RATE * 0.99);
    expect(s.stride).toBeLessThan(STRIDE_RATE * 1.05);
  });
  it("quickens the movement as the runner speeds up, never past the cap", () => {
    const early = makeSimulation(); early.phase = "running";
    const late = makeSimulation(); late.phase = "running"; late.distance = 1200;
    for (let i = 0; i < 60; i++) { stepSimulation(early, 0, 1 / 60); stepSimulation(late, 0, 1 / 60); }
    const ratio = late.stride / early.stride;
    expect(ratio).toBeGreaterThan(1.2);
    expect(ratio).toBeLessThanOrEqual(STRIDE_MAX_MUL + 0.02);
    expect(strideMultiplier(BASE_SPEED)).toBe(1);
    expect(strideMultiplier(30)).toBe(STRIDE_MAX_MUL);
  });
  it("slows the stride while stumbling over a rock", () => {
    const s = makeSimulation(); s.phase = "running";
    collideObstacle(s, { x: 0, z: -0.05, big: true, radius: 0.7, id: "r1" });
    const before = s.stride;
    stepSimulation(s, 0, 0.1);
    expect((s.stride - before) / (STRIDE_RATE * 0.1)).toBeLessThan(0.9);
  });
});

describe("Trunks are solid", () => {
  it("never lets the runner pass through a trunk, even right after a hit", () => {
    const s = makeSimulation(); s.phase = "running"; s.x = 0.05; s.z = 3;
    const tree = { x: 0, z: 0, scale: 1, id: "solid" };
    s.trees = [tree];
    for (let i = 0; i < 240; i++) {
      stepSimulation(s, 0, 1 / 60);
      expect(Math.hypot(s.x - tree.x, s.z - tree.z)).toBeGreaterThanOrEqual(0.45 + 0.22 - 0.05);
    }
    expect(s.z).toBeLessThan(-1);
  });
});

import { requestPause as rp, requestResume as rr, requestExit as re, timeScale as ts, PAUSE_SLOW as PS, RESUME_TIME as RT, EXIT_TIME as ET, makeSimulation as mk, stepSimulation as step } from "@/lib/forest/simulation";
describe("pause time warp", () => {
  it("slows to a full freeze, then accelerates back to normal speed", () => {
    const s = mk(); s.phase = "running";
    expect(rp(s)).toBe(true);
    step(s, 0, PS / 2); expect(ts(s)).toBeGreaterThan(0); expect(ts(s)).toBeLessThan(1);
    for (let i = 0; i < 40; i++) step(s, 0, 0.05);
    expect(s.pause).toBe("paused"); const d = s.distance;
    step(s, 0, 0.05); expect(s.distance).toBe(d);
    rr(s); for (let i = 0; i < Math.ceil(RT / 0.05) + 1; i++) step(s, 0, 0.05);
    expect(s.pause).toBe("none"); expect(ts(s)).toBe(1);
  });
  it("exit returns to the menu", () => {
    const s = mk(); s.phase = "running"; rp(s); re(s);
    for (let i = 0; i < Math.ceil(ET / 0.05) + 1; i++) step(s, 0, 0.05);
    expect(s.phase).toBe("menu");
  });
});
