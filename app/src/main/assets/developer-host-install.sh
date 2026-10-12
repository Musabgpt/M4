#!/bin/sh
# Optional Code-OSS host. Keep tools under /root so runtime upgrades preserve them.
set -eu
VERSION=4.141.0
BASE=/root/.local/share/m4-code
TARGET="$BASE/$VERSION"
BIN="$TARGET/node_modules/.bin/code-server"
mkdir -p "$BASE"
if [ -x "$BIN" ] && [ -f "$TARGET/ready" ] && "$BIN" --version; then
    exit 0
fi
printf '%s\n' 'Installing compiler dependencies for the optional editor host…'
apk-v2 add --no-cache build-base python3 pkgconf libsecret-dev krb5-dev linux-headers
[ "$(node -p 'process.versions.node.split(".")[0]')" = 24 ] || {
    echo 'This editor version requires Node.js 24.' >&2; exit 1;
}
STAGE="$BASE/installing"
rm -rf "$STAGE"
mkdir -p "$STAGE"
printf '%s\n' 'Downloading Code-OSS and building native extensions. This can take several minutes…'
npm install --prefix "$STAGE" --no-audit --no-fund "code-server@$VERSION"
"$STAGE/node_modules/.bin/code-server" --version
# Never mark a partially installed host ready.
printf '%s\n' "$VERSION" > "$STAGE/ready"
rm -rf "$TARGET"
mv "$STAGE" "$TARGET"
printf '%s\n' 'Editor host installed.'
