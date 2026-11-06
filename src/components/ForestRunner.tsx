import { Canvas, useFrame, useThree } from "@react-three/fiber";
import { useCallback, useEffect, useMemo, useRef, useState, type MutableRefObject } from "react";
import { ArrowLeft, LogOut, Pause, Settings } from "lucide-react";
import * as THREE from "three";
import { ForestEffects } from "./ForestEffects";
import { ForestDrone } from "@/lib/forest/drone";
import { beginRun, canSpawnPowerUp, forestLightsFor, CELL, collectPowerUp, DIFFICULTIES, discoverSecret, GATE_CLEARING, GATE_HALF, gatesAround, type Gate, LIGHTS_DIE, passGate, SECRET_CLEARING, secretsAround, SHOCKWAVE_DISSOLVE, surviveStage, type Secret, FOG_END, getSteering, groundHeight, hash, IMPACT_DURATION, makeSimulation, requestExit, requestPause, requestResume, obstaclesAround, orientationValue, makeTiltCal, resetTilt, updateTilt, type TiltCal, placePowerUp, placeSpore, POWERUP_DURATION, POWERUP_GAP, RADIUS, sporeOpacity, stepSimulation, treesAround, daylightFor, type Difficulty, type ForestMood, type GameMode, type Phase, type PowerUp, type PowerUpType, type Simulation } from "@/lib/forest/simulation";
import { checkForUpdate, nativeUpdater, type UpdateInfo } from "@/lib/forest/updater";
import barkUrl from "@/assets/wet-bark.jpg";
import floorUrl from "@/assets/wet-ground.jpg";
import barkNormal from "@/assets/bark-normal.jpg";
import barkRough from "@/assets/bark-rough.jpg";
import floorNormal from "@/assets/ground-normal.jpg";
import floorRough from "@/assets/ground-rough.jpg";

const PALETTE_KEYS = ["fog", "moon", "lamp", "green", "purple", "lavender", "lemon", "trunk", "ground", "crown", "rock", "plant", "ember", "pursuer", "blood", "freezer", "ghost", "shock", "secret"] as const;
type Palette = Record<typeof PALETTE_KEYS[number], THREE.Color>;
type Input = { tilt: number; touch: number; touchMode: boolean; cal: TiltCal };
const MAX_INSTANCES = (RADIUS * 2 + 1) ** 2;
const BRANCHES_PER_TREE = 11;
const ROOTS_PER_TREE = 5;
const SPORE_COUNT = 72;
// Five core leaf masses per tree plus a leaf clump on every branch tip.
const CROWNS_PER_TREE = 5;
const CROWN_SLOTS = CROWNS_PER_TREE + BRANCHES_PER_TREE;

// Installed apps lock to landscape where the OS allows it; browsers get the
// request after fullscreen and simply ignore it when unsupported.
async function lockOrientation(mode: "landscape" | "any") {
  const native = (window as unknown as { AndroidOrientation?: { set: (m: string) => void } }).AndroidOrientation;
  if (native) { native.set(mode); return; }
  const so = screen.orientation as (ScreenOrientation & { lock?: (o: string) => Promise<void>; unlock?: () => void }) | undefined;
  try {
    if (mode === "landscape") {
      if (!document.fullscreenElement && document.documentElement.requestFullscreen) await document.documentElement.requestFullscreen();
      await so?.lock?.("landscape");
    } else so?.unlock?.();
  } catch { /* The game still works in any orientation. */ }
}

// Mirrors groundHeight() in the simulation so the displaced ground, the camera
// height and every planted object always agree.
const GROUND_GLSL = `
float groundHeight(float x, float z) {
  float w1 = sin(x * 0.11 + sin(z * 0.07) * 2.4) * cos(z * 0.09 + sin(x * 0.05) * 2.1);
  float w2 = sin(z * 0.23 - sin(x * 0.16) * 1.8) * cos(x * 0.19 + sin(z * 0.12) * 1.4);
  float w3 = sin((x + z) * 0.47 + sin(x * 0.3) * 1.2);
  return (w1 * 1.15 + w2 * 0.55 + w3 * 0.35) * 0.3;
}`;

const ORB_SLOTS = 2;
const GHOST_GLOW = "#eaf6ff";
const ORB_KEY: Record<PowerUpType, "freezer" | "ghost" | "shock"> = { freezer: "freezer", ghost: "ghost", shockwave: "shock" };
const SECRET_SLOTS = 9;
const ZERO = new THREE.Matrix4().makeScale(0, 0, 0);
/** Shared survive-mode red amount (0..1) read by the scene lights and fog. */
export type RedRef = MutableRefObject<number>;

