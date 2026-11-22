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
    python3 ygg_bridge.py accounts     list the Claude accounts and who is signed in
    python3 ygg_bridge.py login [ID]   sign in to Claude for an account (default: the first)

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

# Accounts --------------------------------------------------------------------
#
# Each Claude account has its own claude config folder (CLAUDE_CONFIG_DIR): its
# sign-in, settings and trust. "default" is claude's own (~/.claude), so the
# sign-in made before accounts existed stays; more live in ~/.ygg-bridge/accounts.

ACCOUNT_ID = re.compile(r"^[a-z0-9][a-z0-9-]{0,31}$")


def accounts() -> list:
    try:
        data = json.loads((home() / "accounts.json").read_text())
    except (OSError, ValueError):
        data = {}
    items = [{"id": a["id"], "name": str(a.get("name") or a["id"])[:40]}
             for a in data.get("accounts", []) if isinstance(a, dict) and ACCOUNT_ID.match(str(a.get("id", "")))]
    if not any(a["id"] == "default" for a in items):
        items.insert(0, {"id": "default", "name": "Personal"})
    return items


def save_accounts(items: list) -> None:
    path = home() / "accounts.json"
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps({"accounts": items}, indent=1))
    tmp.replace(path)


def account(aid: str) -> dict:
    aid = aid or "default"
    for a in accounts():
        if a["id"] == aid:
            return a
    raise Refused(404, f"no account '{aid}'")


def account_dir(aid: str):
    """The account's claude config folder; None for default (claude's own)."""
    aid = account(aid)["id"]
    if aid == "default":
        return None
    d = home() / "accounts" / aid
    d.mkdir(parents=True, exist_ok=True)
    os.chmod(d, 0o700)
    return d


def claude_env(aid: str = "default") -> dict:
    env = dict(os.environ)
    d = account_dir(aid)
    if d:
        env["CLAUDE_CONFIG_DIR"] = str(d)
    return env


def claude_config(aid: str = "default") -> Path:
    d = account_dir(aid)
    return (d or Path(os.environ.get("CLAUDE_CONFIG_DIR") or Path.home())) / ".claude.json"


def credentials(aid: str = "default") -> Path:
    d = account_dir(aid)
    if d:
        return d / ".credentials.json"
    own = os.environ.get("CLAUDE_CONFIG_DIR")
    return Path(own) / ".credentials.json" if own else Path.home() / ".claude" / ".credentials.json"


def account_info(a: dict) -> dict:
    """What the app shows: the name, whether it's signed in, and as whom."""
    creds = credentials(a["id"])
    signed = creds.is_file() and creds.stat().st_size > 2
    email = ""
    try:
        email = json.loads(claude_config(a["id"]).read_text()).get("oauthAccount", {}).get("emailAddress", "")
    except (OSError, ValueError, AttributeError):
        pass
    d = account_dir(a["id"])
    return {"id": a["id"], "name": a["name"], "signedIn": signed, "email": email if signed else "",
            "configDir": str(d) if d else ""}


def add_account(name: str) -> dict:
    name = " ".join(name.split())[:40]
    if not name:
        raise Refused(400, "an account needs a name, like Work")
    items = accounts()
    if any(a["name"].lower() == name.lower() for a in items):
        raise Refused(400, f"there's already an account called {name}")
    base = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")[:24] or "account"
    aid, n = base, 2
    while any(a["id"] == aid for a in items) or aid == "default":
        aid, n = f"{base}-{n}", n + 1
    items.append({"id": aid, "name": name})
    save_accounts(items)
    return account_info(items[-1])


def rename_account(aid: str, name: str) -> dict:
    name = " ".join(name.split())[:40]
    if not name:
        raise Refused(400, "an account needs a name")
    items = accounts()
    for a in items:
        if a["id"] == account(aid)["id"]:
            a["name"] = name
    save_accounts(items)
    return account_info(account(aid))


