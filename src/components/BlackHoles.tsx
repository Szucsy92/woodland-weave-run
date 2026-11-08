import { useEffect, useMemo, useRef, type MutableRefObject } from "react";
import { useFrame } from "@react-three/fiber";
import * as THREE from "three";
import { groundHeight, holesAround, HOLE_HEIGHT, HOLE_RADIUS, HOLE_SEE, universeTime, warpEnvelope, type Simulation } from "@/lib/forest/simulation";

type HolePalette = Record<"secret" | "rock" | "purple" | "lavender" | "green" | "lamp" | "shock", THREE.Color>;

const HOLE_SLOTS = 6;
// Impossible secrets that fill the space while the wormhole is open.
const SWARM = 48;
// Side of the wrapped square the secrets and spores live in (wider than the fog, so a wrap is never seen).
const WINDOW = 100;
const KINDS = ["monolith", "ring", "lantern", "spheres", "totem", "gate"] as const;
// Odd, saturated colours the wormhole paints its secrets and spores with.
const ODD = ["#ff6bd6", "#6bffe1", "#ffd36b", "#9b8cff", "#8dff6b", "#ff7a6b", "#6bb8ff", "#e2ff6b"];

// Remnants: what the wormholes leave behind in the universe they open into, arriving piece by piece.
const REMNANTS = 26;
const REMNANT_SPAN = 90; // seconds over which a universe's remnants drift in
const smooth = (x: number) => { const k = Math.min(1, Math.max(0, x)); return k * k * (3 - 2 * k); };

type SporeCounts = { rain: number; sheets: number; rings: number };
const WORMHOLE_SPORES: SporeCounts = { rain: 260, sheets: 3, rings: 5 };
const REMNANT_SPORES: SporeCounts = { rain: 70, sheets: 1, rings: 2 };
const SHEET_N = 11, RING_N = 56;
const sporeTotal = (c: SporeCounts) => c.rain + c.sheets * SHEET_N * SHEET_N + c.rings * RING_N;

// Cheap deterministic noise: every item keeps its own character.
const rnd = (i: number, k: number) => {
  const v = Math.sin(i * 127.1 + k * 311.7) * 43758.5453;
  return v - Math.floor(v);
};
/** Keeps an anchor in the square window around the centre, so a fixed world place is recycled unseen. */
const wrap = (anchor: number, centre: number) => centre + ((((anchor - centre) % WINDOW) + WINDOW * 1.5) % WINDOW) - WINDOW / 2;
/** Height of a spore that drops from H and bounces on the ground, each bounce lower; u runs 0..1. */
const bounce = (u: number, H: number) => {
  if (u < .45) { const k = u / .45; return H * (1 - k * k); }
  if (u < .75) { const k = (u - .45) / .3; return H * .35 * 4 * k * (1 - k); }
  if (u < .92) { const k = (u - .75) / .17; return H * .12 * 4 * k * (1 - k); }
  const k = (u - .92) / .08; return H * .04 * 4 * k * (1 - k);
};

function glowTexture() {
  const canvas = document.createElement("canvas");
  canvas.width = canvas.height = 64;
  const g = canvas.getContext("2d");
  if (g) {
    const grad = g.createRadialGradient(32, 32, 0, 32, 32, 32);
    grad.addColorStop(0, "rgba(255,255,255,1)");
    grad.addColorStop(0.35, "rgba(255,255,255,.35)");
    grad.addColorStop(1, "rgba(255,255,255,0)");
    g.fillStyle = grad;
    g.fillRect(0, 0, 64, 64);
  }
  return new THREE.CanvasTexture(canvas);
}

