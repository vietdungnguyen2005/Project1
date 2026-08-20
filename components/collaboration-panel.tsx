"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { MessageSquareText, Send, UserRoundCheck } from "lucide-react";
import { useState, type FormEvent } from "react";
import { addTaskComment, assignTaskMember, fetchTaskComments } from "@/lib/task-collaboration-api";
import { fetchWorkspaceOverview } from "@/lib/workspace-api";
import { useTaskStore } from "@/store/task-store";

const commandKey = (operation: string) => `${operation}-${crypto.randomUUID()}`;

export function CollaborationPanel() {
  const tasks = useTaskStore((state) => state.tasks);
  const [taskId, setTaskId] = useState("");
  const [assigneeId, setAssigneeId] = useState("");
  const [comment, setComment] = useState("");
  const queryClient = useQueryClient();

  const effectiveTaskId = taskId || tasks[0]?.id || "";
  const selectedTask = tasks.find((task) => task.id === effectiveTaskId);
  const overview = useQuery({
    queryKey: ["workspace-overview"],
    queryFn: ({ signal }) => fetchWorkspaceOverview(signal),
    retry: 1,
    staleTime: 20_000
  });
  const comments = useQuery({
    queryKey: ["task-comments", effectiveTaskId],
    queryFn: ({ signal }) => fetchTaskComments(effectiveTaskId, signal),
    enabled: Boolean(effectiveTaskId),
    retry: 1
  });

  const assignment = useMutation({
    mutationFn: () => {
      if (!selectedTask) throw new Error("Select a task first.");
      return assignTaskMember(selectedTask.id, assigneeId, selectedTask.version, commandKey("assign"));
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["tasks"] });
      void queryClient.invalidateQueries({ queryKey: ["activities"] });
    }
  });
  const addComment = useMutation({
    mutationFn: () => addTaskComment(effectiveTaskId, comment, commandKey("comment")),
    onSuccess: () => {
      setComment("");
      void queryClient.invalidateQueries({ queryKey: ["task-comments", effectiveTaskId] });
      void queryClient.invalidateQueries({ queryKey: ["activities"] });
    }
  });

  const submitComment = (event: FormEvent) => {
    event.preventDefault();
    if (comment.trim() && !addComment.isPending) addComment.mutate();
  };

  return (
    <section id="collaboration" className="rounded border border-line bg-panel p-4 shadow-[var(--shadow-soft)]" aria-labelledby="collaboration-title">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="font-mono text-xs font-black uppercase tracking-[0.18em] text-accent-strong">Collaboration / 02</p>
          <h2 id="collaboration-title" className="mt-1 text-xl font-black">Task Handoff</h2>
        </div>
        <label className="text-xs font-bold text-ink-soft">Active task
          <select value={effectiveTaskId} onChange={(event) => setTaskId(event.target.value)} className="focus-ring mt-1 block h-10 min-w-64 rounded border border-line bg-panel-muted px-3 text-sm font-bold text-foreground">
            {tasks.map((task) => <option key={task.id} value={task.id}>{task.key} — {task.title}</option>)}
          </select>
        </label>
      </div>

      <div className="mt-4 grid gap-4 lg:grid-cols-[0.7fr_1.3fr]">
        <div className="rounded border border-line bg-panel-muted p-3">
          <div className="flex items-center gap-2"><UserRoundCheck size={17} /><h3 className="font-black">Assignment gate</h3></div>
          <p className="mt-2 text-xs leading-5 text-ink-soft">Commits with expected version {selectedTask?.version ?? "—"}; stale handoffs return 409.</p>
          <select value={assigneeId} onChange={(event) => setAssigneeId(event.target.value)} className="focus-ring mt-3 h-10 w-full rounded border border-line bg-panel px-3 text-sm" aria-label="Task assignee">
            <option value="">Select workspace member</option>
            {overview.data?.members.map((member) => <option key={member.id} value={member.id}>{member.name} · {member.role}</option>)}
          </select>
          <button disabled={!assigneeId || !selectedTask || assignment.isPending} onClick={() => assignment.mutate()} className="focus-ring mt-2 h-10 w-full rounded bg-foreground text-sm font-black text-background disabled:opacity-50">Commit assignment</button>
          {assignment.isError ? <p className="mt-2 text-xs font-bold text-red-600">Assignment rejected; refresh the task version and retry.</p> : null}
        </div>

        <div className="rounded border border-line p-3">
          <div className="flex items-center gap-2"><MessageSquareText size={17} /><h3 className="font-black">Decision log</h3></div>
          <ol className="mt-3 max-h-44 space-y-2 overflow-y-auto" aria-live="polite">
            {comments.data?.map((item) => <li key={item.id} className="border-l-2 border-accent bg-panel-muted px-3 py-2"><p className="text-sm">{item.body}</p><p className="mt-1 text-xs font-bold text-ink-soft">{item.author}</p></li>)}
            {comments.isSuccess && comments.data.length === 0 ? <li className="text-sm text-ink-soft">No handoff notes yet.</li> : null}
          </ol>
          <form onSubmit={submitComment} className="mt-3 grid grid-cols-[1fr_auto] gap-2">
            <label><span className="sr-only">Add task comment</span><input required maxLength={2000} value={comment} onChange={(event) => setComment(event.target.value)} placeholder="Record acceptance criteria or handoff context" className="focus-ring h-10 w-full rounded border border-line bg-panel-muted px-3 text-sm" /></label>
            <button disabled={!effectiveTaskId || addComment.isPending} className="focus-ring flex h-10 items-center gap-2 rounded bg-accent px-4 text-sm font-black text-black"><Send size={15} /> Log</button>
          </form>
        </div>
      </div>
    </section>
  );
}