def remove_account(aid: str) -> None:
    """Forgets the account and its sign-in on this phone. Not default: that is
    claude's own, which other things here use."""
    aid = account(aid)["id"]
    if aid == "default":
        raise Refused(400, "the first account stays; sign it out with claude auth logout instead")
    save_accounts([a for a in accounts() if a["id"] != aid])
    shutil.rmtree(home() / "accounts" / aid, ignore_errors=True)


def prepare_claude(cwd: Path, aid: str = "default") -> None:
    """Answers ahead what an interactive `claude` asks on its first run and in a
    new folder: the theme, and whether you trust the folder. Nobody is there to
    pick, so unanswered it waits until the bridge gives up. The folder is a
    checkout of your own repo that the bridge made."""
    path = claude_config(aid)
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

def run_cloud(cwd: Path, task: str, aid: str = "default"):
    """`claude --cloud <task>` in [cwd], under util-linux's `script` (it wants a
    terminal). Gives (url, id) once it prints the session's link, else
    (None, all it printed)."""
    prepare_claude(cwd, aid)
    command = f"{shlex.quote(CLAUDE)} --cloud {shlex.quote(task)}"
    argv = ["script", "-qfec", command, "/dev/null"] if shutil.which("script") else [CLAUDE, "--cloud", task]
    # claude's own git runs, too, save objects by renaming, not hard-linking (see git()).
    n = int(os.environ.get("GIT_CONFIG_COUNT", "0") or 0)
    env = dict(claude_env(aid), GIT_CONFIG_COUNT=str(n + 1), **{f"GIT_CONFIG_KEY_{n}": "core.createObject", f"GIT_CONFIG_VALUE_{n}": "rename"})
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


def start_cloud(repo: str, task: str, branch: str = "", aid: str = "default") -> dict:
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
    aid = account(aid)["id"]
    found, out = (None, "")
    tried_stub = STUB
    if STUB:
        # A git error here (no such repo, no access, no network) stands: a full clone would meet it too.
        found, out = run_cloud(stub(repo, branch), task, aid)
    if not found and (not tried_stub or REFUSED_TREE.search(reason(out))):
        found, out = run_cloud(checkout(repo, branch), task, aid)
    if not found:
        try:
            # Readable first, then all of it as printed, for when the reading is wrong.
            (home() / "last-start.log").write_text(reason(out) + "\n\n--- as printed ---\n" + repr(out))
        except OSError:
            pass
        raise Refused(502, "claude --cloud made no session: " + reason(out) + " (all of it: ~/.ygg-bridge/last-start.log)")
    url, sid = found
    session = {"id": sid, "url": url, "repo": repo, "branch": branch, "task": task, "account": aid, "at": int(time.time() * 1000)}
    with lock:
        data = state()
        data["sessions"] = [session] + [s for s in data["sessions"] if s["id"] != sid]
        data["sessions"] = data["sessions"][:100]
        save(data)
    return session


def message_cloud(sid: str, text: str, aid: str = "") -> dict:
    """`claude -p "<text>" --cloud <id> --output-format json`: queues the
    message in any cloud session of your account and returns at once."""
    need_claude()
    text = text.strip()
    if not text:
        raise Refused(400, "the message is empty")
    if not re.match(r"^(session_|cse_)[A-Za-z0-9_-]+$", sid):
        raise Refused(400, "that isn't a cloud session id")
    # The account the session is in: as asked, else the one that started it here, else default.
    if not aid:
        aid = next((s.get("account", "") for s in state()["sessions"] if s["id"] == sid), "") or "default"
    env = claude_env(aid)
    with tempfile.TemporaryDirectory() as empty:
        out = subprocess.run([CLAUDE, "-p", text, "--cloud", sid, "--output-format", "json"], cwd=empty,
                             capture_output=True, text=True, timeout=90, env=env)
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


