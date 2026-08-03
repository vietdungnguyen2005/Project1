"use client";

import {
  memo,
  useDeferredValue,
  useMemo,
  useState,
  useTransition,
  type FormEvent,
  type ReactNode
} from "react";
import { AnimatePresence, motion } from "framer-motion";
import { AlertTriangle, CheckCircle2, Clock3, GripVertical, Loader2, Pencil, Plus, X } from "lucide-react";
import { columns } from "@/lib/mock-data";
import { filterTasks, groupTasksByStatus } from "@/lib/kanban-performance";
import type { Task, TaskDraft, TaskPriority, TaskStatus } from "@/lib/types";
import { useDebouncedCallback } from "@/hooks/use-debounced-callback";
import { useTaskCloudSync } from "@/hooks/use-task-cloud-sync";
import { useTaskStore } from "@/store/task-store";

const priorityStyles: Record<TaskPriority, string> = {
  critical: "border-red-500/40 bg-red-500/10 text-red-700 dark:text-red-200",
  high: "border-amber-500/40 bg-amber-500/10 text-amber-700 dark:text-amber-200",
  medium: "border-sky-500/40 bg-sky-500/10 text-sky-700 dark:text-sky-200",
  low: "border-emerald-500/40 bg-emerald-500/10 text-emerald-700 dark:text-emerald-200"
};

const columnIcon: Record<TaskStatus, ReactNode> = {
  backlog: <Clock3 size={16} />,
  "in-progress": <Loader2 size={16} />,
  review: <AlertTriangle size={16} />,
  done: <CheckCircle2 size={16} />
};

export function KanbanBoard() {
  const tasks = useTaskStore((state) => state.tasks);
  const moveTask = useTaskStore((state) => state.moveTask);
  const lastInteractionMs = useTaskStore((state) => state.lastInteractionMs);
  const syncState = useTaskStore((state) => state.syncState);
  const lastSyncError = useTaskStore((state) => state.lastSyncError);
  const cloudSync = useTaskCloudSync();
  const [isPending, startTransition] = useTransition();
  const [draggingId, setDraggingId] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [isCreating, setIsCreating] = useState(false);
  const deferredQuery = useDeferredValue(query);

  const filteredTasks = useMemo(() => {
    return filterTasks(tasks, deferredQuery);
  }, [deferredQuery, tasks]);

  const tasksByStatus = useMemo(
    () => groupTasksByStatus(filteredTasks, columns),
    [filteredTasks]
  );

  const handleDrop = (status: TaskStatus) => {
    if (!draggingId) {
      return;
    }

    const taskId = draggingId;
    setDraggingId(null);

    startTransition(() => {
      moveTask(taskId, status);
    });
  };

  return (
    <section className="rounded border border-line bg-panel p-3 shadow-[var(--shadow-soft)]" aria-labelledby="kanban-title">
      <div className="flex flex-col gap-3 border-b border-line px-1 pb-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h2 id="kanban-title" className="text-lg font-black">Sprint Board</h2>
          <p className="mt-1 text-sm text-ink-soft">
            Prioritized work streams with live latency feedback and sprint limits.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <label className="focus-within:ring-2 focus-within:ring-accent flex h-10 min-w-56 items-center rounded border border-line bg-panel-muted px-3">
            <span className="sr-only">Filter tasks</span>
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Filter board"
              className="w-full bg-transparent text-sm outline-none placeholder:text-ink-soft"
            />
          </label>
          <div
            className="rounded border border-line bg-panel-muted px-3 py-2 font-mono text-xs font-bold text-ink-soft"
            aria-live="polite"
          >
            INP sim {lastInteractionMs}ms {isPending ? "syncing" : "idle"}
          </div>
          <div
            className="rounded border border-line bg-panel-muted px-3 py-2 text-xs font-black text-ink-soft"
            aria-live="polite"
            title={lastSyncError ?? undefined}
          >
            {cloudSync.isSyncing || syncState === "syncing"
              ? "Cloud syncing"
              : syncState === "cloud"
                ? "D1 synced"
                : syncState === "offline"
                  ? "Local fallback"
                  : "Local cache"}
          </div>
          <button
            onClick={() => setIsCreating((current) => !current)}
            className="focus-ring grid size-10 place-items-center rounded bg-accent text-black transition hover:-translate-y-0.5"
            aria-label={isCreating ? "Close task composer" : "Create task"}
            title={isCreating ? "Close task composer" : "Create task"}
          >
            {isCreating ? <X size={18} /> : <Plus size={18} />}
          </button>
        </div>
      </div>

      <AnimatePresence initial={false}>
        {isCreating ? (
          <TaskComposer
            startTransition={startTransition}
            onDone={() => setIsCreating(false)}
          />
        ) : null}
      </AnimatePresence>

      <div className="grid gap-3 pt-3 md:grid-cols-2 2xl:grid-cols-4">
        {columns.map((column) => (
          <KanbanColumn
            key={column.id}
            status={column.id}
            title={column.title}
            limit={column.limit}
            tasks={tasksByStatus[column.id]}
            draggingId={draggingId}
            setDraggingId={setDraggingId}
            onDrop={handleDrop}
          />
        ))}
      </div>
    </section>
  );
}

