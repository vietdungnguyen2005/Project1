# Capability inventory and implementation plan

Audit date: 2026-10-23. Base: `aff7e935817cdd2f81692711b6071471ca2d18bf`. Branch: `feat/customer-workflow-delivery`. Client A and Client B are synthetic scenarios, not customer engagements. The existing uncommitted interview-prep edit is preserved and excluded from the feature commit.

## Verified inventory at baseline

| Requirement | Existing implementation | Evidence | Gap | Reuse decision |
| --- | --- | --- | --- | --- |
| Workspace authorization | PostgreSQL membership; OWNER/ADMIN for administration | `WorkspaceAccessService`, existing session/role/tenant tests | New template routes need these checks | Reuse, no new role model |
| User authentication / CSRF | BCrypt, Spring Session JDBC, BFF trust and CSRF | `SecurityConfiguration`, `OperatorSessionController`, Worker tests | Allowlist new routes, no bypass | Reuse session and operator UI |
| Project provisioning | Project, active sprint and default four columns in one transaction | `WorkspaceOperationsService.createProject`; `ownerCanCreateAProjectWithAnActiveSprintAndTuneWipUsingVersions` | Hard-coded workflow only | Add optional immutable template reference; retain default |
| Versioned customer configuration | None | Provisioning source and V1–V6 migrations | Workspace-scoped reusable definitions, validation and provenance | New additive V7; no edits to prior migrations |
| Retry safety | Workspace/key request hash + stored response in caller transaction | `IdempotencyService`; move/create retry tests | Include template/version identity in new operation hashes | Reuse with operation-specific canonical payload |
| WIP and stale-write protection | Target-column lock, occupancy check, task version | `TaskMoveService`, `TaskCreateService`, `updateWipLimit`; concurrency tests | New UI must address actual column IDs, not fixed status names | Reuse commands; preserve existing board |
| Member assignment | Workspace-member validation, versioned task assignment | `TaskCollaborationService`, assignment tests | Existing browser helper assumes seeded project | Add optional project context, reuse endpoint |
| Audit and outbox | Mutation/activity/outbox share transaction | `WorkspaceEventRecorder`, `TaskEventRecorder`, retry tests | Publish template and project provenance events | Reuse; do not promise durable browser delivery |
| SSE | Per-process emitters; session/membership revalidation | `SseSubscriptionAccess`, SSE tests | No full multi-instance convergence | Leave existing guarantees unchanged; explicit refresh in delivery workbench |
| Frontend configuration | Existing workspace operations form, React Query, operator controls | `workspace-operations.tsx`, `workspace-api.ts` | Scenario preview and generated-project task controls | Extend current screen with a small project workbench |
| Hosting / infrastructure | Pages Worker, Java container, existing Helm/CI | `_worker.js`, Dockerfile, Helm deployment, `ci.yml` | New Worker routes and coordinated rollout | Keep architecture; do not deploy automatically |

The current `docs/product-scope.md` still described obsolete identity fallback. Update it with the verified session/read-only model during handoff, rather than treating it as source-code evidence.

## Smallest end-to-end plan

1. Establish baseline backend/frontend gates; distinguish local environment failures from application assertions.
2. Add immutable, workspace-scoped published template versions. Publishing validates the complete definition before atomic activation. There is no editable-draft lifecycle or automatic "latest version" selection.
3. Expose two read-only synthetic scenario presets; an administrator explicitly publishes a version into their workspace. Presets are not tenant/customer records.
4. Add an optional template ID to existing project creation. Copy validated columns, persist provenance, sprint and project, audit and idempotency response atomically. Omitted ID retains the original defaults. Existing projects are never remapped.
5. Add preview/publish/select controls and a workbench for a selected project, using existing task/assignment APIs and server-side authorization.
6. Verify invalid definitions, role/tenant isolation, duplicate/concurrent commands, immutable versions, rollback and existing Kanban regressions. Run a real browser → Worker → Java → PostgreSQL demonstration for both scenarios, including conflicts and audit.
7. Record actual results, API contracts, screenshots, support/rollback and portfolio wording. No remote feature CI, push, merge or production deployment unless separately requested.

## External CI is not local feature evidence

Read-only GitHub checks on 2026-10-23: main push run [39127968797](https://github.com/vietdungnguyen2005/Project1/actions/runs/39127968797) passed for the base commit. Separate open Dependabot PR heads #2, #3, #4, #5, #6, #7, #10 and #13 had failing latest queried runs. They were not merged, rerun or modified. Their failures are not a failure of this unpushed feature branch. No dependency upgrades are part of this slice.