function World({ simulation, input, palette, red, onPhase, onImpact, onStumble, onPowerUp, onPowerUpEnd, onThreat, onDark, onLit, onGate, onSecret }: {
  simulation: MutableRefObject<Simulation>;
  input: MutableRefObject<Input>;
  palette: Palette;
  onPhase: (phase: Phase, score: number) => void;
  onImpact: (side: number) => void;
  onStumble: () => void;
  onPowerUp: (type: PowerUpType) => void;
  onPowerUpEnd: () => void;
  onThreat: () => void;
  onDark: () => void;
  onLit: () => void;
  onGate: () => void;
  onSecret: (secret: Secret) => void;
  red: RedRef;
}) {
  const trunks = useRef<THREE.InstancedMesh>(null);
  const branches = useRef<THREE.InstancedMesh>(null);
  const roots = useRef<THREE.InstancedMesh>(null);
  const crowns = useRef<THREE.InstancedMesh>(null);
  const rocks = useRef<THREE.InstancedMesh>(null);
  const plants = useRef<THREE.InstancedMesh>(null);
  const shadows = useRef<THREE.InstancedMesh>(null);
  const mist = useRef<THREE.Group>(null);
  const ground = useRef<THREE.Mesh>(null);
  const lamp = useRef<THREE.PointLight>(null);
  const glow = useRef<THREE.Group>(null);
  const glowCell = useRef("");
  const lastImpact = useRef(0);
  const lastStumble = useRef(0);
  const lean = useRef(0);
  const spores = useRef<THREE.Group>(null);
  const sporeWorld = useRef<{ x: number; z: number; h: number }[]>([]);
  const orbs = useRef<THREE.Group>(null);
  const orbWorld = useRef<(PowerUp & { taken?: number })[]>([]);
  const lastOrbSpawn = useRef(0);
  const lastPowerUp = useRef<PowerUpType | null>(null);
  // Smoothed 0..1 ghost amount, so trees fade into and out of their ghost look.
  const ghostK = useRef(0);
  const redLight = useRef<THREE.PointLight>(null);
  const redFlood = useRef<THREE.DirectionalLight>(null);
  const redTarget = useMemo(() => new THREE.Object3D(), []);
  const stageRef = useRef("lit");
  const redMix = red;
  const secretGroup = useRef<THREE.Group>(null);
  const gateGroup = useRef<THREE.Group>(null);
  const gateWorld = useRef<Gate[]>([]);
  // Smoothed share of the canopy lights that has gone out (0 lit .. 1 dead).
  const dieRef = useRef(0);
  // Shared by the trunk, crown and bush shaders: how much of the green/purple
  // canopy light is left, and how far it has turned to the pursuer's red.
  const glowUniforms = useMemo(() => ({ uLights: { value: 1 }, uRed: { value: 0 } }), []);
  const secretWorld = useRef<Secret[]>([]);
  // Instance slots per tree/obstacle id, and pristine matrices for the glitch dissolve.
  const slotMap = useRef(new Map<string, [THREE.InstancedMesh, number][]>());
  const pristine = useRef(new Map<string, THREE.Matrix4[]>());
  const tmpM = useMemo(() => new THREE.Matrix4(), []);
  const tmpG = useMemo(() => new THREE.Matrix4(), []);
  const branchMat = useRef<THREE.MeshStandardMaterial>(null);
  const menuLook = useRef(0);
  const lampSway = useRef(0);
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
    // Trunks with low crowns (~15-17.5 m) that neighbours' foliage merges into overhead.
    // Slimmer, gnarled trunk: knots, irregular cross-section, gentle wander and a
    // buttressed root flare whose lobes run into the separate surface roots.
    const trunkGeometry = new THREE.CylinderGeometry(.27, .4, 22, 16, 32);
    const pos = trunkGeometry.getAttribute("position") as THREE.BufferAttribute;
    for (let i = 0; i < pos.count; i++) {
      const y = pos.getY(i);
      const a = Math.atan2(pos.getZ(i), pos.getX(i));
      const knot = Math.max(0, Math.sin(a * 3 + y * 1.7) * Math.sin(y * 2.3 + a)) ** 4 * .16;
      const swell = 1 + Math.sin(a * 5 + y * .9) * .07 + Math.sin(a * 9 - y * .4) * .035 + Math.sin(a * 2 + y * .23) * .06 + knot;
      const foot = Math.max(0, 1 - (y + 11) / 2.6);
      const root = 1 + foot * foot * (.75 + Math.max(0, Math.cos(a * 5 + .4)) * 1.3);
      const wx = Math.sin(y * .3) * .09 + Math.sin(y * .71 + 1.3) * .05;
      const wz = Math.cos(y * .26 + .7) * .08;
      pos.setXYZ(i, pos.getX(i) * swell * root + wx, y, pos.getZ(i) * swell * root + wz);
    }
    trunkGeometry.computeVertexNormals();
    trunkGeometry.translate(0, 11, 0);
    // Surface root: lies along +x, tapers and dives into the soil at its tip.
    const rootGeometry = new THREE.CylinderGeometry(.025, .16, 2.4, 7, 10);
    {
      const p = rootGeometry.getAttribute("position") as THREE.BufferAttribute;
      for (let i = 0; i < p.count; i++) {
        const y = p.getY(i) + 1.2, t = y / 2.4;
        const flat = 1 - t * .35;
        p.setXYZ(i, y, p.getX(i) * .75 * flat - t * t * .45 + Math.sin(t * 6) * .04, p.getZ(i) + Math.sin(t * 5.3) * .12 * t);
      }
      rootGeometry.computeVertexNormals();
    }
    const branchGeometry = new THREE.CylinderGeometry(.03, .07, 2.2, 6, 8);
    branchGeometry.translate(0, 1.1, 0);
    {
      // Crooked, knotty branch: bends sideways and droops along its length.
      const p = branchGeometry.getAttribute("position") as THREE.BufferAttribute;
      for (let i = 0; i < p.count; i++) {
        const y = p.getY(i), t = y / 2.2;
        p.setXYZ(i, p.getX(i) + Math.sin(t * 7.3) * .09 * t + t * t * .25, y, p.getZ(i) + Math.sin(t * 5.1 + 1) * .08 * t);
      }
      branchGeometry.computeVertexNormals();
    }
    const jitter = (geometry: THREE.BufferGeometry, xz: number, y: number, seed: number) => {
      const p = geometry.getAttribute("position") as THREE.BufferAttribute;
      for (let i = 0; i < p.count; i++) {
        const r1 = hash(i * 7 + 1, i * 13 + 3, seed);
        const r2 = hash(i * 17 + 5, i * 11 + 9, seed + 1);
        p.setXYZ(i, p.getX(i) * (1 - xz + r1 * xz * 2), p.getY(i) * (1 - y + r2 * y * 2), p.getZ(i) * (1 - xz + r2 * xz * 2));
      }
      geometry.computeVertexNormals();
    };
    const crownGeometry = new THREE.IcosahedronGeometry(1, 2);
    {
      const p = crownGeometry.getAttribute("position") as THREE.BufferAttribute;
      const v = new THREE.Vector3();
      for (let i = 0; i < p.count; i++) {
        v.fromBufferAttribute(p, i);
        const n = Math.sin(v.x * 5.1 + v.y * 2.3) * Math.sin(v.z * 4.7 - v.y * 3.1) * .16 + Math.sin(v.x * 11 + v.z * 9) * .05;
        const lump = Math.sin(v.x * 2.1 + 1.3) * Math.sin(v.z * 1.7 + v.y * 2.6) * .32 + Math.sin(v.y * 7.7 + v.x * 6.1) * Math.cos(v.z * 8.3) * .12;
        v.multiplyScalar(Math.max(.35, 1 + n + lump));
        v.y *= v.y < 0 ? .45 : .8;
        p.setXYZ(i, v.x, v.y, v.z);
      }
      crownGeometry.computeVertexNormals();
    }
    const rockGeometry = new THREE.IcosahedronGeometry(1, 2);
    jitter(rockGeometry, .18, .14, 33);
    const plantGeometry = new THREE.IcosahedronGeometry(1, 2);
    jitter(plantGeometry, .35, .4, 47);
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
    const sporeCanvas = document.createElement("canvas");
    sporeCanvas.width = sporeCanvas.height = 128;
    const sctx = sporeCanvas.getContext("2d");
    if (sctx) {
      // Bright hot core with a soft halo: spores read as strong-glow motes
      // without blooming over the view.
      const core = sctx.createRadialGradient(64, 64, 0, 64, 64, 64);
      core.addColorStop(0, "rgba(255,255,255,1)");
      core.addColorStop(.16, "rgba(255,255,255,.92)");
      core.addColorStop(.4, "rgba(255,255,255,.26)");
      core.addColorStop(1, "rgba(255,255,255,0)");
      sctx.fillStyle = core;
      sctx.fillRect(0, 0, 128, 128);
    }
    const groundMaterial = new THREE.MeshStandardMaterial({
      map: floor, normalMap: floorN, roughnessMap: floorR,
      normalScale: new THREE.Vector2(.5, .5), color: palette.ground, roughness: .95, metalness: 0,
    });
    groundMaterial.onBeforeCompile = shader => {
      shader.vertexShader = shader.vertexShader
        .replace("#include <common>", `#include <common>\nvarying vec2 vGW;\n${GROUND_GLSL}`)
        .replace("#include <beginnormal_vertex>", `vec4 gwp = modelMatrix * vec4(position, 1.0);
          float gh = groundHeight(gwp.x, gwp.z);
          vGW = gwp.xz;
          float ge = .55;
          float gxh = (groundHeight(gwp.x + ge, gwp.z) - groundHeight(gwp.x - ge, gwp.z)) / (2. * ge);
          float gzh = (groundHeight(gwp.x, gwp.z + ge) - groundHeight(gwp.x, gwp.z - ge)) / (2. * ge);
          #include <beginnormal_vertex>
          objectNormal = normalize(objectNormal + vec3(-gxh, gzh, 0.));`)
        .replace("#include <begin_vertex>", `#include <begin_vertex>\ntransformed.z += gh;`);
      // Baked surface variety: mossy patches, darker damp earth and leaf litter, no extra lights.
      shader.fragmentShader = shader.fragmentShader
        .replace("#include <common>", `#include <common>\nvarying vec2 vGW;`)
        .replace("#include <map_fragment>", `#include <map_fragment>
          float mossN = sin(vGW.x * .21 + sin(vGW.y * .17) * 2.) * sin(vGW.y * .19 - sin(vGW.x * .13) * 1.7);
          float litN = sin(vGW.x * 1.3 + vGW.y * .7) * sin(vGW.y * 1.1 - vGW.x * .5);
          float moss = smoothstep(.25, .75, mossN);
          float damp = smoothstep(.2, -.6, mossN);
          diffuseColor.rgb = mix(diffuseColor.rgb, diffuseColor.rgb * vec3(.72, 1.18, .74), moss * .6);
          diffuseColor.rgb *= 1. - damp * .3;
          diffuseColor.rgb = mix(diffuseColor.rgb, diffuseColor.rgb * vec3(1.25, 1.02, .78), smoothstep(.55, .95, litN) * .35);`);
    };
    const trunkMaterial = new THREE.MeshStandardMaterial({
      map: bark, normalMap: barkN, roughnessMap: barkR, normalScale: new THREE.Vector2(.75, .75), color: palette.trunk, roughness: .8, metalness: 0,
    });
    trunkMaterial.onBeforeCompile = shader => {
      shader.uniforms["uLights"] = glowUniforms.uLights;
      shader.uniforms["uRed"] = glowUniforms.uRed;
      // Baked contact darkening and moss at the trunk foot instead of dynamic shadows.
      shader.vertexShader = shader.vertexShader
        .replace("#include <common>", `#include <common>\nvarying float vTH;`)
        .replace("#include <begin_vertex>", `#include <begin_vertex>\nvTH = position.y;`);
      shader.fragmentShader = shader.fragmentShader
        .replace("#include <common>", `#include <common>\nvarying float vTH;\nuniform float uLights;\nuniform float uRed;`)
        .replace("#include <map_fragment>", `#include <map_fragment>
          float foot = 1. - smoothstep(1.0, 3.2, vTH);
          diffuseColor.rgb = mix(diffuseColor.rgb, diffuseColor.rgb * vec3(.75, 1.15, .72), foot * .55);
          diffuseColor.rgb *= mix(.45, 1., smoothstep(.9, 2.4, vTH)) * mix(1., .7, smoothstep(10., 18., vTH));`)
        .replace("#include <emissivemap_fragment>", `#include <emissivemap_fragment>
          // Soft wet-bark rim glint, only on trunks close to the runner.
          float near = 1. - smoothstep(4., 14., length(vViewPosition));
          float rim = pow(1. - abs(dot(normal, normalize(vViewPosition))), 3.);
          vec3 tint = mix(vec3(.18, .55, .35), vec3(.42, .26, .62), smoothstep(-.3, .3, sin(vTH * .35)));
          totalEmissiveRadiance += mix(tint, vec3(.95, .12, .06), uRed) * rim * near * .22 * smoothstep(.6, 2., vTH) * mix(uLights, .8, uRed);`);
    };
    // Bushes: a soft purple-green silhouette rim, as if caught by the canopy
    // glow and the headlamp, so undergrowth reads against the dark floor.
    const plantMaterial = new THREE.MeshStandardMaterial({ color: palette.plant, roughness: .9, metalness: 0 });
    plantMaterial.onBeforeCompile = shader => {
      shader.uniforms["uLights"] = glowUniforms.uLights;
      shader.uniforms["uRed"] = glowUniforms.uRed;
      shader.vertexShader = shader.vertexShader
        .replace("#include <common>", `#include <common>\nvarying vec3 vPN;\nvarying vec3 vPV;\nvarying vec3 vPW;`)
        .replace("#include <defaultnormal_vertex>", `#include <defaultnormal_vertex>\nvPN = normalize(transformedNormal);`)
        .replace("#include <project_vertex>", `#include <project_vertex>\nvPV = -mvPosition.xyz;\nvPW = (modelMatrix * instanceMatrix * vec4(transformed, 1.)).xyz;`);
      shader.fragmentShader = shader.fragmentShader
        .replace("#include <common>", `#include <common>\nvarying vec3 vPN;\nvarying vec3 vPV;\nvarying vec3 vPW;\nuniform float uLights;\nuniform float uRed;`)
        .replace("#include <emissivemap_fragment>", `#include <emissivemap_fragment>
          float pd = length(vPV);
          float prim = pow(1. - abs(dot(normalize(vPN), normalize(vPV))), 2.4);
          float pfar = smoothstep(3., 9., pd) * (1. - smoothstep(26., 40., pd));
          vec3 pTint = mix(vec3(.2, .55, .34), vec3(.5, .32, .7), .5 + .5 * sin(vPW.x * .27 + vPW.z * .19));
          totalEmissiveRadiance += mix(pTint, vec3(.95, .12, .06), uRed) * prim * (.18 + .3 * pfar) * mix(uLights, .8, uRed);`);
    };
    const crownMaterial = new THREE.MeshStandardMaterial({ color: palette.crown, roughness: .9, metalness: 0 });
    crownMaterial.onBeforeCompile = shader => {
      shader.uniforms["uLights"] = glowUniforms.uLights;
      shader.uniforms["uRed"] = glowUniforms.uRed;
      // Soft purple-green shards of light catching the leaf undersides — a
      // constant, gentle glow that sketches the canopy vault from below.
      shader.vertexShader = shader.vertexShader
        .replace("#include <common>", `#include <common>\nvarying vec3 vCN;\nvarying vec3 vCW;`)
        .replace("#include <defaultnormal_vertex>", `#include <defaultnormal_vertex>\nvCN = normalize(transformedNormal);`)
        .replace("#include <project_vertex>", `#include <project_vertex>\nvCW = (modelMatrix * instanceMatrix * vec4(transformed, 1.)).xyz;`);
      shader.fragmentShader = shader.fragmentShader
        .replace("#include <common>", `#include <common>\nvarying vec3 vCN;\nvarying vec3 vCW;\nuniform float uLights;\nuniform float uRed;`)
        .replace("#include <emissivemap_fragment>", `#include <emissivemap_fragment>
          float under = smoothstep(.15, -.75, vCN.y);
          float shard = .55 + .45 * sin(vCW.x * .8 + sin(vCW.z * .6) * 2.2) * sin(vCW.z * .7 - sin(vCW.x * .5) * 1.8);
          vec3 glowTint = mix(vec3(.16, .5, .3), vec3(.42, .25, .62), .5 + .5 * sin(vCW.x * .23 + vCW.z * .31));
          totalEmissiveRadiance += mix(glowTint, vec3(.95, .12, .06), uRed) * under * shard * .3 * mix(uLights, .8, uRed);`);
    };
    return { bark, barkN, barkR, floor, floorN, floorR, trunkGeometry, rootGeometry, branchGeometry, crownGeometry, rockGeometry, plantGeometry, groundMaterial, trunkMaterial, crownMaterial, plantMaterial, shadowGeometry: new THREE.PlaneGeometry(1, 1).rotateX(-Math.PI / 2), glowTexture: new THREE.CanvasTexture(canvas), sporeTexture: new THREE.CanvasTexture(sporeCanvas), dummy: new THREE.Object3D(), target: new THREE.Vector3() };
  }, [palette, glowUniforms]);
  useEffect(() => () => {
    [resources.trunkGeometry, resources.rootGeometry, resources.branchGeometry, resources.crownGeometry, resources.rockGeometry, resources.plantGeometry, resources.glowTexture, resources.sporeTexture, resources.groundMaterial, resources.trunkMaterial, resources.crownMaterial, resources.plantMaterial, resources.shadowGeometry].forEach(g => g.dispose());

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
      if (s.mode === "freeroam") {
        // Every secret sits in its own small clearing.
        const secrets = secretsAround(s.x, s.z);
        secretWorld.current = secrets;
        // Red gates need an open way through, too.
        const gates = gatesAround(s.x, s.z);
        gateWorld.current = gates;
        const open = (o: { x: number; z: number }) => secrets.every(e => Math.hypot(o.x - e.x, o.z - e.z) > SECRET_CLEARING)
          && gates.every(g => Math.hypot(o.x - g.x, o.z - g.z) > GATE_CLEARING);
        s.trees = s.trees.filter(open);
        s.obstacles = s.obstacles.filter(open);
      } else secretWorld.current = [];
      slotMap.current.clear();
      pristine.current.clear();
      const branchMesh = branches.current, crownMesh = crowns.current, rockMesh = rocks.current, plantMesh = plants.current;
      s.trees.forEach((tree, i) => {
        const seedX = Math.floor(tree.x), seedZ = Math.floor(tree.z);
        const base = groundHeight(tree.x, tree.z) - 1.1;
        resources.dummy.rotation.order = "XYZ";
        // Four species: slender beech, V-forked, gnarled old oak, thin leaning sapling.
        const species = Math.floor(hash(seedX, seedZ, 200) * 4);
        const girth = ([.8, .9, 1.15, .68][species] ?? 1) * tree.scale;
        const leanAmt = species === 3 ? .07 : species === 2 ? .03 : .015;
        const leanDir = hash(seedX, seedZ, 201) * Math.PI * 2;
        const lx = Math.cos(leanDir) * leanAmt, lz = Math.sin(leanDir) * leanAmt;
        // Horizontal drift of the trunk axis at a given height above the base.
        const ox = (h: number) => -h * lz, oz = (h: number) => h * lx;
        resources.dummy.position.set(tree.x, base, tree.z);
        resources.dummy.scale.set(girth, 1, girth);
        resources.dummy.rotation.set(lx, hash(seedX, seedZ, 5) * 6, lz);
        resources.dummy.updateMatrix();
        trunkMesh.setMatrixAt(i, resources.dummy.matrix);
        // Soft dark contact patch where the trunk meets the ground.
        resources.dummy.position.set(tree.x, groundHeight(tree.x, tree.z) + .05, tree.z);
        resources.dummy.scale.set(3.4 * tree.scale, 1, 3.4 * tree.scale);
        resources.dummy.updateMatrix();
        shadows.current?.setMatrixAt(i, resources.dummy.matrix);
        for (let r = 0; r < ROOTS_PER_TREE; r++) {
          const ang = (r / ROOTS_PER_TREE) * Math.PI * 2 + hash(seedX, seedZ, 210 + r) * .9;
          const rx = tree.x + Math.cos(ang) * .2 * girth, rz = tree.z - Math.sin(ang) * .2 * girth;
          resources.dummy.position.set(rx, groundHeight(rx, rz) + .1, rz);
          resources.dummy.rotation.set(0, ang, -.12 - hash(seedX, seedZ, 220 + r) * .15);
          const rl = (.7 + hash(seedX, seedZ, 230 + r) * .7) * (species === 2 ? 1.35 : 1);
          resources.dummy.scale.set(rl * girth * 1.2, girth * 1.3, girth * 1.3);
          resources.dummy.updateMatrix();
          roots.current?.setMatrixAt(i * ROOTS_PER_TREE + r, resources.dummy.matrix);
        }
        for (let b = 0; b < BRANCHES_PER_TREE; b++) {
          const seed = hash(seedX, seedZ, 20 + b);
          // First branches fan up into the crown; the rest stick out from the trunk lower down.
          const upper = b < 5;
          const fork = species === 1 && b < 2;
          let top = upper ? base + 14.5 + hash(seedX, seedZ, 60 + b) * 2.5 : base + 8 + hash(seedX, seedZ, 60 + b) * 6.5;
          if (fork) top = base + 7 + hash(seedX, seedZ, 61) * 1.5;
          const forkYaw = hash(seedX, seedZ, 202) * Math.PI * 2 + b * Math.PI;
          const yaw = fork ? forkYaw : seed * Math.PI * 2;
          resources.dummy.position.set(tree.x + ox(top - base), top, tree.z + oz(top - base));
          resources.dummy.rotation.order = "YXZ";
          const tilt = fork ? .32 + hash(seedX, seedZ, 41 + b) * .12 : upper ? .55 + hash(seedX, seedZ, 40 + b) * .75 : 1.05 + hash(seedX, seedZ, 40 + b) * .5;
          resources.dummy.rotation.set(tilt, yaw, fork ? 0 : (hash(seedX, seedZ, 80 + b) - .5) * .4);
          const thick = fork ? 4.2 : upper ? 1 : 2.2 + hash(seedX, seedZ, 90 + b) * 1.4;
          const len = fork ? 3.6 : upper ? .7 + seed * .6 : 1.1 + hash(seedX, seedZ, 95 + b) * 1.1;
          resources.dummy.scale.set(tree.scale * thick, tree.scale * len, tree.scale * thick);
          resources.dummy.updateMatrix();
          branchMesh?.setMatrixAt(i * BRANCHES_PER_TREE + b, resources.dummy.matrix);
          // Every branch ends in foliage — no bare wood anywhere in the forest.
          const reach = 2.2 * tree.scale * len;
          resources.dummy.position.set(
            tree.x + ox(top - base) + reach * Math.sin(tilt) * Math.sin(yaw),
            top + reach * Math.cos(tilt),
            tree.z + oz(top - base) + reach * Math.sin(tilt) * Math.cos(yaw));
          resources.dummy.rotation.set((hash(seedX, seedZ, 83 + b) - .5) * .9, seed * Math.PI * 2, (hash(seedX, seedZ, 77 + b) - .5) * .9);
          const cr = .9 + hash(seedX, seedZ, 120 + b) * 1.1;
          resources.dummy.scale.set(cr * tree.scale, cr * (.5 + hash(seedX, seedZ, 122 + b) * .3) * tree.scale, cr * tree.scale);
          resources.dummy.updateMatrix();
          crownMesh?.setMatrixAt(i * CROWN_SLOTS + CROWNS_PER_TREE + b, resources.dummy.matrix);
        }
        resources.dummy.rotation.order = "XYZ";
        for (let c = 0; c < CROWNS_PER_TREE; c++) {
          // Overlapping, irregular leaf masses, low enough and wide enough to merge with neighbours.
          const spread = c === 0 ? .5 : 2.6 + hash(seedX, seedZ, 81 + c) * 3.6;
          const angle = hash(seedX, seedZ, 74 + c) * Math.PI * 2;
          resources.dummy.position.set(tree.x + ox(16) + Math.cos(angle) * spread, base + 15.2 + hash(seedX, seedZ, 71 + c * 3) * 2.0, tree.z + oz(16) + Math.sin(angle) * spread);
          resources.dummy.rotation.set((hash(seedX, seedZ, 83 + c) - .5) * .9, angle, (hash(seedX, seedZ, 77 + c) - .5) * .9);
          const r = (c === 0 ? 3.9 : 2.8) + hash(seedX, seedZ, 73 + c) * 2.0;
          resources.dummy.scale.set(r * (.8 + hash(seedX, seedZ, 85 + c) * .6) * tree.scale, r * (.45 + hash(seedX, seedZ, 87 + c) * .4) * tree.scale, r * (.7 + hash(seedX, seedZ, 79 + c) * .6) * tree.scale);
          resources.dummy.updateMatrix();
          crownMesh?.setMatrixAt(i * CROWN_SLOTS + c, resources.dummy.matrix);
        }
      });
      s.trees.forEach((tree, i) => {
        const slots: [THREE.InstancedMesh, number][] = [[trunkMesh, i]];
        if (shadows.current) slots.push([shadows.current, i]);
        for (let r = 0; r < ROOTS_PER_TREE; r++) if (roots.current) slots.push([roots.current, i * ROOTS_PER_TREE + r]);
        for (let b = 0; b < BRANCHES_PER_TREE; b++) if (branchMesh) slots.push([branchMesh, i * BRANCHES_PER_TREE + b]);
        for (let c = 0; c < CROWN_SLOTS; c++) if (crownMesh) slots.push([crownMesh, i * CROWN_SLOTS + c]);
        slotMap.current.set(tree.id, slots);
      });
      trunkMesh.count = s.trees.length;
      if (branchMesh) branchMesh.count = s.trees.length * BRANCHES_PER_TREE;
      if (roots.current) { roots.current.count = s.trees.length * ROOTS_PER_TREE; roots.current.instanceMatrix.needsUpdate = true; }
      if (crownMesh) crownMesh.count = s.trees.length * CROWN_SLOTS;
      let rockCount = 0, plantCount = 0;
      // Lowest ground point under the footprint, so nothing floats on a slope.
      const footGround = (x: number, z: number, r: number) => Math.min(groundHeight(x, z),
        groundHeight(x + r, z), groundHeight(x - r, z), groundHeight(x, z + r), groundHeight(x, z - r));
      s.obstacles.forEach((o, i) => {
        const gy = footGround(o.x, o.z, o.big ? o.radius * 1.3 : .7);
        resources.dummy.rotation.order = "XYZ";
        resources.dummy.rotation.set(0, hash(Math.floor(o.x), Math.floor(o.z), 80) * 6, 0);
        if (o.big) {
          resources.dummy.position.set(o.x, gy + o.radius * .08, o.z);
          resources.dummy.scale.set(o.radius * 1.35, o.radius * (.55 + hash(Math.floor(o.x), Math.floor(o.z), 82) * .35), o.radius * 1.15);
          resources.dummy.updateMatrix();
          rockMesh?.setMatrixAt(rockCount, resources.dummy.matrix);
          if (rockMesh && shadows.current) slotMap.current.set(o.id, [[rockMesh, rockCount], [shadows.current, s.trees.length + rockCount]]);
          resources.dummy.position.set(o.x, groundHeight(o.x, o.z) + .05, o.z);
          resources.dummy.scale.set(o.radius * 3.2, 1, o.radius * 3);
          resources.dummy.updateMatrix();
          shadows.current?.setMatrixAt(s.trees.length + rockCount, resources.dummy.matrix);
          rockCount++;
        } else {
          resources.dummy.position.set(o.x, gy - .05, o.z);
          const bs = .85 + hash(Math.floor(o.x), Math.floor(o.z), 83) * .55;
          resources.dummy.scale.set(.8 * bs, (.4 + hash(Math.floor(o.x), Math.floor(o.z), 81) * .35) * bs, .8 * bs);
          resources.dummy.updateMatrix();
          if (plantMesh) slotMap.current.set(o.id, [[plantMesh, plantCount]]);
          plantMesh?.setMatrixAt(plantCount++, resources.dummy.matrix);
        }
      });
      // Anything the shockwave already dissolved stays gone across rebuilds.
      for (const id of s.cleared) {
        if (s.elapsed - (s.clearedAt[id] ?? -99) < SHOCKWAVE_DISSOLVE) continue;
        slotMap.current.get(id)?.forEach(([mesh, index]) => mesh.setMatrixAt(index, ZERO));
      }
      if (shadows.current) { shadows.current.count = s.trees.length + rockCount; shadows.current.instanceMatrix.needsUpdate = true; }
      if (rockMesh) { rockMesh.count = rockCount; rockMesh.instanceMatrix.needsUpdate = true; }
      if (plantMesh) { plantMesh.count = plantCount; plantMesh.instanceMatrix.needsUpdate = true; }
      trunkMesh.instanceMatrix.needsUpdate = true;
      if (branchMesh) branchMesh.instanceMatrix.needsUpdate = true;
      if (crownMesh) crownMesh.instanceMatrix.needsUpdate = true;
    }
    stepSimulation(s, getSteering(input.current.touchMode, input.current.touch, input.current.tilt), raw);
    if (s.phase !== previousPhase.current) {
      if (s.phase === "awakening" || s.phase === "menu") {
        orbWorld.current = [];
        lastOrbSpawn.current = 0;
      }
      previousPhase.current = s.phase; onPhase(s.phase, Math.floor(s.distance));
    }
    if (s.impact > 0 && lastImpact.current <= 0) onImpact(s.impactSide);
    lastImpact.current = s.impact;
    if (s.stumble > 0 && lastStumble.current <= 0) onStumble();
    lastStumble.current = s.stumble;
    // Shockwave: cleared trees and undergrowth are shoved into the depth and torn out.
    for (const id of s.cleared) {
      const age = s.elapsed - (s.clearedAt[id] ?? -99);
      const slots = slotMap.current.get(id);
      if (!slots || age < 0 || age > SHOCKWAVE_DISSOLVE + .1) continue;
      let saved = pristine.current.get(id);
      if (!saved) {
        saved = slots.map(([mesh, index]) => { const m = new THREE.Matrix4(); mesh.getMatrixAt(index, m); return m; });
        pristine.current.set(id, saved);
      }
      const k = Math.min(1, age / SHOCKWAVE_DISSOLVE);
      const seed = id.length * 31 + (id.charCodeAt(id.length - 1) || 0);
      // The wave shoves it forward into the depth with growing speed, tips it
      // over and rips it up out of the ground; only at the very end it is gone.
      const fx = -Math.sin(s.heading), fz = -Math.cos(s.heading);
      const push = Math.pow(k, 1.4) * (34 + hash(seed, 7, 404) * 16);
      const lift = Math.sin(Math.min(1, k * 2.6) * Math.PI * .5) * (1.6 + hash(seed, 8, 405) * 2.2) * Math.min(1, k * 1.8);
      const tip = Math.min(1, k * 1.8) * (.7 + hash(seed, 9, 406) * .7);
      const shrink = 1 - THREE.MathUtils.smoothstep(k, .8, 1);
      const [first] = slots;
      if (!first) continue;
      tmpM.copy(saved[0] ?? tmpM);
      const bx = tmpM.elements[12] ?? 0, by = tmpM.elements[13] ?? 0, bz = tmpM.elements[14] ?? 0;
      tmpG.makeTranslation(bx + fx * push, by + lift, bz + fz * push)
        .multiply(new THREE.Matrix4().makeRotationAxis(new THREE.Vector3(fz, 0, -fx), tip))
        .multiply(new THREE.Matrix4().makeScale(shrink, shrink, shrink))
        .multiply(new THREE.Matrix4().makeTranslation(-bx, -by, -bz));
      slots.forEach(([mesh, index], n) => {
        const base = saved?.[n];
        if (!base) return;
        if (k >= 1) mesh.setMatrixAt(index, ZERO);
        else mesh.setMatrixAt(index, tmpM.multiplyMatrices(tmpG, base));
        mesh.instanceMatrix.needsUpdate = true;
      });
    }
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
    const running = s.phase === "running";
    const steering = getSteering(input.current.touchMode, input.current.touch, input.current.tilt);
    // Menu and lying phases: no step rhythm, only a barely noticeable breathing sway.
    const leanTarget = lying ? Math.sin(t * .13) * .035 : steering;
    // Smooth half-wave step bob: rises and falls softly, no sharp bounce cusps.
    // s.stride advances with the pace, so the rhythm speeds up as the run does.
    const bob = running ? (Math.sin(s.stride) * .5 + .5) * .03 : Math.sin(t * .4) * .012;
    // A gentle lateral head translation carries the stride; keep the horizon
    // almost level rather than swinging the whole forest around the viewer.
    const lateralStep = running ? Math.sin(s.stride * .5) * .09 : 0;
    const stepX = Math.cos(s.heading) * lateralStep;
    const stepZ = -Math.sin(s.heading) * lateralStep;
    lean.current += (leanTarget - lean.current) * Math.min(1, raw * (running ? 15 : 2));
    camera.position.set(
      s.x + fall * .45 + leanAmount * .3 + stepX,
      groundHeight(s.x, s.z) + 1.6 - fall * 1.28 + bob,
      s.z + fall * .65 + stepZ);
    // Menu: slow, damped horizontal look-around on tilt or a finger drag;
    // during a run it settles back to the running heading.
    const canLook = s.phase === "menu" || s.phase === "fallen" || s.pause !== "none";
    const lookTarget = canLook ? steering * .5 : 0;
    menuLook.current += (lookTarget - menuLook.current) * Math.min(1, raw * (canLook ? 1.4 : 6));
    const viewHeading = s.heading + menuLook.current;
    resources.target.set(
      s.x - Math.sin(viewHeading) * 18 + stepX,
      groundHeight(s.x, s.z) * .6 + groundHeight(s.x - Math.sin(viewHeading) * 18, s.z - Math.cos(viewHeading) * 18) * .4 + 1.62 + fall * 3,
      s.z - Math.cos(viewHeading) * 18 + stepZ);
    camera.lookAt(resources.target);
    camera.rotateX(bump * .085 + waking * Math.sin(t * 5) * .045 + fall * .065 * Math.sin(t * .32) + tripping * Math.sin(t * 17) * .085 + (running ? Math.sin(s.stride) * .006 : 0));
    camera.rotateY(-leanAmount * .06);
    camera.rotateZ(-leanAmount * .065 + bump * .12 + fall * .72 + waking * Math.sin(t * 4) * .03 + (running ? Math.sin(s.stride * .5) * .007 : Math.sin(t * .4) * .008) + tripping * Math.sin(t * 13) * .07);
    if (lamp.current) {
      // Headlamp rides the step rhythm and leads gently into turns, like a
      // real lamp strapped to a turning head.
      const swayTarget = running ? -steering * .55 : 0;
      lampSway.current += (swayTarget - lampSway.current) * Math.min(1, raw * 4);
      const stepSway = running ? Math.sin(s.stride) * .09 : 0;
      const rx = Math.cos(viewHeading), rz = -Math.sin(viewHeading);
      lamp.current.position.set(
        camera.position.x + rx * (lampSway.current + stepSway),
        camera.position.y + .35 + (running ? (Math.sin(s.stride) * .5 + .5) * .05 : 0),
        camera.position.z + rz * (lampSway.current + stepSway));
      const flicker = lying ? (Math.sin(t * 5.7) + Math.sin(t * 13.2) > .25 ? .14 : .035) : 1 - waking * .8;
      lamp.current.intensity = 65 * flicker;
    }
    if (spores.current) {
      // Spores live in the world, scattered deep along the path: the runner
      // approaches and passes them, and any left behind respawn far ahead.
      const world = sporeWorld.current;
      if (world.length === 0) {
        for (let i = 0; i < SPORE_COUNT; i++) {
          world.push(placeSpore(s.x, s.z, i, 0, true));
        }
      }
      const sh = Math.sin(s.heading), ch = Math.cos(s.heading);
      const ember = surviveStage(s) === "red";
      spores.current.children.forEach((child, i) => {
        const sprite = child as THREE.Sprite;
        const seed = i * 7.13;
        const spore = world[i];
        if (!spore) return;
        // Recycle relative to the facing direction, so freeroam works any way round.
        const wx = spore.x - s.x, wz = spore.z - s.z;
        const side = wx * ch - wz * sh, back = wx * sh + wz * ch;
        if (back > 18 || Math.abs(side) > 55) {
          const fresh = placeSpore(s.x, s.z, i, Math.floor(s.distance));
          const dx = fresh.x - s.x, dz = fresh.z - s.z;
          Object.assign(spore, { x: s.x + dx * ch + dz * sh, z: s.z - dx * sh + dz * ch, h: fresh.h });
        }
        const mx = spore.x + Math.sin(t * .23 + seed * 2) * .6;
        const mz = spore.z + Math.cos(t * .19 + seed) * .6;
        sprite.position.set(mx, groundHeight(mx, mz) + spore.h + Math.sin(t * .5 + seed) * .25, mz);
        const dist = Math.hypot(mx - s.x, mz - s.z);
        const twinkle = .5 + .5 * Math.sin(t * (.35 + hash(i, 2, 8) * .35) + seed * 3);
        const mat = sprite.material as THREE.SpriteMaterial;
        // Survive mode: from the red onset every mote is a glowing ember of its own.
        mat.opacity = Math.min(1, sporeOpacity(dist, twinkle) * (ember ? 1.9 : 1));
        sprite.scale.setScalar((.14 + hash(i, 4, 9) * .12) * (ember ? 1.25 : 1));
        if (!sprite.userData["baseColor"]) sprite.userData["baseColor"] = mat.color.clone();
        mat.color.copy(ember ? palette.ember : sprite.userData["baseColor"] as THREE.Color);
      });
    }
    // Survive sequence: lights die (forest hum dulls), a dark tense moment,
    // then the threat sound and the red pursuer light arrive together.
    const stage = surviveStage(s);
    if (stage !== stageRef.current) {
      if (stage === "dying") onDark();
      if (stage === "red") onThreat();
      // A second gate switches the atmosphere off again: the forest hum returns.
      if (stage === "lit" && stageRef.current !== "lit") onLit();
      stageRef.current = stage;
    }
    const redOn = stage === "red";
    const redGoal = redOn ? (lying ? 1 : .8 + s.threat * .2) : 0;
    // Fast attack so the flood hits on the sound; gentle settle afterwards.
    redMix.current += (redGoal - redMix.current) * Math.min(1, raw * (redGoal > redMix.current ? 9 : 2.5));
    // The canopy's green and purple light dies with the lights and never comes
    // back as green/purple while the pursuer's red takes over the same surfaces.
    const dieTarget = s.surviveTime >= 0 ? Math.min(1, s.surviveTime / LIGHTS_DIE) : 0;
    dieRef.current = dieTarget >= dieRef.current ? dieTarget : dieRef.current + (dieTarget - dieRef.current) * Math.min(1, raw * 1.2);
    glowUniforms.uLights.value = 1 - dieRef.current;
    glowUniforms.uRed.value = redMix.current;
    const behindX = Math.sin(s.heading), behindZ = Math.cos(s.heading);
    if (redLight.current) {
      redLight.current.position.set(s.x + behindX * 5, groundHeight(s.x, s.z) + 3.4, s.z + behindZ * 5);
      redLight.current.intensity = redMix.current * (lying ? 140 : 95 + s.threat * 90);
    }
    if (redFlood.current) {
      // A searchlight from behind: every face turned toward the runner glows red.
      redFlood.current.position.set(s.x + behindX * 30, groundHeight(s.x, s.z) + 9, s.z + behindZ * 30);
      redTarget.position.set(s.x - behindX * 20, groundHeight(s.x, s.z) + 1, s.z - behindZ * 20);
      redTarget.updateMatrixWorld();
      redFlood.current.intensity = redMix.current * (lying ? 3.2 : 2.6 + s.threat * 2.2);
    }
    if (orbs.current) {
      const world = orbWorld.current;
      // Wisps appear only deep into the run, rare and alone — never while one
      // is still drifting ahead or a power is active.
      const live = world.filter(o => o.taken == null);
      if (canSpawnPowerUp(s, lastOrbSpawn.current, live.length > 0)) {
        const index = Math.floor(s.distance / POWERUP_GAP);
        const p = placePowerUp(s.x, s.z, index, Math.floor(s.distance), s.heading);
        if (p) { world.push(p); lastOrbSpawn.current = s.distance; }
      }
      orbs.current.children.forEach((slot, i) => {
        const orb = world[i];
        const core = slot.children[0] as THREE.Mesh | undefined;
        const aura = slot.children[1] as THREE.Sprite | undefined;
        if (!core || !aura) return;
        if (!orb) { slot.visible = false; return; }
        slot.visible = true;
        const orbColor = palette[ORB_KEY[orb.type]];
        const coreMat = core.material as THREE.MeshStandardMaterial;
        if (!coreMat.emissive.equals(orbColor)) {
          coreMat.color.copy(orbColor);
          coreMat.emissive.copy(orbColor);
          (aura.material as THREE.SpriteMaterial).color.copy(orbColor);
        }
        const gy = groundHeight(orb.x, orb.z) + 1.15;
        if (orb.taken != null) {
          // Pickup: the wisp flows into the runner's chest and dissolves.
          const k = Math.min(1, (s.elapsed - orb.taken) / .25);
          core.position.lerp(camera.position, k * .6);
          core.position.y = THREE.MathUtils.lerp(core.position.y, camera.position.y - .35, k);
          core.scale.setScalar(Math.max(.001, .42 * (1 - k)));
          aura.position.copy(core.position);
          aura.scale.setScalar(Math.max(.001, 2.8 * (1 - k)));
          (aura.material as THREE.SpriteMaterial).opacity = .5 * (1 - k);
          if (k >= 1) { world.splice(world.indexOf(orb), 1); slot.visible = false; }
          return;
        }
        core.position.set(orb.x, gy + Math.sin(t * 1.3 + i * 2) * .08, orb.z);
        core.rotation.y = t * .8;
        core.scale.setScalar(.42 + Math.sin(t * 2.1 + i) * .035);
        aura.position.copy(core.position);
        aura.scale.setScalar(2.8 + Math.sin(t * 1.7 + i) * .28);
        (aura.material as THREE.SpriteMaterial).opacity = .6 + Math.sin(t * 1.7 + i) * .12;
        // Left behind or collected by the simulation.
        if (Math.hypot(orb.x - s.x, orb.z - s.z) > 70) { world.splice(world.indexOf(orb), 1); slot.visible = false; return; }
        if (collectPowerUp(s, orb)) { orb.taken = s.elapsed; onPowerUp(orb.type); }
      });
      // The moment a power fades, the frame glow and the haptic follow.
      if (lastPowerUp.current && !s.powerUp) onPowerUpEnd();
      lastPowerUp.current = s.powerUp;
      // Ghost wisp: trunks, roots and branches turn into pale, softly glowing,
      // see-through ghosts (the crowns only slightly), fading in and out.
      const ghostLeft = POWERUP_DURATION - s.powerUpTime;
      const ghostTarget = s.powerUp === "ghost" ? THREE.MathUtils.smootherstep(ghostLeft, 0, 1.6) : 0;
      ghostK.current += (ghostTarget - ghostK.current) * Math.min(1, raw * (ghostTarget > ghostK.current ? 7 : 5));
      const g = ghostK.current < .01 ? 0 : ghostK.current;
      ([[resources.trunkMaterial, .34, .55], [branchMat.current, .34, .55], [resources.crownMaterial, .16, .2]] as const).forEach(([mat, fade, glow]) => {
        if (!mat) return;
        const on = g > 0;
        // Flipping transparency needs a recompile, otherwise three.js keeps the opaque shader and ignores opacity.
        if (mat.transparent !== on) { mat.transparent = on; mat.depthWrite = !on; mat.needsUpdate = true; }
        mat.opacity = 1 - g * fade;
        mat.emissive.set(GHOST_GLOW);
        mat.emissiveIntensity = g * glow;
      });
    }
    if (gateGroup.current) {
      const gates = s.mode === "freeroam" ? gateWorld.current : [];
      gateGroup.current.children.forEach((slot, i) => {
        const gate = gates[i];
        slot.visible = !!gate;
        if (!gate) return;
        slot.position.set(gate.x, groundHeight(gate.x, gate.z), gate.z);
        slot.rotation.y = gate.angle;
        const veil = slot.children[0] as THREE.Mesh | undefined;
        if (veil) (veil.material as THREE.MeshBasicMaterial).opacity = .16 + Math.sin(t * 1.7 + i * 2) * .06;
        if (passGate(s, gate)) onGate();
      });
    }
    if (secretGroup.current) {
      const list = secretWorld.current;
      secretGroup.current.children.forEach((slot, i) => {
        const secret = list[i];
        slot.visible = !!secret;
        if (!secret) return;
        const found = s.found.includes(secret.id);
        if (discoverSecret(s, secret)) onSecret(secret);
        slot.position.set(secret.x, groundHeight(secret.x, secret.z), secret.z);
        slot.children.forEach((variant, v) => {
          variant.visible = ["monolith", "ring", "lantern"][v] === secret.type;
        });
        const pulse = (found ? 1.4 : 1) * (.8 + Math.sin(t * 1.6 + i) * .2);
        slot.traverse(o => {
          if (o instanceof THREE.Sprite) {
            const base = (o.userData["base"] as number | undefined) ?? (o.userData["base"] = o.material.opacity);
            o.material.opacity = Math.min(1, base * pulse * (secret.type === "lantern" ? .85 + Math.sin(t * 9 + i) * .15 : 1));
          }
        });
        const ringGroup = slot.children[1];
        if (ringGroup) ringGroup.rotation.y = t * .25;
      });
    }
    if (mist.current) {
      // Low layered mist drifting between the trunks ahead; a few large soft sprites only.
      mist.current.children.forEach((child, i) => {
        const d = 10 + i * 6;
        const a = s.heading + Math.sin(t * .05 + i * 2.1) * .5 + (i % 2 ? .35 : -.35);
        const mx = s.x - Math.sin(a) * d, mz = s.z - Math.cos(a) * d;
        child.position.set(mx, groundHeight(mx, mz) + .7 + i * .15, mz);
        child.scale.set(14 + i * 3, 3.2, 1);
      });
    }
    if (glow.current) {
      // Freeroam: lights stay anchored to world cells and move only when the
      // cell changes. Classic and survive: one static light that keeps a fixed
      // offset from the runner and ignores the heading (turning swings it).
      const free = s.mode === "freeroam";
      const cell = free ? `free:${Math.floor(s.x / 84)},${Math.floor(s.z / 84)}` : "fixed";
      if (!free || cell !== glowCell.current) {
        glowCell.current = cell;
        const lights = forestLightsFor(s.mode, s.x, s.z);
        glow.current.children.forEach((cluster, i) => {
          const light = lights[i];
          cluster.visible = !!light;
          if (light) cluster.position.set(light.x, groundHeight(light.x, light.z) + 4.2, light.z);
        });
      }
      // Survive mode: the distant lights stutter and die one after another.
      const die = dieRef.current;
      glow.current.children.forEach(cluster => cluster.children.forEach((child, i) => {
        const sprite = child as THREE.Sprite;
        // Staggered: each light gutters out a little after the previous one.
        const own = Math.min(1, Math.max(0, die * 1.6 - i * .15));
        const flick = own > 0 && own < 1 ? (Math.sin(s.elapsed * (26 + i * 7)) > .2 ? 1 : .1) : 1;
        (sprite.material as THREE.SpriteMaterial).opacity = (sprite.userData["base"] as number) * (1 - own) * flick;
        const high = i > 2;
        sprite.scale.setScalar((high ? 30 : i === 0 ? 24 : 20) * (1 + Math.sin(t * .4 + i) * .08));
        if (high) {
          // Blurred light bleeding through the canopy, far above the runners head.
          sprite.position.set((i === 3 ? -3.5 : 3.5) + Math.sin(t * .2 + i) * 2, 13.5 + Math.sin(t * .24 + i) * 1.4, 0);
        } else {
          sprite.position.x = (i === 0 ? -5 : i === 1 ? 5 : 0) + Math.sin(t * .16 + i) * 1.5;
        }
      }));
    }
  });
  return <>
    <pointLight ref={lamp} color={palette.lamp} intensity={65} distance={27} decay={1.6} />
    <pointLight ref={redLight} color={palette.pursuer} intensity={0} distance={70} decay={1.1} />
    <directionalLight ref={redFlood} color={palette.pursuer} intensity={0} target={redTarget} userData={{ survive: true }} />
    <primitive object={redTarget} />
    <group ref={gateGroup}>
      {Array.from({ length: SECRET_SLOTS }, (_, i) => (
        <group key={i} visible={false}>
          {/* The veil: a faint red curtain filling the opening. */}
          <mesh position={[0, 2.2, 0]}>
            <planeGeometry args={[GATE_HALF * 2, 4.4]} />
            <meshBasicMaterial color={palette.pursuer} transparent opacity={.16} blending={THREE.AdditiveBlending} depthWrite={false} side={THREE.DoubleSide} />
          </mesh>
          {/* Two dark posts and a lintel with glowing red seams. */}
          {[-1, 1].map(side => (
            <mesh key={side} position={[side * (GATE_HALF + .2), 2.1, 0]}>
              <boxGeometry args={[.4, 4.6, .4]} />
              <meshStandardMaterial color={palette.rock} roughness={.4} metalness={.2} emissive={palette.ember} emissiveIntensity={.9} />
            </mesh>
          ))}
          <mesh position={[0, 4.5, 0]}>
            <boxGeometry args={[GATE_HALF * 2 + .8, .4, .4]} />
            <meshStandardMaterial color={palette.rock} roughness={.4} metalness={.2} emissive={palette.ember} emissiveIntensity={.9} />
          </mesh>
          {/* A red beacon, visible through the fog from far away. */}
          <sprite position={[0, 3, 0]} scale={[16, 16, 1]}>
            <spriteMaterial map={resources.glowTexture} color={palette.ember} transparent opacity={.32} blending={THREE.AdditiveBlending} depthWrite={false} fog={false} />
          </sprite>
        </group>
      ))}
    </group>
    <group ref={secretGroup}>
      {Array.from({ length: SECRET_SLOTS }, (_, i) => (
        <group key={i} visible={false}>
          <group>
            {/* The Silent Monolith: a dark slab with a glowing rune seam. */}
            <mesh position={[0, 2, 0]} rotation-y={.4}>
              <boxGeometry args={[1, 4.4, .35]} />
              <meshStandardMaterial color={palette.rock} roughness={.35} metalness={.2} emissive={palette.secret} emissiveIntensity={.08} />
            </mesh>
            <mesh position={[.07, 2.1, .2]} rotation-y={.4}>
              <boxGeometry args={[.06, 3.2, .02]} />
              <meshBasicMaterial color={palette.secret} />
            </mesh>
            <sprite position={[0, 2.4, 0]} scale={[5, 7, 1]}>
              <spriteMaterial map={resources.glowTexture} color={palette.secret} transparent opacity={.35} blending={THREE.AdditiveBlending} depthWrite={false} />
            </sprite>
          </group>
          <group>
            {/* The Fairy Ring: glowing caps circling a patch of moss. */}
            {Array.from({ length: 11 }, (_, m) => {
              const a = (m / 11) * Math.PI * 2;
              return (
                <group key={m} position={[Math.cos(a) * 2.6, 0, Math.sin(a) * 2.6]}>
                  <mesh position={[0, .2, 0]} scale={[.16, .1, .16]}>
                    <sphereGeometry args={[1, 10, 6]} />
                    <meshBasicMaterial color={palette.green} />
                  </mesh>
                  <sprite position={[0, .3, 0]} scale={[.9, .9, 1]}>
                    <spriteMaterial map={resources.glowTexture} color={palette.green} transparent opacity={.7} blending={THREE.AdditiveBlending} depthWrite={false} />
                  </sprite>
                </group>
              );
            })}
          </group>
          <group>
            {/* The Lost Lantern: a crooked post with a warm, guttering light. */}
            <mesh position={[0, .8, 0]} rotation-z={.08}>
              <cylinderGeometry args={[.05, .07, 1.6, 6]} />
              <meshStandardMaterial color={palette.trunk} roughness={.9} />
            </mesh>
            <mesh position={[.06, 1.7, 0]}>
              <boxGeometry args={[.24, .3, .24]} />
              <meshBasicMaterial color={palette.lamp} />
            </mesh>
            <sprite position={[.06, 1.7, 0]} scale={[3.2, 3.2, 1]}>
              <spriteMaterial map={resources.glowTexture} color={palette.lamp} transparent opacity={.75} blending={THREE.AdditiveBlending} depthWrite={false} />
            </sprite>
          </group>
        </group>
      ))}
    </group>
    <group ref={orbs}>
      {Array.from({ length: ORB_SLOTS }, (_, i) => (
        <group key={i} visible={false}>
          <mesh>
            <icosahedronGeometry args={[1, 1]} />
            <meshStandardMaterial color={palette.freezer} emissive={palette.freezer} emissiveIntensity={1.6} roughness={.2} />
          </mesh>
          <sprite>
            <spriteMaterial map={resources.glowTexture} color={palette.freezer} transparent opacity={.5} depthWrite={false} blending={THREE.AdditiveBlending} />
          </sprite>
        </group>
      ))}
    </group>
    <group ref={glow}>
      {Array.from({ length: 9 }, (_, cluster) => <group key={cluster}>
        {[palette.green, palette.purple, palette.lavender, palette.purple, palette.lavender].map((color, i) => (
          <sprite key={i} renderOrder={-1} userData={{ base: i > 2 ? .14 : .66 }}>
            <spriteMaterial map={resources.glowTexture} color={color} transparent opacity={i > 2 ? .14 : .66} blending={THREE.AdditiveBlending} depthWrite={false} fog={false} />
          </sprite>
        ))}
      </group>)}
    </group>
    <group ref={mist}>
      {[0, 1, 2, 3].map(i => (
        <sprite key={i}>
          <spriteMaterial map={resources.glowTexture} color={palette.moon} transparent opacity={.07} depthWrite={false} />
        </sprite>
      ))}
    </group>
    <group ref={spores}>
      {Array.from({ length: SPORE_COUNT }, (_, i) => {
        // Mostly lemon-yellow motes; purple and green stay rare accents.
        const roll = hash(i, 3, 10);
        return (
          <sprite key={i}>
            <spriteMaterial map={resources.sporeTexture} color={roll < .08 ? palette.purple : roll < .16 ? palette.green : palette.lemon} transparent opacity={.2} blending={THREE.AdditiveBlending} depthWrite={false} fog={false} />
          </sprite>
        );
      })}
    </group>
    <instancedMesh ref={shadows} args={[resources.shadowGeometry, undefined, MAX_INSTANCES * 2]} frustumCulled={false} renderOrder={1}>
      <meshBasicMaterial map={resources.glowTexture} color="black" transparent opacity={.6} depthWrite={false} polygonOffset polygonOffsetFactor={-2} />
    </instancedMesh>
    <mesh ref={ground} rotation-x={-Math.PI / 2} material={resources.groundMaterial}>
      <planeGeometry args={[200, 200, 160, 160]} />
    </mesh>
    <instancedMesh ref={trunks} args={[resources.trunkGeometry, undefined, MAX_INSTANCES]} material={resources.trunkMaterial} frustumCulled={false} />
    <instancedMesh ref={roots} args={[resources.rootGeometry, undefined, MAX_INSTANCES * ROOTS_PER_TREE]} material={resources.trunkMaterial} frustumCulled={false} />
    <instancedMesh ref={branches} args={[resources.branchGeometry, undefined, MAX_INSTANCES * BRANCHES_PER_TREE]} frustumCulled={false}>
      <meshStandardMaterial ref={branchMat} map={resources.bark} normalMap={resources.barkN} roughnessMap={resources.barkR} normalScale={new THREE.Vector2(.7, .7)} color={palette.trunk} roughness={.95} metalness={0} />
    </instancedMesh>
    <instancedMesh ref={crowns} args={[resources.crownGeometry, undefined, MAX_INSTANCES * CROWN_SLOTS]} material={resources.crownMaterial} frustumCulled={false} />
    <instancedMesh ref={rocks} args={[resources.rockGeometry, undefined, MAX_INSTANCES]} frustumCulled={false}>
      <meshStandardMaterial color={palette.rock} roughness={.55} metalness={0} />
    </instancedMesh>
    <instancedMesh ref={plants} args={[resources.plantGeometry, undefined, MAX_INSTANCES]} material={resources.plantMaterial} frustumCulled={false} />
  </>;
}

