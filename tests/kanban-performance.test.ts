import { describe, expect, it } from "vitest";
import { columns, initialTasks } from "@/lib/mock-data";
import {
  filterTasks,
  groupTasksByStatus,
  measureTaskInteraction,
  updateTaskStatus,
  updateTaskTitle
} from "@/lib/kanban-performance";
import type { Task } from "@/lib/types";

function createTasks(count: number): Task[] {
  return Array.from({ length: count }, (_, index) => ({
    ...initialTasks[index % initialTasks.length],
    id: `vc-load-${index}`,
    title: `Task ${index} web-vitals collaboration`,
    status: columns[index % columns.length].id
  }));
}

describe("kanban performance helpers", () => {
  it("updates one task status without mutating unaffected task references", () => {
    const tasks = createTasks(100);
    const next = updateTaskStatus(tasks, "vc-load-42", "done", "now");

    expect(next).not.toBe(tasks);
    expect(next[42]).toMatchObject({ id: "vc-load-42", status: "done", updatedAt: "now" });
    expect(next[41]).toBe(tasks[41]);
  });

  it("groups and filters high-volume task sets deterministically", () => {
    const tasks = createTasks(1000);
    const filtered = filterTasks(tasks, "web-vitals");
    const grouped = groupTasksByStatus(filtered, columns);

    expect(filtered).toHaveLength(1000);
    expect(Object.values(grouped).reduce((total, group) => total + group.length, 0)).toBe(1000);
  });

  it("keeps large updates below the 50ms INP feedback budget", () => {
    const tasks = createTasks(5000);
    const result = measureTaskInteraction(() =>
      updateTaskTitle(tasks, "vc-load-2450", "Edited without blocking input", "now")
    );

    expect(result.durationMs).toBeLessThan(50);
    expect(result.value[2450].title).toBe("Edited without blocking input");
  });
});
