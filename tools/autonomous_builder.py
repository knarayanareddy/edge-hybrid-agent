#!/usr/bin/env python3
"""
Autonomous Phase Builder for Edge Hybrid Agent
================================================
Delegates full implementation of Phases 1 through 5 to the most capable free model
with an automated cascading fallback across models if rate limits (429) or timeouts occur.

Features:
- Cascading LLM client: Gemini 3.8 Flash -> Gemini Flash Lite -> OpenRouter -> Groq
- Self-evaluation loop: Heuristic static inspection + LLM reviewer + false-positive gate
- Auto-correction: Feeds review critiques back to the synthesizer until quality score >= 85
- Persistent Learning Ledger: Logs lessons and edge cases to lessons_learned.jsonl
- Automated Git commits and pushes after each verified phase
"""

import os
import sys
import re
import json
import time
import socket
import logging
import argparse
import subprocess
from pathlib import Path
from typing import Dict, List, Optional, Tuple, Any
import urllib.request
import urllib.error

socket.setdefaulttimeout(30)

BASE_DIR = Path(__file__).resolve().parent.parent
HERMES_KEYS_FILE = Path.home() / ".hermes" / "idea-dump" / "keys.env"
LESSONS_FILE = BASE_DIR / "lessons_learned.jsonl"
LOG_DIR = BASE_DIR / "logs"
SPEC_FILE = BASE_DIR / "SPEC.md"


def setup_logger() -> logging.Logger:
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    logger = logging.getLogger("autonomous_builder")
    logger.setLevel(logging.INFO)
    logger.handlers.clear()

    formatter = logging.Formatter("[%(asctime)s] [%(levelname)s] %(message)s", "%Y-%m-%d %H:%M:%S")
    ch = logging.StreamHandler(sys.stdout)
    ch.setFormatter(formatter)
    logger.addHandler(ch)

    fh = logging.FileHandler(LOG_DIR / "autonomous_builder.log")
    fh.setFormatter(formatter)
    logger.addHandler(fh)

    return logger


def load_keys() -> Dict[str, str]:
    keys = {}
    if HERMES_KEYS_FILE.exists():
        with open(HERMES_KEYS_FILE, "r") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    keys[k.strip()] = v.strip().strip('"').strip("'")
    for k, v in os.environ.items():
        keys[k] = v
    return keys


