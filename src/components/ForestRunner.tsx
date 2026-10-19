import { Canvas, useFrame, useThree } from "@react-three/fiber";
import { Suspense, useEffect, useMemo, useRef, useState } from "react";
import barkUrl from "@/assets/bark.jpg";
import floorUrl from "@/assets/forest-floor.jpg";

// Phone tilt (-1..1), written by the deviceorientation listener
const tilt = { v: 0, active: false };
function onOrient(e: DeviceOrientationEvent) {
  if (e.gamma == null) return;
  tilt.active = true;
  const landscape = Math.abs((screen.orientation?.angle ?? 0)) === 90;
  const raw = landscape ? (e.beta ?? 0) * Math.sign(screen.orientation.angle) : e.gamma;
  tilt.v = THREE.MathUtils.clamp(raw / 25, -1, 1);
}
async function enableTilt() {
  const D = DeviceOrientationEvent as unknown as { requestPermission?: () => Promise<string> };
  try { if (typeof D?.requestPermission === "function" && (await D.requestPermission()) !== "granted") return; } catch { return; }
  window.removeEventListener("deviceorientation", onOrient);
  window.addEventListener("deviceorientation", onOrient);
}
import * as THREE from "three";

const CELL = 6;
const RADIUS = 9; // cells around player
const BASE_SPEED = 9;
const TURN = 2.2;
const SAFE = 12;
const MAX_HEADING = 1.1; // ~63°, the player can never turn back

function hash(x: number, z: number, s = 0) {
  let h = (x * 374761393 + z * 668265263 + s * 982451653) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967295;
}

type Tree = { x: number; z: number; s: number; r: number };
function treesAround(cx: number, cz: number): Tree[] {
  const out: Tree[] = [];
  for (let i = -RADIUS; i <= RADIUS; i++)
    for (let j = -RADIUS; j <= RADIUS; j++) {
      const gx = cx + i, gz = cz + j;
      if (hash(gx, gz) > 0.55) continue;
      const x = gx * CELL + hash(gx, gz, 1) * CELL;
      const z = gz * CELL + hash(gx, gz, 2) * CELL;
      if (x * x + z * z < SAFE * SAFE) continue;
      out.push({ x, z, s: 0.8 + hash(gx, gz, 3) * 0.8, r: 0.5 });
    }
  return out;
}

const MAX = (RADIUS * 2 + 1) ** 2;

type State = "ready" | "run" | "over";

