"use client";

import { useQuery } from "@tanstack/react-query";
import {
  Activity,
  Boxes,
  FlaskConical,
  Gauge,
  LayoutDashboard,
  Moon,
  Search,
  ShieldCheck,
  Sun
} from "lucide-react";
import { motion } from "framer-motion";
import { useMemo } from "react";
import { KanbanBoard } from "@/components/kanban-board";
import { CollaborationPanel } from "@/components/collaboration-panel";
import { EvidenceCenter } from "@/components/evidence-center";
import { WorkspaceOperations } from "@/components/workspace-operations";
import { useSessionCleanup } from "@/hooks/use-session-cleanup";
import { useTheme } from "@/hooks/use-theme";
import { useWorkspaceEvents } from "@/hooks/use-workspace-events";
import { fetchCloudActivities } from "@/lib/task-api";
import { useTaskStore } from "@/store/task-store";
import type { ActivityItem } from "@/lib/types";

type Metrics = {
  velocity: number;
  cycleTime: string;
  activeUsers: number;
  inpP95: string;
  lighthouse: number;
};

type AppShellProps = {
  initialActivity: ActivityItem[];
  initialMetrics: Metrics;
};

const navItems = [
  { label: "Command", icon: LayoutDashboard, href: "#workspace" },
  { label: "Board", icon: Activity, href: "#kanban-title" },
  { label: "Operations", icon: Boxes, href: "#operations" },
  { label: "Performance", icon: Gauge, href: "#performance" },
  { label: "Audit", icon: ShieldCheck, href: "#audit" },
  { label: "Evidence", icon: FlaskConical, href: "#evidence" }
];