# Local sessions: Claude Code on this phone, in herdr ----------------------------
#
# As on the desktop, Claude Code runs interactively in herdr (https://herdr.dev),
# the terminal multiplexer for coding agents: herdr says whether it works, waits
# at its prompt or shows a dialog, types a prompt as you would, and presses the
# key of a dialog's choice. What Claude answered is read from the transcript it
# writes, not off the screen. One headless herdr server per account, started
# with the account's CLAUDE_CONFIG_DIR, so each Claude in it is that account.

HERDR = os.environ.get("HERDR_BIN", "herdr")
LOCAL_NAME = re.compile(r"^[a-z][a-z0-9_-]{0,31}$")


# Where each account's herdr listens. A unix socket's path must fit in 108
# bytes, and under proot what counts is the path on the phone: herdr's own
# ~/.config/herdr/sessions/<name>/herdr.sock comes to some 117 under Ygg's app
# folder. So a short one in /tmp (herdr puts its client socket beside it).
HERDR_DIR = Path(os.environ.get("YGG_HERDR_DIR", "/tmp"))


def herdr_socket(aid: str) -> Path:
    import hashlib
    return HERDR_DIR / ("yh-" + hashlib.sha1(account(aid)["id"].encode()).hexdigest()[:8] + ".sock")


class HerdrRefused(Refused):
    def __init__(self, code: str, message: str):
        super().__init__(409 if code in ("agent_blocked", "agent_not_ready", "agent_pane_busy") else 502, message)
        self.code = code


def herdr(aid: str, method: str, params: dict = None, timeout: float = 10) -> dict:
    """One request to the account's herdr: a line of JSON each way over its socket."""
    import socket as sock
    s = sock.socket(sock.AF_UNIX)
    s.settimeout(timeout)
    try:
        s.connect(str(herdr_socket(aid)))
    except OSError as e:
        s.close()
        raise Refused(503, f"herdr isn't running for this account ({e})")
    try:
        s.sendall((json.dumps({"id": f"ygg:{secrets.token_hex(4)}", "method": method, "params": params or {}}) + "\n").encode())
        buf = b""
        while not buf.endswith(b"\n"):
            chunk = s.recv(1 << 16)
            if not chunk:
                break
            buf += chunk
    finally:
        s.close()
    reply = json.loads(buf or b"{}")
    if "error" in reply:
        err = reply["error"] or {}
        raise HerdrRefused(str(err.get("code", "")), "herdr: " + str(err.get("message", err)))
    return reply.get("result", {})


def need_herdr(aid: str) -> None:
    """Starts the account's headless herdr server unless it answers already."""
    try:
        herdr(aid, "ping", timeout=3)
        return
    except Refused:
        pass
    if not shutil.which(HERDR):
        raise Refused(503, "herdr isn't installed here: update Ygg's Linux (Settings > Claude bridge > Update)")
    env = claude_env(aid)
    env["HERDR_SOCKET_PATH"] = str(herdr_socket(aid))
    env.setdefault("SHELL", "/bin/bash")
    env.setdefault("TERM", "xterm-256color")
    # Claude in its panes saves git objects by renaming, not hard-linking (see git()).
    n = int(env.get("GIT_CONFIG_COUNT", "0") or 0)
    env.update({"GIT_CONFIG_COUNT": str(n + 1), f"GIT_CONFIG_KEY_{n}": "core.createObject", f"GIT_CONFIG_VALUE_{n}": "rename"})
    log = open(home() / f"herdr-{account(aid)['id']}.log", "ab")
    subprocess.Popen([HERDR, "server"], env=env, stdin=subprocess.DEVNULL,
                     stdout=log, stderr=subprocess.STDOUT, start_new_session=True, cwd=str(Path.home()))
    deadline = time.time() + 15
    while time.time() < deadline:
        time.sleep(0.3)
        try:
            herdr(aid, "ping", timeout=2)
            return
        except Refused:
            continue
    raise Refused(503, f"herdr didn't start (see ~/.ygg-bridge/herdr-{account(aid)['id']}.log)")


