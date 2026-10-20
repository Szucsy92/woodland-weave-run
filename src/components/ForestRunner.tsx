import { Canvas, useFrame, useThree } from "@react-three/fiber";
import { useCallback, useEffect, useMemo, useRef, useState, type MutableRefObject } from "react";
import { ArrowLeft, Settings } from "lucide-react";
import * as THREE from "three";
import { ForestEffects } from "./ForestEffects";
import { ForestDrone } from "@/lib/forest/drone";
import { beginRun, CELL, FOG_END, getSteering, hash, IMPACT_DURATION, makeSimulation, orientationValue, RADIUS, stepSimulation, treesAround, type Phase, type Simulation } from "@/lib/forest/simulation";
import barkUrl from "@/assets/wet-bark.jpg";
import floorUrl from "@/assets/wet-ground.jpg";
import barkNormal from "@/assets/bark-normal.jpg";
import barkRough from "@/assets/bark-rough.jpg";
import floorNormal from "@/assets/ground-normal.jpg";
import floorRough from "@/assets/ground-rough.jpg";

type Palette = Record<"fog" | "moon" | "lamp" | "green" | "purple" | "lavender" | "trunk" | "ground", THREE.Color>;
type Input = { tilt: number; touch: number; touchMode: boolean; neutral: number | null; angle: number };
const MAX_INSTANCES = (RADIUS * 2 + 1) ** 2;

