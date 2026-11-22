#!/usr/bin/env bash
# Installs Ygg's Claude bridge in UserLAnd's Ubuntu (or Debian).
#
# UserLAnd runs Linux under proot, sharing the phone's network: the bridge on
# 127.0.0.1:8765 is the phone's 127.0.0.1:8765, where Ygg reaches it. It runs
# while UserLAnd's session does; opening the session starts it.
#
# Run from the folder holding ygg_bridge.py and setup.sh. Safe to run again.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
say() { printf '\n\033[36m== %s\033[0m\n' "$*"; }

say "The bridge and Claude Code"
mkdir -p "$HOME/ygg-bridge"
cp "$here/ygg_bridge.py" "$HOME/ygg-bridge/ygg_bridge.py"
bash "$here/setup.sh"

mkdir -p "$HOME/.local/bin"
cat > "$HOME/.local/bin/ygg-bridge" <<'LAUNCHER'
#!/usr/bin/env bash
# ygg-bridge start | stop | status | login [account] | accounts | key | github <token> | log
export PATH="$HOME/.local/bin:$PATH" DISABLE_AUTOUPDATER=1
log="$HOME/.ygg-bridge.log"
running() { pgrep -f "ygg_bridge.py serve" >/dev/null; }
case "${1:-status}" in
  start)
    if running; then echo "Already running."; exit 0; fi
    nohup python3 "$HOME/ygg-bridge/ygg_bridge.py" serve >"$log" 2>&1 &
    sleep 2
    running && echo "Started on 127.0.0.1:8765." || { echo "It didn't start:"; tail -5 "$log"; exit 1; }
    ;;
  stop) pkill -f "ygg_bridge.py serve" && echo "Stopped." || echo "Wasn't running." ;;
  status)
    running && echo "Running." || echo "Not running: ygg-bridge start"
    curl -s http://127.0.0.1:8765/health || true; echo
    ;;
  login) script -qfec "python3 $HOME/ygg-bridge/ygg_bridge.py login ${2:-default}" /dev/null ;;
  accounts) python3 "$HOME/ygg-bridge/ygg_bridge.py" accounts ;;
  key) python3 "$HOME/ygg-bridge/ygg_bridge.py" key ;;
  github) python3 "$HOME/ygg-bridge/ygg_bridge.py" github "${2:?ygg-bridge github <token>}" ;;
  log) tail -n 40 "$log" ;;
  *) echo "ygg-bridge start | stop | status | login [account] | accounts | key | github <token> | log"; exit 2 ;;
esac
LAUNCHER
chmod +x "$HOME/.local/bin/ygg-bridge"
grep -q 'ygg-bridge start' "$HOME/.bashrc" 2>/dev/null || cat >> "$HOME/.bashrc" <<'RC'
export PATH="$HOME/.local/bin:$PATH"
# Ygg's Claude bridge starts with the session.
ygg-bridge start >/dev/null 2>&1 || true
RC
"$HOME/.local/bin/ygg-bridge" stop >/dev/null 2>&1 || true
"$HOME/.local/bin/ygg-bridge" start

say "Done. Two more steps"
cat <<'NEXT'
1. Sign Claude Code in (once):    ygg-bridge login
   Open the link it prints, sign in, paste the code back.
2. Give Ygg the bridge's key:     ygg-bridge key
   Copy it into Ygg: Settings → Claude bridge → Key.
For private repos: ygg-bridge github <token>
It runs while UserLAnd's session does; opening the session starts it.
NEXT
echo "Key: $(python3 "$HOME/ygg-bridge/ygg_bridge.py" key)"
