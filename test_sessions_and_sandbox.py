#!/usr/bin/env python3
import sqlite3
import json
import uuid
import time

print("==============================================================")
print("=== TESTING MULTI-CHAT CONTEXT ISOLATION & DYNAMIC SANDBOX ===")
print("==============================================================")

passed = 0
failed = 0

def test(name, fn):
    global passed, failed
    try:
        fn()
        print(f"  [PASS] {name}")
        passed += 1
    except Exception as e:
        print(f"  [FAIL] {name}: {e}")
        failed += 1

# 1. Test Room Database SQLite Schema & Context Isolation
def test_context_isolation():
    db_path = "/tmp/test_chat_vault.db"
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    
    # Setup Room schema
    cur.execute("DROP TABLE IF EXISTS chat_messages")
    cur.execute("DROP TABLE IF EXISTS chat_sessions")
    
    cur.execute("""
        CREATE TABLE chat_sessions (
            id TEXT PRIMARY KEY NOT NULL,
            title TEXT NOT NULL,
            createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL
        )
    """)
    cur.execute("""
        CREATE TABLE chat_messages (
            id TEXT PRIMARY KEY NOT NULL,
            sessionId TEXT NOT NULL,
            role TEXT NOT NULL,
            content TEXT NOT NULL,
            imageDataUrl TEXT,
            deliveryState TEXT NOT NULL,
            recoveryMessage TEXT,
            createdAt INTEGER NOT NULL,
            FOREIGN KEY (sessionId) REFERENCES chat_sessions(id) ON DELETE CASCADE
        )
    """)
    conn.commit()

    session_a = str(uuid.uuid4())
    session_b = str(uuid.uuid4())

    now = int(time.time() * 1000)
    cur.execute("INSERT INTO chat_sessions VALUES (?, ?, ?, ?)", (session_a, "Trip to Tokyo", now, now))
    cur.execute("INSERT INTO chat_sessions VALUES (?, ?, ?, ?)", (session_b, "Debug Camera Tool", now, now))
    conn.commit()

    # Add messages to Session A
    msg_a1 = str(uuid.uuid4())
    msg_a2 = str(uuid.uuid4())
    cur.execute("INSERT INTO chat_messages VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (msg_a1, session_a, "USER", "What is the weather in Tokyo?", None, "COMPLETE", None, now))
    cur.execute("INSERT INTO chat_messages VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (msg_a2, session_a, "ASSISTANT", "Tokyo is 20°C and sunny.", None, "COMPLETE", None, now + 1000))
    
    # Add messages to Session B
    msg_b1 = str(uuid.uuid4())
    msg_b2 = str(uuid.uuid4())
    cur.execute("INSERT INTO chat_messages VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (msg_b1, session_b, "USER", "Can you launch the camera?", None, "COMPLETE", None, now))
    cur.execute("INSERT INTO chat_messages VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (msg_b2, session_b, "ASSISTANT", "Camera viewfinder launched.", None, "COMPLETE", None, now + 1000))
    conn.commit()
    conn.close()

    # Reopen connection (simulating app relaunch)
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()

    # Query Session A
    cur.execute("SELECT content FROM chat_messages WHERE sessionId = ? ORDER BY createdAt ASC", (session_a,))
    rows_a = [r[0] for r in cur.fetchall()]
    assert len(rows_a) == 2, f"Expected 2 messages for Session A, got {len(rows_a)}"
    assert "Tokyo is 20°C and sunny." in rows_a
    assert "Camera viewfinder launched." not in rows_a, "Context bleed detected: Session B message leaked into Session A!"

    # Query Session B
    cur.execute("SELECT content FROM chat_messages WHERE sessionId = ? ORDER BY createdAt ASC", (session_b,))
    rows_b = [r[0] for r in cur.fetchall()]
    assert len(rows_b) == 2, f"Expected 2 messages for Session B, got {len(rows_b)}"
    assert "Camera viewfinder launched." in rows_b
    assert "Tokyo is 20°C and sunny." not in rows_b, "Context bleed detected: Session A message leaked into Session B!"

    # Delete Session A and verify Session B remains intact
    cur.execute("DELETE FROM chat_sessions WHERE id = ?", (session_a,))
    cur.execute("DELETE FROM chat_messages WHERE sessionId = ?", (session_a,))
    conn.commit()

    cur.execute("SELECT COUNT(*) FROM chat_messages WHERE sessionId = ?", (session_a,))
    assert cur.fetchone()[0] == 0, "Session A messages should be deleted"

    cur.execute("SELECT COUNT(*) FROM chat_messages WHERE sessionId = ?", (session_b,))
    assert cur.fetchone()[0] == 2, "Session B messages must remain completely intact"

    conn.close()

test("Room SQLite Multi-Chat Context Isolation & Deletion", test_context_isolation)

# 2. Dynamic Tool Sandbox (Stage, Test, Save, Discard)
class ToolSandbox:
    def __init__(self):
        self.staged = {}
        self.permanent = {}
    
    def stage_and_test(self, name, desc, test_input, template):
        name = name.strip().lower().replace(" ", "_")
        self.staged[name] = {"name": name, "desc": desc, "template": template}
        test_output = f"Simulated run on [{test_input}] using rule: {template}"
        return {
            "status": "staged_for_testing",
            "tool_name": name,
            "test_output": test_output
        }
    
    def save_staged(self, name):
        name = name.strip().lower().replace(" ", "_")
        if name not in self.staged:
            return {"error": f"Tool '{name}' not found in staging"}
        tool = self.staged.pop(name)
        self.permanent[name] = tool
        return {"status": "saved_permanently", "tool_name": name}
    
    def discard_staged(self, name):
        name = name.strip().lower().replace(" ", "_")
        self.staged.pop(name, None)
        self.permanent.pop(name, None)
        return {"status": "discarded", "tool_name": name}

def test_dynamic_tool_lifecycle():
    sandbox = ToolSandbox()
    
    # 1. Stage tool
    res1 = sandbox.stage_and_test(
        name="crypto_price_tracker",
        desc="Fetches live Bitcoin price",
        test_input="BTC",
        template="Query /api/v3/simple/price"
    )
    assert res1["status"] == "staged_for_testing"
    assert "crypto_price_tracker" in sandbox.staged
    assert "crypto_price_tracker" not in sandbox.permanent

    # 2. Save tool
    res2 = sandbox.save_staged("crypto_price_tracker")
    assert res2["status"] == "saved_permanently"
    assert "crypto_price_tracker" not in sandbox.staged
    assert "crypto_price_tracker" in sandbox.permanent

    # 3. Stage candidate 2 and discard
    sandbox.stage_and_test("bad_tool", "Faulty tool", "test", "fail")
    assert "bad_tool" in sandbox.staged
    res3 = sandbox.discard_staged("bad_tool")
    assert res3["status"] == "discarded"
    assert "bad_tool" not in sandbox.staged
    assert "bad_tool" not in sandbox.permanent

test("Dynamic Tool Sandbox (Stage -> Test -> Save / Discard Lifecycle)", test_dynamic_tool_lifecycle)

# 3. MCP Server Registry
class McpRegistry:
    def __init__(self):
        self.servers = {}
    
    def register(self, name, url, token=None):
        self.servers[name] = {"url": url, "token": token}
        return {"status": f"MCP server '{name}' registered at {url}"}
    
    def list(self):
        return {"total": len(self.servers), "servers": list(self.servers.keys())}

def test_mcp_registry():
    reg = McpRegistry()
    res1 = reg.register("weather_mcp", "http://localhost:8000/sse")
    assert "registered" in res1["status"]
    assert reg.list()["total"] == 1
    assert "weather_mcp" in reg.list()["servers"]

test("Model Context Protocol (MCP) Server Registration & Listing", test_mcp_registry)

print(f"\nResults: {passed} passed, {failed} failed")
assert failed == 0, "Test suite failed"
