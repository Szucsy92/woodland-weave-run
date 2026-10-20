import { Canvas, useFrame, useThree } from "@react-three/fiber";
import { useCallback, useEffect, useMemo, useRef, useState, type MutableRefObject } from "react";
import { ArrowLeft, Settings } from "lucide-react";
import * as THREE from "three";
import { ForestEffects } from "./ForestEffects";
import { ForestDrone } from "@/lib/forest/drone";
import { beginRun, CELL, DIFFICULTIES, FOG_END, getSteering, groundHeight, hash, IMPACT_DURATION, makeSimulation, obstaclesAround, orientationValue, RADIUS, stepSimulation, treesAround, type Difficulty, type Phase, type Simulation } from "@/lib/forest/simulation";
import barkUrl from "@/assets/wet-bark.jpg";
import floorUrl from "@/assets/wet-ground.jpg";
import barkNormal from "@/assets/bark-normal.jpg";
import barkRough from "@/assets/bark-rough.jpg";
import floorNormal from "@/assets/ground-normal.jpg";
import floorRough from "@/assets/ground-rough.jpg";
import introIcon from "@/assets/forest-icon.jpg";

type Palette = Record<"fog" | "moon" | "lamp" | "green" | "purple" | "lavender" | "trunk" | "ground" | "crown" | "rock" | "plant", THREE.Color>;
type Input = { tilt: number; touch: number; touchMode: boolean; neutral: number | null; angle: number };
const MAX_INSTANCES = (RADIUS * 2 + 1) ** 2;
const BRANCHES_PER_TREE = 6;

// Mirrors groundHeight() in the simulation so the displaced ground, the camera
// height and every planted object always agree.
const GROUND_GLSL = `
float groundHeight(float x, float z) {
  float w1 = sin(x * 0.11 + sin(z * 0.07) * 2.4) * cos(z * 0.09 + sin(x * 0.05) * 2.1);
  float w2 = sin(z * 0.23 - sin(x * 0.16) * 1.8) * cos(x * 0.19 + sin(z * 0.12) * 1.4);
  float w3 = sin((x + z) * 0.47 + sin(x * 0.3) * 1.2);
  return (w1 * 1.15 + w2 * 0.55 + w3 * 0.35) * 0.62;
}`;

