import { afterEach, expect, it, vi } from "vitest";
import { operatorFetch } from "@/lib/operator-session";

afterEach(() => vi.unstubAllGlobals());
it("never submits a mutation when the current session is anonymous", async () => {
  const network = vi.fn().mockResolvedValue(Response.json({ authenticated: false, roles: [], csrfToken: "anonymous" }));
  vi.stubGlobal("fetch", network);
  await expect(operatorFetch("/api/workspaces/test/projects", { method: "POST" })).rejects.toThrow("sign in");
  expect(network).toHaveBeenCalledTimes(1);
});