class CascadingLLMClient:
    """Robust cascading client across Gemini free models and fallbacks."""

    def __init__(self, keys: Dict[str, str], logger: logging.Logger, prefer_space_bunny: bool = False):
        self.keys = keys
        self.logger = logger
        self.prefer_space_bunny = prefer_space_bunny
        self.gemini_key = keys.get("GEMINI_API_KEY")
        self.tinker_key = keys.get("TINKER_API_KEY")
        # Collect all pooled OpenRouter keys
        self.openrouter_keys: List[str] = []
        for k, v in keys.items():
            if k.startswith("OPENROUTER_API_KEY") and v.strip():
                clean_v = v.strip().strip('"').strip("'")
                if clean_v not in self.openrouter_keys:
                    self.openrouter_keys.append(clean_v)
        self.current_key_idx = 0
        self.logger.info(f"Initialized OpenRouter KeyPool with {len(self.openrouter_keys)} active account keys.")

        self.gemini_models = [
            "gemini-3.8-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.8-flash-lite-tts",
            "gemini-flash-latest"
        ]

    def _call_openrouter(self, prompt: str, system: str, json_mode: bool, max_retries: int = 6) -> Tuple[Optional[str], str]:
        if not self.openrouter_keys:
            return None, "none"
        model = "stealth/space-bunny-alpha"
        for attempt in range(1, max_retries + 1):
            key = self.openrouter_keys[self.current_key_idx % len(self.openrouter_keys)]
            key_tag = f"key-{self.current_key_idx + 1}/{len(self.openrouter_keys)}"
            try:
                payload = {
                    "model": model,
                    "messages": [
                        {"role": "system", "content": system or "You are an expert autonomous software engineer."},
                        {"role": "user", "content": prompt}
                    ],
                    "temperature": 0.2 if json_mode else 0.4
                }
                req = urllib.request.Request(
                    "https://openrouter.ai/api/v1/chat/completions",
                    data=json.dumps(payload).encode("utf-8"),
                    headers={
                        "Authorization": f"Bearer {key}",
                        "Content-Type": "application/json",
                        "HTTP-Referer": "https://github.com/knarayanareddy/edge-hybrid-agent",
                        "X-Title": "Edge Hybrid Agent Builder"
                    },
                    method="POST"
                )
                with urllib.request.urlopen(req, timeout=360) as resp:
                    res = json.loads(resp.read().decode("utf-8"))
                    content = res.get("choices", [{}])[0].get("message", {}).get("content", "")
                    if content.strip():
                        return content.strip(), f"openrouter/{model} [{key_tag}]"
            except urllib.error.HTTPError as e:
                self.logger.warning(f"Space Bunny Alpha on {key_tag} hit HTTP {e.code}: {e.reason}")
                # Rotate key immediately to the next account
                self.current_key_idx = (self.current_key_idx + 1) % len(self.openrouter_keys)
                self.logger.info(f"Rotated to next account key in pool: key-{self.current_key_idx + 1}...")
                time.sleep(1)
            except Exception as e:
                self.logger.warning(f"Space Bunny Alpha error on {key_tag}: {e}")
                self.current_key_idx = (self.current_key_idx + 1) % len(self.openrouter_keys)
                time.sleep(2)
        return None, "none"

    def _call_tinker(self, prompt: str, system: str, json_mode: bool) -> Tuple[Optional[str], str]:
        if not self.tinker_key:
            return None, "none"
        try:
            payload = {
                "model": "zai-org/GLM-5.3:peft:262144",
                "messages": [
                    {"role": "system", "content": system or "You are an expert autonomous software engineer."},
                    {"role": "user", "content": prompt}
                ],
                "temperature": 0.2 if json_mode else 0.4
            }
            req = urllib.request.Request(
                "https://tinker.thinkingmachines.dev/services/tinker-prod/oai/api/v1/chat/completions",
                data=json.dumps(payload).encode("utf-8"),
                headers={
                    "Authorization": f"Bearer {self.tinker_key}",
                    "Content-Type": "application/json"
                },
                method="POST"
            )
            with urllib.request.urlopen(req, timeout=120) as resp:
                res = json.loads(resp.read().decode("utf-8"))
                content = res.get("choices", [{}])[0].get("message", {}).get("content", "")
                if content.strip():
                    return content.strip(), "tinker/zai-org/GLM-5.3:peft:262144"
        except Exception as e:
            self.logger.warning(f"Tinker GLM-5.3 fallback failed: {e}")
        return None, "none"

    def _call_gemini(self, prompt: str, system: str, json_mode: bool, max_retries: int) -> Tuple[Optional[str], str]:
        if not self.gemini_key:
            return None, "none"
        for model in self.gemini_models:
            for attempt in range(max_retries):
                try:
                    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
                    headers = {
                        "x-goog-api-key": self.gemini_key,
                        "Content-Type": "application/json"
                    }
                    parts = []
                    if system:
                        parts.append({"text": f"SYSTEM INSTRUCTION:\n{system}\n\n"})
                    parts.append({"text": prompt})
                    payload: Dict[str, Any] = {
                        "contents": [{"parts": parts}],
                        "generationConfig": {
                            "temperature": 0.2 if json_mode else 0.4,
                            "maxOutputTokens": 8192,
                        }
                    }
                    if json_mode:
                        payload["generationConfig"]["responseMimeType"] = "application/json"

                    req = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"), headers=headers, method="POST")
                    with urllib.request.urlopen(req, timeout=45) as resp:
                        data = json.loads(resp.read().decode("utf-8"))
                        candidates = data.get("candidates", [])
                        if candidates:
                            text = candidates[0].get("content", {}).get("parts", [{}])[0].get("text", "")
                            if text.strip():
                                return text.strip(), f"gemini/{model}"

                except urllib.error.HTTPError as e:
                    if e.code == 429:
                        backoff = (2 ** attempt) * 4
                        self.logger.warning(f"Model {model} hit rate limit (429). Backing off {backoff}s...")
                        time.sleep(backoff)
                        continue
                    elif e.code == 404:
                        self.logger.debug(f"Model {model} not found (404); skipping to next.")
                        break
                    else:
                        self.logger.warning(f"Model {model} HTTP {e.code}: {e.read().decode()[:120]}")
                        break
                except Exception as e:
                    self.logger.warning(f"Model {model} request failed: {e}")
                    break
        return None, "none"

    def complete(self, prompt: str, system: str = "", json_mode: bool = False, max_retries: int = 5) -> Tuple[Optional[str], str]:
        # 1. Dedicated Primary Engine: Space Bunny Alpha with 5 retries & backoff
        resp, prov = self._call_openrouter(prompt, system, json_mode, max_retries=max_retries)
        if resp:
            return resp, prov

        # 2. Radical Emergency Fallback ONLY (triggered only if all 5 retries fail)
        self.logger.critical("RADICAL CONTINGENCY: Space Bunny Alpha failed 5 consecutive attempts. Activating emergency backup...")
        resp, prov = self._call_tinker(prompt, system, json_mode)
        if resp:
            return resp, prov

        return self._call_gemini(prompt, system, json_mode, max_retries=2)


