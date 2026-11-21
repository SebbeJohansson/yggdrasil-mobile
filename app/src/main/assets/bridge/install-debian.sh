#!/usr/bin/env bash
# Installs Ygg's Claude bridge in Android's Linux Terminal: the Debian VM
# Android runs (Settings → System → Developer options → Linux development
# environment). Claude Code supports Debian as it is.
#
# The bridge runs as a system service, so it starts each time the Terminal
# starts the VM and comes back if it stops. The Terminal's port forwarding
# brings port 8765 to the phone, where Ygg reaches it on 127.0.0.1.
#
# Run from the folder holding ygg_bridge.py. Safe to run again: it updates.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
say() { printf '\n\033[36m== %s\033[0m\n' "$*"; }

say "Packages: python, git, script"
sudo apt-get update -y
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y python3 git curl ca-certificates bsdutils

say "Claude Code"
export PATH="$HOME/.local/bin:$PATH"
if ! command -v claude >/dev/null; then
  curl -fsSL https://claude.ai/install.sh | bash
fi
claude --version

say "The bridge"
mkdir -p "$HOME/ygg-bridge"
cp "$here/ygg_bridge.py" "$HOME/ygg-bridge/ygg_bridge.py"

sudo tee /etc/systemd/system/ygg-bridge.service >/dev/null <<UNIT
[Unit]
Description=Ygg's Claude bridge
After=network-online.target

[Service]
User=$USER
Environment=HOME=$HOME
Environment=PATH=$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin
Environment=YGG_BRIDGE_HOST=0.0.0.0
ExecStart=/usr/bin/python3 $HOME/ygg-bridge/ygg_bridge.py serve
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
UNIT

mkdir -p "$HOME/.local/bin"
cat > "$HOME/.local/bin/ygg-bridge" <<'LAUNCHER'
#!/usr/bin/env bash
# ygg-bridge start | stop | restart | status | login | key | github <token> | log
export PATH="$HOME/.local/bin:$PATH"
case "${1:-status}" in
  start|stop|restart) sudo systemctl "$1" ygg-bridge && echo "${1^}ed." ;;
  status)
    systemctl is-active --quiet ygg-bridge && echo "Running." || echo "Not running: ygg-bridge start"
    curl -s http://127.0.0.1:8765/health || true; echo
    ;;
  login) claude auth login ;;
  key) python3 "$HOME/ygg-bridge/ygg_bridge.py" key ;;
  github) python3 "$HOME/ygg-bridge/ygg_bridge.py" github "${2:?ygg-bridge github <token>}" ;;
  log) journalctl -u ygg-bridge -n 40 --no-pager ;;
  *) echo "ygg-bridge start | stop | restart | status | login | key | github <token> | log"; exit 2 ;;
esac
LAUNCHER
chmod +x "$HOME/.local/bin/ygg-bridge"
grep -q '.local/bin' "$HOME/.bashrc" 2>/dev/null || echo 'export PATH="$HOME/.local/bin:$PATH"' >> "$HOME/.bashrc"

# The key first, so the service and you see the same one.
python3 "$HOME/ygg-bridge/ygg_bridge.py" key >/dev/null
sudo systemctl daemon-reload
sudo systemctl enable ygg-bridge >/dev/null
sudo systemctl restart ygg-bridge
sleep 2
systemctl is-active --quiet ygg-bridge || { echo "It didn't start:"; journalctl -u ygg-bridge -n 10 --no-pager; exit 1; }

say "Done. Three more steps"
cat <<'NEXT'
1. Let Ygg reach it: when the Terminal asks to open port 8765, allow it
   (or the Terminal's settings → Port control → 8765 on).
2. Sign Claude Code in (once):        ygg-bridge login
   Open the link it prints, sign in, paste the code back.
3. Give Ygg the bridge's key:         ygg-bridge key
   Copy it into Ygg: Settings → Claude bridge → Key.
For private repos, a GitHub token that can read them: ygg-bridge github <token>

The bridge runs while the Terminal's Linux is on: keep the Terminal open in
the background. ygg-bridge status | restart | log
NEXT
echo "Key: $(python3 "$HOME/ygg-bridge/ygg_bridge.py" key)"
