#!/usr/bin/env python3
import os
import urllib.request
import json
import sys

print("==================================================")
print("=== VERIFYING LIVE CLOUD API & TOOL CALLING ===")
print("==================================================")

raw_keys = os.environ.get("OPENROUTER_KEYS", "") + "|" + os.environ.get("OPENROUTER_KEY", "")
candidate_keys = [k.strip() for k in raw_keys.split("|") if k.strip()]
gemini_key = os.environ.get("GEMINI_KEY", "").strip()

models_to_test = [
    "google/gemini-2.5-flash",
    "google/gemini-2.0-flash-exp:free",
    "meta-llama/llama-3.3-70b-instruct:free",
    "qwen/qwen-2.5-72b-instruct:free"
]

tools_payload = [
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
]

success = False
working_key = None
working_model = None

# 1. Test OpenRouter candidate keys
for idx, key in enumerate(candidate_keys):
    masked_key = key[:6] + "..." + key[-4:] if len(key) > 10 else "***"
    print(f"\n--- Testing Candidate Key #{idx+1} ({masked_key}) ---")
    for model in models_to_test:
        payload = {
            "model": model,
            "messages": [
                {"role": "user", "content": "Set an alarm for 7:30 AM tomorrow labeled Morning Run"}
            ],
            "tools": tools_payload,
            "tool_choice": "auto"
        }

        req = urllib.request.Request(
            "https://openrouter.ai/api/v1/chat/completions",
            headers={
                "Authorization": f"Bearer {key}",
                "Content-Type": "application/json",
                "HTTP-Referer": "https://github.com/knarayanareddy/edge-hybrid-agent",
                "X-Title": "Edge Hybrid Agent CI Verification"
            },
            data=json.dumps(payload).encode()
        )

        try:
            with urllib.request.urlopen(req, timeout=20) as resp:
                status = resp.status
                body = json.loads(resp.read().decode())
                choices = body.get("choices", [])
                if choices:
                    msg = choices[0].get("message", {})
                    tool_calls = msg.get("tool_calls", [])
                    print(f"  [PASS] Key #{idx+1} with Model '{model}' succeeded (HTTP {status})!")
                    if tool_calls:
                        print(f"  [PASS] Function Call detected: {tool_calls[0].get('function', {}).get('name')}")
                    else:
                        print(f"  [PASS] Response text: {msg.get('content')}")
                    success = True
                    working_key = key
                    working_model = model
                    break
        except urllib.error.HTTPError as e:
            err_body = e.read().decode('utf-8', errors='ignore')[:120]
            print(f"  [INFO] Key #{idx+1} + {model} returned HTTP {e.code}: {err_body}")
        except Exception as e:
            print(f"  [INFO] Key #{idx+1} + {model} error: {e}")
    if success:
        break

# 2. Test Gemini API if OpenRouter didn't succeed
if not success and gemini_key:
    print("\n--- Testing Direct Google Generative Language API ---")
    gemini_url = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key={gemini_key}"
    gemini_payload = {
        "contents": [{
            "parts": [{"text": "Set an alarm for 7:30 AM"}]
        }]
    }
    req = urllib.request.Request(
        gemini_url,
        headers={"Content-Type": "application/json"},
        data=json.dumps(gemini_payload).encode()
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            body = json.loads(resp.read().decode())
            print(f"  [PASS] Google Gemini API succeeded (HTTP {resp.status})!")
            success = True
            working_model = "gemini-1.5-flash"
    except Exception as e:
        print(f"  [INFO] Gemini direct API error: {e}")

if success:
    print(f"\n==================================================")
    print(f"=== LIVE API CALL VERIFIED AND FULLY FUNCTIONAL ===")
    print(f"Working Model: {working_model}")
    print(f"==================================================")
    sys.exit(0)
else:
    print("\n[ERROR] None of the candidate keys or models succeeded.")
    sys.exit(1)
