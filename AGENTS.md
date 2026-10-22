<!-- LOVABLE:BEGIN -->
> [!IMPORTANT]
> This project is connected to [Lovable](https://lovable.dev). Avoid rewriting
> published git history — force pushing, or rebasing/amending/squashing commits
> that are already pushed — as it rewrites history on Lovable's side and the
> user will likely lose their project history.
>
> Commits you push to the connected branch sync back to Lovable and show up in
> the editor, so keep the branch in a working state.
<!-- LOVABLE:END -->

## Game architecture
- Keep pure simulation and gameplay rules in src/lib/forest/simulation.ts with rule tests; the scene reads mutable refs and reports only phase transitions to React to avoid per-frame rerenders.
- Keep the continuous browser-only drone in its own lifecycle-managed module and postprocessing in ForestEffects; this separates audio, camera presentation, and gameplay.
- Use local device storage for this game's personal scores/settings only, not shared data; no account or cross-device persistence is implied.
- Keep every tree fully procedural (deformed trunk cylinder, hash-placed branch cylinders, displaced icosahedron crowns) rather than full-tree models; crowns sit low (~15-17.5 m) so neighbouring foliage merges overhead, and every branch ends in a leaf clump — no bare wood. Crown clumps use icosahedron detail 2 to keep the instance budget mobile-safe. Terrain height comes from the shared groundHeight() formula in simulation.ts, mirrored verbatim as GLSL in the ground shader so physics and visuals agree.
- Source scene colors from global semantic CSS tokens, and keep browser/PWA metadata in the root head; installed fullscreen remains platform-dependent. Menu camera is a barely noticeable breathing sway (no step rhythm); the total turn arc is 75 degrees; the app opens landscape-locked with a settings switch for all orientations.
- The Android app is Capacitor wrapping the prerendered static bundle (vite.config pages prerender "/" into dist/client/index.html, capacitor.config webDir is dist/client); the game must stay fully static — no server functions or SSR-only features — so the on-device build needs no backend. The APK builds in GitHub Actions (.github/workflows/android.yml); versionCode comes from the run number, release signing is optional via secrets.
- Keep the stale-start-up recovery guard (src/lib/stale-bundle-recovery.ts) injected in the root head before the app bootstrap, and keep its reload single-shot via a sessionStorage cooldown marker: a cached entry module that goes stale blanks the page forever, while an unguarded auto-reload would loop on a genuinely broken page.
- Native launch policy lives in MainActivity: install JS interfaces before Bridge loads, apply initial orientation before document startup, and restore immersive system-bar hiding on resume, focus and configuration changes; browser fullscreen alone cannot control Android bars.
- Generate native launcher density assets from public/icon-512.png, with the web manifest background and adaptive safe-zone scaling, so installed and web identities cannot diverge.
- Audio startup and foreground return must not depend on storage availability or a tap in the native app; retry suspended native audio while visible/enabled, and clear lifecycle handlers on disposal.
