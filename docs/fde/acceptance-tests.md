# Local acceptance evidence

Date: **2026-10-23, Asia/Bangkok**. Branch: `feat/customer-workflow-delivery`. Base commit: `aff7e935817cdd2f81692711b6071471ca2d18bf`. All clients, accounts and tasks used here are synthetic. This is local evidence, not a customer engagement, production benchmark or deployment certificate.

## Reproduction environment

Windows PowerShell, Java 21, Node 24/npm, Docker Desktop with PostgreSQL 17.6 and Redis 8.2, Python with Playwright/bcrypt and Chromium. No production data or platform credentials are needed. Install dependencies from the unchanged lockfile, then package/build before running the real-browser harness; do not rebuild a JAR while the harness is using it on Windows.

```powershell
npm ci --ignore-scripts
Push-Location backend
.\mvnw.cmd -q verify
Pop-Location
npm run verify
npm run audit:dependencies
pwsh -NoProfile -File tools/verify-operator-session.ps1
docker compose config --quiet
helm lint infra/helm/v-core
```

The real-browser runner owns ports 18081/13001, disposable containers and server processes, and cleans them in `finally`. `tools/qa_customer_workflow.py` runs inside the existing authenticated harness; it is not a fixture server. Screenshots are written under this document's `evidence/` directory. Local command logs and reports under `backend/target` remain ignored; scripts and source assertions are tracked for reproduction.

## Baseline and gates

| Check | Actual result | Artifact / scope |
| --- | --- | --- |
| Initial backend baseline | Failed before application assertions because Docker was not ready | `backend/target/fde-baseline-backend.log`; environment issue, not a regression proof |
| Backend baseline after Docker startup | Passed, 28 tests | `fde-baseline-backend-ready.log`; base code before feature implementation |
| Initial frontend baseline | Passed, 29 unit tests and 8 browser fixture tests | `fde-baseline-frontend.log`; later inspection found local Next 16.3.0 instead of locked 16.4.2, so final gates below use a clean install |
| Clean locked installation | Passed without manifest/lockfile changes | `fde-clean-install.log`; no dependency upgrades in this feature |
| Feature backend | Passed, 37 tests, zero failures/errors/skips | `fde-backend-locked-final.log`, Surefire XML: 36 application integration cases plus 1 SSE unit case |
| Locked frontend | Passed, 32 unit tests, typecheck, lint, production build, performance-script gate and 10 desktop/mobile browser fixture tests | `fde-frontend-locked-final.log`; Next 16.4.2 from the lockfile. No new benchmark claims are made |
| Dependency audit | Passed existing policy | `fde-audit-locked.log`; runtime remains strict, existing expiring lint exception is neither broadened nor renewed |
| Real Worker → Java → DB browser acceptance | Passed after clean locked build and final Java package | `fde-real-browser-final.log`; no API mocks; includes both scenarios, lost-response retry, assignment, ordered moves, WIP conflict, SQL assertions and session/SSE/logout regression |
| Compose / Helm lint and render | Passed | Existing chart/config unchanged; `fde-helm-render.yaml` is local/ignored |
| Stock multi-stage Docker build | Passed, then rebuilt after the final source change; runtime user verified as `vcore` | `fde-docker-build.log` and `fde-docker-final.log`; image `v-core-backend:fde-local`. First cold dependency-download stage was slow; no Dockerfile/dependency workaround was used |
| Feature remote CI / deployment | Not run | No feature push/PR/merge/deploy was authorized |

The base `main` CI result and unrelated failing dependency-update PRs are recorded separately in the [inventory](requirements-matrix.md). A green base run is not feature CI evidence.

## Requirement-level assertions

