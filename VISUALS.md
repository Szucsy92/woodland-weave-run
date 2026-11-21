# Graphics tiers (Low / Medium / High)

Settings → Graphics. **Low is the previous build** (web-parity look) and the default. Medium and High build on it.
Every tier degrades safely: no float render target, no depth resolve, or a shader that fails to compile -> the game
runs the highest tier the device can really handle instead of crashing (`PostFx.effective`, `GameRenderer.applyQuality`).

| Factor | Low | Medium | High |
|---|---|---|---|
| Linear HDR (RGBA16F) pipeline, three.js fog, sRGB maps, dither, glass UI | yes | yes | yes |
| Bloom | | yes (4 levels) | yes (5 levels) |
| Round shapes: canopy, rocks, bushes smooth and finely tessellated (no polygon facets) | faceted (web look) | smooth | smooth |
| Round rings and posts (relic rings, poles) | 6-8 sides | 12-20 sides | 12-20 sides |
| Trunk and roots with a real form (see below) | | yes | yes |
| Boxes (monolith, gate posts, lintel, lantern) | boxes | boxes | boxes |
| Filmic tone mapping (ACES), film grain, faint chromatic aberration | | | yes |
| Key-light shadow map (2048, PCF, 92 m wide, centred on the runner; trunks, roots, branches, rocks, bushes), replaces the contact-shadow decals | | | yes |
| SSAO (half-res, 8 samples, bilateral blur) | | | yes |
| Volumetric light shafts (shadow-map + canopy-gap raymarch, 28 world-anchored steps) | | | yes |
| Wet sheen (sky/ground mirror) and puddles on the ground | | | yes |
| Light whole-tree sway (a smooth world-space gust field) | | | yes |
| Depth of field (far background only) | | | yes |
| Motion blur, proportional to speed (none when slow, up to 2% radial smear at a sprint) | | | yes |

Removed on request: grass, procedural leaves, the Ultra tier, and the "no quads" swaps (boxes stay boxes, veils and
sprites are back).

## Lifelike trunk and roots (Medium and High)
- Trunk: 28 sides instead of 16, finer rows through the base flare, buttress ridges that are narrower and rise to 3.4 m
  so they flow into the surface roots, slightly elliptical and lobed sections, shallow bark fissures and knots, a gentle
  centre-line sway. Same height, radius and attachment points as before, so branches and crowns still sit right.
  UVs follow real height, so the bark texture keeps its scale. Seam normals are welded: the trunk is truly round.
- Roots: a 12-sided swept tube (24 steps) that rises out of the trunk flare, meanders, tapers and dives into the soil;
  wider than tall with soft lumps.
- Branches: 10 sides instead of 6.

Where to tune: `gl/Quality.kt` (all numbers), `Geo.trunkLifelike()` / `rootLifelike()` in `gl/Meshes.kt`,
`ORB_ALPHA` in `GameRenderer`.

Not compiled or run on a device in the environment this was written in: build with CI and tune on hardware.

## Menu and settings (UI)
- The menu and pause cards are narrower (content 264 dp). One card hosts the menu, the pause menu and the settings:
  switching between them glides its size (accordion) while the old content blurs and fades out and the new one blurs in
  (the blur needs Android 12+; older versions fade only). The glass backdrop blur follows the card as it resizes.
- The three game modes (Classic / Survive / Freeroam) moved from Settings to the bottom of the start screen.
- Settings -> Graphics: three tabs (Low / Medium / High) with no description text. Medium and High have a collapsible
  "Fine-tune" accordion with three grouped, zero-centred sliders (see "Fine-tune controls" below), applied live; "Reset to 0"
  restores the designed look. The values are stored per effect (`FxTuning`) and apply to whichever tier has the effect.

## Lights (all tiers) and bloom
- Light sprites dissolve smoothly as the camera enters them (distance-based, per sprite size), instead of flashing and
  popping when you pass through. Forest lights, orb auras, secrets, spores and mist all use it.
