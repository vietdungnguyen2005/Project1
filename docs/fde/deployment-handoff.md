# Deployment, security and support handoff

This synthetic customer slice is implemented locally on `feat/customer-workflow-delivery`. It has not been pushed, merged or deployed. Existing live deployment evidence belongs to earlier revisions. Do not infer feature availability from the public URL returning 200.

## Before an authorized rollout

1. Review the feature diff and [acceptance evidence](acceptance-tests.md), including residual limitations and the separate dependency audit policy. Do not merge unrelated failing dependency PRs to ship this feature.
2. Back up the PostgreSQL database; verify target backend/BFF configuration and HTTPS session cookies without printing secrets. Use existing account provisioning and secret stores; no new platform credentials are required by this feature.
3. Pause configuration/provisioning writes for the coordinated rollout. Apply forward-only V7 through Flyway. Never edit V1–V6 or the Flyway history.
4. Deploy a matching Java artifact and Pages Worker/frontend. The Worker must expose only the new scoped GET and template POST routes; no wildcard API proxy or authentication bypass.
5. Run read-only health/session checks, then an approved synthetic-data smoke: publish/select revision, create project, create/move task, verify audit and role denial. Record actual release SHA and deployment IDs separately.
6. Watch HTTP 400/401/403/409/5xx, database constraints and readiness. A conflict may be correct behavior; investigate its detail before retrying.

Docker and Helm remain the existing packaging/runtime configuration. No new services, dependencies, public signup, cloud resources or infrastructure credentials were added. Kubernetes hosting and a production rollout are not acceptance claims for the local slice.

## Rollback

Stop new configuration writes and roll back matched frontend/Worker/backend artifacts to a known-good release. Prefer leaving additive V7 and customer data in place; old code can ignore the additional table/nullable column, but will not expose the new feature. Verify compatibility and smoke on a disposable restored database before a real rollback. Do not drop templates, delete projects, remove provenance or remap tasks to make an old UI look correct. Project-level WIP changes and template revision publication have separate histories.

## Support playbook

| Symptom | First checks | Safe action |
| --- | --- | --- |
| Cannot publish | Session, CSRF, workspace role, validation response | Correct input/access; never inject owner headers |
| Revision already exists | Workspace/key/version and stored definition | Select stored version or publish a deliberate new revision |
| Foreign template not found | Workspace path and selected ID | Re-select in the correct authorized workspace; never copy IDs to bypass isolation |
| Lost project response | Keep the unchanged form and original key | Retry same command in the same mounted form; check overview/audit before starting another; do not reload while unresolved |
| WIP/stale-version conflict | Current tasks, current column limit and task version | Refresh, inspect, then choose a valid new action; no blind retry loop |
| Database error during provisioning | Request ID and sanitized server logs | Confirm result by operation/project/audit state; do not assume network failure means rollback |
| Audit feed looks incomplete | Workspace activity limit, refresh and persisted DB history | Refresh/query supported history; browser display is not durable notification delivery proof |

## Explicit security and operational limits

- Scenario selection is not tenant switching. Template list/read/publish/provisioning all enforce backend workspace scope; synthetic customer labels do not grant authorization.
- Direct SQL is not a supported template-editing tool. API revisions are immutable; active workflow migration and new approval/transition policies are excluded.
- Pending browser commands are retained in memory, not durable recovery storage. Save/reference the operation and investigate before changing devices/reloading when outcome is unknown.
- Existing workspace/key idempotency expiry/cross-actor limitations and best-effort per-process SSE remain. This is not a platform-wide exactly-once or zero-downtime claim.
- The existing, user-approved lint-only advisory exception expires on 2026-11-18. Runtime audit remains strict; this feature does not broaden or renew that exception.
