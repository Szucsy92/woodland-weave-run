import { describe, expect, it } from "vitest";
import {
  collide, enterHole, exitWarp, HOLE_CORE, HOLE_CELL, holeExit, holesAround, makeSimulation, stepSimulation,
  treesAround, warpChaos, warpEnvelope, WARP_CHAOS_AT, WARP_DURATION, WARP_EXIT_TIME, WARP_SHUFFLE, type Simulation,
} from "@/lib/forest/simulation";

const running = (): Simulation => {
  const s = makeSimulation();
  s.mode = "freeroam";
  s.phase = "running";
  return s;
};
const someHole = () => {
  for (let i = -3; i <= 3; i++) for (let j = -3; j <= 3; j++) {
    const h = holesAround(i * HOLE_CELL + 60, j * HOLE_CELL + 60)[0];
    if (h) return h;
  }
  throw new Error("no hole found");
};

describe("Black holes in freeroam", () => {
  it("are placed deterministically, never in the spawn cell", () => {
    expect(holesAround(500, 500)).toEqual(holesAround(500, 500));
    expect(holesAround(0, 0).every(h => h.id !== "h0:0")).toBe(true);
  });
  it("only pull the runner in on freeroam, and only at the dark body", () => {
    const hole = someHole();
    const s = running();
    s.x = hole.x + HOLE_CORE + 1; s.z = hole.z;
    expect(enterHole(s, hole)).toBe(false);
    s.x = hole.x + .5;
    s.mode = "classic";
    expect(enterHole(s, hole)).toBe(false);
    s.mode = "freeroam";
    expect(enterHole(s, hole)).toBe(true);
    expect(s.warp).toBe(0);
    // Already inside: no second fall.
    expect(enterHole(s, hole)).toBe(false);
  });
  it("run a 10 second wormhole, then throw the runner out far away", () => {
    const hole = someHole();
    const s = running();
    s.x = hole.x; s.z = hole.z;
    expect(enterHole(s, hole)).toBe(true);
    const exit = holeExit(hole);
    expect(Math.hypot(exit.x - hole.x, exit.z - hole.z)).toBeGreaterThan(150);
    let t = 0, sawExit = false;
    while (t < WARP_DURATION + .5) {
      stepSimulation(s, 0, .05);
      t += .05;
      if (s.warp < 0 && !sawExit) { sawExit = true; expect(t).toBeGreaterThanOrEqual(WARP_DURATION - .1); }
    }
    expect(sawExit).toBe(true);
    expect(s.warp).toBe(-1);
    expect(Math.hypot(s.x - hole.x, s.z - hole.z)).toBeGreaterThan(100);
    // The exit shock settles.
    for (let i = 0; i < WARP_EXIT_TIME / .05 + 2; i++) stepSimulation(s, 0, .05);
    expect(s.warpOut).toBe(-1);
  });
  it("make the world chaotic: the heading drifts and trees no longer block", () => {
    const hole = someHole();
    const s = running();
    s.x = hole.x; s.z = hole.z;
    enterHole(s, hole);
    for (let i = 0; i < 60; i++) stepSimulation(s, 0, .05);
    expect(Math.abs(s.heading)).toBeGreaterThan(.05);
    expect(warpEnvelope(s)).toBeCloseTo(1, 5);
    const tree = { x: s.x + .2, z: s.z, scale: 1, id: "t" };
    const before = { x: s.x, z: s.z };
    expect(collide(s, tree)).toBe(false);
    expect(s.x).toBe(before.x);
  });
  it("fade in and out smoothly", () => {
    const s = running();
    expect(warpEnvelope(s)).toBe(0);
    s.warp = 0; expect(warpEnvelope(s)).toBe(0);
    s.warp = .35; expect(warpEnvelope(s)).toBeGreaterThan(0); expect(warpEnvelope(s)).toBeLessThan(1);
    s.warp = 5; expect(warpEnvelope(s)).toBe(1);
    s.warp = WARP_DURATION - .3; expect(warpEnvelope(s)).toBeLessThan(1);
    s.warp = WARP_DURATION; expect(warpEnvelope(s)).toBe(0);
  });
  it("keep the same track and re-pose it every few seconds, then go back to normal", () => {
    const s = running();
    expect(warpChaos(s)).toBe(-1);
    s.warp = WARP_CHAOS_AT - .05;
    expect(warpChaos(s)).toBe(-1);
    s.warp = WARP_CHAOS_AT + .1;
    expect(warpChaos(s)).toBe(0);
    s.warp = WARP_CHAOS_AT + WARP_SHUFFLE + .1;
    expect(warpChaos(s)).toBe(1);
    s.warp = WARP_DURATION - .1;
    expect(warpChaos(s)).toBeGreaterThanOrEqual(2);
    s.warp = WARP_DURATION;
    expect(warpChaos(s)).toBe(-1);
    // The trees themselves are never moved or repeated: only their pose changes.
    expect(treesAround(10, 10, .57)).toEqual(treesAround(10, 10, .57));
    const ids = treesAround(10, 10, .57).map(t => t.id);
    expect(new Set(ids).size).toBe(ids.length);
  });
  it("resets with a new run and exits cleanly when asked", () => {
    const s = running();
    s.warp = 4; s.warpHole = "x"; s.warpFrom = { x: 1, z: 2 };
    exitWarp(s);
    expect(s.warp).toBe(-1);
    expect(s.warpOut).toBe(0);
    expect(makeSimulation().warp).toBe(-1);
  });
});
