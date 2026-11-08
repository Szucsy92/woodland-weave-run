export const CELL = 6;
export const RADIUS = 13;
export const FOG_END = 38;
// ±37.5° → at most 75° total turning arc (tight slalom, agreed with the player).
export const MAX_HEADING = (37.5 * Math.PI) / 180;
export const IMPACT_DURATION = 0.5;
export const MAX_IMPACTS = 3;
export type PauseState = "none" | "slowing" | "paused" | "resuming" | "exiting";
// Pause is a time warp: slow to a freeze, then accelerate back from it.
export const PAUSE_SLOW = 0.7;
export const RESUME_TIME = 1.3;
export const EXIT_TIME = 0.9;
export type Phase = "menu" | "awakening" | "running" | "falling" | "fallen";
export type Tree = { x: number; z: number; scale: number; id: string };
export type Obstacle = { x: number; z: number; big: boolean; radius: number; id: string };
export type Difficulty = "easy" | "normal" | "hard";
export type GameMode = "classic" | "survive" | "freeroam";
export type PowerUpType = "freezer" | "ghost" | "shockwave";
export type PowerUp = { x: number; z: number; type: PowerUpType; id: string };
export type Simulation = ReturnType<typeof makeSimulation>;

// Lone wisps appear from 100 m, rare and one at a time.
export const POWERUP_START = 100;
export const POWERUP_DURATION = 5;
export const POWERUP_GAP = 120;
export const POWERUP_TYPES: PowerUpType[] = ["freezer", "ghost", "shockwave"];
// The shockwave sweeps a widening wedge ahead (trees included). FOG_END + 6 is the full
// horizon, where the forest is generated; the biggest wave reaches three quarters of it.
export const SHOCKWAVE_FULL = FOG_END + 6;
export const SHOCKWAVE_REACH = 0.75;
export const SHOCKWAVE_AHEAD = SHOCKWAVE_FULL * SHOCKWAVE_REACH;
export const SHOCKWAVE_SIDE = 7;
export const SHOCKWAVE_SPREAD = 1.25;
// A standing runner would see the biggest ring run out to SHOCKWAVE_AHEAD in this long.
export const SHOCKWAVE_WAVE = 1.5;
// Ring fronts always travel at this pace relative to the runner.
export const SHOCKWAVE_SPEED = SHOCKWAVE_AHEAD / SHOCKWAVE_WAVE;
// Three rings: one is sent every SHOCKWAVE_GAP seconds (all 3 fit the 5 s power-up).
export const SHOCKWAVE_GAP = 1.6;
export const SHOCKWAVE_RINGS = 3;
// How much forest each ring takes down, as a share of SHOCKWAVE_AHEAD, measured as the trees stand
// when the ring is born. The first rings only bite off the near forest, so that every later ring
// still finds a fresh band of trees to rip out; the last ring takes the full SHOCKWAVE_AHEAD.
export const SHOCKWAVE_LOOKAHEAD = [0.45, 0.72, 1];
// Each tree is shoved forward into the depth and torn out over this long.
export const SHOCKWAVE_DISSOLVE = 0.8;
// A tree is only marked torn out this shortly before its ring front reaches it, so trees that
// have not been reached yet stay in the world and can still be taken by a nearer boundary.
export const SHOCKWAVE_COMMIT = 0.35;
/**
 * How far out a ring's front runs (metres ahead of the runner) before it ends. The runner keeps
 * running into the ring, so trees meet the front earlier: a ring takes everything that stood
 * within reach * (1 + speed / SHOCKWAVE_SPEED) when it was born. Dividing that out keeps the
 * torn-out depth the same for a slow jog and for a flat-out sprint.
 */
