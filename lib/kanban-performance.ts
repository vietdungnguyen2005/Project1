import type { Column, Task, TaskDraft, TaskStatus } from "@/lib/types";

export type GroupedTasks = Record<TaskStatus, Task[]>;

export function createEmptyTaskGroups(columns: Column[]): GroupedTasks {
  return columns.reduce(
    (acc, column) => {
      acc[column.id] = [];
      return acc;
    },
    {
      backlog: [],
      "in-progress": [],
      review: [],
      done: []
    } as GroupedTasks
  );
}

export function filterTasks(tasks: Task[], query: string): Task[] {
  const normalized = query.trim().toLowerCase();

  if (!normalized) {
    return tasks;
  }

  return tasks.filter((task) =>
    [task.title, task.id, task.owner, task.priority, ...task.tags]
      .join(" ")
      .toLowerCase()
      .includes(normalized)
  );
}

export function groupTasksByStatus(tasks: Task[], columns: Column[]): GroupedTasks {
  const groups = createEmptyTaskGroups(columns);

  for (const task of tasks) {
    groups[task.status].push(task);
  }

  return groups;
}

export function updateTaskStatus(
  tasks: Task[],
  taskId: string,
  status: TaskStatus,
  updatedAt: string
): Task[] {
  let didChange = false;

  const nextTasks = tasks.map((task) => {
    if (task.id !== taskId || task.status === status) {
      return task;
    }

    didChange = true;
    return { ...task, status, updatedAt };
  });

  return didChange ? nextTasks : tasks;
}

export function updateTaskTitle(
  tasks: Task[],
  taskId: string,
  title: string,
  updatedAt: string
): Task[] {
  const normalizedTitle = title.trim();

  if (!normalizedTitle) {
    return tasks;
  }

  let didChange = false;

  const nextTasks = tasks.map((task) => {
    if (task.id !== taskId || task.title === normalizedTitle) {
      return task;
    }

    didChange = true;
    return { ...task, title: normalizedTitle, updatedAt };
  });

  return didChange ? nextTasks : tasks;
}

export function createTask(tasks: Task[], draft: TaskDraft, id: string, updatedAt: string): Task[] {
  const task = buildTask(draft, id, updatedAt);

  return task ? [task, ...tasks] : tasks;
}

export function buildTask(draft: TaskDraft, id: string, updatedAt: string): Task | null {
  const title = draft.title.trim();
  const owner = draft.owner.trim();

  if (!title || !owner) {
    return null;
  }

  return {
    ...draft,
    id,
    title,
    owner,
    tags: draft.tags.map((tag) => tag.trim()).filter(Boolean),
    updatedAt
  };
}

export function measureTaskInteraction<T>(interaction: () => T) {
  const startedAt = performance.now();
  const value = interaction();
  const durationMs = Math.round((performance.now() - startedAt) * 100) / 100;

  return { durationMs, value };
}
