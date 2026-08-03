# V-Core SaaS

V-Core SaaS is a high-performance agile project management and collaboration workspace built with Next.js App Router, strict TypeScript, Zustand, TanStack Query, Tailwind CSS v4, and Framer Motion.

The app is intentionally optimized around two real SaaS pain points:

- INP-sensitive task updates: Kanban status moves, inline title editing, deferred filtering, debounced commits, transition-wrapped updates, and memoized task columns/cards.
- Long-lived tab cleanup: one cleanup registry owns timers, abort controllers, event listeners, query cancellation, and ephemeral state flushing on unmount.

## Tech Stack

- Next.js App Router with static export for Cloudflare Pages
- TypeScript strict mode
- Tailwind CSS v4
- Zustand with local task persistence
- TanStack Query for client cache strategy
- Framer Motion micro-interactions
- Cloudflare Pages Worker API with D1 persistence
- Vitest for proof-oriented logic tests
- Playwright for desktop/mobile e2e coverage
- Wrangler for Cloudflare Pages deployment

## Development

```powershell
npm install
npm run dev
```

## Verification

```powershell
npm run typecheck
npm run lint
npm run test
npm run e2e
npm run build
npm run proof:performance
npm run proof:api
```

Latest local proof generated:

- Performance proof: `reports/performance-proof.json`
- API proof: `reports/api-proof.json`
- Lighthouse proof: `reports/lighthouse.json`

Observed proof on this workstation:

- 10,000-task inline title update: under 50ms
- 10,000-task Kanban status update: under 50ms
- 10,000-task filter/group pass: under 50ms
- Cleanup registry: timer cleared, abort controller aborted, listener removed, cache flush called
- Cloudflare D1 API: health/session/tasks/create/update verified
- Cloudflare production URL: https://v-core-saas.pages.dev
- Lighthouse on Cloudflare production: Performance 100, Accessibility 100, Best Practices 100, SEO 100

## Backend API

Production deployments include an advanced-mode Cloudflare Pages Worker at `public/_worker.js`.

Endpoints:

- `GET /api/health`
- `GET /api/session`
- `GET /api/workspaces`
- `GET /api/tasks`
- `POST /api/tasks`
- `PATCH /api/tasks/:id`

The frontend uses optimistic Zustand updates and syncs to D1 after the initial paint. If the API is unavailable locally, the app falls back to local cache.

## Cloudflare Pages

The project exports to `out/` and can be deployed as static assets.

Required environment keys can live in `.env` in this project folder or in the parent folder:

```text
ACCOUNT_ID=
API_TOKEN=
```

The deploy script maps those to Wrangler's `CLOUDFLARE_ACCOUNT_ID` and `CLOUDFLARE_API_TOKEN`.

```powershell
.\scripts\deploy-cloudflare.ps1
```

The script builds the app, creates the `v-core-saas` Pages project if it does not exist, and deploys `out/`.

## CI/CD

GitHub Actions are included:

- `.github/workflows/ci.yml` runs typecheck, lint, Vitest, build, performance proof, Playwright e2e, and audit.
- `.github/workflows/deploy-cloudflare.yml` deploys `out/` to Cloudflare Pages on `main`.

Set these repository secrets before relying on automated deploys:

```text
CLOUDFLARE_ACCOUNT_ID
CLOUDFLARE_API_TOKEN
```
