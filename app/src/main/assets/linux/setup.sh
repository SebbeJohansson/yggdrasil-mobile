#!/bin/bash
# Sets up a Debian/Ubuntu for Claude Code and Ygg's bridge: Ygg's own Linux
# (as root, under proot), or UserLAnd's and Termux's Ubuntu. Run again to update.
# Expects the bridge at ~/ygg-bridge/ygg_bridge.py.
set -eo pipefail
export DEBIAN_FRONTEND=noninteractive
SUDO=""
[ "$(id -u)" -ne 0 ] && SUDO="sudo -E"

echo "== Packages"
$SUDO apt-get update
$SUDO apt-get install -y --no-install-recommends python3 git curl ca-certificates util-linux bsdutils procps

echo "== Claude Code"
# The official binary from Anthropic's download host, checked against its
# manifest, as claude.ai/install.sh fetches it. The installer's own
# `claude install` step (and the auto-updater) copy the binary over itself,
# which fails under proot, so it's placed by hand; running setup again updates it.
base=https://downloads.claude.ai/claude-code-releases
case "$(uname -m)" in
  x86_64|amd64) arch=x64 ;;
  aarch64|arm64) arch=arm64 ;;
  *) echo "unsupported: $(uname -m)"; exit 1 ;;
esac
version=$(curl -fsSL "$base/latest")
curl -fsSL "$base/$version/manifest.json" -o /tmp/claude-manifest.json
sum=$(python3 -c "import json; print(json.load(open('/tmp/claude-manifest.json'))['platforms']['linux-$arch']['checksum'])")
mkdir -p "$HOME/.local/share/claude-code" "$HOME/.local/bin"
bin="$HOME/.local/share/claude-code/claude-$version"
if [ ! -x "$bin" ] || [ "$(sha256sum "$bin" | cut -d' ' -f1)" != "$sum" ]; then
  echo "Downloading Claude Code $version…"
  curl -fsSL "$base/$version/linux-$arch/claude" -o "$bin.part"
  [ "$(sha256sum "$bin.part" | cut -d' ' -f1)" = "$sum" ] || { echo "Claude Code's checksum didn't match"; exit 1; }
  chmod +x "$bin.part"
  mv "$bin.part" "$bin"
fi
ln -sf "$bin" "$HOME/.local/bin/claude"
export PATH="$HOME/.local/bin:$PATH"
find "$HOME/.local/share/claude-code" -maxdepth 1 -type f -name 'claude-[0-9]*' ! -name "claude-$version" -delete
claude --version

echo "== herdr"
# herdr (https://herdr.dev, Apache-2.0), the terminal multiplexer Claude Code
# runs in for local sessions, as on the desktop. One static binary from its
# GitHub release, pinned and checked; a new version means new sums here.
herdr_version=0.9.5
case "$arch" in
  arm64) herdr_asset=herdr-linux-aarch64; herdr_sum=17d7536f2da50859523680ef279dffcac8e4a235f4e46fe8da6c2ec44141897c ;;
  x64) herdr_asset=herdr-linux-x86_64; herdr_sum=14d9e8bc8b4a9e0118d77c32266f76d461f09b40342eb23752960bd89f59929d ;;
esac
herdr_bin="$HOME/.local/share/herdr/herdr-$herdr_version"
mkdir -p "$HOME/.local/share/herdr"
if [ ! -x "$herdr_bin" ] || [ "$(sha256sum "$herdr_bin" | cut -d' ' -f1)" != "$herdr_sum" ]; then
  echo "Downloading herdr $herdr_version…"
  curl -fsSL "https://github.com/herdrdev/herdr/releases/download/v$herdr_version/$herdr_asset" -o "$herdr_bin.part"
  [ "$(sha256sum "$herdr_bin.part" | cut -d' ' -f1)" = "$herdr_sum" ] || { echo "herdr's checksum didn't match"; exit 1; }
  chmod +x "$herdr_bin.part"
  mv "$herdr_bin.part" "$herdr_bin"
fi
ln -sf "$herdr_bin" "$HOME/.local/bin/herdr"
find "$HOME/.local/share/herdr" -maxdepth 1 -type f -name 'herdr-[0-9]*' ! -name "herdr-$herdr_version" -delete
herdr --version

echo "== The bridge"
export PATH="$HOME/.local/bin:$PATH"
python3 "$HOME/ygg-bridge/ygg_bridge.py" key >/dev/null
echo "== Ready"
