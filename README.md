# Woodland Weave Run

A first-person endless runner through a misty night forest, by **László Szűcs (Szucsy92)**.

Dodge procedural trees, cross uneven ground and follow the lights through the woodland. Glowing spores, softly lit undergrowth and a continuous atmospheric soundtrack give the forest its depth.

## Play on Android

Download the latest signed APK from [Releases](https://github.com/Szucsy92/woodland-weave-run/releases). The game runs offline; scores and settings stay on your device. Internet access is only needed to check for and download updates.

- Three difficulty levels: Easy, Normal and Hard
- Tilt or touch controls
- Landscape-first, fullscreen presentation
- In-game update checks under Settings; Android confirms installation

## Ownership and attribution

Copyright © 2026 László Szűcs. All rights reserved for the original project content.

This is a public, source-visible project, **not an open-source license grant**. GitHub permits viewing and forking public repositories; the Fork button cannot be disabled for a public repository.

Keep the author credit, copyright notice and link to the [original repository](https://github.com/Szucsy92/woodland-weave-run) in any permitted copy or fork. A fork is not permission to redistribute, sell or publish a modified game. Uses beyond GitHub's applicable terms require the author's prior written permission. Third-party dependencies retain their own licenses. See [LICENSE](LICENSE).

## Development

Requires Node.js 22 or later and Bun.

```sh
bun install
bun run dev
```

Android releases are built by GitHub Actions and signed using the project's permanent signing key, stored in Actions secrets. Never commit signing keys or passwords to this repository.