function World({ simulation, input, palette, onPhase, onImpact, onStumble }: {
  simulation: MutableRefObject<Simulation>;
  input: MutableRefObject<Input>;
  palette: Palette;
  onPhase: (phase: Phase, score: number) => void;
  onImpact: (side: number) => void;
  onStumble: () => void;
}) {
  const trunks = useRef<THREE.InstancedMesh>(null);
  const branches = useRef<THREE.InstancedMesh>(null);
  const crowns = useRef<THREE.InstancedMesh>(null);
  const rocks = useRef<THREE.InstancedMesh>(null);
  const plants = useRef<THREE.InstancedMesh>(null);
  const ground = useRef<THREE.Mesh>(null);
  const lamp = useRef<THREE.PointLight>(null);
  const glow = useRef<THREE.Group>(null);
  const lastImpact = useRef(0);
  const lastStumble = useRef(0);
  const lean = useRef(0);
  const { camera } = useThree();
  const resources = useMemo(() => {
    const loader = new THREE.TextureLoader();
    const bark = loader.load(barkUrl);
    const barkN = loader.load(barkNormal);
    const barkR = loader.load(barkRough);
    const floor = loader.load(floorUrl);
    const floorN = loader.load(floorNormal);
    const floorR = loader.load(floorRough);
    [bark, floor].forEach(t => { t.colorSpace = THREE.SRGBColorSpace; });
    [barkN, barkR, floorN, floorR].forEach(t => { t.colorSpace = THREE.NoColorSpace; });
    [bark, barkN, barkR].forEach(t => { t.wrapS = t.wrapT = THREE.RepeatWrapping; t.repeat.set(1.5, 7); t.anisotropy = 8; });
    [floor, floorN, floorR].forEach(t => { t.wrapS = t.wrapT = THREE.RepeatWrapping; t.repeat.set(64, 64); t.anisotropy = 8; });
    // Bare trunks, low enough that neighbouring crowns touch overhead.
    const trunkGeometry = new THREE.CylinderGeometry(.36, .46, 22, 14, 24);
    const pos = trunkGeometry.getAttribute("position") as THREE.BufferAttribute;
    for (let i = 0; i < pos.count; i++) {
      const y = pos.getY(i);
      const a = Math.atan2(pos.getZ(i), pos.getX(i));
      const swell = 1 + Math.sin(a * 5 + y * .9) * .065 + Math.sin(a * 9 - y * .4) * .025;
      const root = 1 + Math.pow(Math.max(0, (1 - (y + 11) / 2)), 2) * .65;
      pos.setXYZ(i, pos.getX(i) * swell * root + Math.sin(y * .3) * .065, y, pos.getZ(i) * swell * root);
    }
    trunkGeometry.computeVertexNormals();
    trunkGeometry.translate(0, 11, 0);
    const branchGeometry = new THREE.CylinderGeometry(.03, .06, 2.2, 5, 1);
    branchGeometry.translate(0, 1.1, 0);
    const jitter = (geometry: THREE.BufferGeometry, xz: number, y: number, seed: number) => {
      const p = geometry.getAttribute("position") as THREE.BufferAttribute;
      for (let i = 0; i < p.count; i++) {
        const r1 = hash(i * 7 + 1, i * 13 + 3, seed);
        const r2 = hash(i * 17 + 5, i * 11 + 9, seed + 1);
        p.setXYZ(i, p.getX(i) * (1 - xz + r1 * xz * 2), p.getY(i) * (1 - y + r2 * y * 2), p.getZ(i) * (1 - xz + r2 * xz * 2));
      }
      geometry.computeVertexNormals();
    };
    const crownGeometry = new THREE.IcosahedronGeometry(1, 1);
    jitter(crownGeometry, .28, .35, 21);
    const rockGeometry = new THREE.IcosahedronGeometry(1, 1);
    jitter(rockGeometry, .3, .2, 33);
    const plantGeometry = new THREE.ConeGeometry(.5, 1, 6);
    const canvas = document.createElement("canvas");
    canvas.width = canvas.height = 128;
    const ctx = canvas.getContext("2d");
    if (ctx) {
      const r = ctx.createRadialGradient(64, 64, 0, 64, 64, 64);
      r.addColorStop(0, "rgba(255,255,255,1)");
      r.addColorStop(.2, "rgba(255,255,255,.6)");
      r.addColorStop(1, "rgba(255,255,255,0)");
      ctx.fillStyle = r;
      ctx.fillRect(0, 0, 128, 128);
    }
    const groundMaterial = new THREE.MeshStandardMaterial({
      map: floor, normalMap: floorN, roughnessMap: floorR,
      normalScale: new THREE.Vector2(.5, .5), color: palette.ground, roughness: .95, metalness: 0,
    });
    groundMaterial.onBeforeCompile = shader => {
      shader.vertexShader = shader.vertexShader
        .replace("#include <common>", `#include <common>\n${GROUND_GLSL}`)
        .replace("#include <beginnormal_vertex>", `vec4 gwp = modelMatrix * vec4(position, 1.0);
          float gh = groundHeight(gwp.x, gwp.z);
          float ge = .55;
          float gxh = (groundHeight(gwp.x + ge, gwp.z) - groundHeight(gwp.x - ge, gwp.z)) / (2. * ge);
          float gzh = (groundHeight(gwp.x, gwp.z + ge) - groundHeight(gwp.x, gwp.z - ge)) / (2. * ge);
          #include <beginnormal_vertex>
          objectNormal = normalize(objectNormal + vec3(-gxh, gzh, 0.));`)
        .replace("#include <begin_vertex>", `#include <begin_vertex>\ntransformed.z += gh;`);
    };
    return { bark, barkN, barkR, floor, floorN, floorR, trunkGeometry, branchGeometry, crownGeometry, rockGeometry, plantGeometry, groundMaterial, glowTexture: new THREE.CanvasTexture(canvas), dummy: new THREE.Object3D(), target: new THREE.Vector3() };
  }, [palette]);
  useEffect(() => () => {
    [resources.trunkGeometry, resources.branchGeometry, resources.crownGeometry, resources.rockGeometry, resources.plantGeometry, resources.glowTexture, resources.groundMaterial].forEach(g => g.dispose());
    [resources.bark, resources.barkN, resources.barkR, resources.floor, resources.floorN, resources.floorR].forEach(t => t.dispose());
  }, [resources]);
  const previousPhase = useRef<Phase>("menu");
  useFrame((_, raw) => {
    const s = simulation.current;
    const key = `${Math.floor(s.x / CELL)},${Math.floor(s.z / CELL)}`;
    const trunkMesh = trunks.current;
    if (trunkMesh && key !== s.cell) {
      s.cell = key;
      const level = DIFFICULTIES[s.difficulty];
      s.trees = treesAround(Math.floor(s.x / CELL), Math.floor(s.z / CELL), level.treeGap);
      s.obstacles = obstaclesAround(Math.floor(s.x / CELL), Math.floor(s.z / CELL), level.obstacleGap);
      const branchMesh = branches.current, crownMesh = crowns.current, rockMesh = rocks.current, plantMesh = plants.current;
      s.trees.forEach((tree, i) => {
        const seedX = Math.floor(tree.x), seedZ = Math.floor(tree.z);
        const base = groundHeight(tree.x, tree.z) - .45;
        resources.dummy.rotation.order = "XYZ";
        resources.dummy.position.set(tree.x, base, tree.z);
        resources.dummy.scale.set(tree.scale, 1, tree.scale);
        resources.dummy.rotation.set(0, hash(seedX, seedZ, 5) * 6, 0);
        resources.dummy.updateMatrix();
        trunkMesh.setMatrixAt(i, resources.dummy.matrix);
        for (let b = 0; b < BRANCHES_PER_TREE; b++) {
          const seed = hash(seedX, seedZ, 20 + b);
          const top = base + 19 + hash(seedX, seedZ, 60 + b) * 2.2;
          resources.dummy.position.set(tree.x, top, tree.z);
          resources.dummy.rotation.order = "YXZ";
          resources.dummy.rotation.set(.55 + hash(seedX, seedZ, 40 + b) * .75, seed * Math.PI * 2, 0);
          resources.dummy.scale.set(tree.scale, tree.scale * (.7 + seed * .6), tree.scale);
          resources.dummy.updateMatrix();
          branchMesh?.setMatrixAt(i * BRANCHES_PER_TREE + b, resources.dummy.matrix);
        }
        resources.dummy.rotation.order = "XYZ";
        resources.dummy.position.set(tree.x + (hash(seedX, seedZ, 70) - .5) * 1.6, base + 19.4 + hash(seedX, seedZ, 71) * 1.4, tree.z + (hash(seedX, seedZ, 72) - .5) * 1.6);
        resources.dummy.scale.setScalar((3.1 + hash(seedX, seedZ, 73) * 1.9) * tree.scale);
        resources.dummy.updateMatrix();
        crownMesh?.setMatrixAt(i, resources.dummy.matrix);
      });
      trunkMesh.count = s.trees.length;
      if (branchMesh) branchMesh.count = s.trees.length * BRANCHES_PER_TREE;
      if (crownMesh) crownMesh.count = s.trees.length;
      let rockCount = 0, plantCount = 0;
      s.obstacles.forEach((o, i) => {
        const gy = groundHeight(o.x, o.z);
        resources.dummy.rotation.order = "XYZ";
        resources.dummy.rotation.set(0, hash(Math.floor(o.x), Math.floor(o.z), 80) * 6, 0);
        if (o.big) {
          resources.dummy.position.set(o.x, gy + o.radius * .45, o.z);
          resources.dummy.scale.setScalar(o.radius * 1.25);
          resources.dummy.updateMatrix();
          rockMesh?.setMatrixAt(rockCount++, resources.dummy.matrix);
        } else {
          resources.dummy.position.set(o.x, gy + .32, o.z);
          resources.dummy.scale.set(1.2, .9 + hash(Math.floor(o.x), Math.floor(o.z), 81) * .8, 1.2);
          resources.dummy.updateMatrix();
          plantMesh?.setMatrixAt(plantCount++, resources.dummy.matrix);
        }
      });
      if (rockMesh) { rockMesh.count = rockCount; rockMesh.instanceMatrix.needsUpdate = true; }
      if (plantMesh) { plantMesh.count = plantCount; plantMesh.instanceMatrix.needsUpdate = true; }
      trunkMesh.instanceMatrix.needsUpdate = true;
      if (branchMesh) branchMesh.instanceMatrix.needsUpdate = true;
      if (crownMesh) crownMesh.instanceMatrix.needsUpdate = true;
    }
    stepSimulation(s, getSteering(input.current.touchMode, input.current.touch, input.current.tilt), raw);
    if (s.phase !== previousPhase.current) { previousPhase.current = s.phase; onPhase(s.phase, Math.floor(s.distance)); }
    if (s.impact > 0 && lastImpact.current <= 0) onImpact(s.impactSide);
    lastImpact.current = s.impact;
    if (s.stumble > 0 && lastStumble.current <= 0) onStumble();
    lastStumble.current = s.stumble;
    if (ground.current) {
      ground.current.position.set(s.x, 0, s.z);
      [resources.floor, resources.floorN, resources.floorR].forEach(t => t.offset.set(s.x / 3.125, -s.z / 3.125));
    }
    const t = s.elapsed;
    const lying = s.phase === "falling" || s.phase === "fallen";
    const fall = lying ? THREE.MathUtils.smoothstep(s.phaseTime, 0, 1.25) : 0;
    const waking = s.phase === "awakening" ? 1 - THREE.MathUtils.smoothstep(s.phaseTime, 0, 1.15) : 0;
    const bump = s.impact > 0 ? Math.sin((1 - s.impact / IMPACT_DURATION) * Math.PI) * s.impactSide : 0;
    const leanAmount = lean.current;
    const tripping = Math.min(1, s.stumble * (s.speedMul < .8 ? 1 : 2));
    const bob = s.phase === "running" ? Math.sin(t * 11) * .025 : Math.sin(t * .5) * .015;
    lean.current += (getSteering(input.current.touchMode, input.current.touch, input.current.tilt) - lean.current) * Math.min(1, raw * 9);
    camera.position.set(
      s.x + fall * .45 + leanAmount * .3,
      groundHeight(s.x, s.z) + 1.6 - fall * 1.28 + bob,
      s.z + fall * .65);
    resources.target.set(
      s.x - Math.sin(s.heading) * 18,
      groundHeight(s.x - Math.sin(s.heading) * 18, s.z - Math.cos(s.heading) * 18) + 1.65 + fall * 3,
      s.z - Math.cos(s.heading) * 18);
    camera.lookAt(resources.target);
    camera.rotateX(bump * .085 + waking * Math.sin(t * 5) * .045 + fall * .065 * Math.sin(t * .32) + tripping * Math.sin(t * 17) * .085);
    camera.rotateY(-leanAmount * .06);
    camera.rotateZ(-leanAmount * .055 + bump * .12 + fall * .72 + waking * Math.sin(t * 4) * .03 + Math.sin(t * 5.5) * .018 + tripping * Math.sin(t * 13) * .07);
    if (lamp.current) {
      lamp.current.position.copy(camera.position);
      lamp.current.position.y += .35;
      const flicker = lying ? (Math.sin(t * 5.7) + Math.sin(t * 13.2) > .25 ? .14 : .035) : 1 - waking * .8;
      lamp.current.intensity = 65 * flicker;
    }
    if (glow.current) {
      // Behind the fog wall, past every visible trunk. Constant distance keeps the lights unreachable.
      glow.current.position.set(s.x, groundHeight(s.x, s.z - 42) + 4.2, s.z - 42);
      glow.current.children.forEach((child, i) => {
        const sprite = child as THREE.Sprite;
        sprite.scale.setScalar((i === 0 ? 24 : 20) * (1 + Math.sin(t * .4 + i) * .08));
        sprite.position.x = (i === 0 ? -5 : i === 1 ? 5 : 0) + Math.sin(t * .16 + i) * 1.5;
      });
    }
  });
  return <>
    <pointLight ref={lamp} color={palette.lamp} intensity={65} distance={27} decay={1.6} />
    <group ref={glow}>
      {[palette.green, palette.purple, palette.lavender].map((color, i) => (
        <sprite key={i} renderOrder={-1}>
          <spriteMaterial map={resources.glowTexture} color={color} transparent opacity={.66} blending={THREE.AdditiveBlending} depthWrite={false} fog={false} />
        </sprite>
      ))}
    </group>
    <mesh ref={ground} rotation-x={-Math.PI / 2} material={resources.groundMaterial}>
      <planeGeometry args={[200, 200, 1, 1]} />
    </mesh>
    <instancedMesh ref={trunks} args={[resources.trunkGeometry, undefined, MAX_INSTANCES]} frustumCulled={false}>
      <meshStandardMaterial map={resources.bark} normalMap={resources.barkN} roughnessMap={resources.barkR} normalScale={new THREE.Vector2(.7, .7)} color={palette.trunk} roughness={.95} metalness={0} />
    </instancedMesh>
    <instancedMesh ref={branches} args={[resources.branchGeometry, undefined, MAX_INSTANCES * BRANCHES_PER_TREE]} frustumCulled={false}>
      <meshStandardMaterial color={palette.trunk} roughness={.95} metalness={0} />
    </instancedMesh>
    <instancedMesh ref={crowns} args={[resources.crownGeometry, undefined, MAX_INSTANCES]} frustumCulled={false}>
      <meshStandardMaterial color={palette.crown} roughness={1} metalness={0} flatShading />
    </instancedMesh>
    <instancedMesh ref={rocks} args={[resources.rockGeometry, undefined, MAX_INSTANCES]} frustumCulled={false}>
      <meshStandardMaterial color={palette.rock} roughness={.92} metalness={0} flatShading />
    </instancedMesh>
    <instancedMesh ref={plants} args={[resources.plantGeometry, undefined, MAX_INSTANCES]} frustumCulled={false}>
      <meshStandardMaterial color={palette.plant} roughness={1} metalness={0} flatShading />
    </instancedMesh>
  </>;
}