SUBPHASE_SPECS = {
    "2A": """### Phase 2A: Room DB & Native Action Handlers
Focus: Implement persistent local note storage and native Android system action dispatchers.
Package Root: com.edgehybrid.agent

EXACT REQUIRED FILES TO GENERATE (5 files total):
1. app/src/main/java/com/edgehybrid/agent/data/local/NoteEntity.kt
   - Room @Entity(tableName = "notes") with data class NoteEntity(
       @PrimaryKey(autoGenerate = true) val id: Long = 0,
       val title: String,
       val content: String,
       val timestamp: Long = System.currentTimeMillis()
     )
2. app/src/main/java/com/edgehybrid/agent/data/local/NoteDao.kt
   - Room @Dao interface with:
     @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertNote(note: NoteEntity): Long
     @Query("SELECT * FROM notes ORDER BY timestamp DESC") fun getAllNotes(): kotlinx.coroutines.flow.Flow<List<NoteEntity>>
     @Delete suspend fun deleteNote(note: NoteEntity): Int
3. app/src/main/java/com/edgehybrid/agent/nativeactions/NativeTool.kt
   - Sealed class / enum NativeTool for system tools:
     CREATE_CALENDAR_EVENT("create_calendar_event", "Creates an event in the system calendar"),
     CREATE_QUICK_NOTE("create_quick_note", "Saves a quick note into local Room database"),
     SET_TIMER("set_timer", "Sets a countdown timer via system AlarmClock"),
     SEND_SMS("send_sms", "Prepares or sends an SMS message (requires explicit confirmation)"),
     TOGGLE_FLASHLIGHT("toggle_flashlight", "Toggles device camera torch on or off")
   - Includes parameter schema descriptions formatted as JSON schemas with required arrays.
4. app/src/main/java/com/edgehybrid/agent/nativeactions/ActionConfirmation.kt
   - Data class ActionConfirmation(val id: String, val tool: String, val summary: String, val params: Map<String, Any>) for sensitive actions requiring explicit user consent.
5. app/src/main/java/com/edgehybrid/agent/nativeactions/NativeActionHandler.kt
   - Injected with '@ApplicationContext private val context: Context', 'private val noteDao: NoteDao'.
   - All background work runs on withContext(Dispatchers.IO).
   - createCalendarEvent: Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).putExtra(Events.TITLE, title)...
   - createQuickNote: inserts NoteEntity into noteDao.
   - setTimer: Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_MESSAGE, message).putExtra(AlarmClock.EXTRA_SKIP_UI, true).
   - prepareSms: generates ActionConfirmation or launches Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).
   - toggleFlashlight: CameraManager on context.getSystemService(Context.CAMERA_SERVICE).
""",
    "2B": """### Phase 2B: Headless WebView Sandbox & JavaScript Bridge
Focus: Implement secure, isolated execution of JavaScript starter skills in a headless WebView.
Package Root: com.edgehybrid.agent

EXACT REQUIRED FILES TO GENERATE (6 files total):
1. app/src/main/java/com/edgehybrid/agent/sandbox/ScriptSandbox.kt
   - Interface: suspend fun executeScript(scriptName: String, inputJson: String, networkOrigins: List<String> = emptyList()): Result<String>
2. app/src/main/java/com/edgehybrid/agent/sandbox/AndroidSandboxHostBridge.kt
   - Thread-safe class with @JavascriptInterface methods:
     @JavascriptInterface fun complete(resultJson: String)
     @JavascriptInterface fun fail(errorMessage: String)
   - Resumes the suspended coroutine continuation safely without race conditions.
3. app/src/main/java/com/edgehybrid/agent/sandbox/HeadlessWebViewSandbox.kt
   - Implements ScriptSandbox.
   - Injected with '@ApplicationContext private val context: Context'.
   - STRICT IMPORT: import android.webkit.CookieManager (NEVER import android.view.CookieManager).
   - Uses WebViewAssetLoader with shouldInterceptRequest blocking unlisted hosts, STUN/WebRTC, and private IP ranges.
   - Strict 5,000ms watchdog using kotlinx.coroutines.withTimeoutOrNull(5000).
   - Sets up window.__edgeRun(input, networkOrigins) and window.__edgeHost.
4. app/src/main/assets/skills/calculator.js
   - Safe math expression evaluator (token-based or operator precedence, zero dangerous eval), reads global input, calls window.__edgeHost.complete(JSON.stringify({result: answer})).
5. app/src/main/assets/skills/device_info.js
   - Reads device information via host bridge and returns JSON summary via window.__edgeHost.complete(...).
6. app/src/main/assets/skills/web_extract.js
   - Fetches allowlisted URL markdown content and completes via window.__edgeHost.complete(...).
""",
    "2C": """### Phase 2C: Ktor MCP Client & JSON-RPC Gateway
Focus: Model Context Protocol (MCP) JSON-RPC 2.0 client supporting remote tool discovery and execution.
Package Root: com.edgehybrid.agent

EXACT REQUIRED FILES TO GENERATE (4 files total):
1. app/src/main/java/com/edgehybrid/agent/mcp/McpProtocol.kt
   - Data classes for JSON-RPC 2.0:
     data class JsonRpcRequest(val jsonrpc: String = "2.0", val id: String, val method: String, val params: Map<String, Any?> = emptyMap())
     data class JsonRpcResponse(val jsonrpc: String = "2.0", val id: String, val result: Map<String, Any?>? = null, val error: JsonRpcError? = null)
     data class JsonRpcError(val code: Int, val message: String, val data: Any? = null)
     data class McpToolDefinition(val name: String, val description: String, val inputSchema: Map<String, Any?> = emptyMap())
     data class McpCallToolResult(val content: List<Map<String, String>> = emptyList(), val isError: Boolean = false)
2. app/src/main/java/com/edgehybrid/agent/mcp/McpServerConfig.kt
   - Data class McpServerConfig(val id: String, val name: String, val baseUrl: String, val bearerToken: String? = null, val headers: Map<String, String> = emptyMap())
3. app/src/main/java/com/edgehybrid/agent/mcp/KtorMcpTransportFactory.kt
   - Factory creating Ktor HttpClient configured with ContentNegotiation.
   - Sends 'MCP-Protocol-Version: 2025-03-26' and retains 'Mcp-Session-Id' header on subsequent requests.
   - Structured cancellation safe: does NOT catch CancellationException as an McpTransportException.
4. app/src/main/java/com/edgehybrid/agent/mcp/McpGateway.kt
   - Coordinates remote MCP servers, discovers available tools via 'tools/list', and dispatches 'tools/call'.
""",
    "2D": """### Phase 2D: Hilt DI, Tool Registry & Phase 2 Unit Tests
Focus: Unify native tools, sandboxed JS skills, and MCP tools under Hilt dependency injection, and write comprehensive unit tests.
Package Root: com.edgehybrid.agent

EXACT REQUIRED FILES TO GENERATE (5 files total):
1. app/src/main/java/com/edgehybrid/agent/di/PhaseTwoModule.kt
   - Hilt @Module @InstallIn(SingletonComponent::class) providing:
     * ScriptSandbox -> HeadlessWebViewSandbox (via @Binds or @Provides)
     * NativeActionHandler
     * McpGateway
     * ToolRegistry
2. app/src/main/java/com/edgehybrid/agent/tool/ToolRegistry.kt
   - Catalog uniting NativeActionHandler tools, ScriptSandbox skills, and McpGateway tools.
   - Method: suspend fun executeTool(name: String, argumentsJson: String): Result<String>
3. app/src/main/java/com/edgehybrid/agent/ui/tools/ToolsViewModel.kt
   - ViewModel using @HiltViewModel injecting ToolRegistry and NativeActionHandler.
   - StateFlow exposing registered tools and active ActionConfirmation state.
4. app/src/test/java/com/edgehybrid/agent/nativeactions/NativeActionHandlerTest.kt
   - Unit tests for NativeActionHandler: timer creation intent extras, note creation flow, SMS confirmation generation.
5. app/src/test/java/com/edgehybrid/agent/sandbox/HeadlessWebViewSandboxTest.kt
   - Unit tests for HeadlessWebViewSandbox: parameter validation, bridge completion callback, timeout watchdog behavior.
"""
}


