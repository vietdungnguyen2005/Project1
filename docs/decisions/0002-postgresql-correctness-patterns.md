# ADR 0002: PostgreSQL-backed correctness patterns

- Status: accepted
- Date: 2026-08-09

## Context

Concurrent task moves, stale edits, and network retries can corrupt a Kanban board if correctness relies on browser state or cache timing.

## Decision

Use JPA optimistic versions for stale-write detection, a transactional database lock for WIP-limit enforcement, a unique durable idempotency record for retried commands, and a transactional outbox for audit/realtime events.

## Consequences

- Conflicts become explicit API outcomes rather than silent last-write-wins behavior.
- Redis outages can degrade performance but cannot invalidate committed business state.
- Integration tests must execute against PostgreSQL, not an in-memory substitute.
- Outbox delivery is at least once, so consumers must be idempotent.

