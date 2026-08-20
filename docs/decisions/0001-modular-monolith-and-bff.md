# ADR 0001: Modular monolith behind a same-origin BFF

- Status: accepted
- Date: 2026-08-09

## Context

V-Core needs to demonstrate backend, cloud, and operational engineering without creating distributed-system overhead that a portfolio project cannot justify. The frontend must remain live on a free Cloudflare Pages URL while the Java runtime may move between a local machine and free or temporary container platforms.

## Decision

Build one Spring Boot deployable with enforced domain modules. Keep the Cloudflare Worker as a thin same-origin BFF that proxies `/api/*`; it owns no business data or authorization decisions. Use PostgreSQL as the durable store and Redis only for sessions, rate limits, and cache.

## Consequences

- Transactions can protect the core concurrency pain point without distributed coordination.
- The code still demonstrates explicit service boundaries and can be split later if evidence justifies it.
- The frontend URL and API contract remain stable when the backend host changes.
- The BFF must forward identity, correlation, conditional-version, and idempotency headers safely.
- The legacy D1 business-data path is removed; PostgreSQL is the sole authoritative store.
