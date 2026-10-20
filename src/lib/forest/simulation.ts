export const CELL = 6;
export const RADIUS = 13;
export const FOG_END = 38;
export const MAX_HEADING = 1.1;
export const IMPACT_DURATION = 0.5;
export const MAX_IMPACTS = 3;
export type Phase = "menu" | "awakening" | "running" | "falling" | "fallen";
export type Tree = { x: number; z: number; scale: number; id: string };
export type Obstacle = { x: number; z: number; big: boolean; radius: number; id: string };
export type Difficulty = "easy" | "normal" | "hard";
export type Simulation = ReturnType<typeof makeSimulation>;

// Speed comes from a gentler base with a stronger ramp; density decides how
// much room the runner has to weave through. Easy is the original balance.
export const DIFFICULTIES = {
  easy: { treeGap: 0.57, accel: 100, maxSpeed: 17, obstacleGap: 0.84 },
  normal: { treeGap: 0.66, accel: 66, maxSpeed: 20, obstacleGap: 0.76 },
  hard: { treeGap: 0.75, accel: 46, maxSpeed: 23.5, obstacleGap: 0.66 },
} satisfies Record<Difficulty, { treeGap: number; accel: number; maxSpeed: number; obstacleGap: number }>;

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
  return (w1 * 1.15 + w2 * 0.55 + w3 * 0.35) * 0.62;
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
export function obstaclesAround(cx: number, cz: number, keep = 0.84): Obstacle[] {
  const obstacles: Obstacle[] = [];
  for (let i = -RADIUS; i <= RADIUS; i++) {
    for (let j = -RADIUS; j <= RADIUS; j++) {
      const gx = cx + i, gz = cz + j;
      if (hash(gx, gz, 9) <= keep) continue;
      const x = gx * CELL + hash(gx, gz, 11) * CELL;
      const z = gz * CELL + hash(gx, gz, 12) * CELL;
      if (x * x + z * z < 12 * 12) continue;
      const big = hash(gx, gz, 13) > 0.62;
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
    difficulty: "easy" as Difficulty, stumble: 0, speedMul: 1, lastObstacle: "" };
}

export function beginRun(s: Simulation) {
  const { difficulty } = s;
  Object.assign(s, makeSimulation(), { phase: "awakening", difficulty });
}

// Physical circle separation, with a small tangential impulse instead of a hard stop.
export function collide(s: Simulation, tree: Tree) {
  if (s.phase !== "running") return false;
  const dx = s.x - tree.x, dz = s.z - tree.z;
  const radius = 0.45 * tree.scale + 0.22;
  const distance = Math.hypot(dx, dz);
  if (distance >= radius) return false;
  const side = dx === 0 ? (hash(Math.floor(tree.x), Math.floor(tree.z), 7) > 0.5 ? 1 : -1) : Math.sign(dx);
  const nx = distance > 0.001 ? dx / distance : side;
  const nz = distance > 0.001 ? dz / distance : 0;
  s.x += nx * (radius - distance + 0.01);
  // Never push the runner backwards, even when contacting the front of a trunk.
  s.z += Math.min(0, nz * (radius - distance + 0.01));
  s.pushX = side * 3.5;
  s.heading = Math.max(-MAX_HEADING, Math.min(MAX_HEADING, s.heading - side * 0.14));
  if (s.cooldown > 0 || s.lastTree === tree.id) return false;
  s.hits += 1;
  s.cooldown = 0.9;
  s.lastTree = tree.id;
  s.impact = IMPACT_DURATION;
  s.impactSide = side;
  if (s.hits >= MAX_IMPACTS) { s.phase = "falling"; s.phaseTime = 0; }
  return true;
}

// Obstacles never damage or stop the runner: a heavy stumble (1 s at 65%
// speed) for big rocks, a brief drag (0.5 s at 85%) for undergrowth.
export function collideObstacle(s: Simulation, obstacle: Obstacle) {
  if (s.phase !== "running" || s.stumble > 0) return false;
  const distance = Math.hypot(s.x - obstacle.x, s.z - obstacle.z);
  if (distance >= obstacle.radius + 0.24) return false;
  s.stumble = obstacle.big ? 1 : 0.5;
  s.speedMul = obstacle.big ? 0.65 : 0.85;
  s.lastObstacle = obstacle.id;
  return true;
}

export function stepSimulation(s: Simulation, steering: number, delta: number) {
  const dt = Math.min(delta, 0.05);
  s.elapsed += dt;
  s.phaseTime += dt;
  s.impact = Math.max(0, s.impact - dt);
  s.cooldown = Math.max(0, s.cooldown - dt);
  s.stumble = Math.max(0, s.stumble - dt);
  if (s.phase === "awakening" && s.phaseTime >= 1.15) {
    s.phase = "running"; s.phaseTime = 0;
  }
  if (s.phase === "falling" && s.phaseTime >= 2.8) {
    s.phase = "fallen"; s.phaseTime = 2.8;
  }
  if (s.phase !== "running") return;
  const level = DIFFICULTIES[s.difficulty];
  const count = Math.ceil(dt / (1 / 120));
  const sub = dt / count;
  for (let i = 0; i < count; i++) {
    if (s.phase !== "running") break;
    const speed = Math.min(level.maxSpeed, 8.4 + s.distance / level.accel) * (s.stumble > 0 ? s.speedMul : 1);
    s.heading = Math.max(-MAX_HEADING, Math.min(MAX_HEADING, s.heading - steering * 1.7 * sub));
    s.pushX *= Math.exp(-7 * sub);
    s.x += (-Math.sin(s.heading) * speed + s.pushX) * sub;
    s.z -= Math.cos(s.heading) * speed * sub;
    s.distance += speed * sub;
    for (const tree of s.trees) collide(s, tree);
    for (const obstacle of s.obstacles) collideObstacle(s, obstacle);
  }
}

export function getSteering(touchMode: boolean, touch: number, tilt: number) {
  const input = touchMode ? touch : tilt;
  return Math.abs(input) < 0.06 ? 0 : Math.max(-1, Math.min(1, input));
}

export function orientationValue(gamma: number, beta: number, angle: number) {
  const raw = Math.abs(angle) === 90 ? beta * Math.sign(angle) : gamma;
  return Math.max(-1, Math.min(1, raw / 25));
}
