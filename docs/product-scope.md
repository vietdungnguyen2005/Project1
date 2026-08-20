# V-Core product scope

V-Core is a multi-tenant delivery workspace for small software teams. Its portfolio-grade differentiator is a conflict-safe Kanban workflow with an audit trail that remains correct under retries and concurrent updates.

## Delivered user journey

1. The BFF resolves a trusted demo identity and its workspace membership.
2. The user loads the seeded project, sprint, workflow columns, and tasks.
3. The user searches, creates, renames, and moves tasks.
4. The system rejects stale edits, enforces WIP limits under concurrency, and safely replays idempotent requests.
5. Every accepted task change is visible in the activity log and invalidates connected clients through SSE.

## Delivered operations and collaboration slice

- Cloudflare Access identity support with an explicit fixed demo identity fallback;
- workspace roster and retry-safe invitations;
- project creation with an initial active sprint and default workflow;
- version-checked WIP-limit administration;
- workspace-member assignment and retry-safe task comments;
- recruiter-facing evidence center linked to executable proof.

External email delivery and invitation acceptance remain intentionally deferred; the demo records pending invitations without pretending that mail was sent.

## Portfolio pain point

Two users can edit or move the same task at nearly the same time. A production-grade system must not silently lose data, exceed a column's WIP limit, duplicate a retried command, or publish an activity that was never committed.

V-Core addresses this with:

- optimistic version checks and an explicit `409 Conflict` response;
- transactional locking around WIP-limit decisions;
- durable idempotency records in PostgreSQL;
- a transactional outbox for audit and realtime delivery;
- workspace-scoped authorization and repository queries.

## Definition of done

A vertical slice is complete only when it has:

- input validation and predictable RFC 9457 problem responses;
- authorization and tenant-isolation coverage;
- unit or integration tests for its business rules;
- database migrations for schema changes;
- observable health, logs, metrics, and traces where applicable;
- API documentation and frontend states for loading, empty, error, success, and conflict;
- no skipped tests, placeholder production logic, committed secrets, or untracked manual setup.

## Explicitly deferred

- microservices and Kafka;
- public AWS infrastructure;
- production Kubernetes hosting;
- billing, file uploads, and email delivery.

These are excluded until the core workflow is complete. Kubernetes remains a local/CI deployment proof, not an unnecessary runtime dependency.