| ID | Acceptance condition | Actual evidence |
| --- | --- | --- |
| AC-01 | A/B differ in ordered labels and WIP | `customerTemplatesProvisionDistinctWorkflowsWithoutChangingDefaultProvisioning`; real preset preview and copied columns |
| AC-02 | Tenant A cannot read/publish Tenant B configuration or apply its template locally | `templatesCannotBeReadOrAppliedAcrossWorkspaceBoundaries`: 403 without membership, 404 scoped foreign ID even after adding membership in B, no foreign project |
| AC-03 | Anonymous/viewer cannot publish | `templateMutationsRequireLoginAndWorkspaceAdministration`: 401/403; existing session/CSRF tests and real public read-only/CSRF/logout smoke |
| AC-04 | Competing same-revision publications cannot mix state | `concurrentPublicationOfTheSameRevisionHasOneWinnerWithoutMixedColumns`: gated concurrent requests, one 201/one 409, one complete stored definition and one retry record |
| AC-05 | Invalid WIP, category, duplicate labels, positions, terminal category and null columns fail safely | `invalidWorkflowDefinitionsCannotActivateOrLeaveIdempotencyRecords`: 400, no template or retry record |
| AC-06 | Concurrent project retries do not duplicate project/configuration/audit | `concurrentProjectRetriesCreateOneCompleteWorkflowAndOneAudit`: matching 201 responses, one project, four columns, one PROJECT_CREATED activity |
| AC-07 | Repeated publication is safe; revision 2 never remaps revision-1 projects | `publishingANewRevisionNeverRemapsExistingProjectsAndPublicationIsRetrySafe`: same result, one publication activity, original WIP remains 2 after revision 2 has WIP 9 |
| AC-08 | Partial provisioning failure rolls back | `failureAfterSomeWorkflowColumnsRollsBackProjectSprintAuditAndRetryRecord`: test-only trigger rejects a later column; no project/retry record/project audit/outbox survives; child FKs remain valid |
| AC-09 | A field delimiter cannot disguise a changed templated-project payload | `templatedProjectFingerprintCannotConfuseDelimitersInDifferentFields`: same key with differently split text receives 409 after length-framing fix |
| AC-10 | Existing Kanban, audit/retry and role behavior remains | Original application/SSE tests and original 8 fixture browser tests remain enabled and pass |
| AC-11 | Browser observes actual backend state | Real runner previews/publishes A/B, provisions projects, creates/assigns/moves tasks, checks audit and queries PostgreSQL for exactly `1 project / 1 sprint / 4 columns` per scenario |
| AC-12 | Lost create response does not silently start another operation | Runner fetches real Java commit, aborts browser delivery, verifies locked fields, retries the original key/payload and receives the same project; SQL and audit assert one project/event |
| AC-13 | Client B capacity conflict remains visible and recoverable | Two tasks in VALIDATION, one in APPROVAL; second approval move returns 409 and occupancy stays one. Completing the first allows the second to enter approval |
| AC-14 | Responsive and dark-mode demonstration | Real-browser screenshots and a 390px viewport no-horizontal-overflow assertion; screenshots visually inspected |

Test source: [VCoreApplicationTest](../../backend/src/test/java/dev/vcore/VCoreApplicationTest.java), [Worker contract](../../tests/cloudflare-worker.test.js), [API contracts](../../tests/workspace-api.test.ts), [project task API](../../tests/task-api.test.ts), [scenario fixture](../../tests/e2e/fde-workflow.spec.ts), [real acceptance script](../../tools/qa_customer_workflow.py).

## Red/green evidence and corrections

- Missing template API: `fde-template-red.log` failed with expected 200 / actual 404; initial implemented slice passed in `fde-template-green.log` and full final suite.
- Worker allowlist: `fde-worker-red.log` rejected the new POST; `fde-worker-green.log` passed once only the specific GET/POST paths were added. DELETE remains 405.
- Missing template/project-task API helpers: `fde-template-api-red.log` and `fde-project-task-red.log` failed on absent behavior; corresponding green logs passed.
- Missing scenario UI: `fde-ui-red.log` could not find the new control in the pre-feature build; final desktop/mobile fixture tests pass and prevent implicit default provisioning.
- Delimiter collision: `fde-fingerprint-red.log` returned 201 for a changed request where 409 was expected; length-framed new-template requests pass the final regression. Legacy omitted-template fingerprints remain unchanged for compatibility.
- Fixture fixes were not application proofs: lowercase generated keys violated the uppercase contract, so test fixtures were corrected; a synthetic extra membership needed `finally` cleanup to preserve old session ordering assertions. No old assertion was removed.
- Lint caught ref reads during React render; UI render state now uses state while operation payload/key refs remain in event/mutation code. Generated Playwright/report directories were excluded from lint, not source files. No lint/auth rule was disabled.

## Screenshots

- [Client A, desktop](evidence/client-a-desktop.png)
- [Client B approval-capacity conflict](evidence/client-b-wip-conflict.png)
- [Client B after capacity is released](evidence/client-b-desktop.png)
- [Client B mobile dark mode](evidence/client-b-mobile-dark.png)

These images illustrate assertions; they do not replace DB checks or prove production reliability. Known limits and safe support/rollback are in the [design](solution-design.md) and [handoff](deployment-handoff.md).
