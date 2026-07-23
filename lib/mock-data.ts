import type { ActivityItem, Column, Task } from "@/lib/types";

export const columns: Column[] = [
  { id: "backlog", title: "Backlog", limit: 8 },
  { id: "in-progress", title: "In Progress", limit: 4 },
  { id: "review", title: "Review", limit: 3 },
  { id: "done", title: "Done", limit: 10 }
];

export const initialTasks: Task[] = [
  {
    id: "vc-104",
    title: "Instrument INP budget for inline title edits",
    owner: "An Le",
    status: "backlog",
    priority: "high",
    points: 5,
    updatedAt: "2m ago",
    tags: ["web-vitals", "editor"]
  },
  {
    id: "vc-122",
    title: "Ship granular Kanban memo boundaries",
    owner: "Minh Tran",
    status: "in-progress",
    priority: "critical",
    points: 8,
    updatedAt: "9m ago",
    tags: ["performance", "kanban"]
  },
  {
    id: "vc-138",
    title: "Add long-session cleanup controller",
    owner: "Bao Nguyen",
    status: "in-progress",
    priority: "high",
    points: 5,
    updatedAt: "14m ago",
    tags: ["cleanup", "effects"]
  },
  {
    id: "vc-149",
    title: "Cache sprint metrics with TanStack Query",
    owner: "Linh Pham",
    status: "review",
    priority: "medium",
    points: 3,
    updatedAt: "23m ago",
    tags: ["query", "ssr"]
  },
  {
    id: "vc-151",
    title: "Validate dark mode contrast in command surfaces",
    owner: "Quan Vo",
    status: "done",
    priority: "low",
    points: 2,
    updatedAt: "41m ago",
    tags: ["a11y", "theme"]
  }
];

export const activity: ActivityItem[] = [
  {
    id: "a1",
    actor: "Minh",
    action: "moved",
    target: "Kanban memo boundaries into active sprint",
    time: "1 min"
  },
  {
    id: "a2",
    actor: "Bao",
    action: "closed listeners for",
    target: "presence websocket cleanup",
    time: "7 min"
  },
  {
    id: "a3",
    actor: "Linh",
    action: "published",
    target: "Lighthouse 99 performance baseline",
    time: "18 min"
  }
];

export const sprintMetrics = {
  velocity: 42,
  cycleTime: "1.8d",
  activeUsers: 128,
  inpP95: "38ms",
  lighthouse: 99
};
