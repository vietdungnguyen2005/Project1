"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Boxes, Loader2, Send, SlidersHorizontal, Users } from "lucide-react";
import { useState, type FormEvent } from "react";
import {
  createWorkspaceProject,
  fetchWorkspaceOverview,
  inviteWorkspaceMember,
  updateWorkflowWipLimit,
  type CreateProjectCommand,
  type WorkspaceInvitation
} from "@/lib/workspace-api";

const commandKey = (operation: string) => `${operation}-${crypto.randomUUID()}`;

export function WorkspaceOperations() {
  const queryClient = useQueryClient();
  const [inviteEmail, setInviteEmail] = useState("");
  const [inviteRole, setInviteRole] = useState<WorkspaceInvitation["role"]>("MEMBER");
  const [projectDraft, setProjectDraft] = useState<CreateProjectCommand>({
    name: "",
    key: "",
    description: "",
    sprintName: "",
    sprintGoal: ""
  });

  const overview = useQuery({
    queryKey: ["workspace-overview"],
    queryFn: ({ signal }) => fetchWorkspaceOverview(signal),
    retry: 1,
    staleTime: 20_000
  });

  const refreshOverview = () => queryClient.invalidateQueries({ queryKey: ["workspace-overview"] });
  const invite = useMutation({
    mutationFn: () => inviteWorkspaceMember(inviteEmail, inviteRole, commandKey("invite")),
    onSuccess: () => {
      setInviteEmail("");
      void refreshOverview();
    }
  });
  const createProject = useMutation({
    mutationFn: () => createWorkspaceProject(projectDraft, commandKey("project")),
    onSuccess: () => {
      setProjectDraft({ name: "", key: "", description: "", sprintName: "", sprintGoal: "" });
      void refreshOverview();
    }
  });
  const updateWip = useMutation({
    mutationFn: (input: { projectId: string; columnId: string; limit: number; version: number }) =>
      updateWorkflowWipLimit(
        input.projectId,
        input.columnId,
        input.limit,
        input.version,
        commandKey("wip")
      ),
    onSuccess: () => void refreshOverview()
  });

  const submitInvite = (event: FormEvent) => {
    event.preventDefault();
    if (!invite.isPending) invite.mutate();
  };
  const submitProject = (event: FormEvent) => {
    event.preventDefault();
    if (!createProject.isPending) createProject.mutate();
  };

  return (
    <section id="operations" className="rounded border border-line bg-panel shadow-[var(--shadow-soft)]" aria-labelledby="operations-title">
      <header className="flex flex-col gap-3 border-b border-line px-4 py-4 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="font-mono text-xs font-black uppercase tracking-[0.18em] text-accent-strong">Control plane / 01</p>
          <h2 id="operations-title" className="mt-1 text-xl font-black">Workspace Operations</h2>
          <p className="mt-1 text-sm text-ink-soft">Tenant-scoped project, sprint, workflow and access commands.</p>
        </div>
        <span className="inline-flex items-center gap-2 self-start rounded border border-line bg-panel-muted px-3 py-2 font-mono text-xs font-bold">
          <span className={`size-2 rounded-full ${overview.isSuccess ? "bg-accent" : overview.isError ? "bg-red-500" : "animate-pulse bg-amber-500"}`} />
          {overview.isSuccess ? "POSTGRES LIVE" : overview.isError ? "BACKEND OFFLINE" : "CONNECTING"}
        </span>
      </header>

      {overview.isError ? (
        <div className="m-4 border-l-4 border-amber-500 bg-panel-muted p-4 text-sm">
          Start the Spring Boot backend and Wrangler BFF to enable administrative commands. The board remains readable from its local cache.
        </div>
      ) : null}

      <div className="grid gap-px bg-line lg:grid-cols-[1.25fr_0.75fr]">
        <div className="space-y-px bg-line">
          {(overview.data?.projects ?? []).map((project) => (
            <article key={project.id} className="bg-panel p-4">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                  <div className="flex items-center gap-2">
                    <span className="rounded bg-foreground px-2 py-1 font-mono text-xs font-black text-background">{project.key}</span>
                    <h3 className="font-black">{project.name}</h3>
                  </div>
                  <p className="mt-2 max-w-2xl text-sm leading-6 text-ink-soft">{project.description}</p>
                </div>
                <div className="border-l-2 border-accent pl-3 text-right">
                  <p className="text-xs font-black uppercase text-ink-soft">Active sprint</p>
                  <p className="mt-1 font-bold">{project.activeSprint?.name ?? "Not scheduled"}</p>
                </div>
              </div>

              <div className="mt-4 grid gap-2 sm:grid-cols-2 xl:grid-cols-4">
                {project.columns.map((column) => (
                  <form
                    key={column.id}
                    className="rounded border border-line bg-panel-muted p-3"
                    onSubmit={(event) => {
                      event.preventDefault();
                      const data = new FormData(event.currentTarget);
                      const limit = Number(data.get("limit"));
                      if (Number.isInteger(limit) && limit > 0) {
                        updateWip.mutate({ projectId: project.id, columnId: column.id, limit, version: column.version });
                      }
                    }}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="text-sm font-black">{column.name}</span>
                      <span className="font-mono text-[0.65rem] text-ink-soft">v{column.version}</span>
                    </div>
                    <label className="mt-3 block text-xs font-bold text-ink-soft">
                      WIP limit
                      <input
                        name="limit"
                        type="number"
                        min={1}
                        max={100}
                        defaultValue={column.wipLimit ?? 100}
                        className="focus-ring mt-1 h-9 w-full rounded border border-line bg-panel px-2 text-sm font-bold"
                      />
                    </label>
                    <button disabled={updateWip.isPending} className="focus-ring mt-2 flex h-9 w-full items-center justify-center gap-2 rounded border border-line bg-panel text-xs font-black hover:border-accent">
                      <SlidersHorizontal size={14} /> Commit limit
                    </button>
                  </form>
                ))}
              </div>
            </article>
          ))}

          <form onSubmit={submitProject} className="bg-panel p-4" aria-label="Create project and sprint">
            <div className="flex items-center gap-2"><Boxes size={17} /><h3 className="font-black">New delivery stream</h3></div>
            <div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              <Field label="Project name" value={projectDraft.name} onChange={(name) => setProjectDraft((current) => ({ ...current, name }))} required />
              <Field label="Project key" value={projectDraft.key} onChange={(key) => setProjectDraft((current) => ({ ...current, key }))} required pattern="[A-Za-z][A-Za-z0-9]{1,11}" />
              <Field label="Sprint name" value={projectDraft.sprintName} onChange={(sprintName) => setProjectDraft((current) => ({ ...current, sprintName }))} required />
              <Field label="Description" value={projectDraft.description} onChange={(description) => setProjectDraft((current) => ({ ...current, description }))} required />
              <Field label="Sprint goal" value={projectDraft.sprintGoal} onChange={(sprintGoal) => setProjectDraft((current) => ({ ...current, sprintGoal }))} required />
              <button disabled={createProject.isPending} className="focus-ring mt-5 flex h-10 items-center justify-center gap-2 rounded bg-foreground px-4 text-sm font-black text-background">
                {createProject.isPending ? <Loader2 className="animate-spin" size={16} /> : <Boxes size={16} />} Create stream
              </button>
            </div>
          </form>
        </div>

        <div className="bg-panel p-4">
          <div className="flex items-center gap-2"><Users size={17} /><h3 className="font-black">Access roster</h3></div>
          <ul className="mt-3 divide-y divide-line">
            {(overview.data?.members ?? []).map((member) => (
              <li key={member.id} className="flex items-center justify-between gap-3 py-3">
                <div><p className="text-sm font-bold">{member.name}</p><p className="text-xs text-ink-soft">{member.email}</p></div>
                <span className="rounded border border-line px-2 py-1 font-mono text-[0.65rem] font-black">{member.role}</span>
              </li>
            ))}
          </ul>

          <form onSubmit={submitInvite} className="mt-4 rounded border border-line bg-panel-muted p-3" aria-label="Invite workspace member">
            <p className="text-sm font-black">Issue invitation</p>
            <label className="mt-3 block text-xs font-bold text-ink-soft">Email
              <input required type="email" value={inviteEmail} onChange={(event) => setInviteEmail(event.target.value)} className="focus-ring mt-1 h-10 w-full rounded border border-line bg-panel px-3 text-sm" />
            </label>
            <div className="mt-2 grid grid-cols-[1fr_auto] gap-2">
              <select value={inviteRole} onChange={(event) => setInviteRole(event.target.value as WorkspaceInvitation["role"])} className="focus-ring h-10 rounded border border-line bg-panel px-2 text-sm font-bold" aria-label="Invitation role">
                <option value="ADMIN">Admin</option><option value="MEMBER">Member</option><option value="VIEWER">Viewer</option>
              </select>
              <button disabled={invite.isPending} className="focus-ring flex h-10 items-center gap-2 rounded bg-accent px-3 text-sm font-black text-black"><Send size={15} /> Invite</button>
            </div>
          </form>

          {(overview.data?.invitations.length ?? 0) > 0 ? <p className="mt-4 text-xs font-black uppercase text-ink-soft">Pending invitations</p> : null}
          <ul className="mt-1 space-y-2">
            {overview.data?.invitations.map((invitation) => <li key={invitation.id} className="rounded border border-dashed border-line px-3 py-2 text-xs"><strong>{invitation.email}</strong><span className="ml-2 text-ink-soft">{invitation.role}</span></li>)}
          </ul>
        </div>
      </div>
    </section>
  );
}

function Field({ label, value, onChange, ...inputProps }: { label: string; value: string; onChange: (value: string) => void } & Omit<React.InputHTMLAttributes<HTMLInputElement>, "value" | "onChange">) {
  return <label className="block text-xs font-bold text-ink-soft">{label}<input {...inputProps} value={value} onChange={(event) => onChange(event.target.value)} className="focus-ring mt-1 h-10 w-full rounded border border-line bg-panel-muted px-3 text-sm text-foreground" /></label>;
}
