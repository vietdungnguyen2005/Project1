"use client";

import { create } from "zustand";
import { createJSONStorage, persist } from "zustand/middleware";
import { initialTasks } from "@/lib/mock-data";
import {
  buildTask,
  createTask as addTask,
  measureTaskInteraction,
  updateTaskStatus,
  updateTaskTitle
} from "@/lib/kanban-performance";
import {
  ApiProblemError,
  createCloudTask,
  moveCloudTask,
  renameCloudTask
} from "@/lib/task-api";
import { mergeTaskEditResult, mergeTaskMoveResult, replaceTask } from "@/lib/task-sync";
import type { SyncState, Task, TaskDraft, TaskStatus } from "@/lib/types";

type TaskState = {
  tasks: Task[];
  query: string;
  pendingTaskIds: string[];
  lastInteractionMs: number;
  syncState: SyncState;
  lastSyncError: string | null;
  setQuery: (query: string) => void;
  createTask: (draft: TaskDraft) => void;
  moveTask: (taskId: string, status: TaskStatus) => void;
  updateTaskTitle: (taskId: string, title: string) => void;
  hydrateTasks: (tasks: Task[]) => void;
  setSyncState: (state: SyncState, error?: string | null) => void;
  clearEphemeralState: () => void;
};

const nowIso = () => new Date().toISOString();
const createLocalId = () => `local-${crypto.randomUUID()}`;
const createIdempotencyKey = (operation: string) => `${operation}-${crypto.randomUUID()}`;

const syncErrorMessage = (error: unknown, fallback: string) =>
  error instanceof ApiProblemError && error.status === 409
    ? `Conflict: ${error.message} Your optimistic change was rolled back.`
    : fallback;

export const useTaskStore = create<TaskState>()(
  persist(
    (set, get) => ({
      tasks: initialTasks,
      query: "",
      pendingTaskIds: [],
      lastInteractionMs: 0,
      syncState: "local",
      lastSyncError: null,
      setQuery: (query) => set({ query }),
      createTask: (draft) => {
        const localId = createLocalId();
        const optimisticTask = buildTask(draft, localId, nowIso());
        if (!optimisticTask) {
          return;
        }

        set((state) => {
          const result = measureTaskInteraction(() =>
            addTask(state.tasks, draft, localId, optimisticTask.updatedAt)
          );
          return {
            tasks: result.value,
            pendingTaskIds: [...state.pendingTaskIds, localId],
            lastInteractionMs: result.durationMs,
            syncState: "syncing",
            lastSyncError: null
          };
        });

        void createCloudTask(draft, createIdempotencyKey("create"))
          .then((serverTask) => {
            set((state) => ({
              tasks: replaceTask(state.tasks, localId, serverTask),
              pendingTaskIds: state.pendingTaskIds.filter((id) => id !== localId),
              syncState: "cloud",
              lastSyncError: null
            }));
          })
          .catch((error: unknown) => {
            set((state) => ({
              tasks: state.tasks.filter((task) => task.id !== localId),
              pendingTaskIds: state.pendingTaskIds.filter((id) => id !== localId),
              syncState: "offline",
              lastSyncError: syncErrorMessage(error, "Create was rolled back because cloud sync failed.")
            }));
          });
      },
      moveTask: (taskId, status) => {
        const state = get();
        if (state.pendingTaskIds.includes(taskId)) {
          return;
        }
        const original = state.tasks.find((task) => task.id === taskId);
        if (!original || original.status === status) {
          return;
        }

        const updatedAt = nowIso();
        const result = measureTaskInteraction(() =>
          updateTaskStatus(state.tasks, taskId, status, updatedAt)
        );
        set({
          tasks: result.value,
          pendingTaskIds: [...state.pendingTaskIds, taskId],
          lastInteractionMs: result.durationMs,
          syncState: "syncing",
          lastSyncError: null
        });

        void moveCloudTask(original, status, createIdempotencyKey("move"))
          .then((serverResult) => {
            set((current) => ({
              tasks: current.tasks.map((task) => mergeTaskMoveResult(task, serverResult)),
              pendingTaskIds: current.pendingTaskIds.filter((id) => id !== taskId),
              syncState: "cloud",
              lastSyncError: null
            }));
          })
          .catch((error: unknown) => {
            set((current) => ({
              tasks: current.tasks.map((task) =>
                task.id === taskId
                  ? { ...task, status: original.status, columnId: original.columnId, position: original.position }
                  : task
              ),
              pendingTaskIds: current.pendingTaskIds.filter((id) => id !== taskId),
              syncState: "offline",
              lastSyncError: syncErrorMessage(error, "Move was rolled back because cloud sync failed.")
            }));
          });
      },
      updateTaskTitle: (taskId, title) => {
        const state = get();
        if (state.pendingTaskIds.includes(taskId)) {
          return;
        }
        const original = state.tasks.find((task) => task.id === taskId);
        const normalizedTitle = title.trim();
        if (!original || !normalizedTitle || original.title === normalizedTitle) {
          return;
        }

        const updatedAt = nowIso();
        const result = measureTaskInteraction(() =>
          updateTaskTitle(state.tasks, taskId, normalizedTitle, updatedAt)
        );
        set({
          tasks: result.value,
          pendingTaskIds: [...state.pendingTaskIds, taskId],
          lastInteractionMs: result.durationMs,
          syncState: "syncing",
          lastSyncError: null
        });

        void renameCloudTask(original, normalizedTitle, createIdempotencyKey("edit"))
          .then((serverResult) => {
            set((current) => ({
              tasks: current.tasks.map((task) => mergeTaskEditResult(task, serverResult)),
              pendingTaskIds: current.pendingTaskIds.filter((id) => id !== taskId),
              syncState: "cloud",
              lastSyncError: null
            }));
          })
          .catch((error: unknown) => {
            set((current) => ({
              tasks: current.tasks.map((task) =>
                task.id === taskId ? { ...task, title: original.title } : task
              ),
              pendingTaskIds: current.pendingTaskIds.filter((id) => id !== taskId),
              syncState: "offline",
              lastSyncError: syncErrorMessage(error, "Edit was rolled back because cloud sync failed.")
            }));
          });
      },
      hydrateTasks: (tasks) => {
        set({ tasks, syncState: "cloud", lastSyncError: null });
      },
      setSyncState: (state, error = null) => {
        set({ syncState: state, lastSyncError: error });
      },
      clearEphemeralState: () => {
        set({ lastInteractionMs: 0, pendingTaskIds: [] });
      }
    }),
    {
      name: "v-core-task-cache-v2",
      storage: createJSONStorage(() => window.localStorage),
      partialize: (state) => ({ tasks: state.tasks })
    }
  )
);
