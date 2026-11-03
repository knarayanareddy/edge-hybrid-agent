# edge-hybrid-agent remediation plan

## Roadmap

| Phase | Goal | Entry criteria | Exit criteria |
|---|---|---|---|
| 0 | Baseline and ownership | Pinned SHA and inspected evidence are accepted as the current baseline. | Owner confirms the release boundary for the dashboard, live tests, and Android UI. |
| 1 | Correctness and security hardening | EHA-001 and EHA-002 are accepted as real issues. | SQLite cascade is proven in a regression test; the local status endpoint no longer leaks broad metadata. |
| 2 | Test reliability | EHA-003 is accepted as real test debt. | Default test path is deterministic and offline-capable; live integration is isolated. |
| 3 | UI/accessibility evidence | Owner provides the actual Compose/UI source path. | Screens and semantics are checked against the real UI, not README claims. |
| 4 | Deployment and recovery readiness | Core hardening tasks are complete or formally deferred. | Release boundaries, rollback path, and restore/checkpoint behavior are documented and independently validated. |
| 5 | Independent acceptance | The implementer-owned checks are green and the scope is stable. | A reviewer separate from the implementer accepts the evidence and confirms unresolved owner decisions are explicit. |

## Task dependency map

| Task ID | Depends on | Notes |
|---|---|---|
| `EHA-T1` | none | Fixes EHA-001. |
| `EHA-T2` | none | Fixes EHA-002. |
| `EHA-T3` | none | Fixes EHA-003. |
| `EHA-T4` | owner confirmation of actual UI files | Preventive UI/accessibility audit; no sampled Compose code was available. |
| `EHA-T5` | `EHA-T1`, `EHA-T2`, `EHA-T3` | Deployment/recovery readiness and release boundary proof. |
| `EHA-T6` | `EHA-T1`, `EHA-T2`, `EHA-T3`, `EHA-T4`, `EHA-T5` | Independent acceptance review and closeout. |

## Tasks

### EHA-T1 — Make session deletion prove real SQLite cascade behavior

- **Linked finding:** EHA-001
- **Observed path:** `test_sessions_and_sandbox.py`
- **Proposed implementation paths:** `app/src/main/java/com/edgehybrid/agent/data/local/ChatDatabase.kt`, `app/src/main/java/com/edgehybrid/agent/data/local/ChatDao.kt`, and the matching Android instrumented test path
- **Prerequisites:**
  1. Confirm whether the production DB connection already enables foreign keys.
  2. Confirm the intended deletion policy: cascade, explicit child cleanup, or both.
  3. Locate the runtime code path that opens the Room database.
- **Ordered actions:**
  1. Enable foreign-key enforcement at database open time.
  2. Remove any manual child-row cleanup from the success path, or keep it only as an explicit fallback with a comment.
  3. Rewrite the regression test so it deletes only the parent session row and proves the messages disappear by cascade.
  4. Add a negative test that fails when foreign keys are off.
- **Expected deliverable:** one regression that proves the storage contract, not just the happy path.
- **Verification:** the test should fail if cascade support is removed, and it should not rely on manual cleanup to pass.
- **DoD:**
  - [ ] The test deletes the parent row only.
  - [ ] The child rows disappear without a manual delete.
  - [ ] A deliberate `foreign_keys=OFF` break causes the regression to fail.

### EHA-T2 — Harden the loopback status endpoint

- **Linked finding:** EHA-002
- **Observed path:** `dashboard/server.py`
- **Proposed implementation paths:** `dashboard/server.py`, `dashboard/static/`, and any release toggle/config that controls whether the dashboard ships
- **Prerequisites:**
  1. Decide whether the dashboard is developer-only or can ship in production.
  2. Decide what metadata must stay visible for operator use.
  3. Decide whether local auth can be required without breaking the workflow.
- **Ordered actions:**
  1. Remove the broad CORS policy or replace it with a narrow allowlist.
  2. Redact the git remote and any log content that can expose secrets, hostnames, or internal structure.
  3. If the endpoint must remain, require a local auth token or a loopback-only header check before returning data.
  4. Add a regression that proves a foreign-origin request cannot read the JSON body.
- **Expected deliverable:** a local operator endpoint that is safe by default and explicit about what it reveals.
- **Verification:** a request with the wrong origin or missing token is rejected; the JSON no longer contains the remote URL or raw log tail.
- **DoD:**
  - [ ] Cross-origin reads are blocked.
  - [ ] Remote URL and log tail are not returned in the default payload.
  - [ ] The dashboard’s release status is documented.

### EHA-T3 — Split deterministic tests from live-network smoke checks

- **Linked finding:** EHA-003
- **Observed path:** `test_tools_network.py`
- **Proposed implementation paths:** `test_tools_network.py`, a new fixture directory for recorded responses, and a separate live-smoke script or CI lane
- **Prerequisites:**
  1. Inventory every outbound network call in the current validation scripts.
  2. Decide which checks must be deterministic and which can remain live-only.
  3. Pick a fixture format for recorded responses.
- **Ordered actions:**
  1. Extract the non-network contract checks into a default suite that runs offline.
  2. Move the live calls behind an explicit opt-in flag or separate job.
  3. Add mocked/recorded responses for the default suite so it can prove parsing and edge handling without internet access.
  4. Add a negative test that breaks the fixture or returns 503 so the suite proves it can detect bad input.
