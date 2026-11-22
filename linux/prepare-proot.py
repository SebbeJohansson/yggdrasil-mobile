#!/usr/bin/env python3
"""Fetches proot for Ygg's own Linux and puts it where Android runs it from.

Android 10+ runs no program written into an app's data, only the app's
native libraries (unpacked into nativeLibraryDir). So proot and its loader
ship as jniLibs named lib*.so, as UserLAnd and Termux do. The builds are
Termux's (made for Android's own C library), checked against the SHA-256s
below. Two names are patched, same length or shorter, nul-padded:

  libtalloc.so.2  -> libtalloc.so   (proot's NEEDED, talloc's SONAME): a
                                     packaged library must be called lib*.so

Run again when bumping a version: update the table, run, commit the output.
    python3 linux/prepare-proot.py
"""

import hashlib
import io
import lzma
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

BASE = "https://packages.termux.dev/apt/termux-main/"
PACKAGES = {
    # file in the .deb -> (package path, sha256 of the .deb, name in jniLibs)
    "proot": ("pool/main/p/proot/proot_5.1.107.102_aarch64.deb",
              "10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030"),
    "talloc": ("pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb",
               "556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7"),
    "shmem": ("pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb",
              "0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6"),
}
PREFIX = "data/data/com.termux/files/usr/"
OUT = Path(__file__).resolve().parent.parent / "app/src/main/jniLibs/arm64-v8a"
WANTED = {
    "proot": [(PREFIX + "bin/proot", "libproot.so"), (PREFIX + "libexec/proot/loader", "libproot-loader.so")],
    "talloc": [(PREFIX + "lib/libtalloc.so.2.5.0", "libtalloc.so")],
    "shmem": [(PREFIX + "lib/libandroid-shmem.so", "libandroid-shmem.so")],
}


def deb_data(deb: bytes) -> tarfile.TarFile:
    """The data.tar.xz of a .deb (an ar archive)."""
    assert deb[:8] == b"!<arch>\n"
    pos = 8
    while pos < len(deb):
        name = deb[pos:pos + 16].decode().strip().rstrip("/")
        size = int(deb[pos + 48:pos + 58].decode().strip())
        body = deb[pos + 60:pos + 60 + size]
        if name.startswith("data.tar"):
            data = lzma.decompress(body) if name.endswith(".xz") else body
            return tarfile.open(fileobj=io.BytesIO(data))
        pos += 60 + size + (size % 2)
    raise SystemExit("no data.tar in the package")


def rename_needed(blob: bytes, old: bytes, new: bytes) -> bytes:
    assert len(new) <= len(old)
    target = old + b"\0"
    count = blob.count(target)
    if count == 0:
        raise SystemExit(f"{old!r} not found")
    return blob.replace(target, new + b"\0" * (len(old) - len(new) + 1))


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    for key, (path, sha) in PACKAGES.items():
        deb = urllib.request.urlopen(BASE + path, timeout=60).read()
        got = hashlib.sha256(deb).hexdigest()
        if got != sha:
            raise SystemExit(f"{path}: sha256 {got}, expected {sha}")
        tar = deb_data(deb)
        for member, name in WANTED[key]:
            blob = tar.extractfile("./" + member).read()
            if name in ("libproot.so", "libtalloc.so"):
                blob = rename_needed(blob, b"libtalloc.so.2", b"libtalloc.so")
            (OUT / name).write_bytes(blob)
            print(f"{name}: {len(blob)} bytes, sha256 {hashlib.sha256(blob).hexdigest()[:16]}…")
    return 0


if __name__ == "__main__":
    sys.exit(main())
