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

echo "== The bridge"
export PATH="$HOME/.local/bin:$PATH"
python3 "$HOME/ygg-bridge/ygg_bridge.py" key >/dev/null
echo "== Ready"
