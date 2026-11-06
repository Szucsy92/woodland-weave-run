import { describe, expect, it } from "vitest";
import { beginRun, collectPowerUp, collide, collideObstacle, DARK_PAUSE, discoverSecret, canSpawnPowerUp, forestLightsAround, fixedForestLights, forestLightsFor, LIGHTS_DIE, makeSimulation, MAX_HEADING, placePowerUp, POWERUP_DURATION, POWERUP_GAP, POWERUP_START, secretsAround, stepSimulation, surviveStage, type PowerUp } from "@/lib/forest/simulation";

const orb = (type: PowerUp["type"], x = 0, z = 0): PowerUp => ({ x, z, type, id: `t-${type}` });

describe("Power-up wisps", () => {
  it("only appear deep into the run, rare and spaced far apart", () => {
    expect(POWERUP_START).toBe(100);
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

describe("Agreed power-up tuning", () => {
  it("every power lasts 5 seconds", () => {
    expect(POWERUP_DURATION).toBe(5);
    const s = makeSimulation();
    s.phase = "running";
    collectPowerUp(s, orb("freezer"));
    for (let i = 0; i < 96; i++) stepSimulation(s, 0, 0.05); // 4.8 s
    expect(s.powerUp).toBe("freezer");
    for (let i = 0; i < 6; i++) stepSimulation(s, 0, 0.05);
    expect(s.powerUp).toBeNull();
  });

  it("shockwave also dissolves trees in the way, which then stop blocking", () => {
    const s = makeSimulation();
    s.phase = "running";
    const tree = { x: 0.5, z: -8, scale: 1, id: "t9" };
    s.trees = [tree];
    collectPowerUp(s, orb("shockwave"));
    expect(s.cleared).toContain("t9");
    s.powerUp = null;
    s.x = 0.5; s.z = -8;
    expect(collide(s, tree)).toBe(false);
    expect(s.hits).toBe(0);
  });

  it("ghost also passes through bushes", () => {
    const s = makeSimulation();
    s.phase = "running";
    collectPowerUp(s, orb("ghost"));
    expect(collideObstacle(s, { x: 0, z: 0, big: true, radius: 0.6, id: "o5" })).toBe(false);
  });
});

describe("Survive sequence", () => {
  it("lights die at 10 m, then 1.5-2 s of darkness before the red light and threat sound", () => {
    expect(DARK_PAUSE).toBeGreaterThanOrEqual(1.5);
    expect(DARK_PAUSE).toBeLessThanOrEqual(2);
    const s = makeSimulation();
    s.mode = "survive";
    s.phase = "running";
    s.distance = 9;
    expect(surviveStage(s)).toBe("lit");
    s.distance = 10.5;
    stepSimulation(s, 0, 0.05);
    expect(surviveStage(s)).toBe("dying");
    let t = 0;
    while (surviveStage(s) !== "red" && t < 10) { stepSimulation(s, 0, 0.05); t += 0.05; }
    // Red arrives only after the lights fully died plus the dark pause.
    expect(t).toBeGreaterThanOrEqual(LIGHTS_DIE + DARK_PAUSE - 0.1);
  });

  it("classic mode never enters the sequence", () => {
    const s = makeSimulation();
    s.phase = "running";
    s.distance = 50;
    stepSimulation(s, 0, 0.05);
    expect(surviveStage(s)).toBe("lit");
  });
});

describe("Freeroam", () => {
  it("can turn all the way round and walk back", () => {
    const s = makeSimulation();
    s.mode = "freeroam";
    s.phase = "running";
    for (let i = 0; i < 60; i++) stepSimulation(s, -1, 0.05);
    expect(s.heading).toBeGreaterThan(MAX_HEADING * 2);
  });

  it("runs at the same difficulty-based pace as classic", () => {
    for (const difficulty of ["easy", "normal", "hard"] as const) {
      const free = makeSimulation(); free.mode = "freeroam"; free.phase = "running"; free.difficulty = difficulty; free.distance = 500;
      const classic = makeSimulation(); classic.phase = "running"; classic.difficulty = difficulty; classic.distance = 500;
      for (let i = 0; i < 20; i++) { stepSimulation(free, 0, .05); stepSimulation(classic, 0, .05); }
      expect(free.distance).toBeCloseTo(classic.distance, 6);
    }
  });

  it("falls on the third tree impact while retaining unrestricted heading", () => {
    const s = makeSimulation(); s.mode = "freeroam"; s.phase = "running"; s.heading = Math.PI;
    for (let i = 0; i < 3; i++) {
      s.x = .4; s.z = -i * 10; s.cooldown = 0;
      expect(collide(s, { x: 0, z: s.z, scale: 1, id: `f${i}` })).toBe(true);
      expect(s.phase).toBe(i === 2 ? "falling" : "running");
    }
    expect(s.hits).toBe(3);
    expect(s.heading).toBeGreaterThan(MAX_HEADING);
  });

  it("stumbles on rocks just like classic", () => {
    const s = makeSimulation(); s.mode = "freeroam"; s.phase = "running";
    expect(collideObstacle(s, { x: 0, z: 0, big: true, radius: .7, id: "rock" })).toBe(true);
    expect(s.stumble).toBe(1); expect(s.speedMul).toBe(.65);
  });

  it("freezer slows freeroam without changing steering", () => {
    const a = makeSimulation(); a.mode = "freeroam"; a.phase = "running";
    const b = makeSimulation(); b.mode = "freeroam"; b.phase = "running";
    collectPowerUp(b, orb("freezer"));
    stepSimulation(a, 1, .05); stepSimulation(b, 1, .05);
    expect(b.distance).toBeLessThan(a.distance * .6);
    expect(b.heading).toBeCloseTo(a.heading, 6);
  });

  it("hides secrets in the world that are discovered once each", () => {
    let secrets = secretsAround(0, 0);
    for (let k = 1; secrets.length === 0 && k < 20; k++) secrets = secretsAround(k * 210, 0);
    const secret = secrets[0];
    if (!secret) throw new Error("no secret generated");
    const s = makeSimulation();
    s.mode = "freeroam";
    s.phase = "running";
    s.x = secret.x; s.z = secret.z;
    expect(discoverSecret(s, secret)).toBe(true);
    expect(discoverSecret(s, secret)).toBe(false);
    expect(s.found).toEqual([secret.id]);
  });
});

describe("Earlier power-ups and fixed forest lights", () => {
  it("allows a first orb at exactly 100 m in every mode, never before", () => {
    for (const mode of ["classic", "survive", "freeroam"] as const) {
      const s = makeSimulation(); s.mode = mode; s.phase = "running";
      s.distance = 99.99; expect(canSpawnPowerUp(s, 0, false)).toBe(false);
      s.distance = 100; expect(canSpawnPowerUp(s, 0, false)).toBe(true);
      expect(canSpawnPowerUp(s, 0, true)).toBe(false);
      collectPowerUp(s, orb("ghost")); expect(canSpawnPowerUp(s, 0, false)).toBe(false);
    }
  });
  it("preserves spacing between successive orbs", () => {
    const s = makeSimulation(); s.phase = "running";
    s.distance = 219; expect(canSpawnPowerUp(s, 100, false)).toBe(false);
    s.distance = 220; expect(canSpawnPowerUp(s, 100, false)).toBe(true);
  });
  it("places orbs ahead even when running backwards", () => {
    let p: PowerUp | null = null;
    for (let i = 0; i < 20 && !p; i++) p = placePowerUp(0, 0, i, 100, Math.PI);
    if (!p) throw new Error("No orb generated");
    expect(p.z).toBeGreaterThan(28);
  });
  it("anchors light clusters to world cells, retaining shared locations across cells", () => {
    const lights = forestLightsAround(0, 0);
    expect(lights).toEqual(forestLightsAround(30, 30));
    expect(lights).toContainEqual({ x: 20, z: 42 });
    const next = forestLightsAround(85, 0);
    expect(next).toContainEqual({ x: 20, z: 42 });
    expect(next.filter(p => lights.some(q => q.x === p.x && q.z === p.z))).toHaveLength(6);
  });
  it("keeps lights fixed relative to the runner in classic and survive, world-anchored only in freeroam", () => {
    const a = fixedForestLights(0, 0, 0), b = fixedForestLights(500, -900, 0);
    a.forEach((l, i) => { expect(b[i]!.x - 500).toBeCloseTo(l.x); expect(b[i]!.z + 900).toBeCloseTo(l.z); });
    expect(a.length).toBeLessThan(forestLightsAround(0, 0).length);
    expect(a.every(l => l.z < 0)).toBe(true);
    expect(forestLightsFor("classic", 30, 30, .3)).toEqual(fixedForestLights(30, 30, .3));
    expect(forestLightsFor("survive", 30, 30, .3)).toEqual(fixedForestLights(30, 30, .3));
    expect(forestLightsFor("freeroam", 30, 30, .3)).toEqual(forestLightsAround(30, 30));
  });
});
