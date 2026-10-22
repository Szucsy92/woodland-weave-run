/**
 * A previously cached start-up module can go stale when the served code changes
 * underneath an already-open page (dependency re-optimisation, a restarted
 * dev server, a new deployment). The symptom is a blank screen: the browser
 * fails to fetch the entry chunk and nothing ever renders.
 *
 * This guard runs before the app's own bootstrap, recognises that specific
 * failure, and reloads the page once. The sessionStorage marker makes it
 * single-shot so a genuinely broken page can never loop.
 */

export const RELOAD_GUARD_KEY = "woodland-stale-reload";

/** How long a recovery reload is considered "already tried". */
export const RELOAD_COOLDOWN_MS = 20000;

/** Messages that mean "a module we need to start the app never arrived". */
export const STALE_LOAD_PATTERN =
  "Failed to fetch dynamically imported module|error loading dynamically imported module|Importing a module script failed|Unable to preload CSS";

/** How long to wait for the first frame before treating an empty page as dead. */
export const FIRST_FRAME_TIMEOUT_MS = 10000;

export function isStaleLoadMessage(message: string): boolean {
  return new RegExp(STALE_LOAD_PATTERN, "i").test(message || "");
}

/** Router boundaries catch import errors before global rejection handlers see them. */
export function recoverCaughtStaleLoad(error: Error): void {
  if (typeof window === "undefined" || !isStaleLoadMessage(error.message)) return;
  window.dispatchEvent(new Event("woodland-stale-module"));
}

export const STALE_BUNDLE_RECOVERY_SCRIPT = [
  "(function () {",
  "  var KEY = " + JSON.stringify(RELOAD_GUARD_KEY) + ";",
  "  var COOLDOWN = " + RELOAD_COOLDOWN_MS + ";",
  "  var PATTERN = new RegExp(" + JSON.stringify(STALE_LOAD_PATTERN) + ", 'i');",
  "  function once() {",
  "    try {",
  "      var last = Number(sessionStorage.getItem(KEY) || 0);",
  "      if (last && Date.now() - last < COOLDOWN) return false;",
  "      sessionStorage.setItem(KEY, String(Date.now()));",
  "      return true;",
  "    } catch (e) {",
  "      return false;",
  "    }",
  "  }",
  "  function revive() {",
  "    if (once()) window.location.reload();",
  "  }",
  "  window.addEventListener('woodland-stale-module', revive);",
  "  window.addEventListener('error', function (ev) {",
  "    var msg = (ev && (ev.message || (ev.error && ev.error.message))) || '';",
  "    if (PATTERN.test(msg)) revive();",
  "  }, true);",
  "  window.addEventListener('unhandledrejection', function (ev) {",
  "    var r = ev && ev.reason;",
  "    var msg = (r && (r.message || r.toString())) || '';",
  "    if (PATTERN.test(msg)) revive();",
  "  });",
  "  setTimeout(function () {",
  "    var body = document.body;",
  "    if (!body) return;",
  "    if (!body.querySelector('canvas') && !(body.innerText || '').trim()) revive();",
  "  }, " + FIRST_FRAME_TIMEOUT_MS + ");",
  "})();",
].join("\n");