export function shockwaveReach(ring: number, speed: number) {
  const share = SHOCKWAVE_LOOKAHEAD[Math.min(ring, SHOCKWAVE_LOOKAHEAD.length - 1)] ?? 1;
  return (SHOCKWAVE_AHEAD * share) / (1 + Math.max(0, speed) / SHOCKWAVE_SPEED);
}
export function canSpawnPowerUp(s: Simulation, lastSpawn: number, hasLiveOrb: boolean) {
  return s.phase === "running" && s.distance >= POWERUP_START && !s.powerUp && !hasLiveOrb
    && (lastSpawn === 0 || s.distance - lastSpawn >= POWERUP_GAP);
}
// Stable world cells: turning or moving inside a cell never moves its lights.
export function forestLightsAround(x: number, z: number) {
  const cx = Math.floor(x / 84), cz = Math.floor(z / 84);
  return Array.from({ length: 9 }, (_, i) => {
    const gx = cx + i % 3 - 1, gz = cz + Math.floor(i / 3) - 1;
    return { x: gx * 84 + 20, z: gz * 84 + 42 };
  });
}
// Classic and survive: one far light that is static in the world and centred on
// the runner. It keeps a constant offset from the runner's position (so it never
// gets closer or pops when the world cell changes) but ignores the heading, so
// turning swings it across the view like a real distant light. Only freeroam
// uses the world-cell lattice above.
export const FIXED_LIGHT_OFFSETS = [{ x: 0, z: -100 }];
export function fixedForestLights(x: number, z: number) {
  return FIXED_LIGHT_OFFSETS.map(o => ({ x: x + o.x, z: z + o.z }));
}
export function forestLightsFor(mode: GameMode, x: number, z: number) {
  return mode === "freeroam" ? forestLightsAround(x, z) : fixedForestLights(x, z);
}
export function placePowerUp(x: number, z: number, index: number, generation: number, heading = 0): PowerUp | null {
  if (hash(index, generation, 31) > 0.4) return null;
  const type = POWERUP_TYPES[Math.floor(hash(index, generation, 32) * 3)] ?? "freezer";
  const side = (hash(index, generation, 33) - .5) * 14;
  const ahead = 28 + hash(index, generation, 34) * 22;
  return {
    x: x + side * Math.cos(heading) - ahead * Math.sin(heading),
    z: z - side * Math.sin(heading) - ahead * Math.cos(heading),
    type,
    id: `p${index}:${generation}`,
  };
}

// Survive sequence: at 10 m the lights ahead die, the forest goes dark for a
// tense moment, then the red pursuer light and the threat sound arrive together.
export const SURVIVE_DARK_AT = 10;
export const LIGHTS_DIE = 1.2;
export const DARK_PAUSE = 1.75;
export type SurviveStage = "lit" | "dying" | "dark" | "red";
// Survive mode runs the pursuer sequence from the start of the run; freeroam
// only while a red gate has switched it on (surviveTime >= 0).
export function surviveOn(s: Simulation) {
  return s.mode === "survive" || (s.mode === "freeroam" && s.surviveTime >= 0);
}
export function surviveStage(s: Simulation): SurviveStage {
  if (!surviveOn(s) || s.surviveTime < 0) return "lit";
  if (s.surviveTime < LIGHTS_DIE) return "dying";
  if (s.surviveTime < LIGHTS_DIE + DARK_PAUSE) return "dark";
  return "red";
}

// Freeroam keeps normal running rules, with unlimited turning.
export const FREEROAM_TURN = 1.5;

// Hidden secrets for freeroam, one chance per large world cell.
export const SECRET_CELL = 70;
export const SECRET_REACH = 3.5;
export const SECRET_CLEARING = 6;
export type SecretType = "monolith" | "ring" | "lantern";
export type Secret = { x: number; z: number; type: SecretType; id: string };
export const SECRET_NAMES: Record<SecretType, string> = {
  monolith: "The Silent Monolith",
  ring: "The Fairy Ring",
  lantern: "The Lost Lantern",
};
export function secretsAround(x: number, z: number): Secret[] {
  const cx = Math.floor(x / SECRET_CELL), cz = Math.floor(z / SECRET_CELL);
  const out: Secret[] = [];
  for (let i = -1; i <= 1; i++) {
    for (let j = -1; j <= 1; j++) {
      const gx = cx + i, gz = cz + j;
      if (gx === 0 && gz === 0) continue;
      if (hash(gx, gz, 300) > 0.45) continue;
      const types: SecretType[] = ["monolith", "ring", "lantern"];
      out.push({
        x: gx * SECRET_CELL + 15 + hash(gx, gz, 301) * (SECRET_CELL - 30),
        z: gz * SECRET_CELL + 15 + hash(gx, gz, 302) * (SECRET_CELL - 30),
        type: types[Math.floor(hash(gx, gz, 303) * 3)] ?? "monolith",
        id: `s${gx}:${gz}`,
      });
    }
  }
  return out;
}
export function discoverSecret(s: Simulation, secret: Secret) {
  if (s.mode !== "freeroam" || s.phase !== "running" || s.found.includes(secret.id)) return false;
  if (Math.hypot(s.x - secret.x, s.z - secret.z) > SECRET_REACH) return false;
  s.found.push(secret.id);
  return true;
}