- **Expected deliverable:** a reliable default test path plus a clearly labeled live smoke lane.
- **Verification:** the default suite passes with networking disabled, and the live lane is skipped unless explicitly requested.
- **DoD:**
  - [ ] Default tests do not require internet access.
  - [ ] Live external calls are opt-in only.
  - [ ] A broken fixture makes the relevant test fail.

### EHA-T4 — Verify the real UI and accessibility surface

- **Linked requirement:** preventive requirement; no Compose source was sampled
- **Observed path:** none in the evidence bundle
- **Proposed implementation paths:** the actual Compose screen files under `app/src/main/java/com/edgehybrid/agent/...` and UI test sources
- **Prerequisites:**
  1. Identify the actual screen entry points.
  2. Confirm which states are user-facing and which are debug-only.
  3. Confirm whether accessibility semantics are already covered anywhere.
- **Ordered actions:**
  1. Sample the real UI files and map the main flows: chat, settings, skills, and any live telemetry.
  2. Add or update semantics/accessibility checks for labels, focus order, and non-color cues.
  3. Validate the dark/light theme behavior against real screens, not README claims.
  4. Record any unsupported UI claim as documentation debt.
- **Expected deliverable:** a verified UI checklist tied to actual source, not a marketing description.
- **Verification:** remove a label or semantics node and confirm the UI test fails; verify focus order and contrast on the sampled screens.
- **DoD:**
- [ ] Real UI source is identified and reviewed.
- [ ] Accessibility checks exist for the primary flows.
- [ ] Unsupported README UI claims are marked or corrected.

### EHA-T5 — Document deployment and recovery readiness

- **Linked requirement:** deployment/recovery phase; derived from the current release-boundary risks
- **Observed path:** `dashboard/server.py`, `README.md`, and the repo’s release-facing docs
- **Proposed implementation paths:** deployment docs, rollback notes, and any runbook tied to release or restore flow
- **Prerequisites:**
  1. Confirm what counts as a release boundary for the dashboard and validation assets.
  2. Confirm whether any restore or recovery mechanism exists already.
  3. Confirm who owns release approval and rollback decision-making.
- **Ordered actions:**
  1. Write a recovery note that states how to disable the dashboard, revert the test split, and restore the previous release boundary.
  2. Document the minimum operator checks required before release and after rollback.
  3. Add a recovery validation step that proves the documented rollback path is complete and not just descriptive.
  4. Record any missing recovery primitive as an explicit blocker rather than assuming it exists.
- **Expected deliverable:** a release/recovery note that can be executed by an operator who did not author the change.
- **Verification:** the documented rollback path names the exact boundary to revert and includes a recovery check that can be followed independently.
- **DoD:**
  - [ ] Release boundary is explicitly named.
  - [ ] Recovery/rollback steps are written in order.
  - [ ] A recovery check is present and not just narrative prose.

### EHA-T6 — Run independent acceptance against the evidence only

- **Linked requirement:** independent acceptance phase
- **Observed path:** the full draft pair in `fleet-hardening-review/drafts/edge-hybrid-agent/`
- **Proposed implementation paths:** acceptance checklist, review notes, and any independent QA summary
- **Prerequisites:**
  1. Freeze the evidence set used for acceptance.
  2. Separate implementer claims from reviewer evidence.
  3. Decide which unresolved owner questions must block acceptance.
- **Ordered actions:**
  1. Re-check the three supported findings against the quoted evidence only.
  2. Verify that each task has dependencies, outputs, verification, and DoD items.
  3. Confirm that deployment/recovery and owner-approval sections are present.
  4. Mark any remaining unknowns as owner decisions, not acceptance failures.
- **Expected deliverable:** an acceptance note that confirms the draft satisfies the contract without relying on implementer self-attestation.
- **Verification:** the reviewer can trace each finding and phase to a quoted source or an explicit owner decision.
- **DoD:**
  - [ ] Acceptance uses only the frozen evidence set.
  - [ ] Draft has deployment/recovery and independent acceptance phases.
  - [ ] Remaining unknowns are listed as owner decisions.

## Release and rollback policy

- Do not ship the dashboard broadly until EHA-002 is closed or the feature is disabled by default.
- Do not treat the current network scripts as a reliable regression gate until EHA-003 is split into deterministic and live lanes.
- If EHA-001 fails in a follow-up change, revert the smallest DB/test change first; do not weaken the assertion to make the suite green.
- Any UI change must be verified on the real Compose screens before it is counted as accessibility coverage.

## Changes requiring owner approval

- Enabling or shipping `dashboard/server.py` beyond local development.
- Any auth or CORS change that alters who can read `/api/status`.
- Any change that moves secrets, credentials, or log content into a broader surface.
- Any release-boundary change that promotes live-network checks into default CI.
- Any UI or accessibility claim made without the actual Compose source being sampled.

## Production acceptance checklist

- [ ] Session deletion behavior is proven by a regression that fails when cascade support is removed.
- [ ] The status endpoint does not leak git remotes or raw log tails by default.
- [ ] Offline CI can run the default test suite without the internet.
- [ ] Live integration checks are explicitly labeled and opt-in.
- [ ] The actual UI source has been sampled before any accessibility claim is made.