def workdir(repo: str, branch: str = "") -> Path:
    """The phone's own checkout of a repo to work in: cloned once, then only
    fetched, so work in it is never thrown away. Git in it can push with the
    bridge's GitHub token, read when git needs it, never written into it."""
    if not REPO.match(repo):
        raise Refused(400, "a repo is owner/name, like SebbeJohansson/yggdrasil")
    path = home() / "work" / repo
    url = f"{GIT_BASE}/{repo}.git"
    if not (path / ".git").is_dir():
        fresh_clone(url, path, branch)
        helper = ('!f() { test "$1" = get || exit 0; t=$(cat "$HOME/.ygg-bridge/github" 2>/dev/null) || exit 0; '
                  'echo username=x-access-token; echo "password=$t"; }; f')
        git(["config", "credential.https://github.com.helper", helper], cwd=path)
        git(["config", "core.createObject", "rename"], cwd=path)
    else:
        git(["fetch", "-q", "--prune", "origin"], cwd=path)
        if branch:
            current = git(["branch", "--show-current"], cwd=path).strip()
            if current != branch:
                git(["checkout", "-q", branch], cwd=path)
    plain_git_files(path)
    return path


def local_sessions() -> list:
    return state().get("local", [])


def save_local(items: list) -> None:
    with lock:
        data = state()
        data["local"] = items
        save(data)


def local(name: str) -> dict:
    for s in local_sessions():
        if s["name"] == name:
            return s
    raise Refused(404, f"no local session '{name}'")


def local_name(repo: str) -> str:
    base = re.sub(r"[^a-z0-9_-]+", "-", repo.split("/")[-1].lower()).strip("-")
    base = re.sub(r"^[^a-z]+", "", base)[:26] or "work"
    taken = {s["name"] for s in local_sessions()}
    while True:
        name = f"{base}-{secrets.token_hex(2)}"
        if name not in taken:
            return name


def start_local(repo: str, task: str, aid: str = "default", branch: str = "") -> dict:
    """Claude Code in herdr, in the phone's checkout of the repo, as the account;
    then the task typed in, as you would."""
    need_claude()
    aid = account(aid)["id"]
    cwd = workdir(repo, branch)
    prepare_claude(cwd, aid)
    need_herdr(aid)
    name = local_name(repo)
    created = herdr(aid, "workspace.create", {"cwd": str(cwd), "label": name, "focus": False})
    pane = created.get("root_pane", {}).get("pane_id", "")
    workspace = (created.get("workspace") or {}).get("workspace_id") or pane.split(":")[0]
    deadline = time.time() + 20
    while True:
        try:
            herdr(aid, "agent.start", {"name": name, "kind": "claude", "pane_id": pane, "args": [], "timeout_ms": 30000}, timeout=40)
            break
        except HerdrRefused as e:
            if e.code == "agent_pane_busy" and time.time() < deadline:
                time.sleep(0.3)
                continue
            if e.code == "agent_not_ready":
                break  # Up, but at a dialog: it shows in the chat.
            herdr(aid, "workspace.close", {"workspace_id": workspace})
            raise
    session = {"name": name, "account": aid, "repo": repo, "cwd": str(cwd), "workspace": workspace, "pane": pane,
               "task": task.strip(), "at": int(time.time() * 1000)}
    save_local([session] + local_sessions())
    if task.strip():
        prompt_local(name, task)
    return local_view(session)


def wait_idle(s: dict, seconds: float) -> None:
    deadline = time.time() + seconds
    while time.time() < deadline:
        status = agent_info(s).get("agent_status")
        if status in ("idle", "done", "blocked"):
            return
        time.sleep(0.5)


