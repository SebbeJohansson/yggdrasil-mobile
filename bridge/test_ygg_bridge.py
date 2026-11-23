"""Tests the bridge end to end over HTTP, with a fake `claude` and a local
git repo standing in for GitHub. Run: python3 bridge/test_ygg_bridge.py (the bridge is app/src/main/assets/bridge/ygg_bridge.py)"""

import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path

FAKE_CLAUDE = r'''#!/usr/bin/env python3
import json, os, sys, time
a = sys.argv[1:]
if os.environ.get("FAKE_LOG"):
    with open(os.environ["FAKE_LOG"], "a") as log:
        log.write(json.dumps({"argv": a, "config": os.environ.get("CLAUDE_CONFIG_DIR", "")}) + "\n")
if a == ["--version"]:
    print("2.9.0 (Claude Code)"); sys.exit(0)
if a[:1] == ["--cloud"]:
    import os
    if os.environ.get("FAKE_REFUSE_STUB") and os.path.exists(".git/info/sparse-checkout"):
        print("Not uploading this working tree: its index is a link. Use a fresh clone.", flush=True)
        sys.exit(1)
    print("\x1b[1mSetting up the cloud session…\x1b[0m", flush=True)
    time.sleep(0.2)
    print("Started: https://claude.ai/code/session_01AbC-xyz", flush=True)
    time.sleep(30)  # It keeps going; the bridge stops it once it has the link.
if "--cloud" in a and a[0] == "-p":
    sid = a[a.index("--cloud") + 1]
    ok = sid != "session_gone"
    print(json.dumps({"ok": ok, "session_id": sid, "url": "https://claude.ai/code/" + sid} if ok else {"ok": False, "session_id": sid, "error": "not found"}))
    sys.exit(0)
if a[0] == "-p":
    print(json.dumps({"type": "result", "is_error": False, "result": "echo: " + a[1][-40:], "argv": a}))
    sys.exit(0)
sys.exit(3)
'''


class BridgeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        tmp = Path(cls.tmp)
        fake = tmp / "claude"
        fake.write_text(FAKE_CLAUDE)
        fake.chmod(0o755)
        # A "GitHub" with one repo.
        origin = tmp / "gh" / "me" / "app.git"
        work = tmp / "work"
        subprocess.run(["git", "init", "-q", "-b", "main", str(work)], check=True)
        (work / "README.md").write_text("hi")
        subprocess.run(["git", "-C", str(work), "-c", "user.email=t@t", "-c", "user.name=t", "add", "."], check=True)
        subprocess.run(["git", "-C", str(work), "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "one"], check=True)
        subprocess.run(["git", "clone", "-q", "--bare", str(work), str(origin)], check=True)
        # As GitHub does: partial clones (--filter=blob:none) are served.
        subprocess.run(["git", "-C", str(origin), "config", "uploadpack.allowFilter", "true"], check=True)
        os.environ.update(YGG_BRIDGE_HOME=str(tmp / "home"), CLAUDE_BIN=str(fake), YGG_BRIDGE_GIT_BASE="file://" + str(tmp / "gh"),
                          YGG_BRIDGE_START_WAIT="20", CLAUDE_CONFIG_DIR=str(tmp / "claude-config"))
        (tmp / "claude-config").mkdir()
        # No __pycache__ beside the bridge: the assets folder goes into the APK as it is.
        sys.dont_write_bytecode = True
        sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "app/src/main/assets/bridge"))
        import ygg_bridge
        cls.b = ygg_bridge
        cls.server = ygg_bridge.ThreadingHTTPServer(("127.0.0.1", 0), ygg_bridge.Handler)
        cls.port = cls.server.server_address[1]
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.key = ygg_bridge.key()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def call(self, method, path, body=None, key=True):
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", method=method,
                                     data=json.dumps(body).encode() if body is not None else None)
        if key:
            req.add_header("Authorization", "Bearer " + self.key)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def test_health_is_open_and_says_the_version(self):
        status, body = self.call("GET", "/health", key=False)
        self.assertEqual(200, status)
        self.assertIn("2.9.0", body["claude"])

    def test_everything_else_needs_the_key(self):
        status, body = self.call("GET", "/v1/cloud", key=False)
        self.assertEqual(401, status)
        self.assertIn("key", body["error"])

    def test_start_clones_runs_claude_cloud_and_keeps_the_session(self):
        status, s = self.call("POST", "/v1/cloud", {"repo": "me/app", "task": "Fix the README"})
        self.assertEqual(200, status, s)
        self.assertEqual("session_01AbC-xyz", s["id"])
        self.assertEqual("https://claude.ai/code/session_01AbC-xyz", s["url"])
        # It started from a stub: origin and branch set, no files, nothing for git to report.
        stub = Path(self.tmp) / "home" / "stubs" / "me" / "app"
        run = lambda *a: subprocess.run(["git", *a], cwd=stub, capture_output=True, text=True).stdout.strip()
        self.assertEqual("file://" + str(Path(self.tmp) / "gh" / "me" / "app.git"), run("remote", "get-url", "origin"))
        self.assertEqual("main", run("branch", "--show-current"))
        self.assertEqual("origin/main", run("rev-parse", "--abbrev-ref", "@{upstream}"))
        self.assertEqual("", run("status", "--porcelain"))
        self.assertFalse((stub / "README.md").exists())
        # claude's first-run questions are answered, so it doesn't wait for nobody.
        config = json.loads((Path(self.tmp) / "claude-config" / ".claude.json").read_text())
        self.assertTrue(config["hasCompletedOnboarding"])
        self.assertTrue(config["projects"][str(stub)]["hasTrustDialogAccepted"])
        _, listed = self.call("GET", "/v1/cloud")
        self.assertEqual("Fix the README", listed["sessions"][0]["task"])
        _, rs = self.call("GET", "/v1/repos")
        # The repo a cloud session was started for is offered first next time.
        self.assertEqual("me/app", rs["repos"][0]["repo"])

    def test_start_refuses_a_bad_repo_or_an_empty_task(self):
        self.assertEqual(400, self.call("POST", "/v1/cloud", {"repo": "not a repo", "task": "x"})[0])
        self.assertEqual(400, self.call("POST", "/v1/cloud", {"repo": "me/app", "task": "  "})[0])
        status, body = self.call("POST", "/v1/cloud", {"repo": "me/missing", "task": "x"})
        self.assertEqual(502, status)
        self.assertIn("git ls-remote failed", body["error"])

    def test_github_token_is_kept_and_forgotten(self):
        self.assertEqual(400, self.call("POST", "/v1/github", {"token": "not a token!"})[0])
        status, body = self.call("POST", "/v1/github", {"token": "ghp_" + "a" * 36})
        self.assertEqual((200, True), (status, body["github"]))
        status, body = self.call("POST", "/v1/github", {"token": ""})
        self.assertEqual((200, False), (status, body["github"]))

    def test_a_refused_stub_falls_back_to_a_full_checkout(self):
        os.environ["FAKE_REFUSE_STUB"] = "1"
        try:
            status, s = self.call("POST", "/v1/cloud", {"repo": "me/app", "task": "Again"})
        finally:
            del os.environ["FAKE_REFUSE_STUB"]
        self.assertEqual(200, status, s)
        self.assertTrue((Path(self.tmp) / "home" / "repos" / "me" / "app" / "README.md").exists())

    def test_checkout_is_full_plain_and_kept_up_to_date(self):
        b = self.b
        path = b.checkout("me/app")
        self.assertFalse((path / ".git" / "shallow").exists())
        # An old shallow checkout is cloned again.
        (path / ".git" / "shallow").write_text("x\n")
        path = b.checkout("me/app")
        self.assertFalse((path / ".git" / "shallow").exists())
        # A checkout git can't bring up to date is cloned again.
        (path / ".git" / "HEAD").write_text("garbage\n")
        path = b.checkout("me/app")
        self.assertEqual("main", subprocess.run(["git", "branch", "--show-current"], cwd=path, capture_output=True, text=True).stdout.strip())

    def test_linked_index_becomes_a_plain_file(self):
        path = self.b.checkout("me/app")
        index = path / ".git" / "index"
        real = path / ".git" / ".l2s.index0001"
        index.rename(real)
        index.symlink_to(real.name)
        data = real.read_bytes()
        self.b.plain_git_files(path)
        self.assertFalse(index.is_symlink())
        self.assertFalse(real.exists())
        self.assertEqual(data, index.read_bytes())
        ok = subprocess.run(["git", "status", "--porcelain"], cwd=path, capture_output=True, text=True)
        self.assertEqual(0, ok.returncode, ok.stderr)

    def test_links_in_objects_become_plain_files(self):
        path = self.b.checkout("me/app")
        objects = path / ".git" / "objects"
        f = next(p for p in objects.rglob("*") if p.is_file() and p.parent.name != "info")
        hidden = f.with_name(".l2s." + f.name + "0001")
        f.rename(hidden)
        f.symlink_to(hidden.name)
        (objects / ".l2s.orphan0002").write_text("x")
        self.b.plain_git_files(path)
        self.assertFalse(f.is_symlink())
        self.assertEqual([], [p for p in path.joinpath(".git").rglob(".l2s.*")])
        self.assertEqual([], [p for p in path.joinpath(".git").rglob("*") if p.is_symlink()])
        ok = subprocess.run(["git", "fsck", "--no-progress"], cwd=path, capture_output=True, text=True)
        self.assertEqual(0, ok.returncode, ok.stdout + ok.stderr)

    def test_reason_reads_cursor_moves_as_spaces(self):
        out = "\x1b[2;1HError:\x1b[1CNot\x1b[1Cuploading\x1b[12Gthis working tree.\r\n"
        self.assertEqual("Error: Not uploading this working tree.", self.b.reason(out))

    def test_reason_finds_the_error_line(self):
        out = "\x1b7\x1b[?25l\x1b(B\x1b[>4m\x1b]0;claude\x1b\\Working\x1b8\r\nNot\x1b[1Cuploading this working tree: its index is a link.\r\nUse a fresh clone.\r\n\x1b[<u"
        self.assertTrue(self.b.reason(out).startswith("Not uploading this working tree: its index is a link. Use a fresh clone."), self.b.reason(out))

    def test_accounts_keep_their_own_claude(self):
        status, accts = self.call("GET", "/v1/accounts")
        self.assertEqual(200, status)
        self.assertEqual("default", accts["accounts"][0]["id"])
        status, work = self.call("POST", "/v1/accounts", {"name": "Work"})
        self.assertEqual((200, "work"), (status, work["id"]))
        self.assertEqual(400, self.call("POST", "/v1/accounts", {"name": "work"})[0])
        # Signed in is the account's own credentials, and who from its own config.
        cfg = Path(work["configDir"])
        (cfg / ".credentials.json").write_text('{"claudeAiOauth": {}}')
        (cfg / ".claude.json").write_text(json.dumps({"oauthAccount": {"emailAddress": "me@work.example"}}))
        _, accts = self.call("GET", "/v1/accounts")
        w = next(a for a in accts["accounts"] if a["id"] == "work")
        self.assertEqual((True, "me@work.example"), (w["signedIn"], w["email"]))
        log = Path(self.tmp) / "fake.log"
        os.environ["FAKE_LOG"] = str(log)
        try:
            status, s = self.call("POST", "/v1/cloud", {"repo": "me/app", "task": "At work", "account": "work"})
            self.assertEqual((200, "work"), (status, s["account"]))
            # A follow-up goes in as the account that started the session.
            self.assertEqual(200, self.call("POST", f"/v1/cloud/{s['id']}/message", {"text": "More"})[0])
        finally:
            del os.environ["FAKE_LOG"]
        runs = [json.loads(l) for l in log.read_text().splitlines()]
        self.assertTrue(runs and all(r["config"] == str(cfg) for r in runs), runs)
        self.assertTrue(json.loads((cfg / ".claude.json").read_text())["hasCompletedOnboarding"])
        self.assertEqual(404, self.call("POST", "/v1/cloud", {"repo": "me/app", "task": "x", "account": "nope"})[0])
        self.assertEqual(200, self.call("POST", "/v1/accounts/work", {"name": "Job"})[0])
        self.assertEqual(200, self.call("POST", "/v1/accounts/work/remove")[0])
        self.assertFalse(cfg.exists())
        self.assertEqual(400, self.call("POST", "/v1/accounts/default/remove")[0])

    def test_dialog_is_read_off_the_screen(self):
        screen = "\n".join([
            "─" * 40,
            " Bash command",
            "   npm test",
            "   Run the tests",
            "",
            " Do you want to proceed?",
            " ❯ 1. Yes",
            "   2. Yes, and don't ask again for npm test commands in /root/app",
            "   3. No, and tell Claude what to do differently (esc)",
            "",
            " Esc to cancel",
        ])
        d = self.b.dialog_of(screen)
        self.assertEqual("Do you want to proceed?", d["question"])
        self.assertIn("npm test", d["detail"])
        self.assertEqual(["1", "2", "3"], [c["key"] for c in d["choices"]])
        self.assertTrue(d["choices"][0]["selected"])
        self.assertIsNone(self.b.dialog_of("❯ Try \"fix typecheck errors\""))

    def test_transcript_becomes_a_chat(self):
        lines = [
            {"type": "user", "message": {"role": "user", "content": "Add a README"}, "timestamp": "t1"},
            {"type": "user", "isMeta": True, "message": {"role": "user", "content": "<system-reminder>x</system-reminder>"}},
            {"type": "assistant", "message": {"role": "assistant", "content": [
                {"type": "text", "text": "Sure."},
                {"type": "tool_use", "name": "Write", "input": {"file_path": "/root/app/README.md", "content": "hi"}}]}},
            {"type": "user", "message": {"role": "user", "content": [{"type": "tool_result", "content": "ok"}]}},
            {"type": "assistant", "isSidechain": True, "message": {"content": [{"type": "text", "text": "subagent"}]}},
            {"type": "assistant", "message": {"content": [{"type": "text", "text": "Done."}]}},
        ]
        f = Path(self.tmp) / "t.jsonl"
        f.write_text("\n".join(json.dumps(l) for l in lines) + "\nnot json\n")
        self.assertEqual([("user", "Add a README"), ("assistant", "Sure."), ("tool", "Write: /root/app/README.md"), ("assistant", "Done.")],
                         [(m["role"], m["text"]) for m in self.b.messages(f)])

    def _git(self, *a, cwd):
        return subprocess.run(["git", "-c", "user.email=t@t", "-c", "user.name=t", *a], cwd=cwd, check=True,
                              capture_output=True, text=True).stdout

    def _push_upstream(self, n):
        """n new commits on "GitHub" (the test's origin), from another clone."""
        origin = Path(self.tmp) / "gh" / "me" / "app.git"
        other = Path(self.tmp) / "other-clone"
        if not other.exists():
            self._git("clone", "-q", str(origin), str(other), cwd=self.tmp)
        self._git("pull", "-q", cwd=other)
        for i in range(n):
            (other / f"up{i}-{time.time()}.txt").write_text("x")
            self._git("add", ".", cwd=other)
            self._git("commit", "-qm", f"upstream {i}", cwd=other)
        self._git("push", "-q", cwd=other)

    def test_each_session_has_its_own_worktree_from_the_newest_commit(self):
        b = self.b
        root = b.workdir("me/app")
        self._push_upstream(1)
        newest = self._git("ls-remote", str(Path(self.tmp) / "gh" / "me" / "app.git"), "refs/heads/main", cwd=self.tmp).split()[0]
        one, two = b.worktree("me/app", "app-1111"), b.worktree("me/app", "app-2222")
        a, c = Path(one["path"]), Path(two["path"])
        self.assertEqual(("ygg/app-1111", "main"), (one["branch"], one["base"]))
        # Each starts on the newest commit on GitHub, though the shared checkout wasn't pulled.
        for wt in (a, c):
            self.assertEqual(newest, self._git("rev-parse", "HEAD", cwd=wt).strip())
        self.assertNotEqual(newest, self._git("rev-parse", "HEAD", cwd=root).strip())
        # One's work isn't the other's, nor the shared checkout's.
        (a / "README.md").write_text("one's\n")
        self.assertEqual("hi", (c / "README.md").read_text())
        self.assertEqual("", self._git("status", "--porcelain", cwd=c))
        self.assertEqual("", self._git("status", "--porcelain", cwd=root))
        self.assertEqual("ygg/app-2222", self._git("branch", "--show-current", cwd=c).strip())
        with self.assertRaises(b.Refused):
            b.worktree("me/app", "app-3333", "no-such-branch")

    def test_a_worktree_session_pulls_pushes_and_is_removed_only_without_losing_work(self):
        b = self.b
        wt = b.worktree("me/app", "app-4444")
        cwd = Path(wt["path"])
        b.save_local([{"name": "app-4444", "account": "default", "repo": "me/app", "cwd": str(cwd), "pane": "w9:p9",
                       "task": "", "at": 0, "worktree": wt}])
        try:
            r = self.call("GET", "/v1/local/app-4444/repo")[1]
            self.assertEqual(("ygg/app-4444", "origin/main", 0, 0), (r["branch"], r["upstream"], r["ahead"], r["behind"]))
            # New on GitHub: Pull brings it, as the branch has nothing of its own.
            self._push_upstream(1)
            status, out = self.call("POST", "/v1/local/app-4444/git", {"action": "pull"})
            self.assertEqual(200, status, out)
            self.assertEqual(0, self.call("GET", "/v1/local/app-4444/repo")[1]["behind"])
            # Uncommitted work: not removed.
            (cwd / "README.md").write_text("work\n")
            status, out = self.call("POST", "/v1/local/app-4444/remove")
            self.assertEqual(409, status)
            self.assertIn("not committed", out["error"])
            # Committed, not pushed: still not.
            self._git("commit", "-qam", "work", cwd=cwd)
            self.assertEqual(1, self.call("GET", "/v1/local/app-4444/repo")[1]["ahead"])
            status, out = self.call("POST", "/v1/local/app-4444/remove")
            self.assertEqual(409, status)
            self.assertIn("1 commit not pushed", out["error"])
            # New on GitHub beside its own: Pull says to rebase, and changes nothing.
            self._push_upstream(1)
            status, out = self.call("POST", "/v1/local/app-4444/git", {"action": "pull"})
            self.assertEqual(409, status)
            self.assertIn("rebase", out["error"])
            # Pushed: its branch is on GitHub, and then it can go.
            self.assertEqual(200, self.call("POST", "/v1/local/app-4444/git", {"action": "push"})[0])
            origin = Path(self.tmp) / "gh" / "me" / "app.git"
            self.assertIn("refs/heads/ygg/app-4444", self._git("ls-remote", str(origin), cwd=self.tmp))
            status, out = self.call("POST", "/v1/local/app-4444/remove")
            self.assertEqual(200, status, out)
            self.assertFalse(cwd.exists())
            self.assertEqual([], b.local_sessions())
            root = b.workdir_path("me/app")
            self.assertNotIn("ygg/app-4444", self._git("branch", "--list", "ygg/*", cwd=root))
            # GitHub keeps it.
            self.assertIn("refs/heads/ygg/app-4444", self._git("ls-remote", str(origin), cwd=self.tmp))
        finally:
            b.save_local([])

    def _second_repo(self):
        """me/api on "GitHub", beside me/app."""
        origin = Path(self.tmp) / "gh" / "me" / "api.git"
        if origin.exists():
            return
        work = Path(self.tmp) / "api-work"
        subprocess.run(["git", "init", "-q", "-b", "main", str(work)], check=True)
        (work / "server.py").write_text("print('api')\n")
        self._git("add", ".", cwd=work)
        self._git("commit", "-qm", "api", cwd=work)
        subprocess.run(["git", "clone", "-q", "--bare", str(work), str(origin)], check=True)

    def test_one_session_over_several_repos(self):
        b = self.b
        self._second_repo()
        started = {}

        def fake_open(aid, cwd, name, args):
            started.update(cwd=str(cwd), args=args)
            return "w7:p1", "w7"
        saved = (b.open_claude, b.need_herdr, b.agent_info)
        b.open_claude, b.need_herdr, b.agent_info = fake_open, (lambda aid: None), (lambda s: {})
        try:
            v = b.start_local("me/app", "", also=["me/api", "me/app", " "])
            self.assertEqual(["me/app", "me/api"], v["repos"])
            s = b.local(v["name"])
            app, api = (Path(w["path"]) for w in s["worktrees"])
            # One branch name in both, each its own worktree; Claude starts in the first and may work in the other.
            self.assertEqual({f"ygg/{v['name']}"}, {w["branch"] for w in s["worktrees"]})
            self.assertEqual(str(app), started["cwd"])
            self.assertEqual(["--add-dir", str(api)], started["args"][:2])
            note = started["args"][started["args"].index("--append-system-prompt") + 1]
            self.assertIn(f"me/api: {api}", note)
            self.assertIn("commit in each one", note)
            self.assertIn("api:", v["refreshed"])
            # The repo view, a diff and a push, per repo.
            (api / "server.py").write_text("print('api v2')\n")
            r = self.call("GET", f"/v1/local/{v['name']}/repo?repo=me/api")[1]
            self.assertEqual(("me/api", ["server.py"]), (r["repo"], [f["path"] for f in r["files"]]))
            self.assertEqual([], self.call("GET", f"/v1/local/{v['name']}/repo")[1]["files"])
            self.assertIn("api v2", self.call("GET", f"/v1/local/{v['name']}/diff?path=server.py&repo=me/api")[1]["diff"])
            self.assertEqual(404, self.call("GET", f"/v1/local/{v['name']}/repo?repo=me/other")[0])
            # Not removed while either repo would lose work.
            self._git("commit", "-qam", "v2", cwd=api)
            status, out = self.call("POST", f"/v1/local/{v['name']}/remove")
            self.assertEqual(409, status)
            self.assertIn("1 commit not pushed in me/api", out["error"])
            self.assertEqual(200, self.call("POST", f"/v1/local/{v['name']}/git", {"action": "push", "repo": "me/api"})[0])
            self.assertEqual(200, self.call("POST", f"/v1/local/{v['name']}/remove")[0])
            self.assertFalse(app.exists() or api.exists())
            # Claude never starting leaves no worktree behind.
            def broken(*a):
                raise b.Refused(503, "herdr didn't start")
            b.open_claude = broken
            folders = lambda: {p for p in (Path(self.tmp) / "home" / "worktrees").glob("me/*/*")}
            before = folders()
            with self.assertRaises(b.Refused):
                b.start_local("me/app", "", also=["me/api"])
            self.assertEqual(before, folders())
            self.assertEqual(400, self.call("POST", "/v1/local", {"repo": "me/app", "repos": ["not a repo"]})[0])
        finally:
            b.open_claude, b.need_herdr, b.agent_info = saved
            b.save_local([])

    def test_repos_suggests_what_you_used_newest_first_then_github(self):
        b = self.b
        b.workdir("me/app")
        saved = b.github_repos
        b.github_repos = lambda: [{"repo": "me/gh-only", "branch": "trunk", "source": "github"}, {"repo": "me/app", "branch": "main"}]
        try:
            b.save_local([{"name": "x-1", "repo": "me/api", "at": 9_999_999_999_999, "worktrees": [{"repo": "me/api"}, {"repo": "me/lib"}]}])
            listed = [r["repo"] for r in self.call("GET", "/v1/repos")[1]["repos"]]
            self.assertEqual(["me/api", "me/lib"], listed[:2])
            self.assertIn("me/app", listed)
            self.assertEqual("me/gh-only", listed[-1])
            self.assertEqual(len(listed), len({r.lower() for r in listed}))
        finally:
            b.github_repos = saved
            b.save_local([])

    def test_local_repo_changes_diff_and_push(self):
        """A session from before worktrees, in the shared checkout: still works."""
        b = self.b
        cwd = b.workdir("me/app")
        self._git("merge", "-q", "--ff-only", "origin/main", cwd=cwd)  # Other tests push to "GitHub".
        b.save_local([{"name": "app-test", "account": "default", "repo": "me/app", "cwd": str(cwd), "pane": "w9:p1", "task": "", "at": 0}])
        try:
            (cwd / "README.md").write_text("hi\nmore\n")
            (cwd / "new.txt").write_text("new\n")
            status, r = self.call("GET", "/v1/local/app-test/repo")
            self.assertEqual(200, status, r)
            self.assertEqual(("main", "origin/main", 0, 0), (r["branch"], r["upstream"], r["ahead"], r["behind"]))
            files = {f["path"]: f for f in r["files"]}
            self.assertEqual((2, 1), (files["README.md"]["added"], files["README.md"]["removed"]))
            self.assertEqual("??", files["new.txt"]["status"])
            status, d = self.call("GET", "/v1/local/app-test/diff?path=README.md")
            self.assertIn("+more", d["diff"])
            self.assertIn("+new", self.call("GET", "/v1/local/app-test/diff?path=new.txt")[1]["diff"])
            subprocess.run(["git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qam", "more"], cwd=cwd, check=True)
            self.assertEqual(1, self.call("GET", "/v1/local/app-test/repo")[1]["ahead"])
            status, out = self.call("POST", "/v1/local/app-test/git", {"action": "push"})
            self.assertEqual(200, status, out)
            self.assertEqual(0, self.call("GET", "/v1/local/app-test/repo")[1]["ahead"])
            self.assertEqual(200, self.call("POST", "/v1/local/app-test/git", {"action": "pull"})[0])
            self.assertEqual(400, self.call("POST", "/v1/local/app-test/git", {"action": "reset"})[0])
            self.assertEqual(404, self.call("POST", "/v1/local/app-test/nothing")[0])
            self.assertEqual("app-test", b.local_sessions()[0]["name"])  # an unknown action doesn't stop it
        finally:
            b.save_local([])

    def test_transcript_state_as_sindri_reads_it(self):
        b = self.b
        f = Path(self.tmp) / "state.jsonl"
        def write(*entries):
            f.write_text("\n".join(json.dumps(e) for e in entries) + "\n")
            return b.transcript_state(f)
        user = {"type": "user", "message": {"content": "Fix it"}, "timestamp": "t1"}
        self.assertEqual("busy", write(user)["phase"])
        working = {"type": "assistant", "message": {"stop_reason": "tool_use", "content": [{"type": "tool_use", "name": "Bash"}]}}
        st = write(user, working)
        self.assertEqual(("busy", "Bash"), (st["phase"], st["tool"]))
        done = {"type": "assistant", "message": {"stop_reason": "end_turn", "content": [{"type": "text", "text": "All fixed."}]}}
        meta = {"type": "user", "isMeta": True, "message": {"content": "<local-command-stdout>"}}
        st = write(user, working, done, meta, {"type": "summary"})
        self.assertEqual(("turn-ended", "All fixed."), (st["phase"], st["lastText"]))
        st = write(user, {"type": "user", "message": {"content": [{"type": "text", "text": "[Request interrupted by user]"}]}})
        self.assertEqual(("turn-ended", "interrupted"), (st["phase"], st["ending"]))
        self.assertEqual("busy", write(user, {"type": "assistant", "isSidechain": True, "message": {"stop_reason": "end_turn"}})["phase"])
        self.assertIsNone(write({"type": "summary"}))
        f.write_text("\n".join(json.dumps(e) for e in [{"type": "ai-title", "aiTitle": "Fixing it"}, user, {"type": "custom-title", "customTitle": "Mine"}]))
        self.assertEqual("Mine", b.transcript_title(f))

    def test_resume_needs_a_conversation(self):
        b = self.b
        b.save_local([{"name": "app-gone", "account": "default", "repo": "me/app", "cwd": "/nowhere", "pane": "w9:p1", "task": "", "at": 0}])
        try:
            v = b.local_view(b.local("app-gone"))
            self.assertEqual(("ended", False), (v["phase"], v["resumable"]))
            self.assertEqual(409, self.call("POST", "/v1/local/app-gone/resume")[0])
            # A message to it resumes it first; with no conversation, that's refused too.
            self.assertEqual(409, self.call("POST", "/v1/local/app-gone/prompt", {"text": "hi"})[0])
        finally:
            b.save_local([])

    def test_local_sessions_need_one(self):
        self.assertEqual(404, self.call("GET", "/v1/local/nothing-here")[0])
        self.assertEqual(404, self.call("POST", "/v1/local/nothing-here/prompt", {"text": "hi"})[0])
        self.assertEqual(200, self.call("GET", "/v1/local")[0])
        with self.assertRaises(self.b.Refused):
            self.b.keys_local("nothing-here", ["rm -rf"])

    def test_message_queues_in_any_session(self):
        status, body = self.call("POST", "/v1/cloud/session_abc/message", {"text": "Also add tests"})
        self.assertEqual((200, True), (status, body["ok"]))
        status, body = self.call("POST", "/v1/cloud/session_gone/message", {"text": "hello?"})
        self.assertEqual(502, status)
        self.assertIn("not found", body["error"])
        self.assertEqual(400, self.call("POST", "/v1/cloud/notanid/message", {"text": "x"})[0])

    def test_complete_answers_for_the_claude_provider(self):
        status, body = self.call("POST", "/v1/complete", {"system": "Be brief.", "messages": [{"role": "user", "text": "What is Ygg?"}]})
        self.assertEqual(200, status, body)
        self.assertEqual("echo: What is Ygg?", body["text"])


if __name__ == "__main__":
    unittest.main()