type TaskComposerProps = {
  startTransition: (callback: () => void) => void;
  onDone: () => void;
};

const emptyDraft: TaskDraft = {
  title: "",
  owner: "",
  status: "backlog",
  priority: "medium",
  points: 3,
  tags: ["customer-impact"]
};

function TaskComposer({ startTransition, onDone }: TaskComposerProps) {
  const createTask = useTaskStore((state) => state.createTask);
  const [draft, setDraft] = useState<TaskDraft>(emptyDraft);
  const [tagText, setTagText] = useState(emptyDraft.tags.join(", "));

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();

    startTransition(() => {
      createTask({
        ...draft,
        tags: tagText.split(",").map((tag) => tag.trim()).filter(Boolean)
      });
      setDraft(emptyDraft);
      setTagText(emptyDraft.tags.join(", "));
      onDone();
    });
  };

  return (
    <motion.form
      initial={{ opacity: 0, height: 0 }}
      animate={{ opacity: 1, height: "auto" }}
      exit={{ opacity: 0, height: 0 }}
      transition={{ duration: 0.18 }}
      onSubmit={handleSubmit}
      className="overflow-hidden border-b border-line py-3"
      aria-label="Task composer"
    >
      <div className="grid grid-cols-2 gap-3 px-1 lg:grid-cols-[1.5fr_1fr_0.8fr_0.8fr_0.6fr_auto]">
        <label className="col-span-2 block lg:col-span-1">
          <span className="mb-1 block text-xs font-black uppercase text-ink-soft">Title</span>
          <input
            required
            value={draft.title}
            onChange={(event) => setDraft((current) => ({ ...current, title: event.target.value }))}
            className="focus-ring h-10 w-full rounded border border-line bg-panel-muted px-3 text-sm font-semibold"
            placeholder="Resolve customer escalation path"
          />
        </label>
        <label className="col-span-2 block sm:col-span-1">
          <span className="mb-1 block text-xs font-black uppercase text-ink-soft">Owner</span>
          <input
            required
            value={draft.owner}
            onChange={(event) => setDraft((current) => ({ ...current, owner: event.target.value }))}
            className="focus-ring h-10 w-full rounded border border-line bg-panel-muted px-3 text-sm font-semibold"
            placeholder="Team member"
          />
        </label>
        <label className="block">
          <span className="mb-1 block text-xs font-black uppercase text-ink-soft">Status</span>
          <select
            value={draft.status}
            onChange={(event) => setDraft((current) => ({ ...current, status: event.target.value as TaskStatus }))}
            className="focus-ring h-10 w-full rounded border border-line bg-panel-muted px-3 text-sm font-semibold"
          >
            {columns.map((column) => (
              <option key={column.id} value={column.id}>
                {column.title}
              </option>
            ))}
          </select>
        </label>
        <label className="block">
          <span className="mb-1 block text-xs font-black uppercase text-ink-soft">Priority</span>
          <select
            value={draft.priority}
            onChange={(event) => setDraft((current) => ({ ...current, priority: event.target.value as TaskPriority }))}
            className="focus-ring h-10 w-full rounded border border-line bg-panel-muted px-3 text-sm font-semibold"
          >
            {(["critical", "high", "medium", "low"] satisfies TaskPriority[]).map((priority) => (
              <option key={priority} value={priority}>
                {priority}
              </option>
            ))}
          </select>
        </label>
        <label className="block">
          <span className="mb-1 block text-xs font-black uppercase text-ink-soft">Points</span>
          <input
            min={1}
            max={13}
            type="number"
            value={draft.points}
            onChange={(event) => setDraft((current) => ({ ...current, points: Number(event.target.value) }))}
            className="focus-ring h-10 w-full rounded border border-line bg-panel-muted px-3 text-sm font-semibold"
          />
        </label>
        <button className="focus-ring mt-5 h-10 rounded bg-foreground px-4 text-sm font-black text-background lg:mt-5">
          Add
        </button>
      </div>
      <label className="mt-3 block px-1">
        <span className="mb-1 block text-xs font-black uppercase text-ink-soft">Tags</span>
        <input
          value={tagText}
          onChange={(event) => setTagText(event.target.value)}
          className="focus-ring h-10 w-full rounded border border-line bg-panel-muted px-3 text-sm font-semibold"
          placeholder="performance, customer-impact"
        />
      </label>
    </motion.form>
  );
}

type KanbanColumnProps = {
  status: TaskStatus;
  title: string;
  limit: number;
  tasks: Task[];
  draggingId: string | null;
  setDraggingId: (id: string | null) => void;
  onDrop: (status: TaskStatus) => void;
};