// Red gates for freeroam: walking through one switches the survive atmosphere
// on (lights die, dark, red pursuer, threat sound); the next gate switches it off.
export const GATE_CELL = 90;
export const GATE_HALF = 2.4;
export const GATE_DEPTH = 0.9;
export const GATE_CLEARING = 5;
export const GATE_REARM = 9;
export type Gate = { x: number; z: number; angle: number; id: string };
export function gatesAround(x: number, z: number): Gate[] {
  const cx = Math.floor(x / GATE_CELL), cz = Math.floor(z / GATE_CELL);
  const out: Gate[] = [];
  for (let i = -1; i <= 1; i++) {
    for (let j = -1; j <= 1; j++) {
      const gx = cx + i, gz = cz + j;
      if (gx === 0 && gz === 0) continue;
      if (hash(gx, gz, 500) > 0.4) continue;
      out.push({
        x: gx * GATE_CELL + 15 + hash(gx, gz, 501) * (GATE_CELL - 30),
        z: gz * GATE_CELL + 15 + hash(gx, gz, 502) * (GATE_CELL - 30),
        angle: hash(gx, gz, 503) * Math.PI,
        id: `g${gx}:${gz}`,
      });
    }
  }
  return out;
}
/** Walking through the opening of a gate toggles the survive atmosphere (freeroam only). */
export function passGate(s: Simulation, gate: Gate) {
  if (s.mode !== "freeroam" || s.phase !== "running") return false;
  const dx = s.x - gate.x, dz = s.z - gate.z;
  if (s.lastGate === gate.id) {
    // A gate fires once; it re-arms only after the runner has walked away from it.
    if (Math.hypot(dx, dz) > GATE_REARM) s.lastGate = "";
    return false;
  }
  const lateral = dx * Math.cos(gate.angle) - dz * Math.sin(gate.angle);
  const normal = dx * Math.sin(gate.angle) + dz * Math.cos(gate.angle);
  if (Math.abs(lateral) > GATE_HALF || Math.abs(normal) > GATE_DEPTH) return false;
  s.lastGate = gate.id;
  s.surviveTime = s.surviveTime >= 0 ? -1 : 0;
  return true;
}

// Black holes for freeroam: dark bodies that bend the space around them. Running into one drops the
// runner into a wormhole of warped space for WARP_DURATION seconds (chaotic drift, a forest that repeats
// the same track, but filled with impossible secrets and spores) before the runner is thrown out far away.
export const HOLE_CELL = 170;
export const HOLE_RADIUS = 2.2;
export const HOLE_HEIGHT = 2.6;
export const HOLE_CORE = 2.3;
export const HOLE_CLEARING = 10;
export const WARP_DURATION = 10;
export const WARP_FADE_IN = 0.7;
export const WARP_FADE_OUT = 1;
// Flash and shock when the runner is thrown out again.
export const WARP_EXIT_TIME = 1.4;
export type BlackHole = { x: number; z: number; id: string };
export function holesAround(x: number, z: number): BlackHole[] {
  const cx = Math.floor(x / HOLE_CELL), cz = Math.floor(z / HOLE_CELL);
  const out: BlackHole[] = [];
  for (let i = -1; i <= 1; i++) {
    for (let j = -1; j <= 1; j++) {
      const gx = cx + i, gz = cz + j;
      if (gx === 0 && gz === 0) continue;
      if (hash(gx, gz, 700) > 0.5) continue;
      out.push({
        x: gx * HOLE_CELL + 25 + hash(gx, gz, 701) * (HOLE_CELL - 50),
        z: gz * HOLE_CELL + 25 + hash(gx, gz, 702) * (HOLE_CELL - 50),
        id: `h${gx}:${gz}`,
      });
    }
  }
  return out;
}
/** Where a hole throws the runner out: far away, in a direction that belongs to that hole. */
export function holeExit(hole: BlackHole) {
  const angle = hash(Math.floor(hole.x), Math.floor(hole.z), 703) * Math.PI * 2;
  const distance = 170 + hash(Math.floor(hole.x), Math.floor(hole.z), 704) * 110;
  return { x: hole.x + Math.cos(angle) * distance, z: hole.z + Math.sin(angle) * distance };
}
/** 0..1 strength of the wormhole: eases in after the fall, holds, eases out before the exit. */
export function warpEnvelope(s: Simulation) {
  if (s.warp < 0 || s.warp >= WARP_DURATION) return 0;
  const k = (t: number) => t * t * (3 - 2 * t);
  return k(Math.min(1, s.warp / WARP_FADE_IN)) * k(Math.min(1, (WARP_DURATION - s.warp) / WARP_FADE_OUT));
}
/** Running into the dark body drops the runner into the wormhole (freeroam only). */
export function enterHole(s: Simulation, hole: BlackHole) {
  if (s.mode !== "freeroam" || s.phase !== "running" || s.warp >= 0) return false;
  if (Math.hypot(s.x - hole.x, s.z - hole.z) > HOLE_CORE) return false;
  s.warp = 0;
  s.warpHole = hole.id;
  s.warpFrom = { x: hole.x, z: hole.z };
  s.warpOut = -1;
  s.cell = "";
  return true;
}
/** Ends the wormhole: the runner is thrown out far away, heading unchanged. */
export function exitWarp(s: Simulation) {
  const exit = holeExit({ x: s.warpFrom.x, z: s.warpFrom.z, id: s.warpHole });
  s.x = exit.x; s.z = exit.z;
  s.warp = -1;
  s.warpOut = 0;
  s.stumble = 0; s.speedMul = 1;
  s.pushX = 0;
  s.cell = "";
}

