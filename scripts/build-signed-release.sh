#!/usr/bin/env bash
# This script is executed only in GitHub Actions with the EXISTING NewAl release bundle.
# No keystore is ever uploaded to Git or included in the APK.
set -euo pipefail
: "${MUSABAI_RELEASE_SIGNING_BUNDLE:?Missing original NewAl signing bundle in M4 Actions secrets}"
: "${ANDROID_HOME:?Missing Android SDK}"
umask 077
dir="${RUNNER_TEMP:?}/m4-signing"
mkdir -p "$dir"
chmod 700 "$dir"
trap 'rm -rf "$dir"' EXIT HUP INT TERM
printf '%s' "$MUSABAI_RELEASE_SIGNING_BUNDLE" | base64 --decode > "$dir/bundle.json"
python3 - "$dir/bundle.json" "$dir/release.jks" "$dir/store" "$dir/alias" "$dir/key" <<'PY'
import base64, binascii, json, os, pathlib, sys
src, ks, sp, alias, kp = (pathlib.Path(n) for n in sys.argv[1:])
b = json.loads(src.read_text(encoding="utf-8"))
required = ("keystore_b64", "store_password", "key_alias", "key_password")
if not all(isinstance(b.get(k), str) and b[k] for k in required):
    raise SystemExit("Signing bundle is missing required fields")
for k in required[1:]:
    if any(c in b[k] for c in "\r\n\x00"):
        raise SystemExit("Invalid signing bundle field")
try:
    raw = base64.b64decode(b["keystore_b64"], validate=True)
except (ValueError, binascii.Error):
    raise SystemExit("Invalid keystore encoding")
if len(raw) < 100:
    raise SystemExit("Missing keystore")
ks.write_bytes(raw)
for path, value in ((sp,b["store_password"]),(alias,b["key_alias"]),(kp,b["key_password"])):
    path.write_text(value,encoding="utf-8")
for path in (src,ks,sp,alias,kp): os.chmod(path,0o600)
PY
export MUSABAI_RELEASE_STORE_FILE="$dir/release.jks"
export MUSABAI_RELEASE_STORE_PASSWORD="$(cat "$dir/store")"
export MUSABAI_RELEASE_KEY_ALIAS="$(cat "$dir/alias")"
export MUSABAI_RELEASE_KEY_PASSWORD="$(cat "$dir/key")"
printf '::add-mask::%s\n' "$MUSABAI_RELEASE_STORE_PASSWORD" "$MUSABAI_RELEASE_KEY_ALIAS" "$MUSABAI_RELEASE_KEY_PASSWORD"
gradle --no-daemon :app:assembleRelease
apk="app/build/outputs/apk/release/app-release.apk"
test -s "$apk"
apksigner="$ANDROID_HOME/build-tools/36.0.0/apksigner"
"$apksigner" verify --verbose --print-certs "$apk" > "$dir/signature.txt"
actual="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$dir/signature.txt" | head -1 | tr '[:upper:]' '[:lower:]' | tr -d ':[:space:]')"
expected="$(tr '[:upper:]' '[:lower:]' < android/signing-cert-sha256.txt | tr -d ':[:space:]')"
test "${#actual}" -eq 64 && test "${#expected}" -eq 64
if [ "$actual" != "$expected" ]; then
    echo "::error::Original NewAl-Cloud signing certificate mismatch; refusing to publish update"
    exit 1
fi
mkdir -p release-output
cp "$apk" release-output/M4-NewAl-Cloud-Update.apk
sha256sum release-output/M4-NewAl-Cloud-Update.apk > release-output/M4-NewAl-Cloud-Update.apk.sha256
printf 'SIGNING_CERT_SHA256=%s\n' "$actual"
echo "Signed Android update APK verified against NewAl-Cloud certificate."
