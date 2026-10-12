#!/bin/bash
# Optional Code-OSS host. Keep tools under /root so runtime upgrades preserve them.
set -eu
VERSION=4.141.0
BASE=/root/.local/share/m4-code
TARGET="$BASE/$VERSION"
BIN="$TARGET/node_modules/.bin/code-server"
mkdir -p "$BASE/user-data/User" "$BASE/bin"
# BusyBox ash uses vfork, unavailable under Android PRoot on some kernels.
# Limit the workaround to this host's children; preserve the system /bin/sh.
ln -sf /bin/bash "$BASE/bin/sh"
export PATH="$BASE/bin:$PATH"
export SHELL=/bin/bash
export npm_config_script_shell=/bin/bash
export MAKEFLAGS='SHELL=/bin/bash -j2'
# Keep M4's own free-model Agent as the AI entry point. Respect later user edits.
if [ ! -e "$BASE/user-data/User/settings.json" ]; then
    cat > "$BASE/user-data/User/settings.json" <<'SETTINGS'
{
  "chat.disableAIFeatures": true,
  "telemetry.telemetryLevel": "off",
  "workbench.secondarySideBar.defaultVisibility": "hidden",
  "workbench.colorTheme": "Default Dark Modern"
}
SETTINGS
fi
if [ -x "$BIN" ] && [ -f "$TARGET/ready" ] && "$BIN" --version; then
    exit 0
fi
printf '%s\n' 'Installing compiler dependencies for the optional editor host…'
apk-v2 add --no-cache --no-scripts build-base python3 pkgconf libsecret-dev krb5-dev linux-headers
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
