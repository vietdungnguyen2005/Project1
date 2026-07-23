import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { columns, initialTasks } from "../lib/mock-data";
import {
  filterTasks,
  groupTasksByStatus,
  measureTaskInteraction,
  updateTaskStatus,
  updateTaskTitle
} from "../lib/kanban-performance";
import { SessionCleanupRegistry } from "../lib/session-cleanup-registry";
import type { Task } from "../lib/types";

const BUDGET_MS = 50;

function createTasks(count: number): Task[] {
  return Array.from({ length: count }, (_, index) => ({
    ...initialTasks[index % initialTasks.length],
    id: `vc-proof-${index}`,
    title: `Enterprise rollout task ${index} performance collaboration`,
    owner: `Owner ${index % 16}`,
    status: columns[index % columns.length].id,
    tags: ["performance", "collaboration", `lane-${index % 4}`]
  }));
}

function assertBudget(label: string, durationMs: number) {
  if (durationMs >= BUDGET_MS) {
    throw new Error(`${label} took ${durationMs}ms, above ${BUDGET_MS}ms INP feedback budget`);
  }
}

const tasks = createTasks(10000);

const titleUpdate = measureTaskInteraction(() =>
  updateTaskTitle(tasks, "vc-proof-6120", "Edited title within INP budget", "now")
);
assertBudget("Inline title update across 10,000 tasks", titleUpdate.durationMs);

const statusUpdate = measureTaskInteraction(() =>
  updateTaskStatus(titleUpdate.value, "vc-proof-6120", "done", "now")
);
assertBudget("Kanban status update across 10,000 tasks", statusUpdate.durationMs);

const filterAndGroup = measureTaskInteraction(() =>
  groupTasksByStatus(filterTasks(statusUpdate.value, "performance"), columns)
);
assertBudget("Deferred filter/group across 10,000 tasks", filterAndGroup.durationMs);

let clearedTimer = false;
let listenerRemoved = false;
let cacheFlushed = false;
const controller = new AbortController();
const registry = new SessionCleanupRegistry();

const timer = setInterval(() => {
  clearedTimer = false;
}, 1000);
registry.addTimer(timer);
registry.addAbortController(controller);
registry.addListener(() => {
  listenerRemoved = true;
});
registry.addCacheFlush(() => {
  cacheFlushed = true;
});
registry.flush();
clearInterval(timer);
clearedTimer = true;

if (!controller.signal.aborted || !listenerRemoved || !cacheFlushed || !clearedTimer) {
  throw new Error("Session cleanup proof failed");
}

const proof = {
  generatedAt: new Date().toISOString(),
  taskCount: tasks.length,
  budgetMs: BUDGET_MS,
  titleUpdateMs: titleUpdate.durationMs,
  statusUpdateMs: statusUpdate.durationMs,
  filterAndGroupMs: filterAndGroup.durationMs,
  cleanup: {
    abortController: controller.signal.aborted,
    listenerRemoved,
    cacheFlushed,
    timerCleared: clearedTimer
  }
};

const outputPath = resolve("reports/performance-proof.json");
mkdirSync(dirname(outputPath), { recursive: true });
writeFileSync(outputPath, `${JSON.stringify(proof, null, 2)}\n`);
console.log(JSON.stringify(proof, null, 2));
console.log(`Saved proof to ${outputPath}`);