function Hud({ simulation }: { simulation: MutableRefObject<Simulation> }) {
  const [meters, setMeters] = useState(0);
  useEffect(() => {
    const id = window.setInterval(() => setMeters(Math.floor(simulation.current.distance)), 120);
    return () => window.clearInterval(id);
  }, [simulation]);
  return <div className="forest-hud" aria-live="off">{meters}<small>m</small></div>;
}

function ForestIntro({ onDone }: { onDone: () => void }) {
  useEffect(() => {
    const timer = window.setTimeout(onDone, 2700);
    return () => window.clearTimeout(timer);
  }, [onDone]);
  const figure = (
    <svg viewBox="0 0 120 160" className="forest-figure-svg">
      <ellipse className="forest-fig-arm forest-fig-arm-l" cx="42" cy="82" rx="5.5" ry="16" />
      <ellipse className="forest-fig-arm forest-fig-arm-r" cx="78" cy="98" rx="5.5" ry="16" />
      <ellipse className="forest-fig-shoulders" cx="60" cy="74" rx="21" ry="12" />
      <circle className="forest-fig-head" cx="60" cy="46" r="11.5" />
      <path className="forest-fig-torso" d="M46 82 Q60 74 74 82 L70 116 Q60 122 50 116 Z" />
      <ellipse className="forest-fig-leg forest-fig-leg-l" cx="50" cy="132" rx="7" ry="22" />
      <ellipse className="forest-fig-leg forest-fig-leg-r" cx="70" cy="128" rx="7" ry="22" />
    </svg>
  );
  return <div className="forest-intro" aria-hidden="true">
    <img className="forest-intro-icon" src={introIcon} alt="" />
    <div className="forest-intro-blur">
      {figure}
      <div className="forest-intro-fig-glitch-a">{figure}</div>
      <div className="forest-intro-fig-glitch-b">{figure}</div>
    </div>
  </div>;
}

