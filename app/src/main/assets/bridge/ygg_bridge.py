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
# Start from a stub with no files first (YGG_BRIDGE_STUB=0: always a full checkout).
STUB = os.environ.get("YGG_BRIDGE_STUB", "1") != "0"

CLOUD_URL = re.compile(r"https://claude\.ai/code/(session_[A-Za-z0-9_-]+)")
REPO = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
ANSI = re.compile(r"\x1b\[[0-9;?<>=]*[A-Za-z~]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\x1b[()][A-Za-z0-9]|\x1b[78=>]")
# A terminal program moves the cursor right instead of printing spaces.
# Moves and erases (forward, to a column, to a place) stand for spaces.
CURSOR_RIGHT = re.compile(r"\x1b\[[\d;]*[CGHfX]")

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


GITHUB_TOKEN = re.compile(r"^[A-Za-z0-9_]{20,255}$")


def keep_github(token: str) -> None:
    """Keeps the GitHub token for cloning private repos; an empty one forgets it."""
    token = token.strip()
    path = home() / "github"
    if not token:
        path.unlink(missing_ok=True)
        return
    if not GITHUB_TOKEN.match(token):
        raise Refused(400, "that doesn't look like a GitHub token")
    path.write_text(token + "\n")
    os.chmod(path, 0o600)


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


def reason(text: str) -> str:
    """The line that says why, from a terminal program's output: its first
    error line, else its last lines. Cursor moves and bare control bytes go."""
    clean = re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", "", ANSI.sub("", CURSOR_RIGHT.sub(" ", text)))
    clean = re.sub(r"[ \t]{2,}", " ", clean)
    lines = [l.strip() for l in clean.replace("\r", "\n").splitlines() if l.strip()]
    for i, l in enumerate(lines):
        if re.match(r"(Error\b|error:|Not uploading|Cloud agents require)", l):
            return " ".join(lines[i:i + 4])[:600]
    return " / ".join(lines[-3:])[:400]


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
    # Git saves objects by hard-linking a temp file into place. Under proot's
    # --link2symlink (Ygg's own Linux) each becomes a symlink, and
    # `claude --cloud` won't upload a tree whose .git/objects holds links. Renaming
    # into place makes plain files. Only for the bridge's own git runs.
    extra = ["-c", "core.createObject=rename", *extra]
    out = subprocess.run(["git", *extra, *args], cwd=cwd, capture_output=True, text=True, timeout=timeout, env=env)
    if out.returncode != 0:
        why = last_lines(out.stderr or out.stdout)
        if "could not read Username" in why or "Authentication failed" in why or "Repository not found" in why:
            why = ("GitHub refused it (private repo or a token without access to it). "
                   + ("Give the bridge a token that can read it. " if token else "The bridge has no GitHub token: add one in Settings > Claude bridge. ")
                   + why)
        raise Refused(502, "git " + args[0] + " failed: " + why)
    return out.stdout


def plain_git_files(path: Path) -> None:
    """`claude --cloud` refuses a checkout with a link or a file with a second
    name anywhere git keeps its own files (.git/index, .git/shallow, .git/objects,
    ...): git never makes them so. Under proot's --link2symlink they can be
    (hard links become symlinks to hidden .l2s.* files). Each such file becomes a
    plain one with the same bytes, and the .l2s.* leftovers go."""
    gitdir = path / ".git"
    if not gitdir.is_dir():
        return
    leftovers = []
    for root, dirs, files in os.walk(gitdir):
        for name in files + [d for d in dirs if (Path(root) / d).is_symlink()]:
            f = Path(root) / name
            if name.startswith(".l2s."):
                leftovers.append(f)
                continue
            try:
                st = f.lstat()
            except OSError:
                continue
            if not f.is_symlink() and st.st_nlink == 1:
                continue
            if f.is_symlink() and not f.is_file():
                f.unlink()  # A link to nothing, or to a folder: git made neither.
                continue
            data = f.read_bytes()  # Through the link, if it is one.
            mode = f.stat().st_mode & 0o777
            tmp = f.with_name(f.name + ".ygg-plain")
            tmp.write_bytes(data)
            os.chmod(tmp, mode)
            f.unlink()
            tmp.rename(f)
    for f in leftovers:
        try:
            f.unlink()
        except OSError:
            pass