function Hud({ simulation, secrets }: { simulation: MutableRefObject<Simulation>; secrets: number | null }) {
  const [meters, setMeters] = useState(0);
  useEffect(() => {
    const id = window.setInterval(() => setMeters(Math.floor(simulation.current.distance)), 120);
    return () => window.clearInterval(id);
  }, [simulation]);
  return <div className="forest-hud" aria-live="off">{meters}<small>m</small>{secrets != null && <small> · {secrets} secrets</small>}</div>;
}


function DayCycle({ mood, night, red }: { mood: MutableRefObject<ForestMood>; night: Palette; red: RedRef }) {
  const { scene } = useThree();
  const day = useMemo(() => {
    const st = getComputedStyle(document.documentElement);
    return { fog: new THREE.Color(st.getPropertyValue("--forest-day-fog").trim() || "#8f9b98"), moon: new THREE.Color(st.getPropertyValue("--forest-day-moon").trim() || "#dfe6e2") };
  }, []);
  const level = useRef(-1);
  const tmp = useMemo(() => new THREE.Color(), []);
  useFrame((_, raw) => {
    const d = new Date();
    const target = daylightFor(mood.current, d.getHours() + d.getMinutes() / 60);
    // Manual switches glide over ~2 s; auto follows the clock continuously.
    level.current = level.current < 0 ? target : level.current + (target - level.current) * Math.min(1, raw * 1.5);
    const k = level.current;
    // Survive: the pursuer's red soaks the air itself, not just the lit faces.
    const r = red.current;
    tmp.copy(night.fog).lerp(day.fog, k).lerp(night.blood, r * .75);
    if (scene.background instanceof THREE.Color) scene.background.copy(tmp);
    if (scene.fog instanceof THREE.Fog) { scene.fog.color.copy(tmp); scene.fog.near = 7 - k * 3; }
    scene.traverse(o => {
      if (o.userData["survive"]) return;
      if (o instanceof THREE.HemisphereLight) { o.color.copy(night.moon).lerp(day.moon, k).lerp(night.pursuer, r * .8); o.groundColor.copy(tmp).lerp(night.pursuer, r * .55); o.intensity = .85 + k * 1.6 + r * 1.4; }
      else if (o instanceof THREE.DirectionalLight) { o.color.copy(night.moon).lerp(day.moon, k).lerp(night.pursuer, r * .6); o.intensity = .7 + k * 1.3; }
    });
  });
  return null;
}