- Medium and High: the glow and spore sprites use smooth analytic profiles (no kink where the old gradient changed
  slope), and highlights roll off softly instead of clipping (High uses the filmic curve).
- Bloom is now clearly visible: the threshold is low (0.30 in linear light, raised automatically on bright day scenes so
  the sky never blooms) and the strength is 0.50 (Medium) / 0.45 (High); the Bloom slider scales it from 0 to 150%.
- While an effect slider is dragged the settings card fades to almost nothing and a small readout (name, percent, bar)
  appears, so the picture can be judged.
- The glass rectangle is now published straight from layout, and the card no longer slides in with a translation, so the
  blurred glass stays under the border while the card morphs.

## Going in and out of halos, frost, day and night (from the screen recording)
- Light sprites now dissolve over their whole radius (up to 18 m for the big forest halos) with a smootherstep curve, so
  walking into a halo no longer floods the screen with white and leaving it no longer snaps back to dark. All tiers.
- Every glow that reaches into the ground (forest halos, the fairy-ring glows, orb auras, the lantern and monolith glows)
  carries its height above the ground and fades out softly towards it: no more flat, cut-off bottoms. All tiers.
- The elastic fisheye on pickup (and when a power-up ends) no longer pulls in pixels from outside the picture, which
  produced the horizontal edge smear in the video; it now zooms in towards the middle instead. All tiers.
- Frost eases in (about 0.4 s) and melts out (about 0.6 s) instead of snapping.
- Medium and High soften the lamp's near-field falloff (`Features.lampSoft`), so a trunk right next to the lamp no
  longer burns out to white.
- Day and night: switching the mood is a full 2.6 s eased crossfade; Auto still follows the clock. The forest halos melt
  away with the daylight and come back with the night, orb auras and spores dim a little; Medium and High also dim the
  lamp, lower exposure, bloom and light shafts in daylight.

## Local power-up light spill (native renderer)

Uncollected power-ups contribute compact coloured point lights to the lit scene. Their tint follows the pickup type (freezer, ghost or shockwave), their intensity fades with camera distance and pulses gently with the orb's energy, and their radius is deliberately limited to keep GLES 3.0 fragment work bounded. Low / Medium / High evaluate at most 1 / 2 / 4 nearby pickups respectively. The lights affect normal-mapped bark, ground and other lit materials through the existing GGX lighting path; they are separate from the orb's additive glow sprites. The local lights do not yet cast their own shadow maps: the existing 2048 directional key-light shadow remains a separate system.

## Fades, shadows for lights, sharpness
- **Lights fade, never pop or flash.** Sprites dissolve over their whole radius when you pass through them (smootherstep);
  the forest halos, gates and secrets also fade with distance, so none appears or disappears when the lattice of lights
  shifts under the runner; the survive-mode flicker eases on and off; a new orb grows in over 1.4 s and a collected one
  shrinks away together with its plasma rings and its light.
- **Light no longer shines through trees.** The pickup lights and the lamp are blocked by the trunks between them and the
  shaded point (analytic, soft-edged cylinder test, six trunks per light), and the pickup lights stay low: they no longer
  light the crowns far above.
- **Sharper picture, all tiers.** The render starts at 2.25x density (it was 1.5x, which on a 2.7x screen was about half
  the native resolution) and the surface can be as fine as 3x; it only gives ground when 60 fps is lost (floor 1.5x).
  Contrast-adaptive sharpening is on for every tier (0.30 / 0.36 / 0.40), textures use a negative LOD bias, and Medium
  and High see 12% further before the fog closes in. Settings -> Graphics -> Fine-tune used to have a Sharpness slider on every
  tier (see "Fine-tune controls" below for what replaced it).

## Key light, shadows and beams
- **Sun and moon follow the time of day** (Napszak slider / Auto clock), each on its own arc (`SkyLights.SUN` / `MOON`): rise in the
  east, highest point straight ahead, set in the west. Only the stronger one is the key light that feeds the shadow map, the
  light shafts and the ground pools. The angle depends on the time of day only: while running, beams and pools stay exactly
  where they are.
