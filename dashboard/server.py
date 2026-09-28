#!/usr/bin/env python3
import http.server
import json
import os
import re
import socketserver
import subprocess
from pathlib import Path

PORT = 9229
REPO_DIR = Path(__file__).resolve().parent.parent
LOG_FILE = REPO_DIR / "logs" / "autonomous_builder.log"
STATIC_DIR = Path(__file__).resolve().parent / "static"

PHASES_META = [
    {"id": 1, "title": "Production Cloud Engine & Recursive Loop", "desc": "SSE streaming client, recursive tool orchestration, Android Keystore, Hilt DI"},
    {"id": 2, "title": "Native Tool Execution & Headless Sandbox", "desc": "Local Room DB, MCP tool bridge, foreground gate, sandbox validation"},
    {"id": 3, "title": "TypeSafe JEV Guardrails & Learning", "desc": "Constitutional policy validator, telemetry recorder, self-healing reflection"},
    {"id": 4, "title": "Galaxy S23 Ultra S Pen & Vision", "desc": "Air Actions, low-latency stylus drawing, bitmap encoder, multimodal query"},
    {"id": 5, "title": "Offline LiteRT & On-Device RAG", "desc": "LiteRT NPU acceleration, on-device embeddings, sqlite-vec offline retrieval"}
]

def check_builder_running():
    try:
        res = subprocess.run(["pgrep", "-f", "autonomous_builder.py"], capture_output=True, text=True)
        return bool(res.stdout.strip())
    except Exception:
        return False

def get_git_info():
    try:
        commit = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=REPO_DIR, capture_output=True, text=True).stdout.strip()
        msg = subprocess.run(["git", "log", "-1", "--pretty=%B"], cwd=REPO_DIR, capture_output=True, text=True).stdout.strip().split("\n")[0]
        branch = subprocess.run(["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=REPO_DIR, capture_output=True, text=True).stdout.strip()
        remote = subprocess.run(["git", "remote", "get-url", "origin"], cwd=REPO_DIR, capture_output=True, text=True).stdout.strip()
        return {"commit": commit, "message": msg, "branch": branch, "remote": remote}
    except Exception as e:
        return {"commit": "unknown", "message": str(e), "branch": "main", "remote": ""}

def parse_logs():
    if not LOG_FILE.exists():
        return {
            "phases": [{"id": p["id"], "title": p["title"], "desc": p["desc"], "status": "pending", "score": None, "files": 0, "attempt": 1} for p in PHASES_META],
            "current_phase": 1,
            "active_provider": "space-bunny-alpha",
            "last_log_lines": ["Waiting for logs..."]
        }

    lines = LOG_FILE.read_text(encoding="utf-8", errors="replace").splitlines()
    phases_state = {p["id"]: {"id": p["id"], "title": p["title"], "desc": p["desc"], "status": "pending", "score": None, "files": 0, "attempt": 1, "flaws": []} for p in PHASES_META}
    
    current_phase = 1
    active_provider = "openrouter/stealth/space-bunny-alpha"

    for line in lines:
        m_start = re.search(r"STARTING EXECUTION: Phase (\d+)", line)
        if m_start:
            p_id = int(m_start.group(1))
            current_phase = p_id
            phases_state[p_id]["status"] = "in_progress"

        m_att = re.search(r"\[Phase (\d+)\] Attempt (\d+)/(\d+)", line)
        if m_att:
            p_id = int(m_att.group(1))
            phases_state[p_id]["attempt"] = int(m_att.group(2))

        m_prov = re.search(r"Synthesis received from provider: ([^\s]+)", line)
        if m_prov:
            active_provider = m_prov.group(1)

        m_files = re.search(r"\[Phase (\d+)\] Extracted (\d+) file", line)
        if m_files:
            p_id = int(m_files.group(1))
            phases_state[p_id]["files"] = int(m_files.group(2))

        m_static_fail = re.search(r"\[Phase (\d+)\] Static check failed: \[(.*)\]", line)
        if m_static_fail:
            p_id = int(m_static_fail.group(1))
            phases_state[p_id]["status"] = "gate_rejection"
            phases_state[p_id]["last_issue"] = m_static_fail.group(2).strip("'\"")

        m_eval_pass = re.search(r"\[Phase (\d+)\] Reviewer ACCEPTED with score (\d+)/100", line)
        if m_eval_pass:
            p_id = int(m_eval_pass.group(1))
            phases_state[p_id]["status"] = "completed"
            phases_state[p_id]["score"] = int(m_eval_pass.group(2))

        m_push = re.search(r"\[Phase (\d+)\] Pushed to GitHub", line)
        if m_push:
            p_id = int(m_push.group(1))
            phases_state[p_id]["status"] = "completed"
            phases_state[p_id]["pushed"] = True

    return {
        "phases": list(phases_state.values()),
        "current_phase": current_phase,
        "active_provider": active_provider,
        "last_log_lines": lines[-40:] if lines else []
    }

class DashboardHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(STATIC_DIR), **kwargs)

    def do_GET(self):
        if self.path == "/api/status":
            parsed = parse_logs()
            is_running = check_builder_running()
            git_info = get_git_info()
            data = {
                "running": is_running,
                "git": git_info,
                **parsed
            }
            body = json.dumps(data).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(body)
        else:
            super().do_GET()

if __name__ == "__main__":
    STATIC_DIR.mkdir(parents=True, exist_ok=True)
    with socketserver.TCPServer(("127.0.0.1", PORT), DashboardHandler) as httpd:
        print(f"Edge Hybrid Agent Live Dashboard running on http://127.0.0.1:{PORT}")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            pass