type Spores = { points: THREE.Points; geometry: THREE.BufferGeometry; material: THREE.PointsMaterial; base: Float32Array };
/** Gives every spore a colour: some formations one strange colour, others every colour at once. */
function paintSpores(sp: Spores, seed: number) {
  const col = new THREE.Color();
  const n = sp.base.length / 3;
  for (let i = 0; i < n; i++) {
    const group = Math.floor(i / 40);
    const mono = rnd(group + seed * 17, 41) < .45;
    col.set(ODD[Math.floor(rnd(mono ? group + seed * 17 : i + seed * 1000, 42) * ODD.length)] ?? "#ffffff");
    sp.base.set([col.r, col.g, col.b], i * 3);
  }
}
function makeSpores(glow: THREE.Texture, counts: SporeCounts, size: number): Spores {
  const total = sporeTotal(counts);
  const geometry = new THREE.BufferGeometry();
  geometry.setAttribute("position", new THREE.BufferAttribute(new Float32Array(total * 3), 3));
  geometry.setAttribute("color", new THREE.BufferAttribute(new Float32Array(total * 3), 3));
  const material = new THREE.PointsMaterial({ map: glow, size, sizeAttenuation: true, vertexColors: true, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending });
  const points = new THREE.Points(geometry, material);
  points.frustumCulled = false;
  points.visible = false;
  const sp: Spores = { points, geometry, material, base: new Float32Array(total * 3) };
  paintSpores(sp, 0);
  return sp;
}

/** One secret of the wormhole: the kind and colours follow from its index. */
function Relic({ i, palette, glow }: { i: number; palette: HolePalette; glow: THREE.Texture }) {
  const kind = KINDS[i % KINDS.length]!;
  const hue = ODD[(i * 3 + Math.floor(i / KINDS.length)) % ODD.length]!;
  const alt = ODD[(i * 5 + 2) % ODD.length]!;
  return (
          <group>
    {kind === "monolith" && <>
      <mesh>
        <boxGeometry args={[1, 4.4, .35]} />
        <meshStandardMaterial color={palette.rock} roughness={.35} metalness={.2} emissive={hue} emissiveIntensity={.35} />
      </mesh>
      <mesh position={[.07, .1, .2]}>
        <boxGeometry args={[.06, 3.2, .02]} />
        <meshBasicMaterial color={hue} />
      </mesh>
    </>}
    {kind === "ring" && <>
      <mesh>
        <torusGeometry args={[2.4, .14, 8, 40]} />
        <meshBasicMaterial color={hue} />
      </mesh>
      <mesh rotation-x={Math.PI / 2}>
        <torusGeometry args={[1.5, .08, 6, 28]} />
        <meshBasicMaterial color={alt} />
      </mesh>
    </>}
    {kind === "lantern" && <>
      <mesh position={[0, -.8, 0]}>
        <cylinderGeometry args={[.05, .07, 1.6, 6]} />
        <meshStandardMaterial color={palette.rock} roughness={.9} />
      </mesh>
      <mesh position={[.06, .1, 0]}>
        <boxGeometry args={[.24, .3, .24]} />
        <meshBasicMaterial color={hue} />
      </mesh>
    </>}
    {kind === "spheres" && <>
      {/* A circle of glowing spheres, each its own colour. */}
      {Array.from({ length: 12 }, (_, m) => {
        const a = (m / 12) * Math.PI * 2;
        return (
          <mesh key={m} position={[Math.cos(a) * 2.4, 0, Math.sin(a) * 2.4]}>
            <sphereGeometry args={[.26, 10, 8]} />
            <meshBasicMaterial color={ODD[(m + i) % ODD.length]!} />
          </mesh>
        );
      })}
      {Array.from({ length: 6 }, (_, m) => {
        const a = (m / 6) * Math.PI * 2 + .3;
        return (
          <mesh key={m} position={[Math.cos(a) * 1.2, 0, Math.sin(a) * 1.2]}>
            <sphereGeometry args={[.16, 8, 6]} />
            <meshBasicMaterial color={ODD[(m * 2 + i + 3) % ODD.length]!} />
          </mesh>
        );
      })}
    </>}
    {kind === "totem" && <>
      {/* A stack of odd shapes on a pole, with a glowing crown. */}
      <mesh>
        <cylinderGeometry args={[.12, .16, 6, 6]} />
        <meshStandardMaterial color={palette.rock} roughness={.5} metalness={.2} />
      </mesh>
      <mesh position={[0, -2, 0]}>
        <boxGeometry args={[.9, .7, .9]} />
        <meshStandardMaterial color={palette.rock} emissive={hue} emissiveIntensity={.5} roughness={.4} />
      </mesh>
      <mesh position={[0, -.7, 0]}>
        <sphereGeometry args={[.6, 12, 10]} />
        <meshBasicMaterial color={alt} />
      </mesh>
      <mesh position={[0, .6, 0]} rotation-x={Math.PI / 2}>
        <torusGeometry args={[.8, .1, 6, 24]} />
        <meshBasicMaterial color={hue} />
      </mesh>
      <mesh position={[0, 1.8, 0]}>
        <coneGeometry args={[.55, 1.2, 5]} />
        <meshStandardMaterial color={palette.rock} emissive={alt} emissiveIntensity={.5} roughness={.4} />
      </mesh>
      <mesh position={[0, 3.3, 0]}>
        <icosahedronGeometry args={[.4, 0]} />
        <meshBasicMaterial color={hue} />
      </mesh>
    </>}
    {kind === "gate" && <>
      {/* A gate that opens onto nothing: two posts, a lintel and a faint veil. */}
      {[-1, 1].map(side => (
        <mesh key={side} position={[side * 2.2, 0, 0]}>
          <boxGeometry args={[.4, 4.6, .4]} />
          <meshStandardMaterial color={palette.rock} roughness={.4} metalness={.2} emissive={hue} emissiveIntensity={.9} />
        </mesh>
      ))}
      <mesh position={[0, 2.4, 0]}>
        <boxGeometry args={[5.2, .4, .4]} />
        <meshStandardMaterial color={palette.rock} roughness={.4} metalness={.2} emissive={hue} emissiveIntensity={.9} />
      </mesh>
      <mesh>
        <planeGeometry args={[4.4, 4.4]} />
        <meshBasicMaterial color={alt} transparent opacity={.16} blending={THREE.AdditiveBlending} depthWrite={false} side={THREE.DoubleSide} />
      </mesh>
    </>}
    <sprite scale={[kind === "lantern" ? 3.2 : 7, kind === "monolith" || kind === "totem" ? 8 : kind === "lantern" ? 3.2 : 7, 1]}>
      <spriteMaterial map={glow} color={hue} transparent opacity={.38} blending={THREE.AdditiveBlending} depthWrite={false} fog={false} />
    </sprite>
  </group>
  );
}

