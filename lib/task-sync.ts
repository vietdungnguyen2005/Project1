import type { TaskEditResult, TaskMoveResult } from "@/lib/task-api";
import type { Task } from "@/lib/types";

export function mergeTaskMoveResult(task: Task, result: TaskMoveResult): Task {
  if (task.id !== result.id) {
    return task;
  }

  return { ...task, ...result };
}

export function mergeTaskEditResult(task: Task, result: TaskEditResult): Task {
  if (task.id !== result.id) {
    return task;
  }

  return { ...task, ...result };
}

export function replaceTask(tasks: Task[], taskId: string, replacement: Task): Task[] {
  return tasks.map((task) => (task.id === taskId ? replacement : task));
}

