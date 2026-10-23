"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { useOperatorAccess } from "@/components/operator-access";
import { ApiProblemError } from "@/lib/task-api";
import { fetchWorkflowPresets, fetchWorkflowTemplates, publishWorkflowTemplate, type WorkflowDefinition } from "@/lib/workspace-api";

export function WorkflowTemplatePicker({ value, onChange, disabled }: { value?: string; onChange: (id?: string) => void; disabled: boolean }) {
  const { canAdmin } = useOperatorAccess();
  const client = useQueryClient();
  const presets = useQuery({ queryKey: ["workflow-presets"], queryFn: ({ signal }) => fetchWorkflowPresets(signal), retry: 1 });
  const templates = useQuery({ queryKey: ["workflow-templates"], queryFn: ({ signal }) => fetchWorkflowTemplates(signal), retry: 1 });
  const [draft, setDraft] = useState<WorkflowDefinition | null>(null);
  const [scenario, setScenario] = useState("default");
  const [unconfirmed, setUnconfirmed] = useState(false);
  const attempt = useRef<{ definition: WorkflowDefinition; key: string } | null>(null);
  const publish = useMutation({
    mutationFn: () => {
      if (!draft) throw new Error("Select a synthetic scenario first.");
      attempt.current ??= { definition: structuredClone(draft), key: `template-${crypto.randomUUID()}` };
      setUnconfirmed(true);
      return publishWorkflowTemplate(attempt.current.definition, attempt.current.key);
    },
    onSuccess: (template) => { attempt.current = null; setUnconfirmed(false); onChange(template.id); void client.invalidateQueries({ queryKey: ["workflow-templates"] }); },
    onError: (error) => { if (error instanceof ApiProblemError && [400, 401, 403, 409].includes(error.status)) { attempt.current = null; setUnconfirmed(false); } }
  });
  const locked = disabled || publish.isPending || unconfirmed;
  const selected = templates.data?.find((template) => template.id === value);
  const existing = draft && templates.data?.find((template) => template.key === draft.key && template.version === draft.version);
  const equivalent = existing && draft && JSON.stringify(existing.columns) === JSON.stringify(draft.columns) && existing.name === draft.name && existing.scenario === draft.scenario;
  const preview = selected ?? draft;

  return <fieldset className="mt-4 min-w-0 rounded border border-line bg-panel-muted p-3" aria-label="Customer workflow configuration">
    <legend className="px-1 font-mono text-xs font-black uppercase text-accent-strong">Synthetic customer delivery</legend>
    <p className="text-xs leading-5 text-ink-soft">Demonstration scenarios, not real customer engagements. Templates are workspace-scoped; selecting a scenario does not switch tenant.</p>
    <label className="mt-3 block text-xs font-bold">Customer scenario
      <select aria-label="Customer scenario" disabled={locked} value={scenario} className="focus-ring mt-1 h-10 w-full rounded border border-line bg-panel px-2" onChange={(event) => {
        setScenario(event.target.value); onChange(event.target.value === "default" ? undefined : ""); publish.reset();
        setDraft(structuredClone(presets.data?.find((preset) => preset.scenario === event.target.value) ?? null));
      }}>
        <option value="default">Existing default workflow</option>
        {(presets.data ?? []).map((preset) => <option key={preset.key} value={preset.scenario}>{preset.name}</option>)}
      </select>
    </label>
    {(presets.isError || templates.isError) && <p className="mt-2 text-xs text-ink-soft">Template service unavailable. Default project creation remains available.</p>}
    {draft && <div className="mt-3 grid gap-3 sm:grid-cols-2">
      <label className="text-xs font-bold">Template revision<input aria-label="Template revision" type="number" min={1} max={100000} disabled={locked} value={draft.version} onChange={(event) => { onChange(""); setDraft({ ...draft, version: Number(event.target.value) }); }} className="focus-ring mt-1 h-9 w-full rounded border border-line bg-panel px-2" /></label>
      <div className="flex items-end"><button type="button" disabled={!canAdmin || disabled || publish.isPending || Boolean(value) || Boolean(existing && !equivalent)} onClick={() => existing ? onChange(existing.id) : publish.mutate()} className="focus-ring min-h-10 w-full rounded border border-line bg-panel px-3 text-xs font-black disabled:opacity-50">{publish.isPending ? "Publishing…" : existing ? `Use published version ${draft.version}` : "Validate and publish version"}</button></div>
    </div>}
    {(templates.data?.length ?? 0) > 0 && <label className="mt-3 block text-xs font-bold">Published workflow version
      <select aria-label="Published workflow version" disabled={locked} value={value ?? ""} onChange={(event) => onChange(event.target.value || (draft ? "" : undefined))} className="focus-ring mt-1 h-10 w-full rounded border border-line bg-panel px-2">
        <option value="">No version selected</option>
        {templates.data?.map((template) => <option value={template.id} key={template.id}>{template.key} / v{template.version} / synthetic</option>)}
      </select>
    </label>}
    {preview && <ol className="mt-3 grid gap-2 sm:grid-cols-2 xl:grid-cols-4" aria-label="Workflow preview">
      {preview.columns.map((column, index) => <li key={column.position} className="min-w-0 rounded border border-line bg-panel p-3">
        <p className="break-words text-xs font-black">{index + 1}. {column.name}</p>
        <p className="mt-1 font-mono text-[0.65rem] text-ink-soft">{column.category}</p>
        {draft && !selected ? <label className="mt-2 block text-xs">WIP (blank = unlimited)<input aria-label={`${column.name} preview WIP`} type="number" min={1} max={100} disabled={locked} value={column.wipLimit ?? ""} onChange={(event) => setDraft({ ...draft, columns: draft.columns.map((item, i) => i === index ? { ...item, wipLimit: event.target.value === "" ? null : Number(event.target.value) } : item) })} className="focus-ring mt-1 h-8 w-full rounded border border-line bg-panel-muted px-2" /></label>
          : <p className="mt-2 text-xs">WIP {column.wipLimit ?? "unlimited"}</p>}
      </li>)}
    </ol>}
    {draft && !value && <p className="mt-2 text-xs text-ink-soft">Choose a published version before creating this scenario. Publishing a new version never remaps existing projects.</p>}
    {existing && !equivalent && !selected && <p role="status" className="mt-2 text-xs text-ink-soft">This immutable revision already has different settings. Select the stored revision below or publish a new revision number.</p>}
    {selected && <p role="status" className="mt-2 text-xs font-bold text-accent-strong">Ready: {selected.key} / v{selected.version} · synthetic demonstration</p>}
    {publish.error && <p role="alert" className="mt-2 text-sm text-red-700 dark:text-red-200">{publish.error.message}{unconfirmed ? " Outcome unconfirmed: retry uses the same saved definition/key; do not reload or start another operation." : " Refresh published versions if this revision already exists."}</p>}
  </fieldset>;
}