def prompt_local(name: str, text: str) -> dict:
    s = local(name)
    text = " ".join(text.split("\r"))
    if not text.strip():
        raise Refused(400, "the message is empty")
    # Claude may still be getting ready (its prompt not up yet): wait a little.
    wait_idle(s, 20)
    herdr(s["account"], "agent.prompt", {"target": name, "text": text}, timeout=30)
    s["promptedAt"] = int(time.time() * 1000)
    save_local([s if x["name"] == name else x for x in local_sessions()])
    return {"ok": True}


def agent_info(s: dict) -> dict:
    try:
        agents = herdr(s["account"], "agent.list", timeout=5).get("agents", [])
    except Refused:
        return {}
    return next((a for a in agents if a.get("name") == s["name"] or a.get("pane_id") == s["pane"]), {})


def screen(s: dict, source: str = "detection", lines: int = 0) -> str:
    params = {"target": s["pane"], "source": source, "strip_ansi": True}
    if lines:
        params["lines"] = lines
    return herdr(s["account"], "agent.read", params, timeout=5).get("read", {}).get("text", "")


# A Claude Code dialog read off the screen (as Sindri's DialogScreenOf).
CHOICE = re.compile(r"^\s*(❯\s*)?(\d)\.\s+(.*\S)\s*$")
RULE = re.compile(r"^\s*─{10,}\s*$")
DOTTED = re.compile(r"^\s*╌{10,}\s*$")
FOOTER = re.compile(r"(?i)Esc to cancel|Enter to confirm|Tab to amend")
FAR_RIGHT = re.compile(r"^\s{30,}")
PINNED_RIGHT = re.compile(r"(\S)\s{8,}\S.*$")


def dialog_of(text: str):
    """The question, the detail above it and its numbered choices; None without one."""
    lines = ["" if FAR_RIGHT.match(l) else PINNED_RIGHT.sub(r"\1", l).rstrip() for l in text.split("\n")]
    found = [(i, int(m.group(2)), bool(m.group(1)), m.group(3).strip()) for i, l in enumerate(lines) if (m := CHOICE.match(l))]
    if not found:
        return None
    run = [found[-1]]
    for f in reversed(found[:-1]):
        if run[0][1] == 1 or f[1] != run[0][1] - 1:
            break
        run.insert(0, f)
    if len(run) < 2 or run[0][1] != 1:
        return None
    choices = []
    for at, (index, number, selected, label) in enumerate(run):
        end = run[at + 1][0] if at + 1 < len(run) else len(lines)
        parts = [label]
        for l in lines[index + 1:end]:
            if not l.strip() or FOOTER.search(l):
                break
            parts.append(l.strip())
        choices.append({"key": str(number), "label": " ".join(parts), "selected": selected})
    q = run[0][0] - 1
    while q >= 0 and not lines[q].strip():
        q -= 1
    if q < 0 or RULE.match(lines[q]):
        return None
    top = q
    while top > 0 and q - top < 16 and not RULE.match(lines[top - 1]):
        top -= 1
    detail = "\n".join(l for l in lines[top:q] if not DOTTED.match(l)).strip("\n").rstrip()
    return {"question": lines[q].strip(), "detail": detail, "choices": choices}


def transcript(s: dict):
    """The transcript of the Claude in the session's pane: its pid, in claude's
    own registry (sessions/<pid>.json), gives its session id."""
    base = claude_config(s["account"]).parent if s["account"] != "default" or os.environ.get("CLAUDE_CONFIG_DIR") \
        else Path.home() / ".claude"
    pids = []
    try:
        info = herdr(s["account"], "pane.process_info", {"pane_id": s["pane"]}, timeout=5)
        pids = [p.get("pid") for p in info.get("process_info", {}).get("foreground_processes", []) if p.get("pid")]
    except Refused:
        pass
    sid = s.get("sessionId", "")
    for pid in pids:
        try:
            sid = json.loads((base / "sessions" / f"{pid}.json").read_text()).get("sessionId") or sid
            break
        except (OSError, ValueError):
            continue
    if sid and sid != s.get("sessionId"):
        s["sessionId"] = sid
        save_local([s if x["name"] == s["name"] else x for x in local_sessions()])
    if not sid:
        return None
    path = base / "projects" / re.sub(r"[^a-zA-Z0-9]", "-", s["cwd"]) / f"{sid}.jsonl"
    return path if path.is_file() else None