/** A soft, glowing ring profile with faint uneven streaks: no hard edge anywhere. */
function ringTexture() {
  const size = 256, canvas = document.createElement("canvas");
  canvas.width = canvas.height = size;
  const g = canvas.getContext("2d");
  if (g) {
    const c = size / 2;
    const grad = g.createRadialGradient(c, c, 0, c, c, c);
    grad.addColorStop(0, "rgba(255,255,255,0)");
    grad.addColorStop(.4, "rgba(255,255,255,0)");
    grad.addColorStop(.66, "rgba(255,255,255,.55)");
    grad.addColorStop(.8, "rgba(255,255,255,.22)");
    grad.addColorStop(1, "rgba(255,255,255,0)");
    g.fillStyle = grad;
    g.fillRect(0, 0, size, size);
    // Uneven, hazy streaks so the turning is readable without any visible line.
    g.globalCompositeOperation = "destination-out";
    for (let k = 0; k < 14; k++) {
      const a0 = rnd(k, 61) * Math.PI * 2, len = .3 + rnd(k, 62) * .9;
      g.strokeStyle = `rgba(0,0,0,${.12 + rnd(k, 63) * .2})`;
      g.lineWidth = 14 + rnd(k, 64) * 30;
      g.beginPath();
      g.arc(c, c, c * (.55 + rnd(k, 65) * .3), a0, a0 + len);
      g.stroke();
    }
  }
  return new THREE.CanvasTexture(canvas);
}

/**
 * Freeroam black holes and the wormhole they open. Each hole is a pure black body with a tilted,
 * turning accretion disk, a halo that shows through the fog and a few orbiting sparks; the screen-space
 * bending of the space around it is drawn by ForestEffects. While the wormhole is open the forest stays
 * exactly as it was, but the secrets (sphere rings, totems, gates, slabs, lanterns) stand everywhere in
 * poses no real place could hold, and multicoloured spores ripple in formations, fall and bounce.
 */