function World({ state, onScore, onCrash }: { state: State; onScore: (n: number) => void; onCrash: () => void }) {
  const trunks = useRef<THREE.InstancedMesh>(null);
  const ground = useRef<THREE.Mesh>(null);
  const lamp = useRef<THREE.PointLight>(null);
  const glow = useRef<THREE.Group>(null);
  const glowTex = useMemo(() => {
    const c = document.createElement("canvas"); c.width = c.height = 256;
    const g = c.getContext("2d")!; const r = g.createRadialGradient(128, 128, 0, 128, 128, 128);
    r.addColorStop(0, "rgba(255,255,255,1)"); r.addColorStop(0.25, "rgba(255,255,255,0.55)"); r.addColorStop(1, "rgba(255,255,255,0)");
    g.fillStyle = r; g.fillRect(0, 0, 256, 256);
    const t = new THREE.CanvasTexture(c); return t;
  }, []);
  const keys = useRef({ l: false, r: false });
  const sim = useRef({ x: 0, z: 0, h: 0, dist: 0, cell: "", trees: [] as Tree[], t: 0 });
  const { camera } = useThree();
  const dummy = useMemo(() => new THREE.Object3D(), []);
  const steer = () => {
    const k = (keys.current.l ? 1 : 0) - (keys.current.r ? 1 : 0);
    if (k) return k;
    return tilt.active ? (Math.abs(tilt.v) < 0.08 ? 0 : -tilt.v) : 0;
  };
  const bark = useMemo(() => new THREE.TextureLoader().load(barkUrl), []);
  const floor = useMemo(() => new THREE.TextureLoader().load(floorUrl), []);
  useMemo(() => {
    for (const t of [bark, floor]) { t.wrapS = t.wrapT = THREE.RepeatWrapping; t.colorSpace = THREE.SRGBColorSpace; t.anisotropy = 4; }
    bark.repeat.set(1, 8);
    floor.repeat.set(60, 60);
  }, [bark, floor]);

  useEffect(() => {
    const set = (v: boolean) => (e: KeyboardEvent) => {
      if (["ArrowLeft", "KeyA"].includes(e.code)) keys.current.l = v;
      if (["ArrowRight", "KeyD"].includes(e.code)) keys.current.r = v;
    };
    const d = set(true), u = set(false);
    window.addEventListener("keydown", d);
    window.addEventListener("keyup", u);
    const touch = (e: TouchEvent) => {
      const t = e.touches[0];
      keys.current.l = !!t && t.clientX < window.innerWidth / 2;
      keys.current.r = !!t && t.clientX >= window.innerWidth / 2;
    };
    window.addEventListener("touchstart", touch);
    window.addEventListener("touchend", touch);
    return () => {
      window.removeEventListener("keydown", d);
      window.removeEventListener("keyup", u);
      window.removeEventListener("touchstart", touch);
      window.removeEventListener("touchend", touch);
    };
  }, []);

  useEffect(() => {
    if (state === "run") Object.assign(sim.current, { x: 0, z: 0, h: 0, dist: 0, cell: "" });
  }, [state]);

  useFrame((_, raw) => {
    const dt = Math.min(raw, 0.05);
    const s = sim.current;
    s.t += dt;
    if (state === "run") {
      const speed = BASE_SPEED + s.dist / 60;
      s.h = THREE.MathUtils.clamp(s.h + steer() * TURN * dt, -MAX_HEADING, MAX_HEADING);
      s.x += -Math.sin(s.h) * speed * dt;
      s.z += -Math.cos(s.h) * speed * dt;
      s.dist += speed * dt;
      onScore(Math.floor(s.dist));
      for (const t of s.trees) {
        const dx = t.x - s.x, dz = t.z - s.z;
        if (dx * dx + dz * dz < (t.r * t.s + 0.45) ** 2) { onCrash(); break; }
      }
    }
    const key = `${Math.floor(s.x / CELL)},${Math.floor(s.z / CELL)}`;
    if (key !== s.cell && trunks.current ) {
      s.cell = key;
      s.trees = treesAround(Math.floor(s.x / CELL), Math.floor(s.z / CELL));
      s.trees.forEach((t, i) => {
        dummy.position.set(t.x, 15, t.z); dummy.scale.set(t.s, 1, t.s); dummy.rotation.set(0, t.x, 0); dummy.updateMatrix();
        trunks.current!.setMatrixAt(i, dummy.matrix);
      });
      for (const m of [trunks.current]) { m.count = s.trees.length; m.instanceMatrix.needsUpdate = true; }
    }
    if (ground.current) {
      ground.current.position.set(s.x, 0, s.z);
      floor.offset.set(s.x / (200 / 60), -s.z / (200 / 60));
    }
    const bob = state === "run" ? Math.sin(s.t * 12) * 0.05 : 0;
    camera.position.set(s.x, 1.6 + bob, s.z);
    camera.lookAt(s.x - Math.sin(s.h) * 10, 1.6 + bob, s.z - Math.cos(s.h) * 10);
    lamp.current?.position.set(s.x, 2.2, s.z);
    if (glow.current) {
      glow.current.position.set(s.x, 3, s.z - 90);
      glow.current.children.forEach((c, i) => {
        const p = 1 + Math.sin(s.t * (0.8 + i * 0.5) + i * 2) * 0.15;
        c.scale.setScalar([40, 26, 12][i] * p);
        c.position.x = Math.sin(s.t * 0.4 + i * 2.1) * 3 * i;
      });
    }
    camera.rotateZ(steer() * 0.08);
  });

  return (
    <>
      <pointLight ref={lamp} intensity={60} distance={28} decay={1.6} color="#ffe6b8" />
      <group ref={glow}>
        {["#9b4dff", "#3dff9a", "#e8d8ff"].map((c, i) => (
          <sprite key={c} renderOrder={-1}>
            <spriteMaterial map={glowTex} color={c} transparent opacity={[0.55, 0.5, 0.8][i]} blending={THREE.AdditiveBlending} depthWrite={false} fog={false} />
          </sprite>
        ))}
      </group>
      <mesh ref={ground} rotation-x={-Math.PI / 2} receiveShadow>
        <planeGeometry args={[200, 200]} />
        <meshStandardMaterial map={floor} color="#9aa896" />
      </mesh>
      <instancedMesh ref={trunks} args={[undefined, undefined, MAX]} castShadow frustumCulled={false}>
        <cylinderGeometry args={[0.4, 0.45, 30, 8]} />
        <meshStandardMaterial map={bark} color="#d8ccc0" />
      </instancedMesh>
    </>
  );
}

export function ForestRunner() {
  const [state, setState] = useState<State>("ready");
  const [score, setScore] = useState(0);
  const [best, setBest] = useState(0);
  const scoreRef = useRef(0);

  useEffect(() => { setBest(Number(localStorage.getItem("forest-best") || 0)); }, []);
  useEffect(() => {
    const k = (e: KeyboardEvent) => { if (e.code === "Space" && state !== "run") setState("run"); };
    window.addEventListener("keydown", k);
    return () => window.removeEventListener("keydown", k);
  }, [state]);

  const crash = () => {
    setState("over");
    if (scoreRef.current > best) { setBest(scoreRef.current); localStorage.setItem("forest-best", String(scoreRef.current)); }
  };

  return (
    <div className="dark fixed inset-0 bg-background" onClick={() => { if (state !== "run") { enableTilt(); setState("run"); } }}>
      <Canvas shadows dpr={[1, 2]} camera={{ fov: 70, position: [0, 1.6, 0] }}>
        <color attach="background" args={["#0b1424"]} />
        <fog attach="fog" args={["#0b1424", 6, 45]} />
        <hemisphereLight args={["#8aa0d0", "#1a2418", 1.2]} />
        <directionalLight position={[-10, 25, -5]} intensity={0.9} color="#b8c8ff" />
        <Suspense fallback={null}><World state={state} onScore={(n) => { if (n !== scoreRef.current) { scoreRef.current = n; setScore(n); } }} onCrash={crash} /></Suspense>
      </Canvas>
      <div className="pointer-events-none fixed inset-0 z-10 flex flex-col items-center p-6 font-mono text-foreground">
        <div className="flex w-full justify-between text-lg">
          <span>{score} m</span><span>Rekord: {best} m</span>
        </div>
        {state !== "run" && (
          <div className="mt-[30vh] rounded-lg bg-background/80 px-8 py-6 text-center shadow-lg">
            <h1 className="text-3xl font-bold">{state === "over" ? "Fának ütköztél!" : "Erdei futás"}</h1>
            {state === "over" && <p className="mt-2">Távolság: {score} m</p>}
            <p className="mt-3 text-sm">← / → vagy A / D: kanyarodás · mobilon döntsd meg a telefont balra/jobbra</p>
            <p className="mt-3 font-semibold">Szóköz vagy kattintás az indításhoz</p>
          </div>
        )}
      </div>
    </div>
  );
}