export function AppShell({ initialActivity, initialMetrics }: AppShellProps) {
  const { isDark, toggleTheme } = useTheme();
  useSessionCleanup();
  useWorkspaceEvents();
  const tasks = useTaskStore((state) => state.tasks);
  const query = useTaskStore((state) => state.query);
  const setQuery = useTaskStore((state) => state.setQuery);
  const lastInteractionMs = useTaskStore((state) => state.lastInteractionMs);

  const { data: activities = initialActivity } = useQuery({
    queryKey: ["activities"],
    queryFn: ({ signal }) => fetchCloudActivities(signal),
    initialData: initialActivity,
    initialDataUpdatedAt: 0,
    retry: 1,
    staleTime: 15_000
  });

  const cards = useMemo(
    () => [
      {
        label: "Done points",
        value: tasks.filter((task) => task.status === "done").reduce((sum, task) => sum + task.points, 0),
        suffix: "pts"
      },
      {
        label: "Active WIP",
        value: tasks.filter((task) => task.status === "in-progress" || task.status === "review").length,
        suffix: "tasks"
      },
      { label: "Last UI update", value: lastInteractionMs, suffix: "ms" },
      { label: "Lighthouse proof", value: initialMetrics.lighthouse, suffix: "/100" }
    ],
    [initialMetrics.lighthouse, lastInteractionMs, tasks]
  );

  return (
    <div className="min-h-screen">
      <a
        href="#workspace"
        className="focus-ring fixed left-4 top-4 z-50 -translate-y-20 rounded bg-accent px-3 py-2 text-sm font-semibold text-black focus:translate-y-0"
      >
        Skip to workspace
      </a>

      <div className="grid min-h-screen grid-cols-1 lg:grid-cols-[17rem_1fr]">
        <aside className="border-b border-line bg-panel/88 px-4 py-4 backdrop-blur lg:border-b-0 lg:border-r">
          <div className="flex items-center justify-between lg:block">
            <div className="flex items-center gap-3">
              <div className="grid size-10 place-items-center rounded bg-foreground text-background">
                <span className="font-mono text-sm font-black">VC</span>
              </div>
              <div>
                <p className="text-lg font-black tracking-normal">V-Core</p>
                <p className="text-xs font-medium text-ink-soft">Agile command layer</p>
              </div>
            </div>
          </div>

          <nav aria-label="Primary navigation" className="mt-6 grid grid-cols-2 gap-2 sm:grid-cols-3 lg:block lg:space-y-2">
            {navItems.map((item, index) => {
              const Icon = item.icon;
              return (
                <a
                  key={item.label}
                  href={item.href}
                  className={`focus-ring flex w-full items-center gap-2 rounded px-3 py-2.5 text-left text-sm font-semibold transition ${
                    index === 0
                      ? "bg-foreground text-background"
                      : "text-ink-soft hover:bg-panel-muted hover:text-foreground"
                  }`}
                >
                  <Icon size={17} />
                  {item.label}
                </a>
              );
            })}
          </nav>

          <section className="mt-8 hidden rounded border border-line bg-panel-muted p-4 lg:block" aria-labelledby="health-title">
            <div className="flex items-center gap-2 text-accent-strong">
              <Activity size={17} />
              <h2 id="health-title" className="text-sm font-black text-foreground">
                Long Tab Guard
              </h2>
            </div>
            <p className="mt-3 text-sm leading-6 text-ink-soft">
              Timers, abort controllers, listeners, and cache entries are registered through a single unmount cleanup path.
            </p>
          </section>
        </aside>

        <main id="workspace" className="px-4 py-5 sm:px-6 lg:px-8">
          <header className="flex flex-col gap-4 border-b border-line pb-5 xl:flex-row xl:items-center xl:justify-between">
            <div>
              <p className="text-sm font-bold uppercase tracking-[0.14em] text-accent-strong">Sprint 24 Command Center</p>
              <h1 className="mt-2 text-3xl font-black tracking-normal sm:text-5xl">High-performance agile workspace</h1>
              <p className="mt-3 max-w-3xl text-base leading-7 text-ink-soft">
                V-Core pairs a fast collaboration surface with explicit Core Web Vitals budgets for demanding SaaS teams.
              </p>
            </div>

            <div className="flex flex-wrap items-center gap-2">
              <label className="focus-within:ring-2 focus-within:ring-accent flex h-11 min-w-64 items-center gap-2 rounded border border-line bg-panel px-3 text-ink-soft shadow-sm">
                <Search size={18} aria-hidden="true" />
                <span className="sr-only">Search workspace</span>
                <input
                  value={query}
                  onChange={(event) => setQuery(event.target.value)}
                  className="w-full bg-transparent text-sm text-foreground outline-none placeholder:text-ink-soft"
                  placeholder="Search tasks, owners, tags"
                />
              </label>
              <button
                onClick={toggleTheme}
                className="focus-ring grid size-11 place-items-center rounded border border-line bg-panel text-foreground shadow-sm transition hover:-translate-y-0.5"
                aria-label={isDark ? "Switch to light mode" : "Switch to dark mode"}
                title={isDark ? "Light mode" : "Dark mode"}
              >
                {isDark ? <Sun size={18} /> : <Moon size={18} />}
              </button>
              <div className="flex h-11 items-center rounded bg-foreground px-4 text-sm font-bold text-background shadow-sm">
                Sprint 24
              </div>
            </div>
          </header>

          <section id="performance" aria-label="Live delivery metrics" className="grid gap-3 py-5 sm:grid-cols-2 xl:grid-cols-4">
            {cards.map((card, index) => (
              <motion.article
                key={card.label}
                initial={{ opacity: 0, y: 12 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: index * 0.04, duration: 0.24 }}
                className="rounded border border-line bg-panel p-4 shadow-[var(--shadow-soft)]"
              >
                <p className="text-sm font-semibold text-ink-soft">{card.label}</p>
                <p className="mt-2 text-3xl font-black">
                  {card.value}
                  <span className="ml-1 text-base text-ink-soft">{card.suffix}</span>
                </p>
              </motion.article>
            ))}
          </section>

          <div className="space-y-5 pb-5">
            <WorkspaceOperations />
            <CollaborationPanel />
          </div>

          <div className="grid gap-5 xl:grid-cols-[1fr_22rem]">
            <KanbanBoard />

            <aside className="space-y-5">
              <section id="audit" className="rounded border border-line bg-panel p-4 shadow-[var(--shadow-soft)]" aria-labelledby="activity-title">
                <h2 id="activity-title" className="text-base font-black">Live Collaboration</h2>
                <ol className="mt-4 space-y-4">
                  {activities.map((item) => (
                    <li key={item.id} className="border-l-2 border-accent pl-3">
                      <p className="text-sm">
                        <strong>{item.actor}</strong> {item.action}{" "}
                        <span className="text-ink-soft">{item.target}</span>
                      </p>
                      <p className="mt-1 text-xs font-semibold text-ink-soft">
                        {item.time === "just now" ? item.time : `${item.time} ago`}
                      </p>
                    </li>
                  ))}
                </ol>
              </section>

              <section className="rounded border border-line bg-panel p-4 shadow-[var(--shadow-soft)]" aria-labelledby="architecture-title">
                <h2 id="architecture-title" className="text-base font-black">Performance Architecture</h2>
                <dl className="mt-4 space-y-3 text-sm">
                  <div className="flex justify-between gap-4">
                    <dt className="text-ink-soft">Input feedback budget</dt>
                    <dd className="font-black">&lt; 50ms</dd>
                  </div>
                  <div className="flex justify-between gap-4">
                    <dt className="text-ink-soft">Task cache strategy</dt>
                    <dd className="font-black">Query + Zustand</dd>
                  </div>
                  <div className="flex justify-between gap-4">
                    <dt className="text-ink-soft">Unmount cleanup</dt>
                    <dd className="font-black">Strict registry</dd>
                  </div>
                </dl>
              </section>
            </aside>
          </div>

          <div className="pt-5">
            <EvidenceCenter />
          </div>
        </main>
      </div>
    </div>
  );
}
