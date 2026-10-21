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

type Palette = Record<"fog" | "moon" | "lamp" | "green" | "purple" | "lavender" | "trunk" | "ground" | "crown" | "rock" | "plant", THREE.Color>;
type Input = { tilt: number; touch: number; touchMode: boolean; neutral: number | null; angle: number };
const MAX_INSTANCES = (RADIUS * 2 + 1) ** 2;
const BRANCHES_PER_TREE = 11;
// Five core leaf masses per tree plus a leaf clump on every branch tip.
const CROWNS_PER_TREE = 5;
const CROWN_SLOTS = CROWNS_PER_TREE + BRANCHES_PER_TREE;

// Installed apps lock to landscape where the OS allows it; browsers get the
// request after fullscreen and simply ignore it when unsupported.
async function lockOrientation(mode: "landscape" | "any") {
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
  const shadows = useRef<THREE.InstancedMesh>(null);
  const mist = useRef<THREE.Group>(null);
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
    // Trunks with low crowns (~15-17.5 m) that neighbours' foliage merges into overhead.
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
      map: bark, normalMap: barkN, roughnessMap: barkR, normalScale: new THREE.Vector2(.75, .75), color: palette.trunk, roughness: .95, metalness: 0,
    });
    trunkMaterial.onBeforeCompile = shader => {
      // Baked contact darkening and moss at the trunk foot instead of dynamic shadows.
      shader.vertexShader = shader.vertexShader
        .replace("#include <common>", `#include <common>\nvarying float vTH;`)
        .replace("#include <begin_vertex>", `#include <begin_vertex>\nvTH = position.y;`);
      shader.fragmentShader = shader.fragmentShader
        .replace("#include <common>", `#include <common>\nvarying float vTH;`)
        .replace("#include <map_fragment>", `#include <map_fragment>
          float foot = 1. - smoothstep(1.0, 3.2, vTH);
          diffuseColor.rgb = mix(diffuseColor.rgb, diffuseColor.rgb * vec3(.75, 1.15, .72), foot * .55);
          diffuseColor.rgb *= mix(.45, 1., smoothstep(.9, 2.4, vTH)) * mix(1., .7, smoothstep(10., 18., vTH));`);
    };
    return { bark, barkN, barkR, floor, floorN, floorR, trunkGeometry, branchGeometry, crownGeometry, rockGeometry, plantGeometry, groundMaterial, trunkMaterial, shadowGeometry: new THREE.PlaneGeometry(1, 1).rotateX(-Math.PI / 2), glowTexture: new THREE.CanvasTexture(canvas), dummy: new THREE.Object3D(), target: new THREE.Vector3() };
  }, [palette]);
  useEffect(() => () => {
    [resources.trunkGeometry, resources.branchGeometry, resources.crownGeometry, resources.rockGeometry, resources.plantGeometry, resources.glowTexture, resources.groundMaterial, resources.trunkMaterial, resources.shadowGeometry].forEach(g => g.dispose());

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
        const base = groundHeight(tree.x, tree.z) - 1.1;
        resources.dummy.rotation.order = "XYZ";
        resources.dummy.position.set(tree.x, base, tree.z);
        resources.dummy.scale.set(tree.scale, 1, tree.scale);
        resources.dummy.rotation.set(0, hash(seedX, seedZ, 5) * 6, 0);
        resources.dummy.updateMatrix();
        trunkMesh.setMatrixAt(i, resources.dummy.matrix);
        // Soft dark contact patch where the trunk meets the ground.
        resources.dummy.position.set(tree.x, groundHeight(tree.x, tree.z) + .05, tree.z);
        resources.dummy.scale.set(3.4 * tree.scale, 1, 3.4 * tree.scale);
        resources.dummy.updateMatrix();
        shadows.current?.setMatrixAt(i, resources.dummy.matrix);
        for (let b = 0; b < BRANCHES_PER_TREE; b++) {
          const seed = hash(seedX, seedZ, 20 + b);
          // First branches fan up into the crown; the rest stick out from the trunk lower down.
          const upper = b < 5;
          const top = upper ? base + 14.5 + hash(seedX, seedZ, 60 + b) * 2.5 : base + 8 + hash(seedX, seedZ, 60 + b) * 6.5;
          resources.dummy.position.set(tree.x, top, tree.z);
          resources.dummy.rotation.order = "YXZ";
          const tilt = upper ? .55 + hash(seedX, seedZ, 40 + b) * .75 : 1.05 + hash(seedX, seedZ, 40 + b) * .5;
          resources.dummy.rotation.set(tilt, seed * Math.PI * 2, (hash(seedX, seedZ, 80 + b) - .5) * .4);
          const thick = upper ? 1 : 2.2 + hash(seedX, seedZ, 90 + b) * 1.4;
          const len = upper ? .7 + seed * .6 : 1.1 + hash(seedX, seedZ, 95 + b) * 1.1;
          resources.dummy.scale.set(tree.scale * thick, tree.scale * len, tree.scale * thick);
          resources.dummy.updateMatrix();
          branchMesh?.setMatrixAt(i * BRANCHES_PER_TREE + b, resources.dummy.matrix);
          // Every branch ends in foliage — no bare wood anywhere in the forest.
          const reach = 2.2 * tree.scale * len;
          resources.dummy.position.set(
            tree.x + reach * Math.sin(tilt) * Math.sin(seed * Math.PI * 2),
            top + reach * Math.cos(tilt),
            tree.z + reach * Math.sin(tilt) * Math.cos(seed * Math.PI * 2));
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
          resources.dummy.position.set(tree.x + Math.cos(angle) * spread, base + 15.2 + hash(seedX, seedZ, 71 + c * 3) * 2.0, tree.z + Math.sin(angle) * spread);
          resources.dummy.rotation.set((hash(seedX, seedZ, 83 + c) - .5) * .9, angle, (hash(seedX, seedZ, 77 + c) - .5) * .9);
          const r = (c === 0 ? 3.9 : 2.8) + hash(seedX, seedZ, 73 + c) * 2.0;
          resources.dummy.scale.set(r * (.8 + hash(seedX, seedZ, 85 + c) * .6) * tree.scale, r * (.45 + hash(seedX, seedZ, 87 + c) * .4) * tree.scale, r * (.7 + hash(seedX, seedZ, 79 + c) * .6) * tree.scale);
          resources.dummy.updateMatrix();
          crownMesh?.setMatrixAt(i * CROWN_SLOTS + c, resources.dummy.matrix);
        }
      });
      trunkMesh.count = s.trees.length;
      if (branchMesh) branchMesh.count = s.trees.length * BRANCHES_PER_TREE;
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
          resources.dummy.position.set(o.x, groundHeight(o.x, o.z) + .05, o.z);
          resources.dummy.scale.set(o.radius * 3.2, 1, o.radius * 3);
          resources.dummy.updateMatrix();
          shadows.current?.setMatrixAt(s.trees.length + rockCount, resources.dummy.matrix);
          rockCount++;
        } else {
          resources.dummy.position.set(o.x, gy - .05, o.z);
          resources.dummy.scale.set(.75, .35 + hash(Math.floor(o.x), Math.floor(o.z), 81) * .3, .75);
          resources.dummy.updateMatrix();
          plantMesh?.setMatrixAt(plantCount++, resources.dummy.matrix);
        }
      });
      if (shadows.current) { shadows.current.count = s.trees.length + rockCount; shadows.current.instanceMatrix.needsUpdate = true; }
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
    const running = s.phase === "running";
    const steering = getSteering(input.current.touchMode, input.current.touch, input.current.tilt);
    // Menu and lying phases: no step rhythm, only a barely noticeable breathing sway.
    const leanTarget = lying ? Math.sin(t * .13) * .035 : steering;
    const bob = running ? Math.abs(Math.sin(t * 5.5)) * .018 : Math.sin(t * .4) * .012;
    lean.current += (leanTarget - lean.current) * Math.min(1, raw * (running ? 9 : 2));
    camera.position.set(
      s.x + fall * .45 + leanAmount * .3,
      groundHeight(s.x, s.z) + 1.6 - fall * 1.28 + bob,
      s.z + fall * .65);
    resources.target.set(
      s.x - Math.sin(s.heading) * 18,
      groundHeight(s.x, s.z) * .6 + groundHeight(s.x - Math.sin(s.heading) * 18, s.z - Math.cos(s.heading) * 18) * .4 + 1.62 + fall * 3,
      s.z - Math.cos(s.heading) * 18);
    camera.lookAt(resources.target);
    camera.rotateX(bump * .085 + waking * Math.sin(t * 5) * .045 + fall * .065 * Math.sin(t * .32) + tripping * Math.sin(t * 17) * .085);
    camera.rotateY(-leanAmount * .06);
    camera.rotateZ(-leanAmount * .055 + bump * .12 + fall * .72 + waking * Math.sin(t * 4) * .03 + (running ? Math.sin(t * 5.5) * .018 : Math.sin(t * .4) * .008) + tripping * Math.sin(t * 13) * .07);
    if (lamp.current) {
      lamp.current.position.copy(camera.position);
      lamp.current.position.y += .35;
      const flicker = lying ? (Math.sin(t * 5.7) + Math.sin(t * 13.2) > .25 ? .14 : .035) : 1 - waking * .8;
      lamp.current.intensity = 65 * flicker;
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
      // Behind the fog wall, past every visible trunk. Constant distance keeps the lights unreachable.
      glow.current.position.set(s.x, groundHeight(s.x, s.z - 42) + 4.2, s.z - 42);
      glow.current.children.forEach((child, i) => {
        const sprite = child as THREE.Sprite;
        const high = i > 2;
        sprite.scale.setScalar((high ? 30 : i === 0 ? 24 : 20) * (1 + Math.sin(t * .4 + i) * .08));
        if (high) {
          // Blurred light bleeding through the canopy, far above the runners head.
          sprite.position.set((i === 3 ? -3.5 : 3.5) + Math.sin(t * .2 + i) * 2, 13.5 + Math.sin(t * .24 + i) * 1.4, 0);
        } else {
          sprite.position.x = (i === 0 ? -5 : i === 1 ? 5 : 0) + Math.sin(t * .16 + i) * 1.5;
        }
      });
    }
  });
  return <>
    <pointLight ref={lamp} color={palette.lamp} intensity={65} distance={27} decay={1.6} />
    <group ref={glow}>
        {[palette.green, palette.purple, palette.lavender, palette.purple, palette.lavender].map((color, i) => (
          <sprite key={i} renderOrder={-1}>
            <spriteMaterial map={resources.glowTexture} color={color} transparent opacity={i > 2 ? .14 : .66} blending={THREE.AdditiveBlending} depthWrite={false} fog={false} />
          </sprite>
        ))}
    </group>
    <group ref={mist}>
      {[0, 1, 2, 3].map(i => (
        <sprite key={i}>
          <spriteMaterial map={resources.glowTexture} color={palette.moon} transparent opacity={.07} depthWrite={false} />
        </sprite>
      ))}
    </group>
    <instancedMesh ref={shadows} args={[resources.shadowGeometry, undefined, MAX_INSTANCES * 2]} frustumCulled={false} renderOrder={1}>
      <meshBasicMaterial map={resources.glowTexture} color="black" transparent opacity={.6} depthWrite={false} polygonOffset polygonOffsetFactor={-2} />
    </instancedMesh>
    <mesh ref={ground} rotation-x={-Math.PI / 2} material={resources.groundMaterial}>
      <planeGeometry args={[200, 200, 160, 160]} />
    </mesh>
    <instancedMesh ref={trunks} args={[resources.trunkGeometry, undefined, MAX_INSTANCES]} material={resources.trunkMaterial} frustumCulled={false} />
    <instancedMesh ref={branches} args={[resources.branchGeometry, undefined, MAX_INSTANCES * BRANCHES_PER_TREE]} frustumCulled={false}>
      <meshStandardMaterial map={resources.bark} normalMap={resources.barkN} roughnessMap={resources.barkR} normalScale={new THREE.Vector2(.7, .7)} color={palette.trunk} roughness={.95} metalness={0} />
    </instancedMesh>
    <instancedMesh ref={crowns} args={[resources.crownGeometry, undefined, MAX_INSTANCES * CROWN_SLOTS]} frustumCulled={false}>
      <meshStandardMaterial color={palette.crown} roughness={.9} metalness={0} />
    </instancedMesh>
    <instancedMesh ref={rocks} args={[resources.rockGeometry, undefined, MAX_INSTANCES]} frustumCulled={false}>
      <meshStandardMaterial color={palette.rock} roughness={.55} metalness={0} />
    </instancedMesh>
    <instancedMesh ref={plants} args={[resources.plantGeometry, undefined, MAX_INSTANCES]} frustumCulled={false}>
      <meshStandardMaterial color={palette.plant} roughness={.9} metalness={0} />
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
  const [orient, setOrient] = useState<"landscape" | "any">("landscape");
  const [difficulty, setDifficulty] = useState<Difficulty>("easy");
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
      const stored = localStorage.getItem("forest-difficulty");
      if (stored === "easy" || stored === "normal" || stored === "hard") {
        setDifficulty(stored);
        simulation.current.difficulty = stored;
      }
      const vib = localStorage.getItem("forest-haptics") !== "off";
      setHaptics(vib);
      hapticsRef.current = vib;
      if (localStorage.getItem("forest-orient") === "any") setOrient("any");
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
    await lockOrientation(orient);
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
  const changeOrient = (mode: "landscape" | "any") => {
    setOrient(mode);
    try { localStorage.setItem("forest-orient", mode); } catch { /* Local storage may be disabled. */ }
    void lockOrientation(mode);
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
      <directionalLight position={[0, 24, -20]} color={palette.moon} intensity={.7} />
      <World simulation={simulation} input={input} palette={palette} onPhase={onPhase} onImpact={onImpact} onStumble={onStumble} />
      <ForestEffects simulation={simulation} />
    </Canvas>}
    {(phase === "running" || phase === "awakening" || phase === "falling") && <Hud simulation={simulation} />}
    {menu && (
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
                <span>Képernyő</span>
                <div className="forest-seg">
                  <button className={orient === "landscape" ? "on" : ""} onClick={() => changeOrient("landscape")}>Fekvő</button>
                  <button className={orient === "any" ? "on" : ""} onClick={() => changeOrient("any")}>Bármely</button>
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