function World({ simulation, input, palette, onPhase, onImpact }: {
  simulation: MutableRefObject<Simulation>;
  input: MutableRefObject<Input>;
  palette: Palette;
  onPhase: (phase: Phase, score: number) => void;
  onImpact: (side: number) => void;
}) {
  const trunks = useRef<THREE.InstancedMesh>(null);
  const ground = useRef<THREE.Mesh>(null);
  const lamp = useRef<THREE.PointLight>(null);
  const glow = useRef<THREE.Group>(null);
  const lastImpact = useRef(0);
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
    [bark, barkN, barkR].forEach(t => { t.wrapS = t.wrapT = THREE.RepeatWrapping; t.repeat.set(1.5, 9); t.anisotropy = 8; });
    [floor, floorN, floorR].forEach(t => { t.wrapS = t.wrapT = THREE.RepeatWrapping; t.repeat.set(64, 64); t.anisotropy = 8; });
    const geometry = new THREE.CylinderGeometry(.36, .46, 30, 14, 24);
    const pos = geometry.getAttribute("position") as THREE.BufferAttribute;
    for (let i = 0; i < pos.count; i++) {
      const y = pos.getY(i);
      const a = Math.atan2(pos.getZ(i), pos.getX(i));
      const swell = 1 + Math.sin(a * 5 + y * .7) * .065 + Math.sin(a * 9 - y * .3) * .025;
      const root = 1 + Math.pow(Math.max(0, (1 - (y + 15) / 2)), 2) * .65;
      pos.setXYZ(i, pos.getX(i) * swell * root + Math.sin(y * .22) * .065, y, pos.getZ(i) * swell * root);
    }
    geometry.computeVertexNormals();
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
    return { bark, barkN, barkR, floor, floorN, floorR, geometry, glowTexture: new THREE.CanvasTexture(canvas), dummy: new THREE.Object3D(), target: new THREE.Vector3() };
  }, []);
  useEffect(() => () => {
    resources.geometry.dispose();
    resources.glowTexture.dispose();
    [resources.bark, resources.barkN, resources.barkR, resources.floor, resources.floorN, resources.floorR].forEach(t => t.dispose());
  }, [resources]);
  const previousPhase = useRef<Phase>("menu");
  useFrame((_, raw) => {
    const s = simulation.current;
    const key = `${Math.floor(s.x / CELL)},${Math.floor(s.z / CELL)}`;
    const mesh = trunks.current;
    if (mesh && key !== s.cell) {
      s.cell = key;
      s.trees = treesAround(Math.floor(s.x / CELL), Math.floor(s.z / CELL));
      s.trees.forEach((tree, i) => {
        resources.dummy.position.set(tree.x, 15, tree.z);
        resources.dummy.scale.set(tree.scale, 1, tree.scale);
        resources.dummy.rotation.set(0, hash(Math.floor(tree.x), Math.floor(tree.z), 5) * 6, 0);
        resources.dummy.updateMatrix();
        mesh.setMatrixAt(i, resources.dummy.matrix);
      });
      mesh.count = s.trees.length;
      mesh.instanceMatrix.needsUpdate = true;
    }
    stepSimulation(s, getSteering(input.current.touchMode, input.current.touch, input.current.tilt), raw);
    if (s.phase !== previousPhase.current) { previousPhase.current = s.phase; onPhase(s.phase, Math.floor(s.distance)); }
    if (s.impact > 0 && lastImpact.current <= 0) onImpact(s.impactSide);
    lastImpact.current = s.impact;
    if (ground.current) {
      ground.current.position.set(s.x, 0, s.z);
      [resources.floor, resources.floorN, resources.floorR].forEach(t => t.offset.set(s.x / 3.125, -s.z / 3.125));
    }
    const t = s.elapsed;
    const lying = s.phase === "falling" || s.phase === "fallen";
    const fall = lying ? THREE.MathUtils.smoothstep(s.phaseTime, 0, 1.25) : 0;
    const waking = s.phase === "awakening" ? 1 - THREE.MathUtils.smoothstep(s.phaseTime, 0, 1.15) : 0;
    const bump = s.impact > 0 ? Math.sin((1 - s.impact / IMPACT_DURATION) * Math.PI) * s.impactSide : 0;
    const bob = s.phase === "running" ? Math.sin(t * 11) * .025 : Math.sin(t * .5) * .015;
    camera.position.set(s.x + fall * .45, 1.6 - fall * 1.28 + bob, s.z + fall * .65);
    resources.target.set(s.x - Math.sin(s.heading) * 18, 1.65 + fall * 3, s.z - Math.cos(s.heading) * 18);
    camera.lookAt(resources.target);
    camera.rotateX(bump * .085 + waking * Math.sin(t * 5) * .045 + fall * .065 * Math.sin(t * .32));
    camera.rotateZ(-getSteering(input.current.touchMode, input.current.touch, input.current.tilt) * .035 + bump * .12 + fall * .72 + waking * Math.sin(t * 4) * .03);
    if (lamp.current) {
      lamp.current.position.copy(camera.position);
      lamp.current.position.y += .35;
      const flicker = lying ? (Math.sin(t * 5.7) + Math.sin(t * 13.2) > .25 ? .14 : .035) : 1 - waking * .8;
      lamp.current.intensity = 65 * flicker;
    }
    if (glow.current) {
      // Behind the fog wall, past every visible trunk. Constant distance keeps the lights unreachable.
      glow.current.position.set(s.x, 4.2, s.z - 42);
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
    <mesh ref={ground} rotation-x={-Math.PI / 2}>
      <planeGeometry args={[200, 200]} />
      <meshStandardMaterial map={resources.floor} normalMap={resources.floorN} roughnessMap={resources.floorR} normalScale={new THREE.Vector2(.5, .5)} color={palette.ground} roughness={.95} metalness={0} />
    </mesh>
    <instancedMesh ref={trunks} args={[resources.geometry, undefined, MAX_INSTANCES]} frustumCulled={false}>
      <meshStandardMaterial map={resources.bark} normalMap={resources.barkN} roughnessMap={resources.barkR} normalScale={new THREE.Vector2(.7, .7)} color={palette.trunk} roughness={.95} metalness={0} />
    </instancedMesh>
  </>;
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
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [palette, setPalette] = useState<Palette | null>(null);
  const [notice, setNotice] = useState("");
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
      for (const name of ["fog", "moon", "lamp", "green", "purple", "lavender", "trunk", "ground"] as const) {
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
      <World simulation={simulation} input={input} palette={palette} onPhase={onPhase} onImpact={onImpact} />
      <ForestEffects simulation={simulation} />
    </Canvas>}
    {menu && (
      <div className="forest-menu">
        <section className="forest-glass" aria-label="Futás menü">
          {settingsOpen ? (
            <div className="forest-settings-panel" data-forest-ui>
              <button className="forest-back" onClick={() => setSettingsOpen(false)} aria-label="Vissza"><ArrowLeft size={15} /></button>
              <h2>Beállítások</h2>
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
