# Woodland Weave Run: native Android

Pure Kotlin. No WebView, no Capacitor, no JavaScript. The only network use is the in-app
update check and download from this game's own GitHub releases.

## Build locally
Open this folder in Android Studio (JDK 17+) and press Run, or `./gradlew assembleDebug`.
Tests: `./gradlew testDebugUnitTest`.

## Build in GitHub (the "Run workflow" button)
`.github/workflows/android.yml` builds a signed release APK on every push to `main` and on the
Actions tab's **Run workflow** button, then publishes a GitHub Release
`v<VERSION>-<timestamp>-build<run number>` with the APK attached.

Needed repository secrets (same four as before, same permanent key):
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.

`versionCode` is the Actions run number, exactly as in the old build, and `applicationId` is
unchanged (`com.woodlandweave.run`). Pushing this project to the same repository and signing with the same
key therefore makes the native app install **over** the old Capacitor app as a normal update.
Change the version name in the `VERSION` file.

The unit-test job reports problems but never blocks the APK, so a red test cannot strand an update.
The old iOS job is gone: there is no iOS source yet (the Swift port is the next step).

## In-app update
On launch and when the app returns to the foreground (at most every ten minutes) it asks GitHub for the
latest release. A newer build number in the tag shows "New update available!" in the menu; Settings has a
Check / Download row. The download only accepts https URLs under
`github.com/Szucsy92/woodland-weave-run/releases/download/`, checks the byte count, then hands the APK to the
system installer through a FileProvider (it sends you to the "install unknown apps" screen first if needed).

## Layout
| Package | What it is |
|---|---|
| `sim` | Pure game logic, a line-for-line port of `simulation.ts`. No Android imports. |
| `GameEngine` | Frame loop, power-ups, survive sequence, camera, UI state (replaces the React refs). |
| `gl` | OpenGL ES 3.0 renderer: displaced terrain, wet normal-mapped bark and ground, instanced forest with crown LOD, fog, headlamp, glow sprites, black holes and wormhole relics; `PostFx` is the MSAA full-screen effect pass (blur, glitch, lens, frost, shockwave rings, wormhole, hue). |
| `audio` | AudioTrack loop with live low-pass, stings, synthesized cues, haptics. |
| `data` | DataStore settings and best scores (replaces localStorage). |
| `update` | The OTA updater (replaces the AndroidUpdater JavaScript bridge). |
| `input` | Gravity-sensor tilt steering (no gimbal flip). |
| `ui` | Jetpack Compose menu, HUD, pause, settings in the original glass style. |

## Parity tests
`SimulationParityTest` pins the Kotlin port to numbers produced by the original TypeScript
(hash, terrain, forest layout, a 600-step run, secrets, gates, holes). `UpdaterTest` covers tag parsing,
update selection and the trusted-URL rule.