// Speed comes from a gentler base with a stronger ramp; density decides how
// much room the runner has to weave through. Easy is the original balance.
export const DIFFICULTIES = {
  easy: { treeGap: 0.57, accel: 100, maxSpeed: 17, obstacleGap: 0.84 },
  normal: { treeGap: 0.66, accel: 66, maxSpeed: 20, obstacleGap: 0.76 },
  hard: { treeGap: 0.75, accel: 46, maxSpeed: 23.5, obstacleGap: 0.66 },
} satisfies Record<Difficulty, { treeGap: number; accel: number; maxSpeed: number; obstacleGap: number }>;

// Every run starts at this pace, and the head movement covers one stride per
// fixed distance: quicken and the rhythm quickens with you. The cap keeps the
// fastest pace calm rather than frantic.
export const BASE_SPEED = 8.4;
export const STRIDE_RATE = 11;
export const STRIDE_MAX_MUL = 1.4;
export function strideMultiplier(speed: number) {
  return Math.min(STRIDE_MAX_MUL, Math.max(0.5, speed / BASE_SPEED));
}

export function hash(x: number, z: number, salt = 0) {
  let h = (x * 374761393 + z * 668265263 + salt * 982451653) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967295;
}

// Layered irrational-frequency waves: never repeats within a run, but the same
// formula runs on both CPU (physics, camera, object placement) and GPU
// (displaced ground), so visuals and movement always agree.
export function groundHeight(x: number, z: number) {
  const w1 = Math.sin(x * 0.11 + Math.sin(z * 0.07) * 2.4) * Math.cos(z * 0.09 + Math.sin(x * 0.05) * 2.1);
  const w2 = Math.sin(z * 0.23 - Math.sin(x * 0.16) * 1.8) * Math.cos(x * 0.19 + Math.sin(z * 0.12) * 1.4);
  const w3 = Math.sin((x + z) * 0.47 + Math.sin(x * 0.3) * 1.2);
  return (w1 * 1.15 + w2 * 0.55 + w3 * 0.35) * 0.3;
}

// Fixed world positions on both sides and across depth, including beside the
// runner. Only particles well behind or outside the local forest are recycled.
export function placeSpore(x: number, z: number, index: number, generation = 0, initial = false) {
  return {
    x: x + (hash(index, generation, 6) - .5) * 48,
    z: z - (initial ? -12 + hash(index, generation, 5) * 70 : 8 + hash(index, generation, 5) * 50),
    h: .7 + hash(index, generation, 7) * 3.5,
  };
}

export function sporeOpacity(distance: number, twinkle: number) {
  const depth = Math.max(0, 1 - distance / 70);
  const lamp = Math.max(0, 1 - distance / 18);
  // No near-face disappearance: passing particles retain their soft glow.
  return (.32 + twinkle * .1 + lamp * .12) * depth;
}

export function treesAround(cx: number, cz: number, keep = 0.57): Tree[] {
  const trees: Tree[] = [];
  for (let i = -RADIUS; i <= RADIUS; i++) {
    for (let j = -RADIUS; j <= RADIUS; j++) {
      const gx = cx + i, gz = cz + j;
      if (hash(gx, gz) > keep) continue;
      const x = gx * CELL + hash(gx, gz, 1) * CELL;
      const z = gz * CELL + hash(gx, gz, 2) * CELL;
      if (x * x + z * z < 12 * 12) continue;
      trees.push({ x, z, scale: 0.75 + hash(gx, gz, 3) * 0.9, id: `${gx}:${gz}` });
    }
  }
  return trees;
}

