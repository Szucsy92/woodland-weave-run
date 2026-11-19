package com.woodlandweave.run.gl

object Shaders {
    private const val GROUND_FN = """
float gh(float x, float z) {
  float w1 = sin(x * 0.11 + sin(z * 0.07) * 2.4) * cos(z * 0.09 + sin(x * 0.05) * 2.1);
  float w2 = sin(z * 0.23 - sin(x * 0.16) * 1.8) * cos(x * 0.19 + sin(z * 0.12) * 1.4);
  float w3 = sin((x + z) * 0.47 + sin(x * 0.3) * 1.2);
  return (w1 * 1.15 + w2 * 0.55 + w3 * 0.35) * 0.3;
}
"""


    /** Wind (High tier): the whole tree leans with a gust. A smooth field in world space, so branches and crowns stay attached. */
    private const val WIND_FN = """
uniform float uTime;
uniform vec3 uSway;   // x: sway of a tree top (metres); y, z unused
vec4 placeVertex(mat4 m, vec3 p) {
  vec4 w = m * vec4(p, 1.0);
  if (uSway.x > 0.0) {
    float h = smoothstep(1.5, 15.0, w.y);
    float ph = uTime * 1.25 + w.x * 0.21 + w.z * 0.17;
    float gust = sin(ph) + 0.5 * sin(ph * 2.3 + 1.7) + 0.25 * sin(uTime * 0.37 + w.x * 0.05);
    w.xz += vec2(0.82, 0.57) * gust * uSway.x * h;
  }
  return w;
}
"""

