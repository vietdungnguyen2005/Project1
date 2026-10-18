# V-Core

### Dependency audit policy (2026-10-18)

Run `npm run audit:dependencies`. Runtime dependencies retain a strict high/critical audit. The full audit uses `audit-ci.json` with one user-approved, exact advisory-and-lint-path exception for GHSA-vfj7-8cjw-p6xm (unpatched braces stack exhaustion), expiring at 00:00 Asia/Bangkok on 2026-11-18. This is accepted risk, not a vulnerability fix or a zero-findings claim. Lint only trusted repository configuration; do not feed untrusted root-directory glob patterns into the lint tool. All other high/critical findings and audit-service failures still block CI. Remove the exception when upstream is patched; do not renew automatically.

V-Core is a portfolio-grade agile delivery workspace built to demonstrate backend and cloud engineering, not only UI implementation. A statically exported Next.js client runs on Cloudflare Pages, a thin Worker acts as a trusted backend-for-frontend (BFF), and a Java 21 Spring Boot modular monolith owns business data in PostgreSQL. Redis connectivity remains configured, but authorization no longer uses a Redis membership cache.

## Live demo

- Application: [v-core-saas.pages.dev](https://v-core-saas.pages.dev)
- Backend readiness: [v-core-api.onrender.com/actuator/health/readiness](https://v-core-api.onrender.com/actuator/health/readiness)
- Public deployment proof: [docs/evidence/live-deployment.md](docs/evidence/live-deployment.md)
- Short-lived AWS deployment proof: [docs/evidence/aws-deployment.md](docs/evidence/aws-deployment.md)

The backend uses Render's free web-service tier, so the first request after 15 minutes without inbound traffic can take about a minute while the container wakes up.

## What this repository proves

- Correct concurrent Kanban moves with PostgreSQL row locking, WIP limits, and optimistic versions.
- Retry-safe create, rename, and move commands backed by durable idempotency records.
- Tenant-scoped access control enforced by workspace membership and role.
- Transactional audit and outbox records, with an SSE activity feed for the UI.
- Observable Spring Boot runtime with health probes, Prometheus metrics, correlation IDs, and optional OpenTelemetry export.
- Reproducible local delivery using Docker Compose and production-oriented Kubernetes manifests through Helm.
- Automated frontend, backend, container, dependency, and Helm checks in GitHub Actions.

Architecture and trade-offs are documented in [docs/architecture.md](docs/architecture.md) and [docs/decisions](docs/decisions).

## Stack

| Layer | Technology |
| --- | --- |
| Web | Next.js, React, strict TypeScript, Zustand, TanStack Query, Tailwind CSS |
| Edge BFF | Cloudflare Pages Worker |
| Backend | Java 21, Spring Boot 3.5, Spring Security, Spring Data JPA |
| Data | PostgreSQL, Flyway, Redis |
| Reliability | Idempotency keys, optimistic versions, row locks, transactional outbox |
| Observability | Actuator, Prometheus, OpenTelemetry, request correlation |
| Delivery | Docker, GitHub Actions, Helm, Kubernetes |

## Local full stack

Requirements: Node.js, Docker Desktop, and Java 21 when running Maven outside Docker.

Copy `.dev.vars.example` to `.dev.vars`. The default values are only for local development and already match `compose.yaml`.

If PostgreSQL or Redis already uses the default host ports, set `VCORE_POSTGRES_PORT` and `VCORE_REDIS_PORT` before starting Compose and use the same ports in the backend URLs.

```powershell
npm ci
docker compose --profile app up -d --build
npm run build
npx wrangler pages dev out --port 8788
```

Open `http://localhost:8788`. The BFF forwards allowlisted `/api/*` routes and session cookies to Spring Boot; it does **not** create user identity from email headers. Direct backend documentation is available at `http://localhost:8080/swagger-ui.html` while the Compose profile is running.

### Workspace authentication (local remediation)

The public demo is read-only, using a dedicated VIEWER identity only when the backend explicitly enables `VCORE_DEMO_READ_ENABLED=true` (Compose enables it). Without that flag, anonymous workspace access is denied. `DEMO_USER_EMAIL` and `Cf-Access-Authenticated-User-Email` do not authenticate users.

Flyway V6 adds PostgreSQL account/session tables and a separate demo viewer; older migrations are unchanged. Configure `AUTH_BOOTSTRAP_USERNAME` (an existing non-demo `app_user.email`) and `AUTH_BOOTSTRAP_PASSWORD_HASH` (BCrypt) in the backend environment before initial provisioning. There is no default password or automatic OWNER assignment. Workspace membership still determines which tenant and operations the authenticated account can access. An account with application role OPERATOR is eligible to write, but cannot bypass its workspace role.

Both bootstrap variables blank means no account is provisioned; existing accounts are not overwritten. There is no public signup/password recovery UI. Spring Boot does not load Next.js `.env.local` automatically.

Session cookies are HttpOnly/SameSite=Lax/Secure with a 15-minute idle timeout. `AUTH_SECURE_COOKIE=false` is allowed only for local HTTP (Compose sets it); production must use HTTPS. Disable `auth_account.enabled` to revoke the next request; membership is read from PostgreSQL for every read/write/admin authorization rather than Redis. Login has a shared PostgreSQL budget of 10 attempts/minute/backend peer address; behind the BFF this is not a per-end-user-IP limiter.

The UI keeps search/read access available but disables write controls for public readers. Sign-in/sign-out reload the page to discard the previous identity's query/form state. All mutations fetch a current CSRF token; the Java service also enforces the session, role and CSRF.

Real local browser smoke (run backend verify and frontend build first; do not rebuild while the JAR is running):

```powershell
pwsh -NoProfile -File tools/verify-operator-session.ps1
```

This starts a real local Pages Worker, the packaged Java API and disposable PostgreSQL/Redis. It does not use production credentials/data. See [remediation evidence](docs/evidence/remediation-2026-10-18.md). These changes are local and not yet rolled out to the live links above.

Active SSE subscriptions now check current membership, account availability and the original session's expiry before each event and heartbeat. Invalid access or an authorization-storage failure closes and unregisters the stream. A heartbeat does not extend session idle time. Idle revalidation uses a 15-second default interval (`vcore.sse.heartbeat-interval`); this is not a hard wall-clock SLA under slow DB/network conditions. A change committed after an authorization check cannot retroactively withdraw bytes already being sent.

The real smoke also keeps a separate SSE HTTP connection open while the browser logs out, then verifies EOF. Its local heartbeat interval is reduced to 250 ms for this check; production defaults are unchanged.

To stop the stack without deleting database volumes:

```powershell
docker compose --profile app down
```

## Verification

Frontend:

```powershell
npm run verify
```

Backend integration tests use Testcontainers with real PostgreSQL and Redis instances:

```powershell
cd backend
.\mvnw.cmd verify
```

Infrastructure:

```powershell
helm lint infra/helm/v-core
helm template v-core infra/helm/v-core
docker build -t v-core-backend:local backend
```

## Runtime configuration

No secret belongs in Git. `.env.example` documents deployment variables; `.dev.vars.example` documents the local Cloudflare BFF contract.

The two shared-secret values must match:

- Cloudflare Worker: `BFF_SHARED_SECRET`
- Spring Boot: `VCORE_BFF_SHARED_SECRET`

Production data services use `DATABASE_URL`/PostgreSQL credentials and `REDIS_URL`. Grafana Cloud/OpenTelemetry and Google Cloud placeholders are optional until those accounts are connected.

## Deployment model

The long-lived free demo is split by runtime, not by repository:

1. Cloudflare Pages serves the static frontend and its BFF Worker.
2. A container platform hosts the Spring Boot image.
3. Neon provides PostgreSQL and Upstash provides Redis.

The live demo uses this split with `BACKEND_ORIGIN` and matching BFF secrets configured in provider secret stores. The same backend image has also been verified on ECS with managed RDS and Redis through the repository's Terraform stack; AWS remains short-lived evidence rather than a duplicate project.

## Repository map

```text
app, components, hooks, lib, store  Next.js application
public/_worker.js                   Cloudflare BFF
backend                            Spring Boot modular monolith
infra/helm/v-core                  Kubernetes deployment proof
docs                               scope, architecture, ADRs
tests                              Vitest and Playwright tests
```

## Current scope boundary

The completed portfolio scope covers session resolution; task listing, creation, rename, movement, assignment and comments; project plus active-sprint provisioning; workflow WIP administration; workspace invitations; conflict handling; audit history; live invalidation; and recruiter-facing evidence. Billing, file uploads, invitation email delivery, chat, and notification delivery are deliberately outside scope; see [docs/product-scope.md](docs/product-scope.md).
