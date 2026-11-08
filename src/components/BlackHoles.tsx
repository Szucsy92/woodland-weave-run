import { useEffect, useMemo, useRef, type MutableRefObject } from "react";
import { useFrame } from "@react-three/fiber";
import * as THREE from "three";
import { groundHeight, holesAround, HOLE_HEIGHT, HOLE_RADIUS, warpEnvelope, type Simulation } from "@/lib/forest/simulation";

type HolePalette = Record<"secret" | "rock" | "purple" | "lavender" | "green" | "lamp" | "shock", THREE.Color>;

const HOLE_SLOTS = 6;
// Impossible secrets that fill the space while the wormhole is open.
const SWARM = 30;
// Side of the wrapped square the secrets live in (wider than the fog, so a wrap is never seen).
const WINDOW = 110;
const KINDS = ["monolith", "ring", "lantern"] as const;

// Cheap deterministic noise for the swarm: every item keeps its own character.
const rnd = (i: number, k: number) => {
  const v = Math.sin(i * 127.1 + k * 311.7) * 43758.5453;
  return v - Math.floor(v);
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

/**
 * Freeroam black holes and the wormhole they open. Each hole is a pure black body with a tilted,
 * turning accretion disk, a halo that shows through the fog and a few orbiting sparks; the screen-space
 * bending of the space around it is drawn by ForestEffects. While the wormhole is open the secrets
 * (slabs, rings, lanterns) hang everywhere, tumbling in poses no real place could hold.
 */
export function BlackHoles({ simulation, palette }: { simulation: MutableRefObject<Simulation>; palette: HolePalette }) {
  const holes = useRef<THREE.Group>(null);
  const swarm = useRef<THREE.Group>(null);
  const glow = useMemo(() => glowTexture(), []);
  useEffect(() => () => glow.dispose(), [glow]);
  const tmp = useMemo(() => new THREE.Euler(), []);

  useFrame(() => {
    const s = simulation.current;
    const t = s.elapsed;
    // ---- the black holes themselves ----
    if (holes.current) {
      const list = s.mode === "freeroam" && s.warp < 0 ? holesAround(s.x, s.z) : [];
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
    // ---- the wormhole's impossible secrets ----
    // They stand in the world itself (fixed places that slide past as the runner moves) in poses nothing
    // real could hold: upside down, edge-on, floating, sunk into the ground, all slowly turning.
    if (swarm.current) {
      const w = warpEnvelope(s);
      swarm.current.visible = w > 0.002;
      if (w <= 0.002) return;
      swarm.current.children.forEach((item, i) => {
        // Each item lives in one big wrapped window around the runner, so it is recycled unseen beyond the fog.
        const wrap = (anchor: number, centre: number) => centre + ((((anchor - centre) % WINDOW) + WINDOW * 1.5) % WINDOW) - WINDOW / 2;
        const x = wrap(rnd(i, 1) * 4000, s.x), z = wrap(rnd(i, 2) * 4000, s.z);
        const gy = groundHeight(x, z);
        const lift = rnd(i, 3) < .3 ? -1 + rnd(i, 4) * 1.5 : rnd(i, 4) < .5 ? 1 + rnd(i, 5) * 5 : 4 + rnd(i, 6) * 14;
        item.position.set(x, gy + lift, z);
        // Fixed odd stance per item, drifting round very slowly.
        tmp.set(
          rnd(i, 7) * 6.3 + t * (rnd(i, 8) - .5) * .5,
          rnd(i, 9) * 6.3 + t * (rnd(i, 10) - .5) * .7,
          rnd(i, 11) * 6.3 + t * (rnd(i, 12) - .5) * .4);
        item.rotation.copy(tmp);
        item.scale.setScalar(Math.max(.001, (.7 + rnd(i, 13) * 2.4) * w));
      });
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
          {/* A tilted accretion disk of three turning bands. */}
          <group>
            {[[2.7, 3.6, palette.lavender, .5], [3.7, 4.9, palette.purple, .38], [5, 6.4, palette.secret, .22]].map(([inner, outer, color, opacity], r) => (
              <mesh key={r}>
                <ringGeometry args={[inner as number, outer as number, 48, 1, 0, Math.PI * (1.55 + r * .2)]} />
                <meshBasicMaterial color={color as THREE.Color} transparent opacity={opacity as number} blending={THREE.AdditiveBlending} depthWrite={false} side={THREE.DoubleSide} fog={false} />
              </mesh>
            ))}
          </group>
          {/* A dim violet halo that carries through the fog like the gate beacons. */}
          <sprite scale={[22, 22, 1]}>
            <spriteMaterial map={glow} color={palette.purple} transparent opacity={.4} blending={THREE.AdditiveBlending} depthWrite={false} fog={false} />
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
    <group ref={swarm} visible={false}>
      {Array.from({ length: SWARM }, (_, i) => {
        const kind = KINDS[i % 3];
        return (
          <group key={i}>
            {kind === "monolith" && <>
              <mesh>
                <boxGeometry args={[1, 4.4, .35]} />
                <meshStandardMaterial color={palette.rock} roughness={.35} metalness={.2} emissive={palette.secret} emissiveIntensity={.25} />
              </mesh>
              <mesh position={[.07, .1, .2]}>
                <boxGeometry args={[.06, 3.2, .02]} />
                <meshBasicMaterial color={palette.secret} />
              </mesh>
            </>}
            {kind === "ring" && <>
              <mesh>
                <torusGeometry args={[2.4, .14, 8, 40]} />
                <meshBasicMaterial color={palette.green} />
              </mesh>
              <mesh rotation-x={Math.PI / 2}>
                <torusGeometry args={[1.5, .08, 6, 28]} />
                <meshBasicMaterial color={palette.shock} />
              </mesh>
            </>}
            {kind === "lantern" && <>
              <mesh position={[0, -.8, 0]}>
                <cylinderGeometry args={[.05, .07, 1.6, 6]} />
                <meshStandardMaterial color={palette.rock} roughness={.9} />
              </mesh>
              <mesh position={[.06, .1, 0]}>
                <boxGeometry args={[.24, .3, .24]} />
                <meshBasicMaterial color={palette.lamp} />
              </mesh>
            </>}
            <sprite scale={[kind === "lantern" ? 3.2 : 6, kind === "monolith" ? 7 : kind === "lantern" ? 3.2 : 6, 1]}>
              <spriteMaterial map={glow} color={kind === "lantern" ? palette.lamp : kind === "ring" ? palette.green : palette.secret} transparent opacity={.4} blending={THREE.AdditiveBlending} depthWrite={false} fog={false} />
            </sprite>
          </group>
        );
      })}
    </group>
  </>;
}