// Rocks are big enough to trip over (a real stumble, not damage); undergrowth
// only drags the pace for a moment.
export const OBSTACLE_DENSITY = 0.5;
export function obstaclesAround(cx: number, cz: number, keep = 0.84): Obstacle[] {
  const obstacles: Obstacle[] = [];
  for (let i = -RADIUS; i <= RADIUS; i++) {
    for (let j = -RADIUS; j <= RADIUS; j++) {
      const gx = cx + i, gz = cz + j;
      if (hash(gx, gz, 9) <= keep) continue;
      // Independent thinning preserves the positions and bush/rock balance.
      if (hash(gx, gz, 15) >= OBSTACLE_DENSITY) continue;
      const x = gx * CELL + hash(gx, gz, 11) * CELL;
      const z = gz * CELL + hash(gx, gz, 12) * CELL;
      if (x * x + z * z < 12 * 12) continue;
      const big = hash(gx, gz, 13) > 0.86;
      const radius = big ? 0.5 + hash(gx, gz, 14) * 0.45 : 0.22 + hash(gx, gz, 14) * 0.15;
      obstacles.push({ x, z, big, radius, id: `o${gx}:${gz}` });
    }
  }
  return obstacles;
}

export function makeSimulation() {
  return { x: 0, z: 0, heading: 0, distance: 0, elapsed: 0, phaseTime: 0,
    phase: "menu" as Phase, hits: 0, cooldown: 0, impact: 0, impactSide: 1,
    pushX: 0, lastTree: "", cell: "", trees: [] as Tree[], obstacles: [] as Obstacle[],
    difficulty: "easy" as Difficulty, stumble: 0, speedMul: 1, lastObstacle: "", stride: 0,
    pause: "none" as PauseState, pauseTime: 0,
    mode: "classic" as GameMode, powerUp: null as PowerUpType | null, powerUpTime: 0, waveSpeed: BASE_SPEED,
    threat: 0, cleared: [] as string[], clearedAt: {} as Record<string, number>,
    surviveTime: -1, lastGate: "", found: [] as string[],
    warp: -1, warpOut: -1, warpHole: "", warpFrom: { x: 0, z: 0 } };
}

export function beginRun(s: Simulation) {
  const { difficulty, mode } = s;
  Object.assign(s, makeSimulation(), { phase: "awakening", difficulty, mode });
}

// Physical circle separation, with a small tangential impulse instead of a hard stop.
export function collide(s: Simulation, tree: Tree) {
  if (s.phase !== "running") return false;
  // Ghost wisps let the runner pass straight through the trunks unharmed.
  if (s.powerUp === "ghost" || s.warp >= 0) return false;
  // Trees the shockwave dissolved are gone for good.
  if (s.cleared.includes(tree.id)) return false;
  const dx = s.x - tree.x, dz = s.z - tree.z;
  const radius = 0.45 * tree.scale + 0.22;
  const distance = Math.hypot(dx, dz);
  if (distance >= radius) return false;
  const side = dx === 0 ? (hash(Math.floor(tree.x), Math.floor(tree.z), 7) > 0.5 ? 1 : -1) : Math.sign(dx);
  const nz = distance > 0.001 ? dz / distance : 0;
  if (nz > 0) {
    // Front contact: never push backwards, so throw the runner fully aside
    // to the trunk's edge at the current depth — it can never pass through.
    s.x = tree.x + side * (Math.sqrt(Math.max(0, radius * radius - dz * dz)) + 0.01);
  } else {
    const nx = distance > 0.001 ? dx / distance : side;
    s.x += nx * (radius - distance + 0.01);
    s.z += nz * (radius - distance + 0.01);
  }
  s.pushX = side * 3.5;
  s.heading = s.mode === "freeroam" ? s.heading - side * 0.14
    : Math.max(-MAX_HEADING, Math.min(MAX_HEADING, s.heading - side * 0.14));
  if (s.cooldown > 0 || s.lastTree === tree.id) return false;
  s.hits += 1;
  s.cooldown = 0.9;
  s.lastTree = tree.id;
  s.impact = IMPACT_DURATION;
  s.impactSide = side;
  if (s.hits >= MAX_IMPACTS) { s.phase = "falling"; s.phaseTime = 0; }
  // In survive mode every impact means the pursuer crept closer.
  if (surviveOn(s)) s.threat = 1;
  return true;
}

// Obstacles never damage or stop the runner: a heavy stumble (1 s at 65%
// speed) for big rocks, a brief drag (0.5 s at 85%) for undergrowth.
export function collideObstacle(s: Simulation, obstacle: Obstacle) {
  if (s.phase !== "running" || s.stumble > 0) return false;
  if (s.powerUp === "ghost" || s.warp >= 0) return false;
  // The shockwave flattened this patch of undergrowth earlier in the run.
  if (s.cleared.includes(obstacle.id)) return false;
  const distance = Math.hypot(s.x - obstacle.x, s.z - obstacle.z);
  if (distance >= obstacle.radius + 0.24) return false;
  s.stumble = obstacle.big ? 1 : 0.5;
  s.speedMul = obstacle.big ? 0.65 : 0.85;
  s.lastObstacle = obstacle.id;
  if (surviveOn(s)) s.threat = 1;
  return true;
}

