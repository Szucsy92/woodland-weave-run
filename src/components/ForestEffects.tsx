import { useFrame, useThree } from "@react-three/fiber";
import { useEffect, useMemo, useRef, type MutableRefObject } from "react";
import * as THREE from "three";
import { EffectComposer } from "three/addons/postprocessing/EffectComposer.js";
import { RenderPass } from "three/addons/postprocessing/RenderPass.js";
import { ShaderPass } from "three/addons/postprocessing/ShaderPass.js";
import { OutputPass } from "three/addons/postprocessing/OutputPass.js";
import { DARK_PAUSE, EXIT_TIME, IMPACT_DURATION, LIGHTS_DIE, PAUSE_SLOW, POWERUP_DURATION, RESUME_TIME, SHOCKWAVE_GAP, SHOCKWAVE_RINGS, SHOCKWAVE_SPEED, shockwaveReach, surviveOn, warpEnvelope, holesAround, groundHeight, HOLE_HEIGHT, HOLE_RADIUS, WARP_EXIT_TIME, type Simulation } from "@/lib/forest/simulation";

const LENS_PICKUP = .95 * 1.1, LENS_RING = .5 * 1.1, LENS_END = .45;
export function ForestEffects({ simulation }: { simulation: MutableRefObject<Simulation> }) {
  const { gl, scene, camera, size } = useThree();
  const { composer, effect } = useMemo(() => {
    // The canvas' antialias flag does not reach the composer's off-screen targets, so thin
    // branches and trunk edges came out jagged and crawling. Give the target its own MSAA.
    const target = new THREE.WebGLRenderTarget(1, 1, { type: THREE.HalfFloatType, samples: Math.min(4, gl.capabilities.maxSamples) });
    target.texture.name = "EffectComposer.rt1";
    const composer = new EffectComposer(gl, target);
    const effect = new ShaderPass({
      uniforms: { tDiffuse: { value: null }, blur: { value: 0 }, glitch: { value: 0 }, frost: { value: 0 }, lens: { value: 0 }, wave: { value: 0 }, waveT: { value: 0 }, warp: { value: 0 }, flash: { value: 0 }, hole: { value: new THREE.Vector4(.5, .5, .1, 0) }, reach: { value: Array.from({ length: SHOCKWAVE_RINGS }, (_, i) => shockwaveReach(i, 8.4)) }, time: { value: 0 }, resolution: { value: new THREE.Vector2() } },
      vertexShader: `varying vec2 vUv; void main(){ vUv=uv; gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.); }`,
      fragmentShader: `
        #define RINGS ${SHOCKWAVE_RINGS}
        #define GAP ${SHOCKWAVE_GAP.toFixed(3)}
        #define WSPEED ${SHOCKWAVE_SPEED.toFixed(3)}
        // Ring see-through: low glow plus a wide, soft band = a thin veil rather than a line.
        #define RING_GLOW 0.28
        varying vec2 vUv; uniform sampler2D tDiffuse; uniform float blur,glitch,frost,lens,wave,waveT,warp,flash,time; uniform vec4 hole; uniform float reach[RINGS]; uniform vec2 resolution;
        vec3 sampleBlur(vec2 uv) {
          vec2 d=vec2(blur*8.)/resolution;
          return (texture2D(tDiffuse,uv).rgb*4. + texture2D(tDiffuse,uv+d).rgb + texture2D(tDiffuse,uv-d).rgb + texture2D(tDiffuse,uv+vec2(d.x,-d.y)).rgb + texture2D(tDiffuse,uv+vec2(-d.x,d.y)).rgb)/8.;
        }
        void main(){
          vec2 d=vUv-.5;
          float asp=resolution.x/resolution.y;
          vec2 q=d*vec2(asp,1.);
          float r=length(q);
          // Wormhole: space only sways a little, like a slow jelly; the picture stays the same picture.
          if(warp>.001){
            vec2 sway=vec2(sin(q.y*4.2+time*1.1)+.6*sin(q.y*9.+time*1.9+q.x*3.),cos(q.x*3.6-time*.9)+.6*cos(q.x*8.+time*1.6-q.y*2.5));
            q+=sway*warp*.011;
            q*=1.+warp*.025*sin(time*1.3);
            d=vec2(q.x/asp,q.y);
          }
          // Black hole: light is dragged around the dark body. Whatever lies within the shadow samples
          // the centre of the body (black); outside, the picture is smeared and twisted around it.
          float hring=0.;
          if(hole.w>.001){
            vec2 hq=(hole.xy-.5)*vec2(asp,1.);
            vec2 dh=q-hq;
            float rh=max(length(dh),.0001);
            float rs=hole.z;
            float pull=hole.w*1.15*rs*pow(rs/rh,1.5);
            float tw=hole.w*1.1*exp(-rh/(rs*2.6));
            vec2 dd=mat2(cos(tw),-sin(tw),sin(tw),cos(tw))*dh;
            vec2 src=hq+dd*max(0.,1.-pull/rh);
            q=src; d=vec2(q.x/asp,q.y);
            hring=exp(-pow((rh-rs*1.15)/(rs*.3),2.))*hole.w;
          }
          // Elastic fisheye on pickup: the picture bulges, then springs back and wobbles out.
          vec2 base=.5+d*(1.+lens*dot(q,q)*1.6);
          // Shockwave: three sparse, veil-like translucent purple rings. Each is born at the screen edge
          // and shrinks towards the vanishing point (perspective: radius ~ 1/depth),
          // fading out where the wave's reach ends (three quarters of the forest depth).
          float w=0.;
          for(int i=0;i<RINGS;i++){
            float a=waveT-float(i)*GAP;
            float rc=reach[i];
            float life=rc/WSPEED;
            if(a>0. && a<life){
              float p=a/life;
              float r0=1.25*3./(3.+p*rc);
              float e=(r-r0)/(.03+.11*r0);
              w+=exp(-e*e*.7)*smoothstep(0.,.1,p)*(1.-smoothstep(.6,1.,p));
            }
          }
          w=min(1.,w)*wave;
          base+=q/max(r,.001)*w*.008*vec2(1./asp,1.);
          float band=sin(vUv.y*160.+time*14.)*sin(vUv.y*37.-time*6.);
          vec2 uv=base+vec2(band*glitch*.008,0.);
          vec2 split=vec2(glitch*.012,glitch*.002);
          vec3 c=sampleBlur(uv);
          vec3 left=sampleBlur(uv+split),right=sampleBlur(uv-split);
          c=mix(c,vec3(left.r,right.g,left.b),min(glitch*.85,.85));
          float vignette=1.-smoothstep(.28,.78,length(vUv-.5));
          c*=mix(.46,1.,vignette);
          // Freezer: the world cools to a pale blue, with fine frost crystals creeping in from the screen edges.
          float cold=dot(c,vec3(.3,.55,.15));
          c=mix(c,mix(vec3(cold),c,.55)*vec3(.78,.95,1.18),frost*.7);
          float rim=smoothstep(.3,.8,length(vUv-.5));
          float crystal=pow(max(0.,sin(vUv.x*210.+time*.4)*sin(vUv.y*190.-time*.3)),14.);
          c+=vec3(.55,.8,1.)*frost*(rim*.16+rim*crystal*.5);
          c+=(vec3(.6,.25,1.)*w*.4+vec3(.85,.7,1.)*pow(w,3.)*.1)*RING_GLOW;
          c+=vec3(.78,.5,1.)*hring*.16;
          // Wormhole colour: a faint violet cast, and a flash on the way in and out.
          c=mix(c,c*vec3(.88,.78,1.18)+vec3(0.,.015,.04),warp*.3);
          c*=1.-warp*.1*(.5+.5*sin(time*2.));
          c+=vec3(.82,.64,1.)*flash;
          gl_FragColor=vec4(c,1.);
        }`,
    });
    composer.addPass(new RenderPass(scene, camera));
    composer.addPass(effect);
    composer.addPass(new OutputPass());
    return { composer, effect };
  }, [gl, scene, camera]);
  useEffect(() => {
    composer.setPixelRatio(Math.min(gl.getPixelRatio(), 1.5));
    composer.setSize(size.width, size.height);
    effect.uniforms["resolution"]!.value.set(size.width, size.height);
  }, [composer, effect, size, gl]);
  useEffect(() => () => { effect.dispose(); composer.dispose(); }, [composer, effect]);
  // Smoothed 0..1 frost amount: eases in on pickup and melts away at the end.
  const frost = useRef(0);
  // Shader clock that only ever moves forward. s.elapsed stops while paused and restarts with
  // every run, and s.pauseTime resets at each pause step, so mixing them made the glitch bands
  // and frost crystals jump.
  const clock = useRef(0);
  // Fisheye kick when a power fades: remembers whether one was active and when it ended.
  const hadPower = useRef(false);
  const powerEndedAt = useRef(-99);
  useFrame((_, delta) => {
    const s = simulation.current;
    const lying = s.phase === "falling" || s.phase === "fallen";
    const awake = s.phase === "awakening";
    // 500 ms smooth rise and fall, synced with the audio dip.
    const impact = s.impact > 0 ? Math.sin((1 - s.impact / IMPACT_DURATION) * Math.PI) : 0;
    const occasional = lying ? Math.pow(Math.max(0, Math.sin(s.elapsed * .89 + 2)), 50) * .38 : 0;
    effect.uniforms["blur"]!.value = s.phase === "menu" ? 1.1 : lying ? 1.4 + .5 * Math.sin(s.elapsed * .4) : awake ? (1 - s.phaseTime / 1.15) * 1.2 : impact * 1.7;
    effect.uniforms["glitch"]!.value = impact * 1.1 + occasional + (awake ? Math.pow(Math.sin(s.phaseTime * 8), 2) * (1 - s.phaseTime / 1.15) * .35 : 0);
    // Pause warp: blur and glitch swell while time slows, hold softly while
    // frozen, and drain away as the run accelerates back.
    const p = s.pauseTime;
    let pb = 0, pg = 0;
    if (s.pause === "slowing") { const k = Math.min(1, p / PAUSE_SLOW); pb = k * 1.5; pg = Math.sin(k * Math.PI) * .9 + k * .08; }
    else if (s.pause === "paused") { pb = 1.5 + .2 * Math.sin(p * .6); pg = .08 + Math.pow(Math.max(0, Math.sin(p * 1.3)), 40) * .3; }
    else if (s.pause === "resuming") { const k = Math.min(1, p / RESUME_TIME); pb = (1 - k) * 1.5; pg = Math.sin(k * Math.PI) * .8 + (1 - k) * .08; }
    else if (s.pause === "exiting") { const k = Math.min(1, p / EXIT_TIME); pb = 1.5 + k * 2.5; pg = .3 + Math.sin(k * Math.PI) * .9; }
    // Arriving in the menu: blur drains from full to the menu's soft haze.
    const arrive = s.phase === "menu" ? Math.max(0, 1 - s.phaseTime / .9) * 2.9 : 0;
    effect.uniforms["blur"]!.value = Math.max(effect.uniforms["blur"]!.value + arrive, pb);
    effect.uniforms["glitch"]!.value += pg + arrive * .15;
    // Power-ups: a glitch surge on pickup, a faint shimmer while active and a
    // last flutter as the effect fades.
    if (s.powerUp) {
      const a = s.powerUpTime, left = POWERUP_DURATION - a;
      const surge = a < .5 ? Math.sin((a / .5) * Math.PI) * .7 : 0;
      const fade = left < .6 ? Math.sin((left / .6) * Math.PI) * .45 : 0;
      // The freezer stays calm: a soft cold bloom instead of a glitch tear.
      effect.uniforms["glitch"]!.value += s.powerUp === "freezer" ? surge * .3 : (s.powerUp === "shockwave" ? surge * .5 : surge) + fade + .05;
    }
    // Elastic fisheye on every orb pickup (10% stronger than before): a damped spring kicks in
    // (bulge, overshoot, settle). The shockwave repeats a milder kick as each ring is born, and
    // when any power fades a weaker kick, well under the pickup one, closes it.
    let lens = 0;
    if (s.powerUp) {
      const a = s.powerUpTime;
      const ring = s.powerUp === "shockwave" ? Math.min(SHOCKWAVE_RINGS - 1, Math.floor(a / SHOCKWAVE_GAP)) : 0;
      const age = a - ring * SHOCKWAVE_GAP;
      lens = (ring === 0 ? LENS_PICKUP : LENS_RING) * Math.exp(-age * 3.2) * Math.cos(age * 14);
      hadPower.current = true;
    } else {
      if (hadPower.current) { hadPower.current = false; powerEndedAt.current = clock.current; }
      const age = clock.current - powerEndedAt.current;
      if (age >= 0 && age < 2) lens = LENS_END * Math.exp(-age * 3.2) * Math.cos(age * 14);
    }
    // Falling into a black hole: one big fisheye heave; being thrown out again heaves once more, with a flash.
    let flash = 0;
    if (s.warp >= 0 && s.warp < 1.6) {
      lens += 2.2 * Math.exp(-s.warp * 2.2) * Math.cos(s.warp * 11);
      flash += Math.max(0, 1 - s.warp / .3) * .6;
    }
    if (s.warpOut >= 0) {
      lens += 1.9 * Math.exp(-s.warpOut * 2.6) * Math.cos(s.warpOut * 12);
      flash += Math.exp(-s.warpOut * 3.4) * Math.min(1, (WARP_EXIT_TIME - s.warpOut) / .5) * .9;
    }
    const warp = warpEnvelope(s);
    effect.uniforms["warp"]!.value = warp;
    effect.uniforms["flash"]!.value = flash;
    // The nearest black hole in front of the camera bends the space around it.
    const hole = effect.uniforms["hole"]!.value as THREE.Vector4;
    hole.set(.5, .5, .1, 0);
    if (s.mode === "freeroam" && s.warp < 0) {
      let best: { x: number; z: number } | null = null, bestD = 90;
      for (const h of holesAround(s.x, s.z)) {
        const dist = Math.hypot(h.x - camera.position.x, h.z - camera.position.z);
        if (dist < bestD) { best = h; bestD = dist; }
      }
      if (best) {
        const center = new THREE.Vector3(best.x, groundHeight(best.x, best.z) + HOLE_HEIGHT, best.z);
        const dist3 = Math.max(.5, center.distanceTo(camera.position));
        center.project(camera);
        if (center.z < 1) {
          const fov = ((camera as THREE.PerspectiveCamera).fov ?? 72) * Math.PI / 180;
          const rs = .5 * HOLE_RADIUS / dist3 / Math.tan(fov / 2);
          hole.set(center.x * .5 + .5, center.y * .5 + .5, rs, THREE.MathUtils.clamp((85 - dist3) / 60, 0, 1));
        }
      }
    }
    effect.uniforms["lens"]!.value = lens;
    // Shockwave rings run for the whole power-up, easing in and out.
    effect.uniforms["waveT"]!.value = s.powerUp === "shockwave" ? s.powerUpTime : -1;
    // Every ring runs out as far as its own reach, which follows the pace the power was picked up at.
    if (s.powerUp === "shockwave") effect.uniforms["reach"]!.value = Array.from({ length: SHOCKWAVE_RINGS }, (_, i) => shockwaveReach(i, s.waveSpeed));
    effect.uniforms["wave"]!.value = s.powerUp === "shockwave"
      ? Math.min(1, s.powerUpTime / .2) * Math.min(1, (POWERUP_DURATION - s.powerUpTime) / .3) : 0;
    const frostTarget = s.powerUp === "freezer" ? Math.min(1, (POWERUP_DURATION - s.powerUpTime) / .9) : 0;
    frost.current += (frostTarget - frost.current) * Math.min(1, delta * 4);
    effect.uniforms["frost"]!.value = frost.current < .005 ? 0 : frost.current;
    // Survive atmosphere (survive mode or a freeroam red gate): the red onset hits with a
    // short violent tear.
    if (surviveOn(s) && s.surviveTime >= 0) {
      const r = s.surviveTime - LIGHTS_DIE - DARK_PAUSE;
      if (r >= 0 && r < .45) effect.uniforms["glitch"]!.value += Math.sin((r / .45) * Math.PI) * 1.1;
    }
    effect.uniforms["glitch"]!.value += warp * .12;
    clock.current += Math.min(delta, .1);
    effect.uniforms["time"]!.value = clock.current;
    composer.render(delta);
  }, 1);
  return null;
}
