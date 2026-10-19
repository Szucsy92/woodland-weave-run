import { Canvas, useFrame, useThree } from "@react-three/fiber";
import { useEffect, useMemo, useRef, useState } from "react";
import * as THREE from "three";

const CELL = 6;
const RADIUS = 9; // cells around player
const BASE_SPEED = 9;
const TURN = 2.2;
const SAFE = 12;

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
  const player = useRef<THREE.Group>(null);
  const trunks = useRef<THREE.InstancedMesh>(null);
  const crowns = useRef<THREE.InstancedMesh>(null);
  const crowns2 = useRef<THREE.InstancedMesh>(null);
  const ground = useRef<THREE.Mesh>(null);
  const keys = useRef({ l: false, r: false });
  const sim = useRef({ x: 0, z: 0, h: 0, dist: 0, cell: "", trees: [] as Tree[], t: 0 });
  const { camera } = useThree();
  const dummy = useMemo(() => new THREE.Object3D(), []);

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
      s.h += ((keys.current.l ? 1 : 0) - (keys.current.r ? 1 : 0)) * TURN * dt;
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
    if (key !== s.cell && trunks.current && crowns.current && crowns2.current) {
      s.cell = key;
      s.trees = treesAround(Math.floor(s.x / CELL), Math.floor(s.z / CELL));
      s.trees.forEach((t, i) => {
        dummy.position.set(t.x, 1 * t.s, t.z); dummy.scale.setScalar(t.s); dummy.rotation.set(0, t.x, 0); dummy.updateMatrix();
        trunks.current!.setMatrixAt(i, dummy.matrix);
        dummy.position.y = 3 * t.s; dummy.updateMatrix(); crowns.current!.setMatrixAt(i, dummy.matrix);
        dummy.position.y = 4.4 * t.s; dummy.scale.setScalar(t.s * 0.75); dummy.updateMatrix(); crowns2.current!.setMatrixAt(i, dummy.matrix);
      });
      for (const m of [trunks.current, crowns.current, crowns2.current]) { m.count = s.trees.length; m.instanceMatrix.needsUpdate = true; }
    }
    if (player.current) {
      player.current.position.set(s.x, state === "run" ? 0.6 + Math.abs(Math.sin(s.t * 12)) * 0.15 : 0.6, s.z);
      player.current.rotation.y = s.h;
      const lean = ((keys.current.l ? 1 : 0) - (keys.current.r ? 1 : 0)) * 0.25;
      player.current.rotation.z = THREE.MathUtils.damp(player.current.rotation.z, state === "run" ? lean : 0, 8, dt);
    }
    if (ground.current) ground.current.position.set(s.x, 0, s.z);
    const back = new THREE.Vector3(Math.sin(s.h) * 7, 4, Math.cos(s.h) * 7);
    camera.position.lerp(new THREE.Vector3(s.x, 0, s.z).add(back), 1 - Math.exp(-6 * dt));
    camera.lookAt(s.x - Math.sin(s.h) * 6, 1, s.z - Math.cos(s.h) * 6);
  });

  return (
    <>
      <mesh ref={ground} rotation-x={-Math.PI / 2} receiveShadow>
        <circleGeometry args={[RADIUS * CELL * 1.2, 48]} />
        <meshStandardMaterial color="#5f7d3a" />
      </mesh>
      <instancedMesh ref={trunks} args={[undefined, undefined, MAX]} castShadow>
        <cylinderGeometry args={[0.25, 0.4, 2, 6]} />
        <meshStandardMaterial color="#6b4423" flatShading />
      </instancedMesh>
      <instancedMesh ref={crowns} args={[undefined, undefined, MAX]} castShadow>
        <coneGeometry args={[1.6, 3, 7]} />
        <meshStandardMaterial color="#2f5d34" flatShading />
      </instancedMesh>
      <instancedMesh ref={crowns2} args={[undefined, undefined, MAX]} castShadow>
        <coneGeometry args={[1.3, 2.4, 7]} />
        <meshStandardMaterial color="#3e7a3f" flatShading />
      </instancedMesh>
      <group ref={player}>
        <mesh castShadow position={[0, 0.2, 0]}>
          <capsuleGeometry args={[0.35, 0.6, 4, 10]} />
          <meshStandardMaterial color="#d9622b" flatShading />
        </mesh>
        <mesh castShadow position={[0, 0.95, -0.05]}>
          <sphereGeometry args={[0.28, 10, 8]} />
          <meshStandardMaterial color="#f0c9a0" flatShading />
        </mesh>
      </group>
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
    <div className="fixed inset-0 bg-background" onClick={() => state !== "run" && setState("run")}>
      <Canvas shadows dpr={[1, 2]} camera={{ fov: 60, position: [0, 4, 7] }}>
        <color attach="background" args={["#c9d8c0"]} />
        <fog attach="fog" args={["#c9d8c0", 15, 55]} />
        <hemisphereLight args={["#fff4dc", "#3b4a2a", 0.9]} />
        <directionalLight position={[10, 20, 5]} intensity={1.6} color="#ffe2b0" />
        <World state={state} onScore={(n) => { if (n !== scoreRef.current) { scoreRef.current = n; setScore(n); } }} onCrash={crash} />
      </Canvas>
      <div className="pointer-events-none fixed inset-0 z-10 flex flex-col items-center p-6 font-mono text-foreground">
        <div className="flex w-full justify-between text-lg">
          <span>{score} m</span><span>Rekord: {best} m</span>
        </div>
        {state !== "run" && (
          <div className="mt-[30vh] rounded-lg bg-background/80 px-8 py-6 text-center shadow-lg">
            <h1 className="text-3xl font-bold">{state === "over" ? "Fának ütköztél!" : "Erdei futás"}</h1>
            {state === "over" && <p className="mt-2">Távolság: {score} m</p>}
            <p className="mt-3 text-sm">← / → vagy A / D: kanyarodás · mobilon érintsd a képernyő bal/jobb felét</p>
            <p className="mt-3 font-semibold">Szóköz vagy kattintás az indításhoz</p>
          </div>
        )}
      </div>
    </div>
  );
}
