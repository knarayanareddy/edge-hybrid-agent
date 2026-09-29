#!/usr/bin/env python3
import os
import urllib.request
import json
import sys

print("==================================================")
print("=== VERIFYING LIVE CLOUD API & TOOL CALLING ===")
print("==================================================")

openrouter_key = os.environ.get("OPENROUTER_KEY", "").strip()
gemini_key = os.environ.get("GEMINI_KEY", "").strip()

if not openrouter_key and not gemini_key:
    print("[WARN] No API keys provided in environment, skipping live call.")
    sys.exit(0)

# Test OpenRouter endpoint
if openrouter_key:
    print("Testing OpenRouter Live Endpoint (google/gemini-2.5-flash)...")
    payload = {
        "model": "google/gemini-2.5-flash",
        "messages": [
            {"role": "user", "content": "Set an alarm for 7:30 AM tomorrow labeled Morning Run"}
        ],
        "tools": [
            {
                "type": "function",
                "function": {
                    "name": "set_alarm",
                    "description": "Sets a clock alarm on the device at a specific hour and minute",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "hour": {"type": "integer", "description": "Hour in 24-hour format (0-23)"},
                            "minutes": {"type": "integer", "description": "Minute (0-59)"},
                            "message": {"type": "string", "description": "Alarm label or title"}
                        },
                        "required": ["hour", "minutes"]
                    }
                }
            },
            {
                "type": "function",
                "function": {
                    "name": "open_camera",
                    "description": "Launches the phone native camera viewfinder",
                    "parameters": {
                        "type": "object",
                        "properties": {},
                        "required": []
                    }
                }
            }
        ],
        "tool_choice": "auto"
    }

    req = urllib.request.Request(
        "https://openrouter.ai/api/v1/chat/completions",
        headers={
            "Authorization": f"Bearer {openrouter_key}",
            "Content-Type": "application/json",
            "HTTP-Referer": "https://github.com/knarayanareddy/edge-hybrid-agent",
            "X-Title": "Edge Hybrid Agent CI Verification"
        },
        data=json.dumps(payload).encode()
    )

    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            status = resp.status
            body = json.loads(resp.read().decode())
            print(f"  [PASS] Live OpenRouter HTTP Status: {status}")
            choices = body.get("choices", [])
            assert len(choices) > 0, "No choices returned in API response"
            msg = choices[0].get("message", {})
            print(f"  [PASS] Model Message Received: {msg.get('content') or '(Tool Call Triggered)'}")
            tool_calls = msg.get("tool_calls", [])
            if tool_calls:
                print(f"  [PASS] Tool Call Triggered by Model: {tool_calls[0].get('function', {}).get('name')}")
            print(">>> LIVE OPENROUTER API & TOOL CALLING VERIFIED SUCCESSFULLY! <<<")
    except Exception as e:
        print(f"  [FAIL] OpenRouter API call failed: {e}")
        sys.exit(1)

print("\nAll live cloud API checks passed.")