def tool_line(block: dict) -> str:
    name = block.get("name", "tool")
    args = block.get("input") or {}
    what = args.get("command") or args.get("file_path") or args.get("path") or args.get("pattern") or args.get("url") or args.get("description") or ""
    return f"{name}: {str(what).splitlines()[0][:160]}" if what else name


def messages(path, limit: int = 200) -> list:
    """The chat: your messages, Claude's text, and a line per tool it used."""
    out = []
    if not path:
        return out
    for raw in path.read_text(errors="replace").splitlines():
        try:
            e = json.loads(raw)
        except ValueError:
            continue
        if e.get("isSidechain") or e.get("isMeta") or e.get("type") not in ("user", "assistant"):
            continue
        content = (e.get("message") or {}).get("content")
        at = e.get("timestamp", "")
        if e["type"] == "user":
            text = content if isinstance(content, str) else "\n".join(b.get("text", "") for b in content or [] if isinstance(b, dict) and b.get("type") == "text")
            if text.strip() and not text.lstrip().startswith(("<command-", "<local-command", "<system-reminder", "Caveat:")):
                out.append({"role": "user", "text": text.strip(), "at": at})
        else:
            for b in content or []:
                if not isinstance(b, dict):
                    continue
                if b.get("type") == "text" and b.get("text", "").strip():
                    out.append({"role": "assistant", "text": b["text"].strip(), "at": at})
                elif b.get("type") == "tool_use":
                    out.append({"role": "tool", "text": tool_line(b), "at": at})
    return out[-limit:]


def local_view(s: dict, chat: bool = False) -> dict:
    """A session as the app shows it: status, any dialog, and with chat its messages."""
    info = agent_info(s)
    status = info.get("agent_status") or ("gone" if not info else "unknown")
    view = {k: s.get(k) for k in ("name", "account", "repo", "task", "at", "cwd")}
    view["status"] = status
    view["dialog"] = None
    if status == "blocked":
        try:
            view["dialog"] = dialog_of(screen(s))
        except Refused:
            pass
    if chat:
        view["messages"] = messages(transcript(s))
        if not view["messages"] or status in ("blocked", "unknown"):
            try:
                view["screen"] = screen(s, "recent_unwrapped", 40)[-4000:]
            except Refused:
                view["screen"] = ""
    return view


def choose_local(name: str, question: str, key: str, label: str) -> dict:
    """Presses a dialog's choice, only while that same dialog shows: the same
    digit means something else in the next one."""
    s = local(name)
    shown = dialog_of(screen(s))
    if not shown or shown["question"] != question or not any(c["key"] == key and c["label"] == label for c in shown["choices"]):
        raise Refused(409, "that question isn't showing any more")
    herdr(s["account"], "agent.send_keys", {"target": s["pane"], "keys": [key]})
    return {"ok": True}


def keys_local(name: str, keys: list) -> dict:
    allowed = {"esc", "enter", "ctrl+c", "up", "down", "tab", "shift+tab"}
    if not keys or any(k not in allowed for k in keys):
        raise Refused(400, "only " + ", ".join(sorted(allowed)))
    s = local(name)
    herdr(s["account"], "agent.send_keys", {"target": s["pane"], "keys": keys})
    return {"ok": True}


