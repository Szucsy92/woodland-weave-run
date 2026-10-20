import { Canvas, useFrame, useThree } from "@react-three/fiber";
import { Environment, Lightformer, useTexture } from "@react-three/drei";
import { Suspense, useEffect, useMemo, useRef, useState, type MutableRefObject } from "react";
import { Volume2, VolumeX, Smartphone, ChevronRight } from "lucide-react";
import * as THREE from "three";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { ForestEffects } from "./ForestEffects";
import { ForestDrone } from "@/lib/forest/drone";
import { beginRun, CELL, FOG_END, getSteering, hash, makeSimulation, orientationValue, RADIUS, stepSimulation, treesAround, type Phase, type Simulation } from "@/lib/forest/simulation";
import barkUrl from "@/assets/wet-bark.jpg";
import floorUrl from "@/assets/wet-ground.jpg";
import barkNormal from "@/assets/bark-normal.jpg";
import barkRough from "@/assets/bark-rough.jpg";
import floorNormal from "@/assets/ground-normal.jpg";
import floorRough from "@/assets/ground-rough.jpg";
import icon from "@/assets/forest-icon.jpg";

type Palette = Record<"fog" | "moon" | "lamp" | "green" | "purple" | "surface", THREE.Color>;
type Input = { tilt: number; touch: number; touchMode: boolean; neutral: number | null; angle: number };

function World({ simulation, input, palette, onPhase }: { simulation: MutableRefObject<Simulation>; input: MutableRefObject<Input>; palette: Palette; onPhase: (phase: Phase, score: number) => void }) {
  const trunks = useRef<THREE.InstancedMesh>(null), ground = useRef<THREE.Mesh>(null), lamp = useRef<THREE.PointLight>(null), glow = useRef<THREE.Group>(null);
  const { camera } = useThree();
  const maps = useTexture([barkUrl, barkNormal, barkRough, floorUrl, floorNormal, floorRough]);
  const resources = useMemo(() => {
    maps.forEach((t, i) => { t.wrapS = t.wrapT = THREE.RepeatWrapping; t.colorSpace = i === 0 || i === 3 ? THREE.SRGBColorSpace : THREE.NoColorSpace; t.anisotropy = 8; t.repeat.set(i < 3 ? 1.5 : 64, i < 3 ? 9 : 64); });
    const geometry = new THREE.CylinderGeometry(.36, .46, 30, 14, 24);
    const pos = geometry.attributes.position;
    for (let i = 0; i < pos.count; i++) {
      const y = pos.getY(i), a = Math.atan2(pos.getZ(i), pos.getX(i));
      const swell = 1 + Math.sin(a * 5 + y * .7) * .065 + Math.sin(a * 9 - y * .3) * .025;
      const root = 1 + Math.pow(Math.max(0, (1 - (y + 15) / 2)), 2) * .65;
      pos.setXYZ(i, pos.getX(i) * swell * root + Math.sin(y * .22) * .065, y, pos.getZ(i) * swell * root);
    }
    geometry.computeVertexNormals();
    const canvas = document.createElement("canvas"); canvas.width = canvas.height = 128;
    const ctx = canvas.getContext("2d");
    if (ctx) { const r = ctx.createRadialGradient(64,64,0,64,64,64); r.addColorStop(0,"rgba(255,255,255,1)"); r.addColorStop(.2,"rgba(255,255,255,.6)"); r.addColorStop(1,"rgba(255,255,255,0)"); ctx.fillStyle=r;ctx.fillRect(0,0,128,128); }
    return { geometry, glowTexture: new THREE.CanvasTexture(canvas), dummy: new THREE.Object3D(), target: new THREE.Vector3() };
  }, [maps]);
  useEffect(() => () => { resources.geometry.dispose(); resources.glowTexture.dispose(); }, [resources]);
  const previousPhase = useRef<Phase>("menu");
  useFrame((_, raw) => {
    const s = simulation.current;
    const key = `${Math.floor(s.x/CELL)},${Math.floor(s.z/CELL)}`;
    const mesh = trunks.current;
    if (mesh && key !== s.cell) {
      s.cell = key; s.trees = treesAround(Math.floor(s.x/CELL),Math.floor(s.z/CELL));
      s.trees.forEach((tree,i) => {
        resources.dummy.position.set(tree.x,15,tree.z); resources.dummy.scale.set(tree.scale,1,tree.scale); resources.dummy.rotation.set(0,hash(Math.floor(tree.x),Math.floor(tree.z),5)*6,0); resources.dummy.updateMatrix(); mesh.setMatrixAt(i,resources.dummy.matrix);
      });
      mesh.count = s.trees.length; mesh.instanceMatrix.needsUpdate = true;
    }
    stepSimulation(s, getSteering(input.current.touchMode,input.current.touch,input.current.tilt), raw);
    if (s.phase !== previousPhase.current) { previousPhase.current=s.phase; onPhase(s.phase,Math.floor(s.distance)); }
    if (ground.current) { ground.current.position.set(s.x,0,s.z); for (const t of maps.slice(3)) t.offset.set(s.x/3.125,-s.z/3.125); }
    const t=s.elapsed, lying=s.phase === "falling" || s.phase === "fallen";
    const fall=lying ? THREE.MathUtils.smoothstep(s.phaseTime,0,1.25) : 0;
    const waking=s.phase === "awakening" ? 1-THREE.MathUtils.smoothstep(s.phaseTime,0,1.15) : 0;
    const bump=s.impact>0 ? Math.sin((1-s.impact/.25)*Math.PI)*s.impactSide : 0;
    const bob=s.phase === "running" ? Math.sin(t*11)*.025 : Math.sin(t*.5)*.015;
    camera.position.set(s.x + fall*.45,1.6 - fall*1.28 + bob,s.z + fall*.65);
    resources.target.set(s.x-Math.sin(s.heading)*18,1.65+fall*3,s.z-Math.cos(s.heading)*18);
    camera.lookAt(resources.target); camera.rotateX(bump*.085 + waking*Math.sin(t*5)*.045 + fall*.065*Math.sin(t*.32)); camera.rotateZ(-getSteering(input.current.touchMode,input.current.touch,input.current.tilt)*.035 + bump*.12 + fall*.72 + waking*Math.sin(t*4)*.03);
    if (lamp.current) { lamp.current.position.copy(camera.position); lamp.current.position.y+=.35; const flicker=lying ? (Math.sin(t*5.7)+Math.sin(t*13.2)>.25 ? .14 : .035) : 1-waking*.8; lamp.current.intensity=65*flicker; }
    if (glow.current) {
      // At the fog wall, behind every visible trunk. Constant distance makes the lights unreachable.
      glow.current.position.set(s.x,4.2,s.z-42);
      glow.current.children.forEach((child,i) => { const sprite=child as THREE.Sprite; sprite.scale.setScalar((i===0?24:20)*(1+Math.sin(t*.4+i)*.08)); sprite.position.x=(i===0?-5:5)+Math.sin(t*.16+i)*1.5; });
    }
  });
  return <>
    <pointLight ref={lamp} color={palette.lamp} intensity={65} distance={27} decay={1.6} />
    <group ref={glow}>
      {[palette.green,palette.purple].map((color,i)=><sprite key={i} renderOrder={-1}><spriteMaterial map={resources.glowTexture} color={color} transparent opacity={.66} blending={THREE.AdditiveBlending} depthWrite={false} fog={false} /></sprite>)}
    </group>
    <mesh ref={ground} rotation-x={-Math.PI/2} receiveShadow><planeGeometry args={[200,200]} /><meshPhysicalMaterial map={maps[3]} normalMap={maps[4]} roughnessMap={maps[5]} normalScale={new THREE.Vector2(.55,.55)} color={palette.surface} roughness={.68} clearcoat={.7} clearcoatRoughness={.24} envMapIntensity={.45} /></mesh>
    <instancedMesh ref={trunks} args={[resources.geometry,undefined,(RADIUS*2+1)**2]} frustumCulled={false}><meshPhysicalMaterial map={maps[0]} normalMap={maps[1]} roughnessMap={maps[2]} normalScale={new THREE.Vector2(.8,.8)} color={palette.surface} roughness={.56} clearcoat={.65} clearcoatRoughness={.2} envMapIntensity={.5} /></instancedMesh>
  </>;
}