/** The pace the runner holds without stumbles or the freezer: it sets how far the waves reach. */
export function runSpeed(s: Simulation) {
  const level = DIFFICULTIES[s.difficulty];
  return Math.min(level.maxSpeed, BASE_SPEED + s.distance / level.accel);
}

/** True when a point lies in the widening wedge ahead of the runner. */
export function inShockwave(s: Simulation, x: number, z: number) {
  const dx = x - s.x, dz = z - s.z;
  const along = -dx * Math.sin(s.heading) - dz * Math.cos(s.heading);
  const lateral = dx * Math.cos(s.heading) - dz * Math.sin(s.heading);
  return along > -1 && along < SHOCKWAVE_FULL && Math.abs(lateral) < SHOCKWAVE_SIDE + Math.max(0, along) * SHOCKWAVE_SPREAD;
}

/**
 * When a thing standing `along` metres ahead gets torn out, in power-up time, or null if no ring
 * can take it yet. A ring front runs out at a steady pace while the thing rushes towards the
 * runner, so they meet where front = depth - speed * time. Whatever lies inside the zone the
 * waves already swept (the end of the last finished ring, or the running front) is torn out at once:
 * trees keep ripping out right in front of the runner until the next, bigger ring arrives.
 */
export function shockwaveHit(s: Simulation, along: number): { at: number; boundary: boolean } | null {
  const v = s.waveSpeed, now = s.powerUp === "shockwave" ? s.powerUpTime : 0;
  let best = Infinity, swept = 0;
  for (let i = 0; i < SHOCKWAVE_RINGS; i++) {
    const born = i * SHOCKWAVE_GAP, reach = shockwaveReach(i, v), end = born + reach / SHOCKWAVE_SPEED;
    if (now >= end) { swept = Math.max(swept, reach); continue; }
    if (now > born) swept = Math.max(swept, SHOCKWAVE_SPEED * (now - born));
    const meet = (along + v * now + SHOCKWAVE_SPEED * born) / (SHOCKWAVE_SPEED + v);
    if (meet >= Math.max(now, born) - 1e-9 && meet <= end) best = Math.min(best, meet);
  }
  if (along <= swept + 1e-9) return { at: now, boundary: true };
  return Number.isFinite(best) ? { at: best, boundary: false } : null;
}

/** Marks what a ring front is about to reach as torn out; each piece starts when the front gets there. */
export function sweepShockwave(s: Simulation) {
  const now = s.powerUp === "shockwave" ? s.powerUpTime : 0;
  for (const o of [...s.obstacles, ...s.trees]) {
    if (s.cleared.includes(o.id) || !inShockwave(s, o.x, o.z)) continue;
    const along = -(o.x - s.x) * Math.sin(s.heading) - (o.z - s.z) * Math.cos(s.heading);
    const hit = shockwaveHit(s, along);
    if (!hit || hit.at - now > SHOCKWAVE_COMMIT) continue;
    // No two trunks give way in the same instant: a small spread in time, wider along the standing edge.
    const spread = hash(Math.floor(o.x * 7), Math.floor(o.z * 7), 31) * (hit.boundary ? 0.25 : 0.08);
    s.cleared.push(o.id);
    s.clearedAt[o.id] = s.elapsed + Math.max(0, hit.at - now) + spread;
  }
}

// Running into a wisp binds its power — never while another power is active.
export function collectPowerUp(s: Simulation, p: PowerUp) {
  if (s.phase !== "running" || s.powerUp) return false;
  if (Math.hypot(s.x - p.x, s.z - p.z) > 1.1) return false;
  s.powerUp = p.type;
  s.powerUpTime = 0;
  s.waveSpeed = runSpeed(s);
  if (p.type === "shockwave") sweepShockwave(s);
  return true;
}

export function requestPause(s: Simulation) {
  if (s.phase !== "running" && s.phase !== "awakening") return false;
  if (s.pause !== "none" && s.pause !== "resuming") return false;
  s.pause = "slowing"; s.pauseTime = 0;
  return true;
}
export function requestResume(s: Simulation) {
  if (s.pause !== "slowing" && s.pause !== "paused") return false;
  s.pause = "resuming"; s.pauseTime = 0;
  return true;
}
export function requestExit(s: Simulation) {
  if (s.pause === "none" || s.pause === "exiting") return false;
  s.pause = "exiting"; s.pauseTime = 0;
  return true;
}

