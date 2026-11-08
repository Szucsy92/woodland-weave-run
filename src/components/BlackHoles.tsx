import { useEffect, useMemo, useRef, type MutableRefObject } from "react";
import { useFrame } from "@react-three/fiber";
import * as THREE from "three";
import { groundHeight, holesAround, HOLE_HEIGHT, HOLE_RADIUS, warpEnvelope, type Simulation } from "@/lib/forest/simulation";

type HolePalette = Record<"secret" | "rock" | "purple" | "lavender" | "green" | "lamp" | "shock", THREE.Color>;

const HOLE_SLOTS = 6;
// Impossible secrets that fill the space while the wormhole is open.
const SWARM = 36;
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
    if (swarm.current) {
      const w = warpEnvelope(s);
      swarm.current.visible = w > 0.002;
      if (w <= 0.002) return;
      const camY = groundHeight(s.x, s.z) + 1.6;
      swarm.current.children.forEach((item, i) => {
        // Every item circles the runner on its own orbit, sliding in and out of reach.
        const life = (rnd(i, 1) + t * (.035 + rnd(i, 2) * .06)) % 1;
        const near = 5 + life * 44;
        const az = s.heading + (rnd(i, 3) - .5) * Math.PI * 2.2 + Math.sin(t * (.3 + rnd(i, 4)) + i) * .8;
        const el = (rnd(i, 5) - .5) * Math.PI * 1.1 + Math.sin(t * .5 + i * 1.3) * .35;
        item.position.set(
          s.x - Math.sin(az) * Math.cos(el) * near,
          camY + Math.sin(el) * near * .7,
          s.z - Math.cos(az) * Math.cos(el) * near);
        // Tumbling on every axis: upside down, edge-on, spinning in place, never at rest.
        tmp.set(
          rnd(i, 6) * 6.3 + t * (rnd(i, 7) - .5) * 2.4,
          rnd(i, 8) * 6.3 + t * (rnd(i, 9) - .5) * 3.1,
          rnd(i, 10) * 6.3 + t * (rnd(i, 11) - .5) * 1.7);
        item.rotation.copy(tmp);
        // Sizes jump from a thumb to a house, and swell in and out of existence.
        const size = (.5 + Math.pow(rnd(i, 12), 2.2) * 4.5) * (.75 + Math.sin(t * 1.3 + i * 2.1) * .25);
        const born = Math.min(1, life * 6) * Math.min(1, (1 - life) * 6);
        item.scale.setScalar(Math.max(.001, size * w * born));
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