const KanbanColumn = memo(function KanbanColumn({
  status,
  title,
  limit,
  tasks,
  draggingId,
  setDraggingId,
  onDrop
}: KanbanColumnProps) {
  return (
    <article
      className="min-h-[30rem] rounded border border-line bg-panel-muted p-3"
      onDragOver={(event) => event.preventDefault()}
      onDrop={() => onDrop(status)}
      aria-label={`${title} column`}
    >
      <header className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="grid size-8 place-items-center rounded bg-panel text-accent-strong">
            {columnIcon[status]}
          </span>
          <div>
            <h3 className="text-sm font-black">{title}</h3>
            <p className="text-xs font-semibold text-ink-soft">
              {tasks.length}/{limit} tasks
            </p>
          </div>
        </div>
      </header>

      <div className="mt-3 space-y-3">
        <AnimatePresence initial={false}>
          {tasks.map((task) => (
            <TaskCard
              key={task.id}
              task={task}
              isDragging={draggingId === task.id}
              setDraggingId={setDraggingId}
            />
          ))}
        </AnimatePresence>
      </div>
    </article>
  );
});

type TaskCardProps = {
  task: Task;
  isDragging: boolean;
  setDraggingId: (id: string | null) => void;
};

const TaskCard = memo(function TaskCard({ task, isDragging, setDraggingId }: TaskCardProps) {
  const updateTaskTitle = useTaskStore((state) => state.updateTaskTitle);
  const moveTask = useTaskStore((state) => state.moveTask);
  const [isMovePending, startMoveTransition] = useTransition();
  const [title, setTitle] = useState(task.title);
  const [isEditing, setIsEditing] = useState(false);
  const debouncedSave = useDebouncedCallback((nextTitle: string) => {
    updateTaskTitle(task.id, nextTitle);
  }, 180);

  return (
    <motion.article
      layout
      initial={false}
      animate={{ opacity: isDragging ? 0.55 : 1, y: 0 }}
      exit={{ opacity: 0, scale: 0.97 }}
      transition={{ duration: 0.18 }}
      draggable
      onDragStartCapture={(event) => {
        event.dataTransfer.effectAllowed = "move";
        setDraggingId(task.id);
      }}
      onDragEnd={() => setDraggingId(null)}
      className="rounded border border-line bg-panel p-3 shadow-sm transition hover:-translate-y-0.5 hover:shadow-md"
    >
      <div className="flex items-start gap-2">
        <GripVertical className="mt-1 shrink-0 text-ink-soft" size={16} aria-hidden="true" />
        <div className="min-w-0 flex-1">
          <div className="flex items-center justify-between gap-2">
            <span className="font-mono text-xs font-black text-foreground">{task.id}</span>
            <span className={`rounded border px-2 py-1 text-[0.68rem] font-black uppercase ${priorityStyles[task.priority]}`}>
              {task.priority}
            </span>
          </div>

          {isEditing ? (
            <label className="mt-3 block">
              <span className="sr-only">Edit task title</span>
              <textarea
                value={title}
                rows={2}
                autoFocus
                onChange={(event) => {
                  const nextTitle = event.target.value;
                  setTitle(nextTitle);
                  debouncedSave(nextTitle);
                }}
                onBlur={() => setIsEditing(false)}
                className="focus-ring w-full resize-none rounded border border-line bg-panel-muted p-2 text-sm font-bold leading-5"
              />
            </label>
          ) : (
            <button
              onClick={() => setIsEditing(true)}
              className="focus-ring mt-3 flex w-full items-start justify-between gap-3 text-left text-sm font-bold leading-5"
            >
              <span>{title}</span>
              <Pencil className="mt-0.5 shrink-0 text-ink-soft" size={14} />
            </button>
          )}

          <div className="mt-3 flex flex-wrap gap-1.5">
            {task.tags.map((tag) => (
              <span key={tag} className="rounded bg-panel-muted px-2 py-1 text-xs font-semibold text-ink-soft">
                {tag}
              </span>
            ))}
          </div>

          <label className="mt-3 flex items-center justify-between gap-2 rounded border border-line bg-panel-muted px-2 py-2">
            <span className="text-xs font-black uppercase text-ink-soft">Move</span>
            <span className="sr-only">Move task {title}</span>
            <select
              value={task.status}
              aria-label={`Move task ${title}`}
              disabled={isMovePending}
              onChange={(event) => {
                const nextStatus = event.target.value as TaskStatus;
                startMoveTransition(() => moveTask(task.id, nextStatus));
              }}
              className="focus-ring min-w-32 rounded border border-line bg-panel px-2 py-1 text-xs font-black"
            >
              {columns.map((column) => (
                <option key={column.id} value={column.id}>
                  {column.title}
                </option>
              ))}
            </select>
          </label>

          <footer className="mt-4 flex items-center justify-between text-xs font-semibold text-ink-soft">
            <span>{task.owner}</span>
            <span>{task.points} pts / {task.updatedAt}</span>
          </footer>
        </div>
      </div>
    </motion.article>
  );
});