- **Solid parts and foliage are handled apart.** The shadow map holds only trunks, roots, branches, rocks and bushes. How much the
  foliage lets through is `sunGap()` in `Shaders.kt`: a function of world position and the light direction only (a fixed
  field of openings in a canopy layer 15 m up), so a beam is a slanting column below an opening and a pool of light is where that
  column ends - the same from every side. Dragging the time slider moves the pools (the light direction changes); running does not.
- **Light pools on the ground** (`sunPool()` in `Shaders.kt`): every beam ends in a pool with a strong, bright core that fades smoothly
  outwards to the rim (no flat plateau). Between the pools the floor keeps only ~20% of the key light, the core is lifted (x2.1) and
  gets an additive glow on top; trunk / bush shadows still cut into the pools. The ground dapple is kept subtle so the falloff reads clean.
  Tuning: the `0.20` floor, `1.1` core boost and the `0.55` / `1.35` glow factors in the ground branch of the lit shader.
- **Power-up lights make ground pools too**: under every uncollected pickup the floor gets a pool in the pickup's colour, bright core
  right below it, fading smoothly outwards (the `0.62` radius factor, `0.30` halo / `0.95` core and `0.45` gain in the local-light loop of
  the lit shader).
- **The base light stays.** Trunks, bushes and rocks keep at least 30% of the key light outside the openings, trunks / bushes / rocks only dim by
  a third of that; the beams in the air use a lower floor (8%) so they stay contrasty. Crowns stay in the sun.
- **The shadow map is centred on the runner** (half-width 46 m, `Features.shadowExtent`), never ahead along the heading.
- **Shadow ring.** Casters come from a lattice wider than the playable window (`SHADOW_RADIUS` 22 cells): the very same
  deterministic trees the forest has out there (with the same Freeroam clearing around secrets, gates and black holes), drawn
  into the shadow map only, fading out between 100 and 118 m.
- Tuning: `SkyLights` (arcs, strengths), `SUN_GAP_FN` (opening size, layer height) and the `lowest` arguments of `sunGap` in
  `Shaders.kt` (ground / objects 0.30, beams 0.08).
- Not compiled or run in the environment this was written in: build with CI and check on a device.

## Fine-tune controls (Medium / High)
- Settings -> Graphics -> Fine-tune (hidden on Low) has three grouped, zero-centred sliders instead of one slider per effect:
  **Image & contrast** (bloom + filmic look; Medium and High), **Light & atmosphere** (shadows + light shafts; High) and
  **Focus & motion** (depth of field + motion blur; High). Each runs from -100 (group off) through 0 (the look the renderer is tuned
  for, `FX_NEUTRAL` in `Quality.kt`) to +100 (doubled). "Reset to 0" puts all three back.
- The sharpness slider and the occlusion (SSAO) slider are gone: contrast-adaptive sharpening (`Features.sharpen`) and SSAO
  (`Features.ssao`) are off, and their `FxTuning` values stay 0.
- Colours, sky, fog, key-light balance and beams are the "light balance refined" set above; the sliders only scale effects around it.

## One tier, half resolution
- Low and Medium are gone from Settings -> Graphics: there is a single tier, shown as **Normal**, with the full High effect set and the
  three Fine-tune sliders. The stored quality value is ignored (`SettingsRepo`).
- The scene is drawn at **half the surface's width and height** (`GameRenderer.RENDER_SCALE = 0.5f`) and scaled up in the final pass;
  the frame-rate driven resolution scaler is switched off (`ADAPTIVE_SCALE = false`), so the resolution stays fixed.
- LOW / MEDIUM remain in code only as a safety net: if a device cannot compile the shaders or allocate the float / depth targets,
  the renderer still falls back to a lower tier instead of crashing.