def fresh_clone(url: str, path: Path, branch: str) -> None:
    """A full clone: no shallow list for `claude --cloud` to object to, and the
    history it needs to work out where your branch stands."""
    tmp = path.with_name(path.name + ".ygg-new")
    shutil.rmtree(tmp, ignore_errors=True)
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        git(["clone", *(["--branch", branch] if branch else []), url, str(tmp)])
    except Refused:
        shutil.rmtree(tmp, ignore_errors=True)
        raise
    shutil.rmtree(path, ignore_errors=True)
    tmp.rename(path)


def default_branch(url: str) -> str:
    out = git(["ls-remote", "--symref", url, "HEAD"])
    m = re.search(r"^ref: refs/heads/(\S+)\s+HEAD", out, re.M)
    if not m:
        raise Refused(502, "couldn't tell the repo's default branch")
    return m.group(1)


def stub(repo: str, branch: str = "") -> Path:
    """The least of a checkout that tells `claude --cloud` the repo and branch:
    a git folder with origin set, the branch's newest commit and its trees but
    no file contents (--depth 1 --filter=blob:none), and no files checked out
    (a sparse checkout that matches nothing, so git sees no changes). A few
    hundred KB where a clone is the whole repo. Made afresh each time."""
    if not REPO.match(repo):
        raise Refused(400, "a repo is owner/name, like SebbeJohansson/yggdrasil")
    url = f"{GIT_BASE}/{repo}.git"
    branch = branch or default_branch(url)
    path = home() / "stubs" / repo
    shutil.rmtree(path, ignore_errors=True)
    path.mkdir(parents=True)
    git(["init", "-q", "-b", branch, str(path)])
    git(["remote", "add", "origin", url], cwd=path)
    git(["config", "remote.origin.promisor", "true"], cwd=path)
    git(["config", "remote.origin.partialclonefilter", "blob:none"], cwd=path)
    git(["sparse-checkout", "set", "--no-cone", "/.ygg-stub-nothing"], cwd=path)
    git(["fetch", "-q", "--depth", "1", "--filter=blob:none", "origin",
         f"+refs/heads/{branch}:refs/remotes/origin/{branch}"], cwd=path)
    git(["checkout", "-q", "-B", branch, "--track", f"origin/{branch}"], cwd=path)
    plain_git_files(path)
    return path


def checkout(repo: str, branch: str = "") -> Path:
    """A checkout of the repo at the branch (its default when none), as GitHub
    has it: `claude --cloud` starts the session from the GitHub remote at the
    current branch. Kept between sessions; when it can't be brought up to
    date (or is an old shallow one), it's cloned again."""
    if not REPO.match(repo):
        raise Refused(400, "a repo is owner/name, like SebbeJohansson/yggdrasil")
    path = repo_dir(repo)
    url = f"{GIT_BASE}/{repo}.git"
    if (path / ".git").is_dir() and not (path / ".git" / "shallow").exists():
        try:
            git(["fetch", "--prune", "origin"], cwd=path)
            if not branch:
                git(["remote", "set-head", "origin", "--auto"], cwd=path)
                head = git(["symbolic-ref", "--short", "refs/remotes/origin/HEAD"], cwd=path).strip()
                branch_here = head.split("/", 1)[1]
            else:
                branch_here = branch
            git(["checkout", "-f", "-B", branch_here, f"origin/{branch_here}"], cwd=path)
            git(["clean", "-fdq"], cwd=path)
        except Refused as e:
            if "GitHub refused" in str(e):
                raise
            fresh_clone(url, path, branch)
    else:
        fresh_clone(url, path, branch)
    plain_git_files(path)
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

def claude_config() -> Path:
    return Path(os.environ.get("CLAUDE_CONFIG_DIR") or Path.home()) / ".claude.json"


