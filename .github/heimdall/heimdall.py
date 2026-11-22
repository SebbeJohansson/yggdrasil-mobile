#!/usr/bin/env python3
"""Heimdall for Yggdrasil Mobile: the security watchman, as a GitHub Action.

The desktop's Heimdall watches the machine it runs on. The phone app ships as
an APK built here, so the checks that matter for it run on the repo, on a
schedule: dependencies with known vulnerabilities (OSV), keys committed by
mistake (gitleaks, every commit), and the app's own security settings. What
it finds goes into one GitHub issue labelled `heimdall`, rewritten each run
and closed when all is clear; the app reads that issue and raises an alert
in Gjallar for each finding (update/Heimdall.kt).

    python3 heimdall.py collect   reads the scanners' reports, checks the repo, writes findings.json
    python3 heimdall.py report    writes findings.json into the issue (needs GITHUB_TOKEN, GITHUB_REPOSITORY)

Standard library only. A finding never carries a secret: gitleaks runs with
--redact and only the rule, file, line and commit are kept.
"""

import json
import os
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(os.environ.get("HEIMDALL_ROOT", ".")).resolve()
OUT = ROOT / "findings.json"
LABEL = "heimdall"
TITLE = "Heimdall: security findings"
# Read back by the app; keep this marker and the JSON inside it stable.
MARKER = "heimdall-findings"


def severity_of_osv(vuln: dict) -> str:
    """OSV's database severity when it gives one, else medium."""
    s = (vuln.get("database_specific") or {}).get("severity", "")
    s = str(s).lower()
    return {"critical": "critical", "high": "high", "moderate": "medium", "medium": "medium", "low": "low"}.get(s, "medium")


def from_osv(path: Path) -> list:
    """osv-scanner's JSON: one finding per vulnerable package and advisory."""
    if not path.is_file() or not path.read_text().strip():
        return []
    data = json.loads(path.read_text())
    found = []
    for result in data.get("results", []):
        for pkg in result.get("packages", []):
            p = pkg.get("package", {})
            name, version = p.get("name", "?"), p.get("version", "?")
            for v in pkg.get("vulnerabilities", []):
                vid = v.get("id", "?")
                found.append({
                    "id": f"dep:{name}:{vid}",
                    "kind": "dependency",
                    "severity": severity_of_osv(v),
                    "title": f"{name} {version}: {vid}",
                    "detail": (v.get("summary") or v.get("details") or "")[:300],
                    "link": f"https://osv.dev/vulnerability/{vid}",
                })
    return found


def from_gitleaks(path: Path) -> list:
    """gitleaks' JSON (run with --redact): one finding per leak, without the secret."""
    if not path.is_file() or not path.read_text().strip():
        return []
    found = []
    for leak in json.loads(path.read_text()) or []:
        commit = (leak.get("Commit") or "")[:10]
        file, line, rule = leak.get("File", "?"), leak.get("StartLine", 0), leak.get("RuleID", "secret")
        found.append({
            "id": f"secret:{leak.get('Fingerprint') or f'{commit}:{file}:{rule}:{line}'}",
            "kind": "secret",
            "severity": "critical",
            "title": f"A {rule} in {file}:{line}",
            "detail": f"commit {commit}: {(leak.get('Message') or '').splitlines()[0][:120] if leak.get('Message') else ''}".strip(),
            "link": "",
        })
    return found


