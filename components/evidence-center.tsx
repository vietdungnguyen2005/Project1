import { ArrowUpRight, CheckCircle2, FlaskConical, GitBranch, TerminalSquare } from "lucide-react";
import performanceProof from "@/reports/performance-proof.json";
import { evidenceItems, evidenceLinks } from "@/lib/evidence-data";

export function EvidenceCenter() {
  const measurements = [
    ["Title update", performanceProof.titleUpdateMs],
    ["Status update", performanceProof.statusUpdateMs],
    ["Filter + group", performanceProof.filterAndGroupMs]
  ] as const;

  return (
    <section id="evidence" className="overflow-hidden rounded border border-line bg-panel text-foreground shadow-[var(--shadow-soft)]" aria-labelledby="evidence-title">
      <div className="grid lg:grid-cols-[0.7fr_1.3fr]">
        <header className="relative border-b border-line bg-panel p-5 lg:border-b-0 lg:border-r">
          <div className="absolute inset-y-0 left-0 w-1 bg-accent" />
          <p className="font-mono text-xs font-black uppercase tracking-[0.2em] text-accent">Evidence / 03</p>
          <h2 id="evidence-title" className="mt-3 text-3xl font-black tracking-tight sm:text-4xl">Claims you can rerun.</h2>
          <p className="mt-3 max-w-md text-sm leading-6 text-ink-soft">Every portfolio claim points to an executable test, generated measurement, or delivery definition. No vanity counter is used as proof.</p>

          <dl className="mt-6 grid grid-cols-3 gap-px overflow-hidden rounded border border-line bg-line">
            {measurements.map(([label, value]) => <div key={label} className="bg-panel-muted p-3"><dt className="text-[0.65rem] font-bold uppercase text-ink-soft">{label}</dt><dd className="mt-2 font-mono text-xl font-black text-accent-strong">{value}<span className="text-xs">ms</span></dd></div>)}
          </dl>
          <p className="mt-2 font-mono text-xs text-ink-soft">{performanceProof.taskCount.toLocaleString()} tasks · budget {performanceProof.budgetMs} ms</p>

          <div className="mt-6 space-y-2">
            <a href={evidenceLinks.actions} target="_blank" rel="noreferrer" className="focus-ring flex items-center justify-between rounded bg-accent px-4 py-3 text-sm font-black text-black"><span className="flex items-center gap-2"><GitBranch size={16} /> Open CI runs</span><ArrowUpRight size={16} /></a>
            <a href={evidenceLinks.architecture} target="_blank" rel="noreferrer" className="focus-ring flex items-center justify-between rounded border border-line px-4 py-3 text-sm font-black transition hover:border-accent"><span className="flex items-center gap-2"><TerminalSquare size={16} /> Inspect architecture</span><ArrowUpRight size={16} /></a>
          </div>
        </header>

        <div className="grid gap-px bg-line sm:grid-cols-2">
          {evidenceItems.map((item, index) => (
            <a key={item.id} href={item.href} target="_blank" rel="noreferrer" className="focus-ring group bg-panel p-4 transition hover:bg-panel-muted">
              <div className="flex items-start justify-between gap-3"><span className="font-mono text-xs font-black text-accent">0{index + 1} / {item.label}</span><ArrowUpRight className="transition group-hover:-translate-y-0.5 group-hover:translate-x-0.5" size={16} /></div>
              <h3 className="mt-4 text-base font-black leading-6">{item.claim}</h3>
              <p className="mt-2 text-sm leading-6 text-ink-soft">{item.result}</p>
              <p className="mt-4 flex items-center gap-2 text-xs font-bold text-ink-soft"><CheckCircle2 className="text-accent-strong" size={14} /> {item.proof}</p>
            </a>
          ))}
        </div>
      </div>
      <div className="flex flex-col gap-2 border-t border-line bg-panel-muted px-5 py-3 font-mono text-xs text-ink-soft sm:flex-row sm:items-center sm:justify-between"><span className="flex items-center gap-2"><FlaskConical size={14} /> Reproduce: npm run verify · cd backend &amp;&amp; ./mvnw verify</span><a className="font-bold text-accent-strong hover:underline" href={evidenceLinks.workflow} target="_blank" rel="noreferrer">workflow source ↗</a></div>
    </section>
  );
}
