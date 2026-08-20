# V-Core

V-Core is a portfolio-grade agile delivery workspace built to demonstrate backend and cloud engineering, not only UI implementation. A statically exported Next.js client runs on Cloudflare Pages, a thin Worker acts as a trusted backend-for-frontend (BFF), and a Java 21 Spring Boot modular monolith owns business data in PostgreSQL with Redis as a disposable cache.

## Live demo

- Application: [v-core-saas.pages.dev](https://v-core-saas.pages.dev)
- Backend readiness: [v-core-api.onrender.com/actuator/health/readiness](https://v-core-api.onrender.com/actuator/health/readiness)
- Public deployment proof: [docs/evidence/live-deployment.md](docs/evidence/live-deployment.md)

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

Open `http://localhost:8788`. The BFF injects the local demo identity and forwards `/api/*` to Spring Boot. Direct backend documentation is available at `http://localhost:8080/swagger-ui.html` while the Compose profile is running.

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

The live demo currently uses this split with `BACKEND_ORIGIN` and matching BFF secrets configured in provider secret stores. AWS/Terraform deployment evidence can be added later without duplicating application code or creating a second project.

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
