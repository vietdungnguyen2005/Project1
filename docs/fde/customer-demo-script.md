# Customer demo and portfolio wording

Both clients are hypothetical synthetic demonstrations. No actual engagement, commercial impact, measured production performance or deployed feature is claimed.

## Reproducible walkthrough

Use a disposable local environment and existing operator credentials, not the public site. Build Java/frontend first, then run `pwsh -NoProfile -File tools/verify-operator-session.ps1`. The runner creates its own PostgreSQL/Redis containers and exercises the actual Worker/BFF. For a supervised manual session, keep the equivalent local servers running and follow:

1. Confirm public controls are read-only; sign in as a workspace owner/admin.
2. In **New delivery stream**, select **Client A — Software delivery (synthetic)**. Preview BACKLOG/IN_PROGRESS/REVIEW/DONE and WIP 3/2. Publish version 1 (or select that exact published version), then fill synthetic project/sprint details and create.
3. Observe copied columns in the selected project workbench. Create a task, assign a workspace member and move it into IN_PROGRESS. Inspect recent workspace audit.
4. Select Client B, preview RECEIVED/VALIDATION/APPROVAL/COMPLETED and WIP 2/1, explicitly publish/select its revision and create a second synthetic project.
5. Create two tasks, move them through VALIDATION, then move one into APPROVAL. Trying to move the other into APPROVAL returns 409; one task remains in approval and the other remains in validation. Refresh and inspect the audit. A rejected move is not shown as a committed successful move.
6. Explain that a new template revision affects new projects only. Existing project WIP changes remain separate administrative commands; no active tasks are silently remapped.

The automated runner also aborts delivery of Client A's real committed create response, retries the same in-memory command/key, and verifies one project/sprint with four columns in PostgreSQL. It then rechecks logout and an independent SSE connection. See [acceptance tests](acceptance-tests.md) for exact evidence.

## 90-second English script

“Today I am demonstrating two synthetic customer scenarios, not real client engagements. The problem is delivering different workflows without maintaining a separate application for every team.

Client A is a software team. Its workflow is Backlog, In Progress, Review and Done, with separate capacity limits for engineering and review. I can preview the configuration, publish a validated version and create a project from that exact revision.

Client B is a business operations team. It uses Received, Validation, Approval and Completed, with a smaller approval limit. The same task commands work with these different column IDs. Here, a second task cannot enter a full approval stage: the backend returns a conflict rather than exceeding capacity.

Configuration requires workspace administration rights. The project, sprint, workflow columns and audit records are saved in one transaction, and retries reuse an operation key to avoid duplicate projects. Published revisions do not silently change existing boards.

I validated the implementation with database integration tests and a browser flow through the actual Worker and Java backend. This is local implementation evidence; I am not claiming a production rollout or customer business results.”

## Three FDE interview questions

**How did you turn discovery into delivery scope?** The scenarios differ in labels, capacity and administration, not core task commands. I reused existing roles, project provisioning and Kanban services; added immutable workspace-scoped template revisions; and excluded live workflow migration, custom approval policy and per-customer forks. Evidence: [discovery](client-discovery.md), [inventory](requirements-matrix.md), [design](solution-design.md).

**How can you demonstrate reliability rather than just show configuration screens?** Integration tests check cross-workspace rejection, concurrent publication, duplicate provisioning and failure after some column inserts. The real browser flow loses a response after Java commits and retries the same key; PostgreSQL still has one project, one sprint and four columns. It also reproduces a WIP conflict. Evidence: [test ledger](acceptance-tests.md) and `VCoreApplicationTest` methods listed there. This does not prove every production failure window.

**How would you hand the solution to customer operations?** Provide role/CSRF prerequisites, exact template/version selection, response/error contract, synthetic walkthrough and a rollback procedure that preserves additive schema/data. Separate implemented/tested behavior from deployment status, and document limits such as in-memory pending-command recovery and best-effort SSE. Evidence: [handoff](deployment-handoff.md).

These are implementation explanations, not verified personal work history. Use first-person contribution statements only for work you can explain and actually performed; disclose AI assistance accurately when asked.

## Two ATS-friendly CV bullets

- Implemented workspace-scoped, versioned workflow templates in Java/Spring Boot, reusing transactional project provisioning, idempotent commands and audit/outbox records for two synthetic customer scenarios.
- Validated tenant authorization, concurrent requests and atomic rollback with PostgreSQL/Testcontainers and browser acceptance through a Cloudflare Worker BFF and Java backend; demonstrated locally, not production-deployed.

Only add deployment claims after a separately authorized release has been verified. Do not translate synthetic scenarios into “delivered to two customers.”
