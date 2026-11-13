# Visual parity pass (native vs. Capacitor build)

Rendering pipeline
- Scene target is linear RGBA16F (4x MSAA) like the web composer's half-float target; RGBA8 fallback if the GPU can't.
- Fog = three.js Fog: smoothstep(near, far, view depth) (was linear on radial distance). Sprites and contact shadows fog too (except where the web build set fog={false}).
- Colour maps are SRGB8_ALPHA8 (linear mip/aniso filtering), anisotropy up to 16.
- Metalness (.2 on gates/monoliths/relics) and three's specular anti-aliasing term added to the PBR shader.
- Crowns, rocks, bushes and power orbs are flat-shaded polyhedra in three.js vertex order (FLAT_FACETS in GameRenderer). Set it to false for the previous smooth look.
- Back-face culling on the closed blobs; 8-bit window needs no depth buffer.
- Final pass: sRGB encode + triangular dither (no banding in the dark fog).
- Dynamic resolution: starts at 1.5x density (as before), climbs to 2.0x while 60 fps holds, backs off when it doesn't (ADAPTIVE_SCALE).

UI
- Glass panels get a real backdrop blur(20px) saturate(70%), tint, inset highlight and drop shadow, drawn in the effect pass (GlassState). Pause button: blur(8px).
- Exact oklch colours, arrival animation, title glitch copies, HUD shadow + "· N secrets" on one line, power-up frame flare/pulse/inner edge, settings list edge fade.

Not compiled or run on a device in this environment: build with CI and compare on hardware.
