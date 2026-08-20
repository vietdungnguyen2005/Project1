import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createCloudTask,
  toActivityItem,
  toCreateTaskCommand,
  toMoveTaskCommand
} from "@/lib/task-api";
import type { Task, TaskDraft } from "@/lib/types";
import { mergeTaskMoveResult } from "@/lib/task-sync";

describe("task API contract", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("maps a task draft to the backend create command", () => {
    const draft: TaskDraft = {
      title: "  Retry-safe task creation  ",
      owner: "Demo Owner",
      status: "review",
      priority: "high",
      points: 5,
      tags: [" reliability ", "api"]
    };

    expect(toCreateTaskCommand(draft)).toEqual({
      title: "Retry-safe task creation",
      columnId: "00000000-0000-0000-0000-000000000403",
      priority: "high",
      points: 5,
      tags: ["reliability", "api"]
    });
  });

  it("includes the last-seen version in a move command", () => {
    const task = {
      id: "00000000-0000-0000-0000-000000000501",
      version: 7,
      position: 1000
    } as Task;

    expect(toMoveTaskCommand(task, "done")).toEqual({
      targetColumnId: "00000000-0000-0000-0000-000000000404",
      expectedVersion: 7,
      position: 2000
    });
  });

  it("creates through the tenant-scoped endpoint with an idempotency key", async () => {
    const draft: TaskDraft = {
      title: "Retry-safe task creation",
      owner: "Demo Owner",
      status: "backlog",
      priority: "high",
      points: 5,
      tags: ["api"]
    };
    const responseTask = {
      id: "task-id",
      key: "VC-152",
      title: draft.title,
      owner: draft.owner,
      status: draft.status,
      priority: draft.priority,
      points: draft.points,
      tags: draft.tags,
      columnId: "00000000-0000-0000-0000-000000000401",
      position: 2000,
      version: 0,
      updatedAt: "2026-08-09T10:00:00Z"
    } satisfies Task;
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(responseTask), { status: 201 }));
    vi.stubGlobal("fetch", fetchMock);

    await createCloudTask(draft, "create-task-test-key");

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/workspaces/00000000-0000-0000-0000-000000000100/projects/00000000-0000-0000-0000-000000000200/tasks",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({ "Idempotency-Key": "create-task-test-key" }),
        body: JSON.stringify(toCreateTaskCommand(draft))
      })
    );
  });

  it("reconciles an optimistic task with the committed server version", () => {
    const task = {
      id: "task-id",
      status: "in-progress",
      columnId: "column-in-progress",
      position: 1000,
      version: 4,
      updatedAt: "old"
    } as Task;

    expect(
      mergeTaskMoveResult(task, {
        id: "task-id",
        status: "review",
        columnId: "column-review",
        position: 2000,
        version: 5,
        updatedAt: "2026-08-09T10:00:00Z"
      })
    ).toMatchObject({
      status: "review",
      columnId: "column-review",
      position: 2000,
      version: 5,
      updatedAt: "2026-08-09T10:00:00Z"
    });
  });

  it("maps an audit record to a readable activity item", () => {
    expect(
      toActivityItem(
        {
          id: "activity-id",
          actor: "Demo Owner",
          action: "TASK_MOVED",
          aggregateType: "TASK",
          aggregateId: "task-id",
          aggregateKey: "VC-104",
          aggregateTitle: "Instrument INP budget",
          details: {},
          occurredAt: "2026-08-09T09:59:00Z"
        },
        Date.parse("2026-08-09T10:00:00Z")
      )
    ).toEqual({
      id: "activity-id",
      actor: "Demo Owner",
      action: "moved",
      target: "VC-104 Instrument INP budget",
      time: "1 min"
    });
  });

  it("maps collaboration audit records without leaking backend enum labels", () => {
    expect(
      toActivityItem({
        id: "activity-id",
        actor: "Demo Owner",
        action: "TASK_ASSIGNED",
        aggregateType: "TASK",
        aggregateId: "task-id",
        aggregateKey: "VC-104",
        aggregateTitle: "Instrument INP budget",
        details: {},
        occurredAt: "2026-08-09T10:00:00Z"
      })
    ).toMatchObject({ action: "assigned" });
  });

  it("maps workspace operations into recruiter-readable activity labels", () => {
    expect(
      toActivityItem({
        id: "activity-id",
        actor: "Demo Owner",
        action: "PROJECT_CREATED",
        aggregateType: "PROJECT",
        aggregateId: "project-id",
        aggregateKey: "EVD",
        aggregateTitle: "Evidence Delivery",
        details: {},
        occurredAt: "2026-08-09T10:00:00Z"
      })
    ).toMatchObject({ action: "created project", target: "EVD Evidence Delivery" });
  });
});