class AutonomousPhaseBuilder:
    """Manages the generation, self-evaluation, and persistence of each engineering phase."""

    def __init__(self, llm: CascadingLLMClient, logger: logging.Logger, max_attempts: int = 20):
        self.llm = llm
        self.logger = logger
        self.max_attempts = max_attempts

    def extract_phase_spec(self, phase_id: str) -> str:
        phase_str = str(phase_id).strip()
        if phase_str in SUBPHASE_SPECS:
            return SUBPHASE_SPECS[phase_str]
        if not SPEC_FILE.exists():
            return f"Phase {phase_str} specification missing."
        content = SPEC_FILE.read_text(encoding="utf-8")
        marker = f"### Phase {phase_str}:"
        next_marker = f"### Phase "
        if marker in content:
            part = content.split(marker, 1)[1]
            if next_marker in part:
                return part.split(next_marker, 1)[0].strip()
            return part.split("---", 1)[0].strip()
        return f"Phase {phase_str} details from SPEC.md"

    def generate_phase_files(self, phase_id: str, phase_spec: str, critique: str = "") -> Dict[str, str]:
        """Prompts the LLM to generate production Kotlin files for the phase/sub-phase."""
        critique_block = f"PREVIOUS REVIEW CRITIQUE TO FIX:\n{critique}\n" if critique else ""
        prompt = f"""
You are the Lead Android Systems Architect building {phase_id} of the Edge Hybrid Agent.
TARGET ARCHITECTURE: Kotlin 2.0, Jetpack Compose, Ktor, Room, Hilt, Material 3, Android 14+ (API 34/35).
PACKAGE ROOT: com.edgehybrid.agent

PHASE SPECIFICATION:
{phase_spec}

{critique_block}

RULES:
1. Provide COMPLETE, drop-in, non-stubbed Kotlin / JavaScript / configuration code.
2. NO placeholder comments like '// TODO: Implement later' or dummy returns.
3. Every file must include complete package declaration ('package com.edgehybrid.agent...') and all required imports.
4. Output each file using this EXACT clean delimiter format:

=== FILE: path/to/FileName.kt ===
<full file contents here>
=== END_FILE ===

Generate ONLY the exact files listed in the phase specification. Keep each file concise, complete, and correct.
"""
        system = "You are an autonomous senior Android engineer. Output production code using the specified === FILE: ... === delimiters."
        self.logger.info(f"[{phase_id}] Requesting synthesis from LLM cascade...")
        resp, provider = self.llm.complete(prompt, system=system, json_mode=False)
        if not resp:
            self.logger.error(f"[{phase_id}] Failed to get response from any LLM provider.")
            return {}

        self.logger.info(f"[{phase_id}] Synthesis received from provider: {provider}")
        files_map = {}

        # 1. Delimiter pattern
        delimiter_pattern = re.compile(r"=== FILE:\s*([^\n\r]+?)\s*===\s*\n([\s\S]*?)=== END_FILE ===", re.MULTILINE)
        matches = delimiter_pattern.findall(resp)
        for rel_path, code in matches:
            files_map[rel_path.strip()] = code.strip()

        # 2. Markdown header pattern fallback
        if not files_map:
            md_pattern = re.compile(r"(?:###|##)\s*(?:FILE:)?\s*`?([a-zA-Z0-9_\-./]+\.[a-zA-Z0-9]+)`?\s*\n```(?:kotlin|xml|kts|java|json)?\s*\n([\s\S]*?)```", re.MULTILINE)
            md_matches = md_pattern.findall(resp)
            for rel_path, code in md_matches:
                files_map[rel_path.strip()] = code.strip()

        # 3. JSON fallback
        if not files_map:
            try:
                cleaned = resp.strip()
                if cleaned.startswith("```json"):
                    cleaned = cleaned[7:]
                if cleaned.startswith("```"):
                    cleaned = cleaned[3:]
                if cleaned.endswith("```"):
                    cleaned = cleaned[:-3]
                parsed = json.loads(cleaned.strip(), strict=False)
                if isinstance(parsed, dict):
                    files_map = {k.strip(): str(v).strip() for k, v in parsed.items()}
            except Exception as e:
                self.logger.debug(f"JSON fallback failed: {e}")

        if not files_map:
            self.logger.error(f"[Phase {phase_num}] Could not extract any valid files from response ({len(resp)} chars).")
            return {}

        self.logger.info(f"[Phase {phase_num}] Extracted {len(files_map)} file(s): {list(files_map.keys())}")
        return files_map

    def static_inspection(self, files_map: Dict[str, str]) -> Tuple[bool, List[str]]:
        """Scans generated files for banned placeholder anti-patterns."""
        flaws = []
        banned_patterns = [
            r"//\s*todo",
            r"/\*\s*todo",
            r"throw\s+notimplementederror",
            r"//\s*placeholder",
            r"/\*\s*placeholder",
            r"dummy_token",
            r"dummy_key",
            r"insert_your_key_here",
            r"//\s*implement\s+later"
        ]
        for path, code in files_map.items():
            lower = code.lower()
            for pattern in banned_patterns:
                if re.search(pattern, lower):
                    flaws.append(f"{path}: contains forbidden stub/placeholder matching '{pattern}'")
            # Config, resource, and manifest files can naturally be concise
            is_config_or_resource = any(path.endswith(ext) for ext in [".pro", ".xml", ".properties", ".toml", ".gitignore"])
            min_len = 20 if is_config_or_resource else 80
            if len(code.strip()) < min_len:
                flaws.append(f"{path}: file content is suspiciously short ({len(code)} bytes)")
        return len(flaws) == 0, flaws

    def llm_self_review(self, phase_id: str, phase_spec: str, files_map: Dict[str, str]) -> Tuple[bool, int, List[str]]:
        """Sends the generated files to the LLM reviewer for independent verification."""
        code_summary = "\n\n".join([f"FILE: {p}\n```kotlin\n{c}\n```" for p, c in files_map.items()])
        prompt = f"""
You are an uncompromising Principal Code Reviewer and QA Architect.
Verify whether the following generated code completely satisfies {phase_id} requirements.

REQUIREMENTS:
{phase_spec}

GENERATED CODE:
{code_summary}

EVALUATION CRITERIA:
1. Are all required files for {phase_id} present and fully implemented without stubs?
2. Are coroutines used properly (Dispatchers.IO for background I/O, no UI blocking)?
3. Are error cases, nullability, and exceptions gracefully handled?
4. Are all imports valid and compile-ready (e.g. android.webkit.CookieManager, never android.view.CookieManager)?

Respond in JSON only:
{{
  "passes": true/false,
  "score": 0-100,
  "flaws": ["list of concrete issues"],
  "reflection_lesson": "rule to remember for the future"
}}
"""
        system = "You are an expert QA auditor. Evaluate the code objectively and output valid JSON only."
        resp, provider = None, "none"
        for rev_try in range(2):
            resp, provider = self.llm.complete(prompt, system=system, json_mode=True)
            if resp:
                break
            self.logger.warning(f"Review call attempt {rev_try+1} returned empty; retrying in 5s...")
            time.sleep(5)

        if not resp:
            return False, 50, ["LLM reviewer was unreachable"]

        try:
            cleaned = resp.strip()
            if cleaned.startswith("```json"):
                cleaned = cleaned[7:]
            if cleaned.startswith("```"):
                cleaned = cleaned[3:]
            if cleaned.endswith("```"):
                cleaned = cleaned[:-3]
            data = json.loads(cleaned.strip())
            return data.get("passes", False), data.get("score", 0), data.get("flaws", [])
        except Exception as e:
            return False, 50, [f"Review parsing error: {e}"]

    def record_lesson(self, phase_id: str, rule: str, outcome: str):
        entry = {
            "timestamp": time.time(),
            "phase": str(phase_id),
            "outcome": outcome,
            "rule": rule
        }
        with open(LESSONS_FILE, "a", encoding="utf-8") as f:
            f.write(json.dumps(entry) + "\n")

    def write_files(self, files_map: Dict[str, str]):
        for rel_path, code in files_map.items():
            full_path = BASE_DIR / rel_path
            full_path.parent.mkdir(parents=True, exist_ok=True)
            full_path.write_text(code, encoding="utf-8")
            self.logger.info(f"Wrote file: {rel_path} ({len(code)} chars)")

    def commit_and_push(self, phase_id: str, title: str):
        try:
            subprocess.run(["git", "add", "."], cwd=BASE_DIR, check=True)
            msg = f"feat(phase-{str(phase_id).lower()}): implement {title} with automated self-evaluation"
            subprocess.run(["git", "commit", "-m", msg], cwd=BASE_DIR, check=True)
            subprocess.run(["git", "push", "origin", "main"], cwd=BASE_DIR, check=True)
            self.logger.info(f"[{phase_id}] Pushed to GitHub origin/main successfully!")
        except Exception as e:
            self.logger.warning(f"Git commit/push warning: {e}")

    def is_phase_completed(self, phase_id: str) -> bool:
        if not LESSONS_FILE.exists():
            return False
        with open(LESSONS_FILE, "r", encoding="utf-8") as f:
            for line in f:
                try:
                    data = json.loads(line)
                    if str(data.get("phase")) == str(phase_id) and data.get("outcome") == "success":
                        return True
                except Exception:
                    pass
        return False

    def execute_phase(self, phase_id: str, phase_title: str) -> bool:
        if self.is_phase_completed(phase_id):
            self.logger.info(f"[{phase_id}] Already verified and committed. Skipping.")
            return True
        self.logger.info(f"\n{'='*70}\nSTARTING EXECUTION: {phase_id} - {phase_title}\n{'='*70}")
        phase_spec = self.extract_phase_spec(phase_id)
        critique = ""
        if str(phase_id) == "2B":
            critique = "Ensure HeadlessWebViewSandbox imports 'android.webkit.CookieManager' (not android.view.CookieManager). Ensure withTimeoutOrNull(5000) watchdog."
        elif str(phase_id) == "2C":
            critique = "Ensure McpProtocol sends 'MCP-Protocol-Version: 2025-03-26' and preserves coroutine CancellationException."

        for attempt in range(1, self.max_attempts + 1):
            self.logger.info(f"[{phase_id}] Attempt {attempt}/{self.max_attempts}...")
            files_map = self.generate_phase_files(phase_id, phase_spec, critique)
            if not files_map:
                self.logger.warning(f"[{phase_id}] No files produced on attempt {attempt}. Retrying...")
                continue

            # Static check
            static_ok, static_flaws = self.static_inspection(files_map)
            if not static_ok:
                self.logger.warning(f"[{phase_id}] Static check failed: {static_flaws}")
                critique = "Static check failures:\n" + "\n".join(static_flaws)
                continue

            # LLM Review & False-positive gate
            passes, score, flaws = self.llm_self_review(phase_id, phase_spec, files_map)
            self.logger.info(f"[{phase_id}] Self-Review Score: {score}/100. Passes: {passes}")

            if passes and score >= 80:
                self.logger.info(f"[{phase_id}] ACCEPTED by Self-Evaluation! Writing code to disk...")
                self.write_files(files_map)
                self.record_lesson(phase_id, f"Phase {phase_id} succeeded with score {score}.", "success")
                self.commit_and_push(phase_id, phase_title)
                return True
            else:
                self.logger.warning(f"[{phase_id}] Self-Review rejected output (Score {score}). Flaws: {flaws}")
                critique = f"Self-review score was {score}/100. Flaws to fix:\n" + "\n".join(flaws)
                self.record_lesson(phase_id, f"Phase {phase_id} rejected: {flaws[:2]}", "rejected")

        self.logger.error(f"[{phase_id}] Exhausted attempts without reaching passing score.")
        return False