def stop_local(name: str) -> dict:
    """Ends Claude there and closes its workspace; the checkout and its work stay."""
    s = local(name)
    try:
        herdr(s["account"], "workspace.close", {"workspace_id": s.get("workspace") or s["pane"].split(":")[0]})
    except Refused:
        pass
    save_local([x for x in local_sessions() if x["name"] != name])
    return {"ok": True}


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
    env = claude_env(str(body.get("account") or "default"))
    with tempfile.TemporaryDirectory() as empty:
        out = subprocess.run(argv, cwd=empty, capture_output=True, text=True, timeout=300, env=env)
        if out.returncode != 0 and "--tools" in (out.stderr + out.stdout):
            # An older claude without --tools: ask again without it (print mode still asks before any tool).
            argv = [a for i, a in enumerate(argv) if a != "--tools" and not (i > 0 and argv[i - 1] == "--tools")]
            out = subprocess.run(argv, cwd=empty, capture_output=True, text=True, timeout=300, env=env)
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
                                                             "github": bool(secret("github")), "herdr": bool(shutil.which(HERDR))})
            if not self.allowed():
                return
            if method == "GET" and path == "/v1/cloud":
                return self.answer(200, {"sessions": state()["sessions"]})
            if method == "GET" and path == "/v1/repos":
                return self.answer(200, {"repos": repos()})
            if method == "POST" and path == "/v1/cloud":
                b = self.body()
                return self.answer(200, start_cloud(str(b.get("repo", "")), str(b.get("task", "")), str(b.get("branch") or ""),
                                                    str(b.get("account") or "default")))
            m = re.match(r"^/v1/cloud/([A-Za-z0-9_-]+)/message$", path)
            if method == "POST" and m:
                b = self.body()
                return self.answer(200, message_cloud(m.group(1), str(b.get("text", "")), str(b.get("account") or "")))
            if method == "GET" and path == "/v1/local":
                return self.answer(200, {"sessions": [local_view(x) for x in local_sessions()]})
            if method == "POST" and path == "/v1/local":
                b = self.body()
                return self.answer(200, start_local(str(b.get("repo", "")), str(b.get("task", "")),
                                                    str(b.get("account") or "default"), str(b.get("branch") or "")))
            m = re.match(r"^/v1/local/([a-z][a-z0-9_-]{0,31})(?:/(prompt|choose|keys|stop))?$", path)
            if m and method == "GET" and not m.group(2):
                return self.answer(200, local_view(local(m.group(1)), chat=True))
            if m and method == "POST" and m.group(2):
                b = self.body()
                name, what = m.group(1), m.group(2)
                if what == "prompt":
                    return self.answer(200, prompt_local(name, str(b.get("text", ""))))
                if what == "choose":
                    return self.answer(200, choose_local(name, str(b.get("question", "")), str(b.get("key", "")), str(b.get("label", ""))))
                if what == "keys":
                    return self.answer(200, keys_local(name, [str(k) for k in b.get("keys") or []]))
                return self.answer(200, stop_local(name))
            if method == "GET" and path == "/v1/accounts":
                return self.answer(200, {"accounts": [account_info(a) for a in accounts()]})
            if method == "POST" and path == "/v1/accounts":
                return self.answer(200, add_account(str(self.body().get("name", ""))))
            m = re.match(r"^/v1/accounts/([a-z0-9-]+)(/remove)?$", path)
            if method == "POST" and m:
                if m.group(2):
                    remove_account(m.group(1))
                    return self.answer(200, {"ok": True})
                return self.answer(200, rename_account(m.group(1), str(self.body().get("name", ""))))
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
    elif command == "accounts":
        for a in accounts():
            i = account_info(a)
            print(f"{i['id']:<16} {i['name']:<20} {i['email'] or ('signed in' if i['signedIn'] else 'not signed in')}")
    elif command == "login":
        # ygg_bridge.py login [account]: claude's own sign-in, into that account's folder.
        try:
            env = claude_env(argv[2] if len(argv) > 2 else "default")
        except Refused as e:
            print(e)
            return 2
        return subprocess.run([CLAUDE, "auth", "login"], env=env).returncode
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
