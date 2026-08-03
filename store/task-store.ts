"use client";

import { create } from "zustand";
import { createJSONStorage, persist } from "zustand/middleware";
import { initialTasks } from "@/lib/mock-data";
import {
  buildTask,
  createTask,
  measureTaskInteraction,
  updateTaskStatus,
  updateTaskTitle
} from "@/lib/kanban-performance";
import { createCloudTask, patchCloudTask } from "@/lib/task-api";
import type { SyncState, Task, TaskDraft, TaskStatus } from "@/lib/types";

type TaskState = {
  tasks: Task[];
  lastInteractionMs: number;
  syncState: SyncState;
  lastSyncError: string | null;
  createTask: (draft: TaskDraft) => void;
  moveTask: (taskId: string, status: TaskStatus) => void;
  updateTaskTitle: (taskId: string, title: string) => void;
  hydrateTasks: (tasks: Task[]) => void;
  setSyncState: (state: SyncState, error?: string | null) => void;
  clearEphemeralState: () => void;
};

const nowLabel = () => "just now";
const createId = () => `vc-${Math.random().toString(36).slice(2, 7)}`;

export const useTaskStore = create<TaskState>()(
  persist(
    (set) => ({
      tasks: initialTasks,
      lastInteractionMs: 0,
      syncState: "local",
      lastSyncError: null,
      createTask: (draft) => {
        set((state) => {
          const task = buildTask(draft, createId(), nowLabel());

          if (!task) {
            return state;
          }

          const result = measureTaskInteraction(() => createTask(state.tasks, task, task.id, task.updatedAt));
          void createCloudTask(task)
            .then(() => set({ syncState: "cloud", lastSyncError: null }))
            .catch(() => set({ syncState: "offline", lastSyncError: "Create saved locally; cloud sync pending." }));

          return {
            tasks: result.value,
            lastInteractionMs: result.durationMs,
            syncState: "syncing",
            lastSyncError: null
          };
        });
      },
      moveTask: (taskId, status) => {
        set((state) => {
          const updatedAt = nowLabel();
          const result = measureTaskInteraction(() =>
            updateTaskStatus(state.tasks, taskId, status, updatedAt)
          );
          void patchCloudTask(taskId, { status, updatedAt })
            .then(() => set({ syncState: "cloud", lastSyncError: null }))
            .catch(() => set({ syncState: "offline", lastSyncError: "Move saved locally; cloud sync pending." }));

          return {
            tasks: result.value,
            lastInteractionMs: result.durationMs,
            syncState: "syncing",
            lastSyncError: null
          };
        });
      },
      updateTaskTitle: (taskId, title) => {
        set((state) => {
          const updatedAt = nowLabel();
          const result = measureTaskInteraction(() =>
            updateTaskTitle(state.tasks, taskId, title, updatedAt)
          );
          void patchCloudTask(taskId, { title, updatedAt })
            .then(() => set({ syncState: "cloud", lastSyncError: null }))
            .catch(() => set({ syncState: "offline", lastSyncError: "Edit saved locally; cloud sync pending." }));

          return {
            tasks: result.value,
            lastInteractionMs: result.durationMs,
            syncState: "syncing",
            lastSyncError: null
          };
        });
      },
      hydrateTasks: (tasks) => {
        set({ tasks, syncState: "cloud", lastSyncError: null });
      },
      setSyncState: (state, error = null) => {
        set({ syncState: state, lastSyncError: error });
      },
      clearEphemeralState: () => {
        set({ lastInteractionMs: 0 });
      }
    }),
    {
      name: "v-core-task-cache",
      storage: createJSONStorage(() => window.localStorage),
      partialize: (state) => ({ tasks: state.tasks })
    }
  )
);