export function BlackHoles({ simulation, palette }: { simulation: MutableRefObject<Simulation>; palette: HolePalette }) {
  const holes = useRef<THREE.Group>(null);
  const swarm = useRef<THREE.Group>(null);
  const remnants = useRef<THREE.Group>(null);
  const glow = useMemo(() => glowTexture(), []);
  const veil = useMemo(() => ringTexture(), []);
  useEffect(() => () => { glow.dispose(); veil.dispose(); }, [glow, veil]);
  const tmp = useMemo(() => new THREE.Euler(), []);
  const vec = useMemo(() => new THREE.Vector3(), []);
  const eul = useMemo(() => new THREE.Euler(), []);
  // Two Points objects: the wormhole's own spores and the remnants that stay in a universe afterwards.
  const wormSpores = useMemo(() => makeSpores(glow, WORMHOLE_SPORES, .85), [glow]);
  const remSpores = useMemo(() => makeSpores(glow, REMNANT_SPORES, .6), [glow]);
  const remnantSeed = useRef(-1);
  useEffect(() => () => { for (const sp of [wormSpores, remSpores]) { sp.geometry.dispose(); sp.material.dispose(); } }, [wormSpores, remSpores]);

  /** Writes every spore's place and brightness for this frame. */
  const fillSpores = (sp: Spores, counts: SporeCounts, s: Simulation, t: number, w: number, seed: number, reveal: (group: number, index: number) => number) => {
    const pos = sp.geometry.attributes["position"] as THREE.BufferAttribute;
    const col = sp.geometry.attributes["color"] as THREE.BufferAttribute;
    let n = 0;
    const put = (x: number, y: number, z: number, light: number) => {
      pos.setXYZ(n, x, y, z);
      col.setXYZ(n, sp.base[n * 3]! * light * w, sp.base[n * 3 + 1]! * light * w, sp.base[n * 3 + 2]! * light * w);
      n++;
    };
    const off = seed * 1000;
    // Rain: drops out of the air, hits the ground and bounces lower and lower before fading.
    for (let i = 0; i < counts.rain; i++) {
      const period = 3.4 + rnd(i + off, 21) * 2.6, tau = t / period + rnd(i + off, 22) * 10;
      const cycle = Math.floor(tau), u = tau - cycle;
      const key = i * 7 + cycle * 131 + off;
      const x = wrap(rnd(key, 23) * 4000, s.x) + Math.sin(t * 1.3 + i) * .4;
      const z = wrap(rnd(key, 24) * 4000, s.z) + Math.cos(t * 1.1 + i) * .4;
      const height = 5 + rnd(key, 25) * 11;
      put(x, groundHeight(x, z) + .15 + bounce(u, height), z, Math.min(1, u * 10) * Math.min(1, (1 - u) * 6) * reveal(0, i));
    }
    // Sheets: grids of spores hanging in the air, rippling like cloth in a wind no one feels.
    for (let sh = 0; sh < counts.sheets; sh++) {
      const ax = wrap(rnd(sh + off, 31) * 4000, s.x), az = wrap(rnd(sh + off, 32) * 4000, s.z);
      const ay = groundHeight(ax, az) + 3 + rnd(sh + off, 33) * 8;
      eul.set(rnd(sh + off, 34) * 6.3 + t * .05, rnd(sh + off, 35) * 6.3, rnd(sh + off, 36) * 6.3 + t * .04);
      const shown = reveal(1, sh);
      for (let gx = 0; gx < SHEET_N; gx++) for (let gz = 0; gz < SHEET_N; gz++) {
        const lx = (gx - (SHEET_N - 1) / 2) * 1.1, lz = (gz - (SHEET_N - 1) / 2) * 1.1;
        const ripple = Math.sin(Math.hypot(lx, lz) * .9 - t * 2.4 + sh) * .8 + Math.sin(lx * .6 + t * 1.1) * .3;
        vec.set(lx, ripple, lz).applyEuler(eul);
        put(ax + vec.x, ay + vec.y, az + vec.z, (.75 + Math.sin(t * 2 + gx * .7 + gz * .5) * .25) * shown);
      }
    }
    // Rings and helixes: dots circling in turning hoops that breathe in and out.
    for (let r = 0; r < counts.rings; r++) {
      const ax = wrap(rnd(r + off, 51) * 4000, s.x), az = wrap(rnd(r + off, 52) * 4000, s.z);
      const ay = groundHeight(ax, az) + 2 + rnd(r + off, 53) * 10;
      eul.set(rnd(r + off, 54) * 6.3, rnd(r + off, 55) * 6.3 + t * .1, rnd(r + off, 56) * 6.3);
      const rad = 2.4 + rnd(r + off, 57) * 2 + Math.sin(t * .8 + r) * .9;
      const shown = reveal(2, r);
      for (let k = 0; k < RING_N; k++) {
        const a = (k / RING_N) * Math.PI * 2 + t * (.6 + r * .15) * (r % 2 ? -1 : 1);
        const rise = r % 2 ? (k / RING_N - .5) * 5 : Math.sin(k * .9 + t * 2) * .35;
        vec.set(Math.cos(a) * rad, rise, Math.sin(a) * rad).applyEuler(eul);
        put(ax + vec.x, ay + vec.y, az + vec.z, (.8 + Math.sin(t * 3 + k) * .2) * shown);
      }
    }
    pos.needsUpdate = true;
    col.needsUpdate = true;
  };

  useFrame(() => {
    const s = simulation.current;
    const t = s.elapsed;
    // ---- the black holes themselves (only those near enough to see) ----
    if (holes.current) {
      const list = s.mode === "freeroam" && s.warp < 0
        ? holesAround(s.x, s.z, s.universe).filter(h => Math.hypot(h.x - s.x, h.z - s.z) < HOLE_SEE) : [];
      holes.current.children.forEach((slot, i) => {
        const hole = list[i];
        slot.visible = !!hole;
        if (!hole) return;
        slot.position.set(hole.x, groundHeight(hole.x, hole.z) + HOLE_HEIGHT + Math.sin(t * .6 + i) * .12, hole.z);
        const disk = slot.children[1];
        if (disk) {
          disk.rotation.set(1.2 + Math.sin(t * .2 + i) * .12, 0, .35 + Math.sin(t * .15 + i * 2) * .1);
          disk.children.forEach((ring, r) => { ring.rotation.z = t * (.9 - r * .28) * (r % 2 ? -1 : 1); });
        }
        const sparks = slot.children[3];
        if (sparks) sparks.children.forEach((spark, k) => {
          const a = t * (1.4 - k * .13) + k * 1.7, rad = 3.3 + (k % 4) * .7;
          spark.position.set(Math.cos(a) * rad, Math.sin(a * .9 + k) * .5, Math.sin(a) * rad);
        });
      });
    }
    const w = warpEnvelope(s);
    // ---- the wormhole's impossible secrets ----
    // They stand in the world itself (fixed places that slide past as the runner moves) in poses nothing
    // real could hold: upside down, edge-on, floating, sunk into the ground, all slowly turning.
    if (swarm.current) {
      swarm.current.visible = w > 0.002;
      if (w > 0.002) swarm.current.children.forEach((item, i) => {
        const x = wrap(rnd(i, 1) * 4000, s.x), z = wrap(rnd(i, 2) * 4000, s.z);
        const gy = groundHeight(x, z);
        // Mostly in the open air above the trunks, some planted in the ground at a mad angle.
        const lift = rnd(i, 3) < .3 ? -1 + rnd(i, 4) * 1.5 : rnd(i, 4) < .4 ? 1.5 + rnd(i, 5) * 4 : 5 + rnd(i, 6) * 13;
        item.position.set(x, gy + lift, z);
        tmp.set(
          rnd(i, 7) * 6.3 + t * (rnd(i, 8) - .5) * .5,
          rnd(i, 9) * 6.3 + t * (rnd(i, 10) - .5) * .7,
          rnd(i, 11) * 6.3 + t * (rnd(i, 12) - .5) * .4);
        item.rotation.copy(tmp);
        item.scale.setScalar(Math.max(.001, (1 + rnd(i, 13) * 2.6) * w));
      });
    }
    wormSpores.points.visible = w > 0.002;
    if (w > 0.002) fillSpores(wormSpores, WORMHOLE_SPORES, s, t, w, 0, () => 1);
    // ---- remnants: the wormholes' leftovers, a different mix in every universe, arriving one by one ----
    const inUniverse = s.mode === "freeroam" && s.universe > 0 && s.warp < 0;
    if (remnants.current) remnants.current.visible = inUniverse;
    remSpores.points.visible = inUniverse;
    if (inUniverse) {
      const ut = universeTime(s), u = s.universe;
      if (remnantSeed.current !== u) { remnantSeed.current = u; paintSpores(remSpores, u); }
      if (remnants.current) remnants.current.children.forEach((item, i) => {
        const key = u * 101 + i;
        // Some slots stay empty in a given universe, so each one has its own mix of relics.
        const alive = rnd(key, 71) < Math.min(.8, .3 + u * .1);
        const grown = alive ? smooth((ut - rnd(key, 72) * REMNANT_SPAN) / 4) : 0;
        item.visible = grown > .001;
        if (!item.visible) return;
        const x = wrap(rnd(key, 73) * 4000, s.x), z = wrap(rnd(key, 74) * 4000, s.z);
        const gy = groundHeight(x, z);
        const lift = rnd(key, 75) < .55 ? -.5 + rnd(key, 76) * 2 : 2 + rnd(key, 77) * 9;
        item.position.set(x, gy + lift, z);
        tmp.set(
          rnd(key, 78) * 6.3 + t * (rnd(key, 79) - .5) * .2,
          rnd(key, 80) * 6.3 + t * (rnd(key, 81) - .5) * .3,
          rnd(key, 82) * 6.3 + t * (rnd(key, 83) - .5) * .15);
        item.rotation.copy(tmp);
        item.scale.setScalar(Math.max(.001, (.8 + rnd(key, 84) * 1.8) * grown));
      });
      const arrive = (group: number, index: number) => smooth((ut - rnd(u * 977 + group * 31 + index, 85) * REMNANT_SPAN) / 5);
      fillSpores(remSpores, REMNANT_SPORES, s, t, .8, u, arrive);
    }
  });

  return <>
    <group ref={holes}>
      {Array.from({ length: HOLE_SLOTS }, (_, i) => (
        <group key={i} visible={false}>
          {/* The body: pure black, not touched by fog, so it reads from far away. */}
          <mesh>
            <sphereGeometry args={[HOLE_RADIUS, 28, 20]} />
            <meshBasicMaterial color="#000000" fog={false} />
          </mesh>
          {/* A tilted accretion veil: three faint, soft glows that barely show, turning slowly. */}
          <group>
            {[[10, palette.lavender, .16], [13, palette.purple, .11], [17, palette.secret, .07]].map(([size, color, opacity], r) => (
              <mesh key={r}>
                <planeGeometry args={[size as number, size as number]} />
                <meshBasicMaterial map={veil} color={color as THREE.Color} transparent opacity={opacity as number} blending={THREE.AdditiveBlending} depthWrite={false} side={THREE.DoubleSide} fog={false} />
              </mesh>
            ))}
          </group>
          {/* A dim violet halo that carries through the fog like the gate beacons. */}
          <sprite scale={[22, 22, 1]}>
            <spriteMaterial map={glow} color={palette.purple} transparent opacity={.3} blending={THREE.AdditiveBlending} depthWrite={false} fog={false} />
          </sprite>
          <group>
            {Array.from({ length: 9 }, (_, k) => (
              <sprite key={k} scale={[.55, .55, 1]}>
                <spriteMaterial map={glow} color={palette.lavender} transparent opacity={.8} blending={THREE.AdditiveBlending} depthWrite={false} fog={false} />
              </sprite>
            ))}
          </group>
        </group>
      ))}
    </group>
    <primitive object={wormSpores.points} />
    <primitive object={remSpores.points} />
    <group ref={swarm} visible={false}>
      {Array.from({ length: SWARM }, (_, i) => <Relic key={i} i={i} palette={palette} glow={glow} />)}
    </group>
    <group ref={remnants} visible={false}>
      {Array.from({ length: REMNANTS }, (_, i) => <Relic key={i} i={i + 7} palette={palette} glow={glow} />)}
    </group>
  </>;
}