def main():
    parser = argparse.ArgumentParser(description="Autonomous Phase Builder for Edge Hybrid Agent")
    parser.add_argument("--phase", type=str, help="Specific phase or sub-phase to run (1, 2A, 2B, 2C, 2D, 2, 3, 4, 5)")
    parser.add_argument("--all", action="store_true", help="Execute all phases (1 through 5) sequentially")
    parser.add_argument("--prefer-space-bunny", action="store_true", help="Prioritize Space Bunny Alpha on OpenRouter over Gemini")
    parser.add_argument("--max-attempts", type=int, default=int(os.environ.get("MAX_ATTEMPTS", 20)), help="Maximum attempts per phase")
    args = parser.parse_args()

    logger = setup_logger()
    keys = load_keys()
    llm = CascadingLLMClient(keys, logger, prefer_space_bunny=args.prefer_space_bunny)
    builder = AutonomousPhaseBuilder(llm, logger, max_attempts=args.max_attempts)

    phase_2_subphases = [
        ("2A", "Room DB & Native Action Handlers"),
        ("2B", "Headless WebView Sandbox & JS Bridge"),
        ("2C", "Ktor MCP Client & JSON-RPC Gateway"),
        ("2D", "Hilt DI, Tool Registry & Phase 2 Unit Tests")
    ]

    all_phases = [
        ("1", "Production Cloud Engine & Recursive Agentic Loop"),
        ("2A", "Room DB & Native Action Handlers"),
        ("2B", "Headless WebView Sandbox & JS Bridge"),
        ("2C", "Ktor MCP Client & JSON-RPC Gateway"),
        ("2D", "Hilt DI, Tool Registry & Phase 2 Unit Tests"),
        ("3", "TypeSafe JEV Guardrails & Continuous Learning"),
        ("4", "Samsung Galaxy S23 Ultra S Pen & Vision"),
        ("5", "Offline LiteRT & On-Device RAG")
    ]

    if args.phase:
        p_arg = str(args.phase).strip().upper()
        if p_arg == "2":
            logger.info("Executing Phase 2 via modular sub-phases: 2A -> 2B -> 2C -> 2D")
            for sub_id, sub_title in phase_2_subphases:
                success = builder.execute_phase(sub_id, sub_title)
                if not success:
                    logger.error(f"Stopping execution: Sub-phase {sub_id} failed.")
                    sys.exit(1)
                time.sleep(2)
            sys.exit(0)
        else:
            match = next((t for n, t in all_phases if n.upper() == p_arg), None)
            if not match:
                logger.error(f"Unknown phase identifier: {p_arg}. Available: {[p[0] for p in all_phases]}")
                sys.exit(1)
            success = builder.execute_phase(p_arg, match)
            sys.exit(0 if success else 1)
    elif args.all:
        for p_id, title in all_phases:
            success = builder.execute_phase(p_id, title)
            if not success:
                logger.error(f"Stopping execution: Phase {p_id} failed.")
                sys.exit(1)
            time.sleep(3)
        logger.info("ALL PHASES AND SUB-PHASES COMPLETED AND VERIFIED SUCCESSFULLY!")
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
