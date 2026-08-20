import { DEMO_PROJECT_ID, DEMO_WORKSPACE_ID } from "@/lib/vcore-config";

const taskUrl = (taskId: string) =>
  `/api/workspaces/${DEMO_WORKSPACE_ID}/projects/${DEMO_PROJECT_ID}/tasks/${encodeURIComponent(taskId)}`;

async function parseJson<T>(response: Response): Promise<T> {
  if (!response.ok) {
    throw new Error(`Task collaboration API failed with ${response.status}`);
  }
  return response.json() as Promise<T>;
}

export type TaskAssignment = {
  id: string;
  assignee: { id: string; name: string; email: string };
  version: number;
  updatedAt: string;
};

export type TaskComment = {
  id: string;
  author: string;
  body: string;
  createdAt: string;
};

export async function assignTaskMember(
  taskId: string,
  assigneeId: string,
  expectedVersion: number,
  idempotencyKey: string
) {
  const response = await fetch(`${taskUrl(taskId)}/assignee`, {
    method: "PATCH",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      "Idempotency-Key": idempotencyKey
    },
    body: JSON.stringify({ assigneeId, expectedVersion })
  });
  return parseJson<TaskAssignment>(response);
}

export async function addTaskComment(taskId: string, body: string, idempotencyKey: string) {
  const response = await fetch(`${taskUrl(taskId)}/comments`, {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      "Idempotency-Key": idempotencyKey
    },
    body: JSON.stringify({ body: body.trim() })
  });
  return parseJson<TaskComment>(response);
}

export async function fetchTaskComments(taskId: string, signal?: AbortSignal) {
  const response = await fetch(`${taskUrl(taskId)}/comments`, {
    signal,
    headers: { Accept: "application/json" }
  });
  const payload = await parseJson<{ items: TaskComment[] }>(response);
  return payload.items;
}
