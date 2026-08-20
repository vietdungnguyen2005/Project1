const repository = "https://github.com/vietdungnguyen2005/Project1";

export const evidenceItems = [
  {
    id: "concurrency",
    label: "Concurrency",
    claim: "Two simultaneous moves cannot exceed a PostgreSQL-backed WIP limit.",
    result: "Expected result: one 200, one 409; committed count remains at the limit.",
    proof: "Testcontainers integration",
    href: `${repository}/blob/main/backend/src/test/java/dev/vcore/VCoreApplicationTest.java`
  },
  {
    id: "idempotency",
    label: "Retry safety",
    claim: "Repeated create, edit, move, invite and comment commands do not duplicate state.",
    result: "Durable request hash and response replay live in PostgreSQL for 24 hours.",
    proof: "Database assertions",
    href: `${repository}/blob/main/backend/src/main/java/dev/vcore/task/application/IdempotencyService.java`
  },
  {
    id: "tenant-security",
    label: "Tenant security",
    claim: "Browser-supplied identity headers are removed before the trusted BFF identity is injected.",
    result: "Untrusted direct backend identity returns 401; membership scopes every query.",
    proof: "Worker + Spring Security tests",
    href: `${repository}/blob/main/tests/cloudflare-worker.test.js`
  },
  {
    id: "audit",
    label: "Audit integrity",
    claim: "Business mutation, activity entry and outbox event commit in the same transaction.",
    result: "A retried task move produces exactly one activity and one outbox event.",
    proof: "Transactional integration test",
    href: `${repository}/blob/main/backend/src/main/java/dev/vcore/task/application/TaskEventRecorder.java`
  },
  {
    id: "frontend-correctness",
    label: "UI correctness",
    claim: "Optimistic changes reconcile with server versions and roll back on rejection.",
    result: "Desktop and mobile tests also assert one command for a double-submit risk.",
    proof: "Vitest + Playwright",
    href: `${repository}/blob/main/tests/e2e/v-core.spec.ts`
  },
  {
    id: "performance",
    label: "Interaction budget",
    claim: "Core board transforms remain under a 50 ms budget at 10,000 tasks.",
    result: "Generated on every frontend verify run; raw JSON is committed for review.",
    proof: "Deterministic benchmark",
    href: `${repository}/blob/main/reports/performance-proof.json`
  }
] as const;

export const evidenceLinks = {
  actions: `${repository}/actions/workflows/ci.yml`,
  workflow: `${repository}/blob/main/.github/workflows/ci.yml`,
  architecture: `${repository}/blob/main/docs/architecture.md`
};
