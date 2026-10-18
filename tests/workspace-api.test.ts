import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createWorkspaceProject,
  fetchWorkspaceOverview,
  inviteWorkspaceMember,
  updateWorkflowWipLimit
} from "@/lib/workspace-api";

describe("workspace API contract", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("loads the tenant-scoped operations overview", async () => {
    const payload = { projects: [], members: [], invitations: [] };
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(payload), {
        headers: { "Content-Type": "application/json" }
      })
    );
    vi.stubGlobal("fetch", fetchMock);

    await expect(fetchWorkspaceOverview()).resolves.toEqual(payload);
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/workspaces/00000000-0000-0000-0000-000000000100/overview",
      expect.objectContaining({ headers: { Accept: "application/json" } })
    );
  });

  it("sends invitations with an idempotency key", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ id: "invite-id", status: "PENDING" }), { status: 201 })
    );
    fetchMock.mockResolvedValueOnce(Response.json({ authenticated: true, roles: ["ROLE_OPERATOR"], csrfToken: "test-csrf" }));
    vi.stubGlobal("fetch", fetchMock);

    await inviteWorkspaceMember("engineer@example.jp", "MEMBER", "invite-key");
    expect(new Headers(fetchMock.mock.calls[1][1].headers).get("Idempotency-Key")).toBe("invite-key");

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/workspaces/00000000-0000-0000-0000-000000000100/invitations",
      expect.objectContaining({
        method: "POST",
        headers: expect.any(Headers),
        body: JSON.stringify({ email: "engineer@example.jp", role: "MEMBER" })
      })
    );
  });

  it("creates a project and its initial sprint as one retry-safe command", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ id: "project-id", key: "EVD", columns: [] }), { status: 201 })
    );
    fetchMock.mockResolvedValueOnce(Response.json({ authenticated: true, roles: ["ROLE_OPERATOR"], csrfToken: "test-csrf" }));
    vi.stubGlobal("fetch", fetchMock);
    const command = {
      name: "Evidence Delivery",
      key: "evd",
      description: "Publish proof",
      sprintName: "Evidence Sprint",
      sprintGoal: "Make claims reproducible"
    };

    await createWorkspaceProject(command, "project-key");
    expect(new Headers(fetchMock.mock.calls[1][1].headers).get("Idempotency-Key")).toBe("project-key");

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/workspaces/00000000-0000-0000-0000-000000000100/projects",
      expect.objectContaining({
        method: "POST",
        headers: expect.any(Headers),
        body: JSON.stringify({ ...command, key: "EVD" })
      })
    );
  });

  it("updates WIP with the version last seen by the operator", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ id: "column-id", wipLimit: 4, version: 3 }))
    );
    fetchMock.mockResolvedValueOnce(Response.json({ authenticated: true, roles: ["ROLE_OPERATOR"], csrfToken: "test-csrf" }));
    vi.stubGlobal("fetch", fetchMock);

    await updateWorkflowWipLimit("project-id", "column-id", 4, 2, "wip-key");
    expect(new Headers(fetchMock.mock.calls[1][1].headers).get("Idempotency-Key")).toBe("wip-key");

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/workspaces/00000000-0000-0000-0000-000000000100/projects/project-id/workflow-columns/column-id",
      expect.objectContaining({
        method: "PATCH",
        body: JSON.stringify({ wipLimit: 4, expectedVersion: 2 })
      })
    );
  });
});