export function ForestRunner() {
  const simulation = useRef(makeSimulation());
  const input = useRef<Input>({ tilt: 0, touch: 0, touchMode: false, neutral: null, angle: 0 });
  const drone = useRef<ForestDrone | null>(null);
  const [phase, setPhase] = useState<Phase>("menu");
  const [best, setBest] = useState(0);
  const [last, setLast] = useState(0);
  const [sound, setSound] = useState(true);
  const [touchMode, setTouchMode] = useState(false);
  const [haptics, setHaptics] = useState(true);
  const [difficulty, setDifficulty] = useState<Difficulty>("easy");
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [palette, setPalette] = useState<Palette | null>(null);
  const [notice, setNotice] = useState("");
  const [introDone, setIntroDone] = useState(() => {
    try { return sessionStorage.getItem("forest-intro") === "done"; } catch { return false; }
  });
  const hapticsRef = useRef(true);
  const menu = phase === "menu" || phase === "fallen";

  useEffect(() => {
    const audio = new ForestDrone();
    drone.current = audio;
    try {
      setBest(Number(localStorage.getItem("forest-best")) || 0);
      setLast(Number(localStorage.getItem("forest-last")) || 0);
      setSound(localStorage.getItem("forest-sound") !== "off");
      const touch = localStorage.getItem("forest-touch") === "on";
      setTouchMode(touch);
      input.current.touchMode = touch;
      const stored = localStorage.getItem("forest-difficulty");
      if (stored === "easy" || stored === "normal" || stored === "hard") {
        setDifficulty(stored);
        simulation.current.difficulty = stored;
      }
      const vib = localStorage.getItem("forest-haptics") !== "off";
      setHaptics(vib);
      hapticsRef.current = vib;
    } catch { /* Local storage may be disabled. */ }
    const canvas = document.createElement("canvas");
    canvas.width = canvas.height = 1;
    const ctx = canvas.getContext("2d");
    if (ctx) {
      const styles = getComputedStyle(document.documentElement);
      const p = {} as Palette;
      for (const name of ["fog", "moon", "lamp", "green", "purple", "lavender", "trunk", "ground", "crown", "rock", "plant"] as const) {
        ctx.fillStyle = styles.getPropertyValue(`--forest-${name}`).trim();
        ctx.fillRect(0, 0, 1, 1);
        const data = ctx.getImageData(0, 0, 1, 1).data;
        p[name] = new THREE.Color().setRGB((data[0] ?? 0) / 255, (data[1] ?? 0) / 255, (data[2] ?? 0) / 255, THREE.SRGBColorSpace);
      }
      setPalette(p);
    }
    const orientation = (e: DeviceOrientationEvent) => {
      if (e.gamma == null || e.beta == null) return;
      const angle = screen.orientation?.angle ?? (window as unknown as { orientation?: number }).orientation ?? 0;
      const raw = orientationValue(e.gamma, e.beta, angle);
      if (input.current.neutral == null || input.current.angle !== angle) {
        input.current.neutral = raw;
        input.current.angle = angle;
      }
      input.current.tilt = THREE.MathUtils.clamp(raw - (input.current.neutral ?? 0), -1, 1);
    };
    const visibility = () => { input.current.touch = 0; if (document.hidden) audio.suspend(); };
    window.addEventListener("deviceorientation", orientation);
    document.addEventListener("visibilitychange", visibility);
    return () => {
      window.removeEventListener("deviceorientation", orientation);
      document.removeEventListener("visibilitychange", visibility);
      audio.dispose();
    };
  }, []);
  useEffect(() => { drone.current?.setMode(sound, phase === "awakening" || phase === "running"); }, [sound, phase]);

  const onPhase = useCallback((next: Phase, score: number) => {
    setPhase(next);
    if (next === "falling") {
      setLast(score);
      setBest(old => Math.max(old, score));
      if (hapticsRef.current) navigator.vibrate?.([70, 60, 110, 60, 150]);
      try {
        localStorage.setItem("forest-last", String(score));
        localStorage.setItem("forest-best", String(Math.max(Number(localStorage.getItem("forest-best")) || 0, score)));
      } catch { /* Local storage may be disabled. */ }
    }
  }, []);
  const onImpact = useCallback(() => {
    if (hapticsRef.current) navigator.vibrate?.(65);
    drone.current?.impactDip();
  }, []);
  const onStumble = useCallback(() => {
    if (hapticsRef.current) navigator.vibrate?.(22);
  }, []);

  const start = async () => {
    if (phase !== "menu" && phase !== "fallen") return;
    setNotice("");
    drone.current?.unlock();
    if (!touchMode) {
      if (typeof DeviceOrientationEvent === "undefined") {
        setNotice("Döntés nem elérhető. Kapcsold be az érintéses irányítást.");
        return;
      }
      const D = DeviceOrientationEvent as unknown as { requestPermission?: () => Promise<string> };
      try {
        if (D.requestPermission && await D.requestPermission() !== "granted") {
          setNotice("Döntés nem engedélyezett. Kapcsold be az érintéses irányítást.");
          return;
        }
      } catch {
        setNotice("Kapcsold be az érintéses irányítást.");
        return;
      }
      input.current.neutral = null;
    }
    input.current.touch = 0;
    beginRun(simulation.current);
    setPhase("awakening");
  };
  const toggleSound = () => {
    drone.current?.unlock();
    const next = !sound;
    setSound(next);
    drone.current?.setMode(next, phase === "awakening" || phase === "running");
    try { localStorage.setItem("forest-sound", next ? "on" : "off"); } catch { /* Local storage may be disabled. */ }
  };
  const toggleHaptics = () => {
    const next = !haptics;
    setHaptics(next);
    hapticsRef.current = next;
    if (next) navigator.vibrate?.(30);
    try { localStorage.setItem("forest-haptics", next ? "on" : "off"); } catch { /* Local storage may be disabled. */ }
  };
  const changeTouch = (checked: boolean) => {
    setTouchMode(checked);
    input.current.touchMode = checked;
    input.current.touch = 0;
    setNotice("");
    try { localStorage.setItem("forest-touch", checked ? "on" : "off"); } catch { /* Local storage may be disabled. */ }
  };
  const changeDifficulty = (level: Difficulty) => {
    setDifficulty(level);
    simulation.current.difficulty = level;
    simulation.current.cell = "";
    try { localStorage.setItem("forest-difficulty", level); } catch { /* Local storage may be disabled. */ }
  };
  const markIntroDone = useCallback(() => {
    setIntroDone(true);
    try { sessionStorage.setItem("forest-intro", "done"); } catch { /* Session storage may be disabled. */ }
  }, []);

  return <main
    className="forest-game dark"
    data-phase={phase}
    onPointerDown={e => {
      drone.current?.unlock();
      if (!document.fullscreenElement && document.documentElement.requestFullscreen) void document.documentElement.requestFullscreen().catch(() => undefined);
      const inUi = (e.target as Element | null)?.closest("[data-forest-ui]");
      if (menu) {
        if (inUi) return;
        if (settingsOpen) { setSettingsOpen(false); return; }
        void start();
        return;
      }
      if (touchMode) {
        e.currentTarget.setPointerCapture(e.pointerId);
        input.current.touch = e.clientX < e.currentTarget.clientWidth / 2 ? -1 : 1;
      }
    }}
    onPointerMove={e => {
      if (e.buttons && !menu && touchMode) input.current.touch = e.clientX < e.currentTarget.clientWidth / 2 ? -1 : 1;
    }}
    onPointerUp={() => { input.current.touch = 0; }}
    onPointerCancel={() => { input.current.touch = 0; }}
  >
    {palette && <Canvas dpr={[1, 1.5]} camera={{ fov: 72, position: [0, 1.6, 0] }} gl={{ antialias: true, powerPreference: "high-performance" }}>
      <color attach="background" args={[palette.fog]} />
      <fog attach="fog" args={[palette.fog, 7, FOG_END]} />
      <hemisphereLight args={[palette.moon, palette.fog, .85]} />
      <directionalLight position={[-15, 22, -20]} color={palette.moon} intensity={.7} />
      <World simulation={simulation} input={input} palette={palette} onPhase={onPhase} onImpact={onImpact} onStumble={onStumble} />
      <ForestEffects simulation={simulation} />
    </Canvas>}
    {!introDone && <ForestIntro onDone={markIntroDone} />}
    {(phase === "running" || phase === "awakening" || phase === "falling") && <Hud simulation={simulation} />}
    {introDone && menu && (
      <div className="forest-menu">
        <section className="forest-glass" aria-label="Futás menü">
          {settingsOpen ? (
            <div className="forest-settings-panel" data-forest-ui>
              <button className="forest-back" onClick={() => setSettingsOpen(false)} aria-label="Vissza"><ArrowLeft size={15} /></button>
              <h2>Beállítások</h2>
              <div className="forest-row">
                <span>Nehézség</span>
                <div className="forest-seg forest-seg-three">
                  <button className={difficulty === "easy" ? "on" : ""} onClick={() => changeDifficulty("easy")}>Könnyű</button>
                  <button className={difficulty === "normal" ? "on" : ""} onClick={() => changeDifficulty("normal")}>Közép</button>
                  <button className={difficulty === "hard" ? "on" : ""} onClick={() => changeDifficulty("hard")}>Nehéz</button>
                </div>
              </div>
              <div className="forest-row">
                <span>Irányítás</span>
                <div className="forest-seg">
                  <button className={touchMode ? "" : "on"} onClick={() => changeTouch(false)}>Döntés</button>
                  <button className={touchMode ? "on" : ""} onClick={() => changeTouch(true)}>Érintés</button>
                </div>
              </div>
              <div className="forest-row">
                <span>Audió</span>
                <button className="forest-switch" aria-pressed={sound} onClick={toggleSound}>{sound ? "Be" : "Ki"}</button>
              </div>
              <div className="forest-row">
                <span>Rezgés</span>
                <button className="forest-switch" aria-pressed={haptics} onClick={toggleHaptics}>{haptics ? "Be" : "Ki"}</button>
              </div>
            </div>
          ) : (
            <>
              <div className="forest-head">
                <h1 className="forest-title" data-text="tap and run!">tap and run!</h1>
                <button className="forest-gear" data-forest-ui onClick={() => setSettingsOpen(true)} aria-label="Beállítások"><Settings size={16} /></button>
              </div>
              <div className="forest-scores">
                <div><span>Előző</span><strong>{last}<small>m</small></strong></div>
                <div><span>Rekord</span><strong>{best}<small>m</small></strong></div>
              </div>
            </>
          )}
          {notice && <p role="status" className="forest-notice">{notice}</p>}
        </section>
      </div>
    )}
  </main>;
}
