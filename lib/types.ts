export type TaskStatus = "backlog" | "in-progress" | "review" | "done";

export type TaskPriority = "critical" | "high" | "medium" | "low";

export type Task = {
  id: string;
  title: string;
  owner: string;
  status: TaskStatus;
  priority: TaskPriority;
  points: number;
  updatedAt: string;
  tags: string[];
};

export type TaskDraft = {
  title: string;
  owner: string;
  status: TaskStatus;
  priority: TaskPriority;
  points: number;
  tags: string[];
};

export type Column = {
  id: TaskStatus;
  title: string;
  limit: number;
};

export type ActivityItem = {
  id: string;
  actor: string;
  action: string;
  target: string;
  time: string;
};

export type SyncState = "local" | "syncing" | "cloud" | "offline";

export type SessionUser = {
  email: string;
  name: string;
  provider: "cloudflare-access" | "demo";
  role: "owner" | "member";
};