const ease = (x: number) => x * x * (3 - 2 * x);
/** World time multiplier: 1 normal, 0 frozen. */
export function timeScale(s: Simulation) {
  const p = s.pauseTime;
  switch (s.pause) {
    case "slowing": return 1 - ease(Math.min(1, p / PAUSE_SLOW));
    case "resuming": return Math.pow(Math.min(1, p / RESUME_TIME), 2);
    case "none": return 1;
    default: return 0;
  }
}

export function stepSimulation(s: Simulation, steering: number, delta: number) {
  const real = Math.min(delta, 0.05);
  if (s.pause !== "none") {
    s.pauseTime += real;
    if (s.pause === "slowing" && s.pauseTime >= PAUSE_SLOW) { s.pause = "paused"; s.pauseTime = 0; }
    else if (s.pause === "resuming" && s.pauseTime >= RESUME_TIME) { s.pause = "none"; s.pauseTime = 0; }
    else if (s.pause === "exiting" && s.pauseTime >= EXIT_TIME) {
      const { difficulty, elapsed, mode } = s;
      Object.assign(s, makeSimulation(), { difficulty, elapsed, mode, phase: "menu" });
      return;
    }
  }
  const dt = real * timeScale(s);
  s.elapsed += dt;
  s.phaseTime += dt;
  s.impact = Math.max(0, s.impact - dt);
  s.cooldown = Math.max(0, s.cooldown - dt);
  s.stumble = Math.max(0, s.stumble - dt);
  if (s.powerUp) {
    s.powerUpTime += dt;
    if (s.powerUpTime >= POWERUP_DURATION) { s.powerUp = null; s.powerUpTime = 0; }
  }
  // The wormhole runs its course, then throws the runner out; the exit flash settles afterwards.
  if (s.warp >= 0 && s.phase === "running") {
    s.warp += dt;
    if (s.warp >= WARP_DURATION) exitWarp(s);
  }
  if (s.warpOut >= 0) {
    s.warpOut += dt;
    if (s.warpOut >= WARP_EXIT_TIME) s.warpOut = -1;
  }
  // The pursuer's glow flares for about a second after every mistake, and
  // burns constantly once the runner is down.
  if (surviveOn(s) && (s.phase === "falling" || s.phase === "fallen")) s.threat = 1;
  else s.threat = Math.max(0, s.threat - dt);
  if (s.phase === "awakening" && s.phaseTime >= 1.15) {
    s.phase = "running"; s.phaseTime = 0;
  }
  if (s.phase === "falling" && s.phaseTime >= 2.8) {
    s.phase = "fallen"; s.phaseTime = 2.8;
  }
  // Survive clock: starts when the lights begin to die, then keeps running.
  if (s.mode === "survive") {
    if (s.surviveTime >= 0) s.surviveTime += dt;
    else if (s.phase === "running" && s.distance > SURVIVE_DARK_AT) s.surviveTime = 0;
  } else if (s.mode === "freeroam" && s.surviveTime >= 0) {
    s.surviveTime += dt;
  }
  if (s.phase !== "running") return;
  const level = DIFFICULTIES[s.difficulty];
  const free = s.mode === "freeroam";
  const count = Math.ceil(dt / (1 / 120));
  const sub = dt / count;
  // The waves keep rolling for the whole power-up: whatever comes into reach is torn out too.
  if (s.powerUp === "shockwave") sweepShockwave(s);
  for (let i = 0; i < count; i++) {
    if (s.phase !== "running") break;
    // The freezer slows the world's rush while steering stays fully responsive.
    // Inside the wormhole the pace surges and the heading drifts on its own: nothing holds still.
    const chaos = warpEnvelope(s);
    const speed = Math.min(level.maxSpeed, BASE_SPEED + s.distance / level.accel)
      * (s.stumble > 0 ? s.speedMul : 1) * (s.powerUp === "freezer" ? 0.55 : 1)
      * (1 + chaos * 0.2 * Math.sin(s.warp * 3.1));
    if (chaos > 0) s.heading += chaos * (Math.sin(s.warp * 2.1) * Math.cos(s.warp * 0.73 + 1.3) * 0.7 + Math.sin(s.warp * 5.3) * 0.25) * sub;
    // Freeroam turns without limit, so the walker can head any way, even back.
    s.heading = free ? s.heading - steering * FREEROAM_TURN * sub
      : Math.max(-MAX_HEADING, Math.min(MAX_HEADING, s.heading - steering * 1.7 * sub));
    s.pushX *= Math.exp(-7 * sub);
    s.x += (-Math.sin(s.heading) * speed + s.pushX) * sub;
    s.z -= Math.cos(s.heading) * speed * sub;
    s.distance += speed * sub;
    s.stride += strideMultiplier(speed) * STRIDE_RATE * sub;
    for (const tree of s.trees) collide(s, tree);
    for (const obstacle of s.obstacles) collideObstacle(s, obstacle);
  }
  // Running into a black hole's dark body drops the runner into the wormhole.
  if (free && s.warp < 0 && s.phase === "running") {
    for (const hole of holesAround(s.x, s.z)) if (enterHole(s, hole)) break;
  }
}

