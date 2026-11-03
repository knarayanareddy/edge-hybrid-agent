# edge-hybrid-agent review

## 1) Scope and verdict

- **Repo:** `edge-hybrid-agent`
- **Pinned SHA:** `ef12f7c218c022906644379da0f80d75114003ae`
- **Stage:** implementation/prototype. The tree is an Android app plus local Python dashboard/tests, and the README still instructs a manual Android Studio/device run path.
- **Sampled files:** `README.md`, `dashboard/server.py`, `test_sessions_and_sandbox.py`, `test_tools_network.py`, `test_tool_schemas.py`, and snapshot metadata for the repo.
- **Uninspected boundaries:** the Kotlin app source under `app/src/main/java/...`, Android manifest/resources, Gradle wiring, and the GitHub Actions workflow files in the tree. I did not run Gradle, Android Studio, Python tests, or any repo commands.
- **Verdict:** the repo looks like a real hybrid-agent scaffold, but the inspected evidence shows three hardening gaps: one integrity test that can pass while masking SQLite behavior, one local status endpoint that leaks metadata broadly, and one validation suite that depends on live third-party services.

## 2) Architecture and product boundary

The visible architecture is split into three parts:

1. **Android product surface** — the README describes a local/cloud hybrid agent with OpenRouter or Gemini keys, a TypeSafe JEV key, and an Android device/emulator run loop (`README.md` lines 3–24, 68–70).
2. **Local operational dashboard** — `dashboard/server.py` serves `GET /api/status` on `127.0.0.1:9229`, reads log state, and reports git metadata.
3. **Standalone validation scripts** — the inspected Python files are executable scripts that simulate or call external services directly; I did not inspect workflow wiring, so no stronger runner-integration claim is made here.

External dependencies visible in evidence include OpenRouter/Gemini credentials, the local filesystem/SQLite, Git, and live internet APIs (Open-Meteo, Wikipedia, Frankfurter, example.com). The actual Android UI and accessibility boundary is not inspectable from the sampled files, so UI claims remain unverified here.

## 3) Findings ledger