    val LIT_VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNor;
layout(location=2) in vec2 aUv;
layout(location=3) in vec4 iA;
layout(location=4) in vec4 iB;
layout(location=5) in vec4 iC;
layout(location=6) in vec4 iD;
uniform mat4 uVP;
out vec3 vN; out vec3 vW; out vec2 vUv; out float vLY;
$WIND_FN
void main() {
  mat4 m = mat4(iA, iB, iC, iD);
  vLY = aPos.y;
  vec4 w = placeVertex(m, aPos);
  vec3 s2 = vec3(dot(m[0].xyz, m[0].xyz), dot(m[1].xyz, m[1].xyz), dot(m[2].xyz, m[2].xyz));
  vN = mat3(m) * (aNor / max(s2, vec3(1e-6)));
  vW = w.xyz; vUv = aUv;
  gl_Position = uVP * w;
}
"""

    val GROUND_VS = """#version 300 es
layout(location=0) in vec2 aXZ;
uniform mat4 uVP; uniform vec2 uOff;
out vec3 vN; out vec3 vW; out vec2 vUv; out float vLY;
$GROUND_FN
void main() {
  vLY = 0.0;
  vec2 w = aXZ + uOff;
  float h = gh(w.x, w.y);
  float e = 0.4;
  float hx = gh(w.x + e, w.y) - gh(w.x - e, w.y);
  float hz = gh(w.x, w.y + e) - gh(w.x, w.y - e);
  vN = normalize(vec3(-hx / (2.0 * e), 1.0, -hz / (2.0 * e)));
  vW = vec3(w.x, h, w.y);
  vUv = w / 3.125;
  gl_Position = uVP * vec4(vW, 1.0);
}
"""

    /** The lit fragment shader; [litFs] adds the quality defines (Q_ENV, Q_SHADOW). */
    fun litFs(defines: String) = LIT_FS.replace("//DEFINES", defines)

    val LIT_FS = """#version 300 es
precision highp float;
precision highp sampler2DShadow;
//DEFINES
in vec3 vN; in vec3 vW; in vec2 vUv; in float vLY;
uniform vec3 uColor, uEmissive, uFog, uCam, uFwd, uSky, uGroundC, uMoonDir, uMoon, uLampPos, uLampCol, uRedPos, uRedCol, uFloodDir, uFloodCol;
uniform vec4 uLocalPos[4]; // xyz = light position, w = intensity
uniform vec4 uOcc[30];      // trunks that can block a light: xz, radius (6 per light; slots 0..3 pickups, 4 the lamp)
uniform float uLodBias;
uniform vec4 uLocalColor[4]; // rgb = light colour, w = radius
uniform float uLampSoft;
uniform float uAlpha, uMaps, uFogNear, uFogFar, uHemi, uLampI, uRedI, uNormalScale, uRough, uLights, uRed, uFogOn, uEncode, uMetal;
uniform int uMode;
uniform vec2 uUvScale;
uniform sampler2D uTex, uNorm, uRoughTex;
uniform float uTime;
#ifdef Q_SHADOW
uniform float uShadowOn, uShadowK;
uniform mat4 uLightVP0;
uniform sampler2DShadow uShadow0;
uniform float uShadowTexel;
float pcf3(sampler2DShadow s, vec3 c, float tx) {
  float sum = 0.0;
  for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) sum += texture(s, vec3(c.xy + vec2(float(i), float(j)) * tx, c.z));
  return sum / 9.0;
}
float shadowRaw(vec3 wp, vec3 n) {
  if (uShadowOn < 0.5) return 1.0;
  vec3 p = wp + n * 0.06;
  vec4 l0 = uLightVP0 * vec4(p, 1.0);
  vec3 s0 = l0.xyz / l0.w * 0.5 + 0.5;
  if (s0.x > 0.01 && s0.x < 0.99 && s0.y > 0.01 && s0.y < 0.99 && s0.z < 1.0)
    return pcf3(uShadow0, vec3(s0.xy, s0.z - 0.0008), uShadowTexel);
  return 1.0;
}
float shadowAt(vec3 wp, vec3 n) { return mix(1.0, shadowRaw(wp, n), uShadowK); }
#endif
out vec4 o;
const float PI = 3.14159265;
vec3 srgbToLinear(vec3 c) {
  vec3 lo = c / 12.92;
  vec3 hi = pow((c + 0.055) / 1.055, vec3(2.4));
  return vec3(
    c.r <= 0.04045 ? lo.r : hi.r,
    c.g <= 0.04045 ? lo.g : hi.g,
    c.b <= 0.04045 ? lo.b : hi.b
  );
}
vec3 linearToSrgb(vec3 c) {
  c = max(c, vec3(0.0));
  vec3 lo = c * 12.92;
  vec3 hi = 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055;
  return vec3(
    c.r <= 0.0031308 ? lo.r : hi.r,
    c.g <= 0.0031308 ? lo.g : hi.g,
    c.b <= 0.0031308 ? lo.b : hi.b
  );
}
float falloff(float d, float cutoff) { float r = d / cutoff; return pow(clamp(1.0 - r * r * r * r, 0.0, 1.0), 2.0); }
// Analytic shadow of the nearby trunks (vertical cylinders) for a point light: the ray from the light to the shaded
// point must clear every listed trunk. Soft edges; a point on the trunk itself is never blocked by its own trunk.
float trunkShadow(int slot, vec3 lp, vec3 p) {
  vec2 a = lp.xz; vec2 ab = p.xz - a;
  float len2 = max(dot(ab, ab), 1e-4);
  float s = 1.0;
  for (int j = 0; j < 6; j++) {
    vec4 t = uOcc[slot * 6 + j];
    if (t.z <= 0.0) break;
    vec2 ac = t.xy - a;
    float h = clamp(dot(ac, ab) / len2, 0.0, 1.0);
    float dd = length(ac - ab * h);
    float self = smoothstep(t.z * 1.05, t.z * 1.5, length(p.xz - t.xy));
    s *= mix(1.0, smoothstep(t.z * 0.75, t.z * 1.35, dd), self);
  }
  return s;
}
float vGGX(float a, float nl, float nv) {
  float a2 = a * a;
  float gv = nl * sqrt(a2 + (1.0 - a2) * nv * nv);
  float gl = nv * sqrt(a2 + (1.0 - a2) * nl * nl);
  return 0.5 / max(gv + gl, 1e-6);
}
float dGGX(float a, float nh) { float a2 = a * a; float d = nh * nh * (a2 - 1.0) + 1.0; return a2 / (PI * d * d); }
// Diffuse + GGX specular for one light, the way three.js's standard material shades it.
vec3 light(vec3 n, vec3 v, vec3 l, vec3 irradiance, vec3 albedo, float alpha) {
  float nl = max(dot(n, l), 0.0);
  if (nl <= 0.0) return vec3(0.0);
  vec3 h = normalize(l + v);
  float nv = max(abs(dot(n, v)), 1e-4), nh = max(dot(n, h), 0.0), vh = max(dot(v, h), 0.0);
  // MeshStandardMaterial: diffuse fades with metalness, the specular colour turns towards the albedo.
  vec3 f0 = mix(vec3(0.04), albedo, uMetal);
  vec3 F = f0 + (vec3(1.0) - f0) * pow(1.0 - vh, 5.0);
  return (albedo * (1.0 - uMetal) / PI + F * vGGX(alpha, nl, nv) * dGGX(alpha, nh)) * irradiance * nl;
}
vec3 perturb(vec3 n, vec3 pos, vec2 uv, vec3 m) {
  vec3 q0 = dFdx(pos), q1 = dFdy(pos);
  vec2 s0 = dFdx(uv), s1 = dFdy(uv);
  vec3 q1p = cross(q1, n), q0p = cross(n, q0);
  vec3 T = q1p * s0.x + q0p * s1.x;
  vec3 B = q1p * s0.y + q0p * s1.y;
  float det = max(dot(T, T), dot(B, B));
  float sc = det == 0.0 ? 0.0 : inversesqrt(det);
  return normalize(T * (m.x * sc) + B * (m.y * sc) + n * m.z);
}
// three.js Fog is smoothstep(near, far, depth) on the view-space depth (not the radial distance).
vec3 finish(vec3 col, float depth) {
  float f = smoothstep(uFogNear, uFogFar, depth) * uFogOn;
  col = mix(col, uFog, f);
  // Linear HDR target: keep highlights above 1 (the effect pass encodes). Fallback RGBA8 target: encode here.
  if (uEncode > 0.5) return linearToSrgb(clamp(col, 0.0, 1.0));
  return clamp(col, vec3(0.0), vec3(60000.0));
}
void main() {
  vec3 toCam = uCam - vW;
  float dist = length(toCam);
  vec3 v = toCam / max(dist, 1e-4);
  float depth = dot(vW - uCam, uFwd);
  if (uMode == 1) { o = vec4(finish(uColor, depth), texture(uTex, vUv).a * uAlpha); return; }
  if (uMode == 2) { o = vec4(finish(uColor, depth), uAlpha); return; }
  vec3 n = normalize(vN);
  if (!gl_FrontFacing) n = -n;
  // Specular anti-aliasing like three.js: rougher where the geometric normal changes quickly.
  vec3 dxy = max(abs(dFdx(n)), abs(dFdy(n)));
  float geometryRoughness = max(max(dxy.x, dxy.y), dxy.z);
  vec3 albedo = uColor;
  float rough = uRough;
  // Texture rows start at the top of the image here, three.js flips them: undo that so maps and normals line up.
  vec2 tuv = vec2(vUv.x * uUvScale.x, (1.0 - vUv.y) * uUvScale.y);
  if (uMaps > 0.5) {
    // The colour maps are SRGB8_ALPHA8 textures: the sampler already returns filtered linear light.
    albedo *= texture(uTex, tuv, uLodBias).rgb;
    rough *= texture(uRoughTex, tuv, uLodBias).g;
    vec3 mn = texture(uNorm, tuv, uLodBias).xyz * 2.0 - 1.0;
    mn.xy *= uNormalScale;
    n = perturb(n, vW, vec2(tuv.x, -tuv.y), mn);
  }
  vec3 emissive = uEmissive;
  float outA = uAlpha;
  if (uMode == 3) {
    // Mossy patches, darker damp earth and leaf litter baked into the ground colour.
    float mossN = sin(vW.x * .21 + sin(vW.z * .17) * 2.) * sin(vW.z * .19 - sin(vW.x * .13) * 1.7);
    float litN = sin(vW.x * 1.3 + vW.z * .7) * sin(vW.z * 1.1 - vW.x * .5);
    float moss = smoothstep(.25, .75, mossN);
    // smoothstep requires edge0 < edge1; invert the result for the damp-area mask.
    float damp = 1.0 - smoothstep(-.6, .2, mossN);
    albedo = mix(albedo, albedo * vec3(.72, 1.18, .74), moss * .6);
    albedo *= 1. - damp * .3;
    albedo = mix(albedo, albedo * vec3(1.25, 1.02, .78), smoothstep(.55, .95, litN) * .35);
#ifdef Q_ENV
    // Puddles: dark, glassy, flat water that mirrors the sky.
    float pn = sin(vW.x * .37 + sin(vW.z * .29) * 2.3) * sin(vW.z * .33 - sin(vW.x * .21) * 1.9) + .5 * sin(vW.x * 1.7 + vW.z * 1.3) * sin(vW.z * 1.9 - vW.x * 1.1);
    float pud = smoothstep(.55, .85, pn);
    albedo *= 1. - pud * .55;
    float wet = clamp(damp * .52 + pud * .95, 0.0, 1.0);
    // Wet ground darkens slightly and tightens the GGX highlight; puddles flatten their normal.
    albedo *= 1.0 - wet * .16;
    rough = mix(rough, max(.055, rough * .30), wet);
    n = normalize(mix(n, vec3(0., 1., 0.), pud * .92));
#endif
  } else if (uMode == 4) {
    // Contact darkening and moss at the trunk foot, plus a soft wet-bark rim glint near the runner.
    float foot = 1. - smoothstep(.72, 2.8, vLY);
    float rootCrease = foot * (0.5 + 0.5 * sin(vW.x * 3.7 + vW.z * 2.9));
    albedo = mix(albedo, albedo * vec3(.70, 1.08, .68), foot * .48);
    albedo *= 1.0 - rootCrease * .12;
    albedo *= mix(.52, 1., smoothstep(.72, 2.4, vLY)) * mix(1., .72, smoothstep(10., 18., vLY));
    float near = 1. - smoothstep(4., 14., dist);
    float rim = pow(1. - abs(dot(n, v)), 3.);
    vec3 tint = mix(vec3(.18, .55, .35), vec3(.42, .26, .62), smoothstep(-.3, .3, sin(vLY * .35)));
    emissive += mix(tint, vec3(.95, .12, .06), uRed) * rim * near * .22 * smoothstep(.6, 2., vLY) * mix(uLights, .8, uRed);
  } else if (uMode == 5) {
    // Purple-green shards of light catching the leaf undersides.
    float under = 1.0 - smoothstep(-.75, .15, n.y);
    float shard = .55 + .45 * sin(vW.x * .8 + sin(vW.z * .6) * 2.2) * sin(vW.z * .7 - sin(vW.x * .5) * 1.8);
    vec3 glowTint = mix(vec3(.16, .5, .3), vec3(.42, .25, .62), .5 + .5 * sin(vW.x * .23 + vW.z * .31));
    emissive += mix(glowTint, vec3(.95, .12, .06), uRed) * under * shard * .3 * mix(uLights, .8, uRed);
  } else if (uMode == 6) {
    // Bushes: a soft silhouette rim so undergrowth reads against the dark floor.
    float prim = pow(1. - abs(dot(n, v)), 2.4);
    float pfar = smoothstep(3., 9., dist) * (1. - smoothstep(26., 40., dist));
    vec3 pTint = mix(vec3(.2, .55, .34), vec3(.5, .32, .7), .5 + .5 * sin(vW.x * .27 + vW.z * .19));
    emissive += mix(pTint, vec3(.95, .12, .06), uRed) * prim * (.18 + .3 * pfar) * mix(uLights, .8, uRed);
  }
  float alpha = pow(min(max(rough, 0.0525) + geometryRoughness, 1.0), 2.0);
  vec3 col = albedo / PI * (mix(uGroundC, uSky, n.y * 0.5 + 0.5) * uHemi);
  float sh = 1.0;
  vec3 keyL = normalize(uMoonDir);
#ifdef Q_SHADOW
  // Canopy blobs overlap by design, so they sample the shadow map from a point well above their own surface (outside
  // their own blob): they then only see shadows cast by other crowns and trunks, never self-shadow acne.
  if (uMode == 5) sh = shadowAt(vW + keyL * 4.5, vec3(0.0));
  else sh = shadowAt(vW, n);
#endif
  // Dappled, drifting light: patches of sun / moonlight wander over trunks, ground, bushes and leaves.
  float dapA = sin(vW.x * 0.9 + sin(vW.z * 0.7) * 1.6);   // static dapple: the light pools stay fixed in the world
  float dapB = sin(vW.z * 0.8 + sin(vW.x * 0.6) * 1.4);
  float dap = smoothstep(-0.55, 0.65, dapA * dapB);
  // Ground receives a stronger, broader dapple so the incoming key light reads as soft pools
  // between the trees. Keep foliage contrast unchanged to avoid washing out the canopy.
  if (uMode == 3) sh *= mix(0.48, 1.62, dap);
  else sh *= mix(0.6, 1.3, dap) * (uMode == 5 ? 0.7 : 1.0) + (uMode == 5 ? 0.3 : 0.0);
  if (uMode == 5) {
    // Foliage is translucent: wrapped diffuse on the lit side, and the light shining through leaves seen against it.
    vec3 leaf = albedo + vec3(.10, .22, .07) * 1.4;
    float knl = dot(n, keyL);
    float wrapD = clamp((knl + 0.45) / 1.45, 0.0, 1.0);
    float back = pow(clamp(dot(-v, keyL), 0.0, 1.0), 1.6) * clamp(0.65 - knl * 0.5, 0.0, 1.0);
    col += leaf / PI * uMoon * sh * (wrapD * 0.9 + back * 1.6);
  } else {
    // A modest ground-only irradiance lift makes sun/moon pools visible on the forest floor
    // without raising exposure or bleaching trunks and leaves.
    float keyBoost = (uMode == 3) ? 1.48 : 1.0;
    col += light(n, v, keyL, uMoon * sh * keyBoost, albedo, alpha);
  }
  vec3 tl = uLampPos - vW; float d = length(tl);
  // Medium and High soften the very near field, so a trunk next to the lamp no longer burns out to white.
  vec3 lampIrr = uLampCol * (uLampI / max(pow(d, 1.6) + uLampSoft, 0.01)) * falloff(d, 27.0) * trunkShadow(4, uLampPos, vW);
  col += light(n, v, tl / max(d, 1e-3), lampIrr, albedo, alpha);
  // A small, quality-tiered set of nearby pickups contributes coloured local illumination.
  // The fixed compile-time loop keeps GLES 3.0 work predictable; inactive slots have zero intensity.
  for (int i = 0; i < LOCAL_LIGHT_COUNT; ++i) {
    if (uLocalPos[i].w > 0.001) {
      tl = uLocalPos[i].xyz - vW;
      d = length(tl);
      float localAtten = (uLocalPos[i].w / max(d * d * 0.22 + 0.8, 0.01)) * falloff(d, uLocalColor[i].w);
      // Light stays low and is blocked by trunks: it no longer glows through a tree or lights the crowns far above.
      localAtten *= trunkShadow(i, uLocalPos[i].xyz, vW) * (1.0 - smoothstep(3.5, 8.5, vW.y - uLocalPos[i].y));
      col += light(n, v, tl / max(d, 1e-3), uLocalColor[i].rgb * localAtten, albedo, alpha);
    }
  }
#ifdef Q_ENV
  {
    // Cheap image-based specular: the sky/ground gradient mirrored by the surface (wet bark, puddles, leaves).
    vec3 rv = reflect(-v, n);
    vec3 env = mix(uGroundC, uSky, clamp(rv.y * 0.5 + 0.5, 0.0, 1.0)) * uHemi;
    float fr = pow(1.0 - clamp(dot(n, v), 0.0, 1.0), 5.0);
    float sm = 1.0 - min(rough + geometryRoughness * .65, 1.0);
    // Fresnel-weighted, roughness-filtered environment reflection. The sky/ground gradient
    // gives puddles a broad reflection while bark keeps a narrow, restrained wet glint.
    float envF = mix(.035, .72, fr);
    float envGain = sm * sm * (0.18 + 1.05 * fr);
    col += env * envF * envGain * 1.35;
  }
#endif
  tl = uRedPos - vW; d = length(tl);
  col += light(n, v, tl / max(d, 1e-3), uRedCol * (uRedI / max(pow(d, 1.1), 0.01)) * falloff(d, 70.0), albedo, alpha);
  col += light(n, v, normalize(uFloodDir), uFloodCol, albedo, alpha);
  o = vec4(finish(col + emissive, depth), outA);
}
"""

    val SHADOW_VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=3) in vec4 iA;
layout(location=4) in vec4 iB;
layout(location=5) in vec4 iC;
layout(location=6) in vec4 iD;
uniform mat4 uLightVP;
uniform vec2 uCamXZ, uFade;
$WIND_FN
void main() {
  mat4 m = mat4(iA, iB, iC, iD);
  // Casters far from the runner shrink smoothly to nothing (about their own origin), so the finite edge of the generated
  // forest never makes a shadow, and with it a light beam, pop in or out; the light streams in over the edge instead.
  float f = 1.0 - smoothstep(uFade.x, uFade.y, distance(m[3].xz, uCamXZ));
  gl_Position = uLightVP * placeVertex(m, aPos * f);
}
"""

    val SHADOW_FS = """#version 300 es
precision mediump float;
void main() {}
"""

    val SPRITE_VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec2 aSize;