def prepare_claude(cwd: Path) -> None:
    """Answers ahead what an interactive `claude` asks on its first run and in a
    new folder: the theme, and whether you trust the folder. Nobody is there to
    pick, so unanswered it waits until the bridge gives up. The folder is a
    checkout of your own repo that the bridge made."""
    path = claude_config()
    with lock:
        try:
            data = json.loads(path.read_text())
        except (OSError, ValueError):
            data = {}
        data.setdefault("theme", "dark")
        data["hasCompletedOnboarding"] = True
        project = data.setdefault("projects", {}).setdefault(str(cwd), {})
        project["hasTrustDialogAccepted"] = True
        tmp = path.with_name(path.name + ".ygg-tmp")
        tmp.write_text(json.dumps(data, indent=2))
        os.chmod(tmp, 0o600)
        tmp.replace(path)

def run_cloud(cwd: Path, task: str):
    """`claude --cloud <task>` in [cwd], under util-linux's `script` (it wants a
    terminal). Gives (url, id) once it prints the session's link, else
    (None, all it printed)."""
    prepare_claude(cwd)
    command = f"{shlex.quote(CLAUDE)} --cloud {shlex.quote(task)}"
    argv = ["script", "-qfec", command, "/dev/null"] if shutil.which("script") else [CLAUDE, "--cloud", task]
    # claude's own git runs, too, save objects by renaming, not hard-linking (see git()).
    n = int(os.environ.get("GIT_CONFIG_COUNT", "0") or 0)
    env = dict(os.environ, GIT_CONFIG_COUNT=str(n + 1), **{f"GIT_CONFIG_KEY_{n}": "core.createObject", f"GIT_CONFIG_VALUE_{n}": "rename"})
    proc = subprocess.Popen(argv, cwd=cwd, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    seen = []
    deadline = time.time() + START_WAIT
    timer = threading.Timer(START_WAIT, proc.kill)
    timer.start()
    try:
        for raw in iter(proc.stdout.readline, b""):
            text = raw.decode(errors="replace")
            seen.append(text)
            m = CLOUD_URL.search(ANSI.sub("", text))
            if m:
                return (m.group(0), m.group(1)), ""
            if time.time() > deadline:
                break
    finally:
        timer.cancel()
        proc.kill()
        proc.wait()
        proc.stdin.close()
        proc.stdout.close()
    return None, "".join(seen)


# What claude says when it won't take a working tree as it is, rather than
# for a reason no checkout would change (sign-in, network, limits).
REFUSED_TREE = re.compile(r"Not uploading|working tree|shallow|bundle|fresh clone|retry there", re.I)


def start_cloud(repo: str, task: str, branch: str = "") -> dict:
    """Starts a cloud session for the repo. `claude --cloud` takes the repo and
    branch from the folder it runs in, so it needs a checkout to stand in:
    first a stub with no files ([stub]), and when claude won't take that, a
    full checkout ([checkout])."""
    need_claude()
    task = task.strip()
    if not task:
        raise Refused(400, "the task is empty")
    if not REPO.match(repo):
        raise Refused(400, "a repo is owner/name, like SebbeJohansson/yggdrasil")
    found, out = (None, "")
    tried_stub = STUB
    if STUB:
        # A git error here (no such repo, no access, no network) stands: a full clone would meet it too.
        found, out = run_cloud(stub(repo, branch), task)
    if not found and (not tried_stub or REFUSED_TREE.search(reason(out))):
        found, out = run_cloud(checkout(repo, branch), task)
    if not found:
        try:
            # Readable first, then all of it as printed, for when the reading is wrong.
            (home() / "last-start.log").write_text(reason(out) + "\n\n--- as printed ---\n" + repr(out))
        except OSError:
            pass
        raise Refused(502, "claude --cloud made no session: " + reason(out) + " (all of it: ~/.ygg-bridge/last-start.log)")
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
                keep_github(str(self.body().get("token", "")))
                return self.answer(200, {"ok": True, "github": bool(secret("github"))})
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
        try:
            keep_github(argv[2])
        except Refused as e:
            print(e)
            return 2
        print("GitHub token kept for cloning.")
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
