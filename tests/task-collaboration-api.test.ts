import { afterEach, describe, expect, it, vi } from "vitest";
import { addTaskComment, assignTaskMember, fetchTaskComments } from "@/lib/task-collaboration-api";

describe("task collaboration API", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("assigns only with the task version last seen by the client", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ id: "task-id", version: 5 }))
    );
    fetchMock.mockResolvedValueOnce(Response.json({ authenticated: true, roles: ["ROLE_OPERATOR"], csrfToken: "test-csrf" }));
    vi.stubGlobal("fetch", fetchMock);

    await assignTaskMember("task-id", "member-id", 4, "assign-key");

    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining("/tasks/task-id/assignee"),
      expect.objectContaining({
        method: "PATCH",
        body: JSON.stringify({ assigneeId: "member-id", expectedVersion: 4 })
      })
    );
  });

  it("posts a trimmed comment with an idempotency key", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ id: "comment-id", body: "Acceptance checked" }), { status: 201 })
    );
    fetchMock.mockResolvedValueOnce(Response.json({ authenticated: true, roles: ["ROLE_OPERATOR"], csrfToken: "test-csrf" }));
    vi.stubGlobal("fetch", fetchMock);

    await addTaskComment("task-id", "  Acceptance checked  ", "comment-key");
    expect(new Headers(fetchMock.mock.calls[1][1].headers).get("Idempotency-Key")).toBe("comment-key");

    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining("/tasks/task-id/comments"),
      expect.objectContaining({
        method: "POST",
        headers: expect.any(Headers),
        body: JSON.stringify({ body: "Acceptance checked" })
      })
    );
  });

  it("loads the comment thread scoped to a task", async () => {
    const comments = [{ id: "comment-id", author: "Demo Owner", body: "Ready", createdAt: "now" }];
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ items: comments })));
    vi.stubGlobal("fetch", fetchMock);

    await expect(fetchTaskComments("task-id")).resolves.toEqual(comments);
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining("/tasks/task-id/comments"),
      expect.objectContaining({ headers: { Accept: "application/json" } })
    );
  });
});
