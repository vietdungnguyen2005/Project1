# AWS deployment evidence

V-Core was deployed and verified in `us-east-1` on `2026-08-20T15:42:07Z` from commit `d58e9b57f2c57497a622230641acac5d17424bce`.

| Check | Recorded result |
|---|---|
| Terraform bootstrap | `42 added, 0 changed, 0 destroyed` |
| Verified teardown | `42 destroyed`; Terraform state contains `0` resources |
| HTTPS readiness | API Gateway → ALB → ECS returned HTTP `200` |
| Trust boundary | Direct `/api/session` without the BFF key returned HTTP `401` |
| Runtime | ECS desired `1`, running `1`, pending `0` |
| Data services | encrypted, non-public RDS PostgreSQL; Redis with in-transit and at-rest encryption |
| Container | immutable ECR tag equals the full Git SHA; digest begins `sha256:f2a167c59d91` |
| Operations | Container Insights, seven-day logs, dashboard, target 5xx and unhealthy-target alarms |

The time-limited endpoint was `https://cfzxgx733d.execute-api.us-east-1.amazonaws.com`. Cloudflare/Render remains the persistent free-tier demo.

## Conflict-safe Kanban proof

Two requests attempted to move different version-0 tasks into the same WIP-limited column at the same time. The recorded HTTP statuses were exactly `200` and `409`; a follow-up board query showed exactly three tasks in the target column, equal to its limit. This proves that the PostgreSQL locking and optimistic-version design still holds on managed RDS behind ECS, rather than only in local tests.

Infrastructure source and the Academy-account limitations are documented in [`infra/terraform/aws`](../../infra/terraform/aws/README.md). Terraform state, non-public AWS identifiers, ARNs, and credentials are excluded because the state contains generated secrets.
