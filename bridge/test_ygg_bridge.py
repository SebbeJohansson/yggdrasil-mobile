"""Tests the bridge end to end over HTTP, with a fake `claude` and a local
git repo standing in for GitHub. Run: python3 bridge/test_ygg_bridge.py (the bridge is app/src/main/assets/bridge/ygg_bridge.py)"""

import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path

FAKE_CLAUDE = r'''#!/usr/bin/env python3
import json, sys, time
a = sys.argv[1:]
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
        self.assertEqual([{"repo": "me/app", "branch": "main"}], rs["repos"])

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