layout(location=2) in vec4 aCol;
layout(location=3) in float aGround;   // height of the centre above the ground (0 = no ground fade)
uniform mat4 uVP; uniform vec3 uRight, uUp, uCam, uFwd;
out vec2 vUv; out vec4 vCol; out float vDepth; out float vFade; out float vWY; out float vGY; out float vSoft;
void main() {
  vec2 c = vec2(float(gl_VertexID & 1) * 2.0 - 1.0, float(gl_VertexID >> 1) * 2.0 - 1.0);
  vec3 w = aPos + uRight * c.x * aSize.x * 0.5 + uUp * c.y * aSize.y * 0.5;
  vUv = vec2(c.x * 0.5 + 0.5, 0.5 - c.y * 0.5); vCol = aCol;
  // three.js sprites fog on the view depth of their centre.
  vDepth = dot(aPos - uCam, uFwd);
  // Passing through a light must not flash or pop: a sprite dissolves over its whole radius as the camera enters it
  // (and returns the same way on the far side).
  float nearR = clamp(aSize.x * 0.5, 0.3, 18.0);
  float nt = clamp((distance(uCam, aPos) - nearR * 0.12) / (nearR * 0.88), 0.0, 1.0);
  vFade = nt * nt * nt * (nt * (nt * 6.0 - 15.0) + 10.0);
  // Glows that reach into the ground fade out softly towards it instead of ending in a hard edge.
  vWY = w.y;
  vGY = aGround > 0.0 ? aPos.y - aGround : -1000.0;
  vSoft = clamp(aSize.x * 0.08, 0.25, 2.5);
  gl_Position = uVP * vec4(w, 1.0);
}
"""

    val SPRITE_FS = """#version 300 es
