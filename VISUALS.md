# Graphics tiers (Low / Medium / High / Ultra)

Pick the tier in Settings → Graphics. **Low is the previous build** (web parity look). The default is Low.
Every tier degrades safely: if the GPU lacks float render targets, a depth resolve, or a shader fails to compile,
the game drops to the highest tier the device can really run instead of crashing (see `PostFx.effective`).

| Factor | Low | Medium | High | Ultra |
|---|---|---|---|---|
| Linear HDR (RGBA16F) pipeline, three.js fog, sRGB maps, dither | yes | yes | yes | yes |
| Filmic tone mapping (ACES) | | yes | yes | yes |
| Bloom (soft knee, Karis average) | | 3 levels | 5 levels | 5 levels, stronger |
| Film grain | | yes | yes | yes |
| Foliage | flat-shaded polyhedra | smooth + procedural leaves (see-through via alpha-to-coverage), translucency | finer canopy (642-vert near crowns) | same, finest |
| Wind (trunk sway, crown flutter, grass) | | yes | yes | yes |
| Image-based specular (wet sheen), puddles | | yes | yes | yes |
| Moon shadow map | | | 1 cascade, 2048, PCF | 2 cascades, 3072 + 2048, PCF |
| SSAO (half-res, bilateral blur) | | | 8 samples | 14 samples |
| Volumetric light shafts (shadow-map raymarch) | | | 14 steps | 40 steps, longer range |
| Grass blades (tapered, curved, pointed) | | | 40% | 100% |
| Depth of field | | | | yes |
| Radial motion blur at speed | | | | yes |
| Chromatic aberration | | | | yes |
| **No flat quads at all** | quads | quads | quads | **none** |

"No flat quads" (Ultra): contact-shadow decals -> real shadow maps; glow/mist/spore sprites -> analytic depth-occluded
light halos in the effect pass, volumetric fog and 3D spore spheres; veil planes -> removed (volumetrics) or glowing
tori (accretion rings); boxes -> ellipsoid stones and capped columns.

Where to tune: every number lives in `gl/Quality.kt` (`Features`). Look-dev constants in the shaders:
leaf density (`LEAF_FN` in `Shaders.kt`), shaft strength (`fogDensity`), bloom (`bloomStrength`, `bloomThreshold`),
exposure (`exposure`). Orb translucency: `ORB_ALPHA` in `GameRenderer`.

Not compiled or run on a device in the environment this was written in: build with CI and tune on hardware.
