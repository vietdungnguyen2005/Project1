"use client";

import { useQuery } from "@tanstack/react-query";
import { createContext, useContext, useState, type FormEvent, type ReactNode } from "react";
import { getOperatorSession, type OperatorSession } from "@/lib/operator-session";
import { fetchCloudSession } from "@/lib/task-api";

const OperatorContext = createContext<{ session: OperatorSession | undefined; canWrite: boolean; canAdmin: boolean }>({ session: undefined, canWrite: false, canAdmin: false });

export const useOperatorAccess = () => useContext(OperatorContext);

export function OperatorProvider({ children }: { children: ReactNode }) {
  const auth = useQuery({
    queryKey: ["operator-session"], queryFn: ({ signal }) => getOperatorSession(signal),
    retry: 1, staleTime: 15_000, refetchInterval: 60_000
  });
  const workspace = useQuery({ queryKey: ["session"], queryFn: ({ signal }) => fetchCloudSession(signal), retry: 1, staleTime: 15_000 });
  const authenticated = Boolean(auth.data?.authenticated && auth.data.roles.includes("ROLE_OPERATOR"));
  const role = workspace.data?.user.role;
  const access = {
    session: auth.data,
    canWrite: authenticated && Boolean(role && role !== "viewer"),
    canAdmin: authenticated && (role === "owner" || role === "admin")
  };
  return <OperatorContext.Provider value={access}>{children}</OperatorContext.Provider>;
}

export function OperatorAccess() {
  const { session } = useOperatorAccess();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function authenticate(event?: FormEvent<HTMLFormElement>) {
    event?.preventDefault();
    const form = event?.currentTarget;
    const data = form ? new FormData(form) : null;
    setBusy(true); setError("");
    try {
      const current = await getOperatorSession();
      const response = await fetch(data ? "/api/auth/login" : "/api/auth/logout", {
        method: "POST", headers: { "X-CSRF-TOKEN": current.csrfToken },
        body: data ? new URLSearchParams({ username: String(data.get("username")), password: String(data.get("password")) }) : undefined
      });
      if (!response.ok) throw new Error(response.status === 429 ? "Too many attempts. Try again in a minute." : "Authentication failed. Please try again.");
      // Discard old identity's query/form state instead of reusing it after an account change.
      window.location.reload();
    } catch (cause) { setError(cause instanceof Error ? cause.message : "Session unavailable."); setBusy(false); }
  }

  return <section aria-label="Operator access" className="mt-5 rounded border border-line bg-panel px-4 py-3 text-sm shadow-[var(--shadow-soft)]">
    <div className="flex flex-wrap items-center justify-between gap-3">
      <p role="status" className="font-mono text-xs text-ink-soft">{session?.authenticated ? `Signed in / ${session.username}` : "Public demo / read-only · Sign in to make changes"}</p>
      {session?.authenticated
        ? <button className="focus-ring rounded border border-line px-4 py-2 font-bold" type="button" disabled={busy} onClick={() => void authenticate()}>Sign out</button>
        : <details><summary className="focus-ring cursor-pointer rounded px-2 py-2 font-bold text-accent-strong">Operator sign-in</summary>
          <form onSubmit={(event) => void authenticate(event)} className="mt-3 flex flex-wrap items-end gap-3">
            <label className="text-xs font-bold">Username<input name="username" autoComplete="username" required maxLength={100} className="focus-ring mt-1 block rounded border border-line bg-panel-muted p-2 text-foreground" /></label>
            <label className="text-xs font-bold">Password<input name="password" type="password" autoComplete="current-password" required maxLength={128} className="focus-ring mt-1 block rounded border border-line bg-panel-muted p-2 text-foreground" /></label>
            <button className="focus-ring rounded bg-foreground px-4 py-2 font-bold text-background disabled:opacity-50" type="submit" disabled={busy || !session}>Sign in</button>
          </form>
        </details>}
    </div>
    {error && <p role="alert" className="mt-3 text-red-700 dark:text-red-200">{error}</p>}
  </section>;
}
