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
void main() {
  mat4 m = mat4(iA, iB, iC, iD);
  vLY = aPos.y;
  vec4 w = m * vec4(aPos, 1.0);
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

    val LIT_FS = """#version 300 es
precision highp float;
in vec3 vN; in vec3 vW; in vec2 vUv; in float vLY;
uniform vec3 uColor, uEmissive, uFog, uCam, uSky, uGroundC, uMoonDir, uMoon, uLampPos, uLampCol, uRedPos, uRedCol, uFloodDir, uFloodCol;
uniform float uAlpha, uMaps, uFogNear, uFogFar, uHemi, uLampI, uRedI, uNormalScale, uRough, uLights, uRed, uFogOn;
uniform int uMode;
uniform vec2 uUvScale;
uniform sampler2D uTex, uNorm, uRoughTex;
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
  vec3 F = vec3(0.04) + vec3(0.96) * pow(1.0 - vh, 5.0);
  return (albedo / PI + F * vGGX(alpha, nl, nv) * dGGX(alpha, nh)) * irradiance * nl;
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
vec3 finish(vec3 col, float dist) {
  float f = clamp((dist - uFogNear) / (uFogFar - uFogNear), 0.0, 1.0) * uFogOn;
  return linearToSrgb(clamp(mix(col, uFog, f), 0.0, 1.0));
}
void main() {
  vec3 toCam = uCam - vW;
  float dist = length(toCam);
  vec3 v = toCam / max(dist, 1e-4);
  if (uMode == 1) { o = vec4(uColor, texture(uTex, vUv).a * uAlpha); return; }
  if (uMode == 2) { o = vec4(finish(uColor, dist), uAlpha); return; }
  vec3 n = normalize(vN);
  if (!gl_FrontFacing) n = -n;
  vec3 albedo = uColor;
  float rough = uRough;
  // Texture rows start at the top of the image here, three.js flips them: undo that so maps and normals line up.
  vec2 tuv = vec2(vUv.x * uUvScale.x, (1.0 - vUv.y) * uUvScale.y);
  if (uMaps > 0.5) {
    albedo *= srgbToLinear(texture(uTex, tuv).rgb);
    rough *= texture(uRoughTex, tuv).g;
    vec3 mn = texture(uNorm, tuv).xyz * 2.0 - 1.0;
    mn.xy *= uNormalScale;
    n = perturb(n, vW, vec2(tuv.x, -tuv.y), mn);
  }
  vec3 emissive = uEmissive;
  if (uMode == 3) {
    // Mossy patches, darker damp earth and leaf litter baked into the ground colour.
    float mossN = sin(vW.x * .21 + sin(vW.z * .17) * 2.) * sin(vW.z * .19 - sin(vW.x * .13) * 1.7);
    float litN = sin(vW.x * 1.3 + vW.z * .7) * sin(vW.z * 1.1 - vW.x * .5);
    float moss = smoothstep(.25, .75, mossN);
    float damp = smoothstep(.2, -.6, mossN);
    albedo = mix(albedo, albedo * vec3(.72, 1.18, .74), moss * .6);
    albedo *= 1. - damp * .3;
    albedo = mix(albedo, albedo * vec3(1.25, 1.02, .78), smoothstep(.55, .95, litN) * .35);
  } else if (uMode == 4) {
    // Contact darkening and moss at the trunk foot, plus a soft wet-bark rim glint near the runner.
    float foot = 1. - smoothstep(1.0, 3.2, vLY);
    albedo = mix(albedo, albedo * vec3(.75, 1.15, .72), foot * .55);
    albedo *= mix(.45, 1., smoothstep(.9, 2.4, vLY)) * mix(1., .7, smoothstep(10., 18., vLY));
    float near = 1. - smoothstep(4., 14., dist);
    float rim = pow(1. - abs(dot(n, v)), 3.);
    vec3 tint = mix(vec3(.18, .55, .35), vec3(.42, .26, .62), smoothstep(-.3, .3, sin(vLY * .35)));
    emissive += mix(tint, vec3(.95, .12, .06), uRed) * rim * near * .22 * smoothstep(.6, 2., vLY) * mix(uLights, .8, uRed);
  } else if (uMode == 5) {
    // Purple-green shards of light catching the leaf undersides.
    float under = smoothstep(.15, -.75, n.y);
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
  float alpha = pow(clamp(rough, 0.0525, 1.0), 2.0);
  vec3 col = albedo / PI * (mix(uGroundC, uSky, n.y * 0.5 + 0.5) * uHemi);
  col += light(n, v, normalize(uMoonDir), uMoon, albedo, alpha);
  vec3 tl = uLampPos - vW; float d = length(tl);
  col += light(n, v, tl / max(d, 1e-3), uLampCol * (uLampI / max(pow(d, 1.6), 0.01)) * falloff(d, 27.0), albedo, alpha);
  tl = uRedPos - vW; d = length(tl);
  col += light(n, v, tl / max(d, 1e-3), uRedCol * (uRedI / max(pow(d, 1.1), 0.01)) * falloff(d, 70.0), albedo, alpha);
  col += light(n, v, normalize(uFloodDir), uFloodCol, albedo, alpha);
  o = vec4(finish(col + emissive, dist), uAlpha);
}
"""

    val SPRITE_VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec2 aSize;
layout(location=2) in vec4 aCol;
uniform mat4 uVP; uniform vec3 uRight, uUp;
out vec2 vUv; out vec4 vCol;
void main() {
  vec2 c = vec2(float(gl_VertexID & 1) * 2.0 - 1.0, float(gl_VertexID >> 1) * 2.0 - 1.0);
  vec3 w = aPos + uRight * c.x * aSize.x * 0.5 + uUp * c.y * aSize.y * 0.5;
  vUv = vec2(c.x * 0.5 + 0.5, 0.5 - c.y * 0.5); vCol = aCol;
  gl_Position = uVP * vec4(w, 1.0);
}
"""

    val SPRITE_FS = """#version 300 es
precision mediump float;
in vec2 vUv; in vec4 vCol;
uniform sampler2D uTex;
out vec4 o;
// Fragment shaders are compiled independently: keep the sRGB encoder local to
// this shader instead of relying on the helper declared in LIT_FS.
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
  float a = texture(uTex, vUv).a * vCol.a;
  o = vec4(linearToSrgb(clamp(vCol.rgb, 0.0, 1.0)), a);
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

    /** Port of the web build's ForestEffects pass: blur, glitch, lens, frost, shockwave rings, wormhole, black hole, hue. */
    fun postFs(rings: Int, gap: Double, speed: Double): String {
        fun f(v: Double) = String.format(java.util.Locale.US, "%.3f", v)
        return """#version 300 es
precision highp float;
#define RINGS $rings
#define GAP ${f(gap)}
#define WSPEED ${f(speed)}
#define RING_GLOW 0.28
in vec2 vUv;
uniform sampler2D tDiffuse;
uniform float blur, glitch, frost, lens, wave, waveT, warp, flash, hue, time;
uniform vec4 hole;
uniform float reach[RINGS];
uniform vec2 resolution;
out vec4 o;
// The scene is stored gamma-encoded; all effect maths below runs in linear light, like the original.
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
vec3 tap(vec2 uv) { return srgbToLinear(texture(tDiffuse, uv).rgb); }
vec3 sampleBlur(vec2 uv) {
  vec2 d = vec2(blur * 8.0) / resolution;
  return (tap(uv) * 4.0 + tap(uv + d) + tap(uv - d) + tap(uv + vec2(d.x, -d.y)) + tap(uv + vec2(-d.x, d.y))) / 8.0;
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
  vec2 base = 0.5 + d * (1.0 + lens * dot(q, q) * 1.6);
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
  float vignette = 1.0 - smoothstep(0.28, 0.78, length(vUv - 0.5));
  c *= mix(0.46, 1.0, vignette);
  float cold = dot(c, vec3(0.3, 0.55, 0.15));
  c = mix(c, mix(vec3(cold), c, 0.55) * vec3(0.78, 0.95, 1.18), frost * 0.7);
  float rim = smoothstep(0.3, 0.8, length(vUv - 0.5));
  float crystal = pow(max(0.0, sin(vUv.x * 210.0 + time * 0.4) * sin(vUv.y * 190.0 - time * 0.3)), 14.0);
  c += vec3(0.55, 0.8, 1.0) * frost * (rim * 0.16 + rim * crystal * 0.5);
  c += (vec3(0.6, 0.25, 1.0) * w * 0.4 + vec3(0.85, 0.7, 1.0) * pow(w, 3.0) * 0.1) * RING_GLOW;
  c += vec3(0.78, 0.5, 1.0) * hring * 0.16;
  c = mix(c, c * vec3(0.88, 0.78, 1.18) + vec3(0.0, 0.015, 0.04), warp * 0.3);
  c *= 1.0 - warp * 0.1 * (0.5 + 0.5 * sin(time * 2.0));
  if (abs(hue) > 0.001) {
    vec3 k = vec3(0.57735);
    float ch = cos(hue), sh = sin(hue);
    c = max(c * ch + cross(k, c) * sh + k * dot(k, c) * (1.0 - ch), vec3(0.0));
  }
  c += vec3(0.82, 0.64, 1.0) * flash;
  o = vec4(linearToSrgb(clamp(c, 0.0, 1.0)), 1.0);
}
"""
    }
}