precision highp float;
in vec2 vUv; in vec4 vCol; in float vDepth; in float vFade; in float vWY; in float vGY; in float vSoft;
uniform sampler2D uTex;
uniform vec3 uFog;
uniform float uFogNear, uFogFar, uFogOn, uEncode;
out vec4 o;
// Fragment shaders are compiled independently: keep the sRGB encoder local to this shader.
vec3 linearToSrgb(vec3 c) {
  c = max(c, vec3(0.0));
  vec3 lo = c * 12.92;
  vec3 hi = 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055;
  return vec3(
    c.r <= 0.0031308 ? lo.r : hi.r,
    c.g <= 0.0031308 ? lo.g : hi.g,
    c.b <= 0.0031308 ? lo.b : hi.b
  );
}
void main() {
  float a = texture(uTex, vUv).a * vCol.a * vFade * smoothstep(0.0, vSoft, vWY - vGY);
  vec3 col = vCol.rgb;
  // SpriteMaterial fogs by default; the glow clusters, spores and black-hole halos switch it off.
  col = mix(col, uFog, smoothstep(uFogNear, uFogFar, vDepth) * uFogOn);
  o = vec4(uEncode > 0.5 ? linearToSrgb(clamp(col, 0.0, 1.0)) : col, a);
}
"""

    val POST_VS = """#version 300 es
out vec2 vUv;
void main() {
  vec2 c = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
  vUv = c;
  gl_Position = vec4(c * 2.0 - 1.0, 0.0, 1.0);
}
"""

    /**
     * Port of the web build's ForestEffects pass (blur, glitch, lens, frost, shockwave rings, wormhole,
     * black hole, hue) plus what the browser did around it: the OutputPass (sRGB encode, here with a
     * triangular dither so the dark fog gradients never band) and the CSS glass panels (backdrop blur,
     * saturate, tint, drop shadow).
     */
    fun postFs(rings: Int, gap: Double, speed: Double, defines: String = ""): String {
        fun f(v: Double) = String.format(java.util.Locale.US, "%.3f", v)
        return """#version 300 es
