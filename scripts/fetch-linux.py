#!/usr/bin/env python3
"""Build-time fetcher for official Alpine and verified Termux PRoot packages."""
import hashlib
import lzma
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import urllib.request

assets,libs=map(Path,sys.argv[1:3])
ALPINE_VERSION="3.24.2"
ALPINE_BASE="https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64"
TERMUX="https://packages.termux.dev/apt/termux-main"
def get(url):
    req=urllib.request.Request(url,headers={"User-Agent":"MusabArabicTerminal/0.5"})
    with urllib.request.urlopen(req,timeout=120) as response:
        return response.read()
def verify(data,checksum,filename):
    actual=hashlib.sha256(data).hexdigest()
    if actual.lower()!=checksum.lower():
        raise ValueError("SHA256 mismatch "+filename)
filename=f"alpine-minirootfs-{ALPINE_VERSION}-aarch64.tar.gz"
archive=get(f"{ALPINE_BASE}/{filename}")
sha=get(f"{ALPINE_BASE}/{filename}.sha256").decode().split()[0]
verify(archive,sha,filename)
(assets/"alpine-rootfs.tgz").write_bytes(archive)
(assets/"alpine-rootfs.sha256").write_text(sha+"\n")
print(f"Verified Alpine {ALPINE_VERSION}, {len(archive)} bytes")
# Official apt hosts can publish .gz or plain Packages but omit .xz.
# Resolve available format instead of hard-coding the extension.
import gzip
import urllib.error
index=None
for extension in ("Packages.xz","Packages.gz","Packages"):
    try:
        packages=get(f"{TERMUX}/dists/stable/main/binary-aarch64/{extension}")
        if extension.endswith(".xz"):
            index=lzma.decompress(packages).decode()
        elif extension.endswith(".gz"):
            index=gzip.decompress(packages).decode()
        else:
            index=packages.decode()
        print("Termux package metadata format:",extension)
        break
    except urllib.error.HTTPError as e:
        if e.code!=404:raise
if index is None:raise RuntimeError("Termux package index unavailable (all official formats)")
records={}
for block in index.split("\n\n"):
    item={}
    for line in block.splitlines():
        if not line or line.startswith(" ") or ":" not in line:continue
        key,val=line.split(":",1)
        item[key.strip()]=val.strip()
    if item.get("Package") in ("proot","libtalloc","libandroid-shmem"):
        records[item["Package"]]=item
for name in ("proot","libtalloc","libandroid-shmem"):
    if name not in records:raise RuntimeError("Missing Termux package "+name)
with tempfile.TemporaryDirectory() as work:
    tmp=Path(work);unpacked=tmp/"unpacked";unpacked.mkdir()
    for name in ("proot","libtalloc","libandroid-shmem"):
        meta=records[name]
        data=get(f'{TERMUX}/{meta["Filename"]}')
        verify(data,meta["SHA256"],name)
        deb=tmp/f"{name}.deb";deb.write_bytes(data)
        subprocess.run(["dpkg-deb","-x",str(deb),str(unpacked)],check=True)
        print("Verified",name,meta.get("Version"))
    all_files=[p for p in unpacked.rglob("*") if p.is_file()]
    def pick(ending):
        matches=[p for p in all_files if str(p).endswith(ending)]
        if not matches:raise RuntimeError("Missing Termux binary "+ending)
        return matches[0]
    talloc=next((p for p in all_files if p.name.startswith("libtalloc.so.") and not p.is_symlink()),None)
    if talloc is None:raise RuntimeError("Missing talloc binary")
    binary={
        pick("/bin/proot"):"libproot.so",
        pick("/libexec/proot/loader"):"libproot-loader.so",
        talloc:"libtalloc.so",
        pick("/libandroid-shmem.so"):"libandroid-shmem.so"
    }
    for original,name in binary.items():
        dest=libs/name
        shutil.copyfile(original,dest)
        dest.chmod(0o755)
    subprocess.run(["patchelf","--replace-needed","libtalloc.so.2","libtalloc.so",
                    str(libs/"libproot.so")],check=True)
    deps=subprocess.check_output(["readelf","-d",str(libs/"libproot.so")],text=True)
    if "libtalloc.so.2" in deps:raise RuntimeError("Cannot fix Android linker dependency")
    for path in libs.glob("*.so"):print(path.name,path.stat().st_size)
