import { describe, expect, it } from "vitest";
import { beginRun, collectPowerUp, collide, collideObstacle, makeSimulation, placePowerUp, POWERUP_DURATION, POWERUP_GAP, POWERUP_START, stepSimulation, type PowerUp } from "@/lib/forest/simulation";

const orb = (type: PowerUp["type"], x = 0, z = 0): PowerUp => ({ x, z, type, id: `t-${type}` });

describe("Power-up wisps", () => {
  it("only appear deep into the run, rare and spaced far apart", () => {
    expect(POWERUP_START).toBe(550);
    expect(POWERUP_GAP).toBeGreaterThanOrEqual(120);
    // Placement itself is chance-gated: most slots stay empty.
    let placed = 0;
    for (let i = 0; i < 20; i++) if (placePowerUp(0, -600, i, 600)) placed++;
    expect(placed).toBeLessThan(20);
  });

  it("binds only one power at a time — a second wisp is ignored", () => {
    const s = makeSimulation();
    s.phase = "running";
    expect(collectPowerUp(s, orb("freezer"))).toBe(true);
    expect(s.powerUp).toBe("freezer");
    expect(collectPowerUp(s, orb("ghost"))).toBe(false);
    expect(s.powerUp).toBe("freezer");
  });

  it("fades out after the agreed duration", () => {
    const s = makeSimulation();
    s.phase = "running";
    collectPowerUp(s, orb("ghost"));
    for (let t = 0; t < POWERUP_DURATION + 0.2; t += 0.05) stepSimulation(s, 0, 0.05);
    expect(s.powerUp).toBeNull();
  });

  it("ignores wisps that are out of reach", () => {
    const s = makeSimulation();
    s.phase = "running";
    expect(collectPowerUp(s, orb("ghost", 3, 0))).toBe(false);
    expect(s.powerUp).toBeNull();
  });

  it("ghost lets the runner pass through trunks unharmed", () => {
    const s = makeSimulation();
    s.phase = "running";
    collectPowerUp(s, orb("ghost"));
    const tree = { x: 0, z: -0.3, scale: 1, id: "t1" };
    expect(collide(s, tree)).toBe(false);
    expect(s.hits).toBe(0);
  });

  it("freezer slows the world's rush while steering stays intact", () => {
    const plain = makeSimulation();
    plain.phase = "running";
    const frozen = makeSimulation();
    frozen.phase = "running";
    collectPowerUp(frozen, orb("freezer"));
    for (let i = 0; i < 20; i++) { stepSimulation(plain, 0, 0.05); stepSimulation(frozen, 0, 0.05); }
    expect(frozen.distance).toBeLessThan(plain.distance * 0.7);
    // Steering is untouched: same tilt, same heading change.
    const a = makeSimulation(); a.phase = "running";
    const b = makeSimulation(); b.phase = "running";
    collectPowerUp(b, orb("freezer"));
    stepSimulation(a, 1, 0.05); stepSimulation(b, 1, 0.05);
    expect(b.heading).toBeCloseTo(a.heading, 5);
  });

  it("shockwave flattens the obstacles just ahead and they stay harmless", () => {
    const s = makeSimulation();
    s.phase = "running";
    const bush = { x: 1, z: -5, big: false, radius: 0.3, id: "o1" };
    s.obstacles = [bush];
    collectPowerUp(s, orb("shockwave"));
    expect(s.cleared).toContain("o1");
    s.x = 1; s.z = -5;
    expect(collideObstacle(s, bush)).toBe(false);
    expect(s.stumble).toBe(0);
  });

  it("shockwave leaves obstacles outside the wave untouched", () => {
    const s = makeSimulation();
    s.phase = "running";
    const far = { x: 1, z: -30, big: false, radius: 0.3, id: "o2" };
    s.obstacles = [far];
    collectPowerUp(s, orb("shockwave"));
    expect(s.cleared).not.toContain("o2");
  });
});

describe("Survive mode threat", () => {
  it("flares for about a second after every impact, then fades", () => {
    const s = makeSimulation();
    s.mode = "survive";
    s.phase = "running";
    expect(collide(s, { x: 0, z: -0.3, scale: 1, id: "t1" })).toBe(true);
    expect(s.threat).toBe(1);
    for (let i = 0; i < 10; i++) stepSimulation(s, 0, 0.05);
    expect(s.threat).toBeGreaterThan(0);
    expect(s.threat).toBeLessThan(1);
    for (let i = 0; i < 12; i++) stepSimulation(s, 0, 0.05);
    expect(s.threat).toBe(0);
  });

  it("stays dark in classic mode — impacts raise no threat", () => {
    const s = makeSimulation();
    s.phase = "running";
    collide(s, { x: 0, z: -0.3, scale: 1, id: "t1" });
    expect(s.threat).toBe(0);
  });

  it("burns constantly while the runner is down", () => {
    const s = makeSimulation();
    s.mode = "survive";
    s.phase = "falling";
    s.threat = 1;
    for (let i = 0; i < 40; i++) stepSimulation(s, 0, 0.05);
    expect(s.threat).toBe(1);
  });

  it("survives a new run — the chosen mode carries over", () => {
    const s = makeSimulation();
    s.mode = "survive";
    beginRun(s);
    expect(s.mode).toBe("survive");
    expect(s.phase).toBe("awakening");
    expect(s.threat).toBe(0);
    expect(s.powerUp).toBeNull();
  });
});