precision highp float;
#define RINGS $rings
#define GAP ${f(gap)}
#define WSPEED ${f(speed)}
#define RING_GLOW 0.28
$defines
precision highp sampler2D;
precision highp sampler2DShadow;
in vec2 vUv;
uniform sampler2D tDiffuse;
uniform float blur, glitch, frost, lens, wave, waveT, warp, flash, hue, time;
uniform float uSrcSrgb;
uniform float uSharpen;
uniform vec4 hole;
uniform float reach[RINGS];
uniform vec2 resolution;
// Glass panels, in dp with the origin at the bottom left of the screen.
uniform vec2 uRootDp;
uniform vec4 uGlassRect[2];
uniform vec4 uGlassParam[2];   // x blur sigma (dp), y saturate, z shadow (0/1), w opacity
uniform float uGlassCount;
#ifdef Q_FULL
uniform sampler2D uBloom;
uniform float uTone, uExposure, uBloomK, uGrain, uChroma;
#endif
#ifdef Q_DEPTH
uniform sampler2D uDepth, uAO;
uniform vec2 uNF, uTanAspect;
uniform mat4 uInvVP, uVP;
uniform vec3 uCamPos, uMoonDir, uMoonCol;
uniform float uAoK, uShaftSteps, uShaftMax, uFogDens, uDofK, uFocus, uMotion;
#ifdef Q_SHAFTS
uniform mat4 uLightVP0;
uniform sampler2DShadow uShadowC0;
#endif
#endif
out vec4 o;
vec3 srgbToLinear(vec3 c) {
  vec3 lo = c / 12.92;
  vec3 hi = pow((c + 0.055) / 1.055, vec3(2.4));
  return vec3(
    c.r <= 0.04045 ? lo.r : hi.r,
    c.g <= 0.04045 ? lo.g : hi.g,
    c.b <= 0.04045 ? lo.b : hi.b
  );
}
vec3 linearToSrgb(vec3 c) {
  c = max(c, vec3(0.0));
  vec3 lo = c * 12.92;
  vec3 hi = 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055;
  return vec3(
    c.r <= 0.0031308 ? lo.r : hi.r,
    c.g <= 0.0031308 ? lo.g : hi.g,
    c.b <= 0.0031308 ? lo.b : hi.b
  );
}
// The scene is linear HDR (or, on the RGBA8 fallback, gamma encoded); all effect maths runs in linear light.
vec3 tap(vec2 uv) {
  vec3 c = textureLod(tDiffuse, uv, 0.0).rgb;
  return uSrcSrgb > 0.5 ? srgbToLinear(c) : c;
}
vec3 sampleBlur(vec2 uv) {
  vec2 d = vec2(blur * 8.0) / resolution;
  return (tap(uv) * 4.0 + tap(uv + d) + tap(uv - d) + tap(uv + vec2(d.x, -d.y)) + tap(uv + vec2(-d.x, d.y))) / 8.0;
}
// Interleaved gradient noise: cheap, blue-ish, no visible pattern.
float ign(vec2 p) { return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715)))); }
#ifdef Q_FULL
vec3 acesFit(vec3 x) { return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0); }
// Gentle highlight roll-off: bright lights bend towards white instead of clipping into flat blobs.
vec3 softShoulder(vec3 x) {
  vec3 over = max(x - 0.7, vec3(0.0));
  return min(x, vec3(0.7)) + 0.3 * (1.0 - exp(-over / 0.3));
}
#endif
#ifdef Q_DEPTH
float linZ(float d) { float z = d * 2.0 - 1.0; return 2.0 * uNF.x * uNF.y / ((uNF.y + uNF.x) - z * (uNF.y - uNF.x)); }
vec3 worldAt(vec2 uv, float d) { vec4 p = uInvVP * vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0); return p.xyz / p.w; }
// Circle of confusion: the far field blurs fully, the near field only gently (near canopy and bushes used to smear
// into ghost copies and stripes).
float cocOf(float z) {
  float c = abs(z - uFocus) / max(z, 0.5) * uDofK;
  if (z < uFocus) c *= 0.35;
  return clamp(c, 0.0, 1.0);
}
vec3 dofGather(vec2 uv, vec3 c0) {
  float asp = resolution.x / resolution.y;
  float z = linZ(texture(uDepth, uv).r);
  float coc = max(cocOf(z) - 0.05, 0.0);
  if (coc < 0.01) return c0;
  float rad = coc * 0.011;
  // The tap spiral is rotated per pixel: fixed taps echo bright and dark edges as regular copies and horizontal stripes,
  // a rotated spiral turns that into fine noise that the dither hides.
  float rot = ign(gl_FragCoord.xy) * 6.2831853;
  vec3 acc = c0; float ws = 1.0;
  for (int i = 0; i < 16; i++) {
    float fi = (float(i) + 0.5) / 16.0;
    float a = float(i) * 2.39996323 + rot;
    vec2 suv = uv + vec2(cos(a), sin(a)) * sqrt(fi) * rad * vec2(1.0 / asp, 1.0);
    float sz = linZ(texture(uDepth, suv).r);
    float sc = cocOf(sz);
    float w = clamp(sc / max(coc, 0.01), 0.0, 1.0);
    acc += tap(suv) * w; ws += w;
  }
  return acc / ws;
}
#ifdef Q_SHAFTS
// Beams beyond the reach of the shadow map, and of the shadow casters (which fade out 26-52 m from the runner), are carried
// on by a pattern of light columns that lives in the light's own plane. A point's position ACROSS the light, (a, b), is the
// same along a whole beam, so a pattern over (a, b) gives straight beams that run parallel to the light, exactly like the
// real ones, on to the horizon. The coordinates are fixed in the world (not in the camera or in the map), so the beams stay
// put while the runner moves, and they are there in every direction he looks: ahead, and behind to the south once he turns.
float bmHash(vec2 p) { p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
float bmNoise(vec2 p) {
  vec2 i = floor(p), f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  return mix(mix(bmHash(i), bmHash(i + vec2(1.0, 0.0)), f.x), mix(bmHash(i + vec2(0.0, 1.0)), bmHash(i + vec2(1.0, 1.0)), f.x), f.y);
}
float beamField(vec3 wp) {
  vec3 f = -normalize(uMoonDir);
  vec3 r = normalize(vec3(-f.z, 0.0, f.x));   // the same light basis as ShadowMap.update
  vec3 u = cross(r, f);
  vec2 ab = vec2(dot(wp, r), dot(wp, u));
  float n = 0.6 * bmNoise(ab / 3.0) + 0.4 * bmNoise(ab / 1.25 + 17.0);
  return 0.25 + 0.75 * smoothstep(0.38, 0.62, n);   // mean about .62, the shadow map's average
}
// Distance from the runner (m) over which the real shadow map hands the beams over to beamField.
const float REAL_NEAR = 28.0;
const float REAL_FAR = 48.0;
float shadowMarch(vec3 wp) {
  float v = beamField(wp);
  vec4 l = uLightVP0 * vec4(wp, 1.0);
  vec3 s = l.xyz / l.w * 0.5 + 0.5;
  // The real shadow pattern fades into the continuation near the map's border and with distance from the runner, so neither
  // the edge of the map nor the end of the shadow casters shows as a moving line of light.
  vec2 e2 = min(s.xy, 1.0 - s.xy);
  float edge = smoothstep(0.0, 0.18, min(e2.x, e2.y));
  float w = edge * (1.0 - smoothstep(REAL_NEAR, REAL_FAR, distance(wp.xz, uCamPos.xz)));
  if (w > 0.002) {
    float sv = (s.z < 1.0) ? texture(uShadowC0, vec3(clamp(s.xy, 0.0, 1.0), s.z)) : 1.0;
    v = mix(v, sv, w);
  }
  return v;
}
// Light shafts: march the view ray through height fog, asking the shadow map where the moon reaches.
// The near march (uShaftMax, dense world-anchored steps) is followed by a few coarser samples that carry the beams on to the
// horizon haze. The scene itself is only dimmed over the near range: farther away it is already fog-coloured.
const float SHAFT_FAR = 120.0;
const int SHAFT_FAR_STEPS = 12;
const float SHAFT_FAR_GAIN = 0.6;
vec3 shafts(vec3 c, vec2 uv, float d) {
  vec3 ro = uCamPos;
  vec3 rd = worldAt(uv, d) - ro;
  float len = length(rd);
  rd /= max(len, 1e-3);
  float lenAll = len;
  len = min(len, uShaftMax);
  int N = int(uShaftSteps);
  // The samples sit on planes that are fixed in the WORLD (equal spacing, anchored to the world, not to the camera), so as
  // the runner moves the sample points do not slide through the beams. Sliding samples are what made thin beams flicker,
  // swim and jump: with the old spacing (a function of the scene depth behind each pixel) every step moved every beam.
  float dtFix = uShaftMax / float(N);
  float ph = fract(-dot(ro, rd) / dtFix);
  float j = ign(gl_FragCoord.xy);   // static per-pixel offset over a whole step: the world anchor stays, the banding (dashed lines) goes
  float cosT = dot(rd, uMoonDir);
  float g = 0.55;   // wider lobe: the beams still glow when the light is 20-30 degrees off the view axis
  float phase = (1.0 - g * g) / (12.566 * pow(max(1.0 + g * g - 2.0 * g * cosT, 0.12), 1.5));
  // The light is low and often off to the side or, once the runner turns round, behind him: keep a floor under the forward lobe
  // so the beams still glow then (looking away from the light they keep about two thirds of the glow they have looking into it).
  phase = phase * 0.45 + 0.30;
  float T = 1.0; vec3 sc = vec3(0.0);
  for (int i = 0; i < 48; i++) {
    if (i >= N) break;
    float t = (float(i) + fract(ph + j)) * dtFix;
    if (t >= len) break;
    float seg = min(dtFix, len - t);
    vec3 p = ro + rd * t;
    float heightFog = exp(-max(p.y, 0.0) * 0.105);
    // The beams are static in the world: the canopy density does not move with time.
    float canopy = 0.8 + 0.2 * sin(p.x * 0.31 + p.z * 0.23) * sin(p.z * 0.27 - p.x * 0.17)
                 + 0.12 * sin(p.x * 0.9) * sin(p.z * 0.8);
    float dens = uFogDens * heightFog * canopy;
    float vis = shadowMarch(p);
    // A small ambient term keeps the beam visible at soft shadow edges without flattening contrast.
    vec3 inscatter = uMoonCol * (phase * vis + 0.035) * dens * seg;
    sc += inscatter * T;
    T *= exp(-dens * seg * 0.58);
  }
  float Tscene = T;
  // Far part: samples spaced geometrically from the end of the near march out to SHAFT_FAR (the horizon), with a static
  // per-pixel offset. The beams there come from the shadow map where it still reaches and from beamField beyond.
  float farLen = min(lenAll, SHAFT_FAR);
  if (farLen > uShaftMax + 0.5) {
    float rho = pow(SHAFT_FAR / uShaftMax, 1.0 / float(SHAFT_FAR_STEPS));
    for (int i = 0; i < SHAFT_FAR_STEPS; i++) {
      float t = uShaftMax * pow(rho, float(i) + j);
      if (t >= farLen) break;
      float seg = min(t * (rho - 1.0), farLen - t);
      vec3 p = ro + rd * t;
      float heightFog = exp(-max(p.y, 0.0) * 0.105);
      float dens = uFogDens * heightFog * 0.8;   // the mean canopy density
      float vis = shadowMarch(p);
      float fade = 1.0 - smoothstep(0.55 * SHAFT_FAR, SHAFT_FAR, t);   // the horizon melts into the haze
      sc += uMoonCol * (phase * vis + 0.035) * dens * seg * T * SHAFT_FAR_GAIN * fade;
      T *= exp(-dens * seg * 0.58);
    }
  }
  return c * Tscene + sc * 1.6;
}
#endif
#endif
float sdRoundBox(vec2 p, vec2 half_, float r) {
  vec2 q = abs(p) - half_ + r;
  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}
// Gaussian-ish backdrop blur: golden-angle taps over a pre-filtered mip level.
vec3 backdrop(vec2 uv, float sigmaDp) {
  float ppd = resolution.y / uRootDp.y;                 // rendered pixels per dp
  float lod = max(0.0, log2(max(1.0, sigmaDp * ppd * 0.5)));
  vec2 sg = vec2(sigmaDp) / uRootDp;
  vec3 acc = vec3(0.0); float wsum = 0.0;
  for (int i = 0; i < 20; i++) {
    float fi = float(i) + 0.5;
    float r = sqrt(fi / 20.0) * 2.2;                    // in sigmas
    float a = fi * 2.39996323;
    vec2 off = vec2(cos(a), sin(a)) * r * sg;
    float w = exp(-0.5 * r * r);
    vec3 c = textureLod(tDiffuse, clamp(uv + off, vec2(0.002), vec2(0.998)), lod).rgb;
    acc += (uSrcSrgb > 0.5 ? srgbToLinear(c) : c) * w; wsum += w;
  }
  return acc / wsum;
}
void main() {
  vec2 d = vUv - 0.5;
  float asp = resolution.x / resolution.y;
  vec2 q = d * vec2(asp, 1.0);
  float r = length(q);
  if (warp > 0.001) {
    vec2 sway = vec2(sin(q.y * 4.2 + time * 1.1) + 0.6 * sin(q.y * 9.0 + time * 1.9 + q.x * 3.0), cos(q.x * 3.6 - time * 0.9) + 0.6 * cos(q.x * 8.0 + time * 1.6 - q.y * 2.5));
    q += sway * warp * 0.011;
    q *= 1.0 + warp * 0.025 * sin(time * 1.3);
    d = vec2(q.x / asp, q.y);
  }
  float hring = 0.0;
  if (hole.w > 0.001) {
    vec2 hq = (hole.xy - 0.5) * vec2(asp, 1.0);
    vec2 dh = q - hq;
    float rh = max(length(dh), 0.0001);
    float rs = hole.z;
    float pull = hole.w * 1.15 * rs * pow(rs / rh, 1.5);
    float tw = hole.w * 1.1 * exp(-rh / (rs * 2.6));
    vec2 dd = mat2(cos(tw), -sin(tw), sin(tw), cos(tw)) * dh;
    vec2 src = hq + dd * max(0.0, 1.0 - pull / rh);
    q = src; d = vec2(q.x / asp, q.y);
    hring = exp(-pow((rh - rs * 1.15) / (rs * 0.3), 2.0)) * hole.w;
  }
  // The elastic fisheye zooms in towards the middle instead of pulling in what lies outside the picture (no edge smear).
  float lensEdge = (asp * 0.5) * (asp * 0.5) + 0.25;
  float lensK = (1.0 + lens * dot(q, q) * 1.6) / max(1.0, 1.0 + lens * lensEdge * 1.6);
  vec2 base = 0.5 + d * lensK;
  float w = 0.0;
  for (int i = 0; i < RINGS; i++) {
    float a = waveT - float(i) * GAP;
    float rc = reach[i];
    float life = rc / WSPEED;
    if (a > 0.0 && a < life) {
      float p = a / life;
      float r0 = 1.25 * 3.0 / (3.0 + p * rc);
      float e = (r - r0) / (0.03 + 0.11 * r0);
      w += exp(-e * e * 0.7) * smoothstep(0.0, 0.1, p) * (1.0 - smoothstep(0.6, 1.0, p));
    }
  }
  w = min(1.0, w) * wave;
  base += q / max(r, 0.001) * w * 0.008 * vec2(1.0 / asp, 1.0);
  vec2 uv = base;
  vec2 split = vec2(glitch * 0.012, glitch * 0.002);
  vec3 c;
  if (blur < 0.001 && glitch < 0.001) {
    c = tap(uv);
  } else {
    c = sampleBlur(uv);
    vec3 left = sampleBlur(uv + split), right = sampleBlur(uv - split);
    c = mix(c, vec3(left.r, right.g, left.b), min(glitch * 0.85, 0.85));
  }
#ifdef Q_FULL
  if (uChroma > 0.0 && blur < 0.001 && glitch < 0.001) {
    vec2 off = (uv - 0.5) * uChroma * dot(q, q) * 2.0;
    c.r = tap(uv + off).r; c.b = tap(uv - off).b;
  }
#endif
#ifdef Q_DEPTH
  if (uMotion > 0.001) {
    vec3 acc = c;
    for (int i = 1; i < 6; i++) acc += tap(uv + (0.5 - uv) * uMotion * float(i) / 5.0);
    c = acc / 6.0;
  }
  if (uDofK > 0.001) c = dofGather(uv, c);
#endif
  if (uSharpen > 0.001) {
    // Detail recovery at one render pixel: bark, edges and leaves gain definition. The boost is capped relative to the
    // pixel, so bright lights get no halo, and it fades out while the menu blurs the picture.
    vec2 px = 1.0 / resolution;
    vec3 around = (tap(uv + vec2(px.x, 0.0)) + tap(uv - vec2(px.x, 0.0))
                 + tap(uv + vec2(0.0, px.y)) + tap(uv - vec2(0.0, px.y))) * 0.25;
    vec3 detail = clamp(c - around, -abs(c) * 0.4 - 0.03, abs(c) * 0.4 + 0.03);
    c = max(c + detail * uSharpen * (1.0 - clamp(blur, 0.0, 1.0)), vec3(0.0));
  }
  float vignette = 1.0 - smoothstep(0.28, 0.78, length(vUv - 0.5));
  c *= mix(0.46, 1.0, vignette);
  float cold = dot(c, vec3(0.3, 0.55, 0.15));
  c = mix(c, mix(vec3(cold), c, 0.55) * vec3(0.78, 0.95, 1.18), frost * 0.7);
  float rim = smoothstep(0.3, 0.8, length(vUv - 0.5));
  float crystal = pow(max(0.0, sin(vUv.x * 210.0 + time * 0.4) * sin(vUv.y * 190.0 - time * 0.3)), 14.0);
  c += vec3(0.55, 0.8, 1.0) * frost * (rim * 0.16 + rim * crystal * 0.5);
  c += (vec3(0.6, 0.25, 1.0) * w * 0.4 + vec3(0.85, 0.7, 1.0) * pow(w, 3.0) * 0.1) * RING_GLOW;
  c += vec3(0.78, 0.5, 1.0) * hring * 0.16;
#ifdef Q_DEPTH
  {
    float dd0 = texture(uDepth, uv).r;
    if (uAoK > 0.0) c *= mix(1.0, texture(uAO, vUv).r, uAoK);
#ifdef Q_SHAFTS
    if (uShaftSteps > 0.5) c = shafts(c, uv, dd0);
#endif
  }
#endif
#ifdef Q_FULL
  c += texture(uBloom, vUv).rgb * uBloomK;
#endif
  c = mix(c, c * vec3(0.88, 0.78, 1.18) + vec3(0.0, 0.015, 0.04), warp * 0.3);
  c *= 1.0 - warp * 0.1 * (0.5 + 0.5 * sin(time * 2.0));
  if (abs(hue) > 0.001) {
    vec3 k = vec3(0.57735);
    float ch = cos(hue), sh = sin(hue);
    c = max(c * ch + cross(k, c) * sh + k * dot(k, c) * (1.0 - ch), vec3(0.0));
  }
  c += vec3(0.82, 0.64, 1.0) * flash;
#ifdef Q_FULL
  c = mix(softShoulder(c), acesFit(c * uExposure), uTone);
#endif
  // OutputPass: linear -> sRGB. From here on the picture is gamma encoded, like the browser's compositor.
  vec3 outS = linearToSrgb(clamp(c, 0.0, 1.0));

  // CSS glass panels: backdrop-filter blur + saturate, translucent tint, 1px inset highlight, soft drop shadow.
  if (uGlassCount > 0.5) {
    vec2 dp = vUv * uRootDp;
    float vig = mix(0.46, 1.0, vignette);
    for (int i = 0; i < 2; i++) {
      if (float(i) >= uGlassCount) break;
      vec4 rc = uGlassRect[i]; vec4 pr = uGlassParam[i];
      vec2 ctr = (rc.xy + rc.zw) * 0.5; vec2 hs = (rc.zw - rc.xy) * 0.5;
      float sd = sdRoundBox(dp - ctr, hs, 10.0);
      float op = pr.w;
      if (pr.z > 0.5) {
        // box-shadow: 0 16px 70px oklch(0.12 0.01 290 / 0.35), only outside the box.
        float sds = sdRoundBox(dp - ctr + vec2(0.0, 16.0), hs, 10.0);
        float cov = 1.0 - smoothstep(-35.0, 35.0, sds);
        outS = mix(outS, vec3(0.022, 0.0207, 0.0347), 0.35 * cov * op * smoothstep(-0.5, 0.5, sd));
      }
      float inside = 1.0 - smoothstep(-0.5, 0.5, sd);
      if (inside > 0.0) {
        vec3 bl = backdrop(vUv, pr.x) * vig;
#ifdef Q_FULL
        bl = mix(softShoulder(bl), acesFit(bl * uExposure), uTone);
#endif
        vec3 bs = linearToSrgb(clamp(bl, 0.0, 1.0));
        float lum = dot(bs, vec3(0.213, 0.715, 0.072));
        bs = mix(vec3(lum), bs, pr.y);                                   // saturate()
        bs = mix(bs, vec3(0.2385, 0.2367, 0.2574), 0.3);                 // oklch(0.36 0.009 290 / 0.3)
        float fromTop = (rc.w - dp.y);
        float line = smoothstep(0.5, 1.5, fromTop) * (1.0 - smoothstep(1.5, 2.5, fromTop));
        bs = mix(bs, vec3(1.0), 0.1 * line);                             // inset 0 1px 0 oklch(1 0 0 / 0.1)
        outS = mix(outS, bs, inside * op);
      }
    }
  }
  // Triangular-PDF dither, one 8-bit step: removes banding in the dark gradients.
  vec2 fc = gl_FragCoord.xy + vec2(fract(time * 7.13) * 31.0, fract(time * 3.77) * 17.0);
  float dn = (ign(fc) + ign(fc + vec2(5.3, 11.7)) - 1.0) / 255.0;
#ifdef Q_FULL
  dn += (ign(fc * 1.37 + vec2(time * 61.0, 0.0)) - 0.5) * uGrain;
#endif
  o = vec4(clamp(outS + dn, 0.0, 1.0), 1.0);
}
"""
    }

    /** Half-resolution screen-space ambient occlusion from the resolved depth. */
    const val SSAO_FS = """#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 vUv;
uniform sampler2D uDepth;
uniform mat4 uProj;
uniform vec2 uNF, uTanAspect, uTexel;
uniform float uRadius;
out vec4 o;
float ign(vec2 p) { return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715)))); }
float linZ(float d) { float z = d * 2.0 - 1.0; return 2.0 * uNF.x * uNF.y / ((uNF.y + uNF.x) - z * (uNF.y - uNF.x)); }
vec3 vpos(vec2 uv) { float z = linZ(texture(uDepth, uv).r); return vec3((uv * 2.0 - 1.0) * uTanAspect * z, -z); }
void main() {
  vec3 p = vpos(vUv);
  if (-p.z > 70.0) { o = vec4(1.0); return; }
  vec3 px = vpos(vUv + vec2(uTexel.x, 0.0)), nx = vpos(vUv - vec2(uTexel.x, 0.0));
  vec3 py = vpos(vUv + vec2(0.0, uTexel.y)), ny = vpos(vUv - vec2(0.0, uTexel.y));
  vec3 dx = abs(px.z - p.z) < abs(nx.z - p.z) ? px - p : p - nx;
  vec3 dy = abs(py.z - p.z) < abs(ny.z - p.z) ? py - p : p - ny;
  vec3 n = normalize(cross(dx, dy));
  if (dot(n, -p) < 0.0) n = -n;
  vec3 tn = normalize(abs(n.x) > 0.9 ? cross(n, vec3(0.0, 1.0, 0.0)) : cross(n, vec3(1.0, 0.0, 0.0)));
  vec3 bn = cross(n, tn);
  float ang = ign(gl_FragCoord.xy) * 6.2831853;
  float occ = 0.0;
  for (int i = 0; i < SSAO_N; i++) {
    float fi = (float(i) + 0.5) / float(SSAO_N);
    float a = ang + float(i) * 2.399963;
    float zz = fi; float rr = sqrt(1.0 - zz * zz);
    vec3 d = tn * (cos(a) * rr) + bn * (sin(a) * rr) + n * zz;
    vec3 sp = p + d * (uRadius * mix(0.2, 1.0, fi * fi));
    vec4 pp = uProj * vec4(sp, 1.0);
    vec2 suv = pp.xy / pp.w * 0.5 + 0.5;
    float sd = vpos(suv).z;
    float range = smoothstep(0.0, 1.0, uRadius / max(abs(p.z - sd), 1e-3));
    float hit = sd >= sp.z + 0.025 ? 1.0 : 0.0;
    // Bias the kernel toward short distances: roots, stones and ground contacts get
    // a readable local crease, while broad distant forms remain softly lit.
    occ += hit * range * mix(1.18, 0.78, fi);
  }
  float ao = 1.0 - occ / float(SSAO_N);
  // Surfaces seen at a grazing angle (flat canopy undersides, the ground far away) have a noisy depth-derived normal:
  // the hemisphere then flips from row to row and prints horizontal stripes. Fade the occlusion out there.
  float facing = abs(dot(n, normalize(-p)));
  ao = mix(1.0, ao, smoothstep(0.1, 0.35, facing));
  o = vec4(vec3(pow(clamp(ao, 0.0, 1.0), 1.78)), 1.0);
}
"""

    /** Depth-aware blur of the raw occlusion. */
    const val AO_BLUR_FS = """#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 vUv;