def config_checks(root: Path) -> list:
    """The app's own settings that keep it closed: the bridge on loopback, no
    backups of its data, no plain-HTTP traffic, nothing debuggable shipped."""
    found = []
    bridge = root / "app/src/main/assets/bridge/ygg_bridge.py"
    if bridge.is_file():
        m = re.search(r'HOST\s*=\s*os\.environ\.get\("YGG_BRIDGE_HOST",\s*"([^"]+)"\)', bridge.read_text())
        if not m or m.group(1) not in ("127.0.0.1", "localhost", "::1"):
            found.append({"id": "config:bridge-host", "kind": "config", "severity": "high",
                          "title": "The Claude bridge listens beyond loopback by default",
                          "detail": "ygg_bridge.py's HOST should default to 127.0.0.1", "link": ""})
    manifest = root / "app/src/main/AndroidManifest.xml"
    if manifest.is_file():
        text = manifest.read_text()
        if 'android:allowBackup="true"' in text or "android:allowBackup" not in text:
            found.append({"id": "config:allow-backup", "kind": "config", "severity": "medium",
                          "title": "App data can be backed up off the phone",
                          "detail": "AndroidManifest.xml should set android:allowBackup=\"false\": tokens and memory live there", "link": ""})
        if 'android:usesCleartextTraffic="true"' in text:
            found.append({"id": "config:cleartext", "kind": "config", "severity": "medium",
                          "title": "Plain-HTTP traffic is allowed",
                          "detail": "AndroidManifest.xml sets usesCleartextTraffic=\"true\"", "link": ""})
        if 'android:debuggable="true"' in text:
            found.append({"id": "config:debuggable", "kind": "config", "severity": "high",
                          "title": "The manifest makes every build debuggable", "detail": "", "link": ""})
    return found


def collect() -> list:
    findings = from_osv(ROOT / "osv.json") + from_gitleaks(ROOT / "gitleaks.json") + config_checks(ROOT)
    # One finding per id, the worst first.
    order = {"critical": 0, "high": 1, "medium": 2, "low": 3}
    unique = {f["id"]: f for f in findings}
    findings = sorted(unique.values(), key=lambda f: (order.get(f["severity"], 9), f["id"]))
    OUT.write_text(json.dumps(findings, indent=1))
    print(f"{len(findings)} findings")
    return findings


def body(findings: list, run_url: str) -> str:
    lines = [f"Heimdall's last look, [this run]({run_url}): **{len(findings)}** finding{'s' if len(findings) != 1 else ''}.", "",
             "| Severity | What | Where |", "| --- | --- | --- |"]
    for f in findings:
        what = f["title"].replace("|", "\\|")
        where = f"[{f['kind']}]({f['link']})" if f.get("link") else f["kind"]
        lines.append(f"| {f['severity']} | {what} | {where} |")
    lines += ["", "Rewritten on every run, closed when all is clear. The app reads the block below.", "",
              f"<!-- {MARKER}", json.dumps(findings), "-->"]
    return "\n".join(lines)


def api(method: str, path: str, payload=None):
    req = urllib.request.Request("https://api.github.com" + path, method=method,
                                 data=json.dumps(payload).encode() if payload is not None else None)
    req.add_header("Authorization", "Bearer " + os.environ["GITHUB_TOKEN"])
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    with urllib.request.urlopen(req, timeout=30) as r:
        text = r.read().decode()
        return json.loads(text) if text else None


def report() -> None:
    repo = os.environ["GITHUB_REPOSITORY"]
    findings = json.loads(OUT.read_text()) if OUT.is_file() else []
    run_url = f"{os.environ.get('GITHUB_SERVER_URL', 'https://github.com')}/{repo}/actions/runs/{os.environ.get('GITHUB_RUN_ID', '')}"
    issues = api("GET", f"/repos/{repo}/issues?labels={LABEL}&state=all&per_page=10") or []
    issue = next((i for i in issues if i.get("title") == TITLE and "pull_request" not in i), None)
    if findings:
        payload = {"title": TITLE, "body": body(findings, run_url), "labels": [LABEL], "state": "open"}
        if issue:
            api("PATCH", f"/repos/{repo}/issues/{issue['number']}", payload)
        else:
            api("POST", f"/repos/{repo}/issues", {k: v for k, v in payload.items() if k != "state"})
        print(f"issue updated: {len(findings)} findings")
    elif issue and issue.get("state") == "open":
        api("PATCH", f"/repos/{repo}/issues/{issue['number']}", {"body": body([], run_url), "state": "closed"})
        print("all clear: issue closed")
    else:
        print("all clear")


if __name__ == "__main__":
    what = sys.argv[1] if len(sys.argv) > 1 else ""
    if what == "collect":
        collect()
    elif what == "report":
        report()
    else:
        print(__doc__)
        sys.exit(2)
