import type { ActivityItem, SessionUser, Task, TaskDraft, TaskStatus } from "@/lib/types";
import { DEMO_PROJECT_ID, DEMO_WORKSPACE_ID, WORKFLOW_COLUMN_IDS } from "@/lib/vcore-config";

const taskCollectionUrl =
  `/api/workspaces/${DEMO_WORKSPACE_ID}/projects/${DEMO_PROJECT_ID}/tasks`;

type ApiTasksResponse = {
  items: Task[];
  truncated: boolean;
};

type ApiSessionResponse = {
  user: {
    id: string;
    email: string;
    name: string;
  };
  workspaces: Array<{
    id: string;
    name: string;
    slug: string;
    role: "OWNER" | "ADMIN" | "MEMBER" | "VIEWER";
  }>;
};

export type TaskMoveResult = Pick<
  Task,
  "id" | "columnId" | "status" | "position" | "version" | "updatedAt"
>;

export type TaskEditResult = Pick<Task, "id" | "title" | "version" | "updatedAt">;

type ApiProblem = {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
};

export type ApiActivity = {
  id: string;
  actor: string;
  action:
    | "TASK_CREATED"
    | "TASK_RENAMED"
    | "TASK_MOVED"
    | "TASK_ASSIGNED"
    | "TASK_COMMENTED"
    | "INVITATION_CREATED"
    | "PROJECT_CREATED"
    | "WORKFLOW_WIP_UPDATED";
  aggregateType: string;
  aggregateId: string;
  aggregateKey: string | null;
  aggregateTitle: string | null;
  details: Record<string, unknown>;
  occurredAt: string;
};

export class ApiProblemError extends Error {
  constructor(
    readonly status: number,
    readonly problem: ApiProblem
  ) {
    super(problem.detail || problem.title || `API request failed with ${status}`);
    this.name = "ApiProblemError";
  }
}

export function toCreateTaskCommand(draft: TaskDraft) {
  return {
    title: draft.title.trim(),
    columnId: WORKFLOW_COLUMN_IDS[draft.status],
    priority: draft.priority,
    points: draft.points,
    tags: draft.tags.map((tag) => tag.trim()).filter(Boolean)
  };
}

export function toMoveTaskCommand(task: Task, status: TaskStatus) {
  return {
    targetColumnId: WORKFLOW_COLUMN_IDS[status],
    expectedVersion: task.version,
    position: task.position + 1000
  };
}

export function toActivityItem(activity: ApiActivity, now = Date.now()): ActivityItem {
  const actionLabels: Record<ApiActivity["action"], string> = {
    TASK_CREATED: "created",
    TASK_RENAMED: "renamed",
    TASK_MOVED: "moved",
    TASK_ASSIGNED: "assigned",
    TASK_COMMENTED: "commented on",
    INVITATION_CREATED: "created invitation for",
    PROJECT_CREATED: "created project",
    WORKFLOW_WIP_UPDATED: "updated workflow for"
  };
  const elapsedMinutes = Math.max(
    0,
    Math.floor((now - Date.parse(activity.occurredAt)) / 60_000)
  );

  return {
    id: activity.id,
    actor: activity.actor,
    action: actionLabels[activity.action],
    target:
      [activity.aggregateKey, activity.aggregateTitle].filter(Boolean).join(" ") ||
      activity.aggregateId,
    time: elapsedMinutes < 1 ? "just now" : `${elapsedMinutes} min`
  };
}

async function parseJson<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const problem = await response.json().catch(() => ({} as ApiProblem));
    throw new ApiProblemError(response.status, problem as ApiProblem);
  }

  return response.json() as Promise<T>;
}

const jsonHeaders = (idempotencyKey?: string) => ({
  Accept: "application/json",
  "Content-Type": "application/json",
  ...(idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {})
});

export async function fetchCloudSession(signal?: AbortSignal) {
  const response = await fetch("/api/session", {
    signal,
    headers: { Accept: "application/json" }
  });
  const session = await parseJson<ApiSessionResponse>(response);
  const workspace = session.workspaces[0];

  if (!workspace) {
    throw new ApiProblemError(403, {
      title: "No workspace access",
      detail: "The signed-in user does not belong to a workspace."
    });
  }

  const user: SessionUser = {
    email: session.user.email,
    name: session.user.name,
    provider: "bff",
    role: workspace.role.toLowerCase() as SessionUser["role"]
  };

  return { user, workspace };
}

export async function fetchCloudTasks(signal?: AbortSignal) {
  const response = await fetch(taskCollectionUrl, {
    signal,
    headers: { Accept: "application/json" }
  });
  const payload = await parseJson<ApiTasksResponse>(response);

  return {
    tasks: payload.items,
    storage: "postgres" as const,
    truncated: payload.truncated
  };
}

export async function fetchCloudActivities(signal?: AbortSignal) {
  const response = await fetch(`/api/workspaces/${DEMO_WORKSPACE_ID}/activities?limit=20`, {
    signal,
    headers: { Accept: "application/json" }
  });
  const payload = await parseJson<{ items: ApiActivity[] }>(response);
  return payload.items.map((activity) => toActivityItem(activity));
}

export async function createCloudTask(draft: TaskDraft, idempotencyKey: string) {
  const response = await fetch(taskCollectionUrl, {
    method: "POST",
    headers: jsonHeaders(idempotencyKey),
    body: JSON.stringify(toCreateTaskCommand(draft))
  });

  return parseJson<Task>(response);
}

export async function moveCloudTask(
  task: Task,
  status: TaskStatus,
  idempotencyKey: string
) {
  const response = await fetch(`${taskCollectionUrl}/${encodeURIComponent(task.id)}/moves`, {
    method: "POST",
    headers: jsonHeaders(idempotencyKey),
    body: JSON.stringify(toMoveTaskCommand(task, status))
  });

  return parseJson<TaskMoveResult>(response);
}

export async function renameCloudTask(task: Task, title: string, idempotencyKey: string) {
  const response = await fetch(`${taskCollectionUrl}/${encodeURIComponent(task.id)}`, {
    method: "PATCH",
    headers: jsonHeaders(idempotencyKey),
    body: JSON.stringify({ title: title.trim(), expectedVersion: task.version })
  });

  return parseJson<TaskEditResult>(response);
}