uniform sampler2D uAoIn, uDepth;
uniform vec2 uTexel;
out vec4 o;
void main() {
  float d0 = texture(uDepth, vUv).r;
  float sum = 0.0, ws = 0.0;
  for (int i = -2; i <= 2; i++) for (int j = -2; j <= 2; j++) {
    vec2 uv = vUv + vec2(float(i), float(j)) * uTexel;
    // Compare depths relatively (1 - d is proportional to 1/z). The old fixed threshold on the non-linear depth blurred
    // only along rows wherever the depth changes from row to row (canopy undersides, the ground at a grazing angle),
    // so the row-to-row noise of the occlusion survived as horizontal stripes. Now such surfaces blur in every direction
    // and only real depth edges stop the blur.
    float di = texture(uDepth, uv).r;
    float w = exp(-abs(log(max(1.0 - di, 1e-6) / max(1.0 - d0, 1e-6))) * 12.0);
    sum += texture(uAoIn, uv).r * w; ws += w;
  }
  o = vec4(vec3(sum / max(ws, 1e-4)), 1.0);
}
"""

    /** Bloom downsample (first level also thresholds with a soft knee and a Karis average). */
    const val BLOOM_DOWN_FS = """#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 vUv;
uniform sampler2D uSrc;
uniform vec2 uTexel;
uniform float uPre, uThreshold;
out vec4 o;
vec3 s(vec2 uv) { return textureLod(uSrc, uv, 0.0).rgb; }
void main() {
  vec2 t = uTexel;
  vec3 a = s(vUv + t * vec2(-1.0, -1.0)), b = s(vUv + t * vec2(1.0, -1.0));
  vec3 c = s(vUv + t * vec2(-1.0, 1.0)), d = s(vUv + t * vec2(1.0, 1.0));
  vec3 r;
  if (uPre > 0.5) {
    float wa = 1.0 / (1.0 + dot(a, vec3(0.3, 0.55, 0.15))), wb = 1.0 / (1.0 + dot(b, vec3(0.3, 0.55, 0.15)));
    float wc = 1.0 / (1.0 + dot(c, vec3(0.3, 0.55, 0.15))), wd = 1.0 / (1.0 + dot(d, vec3(0.3, 0.55, 0.15)));
    r = (a * wa + b * wb + c * wc + d * wd) / (wa + wb + wc + wd);
    float br = max(r.r, max(r.g, r.b));
    float knee = uThreshold * 0.5;
    float soft = clamp(br - uThreshold + knee, 0.0, 2.0 * knee);
    soft = soft * soft / (4.0 * knee + 1e-4);
    r *= max(soft, br - uThreshold) / max(br, 1e-4);
  } else {
    r = (a + b + c + d + s(vUv) * 4.0) / 8.0;
  }
  o = vec4(min(r, vec3(60000.0)), 1.0);
}
"""

    /** Bloom upsample: 9-tap tent, added onto the next larger level. */
    const val BLOOM_UP_FS = """#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 vUv;
uniform sampler2D uSrc;
uniform vec2 uTexel;
out vec4 o;
vec3 s(vec2 uv) { return textureLod(uSrc, uv, 0.0).rgb; }
void main() {
  vec2 t = uTexel;
  vec3 r = s(vUv) * 4.0
    + (s(vUv + vec2(t.x, 0.0)) + s(vUv - vec2(t.x, 0.0)) + s(vUv + vec2(0.0, t.y)) + s(vUv - vec2(0.0, t.y))) * 2.0
    + s(vUv + t) + s(vUv - t) + s(vUv + vec2(t.x, -t.y)) + s(vUv + vec2(-t.x, t.y));
  o = vec4(r / 16.0 * 0.72, 1.0);
}
"""
}
