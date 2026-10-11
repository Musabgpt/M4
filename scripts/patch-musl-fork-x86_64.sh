#!/bin/sh
# M4 Android x86_64 compatibility experiment - never used in ARM64 release.
# Rebuild Alpine's musl 1.2.6-r2 with its signed-release source and
# all five Alpine stable patch checksums verified, including CVE fixes.
# musl _Fork()/vfork() select SYS_clone where Android disallows SYS_fork.
set -eu
[ "$(uname -m)" = "x86_64" ] || { echo "x86_64 build host required" >&2; exit 1; }
ROOT=/opt/guest
apk add --no-cache build-base patch curl ca-certificates binutils
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"
curl --fail --location --silent --show-error --retry 3 \
  https://musl.libc.org/releases/musl-1.2.6.tar.gz -o musl-1.2.6.tar.gz
printf '%s  %s\n' d585fd3b613c66151fc3249e8ed44f77020cb5e6c1e635a616d3f9f82460512a musl-1.2.6.tar.gz | sha256sum -c -
tar -xzf musl-1.2.6.tar.gz
cd musl-1.2.6
APORTS=f5640d3a10f664c9119720c60515265d3d6f6d01
mkdir -p "$WORK/patches"
cd "$WORK/patches"
for name in handle-aux-at_base.patch \
  0001-add-stub-for-pthread_mutexattr_setprioceiling.patch \
  fix-loongarch64-zero-len-extcontext.patch \
  CVE-2026-6042.patch CVE-2026-40200.patch; do
  curl --fail --location --silent --show-error --retry 3 \
    "https://raw.githubusercontent.com/alpinelinux/aports/$APORTS/main/musl/$name" -o "$name"
done
cat > checksums.sha512 <<'ALPINE_PATCHES'
a76f79b801497ad994746cf82bb6eaf86f9e1ae646e6819fbae8532a7f4eee53a96ac1d4e789ec8f66aea2a68027b0597f7a579b3369e01258da8accfce41370  handle-aux-at_base.patch
83ec0f774dbb5d4f4d4b917d149ea0ef609d028d18ae7624e90fedda6e3174c91ade93751bfd376adfa2848155110fc4dd02b6790363b98e5395c4787e8731e8  0001-add-stub-for-pthread_mutexattr_setprioceiling.patch
6eae45aa82db69bc96386aedf3d1bfe83dfa0a7b4b824326c1c7e1bab31100d635ed0e8930c26a1694697fad59167a42e2b5f3a2ac51b07e36d85f047e268cff  fix-loongarch64-zero-len-extcontext.patch
f7849abeab0e4eab992a80464afa07b9c8aae5cee76040523b9dbc0931435f28ea8f5792b8a4a0cd6d608ac2f37e3225e89afcb3171a0a6df44450ed57cee83b  CVE-2026-6042.patch
a64ab7688d1a85e560b5687783df482d2467a79b7529a8dc80c46ddda8dc2a812f2261557fc8ee98dc3bdf7322761bd6d9  CVE-2026-40200.patch
ALPINE_PATCHES
sha512sum -c checksums.sha512
cd "$WORK/musl-1.2.6"
for p in "$WORK"/patches/*.patch; do patch -p1 < "$p"; done
# Alpine's optimization selection also removes these two hand-coded asm files.
rm src/string/x86_64/memcpy.s src/string/x86_64/memmove.s
# Every replacement is asserted so future musl changes fail rather than
# silently shipping an unmodified or unpredictable runtime.
for target in src/process/_Fork.c src/process/vfork.c; do
  [ "$(grep -c '^#ifdef SYS_fork$' "$target")" -eq 1 ] || \
    { echo "Unexpected musl syscall branch in $target" >&2; exit 1; }
  sed -i 's/^#ifdef SYS_fork$/#if defined(SYS_fork) \&\& !defined(__x86_64__)/' "$target"
  grep -F '#if defined(SYS_fork) && !defined(__x86_64__)' "$target" >/dev/null
done
LDFLAGS="-Wl,-soname,libc.musl-x86_64.so.1" \
  ./configure --prefix=/usr --sysconfdir=/etc --enable-debug
make -j2
test -s lib/libc.so
readelf -h lib/libc.so | grep -F 'Advanced Micro Devices X86-64' >/dev/null
readelf -d lib/libc.so | grep -F 'libc.musl-x86_64.so.1' >/dev/null
test -f "$ROOT/lib/ld-musl-x86_64.so.1"
test ! -L "$ROOT/lib/ld-musl-x86_64.so.1"
sha256sum "$ROOT/lib/ld-musl-x86_64.so.1" lib/libc.so
cp lib/libc.so "$ROOT/lib/ld-musl-x86_64.so.1"
# Linux smoke test verifies dynamic loader compatibility. Actual Android
# seccomp/PRoot compatibility is checked separately by instrumentation.
chroot "$ROOT" /bin/bash -c 'set -e; python3 -c "import subprocess; assert subprocess.check_output([\"/bin/echo\",\"ok\"]).strip()==b\"ok\""; node -v >/dev/null'
rm -rf "$ROOT/opt/m4-build"
echo "PASS: x86_64 musl runtime rebuilt from pinned 1.2.6 source with all Alpine security patches and clone fork path"
