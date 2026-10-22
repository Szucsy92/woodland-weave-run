#!/usr/bin/env bash
set -euo pipefail

for name in ANDROID_KEYSTORE_BASE64 ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD; do
  if [ -z "${!name:-}" ]; then
    echo "Missing required GitHub Actions secret: $name. No APK will be published." >&2
    exit 1
  fi
done

umask 077
key=android/app/release.keystore
cleanup_on_error() { rm -f "$key"; }
trap cleanup_on_error ERR
printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 --decode > "$key"
cert=$(mktemp)
trap 'rm -f "$cert"' EXIT
keytool -exportcert -keystore "$key" -storepass:env ANDROID_KEYSTORE_PASSWORD \
  -alias "$ANDROID_KEY_ALIAS" -file "$cert" >/dev/null 2>&1
fingerprint=$(sha256sum "$cert" | cut -d ' ' -f 1)
if [ -n "${GITHUB_ENV:-}" ]; then
  printf 'ANDROID_SIGNING_SHA256=%s\n' "$fingerprint" >> "$GITHUB_ENV"
fi
echo "Permanent signing certificate SHA-256: $fingerprint"