| ID | Severity | Status | Evidence | Impact | Recommended correction |
|---|---:|---|---|---|---|
| **EHA-001** | Medium | confirmed static | `test_sessions_and_sandbox.py` [L27-L29](https://github.com/knarayanareddy/edge-hybrid-agent/blob/ef12f7c218c022906644379da0f80d75114003ae/test_sessions_and_sandbox.py#L27-L29), [L34-L53](https://github.com/knarayanareddy/edge-hybrid-agent/blob/ef12f7c218c022906644379da0f80d75114003ae/test_sessions_and_sandbox.py#L34-L53), [L101-L104](https://github.com/knarayanareddy/edge-hybrid-agent/blob/ef12f7c218c022906644379da0f80d75114003ae/test_sessions_and_sandbox.py#L101-L104) | The test opens SQLite with `“conn = sqlite3.connect(db_path)”`, declares `“FOREIGN KEY (sessionId) REFERENCES chat_sessions(id) ON DELETE CASCADE”`, then compensates with `“DELETE FROM chat_messages WHERE sessionId = ?”`. That can let a broken schema or runtime setting pass green. | Enable `PRAGMA foreign_keys = ON` at connection open, delete the parent row only, and assert the child rows disappear via cascade. Add a negative regression that fails if foreign keys are off. |
| **EHA-002** | Medium | confirmed static | `dashboard/server.py` [L30-L36](https://github.com/knarayanareddy/edge-hybrid-agent/blob/ef12f7c218c022906644379da0f80d75114003ae/dashboard/server.py#L30-L36), [L106-L121](https://github.com/knarayanareddy/edge-hybrid-agent/blob/ef12f7c218c022906644379da0f80d75114003ae/dashboard/server.py#L106-L121) | `GET /api/status` returns `“remote”: remote` from `get_git_info()`, and the handler sends `“Access-Control-Allow-Origin”, "*"`. Any local webpage or process that can reach localhost can read that data. | Narrow CORS, remove or redact the git remote and log tail, and gate the endpoint behind a local auth token if it must stay exposed. |
| **EHA-003** | Low-Medium | confirmed static | `test_tools_network.py` [L24-L104](https://github.com/knarayanareddy/edge-hybrid-agent/blob/ef12f7c218c022906644379da0f80d75114003ae/test_tools_network.py#L24-L104) | The validation suite hard-codes live calls with `“urllib.request.urlopen(req, timeout=10)”` against Open-Meteo, Wikipedia, Frankfurter, and example.com. That makes CI and local verification flaky, non-deterministic, and offline-hostile. | Split deterministic contract tests from live integration smoke tests, add fixtures/mocks for the default path, and make live calls opt-in with explicit network preconditions. |

## 4) Five-discipline assessment

- **Correctness/code review:** strongest issue is EHA-001. The repository is trying to validate session isolation, but the inspected test does not actually prove SQLite cascade behavior.
- **Automation/testing:** the suite is mostly ad hoc scripts. `test_tool_schemas.py` only checks schema shape, while `test_tools_network.py` depends on third-party uptime. That is enough for a demo, not enough for stable regression gating.
- **DevOps/operations:** `dashboard/server.py` is a useful local operator surface, but it is not production-safe as written because it leaks runtime metadata and has no obvious auth or release toggle in the inspected path.
- **OWASP/security:** EHA-002 is the main security concern. The endpoint is loopback-bound, which helps, but the `*` CORS header plus exposed git/log data still creates a local-data disclosure surface.
- **UI/UX/accessibility:** not meaningfully assessable from the sampled files. The README advertises a Compose UI and telemetry, but no Compose source or accessibility evidence was inspected, so no honest accessibility claim can be made here.

## 5) Production blockers and owner questions

1. Should `dashboard/server.py` be present in any release build, or is it strictly a developer-only aid?
2. Is SQLite foreign-key enforcement enabled in the actual Android runtime path, and if so, where is that guaranteed?
3. Are the live network scripts meant to stay in CI, or should they move to a manual smoke lane?
4. Which Compose files own the real UI, so accessibility and contrast can be checked against actual screens rather than README claims?
5. Where are OpenRouter/Gemini/API secrets stored in the real app path, and is the key store implementation part of the release boundary?

## 6) Evidence inventory and limitations

Inspected paths and ranges:

- `README.md` [L3-L24](https://github.com/knarayanareddy/edge-hybrid-agent/blob/ef12f7c218c022906644379da0f80d75114003ae/README.md#L3-L24), [L68-L70](https://github.com/knarayanareddy/edge-hybrid-agent/blob/ef12f7c218c022906644379da0f80d75114003ae/README.md#L68-L70)
- `dashboard/server.py` [L30-L36](https://github.com/knarayanareddy/edge-hybrid-agent/blob/ef12f7c218c022906644379da0f80d75114003ae/dashboard/server.py#L30-L36), [L106-L121](https://github.com/knarayanareddy/edge-hybrid-agent/blob/ef12f7c218c022906644379da0f80d75114003ae/dashboard/server.py#L106-L121)
- `test_sessions_and_sandbox.py` [L27-L29](https://github.com/knarayanareddy/edge-hybrid-agent/blob/ef12f7c218c022906644379da0f80d75114003ae/test_sessions_and_sandbox.py#L27-L29), [L34-L53](https://github.com/knarayanareddy/edge-hybrid-agent/blob/ef12f7c218c022906644379da0f80d75114003ae/test_sessions_and_sandbox.py#L34-L53), [L101-L104](https://github.com/knarayanareddy/edge-hybrid-agent/blob/ef12f7c218c022906644379da0f80d75114003ae/test_sessions_and_sandbox.py#L101-L104)
- `test_tools_network.py` [L24-L104](https://github.com/knarayanareddy/edge-hybrid-agent/blob/ef12f7c218c022906644379da0f80d75114003ae/test_tools_network.py#L24-L104)
- `test_tool_schemas.py` [L4-L120](https://github.com/knarayanareddy/edge-hybrid-agent/blob/ef12f7c218c022906644379da0f80d75114003ae/test_tool_schemas.py#L4-L120)

Unrun checks: no app build, no Android test run, no Python test execution, no workflow execution, no `gh api`, and no device/emulator interaction.
