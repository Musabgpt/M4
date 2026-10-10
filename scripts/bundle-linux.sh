#!/usr/bin/env bash
set -euo pipefail
ROOT="$(pwd)"
ASSET="$ROOT/app/src/main/assets"
NATIVE="$ROOT/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$ASSET" "$NATIVE"
python3 "$ROOT/scripts/fetch-linux.py" "$ASSET" "$NATIVE"
test -s "$ASSET/alpine-rootfs.tgz"
test -s "$NATIVE/libproot.so"
test -s "$NATIVE/libproot-loader.so"

# Install real ARM64 developer packages in CI, so they work even if apk
# networking in Android/PRoot fails. Alpine signs repository indexes and
# packages; signature validation remains enabled. Do not use --allow-untrusted.
STAGING="$RUNNER_TEMP/arabic-terminal-alpine"
mkdir -p "$STAGING"
tar -xzf "$ASSET/alpine-rootfs.tgz" -C "$STAGING"
docker run --rm --network host \
  -v "$STAGING:/opt/guest" alpine:3.24 sh -ec '
    apk --root /opt/guest --arch aarch64 update
    apk --root /opt/guest --arch aarch64 add --scripts=no --commit-hooks=no \
        python3 py3-pip git nodejs npm curl ca-certificates-bundle \
        bash coreutils findutils grep sed nano less tmux htop jq ripgrep \
        procps openssh-client zip unzip file py3-pytest github-cli
    # GitHub CLI is required for the in-app OAuth device login.
    test -x /opt/guest/usr/bin/gh
    for optional in tree-sitter-cli ast-grep; do
        if apk --root /opt/guest --arch aarch64 add --scripts=no --commit-hooks=no "$optional"; then
            echo "Bundled Alpine developer tool: $optional"
        else
            echo "WARNING: optional Alpine developer tool unavailable: $optional" >&2
        fi
    done
    apk --root /opt/guest --arch aarch64 info -e python3 git nodejs npm bash coreutils nano tmux github-cli
    test -f /opt/guest/usr/bin/python3 || test -L /opt/guest/usr/bin/python3
    test -f /opt/guest/usr/bin/node || test -L /opt/guest/usr/bin/node
  '

# Include the actual M4 source tree from this exact Git revision.
# git archive excludes .git history, build artifacts and untracked secrets.
# Recreate a clean local Git baseline for offline, on-device editing.
SEED="$STAGING/opt/m4-seed"
mkdir -p "$SEED"
git -C "$ROOT" archive --format=tar HEAD | tar -C "$SEED" -xf -
test -s "$SEED/agent/agent.py"
test -s "$SEED/app/src/main/java/com/musabgpt/m4/ChatScreen.kt"
git -C "$SEED" init -q
git -C "$SEED" -c user.name='M4 APK Seed' -c user.email='m4-seed@localhost.invalid' add -A
git -C "$SEED" -c user.name='M4 APK Seed' -c user.email='m4-seed@localhost.invalid' commit -qm 'Offline M4 source baseline'
git -C "$SEED" remote add origin https://github.com/Musabgpt/M4.git
test -d "$SEED/.git"

# Ship only the integrated M4 engine and fixed gateway catalog offline.
mkdir -p "$STAGING/opt/term-agent" "$STAGING/usr/local/bin"
cp "$ROOT/agent/agent.py" "$STAGING/opt/term-agent/agent.py"
cp -R "$ROOT/agent/newal" "$STAGING/opt/term-agent/newal"
find "$STAGING/opt/term-agent/newal" -type f -name '*.pyc' -delete
cp "$ROOT/agent/system_prompt.md" "$STAGING/opt/term-agent/system_prompt.md"
cp "$ROOT/agent/provider_routes.py" "$STAGING/opt/term-agent/provider_routes.py"
cp "$ROOT/agent/term-agent.sh" "$STAGING/usr/local/bin/term-agent"
cp "$ROOT/agent/term-agent-watch.sh" "$STAGING/usr/local/bin/term-agent-watch"
cp "$ROOT/agent/m4-agent-start.sh" "$STAGING/usr/local/bin/m4-agent-start"
chmod 755 "$STAGING/usr/local/bin/term-agent" "$STAGING/usr/local/bin/term-agent-watch" "$STAGING/usr/local/bin/m4-agent-start"
python3 -m py_compile "$ROOT/agent/agent.py"
test -s "$STAGING/opt/term-agent/agent.py"
test -s "$STAGING/usr/bin/pytest"

# apk-tools v3 uses syscalls not always supported by Android PRoot.
# Install the official Alpine 3.22 ARM64 apk-tools v2 static package using
# Alpine's signed-index and package verification, and keep current apk intact.
LEGACY="$RUNNER_TEMP/arabic-terminal-apk-v2"
mkdir -p "$LEGACY/etc/apk/keys"
# Union official Alpine 3.24 rootfs keys with Alpine 3.22 container keys;
# both are distributed by Alpine, and unknown signatures still fail closed.
cp "$STAGING/etc/apk/keys/"*.pub "$LEGACY/etc/apk/keys/"
docker run --rm --network host -v "$LEGACY:/opt/apk-v2" alpine:3.22 sh -ec '
  mkdir -p /opt/apk-v2/etc/apk/keys
  cp /etc/apk/keys/*.pub /opt/apk-v2/etc/apk/keys/
  apk --root /opt/apk-v2 --arch aarch64 --initdb --no-cache \
    --repositories-file /dev/null \
    --repository https://dl-cdn.alpinelinux.org/alpine/v3.22/main \
    add apk-tools-static
  test -s /opt/apk-v2/sbin/apk.static
'
mkdir -p "$STAGING/usr/local/bin"
cp "$LEGACY/sbin/apk.static" "$STAGING/usr/local/bin/apk-v2"
chmod 755 "$STAGING/usr/local/bin/apk-v2"
file "$STAGING/usr/local/bin/apk-v2"
test "$(wc -c < "$STAGING/usr/local/bin/apk-v2")" -gt 1000000

echo "Offline developer tools:"
for exe in "$STAGING/usr/bin/"{python3,node,npm,git}; do
    test -e "$exe" || { echo "Missing packaged executable: $exe"; exit 1; }
done
# Full offline rootfs replaces the minimal 4MB upstream archive and is
# extracted once at runtime by LinuxEnvironment.
tar --hard-dereference --exclude='./lib/apk/db/lock' --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner \
  -czf "$ASSET/alpine-rootfs.tgz" -C "$STAGING" .
python3 - "$ASSET/alpine-rootfs.tgz" <<'PY'
import sys, tarfile
with tarfile.open(sys.argv[1], "r:gz") as tf:
    hardlinks=[e.name for e in tf if e.islnk()]
    if hardlinks:
        raise SystemExit("Hardlinks remain in bundled archive: "+str(hardlinks[:10]))
print("PASS: rootfs archive has no host hard links")
PY
sha256sum "$ASSET/alpine-rootfs.tgz" | awk '{print $1}' > "$ASSET/alpine-rootfs.sha256"
test "$(stat -c%s "$ASSET/alpine-rootfs.tgz")" -lt 150000000
echo "Linux runtime + Python/Git/Node/npm bundled: $(du -h "$ASSET/alpine-rootfs.tgz" | cut -f1)"
