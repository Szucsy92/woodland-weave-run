// Checks the GitHub Releases of this game for a newer installable APK.
export const RELEASES_API = "https://api.github.com/repos/Szucsy92/woodland-weave-run/releases/latest";

type NativeUpdater = { versionCode(): number; download(url: string): void };

export function nativeUpdater(): NativeUpdater | null {
  if (typeof window === "undefined") return null;
  return (window as unknown as { AndroidUpdater?: NativeUpdater }).AndroidUpdater ?? null;
}

/** Tags look like v1.0.0-build12; the build number is the Android versionCode. */
export function buildFromTag(tag: string): number | null {
  const m = /build(\d+)/i.exec(tag);
  return m ? Number(m[1]) : null;
}

export type UpdateInfo = { build: number; name: string; apkUrl: string };

export function pickUpdate(release: { tag_name?: string; name?: string; assets?: { name: string; browser_download_url: string }[] }, current: number): UpdateInfo | null {
  const build = buildFromTag(release.tag_name ?? "");
  const apk = release.assets?.find((a) => a.name.toLowerCase().endsWith(".apk"));
  if (build === null || !apk || build <= current) return null;
  return { build, name: release.name ?? release.tag_name ?? "", apkUrl: apk.browser_download_url };
}

export async function checkForUpdate(current: number): Promise<UpdateInfo | null> {
  const res = await fetch(RELEASES_API, { headers: { Accept: "application/vnd.github+json" }, cache: "no-store" });
  if (!res.ok) throw new Error(`status ${res.status}`);
  return pickUpdate(await res.json(), current);
}
