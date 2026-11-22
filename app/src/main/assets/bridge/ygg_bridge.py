#!/usr/bin/env python3
"""Ygg's Claude bridge: the real `claude` CLI, on this phone, for the Ygg app.

Runs in Android's Linux Terminal (a Debian VM; install-debian.sh) or in
Termux's Ubuntu (install.sh), where Claude Code is supported. The app asks it to:

  - start a Claude Code cloud session for a repo   `claude --cloud "<task>"`
  - send a follow-up to any cloud session          `claude -p "<text>" --cloud <id>`
  - answer one question for Ygg's Claude provider  `claude -p`

Every call but GET /health carries the bridge's key (Authorization: Bearer),
so other apps on the phone can't use your Claude account through it.

    python3 ygg_bridge.py serve        run it (Termux keeps it going)
    python3 ygg_bridge.py key          print the key to paste into Ygg
    python3 ygg_bridge.py github TOKEN keep a GitHub token for cloning private repos

Standard library only. Kept in ~/.ygg-bridge: the key, the GitHub token, the
sessions it started, and shallow checkouts of the repos you start sessions for.
"""

import json
import os
import re
import secrets
import shlex
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

VERSION = "1"
HOME = Path(os.environ.get("YGG_BRIDGE_HOME", Path.home() / ".ygg-bridge"))
PORT = int(os.environ.get("YGG_BRIDGE_PORT", "8765"))
# 127.0.0.1 in Termux (it shares the phone's network). In Android's Linux
# Terminal the bridge is in a VM, and the Terminal's port forwarding brings
# 8765 to the phone; there it listens on the VM's own addresses, which only
# the phone can reach, and the key guards it as everywhere.
HOST = os.environ.get("YGG_BRIDGE_HOST", "127.0.0.1")
CLAUDE = os.environ.get("CLAUDE_BIN", "claude")
START_WAIT = int(os.environ.get("YGG_BRIDGE_START_WAIT", "180"))
# Where repos are cloned from; a test points it at local ones.
GIT_BASE = os.environ.get("YGG_BRIDGE_GIT_BASE", "https://github.com")

CLOUD_URL = re.compile(r"https://claude\.ai/code/(session_[A-Za-z0-9_-]+)")
REPO = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
ANSI = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]|\x1b\][^\x07]*\x07")

lock = threading.Lock()


def home() -> Path:
    HOME.mkdir(parents=True, exist_ok=True)
    os.chmod(HOME, 0o700)
    return HOME


def secret(name: str, make: bool = False) -> str:
    path = home() / name
    if path.exists():
        return path.read_text().strip()
    if not make:
        return ""
    value = secrets.token_urlsafe(24)
    path.write_text(value + "\n")
    os.chmod(path, 0o600)
    return value


def key() -> str:
    return secret("key", make=True)


def state() -> dict:
    path = home() / "sessions.json"
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        return {"sessions": []}


def save(data: dict) -> None:
    path = home() / "sessions.json"
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=1))
    tmp.replace(path)


def last_lines(text: str, n: int = 3) -> str:
    lines = [l.strip() for l in ANSI.sub("", text).splitlines() if l.strip()]
    return " / ".join(lines[-n:])[:400]


