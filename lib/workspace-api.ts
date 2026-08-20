import { DEMO_WORKSPACE_ID } from "@/lib/vcore-config";

export type WorkspaceMember = {
  id: string;
  name: string;
  email: string;
  role: "OWNER" | "ADMIN" | "MEMBER" | "VIEWER";
};

export type WorkspaceInvitation = {
  id: string;
  email: string;
  role: "ADMIN" | "MEMBER" | "VIEWER";
  status: "PENDING";
  expiresAt: string;
};

export type WorkflowColumn = {
  id: string;
  name: string;
  category: "BACKLOG" | "TODO" | "IN_PROGRESS" | "DONE";
  position: number;
  wipLimit: number | null;
  version: number;
};

export type WorkspaceProject = {
  id: string;
  name: string;
  key: string;
  description: string;
  activeSprint: {
    id: string;
    name: string;
    goal: string;
    status: "ACTIVE";
  } | null;
  columns: WorkflowColumn[];
};

export type WorkspaceOverview = {
  projects: WorkspaceProject[];
  members: WorkspaceMember[];
  invitations: WorkspaceInvitation[];
};

export type CreateProjectCommand = {
  name: string;
  key: string;
  description: string;
  sprintName: string;
  sprintGoal: string;
};

async function parseJson<T>(response: Response): Promise<T> {
  if (!response.ok) {
    throw new Error(`Workspace API failed with ${response.status}`);
  }
  return response.json() as Promise<T>;
}

export async function fetchWorkspaceOverview(signal?: AbortSignal) {
  const response = await fetch(`/api/workspaces/${DEMO_WORKSPACE_ID}/overview`, {
    signal,
    headers: { Accept: "application/json" }
  });
  return parseJson<WorkspaceOverview>(response);
}

export async function inviteWorkspaceMember(
  email: string,
  role: WorkspaceInvitation["role"],
  idempotencyKey: string
) {
  const response = await fetch(`/api/workspaces/${DEMO_WORKSPACE_ID}/invitations`, {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      "Idempotency-Key": idempotencyKey
    },
    body: JSON.stringify({ email: email.trim().toLowerCase(), role })
  });
  return parseJson<WorkspaceInvitation>(response);
}

export async function createWorkspaceProject(
  command: CreateProjectCommand,
  idempotencyKey: string
) {
  const response = await fetch(`/api/workspaces/${DEMO_WORKSPACE_ID}/projects`, {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      "Idempotency-Key": idempotencyKey
    },
    body: JSON.stringify({ ...command, key: command.key.trim().toUpperCase() })
  });
  return parseJson<WorkspaceProject>(response);
}

export async function updateWorkflowWipLimit(
  projectId: string,
  columnId: string,
  wipLimit: number,
  expectedVersion: number,
  idempotencyKey: string
) {
  const response = await fetch(
    `/api/workspaces/${DEMO_WORKSPACE_ID}/projects/${encodeURIComponent(projectId)}/workflow-columns/${encodeURIComponent(columnId)}`,
    {
      method: "PATCH",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        "Idempotency-Key": idempotencyKey
      },
      body: JSON.stringify({ wipLimit, expectedVersion })
    }
  );
  return parseJson<WorkflowColumn>(response);
}
