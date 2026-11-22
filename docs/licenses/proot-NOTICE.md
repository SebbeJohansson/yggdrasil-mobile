# proot, talloc and libandroid-shmem in Ygg

Ygg's own Linux runs Ubuntu under **proot**, shipped in the app as native
libraries (`app/src/main/jniLibs/arm64-v8a/`). They're Termux's builds for
Android, fetched and checked by `linux/prepare-proot.py`, with one name
patched (`libtalloc.so.2` → `libtalloc.so`, so Android packages it).

| File | Project | Version | Licence | Source |
| --- | --- | --- | --- | --- |
| `libproot.so`, `libproot-loader.so` | PRoot (Termux's fork) | 5.1.107.102 | GPL-2.0-or-later | https://github.com/termux/proot, Termux package `proot` |
| `libtalloc.so` | talloc (Samba) | 2.5.0 | LGPL-3.0-or-later | https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz |
| `libandroid-shmem.so` | libandroid-shmem (Termux) | 0.7 | BSD-3-Clause | https://github.com/termux/libandroid-shmem |

The exact packages are the `.deb` files named in `linux/prepare-proot.py`,
from https://packages.termux.dev/apt/termux-main/, with their SHA-256s; the
build recipes are in https://github.com/termux/termux-packages under
`packages/proot`, `packages/libtalloc` and `packages/libandroid-shmem`.

proot runs as its own program (Ygg starts it with `ProcessBuilder`); Ygg
doesn't link against it. The full licence texts are in those source trees.

Ubuntu's base (downloaded on first setup, not shipped) is from
https://cdimage.ubuntu.com/ubuntu-base/ under Ubuntu's licences. Claude Code
is downloaded from Anthropic's download host under Anthropic's terms.
