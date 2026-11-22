#!/data/data/com.termux/files/usr/bin/bash
# Installs Ygg's Claude bridge in Termux.
#
# Claude Code supports Ubuntu, not Android itself, so the bridge and the
# `claude` CLI live in an Ubuntu inside Termux (proot-distro). It shares the
# phone's network, so the Ygg app reaches it on 127.0.0.1:8765.
#
# Run from the folder holding ygg_bridge.py and setup.sh. Safe to run again: it updates.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
distro=ubuntu
rootfs="$PREFIX/var/lib/proot-distro/installed-rootfs/$distro"

say() { printf '\n\033[36m== %s\033[0m\n' "$*"; }

say "Termux packages"
pkg update -y
pkg install -y proot-distro

if [ ! -d "$rootfs" ]; then
  say "Ubuntu (once, a few hundred MB)"
  proot-distro install "$distro"
fi

say "The bridge and Claude Code, inside Ubuntu"
mkdir -p "$rootfs/root/ygg-bridge"
cp "$here/ygg_bridge.py" "$rootfs/root/ygg-bridge/ygg_bridge.py"
cp "$here/setup.sh" "$rootfs/root/ygg-setup.sh"
proot-distro login "$distro" -- env HOME=/root PATH="/root/.local/bin:/usr/local/bin:/usr/bin:/bin" bash /root/ygg-setup.sh

mkdir -p "$HOME/bin"
cat > "$HOME/bin/ygg-bridge" <<'LAUNCHER'
#!/data/data/com.termux/files/usr/bin/bash
# ygg-bridge start | stop | status | login | key | github <token> | log
distro=ubuntu
log="$HOME/.ygg-bridge.log"
inside() { proot-distro login "$distro" -- env DISABLE_AUTOUPDATER=1 PATH="/root/.local/bin:/usr/local/bin:/usr/bin:/bin" "$@"; }
running() { pgrep -f "ygg_bridge.py serve" >/dev/null; }
case "${1:-status}" in
  start)
    if running; then echo "Already running."; exit 0; fi
    # Keeps Android from pausing Termux while the bridge works.
    command -v termux-wake-lock >/dev/null && termux-wake-lock
    nohup proot-distro login "$distro" -- env DISABLE_AUTOUPDATER=1 PATH="/root/.local/bin:/usr/local/bin:/usr/bin:/bin" \
      python3 /root/ygg-bridge/ygg_bridge.py serve >"$log" 2>&1 &
    sleep 2
    running && echo "Started on 127.0.0.1:8765." || { echo "It didn't start:"; tail -5 "$log"; exit 1; }
    ;;
  stop)
    pkill -f "ygg_bridge.py serve" && echo "Stopped." || echo "Wasn't running."
    command -v termux-wake-unlock >/dev/null && termux-wake-unlock
    ;;
  status)
    running && echo "Running." || echo "Not running: ygg-bridge start"
    curl -s http://127.0.0.1:8765/health || true; echo
    ;;
  login) inside script -qfec "claude auth login" /dev/null ;;
  key) inside python3 /root/ygg-bridge/ygg_bridge.py key ;;
  github) inside python3 /root/ygg-bridge/ygg_bridge.py github "${2:?ygg-bridge github <token>}" ;;
  log) tail -n 40 "$log" ;;
  *) echo "ygg-bridge start | stop | status | login | key | github <token> | log"; exit 2 ;;
esac
LAUNCHER
chmod +x "$HOME/bin/ygg-bridge"
grep -q 'HOME/bin' "$HOME/.bashrc" 2>/dev/null || echo 'export PATH="$HOME/bin:$PATH"' >> "$HOME/.bashrc"

# With the Termux:Boot app installed and opened once, it starts with the phone.
mkdir -p "$HOME/.termux/boot"
cat > "$HOME/.termux/boot/ygg-bridge" <<'BOOT'
#!/data/data/com.termux/files/usr/bin/bash
"$HOME/bin/ygg-bridge" start
BOOT
chmod +x "$HOME/.termux/boot/ygg-bridge"

"$HOME/bin/ygg-bridge" stop >/dev/null 2>&1 || true
"$HOME/bin/ygg-bridge" start

say "Done. Three more steps"
cat <<'NEXT'
1. Sign Claude Code in (once):        ygg-bridge login
   Open the link it prints, sign in, paste the code back.
2. Give Ygg the bridge's key:         ygg-bridge key
   Copy it into Ygg: Settings → Claude bridge → Key.
3. For private repos, a GitHub token that can read them:
                                      ygg-bridge github <token>

Restart it any time with: ygg-bridge start   (status, stop, log)
NEXT
"$HOME/bin/ygg-bridge" key | sed 's/^/Key: /'
