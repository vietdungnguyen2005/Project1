"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { useOperatorAccess } from "@/components/operator-access";
import { ApiProblemError, createProjectTask, fetchCloudActivities, fetchProjectTasks, moveProjectTask } from "@/lib/task-api";
import { assignTaskMember } from "@/lib/task-collaboration-api";
import type { Task } from "@/lib/types";
import type { WorkspaceMember, WorkspaceProject } from "@/lib/workspace-api";

export function ProjectWorkflowBoard({ project, members }: { project: WorkspaceProject; members: WorkspaceMember[] }) {
  const { canWrite } = useOperatorAccess();
  const client = useQueryClient();
  const tasks = useQuery({ queryKey: ["customer-tasks", project.id], queryFn: ({ signal }) => fetchProjectTasks(project.id, signal), retry: 1 });
  const activities = useQuery({ queryKey: ["customer-activities"], queryFn: ({ signal }) => fetchCloudActivities(signal), retry: 1 });
  const [title, setTitle] = useState("");
  const [columnId, setColumnId] = useState(project.columns[0]?.id ?? "");
  const pendingCreate = useRef<{ title: string; columnId: string; key: string } | null>(null);
  const keys = useRef(new Map<string, string>());
  const [notice, setNotice] = useState("");
  const [unconfirmed, setUnconfirmed] = useState(false);
  const refresh = () => {
    void client.invalidateQueries({ queryKey: ["customer-tasks", project.id] });
    void client.invalidateQueries({ queryKey: ["customer-activities"] });
    void client.invalidateQueries({ queryKey: ["activities"] });
  };
  const fail = (error: Error) => {
    setNotice(error instanceof ApiProblemError && error.status === 409 ? `Conflict: ${error.message} Current tasks are being refreshed; review before retrying.` : `${error.message} Keep the same operation when retrying an unconfirmed request.`);
    refresh();
  };
  const create = useMutation({ mutationFn: () => {
    pendingCreate.current ??= { title, columnId, key: `customer-task-${crypto.randomUUID()}` };
    setUnconfirmed(true);
    const saved = pendingCreate.current;
    return createProjectTask(project.id, saved.columnId, saved.title, saved.key);
  }, onSuccess: () => { pendingCreate.current = null; setUnconfirmed(false); setTitle(""); setNotice("Task created in the selected customer workflow."); refresh(); },
    onError: (error: Error) => { if (error instanceof ApiProblemError && [400, 401, 403, 409].includes(error.status)) { pendingCreate.current = null; setUnconfirmed(false); } fail(error); } });
  const operate = useMutation({ mutationFn: async (command: { task: Task; target: string; action: "move" | "assign" }): Promise<void> => {
    const identity = JSON.stringify([command.action, command.task.id, command.task.version, command.target]);
    let key = keys.current.get(identity);
    if (!key) { key = `customer-${command.action}-${crypto.randomUUID()}`; keys.current.set(identity, key); }
    if (command.action === "move") await moveProjectTask(project.id, command.task, command.target, key);
    else await assignTaskMember(command.task.id, command.target, command.task.version, key, project.id);
  }, onSuccess: () => { setNotice("Change committed. Audit history refreshed."); refresh(); }, onError: fail });
  const busy = create.isPending || operate.isPending;

  return <section className="mt-4 min-w-0 rounded border border-accent/50 bg-panel-muted p-3" aria-label={`Workflow workbench ${project.key}`}>
    <div className="flex flex-wrap items-center justify-between gap-2">
      <div><p className="font-mono text-xs font-bold text-accent-strong">Synthetic delivery workbench</p><h4 className="mt-1 font-black">{project.name}</h4></div>
      <button type="button" onClick={refresh} className="focus-ring min-h-10 rounded border border-line px-3 text-xs font-bold">Refresh workflow</button>
    </div>
    <p className="mt-2 text-xs text-ink-soft">Actual column IDs from this project. Refresh to load changes made by other operators; the original seeded Sprint Board is unchanged.</p>
    {tasks.error && <p role="alert" className="mt-2 text-sm text-red-700 dark:text-red-200">{tasks.error.message}</p>}
    {tasks.isPending && <p className="mt-2 text-sm text-ink-soft">Loading customer tasks…</p>}
    <form aria-label="Create customer task" className="mt-3 grid gap-2 sm:grid-cols-[minmax(0,1fr)_auto_auto] sm:items-end" onSubmit={(event) => { event.preventDefault(); if (canWrite && !busy) create.mutate(); }}>
      <label className="min-w-0 flex-1 text-xs font-bold">Customer task title<input required maxLength={240} value={title} disabled={busy || unconfirmed} onChange={(event) => setTitle(event.target.value)} className="focus-ring mt-1 block h-10 w-full rounded border border-line bg-panel px-2" /></label>
      <label className="text-xs font-bold">Initial stage<select aria-label="Initial stage" disabled={busy || unconfirmed} value={columnId} onChange={(event) => setColumnId(event.target.value)} className="focus-ring mt-1 block h-10 rounded border border-line bg-panel px-2">{project.columns.map((column) => <option key={column.id} value={column.id}>{column.name}</option>)}</select></label>
      <button disabled={!canWrite || busy || !columnId} className="focus-ring min-h-10 rounded bg-foreground px-3 text-sm font-bold text-background disabled:opacity-50">Add customer task</button>
    </form>
    <p role="status" className="mt-3 text-sm text-accent-strong">{notice}</p>
    {tasks.data?.truncated && <p className="mt-2 text-xs text-ink-soft">Only the first 200 tasks are shown. This is not a full export.</p>}
    <div className="mt-3 grid min-w-0 gap-3 sm:grid-cols-2 xl:grid-cols-4">
      {project.columns.map((column) => {
        const items = tasks.data?.items.filter((task) => task.columnId === column.id) ?? [];
        return <section aria-label={`Stage ${column.name}`} key={column.id} className="min-w-0 rounded border border-line bg-panel p-2">
          <h5 className="break-words text-xs font-black">{column.name}</h5><p className="mt-1 font-mono text-xs text-ink-soft">Shown {items.length} · WIP {column.wipLimit ?? "unlimited"}</p>
          {items.length === 0 && <p className="mt-3 text-xs text-ink-soft">No tasks shown.</p>}
          {items.map((task) => <article key={`${task.id}-${task.version}`} className="mt-3 min-w-0 rounded border border-line bg-panel-muted p-2" aria-label={task.key}>
            <p className="font-mono text-[0.65rem] text-ink-soft">{task.key} · v{task.version}</p><p className="mt-1 break-words text-sm font-bold">{task.title}</p><p className="mt-1 break-words text-xs text-ink-soft">Owner: {task.owner}</p>
            <form className="mt-2" onSubmit={(event) => { event.preventDefault(); if (canWrite && !busy) operate.mutate({ task, target: String(new FormData(event.currentTarget).get("target")), action: "move" }); }}>
              <label className="text-xs">Move to<select name="target" aria-label={`Move ${task.key} to`} defaultValue={task.columnId} disabled={!canWrite || busy} className="focus-ring mt-1 block h-10 w-full rounded border border-line bg-panel px-1">{project.columns.map((target) => <option key={target.id} value={target.id}>{target.name}</option>)}</select></label>
              <button disabled={!canWrite || busy} className="focus-ring mt-1 min-h-10 w-full rounded border border-line text-xs font-bold">Move task</button>
            </form>
            <form className="mt-2" onSubmit={(event) => { event.preventDefault(); if (canWrite && !busy) operate.mutate({ task, target: String(new FormData(event.currentTarget).get("assignee")), action: "assign" }); }}>
              <label className="text-xs">Assign member<select name="assignee" aria-label={`Assign ${task.key}`} required defaultValue="" disabled={!canWrite || busy} className="focus-ring mt-1 block h-10 w-full rounded border border-line bg-panel px-1"><option value="" disabled>Choose workspace member</option>{members.map((member) => <option key={member.id} value={member.id}>{member.name}</option>)}</select></label>
              <button disabled={!canWrite || busy} className="focus-ring mt-1 min-h-10 w-full rounded border border-line text-xs font-bold">Assign task</button>
            </form>
          </article>)}
        </section>;
      })}
    </div>
    <details className="mt-4 rounded border border-line bg-panel p-3" open><summary className="focus-ring cursor-pointer text-sm font-black">Recent workspace audit</summary>
      {activities.error && <p className="mt-2 text-xs">Audit unavailable: {activities.error.message}</p>}
      {activities.isPending && <p className="mt-2 text-xs text-ink-soft">Loading workspace audit…</p>}
      <ol className="mt-2 space-y-2">{activities.data?.map((item) => <li key={item.id} className="break-words border-t border-line pt-2 text-xs">{item.actor} {item.action} {item.target} · {item.time}</li>)}</ol>
    </details>
  </section>;
}
