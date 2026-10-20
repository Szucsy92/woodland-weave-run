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
- Preserve procedural trunk-only geometry with generated surface maps rather than full-tree models because the game's visual constraint excludes crowns and branches.
- Source scene colors from global semantic CSS tokens, and keep browser/PWA metadata in the root head; installed fullscreen remains platform-dependent.
