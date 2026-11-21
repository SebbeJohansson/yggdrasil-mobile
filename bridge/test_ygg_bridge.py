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
        os.environ.update(YGG_BRIDGE_HOME=str(tmp / "home"), CLAUDE_BIN=str(fake), YGG_BRIDGE_GIT_BASE="file://" + str(tmp / "gh"),
                          YGG_BRIDGE_START_WAIT="20")
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
        self.assertTrue((Path(self.tmp) / "home" / "repos" / "me" / "app" / "README.md").exists())
        _, listed = self.call("GET", "/v1/cloud")
        self.assertEqual("Fix the README", listed["sessions"][0]["task"])
        _, rs = self.call("GET", "/v1/repos")
        self.assertEqual([{"repo": "me/app", "branch": "main"}], rs["repos"])

    def test_start_refuses_a_bad_repo_or_an_empty_task(self):
        self.assertEqual(400, self.call("POST", "/v1/cloud", {"repo": "not a repo", "task": "x"})[0])
        self.assertEqual(400, self.call("POST", "/v1/cloud", {"repo": "me/app", "task": "  "})[0])
        status, body = self.call("POST", "/v1/cloud", {"repo": "me/missing", "task": "x"})
        self.assertEqual(502, status)
        self.assertIn("git clone failed", body["error"])

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
