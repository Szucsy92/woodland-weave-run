# Woodland Weave Run

Első személyű, végtelen éjszakai erdőfutás, készítette **László Szűcs (Szucsy92)**.

Offline játék: nincs szerver, webes felület vagy adatgyűjtés. Az egyetlen hálózati funkció a GitHub Releases alapú frissítés (Beállítások → Update, és induláskor csendes ellenőrzés), az Android telepítést jóváhagyatja.

## Build (Node.js 22+)

```sh
npm install
npm run build
npx cap sync android
cd android && ./gradlew assembleDebug
```

A GitHub Actions workflow (`.github/workflows/android.yml`) aláírt APK-t épít és Release-ként publikálja; az Actions secrets-ben kell a négy `ANDROID_KEY*` titok.

## Jogok

Lásd a [LICENSE](LICENSE) fájlt.