class Refused(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status


def claude_version() -> str:
    if not shutil.which(CLAUDE):
        return ""
    try:
        out = subprocess.run([CLAUDE, "--version"], capture_output=True, text=True, timeout=20)
        return out.stdout.strip() or out.stderr.strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def need_claude() -> None:
    if not shutil.which(CLAUDE):
        raise Refused(503, "claude isn't installed here: run the installer again")


# Repos ---------------------------------------------------------------------

def repo_dir(repo: str) -> Path:
    return home() / "repos" / repo


def git(args: list, cwd: Path = None, timeout: int = 300) -> str:
    env = dict(os.environ, GIT_TERMINAL_PROMPT="0")
    token = secret("github")
    extra = []
    if token:
        # The token goes in a header for github.com only, never into the checkout's config.
        import base64
        basic = base64.b64encode(f"x-access-token:{token}".encode()).decode()
        extra = ["-c", f"http.https://github.com/.extraheader=Authorization: Basic {basic}"]
    out = subprocess.run(["git", *extra, *args], cwd=cwd, capture_output=True, text=True, timeout=timeout, env=env)
    if out.returncode != 0:
        text = out.stderr or out.stdout
        if "could not read Username" in text or "Authentication failed" in text or "Repository not found" in text:
            if secret("github"):
                raise Refused(403, "GitHub refused the token for this repo: it needs Contents: read on it (or the repo name is wrong)")
            raise Refused(403, "this repo is private (or doesn't exist): add a GitHub token that can read it, in Ygg: Settings → Claude bridge")
        raise Refused(502, "git " + args[0] + " failed: " + last_lines(text))
    return out.stdout


def checkout(repo: str, branch: str = "") -> Path:
    """A shallow checkout of the repo at the branch (its default when none):
    `claude --cloud` clones the GitHub remote at the current branch."""
    if not REPO.match(repo):
        raise Refused(400, "a repo is owner/name, like SebbeJohansson/yggdrasil")
    path = repo_dir(repo)
    url = f"{GIT_BASE}/{repo}.git"
    if not (path / ".git").exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        git(["clone", "--depth", "1", *(["--branch", branch] if branch else []), url, str(path)])
        return path
    if branch:
        git(["fetch", "--depth", "1", "origin", f"{branch}:refs/remotes/origin/{branch}"], cwd=path)
        git(["checkout", "-B", branch, f"origin/{branch}"], cwd=path)
    else:
        git(["pull", "--ff-only", "--depth", "1"], cwd=path)
    return path


def repos() -> list:
    root = home() / "repos"
    found = []
    if root.exists():
        for owner in sorted(root.iterdir()):
            for name in sorted(owner.iterdir()) if owner.is_dir() else []:
                if (name / ".git").exists():
                    branch = subprocess.run(["git", "branch", "--show-current"], cwd=name, capture_output=True, text=True).stdout.strip()
                    found.append({"repo": f"{owner.name}/{name.name}", "branch": branch})
    return found


# Cloud sessions --------------------------------------------------------------

def start_cloud(repo: str, task: str, branch: str = "") -> dict:
    """`claude --cloud` in the repo. It wants a terminal, so util-linux's
    `script` gives it one; the session exists once its link is printed."""
    need_claude()
    task = task.strip()
    if not task:
        raise Refused(400, "the task is empty")
    cwd = checkout(repo, branch)
    command = f"{shlex.quote(CLAUDE)} --cloud {shlex.quote(task)}"
    argv = ["script", "-qfec", command, "/dev/null"] if shutil.which("script") else [CLAUDE, "--cloud", task]
    proc = subprocess.Popen(argv, cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    seen = []
    found = None
    deadline = time.time() + START_WAIT
    timer = threading.Timer(START_WAIT, proc.kill)
    timer.start()
    try:
        for raw in iter(proc.stdout.readline, b""):
            line = ANSI.sub("", raw.decode(errors="replace"))
            seen.append(line)
            m = CLOUD_URL.search(line)
            if m:
                found = (m.group(0), m.group(1))
                break
            if time.time() > deadline:
                break
    finally:
        timer.cancel()
        proc.kill()
        proc.wait()
        proc.stdin.close()
        proc.stdout.close()
    if not found:
        raise Refused(502, "claude --cloud printed no session link: " + last_lines("".join(seen)))
    url, sid = found
    session = {"id": sid, "url": url, "repo": repo, "branch": branch, "task": task, "at": int(time.time() * 1000)}
    with lock:
        data = state()
        data["sessions"] = [session] + [s for s in data["sessions"] if s["id"] != sid]
        data["sessions"] = data["sessions"][:100]
        save(data)
    return session


def message_cloud(sid: str, text: str) -> dict:
    """`claude -p "<text>" --cloud <id> --output-format json`: queues the
    message in any cloud session of your account and returns at once."""
    need_claude()
    text = text.strip()
    if not text:
        raise Refused(400, "the message is empty")
    if not re.match(r"^(session_|cse_)[A-Za-z0-9_-]+$", sid):
        raise Refused(400, "that isn't a cloud session id")
    with tempfile.TemporaryDirectory() as empty:
        out = subprocess.run([CLAUDE, "-p", text, "--cloud", sid, "--output-format", "json"], cwd=empty,
                             capture_output=True, text=True, timeout=90)
    try:
        sent = json.loads(out.stdout)
    except ValueError:
        raise Refused(502, "claude answered something other than JSON: " + last_lines(out.stderr + out.stdout))
    now = int(time.time() * 1000)
    with lock:
        data = state()
        for s in data["sessions"]:
            if s["id"] == sid:
                s["lastSentAt"] = now
                s["lastError"] = "" if sent.get("ok") else sent.get("error", "")
        save(data)
    if not sent.get("ok"):
        raise Refused(502, "the cloud session didn't take it: " + str(sent.get("error", "")))
    return {"ok": True, "id": sid, "url": sent.get("url", f"https://claude.ai/code/{sid}")}


# Ygg's Claude provider --------------------------------------------------------

def complete(body: dict) -> dict:
    """One answer for Ygg's Claude provider (docs/claude-bridge.md): the
    messages as one prompt, `claude -p`, no tools, in an empty folder."""
    need_claude()
    messages = body.get("messages") or []
    if not messages or messages[-1].get("role") != "user":
        raise Refused(400, "the last message must be the user's")
    if len(messages) == 1:
        prompt = messages[0].get("text", "")
    else:
        prompt = "\n\n".join(("User: " if m.get("role") == "user" else "Assistant: ") + m.get("text", "") for m in messages)
        prompt += "\n\nAnswer the user's last message."
    argv = [CLAUDE, "-p", prompt, "--output-format", "json", "--tools", ""]
    if body.get("system"):
        argv += ["--append-system-prompt", body["system"]]
    with tempfile.TemporaryDirectory() as empty:
        out = subprocess.run(argv, cwd=empty, capture_output=True, text=True, timeout=300)
        if out.returncode != 0 and "--tools" in (out.stderr + out.stdout):
            # An older claude without --tools: ask again without it (print mode still asks before any tool).
            argv = [a for i, a in enumerate(argv) if a != "--tools" and not (i > 0 and argv[i - 1] == "--tools")]
            out = subprocess.run(argv, cwd=empty, capture_output=True, text=True, timeout=300)
    try:
        result = json.loads(out.stdout)
    except ValueError:
        raise Refused(502, "claude failed: " + last_lines(out.stderr + out.stdout))
    if result.get("is_error"):
        raise Refused(502, "claude: " + str(result.get("result", "error"))[:300])
    return {"text": result.get("result", "")}


# HTTP -----------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    server_version = "ygg-bridge/" + VERSION

    def log_message(self, fmt, *args):  # Quiet: nothing you send is logged.
        pass

    def answer(self, status: int, body: dict) -> None:
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def allowed(self) -> bool:
        given = self.headers.get("Authorization", "")
        if secrets.compare_digest(given, "Bearer " + key()):
            return True
        self.answer(401, {"error": "the bridge's key is missing or wrong: paste it into Ygg (python3 ygg_bridge.py key)"})
        return False

    def body(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        if length > 1_000_000:
            raise Refused(413, "too big")
        try:
            return json.loads(self.rfile.read(length) or b"{}")
        except ValueError:
            raise Refused(400, "not JSON")

    def route(self, method: str) -> None:
        path = self.path.split("?")[0].rstrip("/")
        try:
            if method == "GET" and path == "/health":
                version = claude_version()
                return self.answer(200 if version else 503, {"ok": bool(version), "bridge": VERSION, "claude": version,
                                                             "github": bool(secret("github"))})
            if not self.allowed():
                return
            if method == "GET" and path == "/v1/cloud":
                return self.answer(200, {"sessions": state()["sessions"]})
            if method == "GET" and path == "/v1/repos":
                return self.answer(200, {"repos": repos()})
            if method == "POST" and path == "/v1/cloud":
                b = self.body()
                return self.answer(200, start_cloud(str(b.get("repo", "")), str(b.get("task", "")), str(b.get("branch") or "")))
            m = re.match(r"^/v1/cloud/([A-Za-z0-9_-]+)/message$", path)
            if method == "POST" and m:
                return self.answer(200, message_cloud(m.group(1), str(self.body().get("text", ""))))
            if method == "POST" and path == "/v1/github":
                token = str(self.body().get("token", "")).strip()
                path_ = home() / "github"
                if not token:
                    path_.unlink(missing_ok=True)
                    return self.answer(200, {"github": False})
                if not re.match(r"^(github_pat_|gh[pousr]_)[A-Za-z0-9_]{20,}$", token):
                    raise Refused(400, "that doesn't look like a GitHub token (github_pat_… or ghp_…)")
                path_.write_text(token + "\n")
                os.chmod(path_, 0o600)
                return self.answer(200, {"github": True})
            if method == "POST" and path == "/v1/complete":
                return self.answer(200, complete(self.body()))
            return self.answer(404, {"error": "no such thing"})
        except Refused as e:
            return self.answer(e.status, {"error": str(e)})
        except subprocess.TimeoutExpired:
            return self.answer(504, {"error": "claude took too long"})
        except Exception as e:  # The app shows the reason; the bridge keeps going.
            return self.answer(500, {"error": f"{type(e).__name__}: {e}"})

    def do_GET(self):
        self.route("GET")

    def do_POST(self):
        self.route("POST")


def serve() -> None:
    key()
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"Ygg's Claude bridge on {HOST}:{PORT} (claude: {claude_version() or 'not installed'})", flush=True)
    server.serve_forever()


def main(argv: list) -> int:
    command = argv[1] if len(argv) > 1 else "serve"
    if command == "serve":
        serve()
    elif command == "key":
        print(key())
    elif command == "github" and len(argv) > 2:
        path = home() / "github"
        path.write_text(argv[2].strip() + "\n")
        os.chmod(path, 0o600)
        print("GitHub token kept for cloning.")
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
