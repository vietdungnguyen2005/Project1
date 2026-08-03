import type { SessionUser, Task } from "@/lib/types";

type ApiTasksResponse = {
  tasks: Task[];
  storage: "d1" | "memory";
  syncVersion: string;
};

type ApiSessionResponse = {
  user: SessionUser;
  workspace: {
    id: string;
    name: string;
    plan: string;
  };
};

async function parseJson<T>(response: Response): Promise<T> {
  if (!response.ok) {
    throw new Error(`API request failed with ${response.status}`);
  }

  return response.json() as Promise<T>;
}

export async function fetchCloudSession(signal?: AbortSignal) {
  const response = await fetch("/api/session", {
    signal,
    headers: {
      "Accept": "application/json"
    }
  });

  return parseJson<ApiSessionResponse>(response);
}

export async function fetchCloudTasks(signal?: AbortSignal) {
  const response = await fetch("/api/tasks", {
    signal,
    headers: {
      "Accept": "application/json"
    }
  });

  return parseJson<ApiTasksResponse>(response);
}

export async function createCloudTask(task: Task) {
  const response = await fetch("/api/tasks", {
    method: "POST",
    headers: {
      "Accept": "application/json",
      "Content-Type": "application/json"
    },
    body: JSON.stringify(task)
  });

  return parseJson<{ task: Task }>(response);
}

export async function patchCloudTask(taskId: string, patch: Partial<Pick<Task, "title" | "status" | "updatedAt">>) {
  const response = await fetch(`/api/tasks/${encodeURIComponent(taskId)}`, {
    method: "PATCH",
    headers: {
      "Accept": "application/json",
      "Content-Type": "application/json"
    },
    body: JSON.stringify(patch)
  });

  return parseJson<{ task: Task }>(response);
}
