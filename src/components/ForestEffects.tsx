import { useFrame, useThree } from "@react-three/fiber";
import { useEffect, useMemo, type MutableRefObject } from "react";
import * as THREE from "three";
import { EffectComposer } from "three/addons/postprocessing/EffectComposer.js";
import { RenderPass } from "three/addons/postprocessing/RenderPass.js";
import { ShaderPass } from "three/addons/postprocessing/ShaderPass.js";
import { OutputPass } from "three/addons/postprocessing/OutputPass.js";
import { IMPACT_DURATION, type Simulation } from "@/lib/forest/simulation";

export function ForestEffects({ simulation }: { simulation: MutableRefObject<Simulation> }) {
  const { gl, scene, camera, size } = useThree();
  const { composer, effect } = useMemo(() => {
    const composer = new EffectComposer(gl);
    const effect = new ShaderPass({
      uniforms: { tDiffuse: { value: null }, blur: { value: 0 }, glitch: { value: 0 }, time: { value: 0 }, resolution: { value: new THREE.Vector2() } },
      vertexShader: `varying vec2 vUv; void main(){ vUv=uv; gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.); }`,
      fragmentShader: `
        varying vec2 vUv; uniform sampler2D tDiffuse; uniform float blur,glitch,time; uniform vec2 resolution;
        vec3 sampleBlur(vec2 uv) {
          vec2 d=vec2(blur*8.)/resolution;
          return (texture2D(tDiffuse,uv).rgb*4. + texture2D(tDiffuse,uv+d).rgb + texture2D(tDiffuse,uv-d).rgb + texture2D(tDiffuse,uv+vec2(d.x,-d.y)).rgb + texture2D(tDiffuse,uv+vec2(-d.x,d.y)).rgb)/8.;
        }
        void main(){
          float band=sin(vUv.y*160.+time*14.)*sin(vUv.y*37.-time*6.);
          vec2 uv=vUv+vec2(band*glitch*.008,0.);
          vec2 split=vec2(glitch*.012,glitch*.002);
          vec3 c=sampleBlur(uv);
          vec3 left=sampleBlur(uv+split),right=sampleBlur(uv-split);
          c=mix(c,vec3(left.r,right.g,left.b),min(glitch*.85,.85));
          float vignette=1.-smoothstep(.28,.78,length(vUv-.5));
          c*=mix(.46,1.,vignette);
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
  useEffect(() => () => composer.dispose(), [composer]);
  useFrame((_, delta) => {
    const s = simulation.current;
    const lying = s.phase === "falling" || s.phase === "fallen";
    const awake = s.phase === "awakening";
    // 500 ms smooth rise and fall, synced with the audio dip.
    const impact = s.impact > 0 ? Math.sin((1 - s.impact / IMPACT_DURATION) * Math.PI) : 0;
    const occasional = lying ? Math.pow(Math.max(0, Math.sin(s.elapsed * .89 + 2)), 50) * .38 : 0;
    effect.uniforms["blur"]!.value = s.phase === "menu" ? 1.1 : lying ? 1.4 + .5 * Math.sin(s.elapsed * .4) : awake ? (1 - s.phaseTime / 1.15) * 1.2 : impact * 1.7;
    effect.uniforms["glitch"]!.value = impact * 1.1 + occasional + (awake ? Math.pow(Math.sin(s.phaseTime * 8), 2) * (1 - s.phaseTime / 1.15) * .35 : 0);
    effect.uniforms["time"]!.value = s.elapsed;
    composer.render(delta);
  }, 1);
  return null;
}
