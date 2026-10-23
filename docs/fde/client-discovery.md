# Synthetic client discovery

These are two hypothetical delivery scenarios for a portfolio demonstration, not actual consulting engagements. No customer interviews, commercial outcomes or production adoption are claimed.

## Business problem

The existing product provisions one fixed software-delivery board. A consulting team needs repeatable onboarding for teams with different stage labels and capacity limits, without forking the product or rewriting its task commands.

| Scenario | Stakeholders | Requested workflow | Capacity and authority |
| --- | --- | --- | --- |
| Client A — synthetic software team | Engineering lead, reviewers, contributors | BACKLOG → IN_PROGRESS → REVIEW → DONE | Engineering WIP 3, review WIP 2; workspace members can receive task assignments |
| Client B — synthetic business operations | Operations lead, validators, approvers | RECEIVED → VALIDATION → APPROVAL → COMPLETED | Validation WIP 2, approval WIP 1; only workspace OWNER/ADMIN can publish templates/provision projects/change WIP |

The numbers are demonstrative acceptance inputs, not staffing recommendations or measured customer capacity.

## Assumptions to confirm before a real engagement

- Does a stage represent visual ordering or an enforced transition/approval policy? This slice delivers ordered columns, not a mandatory transition graph or a payment/approval authorization engine.
- Are task assignees workspace members or a separate project roster? This slice reuses workspace-member task assignment; it does not introduce project-specific memberships.
- Who administers configuration? Existing workspace OWNER/ADMIN roles apply to both scenarios; choosing a scenario never grants a role.
- Should an existing board change when a template changes? No: projects copy a chosen immutable revision. Migrating active tasks requires a separate discovery and safe migration plan.
- What does a retry mean? Keep the same operation key and unchanged payload after an unconfirmed response. Do not interpret a browser timeout as rollback.
- Does publishing version 2 supersede version 1? No automatic latest/retirement behavior is assumed. Selection is explicit by published template ID.

## Operational constraints

Keep Java/Spring Boot, PostgreSQL, existing sessions/CSRF, the Pages Worker, existing React Query conventions and Helm configuration. Do not purchase services or change dependencies. Public demo is read-only; operator login is needed for writes. Customer-facing data and screenshots are synthetic only.

The UI operates within the existing demo workspace. Client A/B selection is scenario selection, **not** tenant switching. Backend tenant boundaries are verified independently with separate workspace records. The demo is local until a separately authorized deployment and smoke test have completed.

## Success criteria

A stakeholder can preview a scenario, explicitly publish/select a validated revision, create a complete project, observe its copied columns, create/assign/move tasks, see a capacity conflict and inspect committed audit history. Invalid, unauthorized, foreign-template and duplicate requests must not leave partial state. See [acceptance tests](acceptance-tests.md).
