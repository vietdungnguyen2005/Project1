import { beforeEach, describe, expect, it, vi } from "vitest";

const { createCloudTask } = vi.hoisted(() => ({ createCloudTask: vi.fn() }));

vi.mock("@/lib/task-api", () => ({
  ApiProblemError: class ApiProblemError extends Error {
    status = 500;
  },
  createCloudTask,
  moveCloudTask: vi.fn(),
  renameCloudTask: vi.fn()
}));

import { useTaskStore } from "@/store/task-store";

describe("task store cloud consistency", () => {
  beforeEach(() => {
    localStorage.clear();
    createCloudTask.mockReset();
    useTaskStore.setState({
      tasks: [],
      pendingTaskIds: [],
      syncState: "cloud",
      lastSyncError: null
    });
  });

  it("rolls back an optimistic create when the authoritative API rejects it", async () => {
    createCloudTask.mockRejectedValue(new Error("network unavailable"));

    useTaskStore.getState().createTask({
      title: "Never leave a ghost task",
      owner: "Demo Owner",
      status: "backlog",
      priority: "high",
      points: 3,
      tags: ["reliability"]
    });

    expect(useTaskStore.getState().tasks).toHaveLength(1);
    await vi.waitFor(() => expect(useTaskStore.getState().pendingTaskIds).toEqual([]));

    expect(useTaskStore.getState().tasks).toEqual([]);
    expect(useTaskStore.getState().lastSyncError).toContain("rolled back");
  });
});
