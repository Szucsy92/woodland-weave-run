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
| Moon shadow map (2048, PCF), replaces the contact-shadow decals | | | yes |
| SSAO (half-res, 8 samples, bilateral blur) | | | yes |
| Volumetric light shafts (shadow-map raymarch, 12 steps) | | | yes |
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
  "Fine-tune" accordion: Medium has Bloom; High adds Filmic look, Shadows, Occlusion, Light shafts, Depth of field and
  Motion blur. Each slider is a multiplier of the designed strength (0 = off, 100% = as designed, up to 150%), applied
  live; "Reset" restores 100%. They are stored per setting (`FxTuning`) and apply to whichever tier has the effect.

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
