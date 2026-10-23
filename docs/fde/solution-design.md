# Customer workflow delivery design

All client scenarios are synthetic demonstrations. Reuse decisions and the pre-implementation plan are in the [capability inventory](requirements-matrix.md).

## Alternatives and scope

| Alternative | Trade-off | Decision |
| --- | --- | --- |
| Separate application per customer | Duplicates task/auth/reliability code and creates divergent deployments | Rejected |
| Edit live project columns whenever configuration changes | Requires task remapping, concurrency/migration policy and rollback | Explicitly out of scope |
| Workspace-scoped immutable published template revisions, copied at provisioning | Small additive model; preserves project history and existing commands | Implemented |

A single validated POST publishes and activates a complete revision atomically. There is no editable draft endpoint, activation toggle, automatic latest version, retirement workflow or template deletion API. Invalid definitions never become published records. Versions of the same key may coexist. A new project selects one specific template ID; existing projects retain their copied columns even when another revision is published. Existing per-project WIP administration remains possible and does not edit the source template.

## Persistence and consistency

Additive migration `V7__workflow_templates.sql` introduces `workflow_template` and a nullable `project.workflow_template_id`. No old migration is edited. Historical projects keep a null provenance reference and unchanged columns.

- Unique `(workspace_id, template_key, revision)` prevents competing publications overwriting a revision. The API provides no mutation of published definitions.
- A composite project/template FK includes workspace ID, in addition to the service's scoped lookup. A foreign template ID cannot be applied to a local project.
- Publishing reuses `IdempotencyService` and `WorkspaceEventRecorder`. Definition hash uses an operation prefix and canonical JSON with columns sorted by position; labels are trimmed and compared case-insensitively for uniqueness.
- Templated project hashes use an operation prefix, explicit template ID and length-framed text fields, so delimiter characters cannot move data between fields. The omitted-template path retains its legacy fingerprint for compatibility with existing records; this feature does not upgrade all historical/default idempotency formats.
- Provisioning reuses the existing transaction: project, active sprint, copied columns, template reference, activity/outbox and stored idempotency response commit or roll back together. Omitted template ID retains the original defaults and default request fingerprint.
- New versions never modify existing projects or silently remap tasks.
- Existing task create/move WIP locks, optimistic versions, assignment checks, session/CSRF and SSE authorization are unchanged.

## API contract

All routes below are under `/api/workspaces/{workspaceId}` and require trusted BFF transport plus current workspace access. Publishing/provisioning additionally require an authenticated OPERATOR, valid CSRF and workspace OWNER/ADMIN. Knowing the BFF secret does not grant user permissions.

| Method and route | Result |
| --- | --- |
| `GET /workflow-presets` | 200 array of two read-only synthetic `Definition` suggestions; no records are created |
| `GET /workflow-templates` | 200 array of this workspace's published revisions |
| `GET /workflow-templates/{id}` | 200 one scoped revision; 404 for an ID outside this workspace when caller otherwise has local access |
| `POST /workflow-templates` | 201 published revision; requires `Idempotency-Key` |
| `POST /projects` | Existing 201 project response plus nullable `workflowTemplateId`; optional request field of that name selects a revision |

Example publication body (Client B, synthetic):

```json
{
  "key": "CLIENT_B_OPERATIONS", "version": 1,
  "name": "Client B — Business operations (synthetic)", "scenario": "CLIENT_B",
  "columns": [
    {"name":"RECEIVED","category":"BACKLOG","position":0,"wipLimit":null},
    {"name":"VALIDATION","category":"TODO","position":1,"wipLimit":2},
    {"name":"APPROVAL","category":"IN_PROGRESS","position":2,"wipLimit":1},
    {"name":"COMPLETED","category":"DONE","position":3,"wipLimit":null}
  ]
}
```

The response includes `id`, all definition fields and `synthetic: true`. Every stored revision is published/active. Project creation keeps the existing fields `name`, `key`, `description`, `sprintName`, `sprintGoal`, and optionally adds `workflowTemplateId`. The full returned columns include their actual IDs and versions; frontend must use these IDs rather than map customer labels to the seeded board's fixed IDs.

Validation: key `[A-Z][A-Z0-9_]{1,39}`, revision 1–100000, nonblank name up to 120 characters, scenario CLIENT_A/CLIENT_B/CUSTOM, 2–12 columns, unique nonblank column names up to 80 characters, contiguous positions from zero, supported categories BACKLOG/TODO/IN_PROGRESS/DONE, nullable WIP or 1–100. First category is BACKLOG/TODO; last is DONE; no earlier column is DONE. Category is an internal semantic classification, not a customer-specific approval role.

| Error | Meaning / operator action |
| --- | --- |
| 400 | Invalid DTO or workflow definition; correct fields, no partial template activation |
| 401 | Missing/unusable authenticated mutation session or untrusted BFF; authenticate/configure transport |
| 403 | CSRF/workspace/admin authorization denied; do not work around by changing IDs |
| 404 | Template not found in the authorized workspace; verify selection |
| 409 | Revision uniqueness, idempotency mismatch, stale version or WIP/DB constraint conflict; inspect detail and refresh before a new action |
| 405 at Worker | Method not exposed; templates are not editable/deletable through the BFF |

Existing problem responses are retained. Matching retries replay a 201 result without a duplicate project/publication event. Same revision with another command key conflicts, rather than silently replacing it. Idempotency remains workspace/key scoped with the existing retention limitations; this slice does not claim permanent cross-actor or exactly-once guarantees.

## Frontend boundary

The current operations form adds scenario preview, nullable WIP inputs, explicit revision publication and selection. A pending scenario cannot silently submit the default workflow. Existing project creation stays available when no scenario is chosen. Unconfirmed publication/project/task creation retains an in-memory command/key and locks edits for an unchanged retry; it is not persistent cross-tab/reload recovery.

The project workbench reuses task APIs and operator authorization, grouping tasks by real column IDs. It shows conflict details, refreshes after mutations/conflicts, supports workspace-member assignment and displays recent workspace audit. It does not replace the existing seeded Sprint Board. Audit display is workspace-wide, not an exhaustive per-project export. Task list remains limited to 200; shown counts are not authoritative WIP occupancy. Cross-operator synchronization uses explicit refresh here, without claiming new durable SSE delivery.