export function ForestRunner() {
  const simulation=useRef(makeSimulation()), input=useRef<Input>({tilt:0,touch:0,touchMode:false,neutral:null,angle:0});
  const drone=useRef<ForestDrone | null>(null);
  const [phase,setPhase]=useState<Phase>("menu"), [best,setBest]=useState(0), [last,setLast]=useState(0), [sound,setSound]=useState(true), [touchMode,setTouchMode]=useState(false), [palette,setPalette]=useState<Palette | null>(null), [notice,setNotice]=useState("");
  const menu=phase === "menu" || phase === "fallen";
  useEffect(()=>{
    const audio=new ForestDrone();drone.current=audio;
    try { setBest(Number(localStorage.getItem("forest-best"))||0);setLast(Number(localStorage.getItem("forest-last"))||0);setSound(localStorage.getItem("forest-sound")!=="off"); const touch=localStorage.getItem("forest-touch")==="on";setTouchMode(touch);input.current.touchMode=touch; } catch { /* Local score storage may be disabled. */ }
    const canvas=document.createElement("canvas");canvas.width=canvas.height=1;const ctx=canvas.getContext("2d");
    if(ctx){ const styles=getComputedStyle(document.documentElement), p={} as Palette; for(const name of ["fog","moon","lamp","green","purple","surface"] as const){ctx.fillStyle=styles.getPropertyValue(`--forest-${name}`).trim();ctx.fillRect(0,0,1,1);const data=ctx.getImageData(0,0,1,1).data;p[name]=new THREE.Color().setRGB(data[0]/255,data[1]/255,data[2]/255,THREE.SRGBColorSpace);}setPalette(p); }
    const orientation=(e:DeviceOrientationEvent)=>{
      if(e.gamma==null || e.beta==null)return;
      const angle=screen.orientation?.angle ?? (window as unknown as {orientation?:number}).orientation ?? 0;
      const raw=orientationValue(e.gamma,e.beta,angle);
      if(input.current.neutral==null || input.current.angle!==angle){input.current.neutral=raw;input.current.angle=angle;}
      input.current.tilt=THREE.MathUtils.clamp(raw-(input.current.neutral??0),-1,1);
    };
    const visibility=()=>{input.current.touch=0;if(document.hidden)audio.suspend();};
    window.addEventListener("deviceorientation",orientation);document.addEventListener("visibilitychange",visibility);
    return ()=>{window.removeEventListener("deviceorientation",orientation);document.removeEventListener("visibilitychange",visibility);audio.dispose();};
  },[]);
  useEffect(()=>{drone.current?.setMode(sound,phase==="awakening"||phase==="running");},[sound,phase]);
  const onPhase=(next:Phase,score:number)=>{
    setPhase(next);
    if(next==="falling") {setLast(score);setBest(old=>Math.max(old,score));try{localStorage.setItem("forest-last",String(score));localStorage.setItem("forest-best",String(Math.max(best,score)));}catch{}}
  };
  const start=async()=>{
    if(!menu)return;
    setNotice("");drone.current?.unlock();
    if(!touchMode){
      if(typeof DeviceOrientationEvent === "undefined"){setNotice("Döntés nem elérhető. Kapcsold be az érintéses irányítást.");return;}
      const D=DeviceOrientationEvent as unknown as {requestPermission?:()=>Promise<string>};
      try{if(D.requestPermission && await D.requestPermission()!=="granted"){setNotice("Döntés nem engedélyezett. Kapcsold be az érintéses irányítást.");return;}}catch{setNotice("Kapcsold be az érintéses irányítást.");return;}
      input.current.neutral=null;
    }
    input.current.touch=0;
    if(!document.fullscreenElement && document.documentElement.requestFullscreen)void document.documentElement.requestFullscreen().catch(()=>undefined);
    beginRun(simulation.current);setPhase("awakening");
  };
  const toggleSound=()=>{drone.current?.unlock();const next=!sound;setSound(next);try{localStorage.setItem("forest-sound",next?"on":"off");}catch{}};
  const changeTouch=(checked:boolean)=>{drone.current?.unlock();setTouchMode(checked);input.current.touchMode=checked;input.current.touch=0;setNotice("");try{localStorage.setItem("forest-touch",checked?"on":"off");}catch{}};
  return <main className="forest-game dark" data-phase={phase} onPointerDown={e=>{drone.current?.unlock();if(menu || !touchMode)return;e.currentTarget.setPointerCapture(e.pointerId);input.current.touch=e.clientX<e.currentTarget.clientWidth/2?-1:1;}} onPointerMove={e=>{if(e.buttons && !menu && touchMode)input.current.touch=e.clientX<e.currentTarget.clientWidth/2?-1:1;}} onPointerUp={()=>{input.current.touch=0;}} onPointerCancel={()=>{input.current.touch=0;}}>
    {palette && <Canvas dpr={[1,1.5]} camera={{fov:72,position:[0,1.6,0]}} gl={{antialias:true}}>
      <color attach="background" args={[palette.fog]} /><fog attach="fog" args={[palette.fog,7,FOG_END]} />
      <hemisphereLight args={[palette.moon,palette.fog,.85]} /><directionalLight position={[-15,22,-20]} color={palette.moon} intensity={.7} />
      <Environment resolution={64}><Lightformer color={palette.moon} intensity={.7} position={[0,10,0]} rotation-x={Math.PI/2} scale={[25,25,1]} /><Lightformer color={palette.green} intensity={.4} position={[-10,3,-15]} scale={[10,4,1]} /><Lightformer color={palette.purple} intensity={.4} position={[10,3,-15]} scale={[10,4,1]} /></Environment>
      <Suspense fallback={null}><World simulation={simulation} input={input} palette={palette} onPhase={onPhase} /></Suspense><ForestEffects simulation={simulation} />
    </Canvas>}
    {menu && <div className="forest-menu">
      <Button variant="ghost" className="forest-title" onClick={start} aria-label="tap and run!">tap and run!</Button>
      <section className="forest-glass" aria-label="Futás menü">
        <img src={icon} className="forest-mark" alt="" width={64} height={64} />
        <div className="forest-scores"><div><span>Előző futás</span><strong>{last}<small>m</small></strong></div><div><span>Rekord</span><strong>{best}<small>m</small></strong></div></div>
        <div className="forest-settings"><Button variant="ghost" size="icon" onClick={toggleSound} aria-label={sound?"Hang kikapcsolása":"Hang bekapcsolása"} aria-pressed={sound} title="Hang">{sound?<Volume2/>:<VolumeX/>}</Button><label className="forest-touch"><Checkbox checked={touchMode} onCheckedChange={value=>changeTouch(value===true)} aria-label="Érintéses irányítás"/><Smartphone size={17}/><span>Érintés</span></label></div>
        <Button variant="ghost" className="forest-start" onClick={start} aria-label="Futás indítása"><ChevronRight size={25}/></Button>
        {notice && <p role="status" className="forest-notice">{notice}</p>}
      </section>
    </div>}
  </main>;
}