function buzz(pattern: number | number[]) {
  const list = Array.isArray(pattern) ? pattern : [pattern];
  const native = (globalThis as { AndroidHaptics?: { pulse(p: string): void } }).AndroidHaptics;
  try {
    if (native) native.pulse(list.join(","));
    else navigator.vibrate?.(list);
  } catch { /* Vibration unavailable. */ }
}

export function ForestRunner() {
  const simulation = useRef(makeSimulation());
  const red = useRef(0);
  const [secretCount, setSecretCount] = useState(0);
  const input = useRef<Input>({ tilt: 0, touch: 0, touchMode: false, cal: makeTiltCal() });
  const drone = useRef<ForestDrone | null>(null);
  const [phase, setPhase] = useState<Phase>("menu");
  const [best, setBest] = useState(0);
  const [last, setLast] = useState(0);
  const [sound, setSound] = useState(true);
  const [touchMode, setTouchMode] = useState(false);
  const [haptics, setHaptics] = useState(true);
  const [orient, setOrient] = useState<"landscape" | "any">("landscape");
  const [difficulty, setDifficulty] = useState<Difficulty>("easy");
  const [mode, setMode] = useState<GameMode>("classic");
  const [activePower, setActivePower] = useState<PowerUpType | null>(null);
  // The frame and veil melt away instead of vanishing when a power-up ends.
  const [leavingPower, setLeavingPower] = useState<PowerUpType | null>(null);
  const lastPowerType = useRef<PowerUpType | null>(null);
  const leaveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [mood, setMood] = useState<ForestMood>("night");
  const moodRef = useRef<ForestMood>("night");
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [palette, setPalette] = useState<Palette | null>(null);
  const [notice, setNotice] = useState("");
  const hapticsRef = useRef(true);
  const menuDrag = useRef<{ x: number; moved: boolean } | null>(null);
  const menu = phase === "menu" || phase === "fallen";
  const [pauseUi, setPauseUi] = useState<"none" | "paused" | "exiting">("none");
  const paused = pauseUi === "paused";
  const lookMode = menu || paused;
  const pauseRun = () => {
    if (!requestPause(simulation.current)) return;
    input.current.touch = 0;
    setSettingsOpen(false);
    setPauseUi("paused");
  };
  const pauseRef = useRef(pauseRun);
  pauseRef.current = pauseRun;
  const resumeRun = () => {
    if (requestResume(simulation.current)) { setSettingsOpen(false); setPauseUi("none"); input.current.tilt = 0; resetTilt(input.current.cal, performance.now()); }
  };
  const exitRun = () => {
    if (requestExit(simulation.current)) { setSettingsOpen(false); setPauseUi("exiting"); }
  };
  const [pendingUpdate, setPendingUpdate] = useState<UpdateInfo | null>(null);
  const [updateDismissed, setUpdateDismissed] = useState(false);
  const [updateDownloading, setUpdateDownloading] = useState(false);

  // Quietly look for a newer release on launch and each time the app returns to the foreground.
  useEffect(() => {
    const native = nativeUpdater();
    if (!native) return;
    let last = 0;
    const look = () => {
      if (document.visibilityState !== "visible" || Date.now() - last < 10 * 60 * 1000) return;
      last = Date.now();
      checkForUpdate(native.versionCode()).then(u => { if (u) setPendingUpdate(u); }).catch(() => undefined);
    };
    look();
    document.addEventListener("visibilitychange", look);
    return () => document.removeEventListener("visibilitychange", look);
  }, []);

  useEffect(() => {
    const audio = new ForestDrone();
    drone.current = audio;
    let launchSound = true;
    let launchOrientation: "landscape" | "any" = "landscape";
    try {
      setBest(Number(localStorage.getItem("forest-best")) || 0);
      setLast(Number(localStorage.getItem("forest-last")) || 0);
      launchSound = localStorage.getItem("forest-sound") !== "off";
      setSound(launchSound);
      const touch = localStorage.getItem("forest-touch") === "on";
      setTouchMode(touch);
      input.current.touchMode = touch;
      const stored = localStorage.getItem("forest-difficulty");
      if (stored === "easy" || stored === "normal" || stored === "hard") {
        setDifficulty(stored);
        simulation.current.difficulty = stored;
      }
      const storedMode = localStorage.getItem("forest-mode");
      if (storedMode === "classic" || storedMode === "survive" || storedMode === "freeroam") {
        setMode(storedMode);
        simulation.current.mode = storedMode;
      }
      const vib = localStorage.getItem("forest-haptics") !== "off";
      setHaptics(vib);
      hapticsRef.current = vib;
      const m = localStorage.getItem("forest-mood");
      if (m === "day" || m === "auto") { setMood(m); moodRef.current = m; }
      if (localStorage.getItem("forest-orient") === "any") launchOrientation = "any";
    } catch { /* Local storage may be disabled. */ }
    setOrient(launchOrientation);
    void lockOrientation(launchOrientation);
    audio.setMode(launchSound, false);
    // A storage failure must not stop the default menu sound.
    if (launchSound) audio.unlock();
    const canvas = document.createElement("canvas");
    canvas.width = canvas.height = 1;
    const ctx = canvas.getContext("2d");
    if (ctx) {
      const styles = getComputedStyle(document.documentElement);
      const p = {} as Palette;
      for (const name of PALETTE_KEYS) {
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
      input.current.tilt = updateTilt(input.current.cal, raw, angle, performance.now());
    };
    // Re-centre whenever the screen turns or the app comes back: the phone is
    // being moved, so the old neutral no longer means anything.
    const recenter = () => { input.current.tilt = 0; resetTilt(input.current.cal, performance.now()); };
    screen.orientation?.addEventListener?.("change", recenter);
    window.addEventListener("orientationchange", recenter);
    const visibility = () => {
      input.current.touch = 0;
      recenter();
      if (document.hidden) { pauseRef.current(); audio.suspend(); }
      else {
        let enabled = true;
        try { enabled = localStorage.getItem("forest-sound") !== "off"; } catch { /* Keep default. */ }
        if (enabled) audio.unlock();
      }
    };
    window.addEventListener("deviceorientation", orientation);
    document.addEventListener("visibilitychange", visibility);
    return () => {
      window.removeEventListener("deviceorientation", orientation);
      screen.orientation?.removeEventListener?.("change", recenter);
      window.removeEventListener("orientationchange", recenter);
      document.removeEventListener("visibilitychange", visibility);
      audio.dispose();
    };
  }, []);
  useEffect(() => { drone.current?.setMode(sound, (phase === "awakening" || phase === "running") && pauseUi === "none"); }, [sound, phase, pauseUi]);

  const onPhase = useCallback((next: Phase, score: number) => {
    setPhase(next);
    if (next === "menu") { setPauseUi("none"); drone.current?.setMuffled(false); }
    if (next === "falling") {
      drone.current?.play("end");
      setLast(score);
      setBest(old => Math.max(old, score));
      if (hapticsRef.current) buzz([70, 60, 110, 60, 150]);
      try {
        localStorage.setItem("forest-last", String(score));
        localStorage.setItem("forest-best", String(Math.max(Number(localStorage.getItem("forest-best")) || 0, score)));
      } catch { /* Local storage may be disabled. */ }
    }
  }, []);
  const onImpact = useCallback(() => {
    if (hapticsRef.current) buzz(65);
    drone.current?.impactDip();
  }, []);
  const onStumble = useCallback(() => {
    if (hapticsRef.current) buzz(22);
  }, []);
  const onPowerUp = useCallback((type: PowerUpType) => {
    if (leaveTimer.current) clearTimeout(leaveTimer.current);
    setLeavingPower(null);
    lastPowerType.current = type;
    setActivePower(type);
    if (hapticsRef.current) buzz([35, 30, 60]);
  }, []);
  const onPowerUpEnd = useCallback(() => {
    setActivePower(null);
    setLeavingPower(lastPowerType.current);
    if (leaveTimer.current) clearTimeout(leaveTimer.current);
    leaveTimer.current = setTimeout(() => setLeavingPower(null), 1100);
    if (hapticsRef.current) buzz(25);
  }, []);
  const onThreat = useCallback(() => {
    drone.current?.play("threat");
    if (hapticsRef.current) buzz([40, 40, 90]);
  }, []);
  const onDark = useCallback(() => {
    drone.current?.setMuffled(true);
  }, []);
  const onLit = useCallback(() => {
    drone.current?.setMuffled(false);
  }, []);
  const onGate = useCallback(() => {
    if (hapticsRef.current) buzz([30, 40, 30]);
  }, []);
  const onSecret = useCallback((_secret: Secret) => {
    const found = simulation.current.found;
    setSecretCount(found.length);
    if (hapticsRef.current) buzz([30, 50, 30, 50, 80]);
    try { localStorage.setItem("forest-secrets", JSON.stringify(found)); } catch { /* Local storage may be disabled. */ }
  }, []);

  const start = async () => {
    if (phase !== "menu" && phase !== "fallen") return;
    setNotice("");
    drone.current?.unlock();
    await lockOrientation(orient);
    if (!touchMode) {
      if (typeof DeviceOrientationEvent === "undefined") {
        setNotice("Tilt is not available. Switch to touch controls.");
        return;
      }
      const D = DeviceOrientationEvent as unknown as { requestPermission?: () => Promise<string> };
      try {
        if (D.requestPermission && await D.requestPermission() !== "granted") {
          setNotice("Tilt permission denied. Switch to touch controls.");
          return;
        }
      } catch {
        setNotice("Switch to touch controls.");
        return;
      }
      resetTilt(input.current.cal, performance.now());
    }
    input.current.touch = 0;
    beginRun(simulation.current);
    // Secrets stay discovered across freeroam walks on this device.
    try {
      const saved: unknown = JSON.parse(localStorage.getItem("forest-secrets") ?? "[]");
      if (Array.isArray(saved)) simulation.current.found = saved.filter((v): v is string => typeof v === "string");
    } catch { /* Local storage may be disabled. */ }
    setSecretCount(simulation.current.found.length);
    red.current = 0;
    drone.current?.setMuffled(false);
    drone.current?.play("start");
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
    if (next) buzz(30);
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
  const changeMode = (next: GameMode) => {
    setMode(next);
    simulation.current.mode = next;
    try { localStorage.setItem("forest-mode", next); } catch { /* Local storage may be disabled. */ }
  };
  const changeMood = (m: ForestMood) => {
    setMood(m);
    moodRef.current = m;
    try { localStorage.setItem("forest-mood", m); } catch { /* Local storage may be disabled. */ }
  };
  const changeOrient = (mode: "landscape" | "any") => {
    setOrient(mode);
    try { localStorage.setItem("forest-orient", mode); } catch { /* Local storage may be disabled. */ }
    void lockOrientation(mode);
  };
  const settingsPanel = (
            <div className="forest-settings-panel" data-forest-ui>
              <button className="forest-back" onClick={() => setSettingsOpen(false)} aria-label="Back"><ArrowLeft size={15} /></button>
              <h2>Settings</h2>
              <div className="forest-settings-scroll">
              <div className="forest-row">
                <span>Difficulty</span>
                <div className="forest-seg forest-seg-three">
                  <button className={difficulty === "easy" ? "on" : ""} onClick={() => changeDifficulty("easy")}>Easy</button>
                  <button className={difficulty === "normal" ? "on" : ""} onClick={() => changeDifficulty("normal")}>Normal</button>
                  <button className={difficulty === "hard" ? "on" : ""} onClick={() => changeDifficulty("hard")}>Hard</button>
                </div>
              </div>
              <div className="forest-row">
                <span>Mode</span>
                <div className="forest-seg forest-seg-three">
                  <button className={mode === "classic" ? "on" : ""} onClick={() => changeMode("classic")}>Classic</button>
                  <button className={mode === "survive" ? "on" : ""} onClick={() => changeMode("survive")}>Survive</button>
                  <button className={mode === "freeroam" ? "on" : ""} onClick={() => changeMode("freeroam")}>Freeroam</button>
                </div>
              </div>
              <div className="forest-row">
                <span>Forest</span>
                <div className="forest-seg forest-seg-three">
                  <button className={mood === "night" ? "on" : ""} onClick={() => changeMood("night")}>Night</button>
                  <button className={mood === "day" ? "on" : ""} onClick={() => changeMood("day")}>Day</button>
                  <button className={mood === "auto" ? "on" : ""} onClick={() => changeMood("auto")}>Auto</button>
                </div>
              </div>
              <div className="forest-row">
                <span>Controls</span>
                <div className="forest-seg">
                  <button className={touchMode ? "" : "on"} onClick={() => changeTouch(false)}>Tilt</button>
                  <button className={touchMode ? "on" : ""} onClick={() => changeTouch(true)}>Touch</button>
                </div>
              </div>
              <div className="forest-row">
                <span>Orientation</span>
                <div className="forest-seg">
                  <button className={orient === "landscape" ? "on" : ""} onClick={() => changeOrient("landscape")}>Landscape</button>
                  <button className={orient === "any" ? "on" : ""} onClick={() => changeOrient("any")}>Any</button>
                </div>
              </div>
              <div className="forest-row">
                <span>Audio</span>
                <button className="forest-switch" aria-pressed={sound} onClick={toggleSound}>{sound ? "On" : "Off"}</button>
              </div>
              <div className="forest-row">
                <span>Haptics</span>
                <button className="forest-switch" aria-pressed={haptics} onClick={toggleHaptics}>{haptics ? "On" : "Off"}</button>
              </div>
              <UpdateRow />
              </div>
            </div>
  );
  return <main
    className="forest-game dark"
    data-phase={phase}
    onPointerDown={e => {
      drone.current?.unlock();
      const inUi = (e.target as Element | null)?.closest("[data-forest-ui]");
      // Entering fullscreen resizes the page mid-tap, which swallows the button press.
      // The installed app is already fullscreen, and menu buttons must react on first touch.
      const native = "AndroidOrientation" in window;
      if (!native && !inUi && !document.fullscreenElement && document.documentElement.requestFullscreen) void document.documentElement.requestFullscreen().catch(() => undefined);
      if (inUi) return;
      if (lookMode) {
        if (settingsOpen) { setSettingsOpen(false); return; }
        // A tap starts the run; a horizontal drag looks around instead.
        menuDrag.current = { x: e.clientX, moved: false };
        e.currentTarget.setPointerCapture(e.pointerId);
        return;
      }
      if (touchMode && pauseUi === "none") {
        e.currentTarget.setPointerCapture(e.pointerId);
        input.current.touch = e.clientX < e.currentTarget.clientWidth / 2 ? -1 : 1;
      }
    }}
    onPointerMove={e => {
      if (lookMode && menuDrag.current && e.buttons) {
        const dx = e.clientX - menuDrag.current.x;
        if (Math.abs(dx) > 8) menuDrag.current.moved = true;
        input.current.touch = THREE.MathUtils.clamp(-dx / 90, -1, 1);
        return;
      }
      if (e.buttons && !lookMode && pauseUi === "none" && touchMode) input.current.touch = e.clientX < e.currentTarget.clientWidth / 2 ? -1 : 1;
    }}
    onPointerUp={() => {
      if (menuDrag.current) {
        const wasTap = !menuDrag.current.moved;
        menuDrag.current = null;
        input.current.touch = 0;
        if (menu && wasTap) void start();
        return;
      }
      input.current.touch = 0;
    }}
    onPointerCancel={() => { input.current.touch = 0; }}
  >
    {palette && <Canvas dpr={[1, 1.5]} camera={{ fov: 72, position: [0, 1.6, 0] }} gl={{ antialias: true, powerPreference: "high-performance" }}>
      <color attach="background" args={[palette.fog]} />
      <fog attach="fog" args={[palette.fog, 7, FOG_END]} />
      <hemisphereLight args={[palette.moon, palette.fog, .85]} />
      <directionalLight position={[0, 24, -20]} color={palette.moon} intensity={.7} />
      <World simulation={simulation} input={input} palette={palette} red={red} onDark={onDark} onLit={onLit} onGate={onGate} onSecret={onSecret} onPhase={onPhase} onImpact={onImpact} onStumble={onStumble} onPowerUp={onPowerUp} onPowerUpEnd={onPowerUpEnd} onThreat={onThreat} />
      <ForestEffects simulation={simulation} />
      <DayCycle mood={moodRef} night={palette} red={red} />
    </Canvas>}
    {(phase === "running" || phase === "awakening" || phase === "falling") && <Hud simulation={simulation} secrets={mode === "freeroam" ? secretCount : null} />}
    {(activePower ?? leavingPower) && <><div className={`forest-power-veil${activePower ? "" : " is-out"}`} data-type={activePower ?? leavingPower ?? undefined} aria-hidden /><div className={`forest-power-glow${activePower ? "" : " is-out"}`} data-type={activePower ?? leavingPower ?? undefined} aria-hidden /></>}
    {(phase === "running" || phase === "awakening") && pauseUi === "none" && (
      <button className="forest-pause" data-forest-ui onClick={pauseRun} aria-label="Pause"><Pause size={18} /></button>
    )}
    {paused && (
      <div className="forest-menu">
        <section className="forest-glass" aria-label="Paused">
          {settingsOpen ? settingsPanel : (
            <div className="forest-pause-row" data-forest-ui>
              <button className="forest-resume" onClick={resumeRun}><span className="forest-title" data-text="resume">resume</span></button>
              <div className="forest-pause-side">
                <button className="forest-gear" onClick={() => setSettingsOpen(true)} aria-label="Settings"><Settings size={16} /></button>
                <button className="forest-gear" onClick={exitRun} aria-label="Exit to menu"><LogOut size={16} /></button>
              </div>
            </div>
          )}
        </section>
      </div>
    )}
    {menu && (
      <div className="forest-menu">
        <section className="forest-glass" aria-label="Run menu">
          {settingsOpen ? settingsPanel : (
            <>
              <div className="forest-head">
                <h1 className="forest-title" data-text="tap and run!">tap and run!</h1>
                <button className="forest-gear" data-forest-ui onClick={() => setSettingsOpen(true)} aria-label="Settings"><Settings size={16} /></button>
              </div>
              <div className="forest-scores">
                <div><span>Last</span><strong>{last}<small>m</small></strong></div>
                <div><span>Best</span><strong>{best}<small>m</small></strong></div>
              </div>
              {pendingUpdate && !updateDismissed && (
                <div className="forest-row" data-forest-ui role="status" style={{ flexWrap: "wrap", marginTop: 10, pointerEvents: "auto" }} onPointerDown={e => e.stopPropagation()} onPointerUp={e => e.stopPropagation()}>
                  <span>{updateDownloading ? "Downloading… then confirm the install." : "New update available!"}</span>
                  {!updateDownloading && <>
                    <button className="forest-switch" aria-pressed onClick={e => { e.stopPropagation(); nativeUpdater()?.download(pendingUpdate.apkUrl); setUpdateDownloading(true); }}>Update</button>
                    <button className="forest-switch" aria-pressed={false} onClick={e => { e.stopPropagation(); setUpdateDismissed(true); }}>Later</button>
                  </>}
                </div>
              )}
            </>
          )}
          {notice && <p role="status" className="forest-notice">{notice}</p>}
        </section>
      </div>
    )}
  </main>;
}

function UpdateRow() {
  const [state, setState] = useState<"idle" | "checking" | "latest" | "error" | "web" | "downloading">("idle");
  const [update, setUpdate] = useState<UpdateInfo | null>(null);
  const check = async () => {
    const native = nativeUpdater();
    if (!native) { setState("web"); return; }
    setState("checking"); setUpdate(null);
    try {
      const found = await checkForUpdate(native.versionCode());
      if (found) { setUpdate(found); setState("idle"); } else setState("latest");
    } catch { setState("error"); }
  };
  const download = () => {
    const native = nativeUpdater();
    if (!native || !update) return;
    native.download(update.apkUrl);
    setState("downloading");
  };
  const label = {
    idle: "", checking: "Checking…", latest: "Latest version installed.",
    error: "Could not reach updates.", web: "Only available in the phone app.",
    downloading: "Downloading… then confirm the install.",
  }[state];
  return (
    <div className="forest-row" style={{ flexWrap: "wrap" }}>
      <span>Update</span>
      {update && state !== "downloading"
        ? <button className="forest-switch" aria-pressed onClick={download}>Download</button>
        : <button className="forest-switch" aria-pressed={false} disabled={state === "checking" || state === "downloading"} onClick={check}>Check</button>}
      {(update && state !== "downloading") && <small style={{ width: "100%", opacity: .75 }}>New update available (build {update.build}).</small>}
      {label && <small style={{ width: "100%", opacity: .75 }}>{label}</small>}
    </div>
  );
}
