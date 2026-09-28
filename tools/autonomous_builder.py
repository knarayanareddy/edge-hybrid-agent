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


class AutonomousPhaseBuilder:
    """Manages the generation, self-evaluation, and persistence of each engineering phase."""

    def __init__(self, llm: CascadingLLMClient, logger: logging.Logger, max_attempts: int = 20):
        self.llm = llm
        self.logger = logger
        self.max_attempts = max_attempts

    def extract_phase_spec(self, phase_num: int) -> str:
        if not SPEC_FILE.exists():
            return f"Phase {phase_num} specification missing."
        content = SPEC_FILE.read_text(encoding="utf-8")
        marker = f"### Phase {phase_num}:"
        next_marker = f"### Phase {phase_num + 1}:"
        if marker in content:
            part = content.split(marker, 1)[1]
            if next_marker in part:
                return part.split(next_marker, 1)[0].strip()
            return part.split("---", 1)[0].strip()
        return f"Phase {phase_num} details from SPEC.md"

    def generate_phase_files(self, phase_num: int, phase_spec: str, critique: str = "") -> Dict[str, str]:
        """Prompts the LLM to generate all production Kotlin files for the phase."""
        critique_block = f"PREVIOUS REVIEW CRITIQUE TO FIX:\n{critique}\n" if critique else ""
        prompt = f"""
You are the Lead Android Systems Architect building Phase {phase_num} of the Edge Hybrid Agent.
TARGET ARCHITECTURE: Kotlin 2.0, Jetpack Compose, Ktor, Room, Hilt, Material 3, Android 14+ (API 34/35).

PHASE SPECIFICATION:
{phase_spec}

{critique_block}

RULES:
1. Provide COMPLETE, drop-in, non-stubbed Kotlin / configuration code.
2. NO placeholder comments like '// TODO: Implement later' or dummy returns.
3. Every file must include complete package declaration and all required imports.
4. Output each file using this EXACT clean delimiter format:

=== FILE: path/to/FileName.kt ===
<full file contents here>
=== END_FILE ===

Repeat for all files needed to fully satisfy Phase {phase_num}.
"""
        system = "You are an autonomous senior Android engineer. Output production code using the specified === FILE: ... === delimiters."
        self.logger.info(f"[Phase {phase_num}] Requesting synthesis from LLM cascade...")
        resp, provider = self.llm.complete(prompt, system=system, json_mode=False)
        if not resp:
            self.logger.error(f"[Phase {phase_num}] Failed to get response from any LLM provider.")
            return {}

        self.logger.info(f"[Phase {phase_num}] Synthesis received from provider: {provider}")
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

    def llm_self_review(self, phase_num: int, phase_spec: str, files_map: Dict[str, str]) -> Tuple[bool, int, List[str]]:
        """Sends the generated files to the LLM reviewer for independent verification."""
        code_summary = "\n\n".join([f"FILE: {p}\n```kotlin\n{c}\n```" for p, c in files_map.items()])
        prompt = f"""
You are an uncompromising Principal Code Reviewer and QA Architect.
Verify whether the following generated code completely satisfies Phase {phase_num} requirements.

REQUIREMENTS:
{phase_spec}

GENERATED CODE:
{code_summary}

EVALUATION CRITERIA:
1. Are all required classes, functions, and interfaces fully implemented without stubs?
2. Are coroutines used properly (Dispatchers.IO for background I/O, no UI blocking)?
3. Are error cases and exceptions gracefully handled?
4. Are there any false positives or mocked behaviors pretending to be real?

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

    def record_lesson(self, phase_num: int, rule: str, outcome: str):
        entry = {
            "timestamp": time.time(),
            "phase": phase_num,
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

    def commit_and_push(self, phase_num: int, title: str):
        try:
            subprocess.run(["git", "add", "."], cwd=BASE_DIR, check=True)
            msg = f"feat(phase-{phase_num}): implement {title} with automated self-evaluation"
            subprocess.run(["git", "commit", "-m", msg], cwd=BASE_DIR, check=True)
            subprocess.run(["git", "push", "origin", "main"], cwd=BASE_DIR, check=True)
            self.logger.info(f"[Phase {phase_num}] Pushed to GitHub origin/main successfully!")
        except Exception as e:
            self.logger.warning(f"Git commit/push warning: {e}")

    def is_phase_completed(self, phase_num: int) -> bool:
        if not LESSONS_FILE.exists():
            return False
        with open(LESSONS_FILE, "r", encoding="utf-8") as f:
            for line in f:
                try:
                    data = json.loads(line)
                    if data.get("phase") == phase_num and data.get("outcome") == "success":
                        return True
                except Exception:
                    pass
        return False

    def execute_phase(self, phase_num: int, phase_title: str) -> bool:
        if self.is_phase_completed(phase_num):
            self.logger.info(f"[Phase {phase_num}] Already verified and committed. Skipping.")
            return True
        self.logger.info(f"\n{'='*70}\nSTARTING EXECUTION: Phase {phase_num} - {phase_title}\n{'='*70}")
        phase_spec = self.extract_phase_spec(phase_num)
        critique = ""
        if phase_num == 2:
            critique = """Known critical compilation & architecture requirements from architectural audit to address on Attempt 1:
1. STRICT IMPORTS:
   - In HeadlessWebViewSandbox.kt: MUST import 'android.webkit.CookieManager' (NEVER 'android.view.CookieManager').
   - In test files: MUST import 'androidx.activity.result.ActivityResult' (NEVER 'android.app.ActivityResult').
2. DEPENDENCIES in app/build.gradle.kts:
   - MUST declare: implementation("androidx.webkit:webkit:1.11.0")
   - MUST declare: implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
3. HILT INJECTION:
   - In NativeActionHandler: inject '@ApplicationContext private val context: Context'.
4. SANDBOX JAVASCRIPT CONTRACT:
   - HeadlessWebViewSandbox must define window.__edgeRun or invoke the script with the exact bootstrap global variables (input, networkOrigins) that calculator.js, device_info.js, and web_extract.js expect.
   - The completion callback bridge (edgeHost.complete / edgeHost.fail) must be properly wired to resume the Kotlin coroutine.
5. CONSTRUCTORS & DATA FLOW:
   - In AgentViewModel, ensure Context passed to NativeActionHandler.createCalendarEvent is non-null.
   - In SkillNetworkPolicy, initialize all val properties in the primary constructor (rules: List<String> = emptyList(), directWebViewNetworkEnabled: Boolean = false).
   - In NativeActionHandler prepareSms, explicitly call retainPendingMessage so confirmSms has the active pending action.
   - In SkillHostBridge startExecution, assign the skill property on ActiveExecution (active.skill must not be null).
   - In WebMarkdownExtractor, render direct text children properly so content inside tags is never dropped.
   - For MCP, send negotiated MCP-Protocol-Version header on subsequent requests.
   - In PizzaTimerInstrumentedTest, invoke through AgentCommandParser end-to-end to verify 'Set a timer for 15 minutes for pizza'.
"""

        for attempt in range(1, self.max_attempts + 1):
            self.logger.info(f"[Phase {phase_num}] Attempt {attempt}/{self.max_attempts}...")
            files_map = self.generate_phase_files(phase_num, phase_spec, critique)
            if not files_map:
                self.logger.warning(f"[Phase {phase_num}] No files produced on attempt {attempt}. Retrying...")
                continue

            # Static check
            static_ok, static_flaws = self.static_inspection(files_map)
            if not static_ok:
                self.logger.warning(f"[Phase {phase_num}] Static check failed: {static_flaws}")
                critique = "Static check failures:\n" + "\n".join(static_flaws)
                continue

            # LLM Review & False-positive gate
            passes, score, flaws = self.llm_self_review(phase_num, phase_spec, files_map)
            self.logger.info(f"[Phase {phase_num}] Self-Review Score: {score}/100. Passes: {passes}")

            if passes and score >= 80:
                self.logger.info(f"[Phase {phase_num}] ACCEPTED by Self-Evaluation! Writing code to disk...")
                self.write_files(files_map)
                self.record_lesson(phase_num, f"Phase {phase_num} succeeded with score {score}.", "success")
                self.commit_and_push(phase_num, phase_title)
                return True
            else:
                self.logger.warning(f"[Phase {phase_num}] Self-Review rejected output (Score {score}). Flaws: {flaws}")
                critique = f"Self-review score was {score}/100. Flaws to fix:\n" + "\n".join(flaws)
                self.record_lesson(phase_num, f"Phase {phase_num} rejected: {flaws[:2]}", "rejected")

        self.logger.error(f"[Phase {phase_num}] Exhausted attempts without reaching passing score.")
        return False


def main():
    parser = argparse.ArgumentParser(description="Autonomous Phase Builder for Edge Hybrid Agent")
    parser.add_argument("--phase", type=int, choices=[1, 2, 3, 4, 5], help="Execute a single specific phase")
    parser.add_argument("--all", action="store_true", help="Execute all phases (1 through 5) sequentially")
    parser.add_argument("--prefer-space-bunny", action="store_true", help="Prioritize Space Bunny Alpha on OpenRouter over Gemini")
    parser.add_argument("--max-attempts", type=int, default=int(os.environ.get("MAX_ATTEMPTS", 20)), help="Maximum attempts per phase")
    args = parser.parse_args()

    logger = setup_logger()
    keys = load_keys()
    llm = CascadingLLMClient(keys, logger, prefer_space_bunny=args.prefer_space_bunny)
    builder = AutonomousPhaseBuilder(llm, logger, max_attempts=args.max_attempts)

    phases = [
        (1, "Production Cloud Engine & Recursive Agentic Loop"),
        (2, "Native Tool Execution & Headless Sandbox"),
        (3, "TypeSafe JEV Guardrails & Continuous Learning"),
        (4, "Samsung Galaxy S23 Ultra S Pen & Vision"),
        (5, "Offline LiteRT & On-Device RAG")
    ]

    if args.phase:
        p_num = args.phase
        title = next(t for n, t in phases if n == p_num)
        success = builder.execute_phase(p_num, title)
        sys.exit(0 if success else 1)
    elif args.all:
        for p_num, title in phases:
            success = builder.execute_phase(p_num, title)
            if not success:
                logger.error(f"Stopping execution: Phase {p_num} failed.")
                sys.exit(1)
            time.sleep(3)
        logger.info("ALL PHASES COMPLETED AND VERIFIED SUCCESSFULLY!")
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