export function getSteering(touchMode: boolean, touch: number, tilt: number) {
  const input = touchMode ? touch : tilt;
  return Math.abs(input) < 0.06 ? 0 : Math.max(-1, Math.min(1, input));
}

// Tilt is read from the gravity component along the screen's horizontal axis.
// Raw beta/gamma flip by 180° when the phone passes upright (gimbal flip), which
// made steering jump to the opposite side or stick; this value stays continuous.
export function orientationValue(gamma: number, beta: number, angle: number) {
  const b = (beta * Math.PI) / 180, g = (gamma * Math.PI) / 180;
  const gx = Math.cos(b) * Math.sin(g), gy = Math.sin(b);
  const a = ((Math.round(angle / 90) * 90) % 360 + 360) % 360;
  const sx = a === 90 ? gy : a === 270 ? -gy : a === 180 ? -gx : gx;
  const deg = (Math.asin(Math.max(-1, Math.min(1, sx))) * 180) / Math.PI;
  return Math.max(-1, Math.min(1, deg / 25));
}

// Tilt calibration. The neutral (zero) tilt is captured once the phone is held
// steady, and again whenever the screen rotates, the app resumes, or the value
// has been pegged far from neutral for too long. This keeps steering from
// sticking when the phone is turned after the run has already started.
export const TILT_SETTLE_MS = 500;   // ignore input while the phone is still being turned
export const TILT_SAMPLES = 6;       // steady samples averaged into the neutral
export const TILT_STEADY = 0.08;     // max spread (~2°) of a steady sample window
export const TILT_FORCE_MS = 1500;   // accept the average anyway after this long
export const TILT_PEG = 1.2;         // offset (~30°) that counts as "stuck"
export const TILT_PEG_MS = 1200;     // ...when held this long
export type TiltCal = {
  neutral: number | null; angle: number | null; settleUntil: number;
  sum: number; count: number; min: number; max: number; since: number; pegSince: number;
};
export function makeTiltCal(): TiltCal {
  return { neutral: null, angle: null, settleUntil: 0, sum: 0, count: 0, min: 0, max: 0, since: 0, pegSince: 0 };
}
/** Throw the neutral away; steering reads 0 until the phone is steady again. */
export function resetTilt(c: TiltCal, now: number, settleMs = TILT_SETTLE_MS) {
  c.neutral = null; c.settleUntil = now + settleMs;
  c.sum = 0; c.count = 0; c.since = now + settleMs; c.pegSince = 0;
}
/** Feed one orientation sample (raw from orientationValue); returns steering tilt -1..1. */
export function updateTilt(c: TiltCal, raw: number, angle: number, now: number) {
  if (c.angle !== angle) { c.angle = angle; resetTilt(c, now); }
  if (now < c.settleUntil) return 0;
  if (c.neutral == null) {
    if (c.count > 0 && Math.max(c.max, raw) - Math.min(c.min, raw) > TILT_STEADY && now - c.since < TILT_FORCE_MS) {
      c.sum = 0; c.count = 0; // still moving: start the window again
    }
    if (c.count === 0) { c.min = raw; c.max = raw; } else { c.min = Math.min(c.min, raw); c.max = Math.max(c.max, raw); }
    c.sum += raw; c.count++;
    if (c.count >= TILT_SAMPLES) { c.neutral = c.sum / c.count; c.pegSince = 0; }
    return 0;
  }
  const off = raw - c.neutral;
  if (Math.abs(off) > TILT_PEG) {
    if (c.pegSince === 0) c.pegSince = now;
    else if (now - c.pegSince > TILT_PEG_MS) { resetTilt(c, now, 250); return 0; }
  } else c.pegSince = 0;
  return Math.max(-1, Math.min(1, off));
}

export type ForestMood = "night" | "day" | "auto";
/** Daylight amount 0 (night) .. 1 (misty day). Auto follows local time with slow dawn 5-8h and dusk 17-20h. */
export function daylightFor(mood: ForestMood, hours: number) {
  if (mood === "night") return 0;
  if (mood === "day") return 1;
  const h = ((hours % 24) + 24) % 24;
  const ramp = (a: number, b: number) => Math.min(1, Math.max(0, (h - a) / (b - a)));
  const s = (x: number) => x * x * (3 - 2 * x);
  return s(ramp(5, 8)) * (1 - s(ramp(17, 20)));
}
